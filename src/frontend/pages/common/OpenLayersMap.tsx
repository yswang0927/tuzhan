import { useRef, useEffect, useImperativeHandle, type Ref } from "react";
import OLMap from 'ol/Map.js';
import View from 'ol/View.js';
import Feature from 'ol/Feature.js';
import Point from 'ol/geom/Point.js';
import LineString from 'ol/geom/LineString.js';
import Polygon from 'ol/geom/Polygon.js';
import VectorLayer from 'ol/layer/Vector.js';
import VectorSource from 'ol/source/Vector.js';
import Attribution from 'ol/control/Attribution.js';
import { defaults as defaultControls } from 'ol/control/defaults.js';
import Draw from 'ol/interaction/Draw.js';
import Overlay from 'ol/Overlay.js';
import { fromLonLat, toLonLat } from 'ol/proj.js';
import { getVectorContext } from 'ol/render.js';
import { unByKey } from 'ol/Observable.js';
import { easeOut } from 'ol/easing.js';
import { Style, Stroke, Fill, Circle as CircleStyle, RegularShape } from 'ol/style.js';
import type { Geometry } from 'ol/geom.js';
import { apply } from 'ol-mapbox-style';

import {formatDate} from "@/utils";    

import 'ol/ol.css';

// ==================== 对外数据类型 ====================
export interface PointData {
    objectId: string;
    eventTime: number; // 时间戳(秒)
    lon: number;       // 经度
    lat: number;       // 纬度
    [key: string]: any;
}

export interface LineOptions {
    lineColor?: string;
    lineWidth?: number;
    showDirection?: boolean;
}

export interface PolygonOptions {
    lineColor?: string;
    lineWidth?: number;
    fillColor?: string;
}

export interface LineData {
    objectId: string;
    points: PointData[];
    options?: LineOptions;
}

export interface PolygonData {
    id: string;
    coordinates: number[][]; // [[lon,lat],...]
    options?: PolygonOptions;
}

// ==================== 交互绘制类型 ====================
/** 可交互绘制的几何类型 */
export type DrawType = 'Point' | 'LineString' | 'Polygon' | 'Circle';

export interface DrawOptions {
    lineColor?: string;
    lineWidth?: number;
    fillColor?: string;
    /** 绘制完成后是否保留图形在地图上, 默认 true */
    keepResult?: boolean;
    /** 绘制完成后触发的一次性回调, 优先级高于 onDrawEnd 属性 */
    onFinish?: (result: DrawResult) => void;
}

export interface DrawResult {
    type: DrawType;
    /** 经纬度坐标:
     *  - Point:      [lon, lat]
     *  - LineString: [[lon,lat], ...]
     *  - Polygon:    [[lon,lat], ...] (外环, 已闭合)
     *  - Circle:     圆心 [lon, lat], 另见 radius(米)
     */
    coordinates: any;
    /** Circle 类型时的半径(米) */
    radius?: number;
}

/** 对外暴露的地图操作方法 */
export interface OpenLayersMapHandle {
    /** 绘制高亮动画点 */
    drawPoint: (point: PointData) => void;
    /** 绘制带方向箭头的轨迹线 */
    drawLine: (points: PointData[], options?: LineOptions) => void;
    /** 绘制多边形区域 */
    drawPolygon: (id: string, coordinates: number[][], options?: PolygonOptions) => void;
    /** 聚焦某个点 */
    focusPoint: (point: PointData) => void;
    /** 聚焦某条线(一组点) */
    focusLine: (points: PointData[]) => void;
    /** 聚焦某个多边形区域 */
    focusPolygon: (id: string, coordinates?: number[][]) => void;
    /** 根据一组点计算外接矩形, 并将该区域移动到地图中间 */
    fitViewport: (points: PointData[]) => void;
    /** 开启交互绘制, 用户在地图上用鼠标绘制几何图形, 完成后回调返回数据 */
    startDraw: (type: DrawType, options?: DrawOptions) => void;
    /** 取消当前正在进行的交互绘制 */
    cancelDraw: () => void;
    /** 清除所有绘制内容 */
    clearAll: () => void;
}

export interface OpenLayersMapProps {
    ref?: Ref<OpenLayersMapHandle>;
    onPointClick?: (pointData: PointData) => void;
    onLineClick?: (lineData: LineData) => void;
    onPolygonClick?: (polygonData: PolygonData) => void;
    /** 任意一次交互绘制完成后触发(与 startDraw 的 onFinish 同时存在时, 二者都会被调用) */
    onDrawEnd?: (result: DrawResult) => void;
}

// ==================== 默认样式常量 ====================
const DEFAULT_LINE_COLOR = '#1890ff';
const DEFAULT_LINE_WIDTH = 4;
const DEFAULT_POLYGON_LINE_COLOR = '#A94E22';
const DEFAULT_POLYGON_FILL_COLOR = 'rgba(196, 97, 47, 0.3)';
const POINT_COLOR = '#f5222d';
const HIGHLIGHT_DURATION = 2500; // 高亮动画周期(ms)

// Feature 上挂载的自定义属性 key
const KIND = 'kind';         // 'point' | 'line' | 'polygon' | 'lineVertex'
const RAW_DATA = 'rawData';  // 原始数据, 用于点击回调

export function OpenLayersMap({ ref, onPointClick, onLineClick, onPolygonClick, onDrawEnd }: OpenLayersMapProps = {}) {
    const mapDomRef = useRef<HTMLDivElement | null>(null);
    const mapRef = useRef<OLMap | null>(null);

    // 三类要素分别使用独立的矢量图层, 便于管理与聚焦
    const pointSourceRef = useRef(new VectorSource());
    const lineSourceRef = useRef(new VectorSource());
    const polygonSourceRef = useRef(new VectorSource());
    const pointLayerRef = useRef<VectorLayer | null>(null);

    // 交互绘制: 临时图层 + 当前 Draw 交互
    const drawSourceRef = useRef(new VectorSource());
    const drawInteractionRef = useRef<Draw | null>(null);

    // 弹窗 Overlay
    const popupRef = useRef<HTMLDivElement | null>(null);
    const overlayRef = useRef<Overlay | null>(null);

    // 记录正在做高亮动画的点 feature -> 其 postrender 监听 key, 防止重复注册
    const animatingRef = useRef<Map<Feature, any>>(new Map());

    // 用最新的回调引用, 避免闭包捕获旧的 props
    const callbacksRef = useRef({ onPointClick, onLineClick, onPolygonClick, onDrawEnd });
    callbacksRef.current = { onPointClick, onLineClick, onPolygonClick, onDrawEnd };

    // 基础点样式(静态显示)
    const buildPointStyle = () => new Style({
        image: new CircleStyle({
            radius: 6,
            fill: new Fill({ color: POINT_COLOR }),
            stroke: new Stroke({ color: '#ffffff', width: 2 }),
        }),
    });

    // 线样式: 主线 + 沿线方向箭头
    const buildLineStyle = (feature: Feature<Geometry>, options: LineOptions) => {
        const color = options.lineColor || DEFAULT_LINE_COLOR;
        const width = options.lineWidth || DEFAULT_LINE_WIDTH;
        const styles: Style[] = [
            new Style({ stroke: new Stroke({ color, width, lineCap: 'round', lineJoin: 'round' }) }),
        ];

        if (options.showDirection !== false) {
            const geom = feature.getGeometry() as LineString;
            geom.forEachSegment((start, end) => {
                const dx = end[0] - start[0];
                const dy = end[1] - start[1];
                const rotation = Math.atan2(dy, dx);
                const mid = [(start[0] + end[0]) / 2, (start[1] + end[1]) / 2];
                styles.push(new Style({
                    geometry: new Point(mid),
                    image: new RegularShape({
                        points: 3,
                        radius: 7,
                        stroke: new Stroke({ color: color, width: 1 }),
                        fill: new Fill({ color: '#fff' }),
                        rotateWithView: true,
                        // RegularShape 三角形默认朝上, 需旋转对齐线方向
                        rotation: -rotation + Math.PI / 2,
                    }),
                }));
            });
        }
        return styles;
    };

    // 给点 feature 附加脉冲高亮动画: 在图层 postrender 时绘制逐渐扩散并淡出的圆环
    const startHighlight = (feature: Feature<Geometry>) => {
        const layer = pointLayerRef.current;
        const map = mapRef.current;
        if (!layer || !map) return;
        if (animatingRef.current.has(feature)) return;

        let start = Date.now();
        const listenerKey = layer.on('postrender', (event: any) => {
            const geom = feature.getGeometry() as Point;
            if (!geom) return;

            const elapsed = Date.now() - start;
            const ratio = (elapsed % HIGHLIGHT_DURATION) / HIGHLIGHT_DURATION;
            if (ratio === 0) start = Date.now();

            const eased = easeOut(ratio);
            const radius = 6 + eased * 22;
            const opacity = easeOut(1 - ratio);

            const vectorContext = getVectorContext(event);
            vectorContext.setStyle(new Style({
                image: new CircleStyle({
                    radius,
                    stroke: new Stroke({
                        color: `rgba(245, 34, 45, ${opacity})`,
                        width: 3 + eased,
                    }),
                }),
            }));
            vectorContext.drawGeometry(geom);
            // 持续触发重绘形成动画
            map.render();
        });
        animatingRef.current.set(feature, listenerKey);
    };

    // 将一组点按 objectId 分组, 并按 eventTime 升序排序
    const groupPointsByObject = (points: PointData[]): LineData[] => {
        const groups = new Map<string, PointData[]>();
        for (const p of points) {
            if (p.lon === undefined || p.lat === undefined) continue;
            const arr = groups.get(p.objectId) || [];
            arr.push(p);
            groups.set(p.objectId, arr);
        }
        const result: LineData[] = [];
        groups.forEach((arr, objectId) => {
            arr.sort((a, b) => (a.eventTime || 0) - (b.eventTime || 0));
            result.push({ objectId, points: arr });
        });
        return result;
    };

    // 两个经纬度点之间的球面距离(米)
    const haversine = (a: number[], b: number[]): number => {
        const R = 6378137;
        const toRad = (d: number) => (d * Math.PI) / 180;
        const dLat = toRad(b[1] - a[1]);
        const dLon = toRad(b[0] - a[0]);
        const lat1 = toRad(a[1]);
        const lat2 = toRad(b[1]);
        const h = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
    };

    // 将地图视野适配到给定 extent
    const fitExtent = (extent: number[] | undefined | null) => {
        const map = mapRef.current;
        if (!map || !extent) return;
        // 空 source 返回的 extent 为 [Infinity, ...], 需要防御
        if (!extent.every((n) => Number.isFinite(n))) return;
        map.getView().fit(extent, {
            padding: [80, 80, 80, 80],
            duration: 500,
            maxZoom: 16,
        });
    };

    // 根据一组点计算外接矩形并将该区域移动到地图中间(focusLine / fitViewport 共用)
    const fitPoints = (points: PointData[]) => {
        const coords = (points || [])
            .filter((p) => p.lon !== undefined && p.lat !== undefined)
            .map((p) => fromLonLat([p.lon, p.lat]));
        if (coords.length === 0) return;
        // 累计外接矩形 [minX, minY, maxX, maxY]
        let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
        for (const [x, y] of coords) {
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
        }
        // fitExtent 内部已处理居中、padding、无效值防御及单点(退化)情况
        fitExtent([minX, minY, maxX, maxY]);
    };

    // 将绘制得到的几何图形转换为经纬度结果
    const geometryToResult = (type: DrawType, geom: any): DrawResult => {
        if (type === 'Point') {
            return { type, coordinates: toLonLat(geom.getCoordinates()) };
        }
        if (type === 'LineString') {
            return { type, coordinates: geom.getCoordinates().map((c: number[]) => toLonLat(c)) };
        }
        if (type === 'Polygon') {
            // 取外环, getCoordinates() 返回的环已自动闭合
            const ring = geom.getCoordinates()[0] || [];
            return { type, coordinates: ring.map((c: number[]) => toLonLat(c)) };
        }
        if (type === 'Circle') {
            const center = geom.getCenter();
            const radiusMapUnits = geom.getRadius();
            // 用圆心到圆上一点的经纬度距离近似计算半径(米)
            const edge = [center[0] + radiusMapUnits, center[1]];
            const c1 = toLonLat(center);
            const c2 = toLonLat(edge);
            const radius = haversine(c1, c2);
            return { type, coordinates: c1, radius };
        }
        return { type, coordinates: geom.getCoordinates?.() };
    };

    useImperativeHandle(ref, (): OpenLayersMapHandle => ({
        drawPoint: (point: PointData) => {
            if (point.lon === undefined || point.lat === undefined) return;
            const feature = new Feature({
                geometry: new Point(fromLonLat([point.lon, point.lat])),
            });
            feature.set(KIND, 'point');
            feature.set(RAW_DATA, point);
            feature.setStyle(buildPointStyle());
            pointSourceRef.current.addFeature(feature);
            startHighlight(feature);
        },

        drawLine: (points: PointData[], options: LineOptions = {}) => {
            if (!points || points.length == 0) return;
            const coords = points.map((p) => fromLonLat([p.lon, p.lat]));
            const feature = new Feature({ geometry: new LineString(coords) });
            feature.set(KIND, 'line');
            feature.set(RAW_DATA, options);
            feature.setStyle(buildLineStyle(feature, options));
            lineSourceRef.current.addFeature(feature);

            // 在每个拐点绘制白色背景圆点
            for (let i = 0, len = points.length; i < len; i++) {
                const p = points[i];
                 const vertexFeature = new Feature({
                    geometry: new Point(fromLonLat([p.lon, p.lat])),
                });
                vertexFeature.set(KIND, 'lineVertex');
                vertexFeature.set(RAW_DATA, p);
                vertexFeature.setStyle(new Style({
                    image: new CircleStyle({
                        radius: 8,
                        fill: new Fill({ 
                            color: i === 0 ? '#52c41a' : ( i === len - 1 ? '#f5222d' : '#fff') 
                        }),
                        stroke: new Stroke({ 
                            color: options.lineColor || DEFAULT_LINE_COLOR, 
                            width: 2 
                        })
                    })
                }));
                lineSourceRef.current.addFeature(vertexFeature);
            }
        },

        drawPolygon: (id: string, coordinates: number[][], options: PolygonOptions = {}) => {
            if (!coordinates || coordinates.length < 3) return;
            const ring = coordinates.map((c) => fromLonLat([c[0], c[1]]));
            const feature = new Feature({ geometry: new Polygon([ring]) });
            feature.set(KIND, 'polygon');
            feature.set(RAW_DATA, { id, coordinates, options } as PolygonData);
            feature.setStyle(new Style({
                stroke: new Stroke({
                    color: options.lineColor || DEFAULT_POLYGON_LINE_COLOR,
                    width: options.lineWidth || 2,
                }),
                fill: new Fill({ color: options.fillColor || DEFAULT_POLYGON_FILL_COLOR }),
            }));
            polygonSourceRef.current.addFeature(feature);
        },

        focusPoint: (point: PointData) => {
            if (point.lon === undefined || point.lat === undefined) return;
            const map = mapRef.current;
            if (!map) return;
            map.getView().animate({
                center: fromLonLat([point.lon, point.lat]),
                zoom: Math.max(map.getView().getZoom() || 0, 14),
                duration: 500,
            });
        },

        focusLine: (points: PointData[]) => {
            fitPoints(points);
        },

        focusPolygon: (id: string, coordinates?: number[][]) => {
            if (coordinates && coordinates.length >= 3) {
                const ring = coordinates.map((c) => fromLonLat([c[0], c[1]]));
                fitExtent(new Polygon([ring]).getExtent());
                return;
            }
            // 未传坐标时, 尝试根据已绘制的 id 查找
            const feature = polygonSourceRef.current.getFeatures()
                .find((f) => (f.get(RAW_DATA) as PolygonData)?.id === id);
            if (feature) {
                fitExtent(feature.getGeometry()?.getExtent());
            }
        },

        fitViewport: (points: PointData[]) => {
            fitPoints(points);
        },

        startDraw: (type: DrawType, options: DrawOptions = {}) => {
            const map = mapRef.current;
            if (!map) return;

            // 先结束上一次未完成的绘制
            if (drawInteractionRef.current) {
                map.removeInteraction(drawInteractionRef.current);
                drawInteractionRef.current = null;
            }
            drawSourceRef.current.clear();

            const color = options.lineColor || DEFAULT_POLYGON_LINE_COLOR;
            const width = options.lineWidth || 2;
            const fill = options.fillColor || DEFAULT_POLYGON_FILL_COLOR;

            const draw = new Draw({
                source: drawSourceRef.current,
                type,
                // 绘制过程中的动态样式
                style: new Style({
                    stroke: new Stroke({ color, width, lineDash: [8, 6] }),
                    fill: new Fill({ color: fill }),
                    image: new CircleStyle({
                        radius: 5,
                        fill: new Fill({ color }),
                        stroke: new Stroke({ color: '#ffffff', width: 2 }),
                    }),
                }),
            });

            draw.on('drawend', (evt: any) => {
                const geom = evt.feature.getGeometry();
                const result = geometryToResult(type, geom);

                // 移除交互, 退出绘制状态
                map.removeInteraction(draw);
                drawInteractionRef.current = null;

                // 是否保留绘制结果
                if (options.keepResult === false) {
                    // drawend 时 feature 尚未加入 source, 延迟清理即可
                    setTimeout(() => drawSourceRef.current.clear(), 0);
                }

                options.onFinish?.(result);
                callbacksRef.current.onDrawEnd?.(result);
            });

            map.addInteraction(draw);
            drawInteractionRef.current = draw;
        },

        cancelDraw: () => {
            const map = mapRef.current;
            if (map && drawInteractionRef.current) {
                map.removeInteraction(drawInteractionRef.current);
                drawInteractionRef.current = null;
            }
            drawSourceRef.current.clear();
        },

        clearAll: () => {
            // 停止全部高亮动画
            animatingRef.current.forEach((key) => unByKey(key));
            animatingRef.current.clear();
            pointSourceRef.current.clear();
            lineSourceRef.current.clear();
            polygonSourceRef.current.clear();
            drawSourceRef.current.clear();
            overlayRef.current?.setPosition(undefined);
            popupRef.current && (popupRef.current.innerHTML = '');
        },
    }), []);

    useEffect(() => {
        if (!mapDomRef.current) {
            return;
        }

        const key = 'Mncog8HkQPxDHlnvb2kI';
        const styleJson = `https://api.maptiler.com/maps/base-v4/style.json?key=${key}`;

        const attribution = new Attribution({
            collapsible: false,
        });

        // 三个矢量图层, z-index: 多边形 < 线 < 点
        const polygonLayer = new VectorLayer({ source: polygonSourceRef.current, zIndex: 10 });
        const lineLayer = new VectorLayer({ source: lineSourceRef.current, zIndex: 20 });
        const pointLayer = new VectorLayer({ source: pointSourceRef.current, zIndex: 30 });
        pointLayerRef.current = pointLayer;
        // 交互绘制的临时图层, 置于最上层
        const drawLayer = new VectorLayer({ source: drawSourceRef.current, zIndex: 40 });

        const map = new OLMap({
            target: mapDomRef.current,
            controls: defaultControls({ attribution: false }).extend([attribution]),
            layers: [polygonLayer, lineLayer, pointLayer, drawLayer],
            view: new View({
                constrainResolution: true,
                center: fromLonLat([0, 0]),
                zoom: 1,
            }),
        });
        mapRef.current = map;

        apply(map, styleJson);

        // 弹窗 Overlay
        const overlay = new Overlay({
            element: popupRef.current!,
            autoPan: true,
            positioning: 'bottom-center',
            offset: [0, -20],
        });
        map.addOverlay(overlay);
        overlayRef.current = overlay;

        // 统一的要素点击分发
        const clickKey = map.on('singleclick', (evt) => {
            // 交互绘制进行时, 不触发要素点击回调
            if (drawInteractionRef.current) {
                return;
            }

            const feature = map.forEachFeatureAtPixel(evt.pixel, (f) => f as Feature<Geometry>);
            if (!feature) {
                overlayRef.current?.setPosition(undefined);
                return;
            }

            const kind = feature.get(KIND);
            const raw = feature.get(RAW_DATA);
            const cbs = callbacksRef.current;

            if (kind === 'lineVertex') {
                const p = raw as PointData;
                const overlay = overlayRef.current;
                const popup = popupRef.current;
                if (overlay && popup) {
                    popup.innerHTML = `
                        <div style="font-size:12px;line-height:1.6;">
                            <div><b>objectId:</b> ${p.objectId}</div>
                            <div><b>eventTime:</b> ${formatDate(p.eventTime)}</div>
                            <div><b>lon:</b> ${p.lon}</div>
                            <div><b>lat:</b> ${p.lat}</div>
                        </div>`;
                    overlay.setPosition((feature.getGeometry() as Point).getCoordinates());
                }
            } else {
                overlayRef.current?.setPosition(undefined);

                if (kind === 'point') {
                    const p = raw as PointData;
                    const overlay = overlayRef.current;
                    const popup = popupRef.current;
                    if (overlay && popup) {
                        popup.innerHTML = `
                            <div style="font-size:12px;line-height:1.6;">
                                <div><b>objectId:</b> ${p.objectId}</div>
                                <div><b>eventTime:</b> ${formatDate(p.eventTime)}</div>
                                <div><b>lon:</b> ${p.lon}</div>
                                <div><b>lat:</b> ${p.lat}</div>
                            </div>`;
                        overlay.setPosition((feature.getGeometry() as Point).getCoordinates());
                    }

                    cbs.onPointClick?.(raw as PointData);
                }
                else if (kind === 'line') {
                    cbs.onLineClick?.(raw as LineData);
                }
                else if (kind === 'polygon') {
                    cbs.onPolygonClick?.(raw as PolygonData);
                }
            }
        });

        // 悬停到要素时切换指针样式
        const moveKey = map.on('pointermove', (evt) => {
            if (evt.dragging) return;
            const hit = map.hasFeatureAtPixel(evt.pixel);
            map.getTargetElement().style.cursor = hit ? 'pointer' : '';
        });

        return () => {
            unByKey(clickKey);
            unByKey(moveKey);
            animatingRef.current.forEach((k) => unByKey(k));
            animatingRef.current.clear();
            if (drawInteractionRef.current) {
                map.removeInteraction(drawInteractionRef.current);
                drawInteractionRef.current = null;
            }
            overlayRef.current = null;
            map.setTarget(undefined);
            mapRef.current = null;
            pointLayerRef.current = null;
        };
    }, []);

    return (
        <div className="relative w-full h-full">
            <div ref={mapDomRef} className="absolute inset-0"></div>
            <div ref={popupRef} className="ol-popup"></div>
        </div>
    );
}

