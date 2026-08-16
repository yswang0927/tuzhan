import { useEffect, useRef, useState, useCallback } from "react";
import { Button, ButtonGroup } from "@blueprintjs/core";
import { useL10n } from "@/l10n";
import { useTrajectoryStore } from './store';

import "./map.css";

function escapeHtml(str: string | null) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

export default function GeoMap() {
    const { t } = useL10n();
    const mapDomRef = useRef(null);
    const mapRef = useRef(null);
    const mouseToolRef = useRef(null);
    const polyEditorRef = useRef(null);
    const [isDrawing, setIsDrawing] = useState(false);

    const trajectoryData = useTrajectoryStore(state => state.trajectoryData);
    const AMapRef = useRef<any>(null);
    const overlaysRef = useRef<any[]>([]);
    const [mapReady, setMapReady] = useState(false);

    const drawTrajectory = useCallback(() => {
        const map = mapRef.current as any;
        const AMap = AMapRef.current;
        if (!map || !AMap) return;

        // --- 清除旧的轨迹 ---
        if (overlaysRef.current.length > 0) {
            map.remove(overlaysRef.current);
            overlaysRef.current = [];
        }

        if (!trajectoryData || trajectoryData.length === 0) return;

        // --- 将轨迹数据转换为 AMap 坐标路径 [lng, lat] ---
        const path = trajectoryData.filter(p => p.lon !== undefined && p.lat !== undefined).map(p => [p.lon, p.lat]);
        if (path.length === 0) return;

        // --- 绘制轨迹折线 ---
        const polyline = new AMap.Polyline({
            path: path,
            isOutline: true,
            outlineColor: '#e6f7ff',
            borderWeight: 2,
            strokeColor: '#1890ff',
            strokeOpacity: 1,
            strokeWeight: 6,
            strokeStyle: 'solid',
            lineJoin: 'round',
            lineCap: 'round',
            showDir: true,       // 显示方向箭头
            zIndex: 50,
        });
        map.add(polyline);
        overlaysRef.current.push(polyline);

        // --- 在每个轨迹点添加标记 ---
        const markers: any = [];
        const infoWindow = new AMap.InfoWindow({
            isCustom: true,
            offset: new AMap.Pixel(0, -30),
        });

        trajectoryData.forEach(function (point, index) {
            if (point.lon === undefined || point.lat === undefined) return;

            var isStart = index === 0;
            var isEnd = index === trajectoryData.length - 1;
            var markerClass = isStart ? 'start' : (isEnd ? 'end' : 'way');
            var label = isStart ? '起' : (isEnd ? '终' : String(index));

            var marker = new AMap.Marker({
                position: [point.lon, point.lat],
                content: '<div class="traj-marker ' + markerClass + '">' + label + '</div>',
                offset: new AMap.Pixel(-14, -14),
                zIndex: isStart || isEnd ? 120 : 110,
            });

            // 点击标记弹出信息窗体
            marker.on('click', function () {
                var html = ''
                    + '<div class="traj-info-window">'
                    + '  <div class="iw-header">'
                    + '    <span>' + (isStart ? '🟢' : (isEnd ? '🔴' : '🔵')) + '</span>'
                    + '    <span>' + escapeHtml(point.objectId || ('轨迹点 ' + index)) + '</span>'
                    + '  </div>'
                    + '  <div class="iw-body">'
                    + '    <div class="iw-row"><span class="label">序号</span><span class="val">' + (index + 1) + ' / ' + trajectoryData.length + '</span></div>'
                    + '    <div class="iw-row"><span class="label">时间</span><span class="val">' + escapeHtml(point.eventTime || '-') + '</span></div>'
                    + '    <div class="iw-row"><span class="label">经度</span><span class="val">' + point.lon.toFixed(6) + '</span></div>'
                    + '    <div class="iw-row"><span class="label">纬度</span><span class="val">' + point.lat.toFixed(6) + '</span></div>'
                    + '  </div>'
                    + '  <div class="iw-arrow"></div>'
                    + '</div>';
                infoWindow.setContent(html);
                infoWindow.open(map, marker.getPosition());
            });

            markers.push(marker);
        });
        map.add(markers);
        overlaysRef.current.push(...markers);

        // --- 自动调整视野，展示全部轨迹 ---
        map.setFitView([polyline].concat(markers), false, [200, 200, 200, 200]);
    }, [trajectoryData]);

    useEffect(() => {
        if (mapReady) {
            drawTrajectory();
        }
    }, [trajectoryData, mapReady, drawTrajectory]);

    const toggleDrawPolygon = () => {
        const mouseTool = mouseToolRef.current;
        if (!mouseTool) return;

        if (isDrawing) {
            mouseTool.close(true);
            setIsDrawing(false);
        } else {
            mouseTool.polygon({
                fillColor: '#C4612F',
                fillOpacity: 0.3,
                strokeColor: '#A94E22',
                strokeWeight: 2,
                strokeStyle: 'solid',
            });
            setIsDrawing(true);
        }
    };

    useEffect(() => {
        (window as any).AMapLoader.load({
            key: "d39920f829c920ba5e6d14abbd52e88f",
            version: "2.0",
            plugins: ['AMap.Scale', 'AMap.ToolBar', 'AMap.MouseTool', 'AMap.PolygonEditor']
        })
            .then((AMap: any) => {
                const map = mapRef.current = new AMap.Map(mapDomRef.current, {
                    viewMode: '2D',
                    zoom: 13,
                });
                AMapRef.current = AMap;

                map.addControl(new AMap.Scale());
                map.addControl(new AMap.ToolBar({ position: 'RT' }));

                const mouseTool = new AMap.MouseTool(map);
                mouseToolRef.current = mouseTool;

                mouseTool.on('draw', (event: any) => {
                    setIsDrawing(false);
                    const polygon = event.obj;
                    const path = polygon.getPath();
                    const coordinates = path.map((lngLat: any) => ({
                        lng: lngLat.getLng(),
                        lat: lngLat.getLat()
                    }));
                    console.log('绘制完成的多边形顶点坐标:', coordinates);

                    // 进入编辑状态
                    const polyEditor = new AMap.PolygonEditor(map, polygon);
                    polyEditorRef.current = polyEditor;
                    polyEditor.open();

                    // 监听编辑事件
                    polyEditor.on('adjust', () => {
                        const updatedPath = polygon.getPath();
                        const updatedCoordinates = updatedPath.map((lngLat: any) => ({
                            lng: lngLat.getLng(),
                            lat: lngLat.getLat()
                        }));
                        console.log('多边形顶点已调整:', updatedCoordinates);
                    });
                });

                setMapReady(true);

            }).catch((e: any) => {
                console.error(e);
            });
    }, []);

    return (
        <div className="relative w-full h-full">
            <div ref={mapDomRef} className="absolute inset-0"></div>
            <div className="absolute map-toolbar" style={{ top: "10px", left: "50%", transform: "translateX(-50%)", zIndex: 100 }}>
                <ButtonGroup>
                    <Button className="absolute" icon="polygon-filter" intent="warning"
                        onClick={toggleDrawPolygon}
                        style={{
                            fontWeight: isDrawing ? 500 : 400,
                        }}
                        text={isDrawing ? '完成绘制' : '绘制多边形'}
                    />
                </ButtonGroup>
            </div>

        </div>
    );
}