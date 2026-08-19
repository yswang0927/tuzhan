import React, { useEffect, useRef, useState, useCallback } from "react";
import Draggable from "react-draggable";
import { Button, ButtonGroup } from "@blueprintjs/core";

import { LogoIcon } from "@/utils/icons";
import { OpenLayersMap, type OpenLayersMapHandle, type PointData, type DrawResult } from "@/pages/common/OpenLayersMap";
import { useL10n } from "@/l10n";
import { LayoutResizer } from "@/utils";
import { useHomeStore } from "./store";
import { MainMenuPanelStack, BottomAreaContent } from "./SubMenus";
import { useMenusConfig } from "./menusConfig";

import "./style.css";

export default function Home() {
    const { t } = useL10n();
    const { mainMenu, setMainMenu } = useHomeStore();
    const setMapApi = useHomeStore(state => state.setMapApi);

    // 稳定的回调 ref：只在挂载/卸载时各调一次，避免内联函数每次渲染都触发 setMapApi 造成死循环
    const mapCallbackRef = useCallback((handle: OpenLayersMapHandle | null) => {
        mapRef.current = handle;
        setMapApi(handle); // 卸载时 handle 为 null，自动清理
    }, [setMapApi]);
    const menusConfig = useMenusConfig();
    const resizerDomRef = useRef<HTMLDivElement | null>(null);
    const submenuContainerRef = useRef<HTMLDivElement | null>(null);
    const mapRef = useRef<OpenLayersMapHandle>(null);

    // ==================== 地图 API 快速演示 ====================
    // 北京附近的一组模拟轨迹点(经度, 纬度, 时间戳秒)
    const demoTrack: PointData[] = [
        { objectId: "car-001", eventTime: 1700000000, lon: 116.397, lat: 39.909 },
        { objectId: "car-001", eventTime: 1700000600, lon: 116.410, lat: 39.915 },
        { objectId: "car-001", eventTime: 1700001200, lon: 116.425, lat: 39.918 },
        { objectId: "car-001", eventTime: 1700001800, lon: 116.438, lat: 39.912 },
        { objectId: "car-001", eventTime: 1700002400, lon: 116.450, lat: 39.905 },
    ];
    const demoPolygon: number[][] = [
        [116.39, 39.92], [116.46, 39.92], [116.46, 39.89], [116.39, 39.89],
    ];

    const handleDrawPoint = () => {
        const p: PointData = { objectId: "poi-1", eventTime: 1700000000, lon: 116.397, lat: 39.909 };
        mapRef.current?.drawPoint(p);
        mapRef.current?.focusPoint(p);
    };

    const handleDrawLines = () => {
        mapRef.current?.drawLines(demoTrack, { lineColor: "#1890ff", lineWidth: 4, showDirection: true });
        mapRef.current?.focusLine(demoTrack);
    };

    const handleDrawPolygon = () => {
        mapRef.current?.drawPolygon("area-1", demoPolygon, {
            lineColor: "#A94E22", lineWidth: 2, fillColor: "rgba(196, 97, 47, 0.3)",
        });
        mapRef.current?.focusPolygon("area-1", demoPolygon);
    };

    const handleClear = () => mapRef.current?.clearAll();

    // ==================== 交互绘制演示 ====================
    const [drawing, setDrawing] = useState(false);

    const startDrawPolygon = () => {
        setDrawing(true);
        mapRef.current?.startDraw("Polygon", {
            lineColor: "#A94E22",
            fillColor: "rgba(196, 97, 47, 0.3)",
            onFinish: (result: DrawResult) => {
                setDrawing(false);
                console.log("交互绘制多边形完成:", result.coordinates);
                // 演示: 拿到坐标后可回写为正式图形
                mapRef.current?.drawPolygon(`draw-${Date.now()}`, result.coordinates);
            },
        });
    };

    const startDrawRoute = () => {
        setDrawing(true);
        mapRef.current?.startDraw("LineString", {
            lineColor: "#1890ff",
            lineWidth: 4,
            onFinish: (result: DrawResult) => {
                setDrawing(false);
                console.log("交互绘制路线完成:", result.coordinates);
            },
        });
    };

    const cancelDraw = () => {
        setDrawing(false);
        mapRef.current?.cancelDraw();
    };

    useEffect(() => {
        if (!resizerDomRef.current) {
            return;
        }
        const layoutResizer = new LayoutResizer({
            key: "resizer2", // 如果配置了,则可以自动记忆
            trigger: resizerDomRef.current,
            target: resizerDomRef.current?.parentElement
        });
        return () => {
            layoutResizer && layoutResizer.destroy();
        };
    }, []);

    return (
        <div className="map-app-panel">
            <div className="map-app-header">
                <div className="flex h-full flex-1 nowrap">
                    <div className="map-app-header-icon h-full">
                        <LogoIcon />
                    </div>
                    <div className="map-app-header-title">{t('时空情报平台')}</div>
                </div>

                <div className="flex-2 flex items-center justify-center">
                    {/* 主菜单 */}
                    <div className="flex items-center gap-lg">
                        {menusConfig.map((menu, index) => (
                            <React.Fragment key={menu.id}>
                                <Button 
                                    icon={menu.icon} 
                                    variant="minimal" 
                                    text={menu.name} 
                                    active={mainMenu === menu.id} 
                                    onClick={() => setMainMenu(menu.id)} 
                                />
                                {index < menusConfig.length - 1 && (
                                    <span style={{ lineHeight: 1, fontSize: 0 }}><span className="menu-dot"></span></span>
                                )}
                            </React.Fragment>
                        ))}
                    </div>
                </div>

                <div className="flex-1">
                    {/* 预留 */}
                </div>
            </div>

            <div className="map-app-main relative">
                <div className="absolute inset-0">
                    <OpenLayersMap
                        ref={mapCallbackRef}
                        onPointClick={(d) => console.log("点击点:", d)}
                        onLineClick={(d) => console.log("点击线:", d)}
                        onPolygonClick={(d) => console.log("点击多边形:", d)}
                        onDrawEnd={(d) => console.log("绘制结束(全局):", d)}
                    />

                    {/* 地图 API 快速演示工具条 */}
                    <div className="absolute flex flex-col gap-sm items-end" style={{ top: "1rem", right: "1rem", zIndex: 20 }}>
                        <ButtonGroup>
                            <Button icon="map-marker" text={t("绘制点")} onClick={handleDrawPoint} />
                            <Button icon="trending-up" text={t("绘制轨迹线")} onClick={handleDrawLines} />
                            <Button icon="polygon-filter" text={t("绘制区域")} onClick={handleDrawPolygon} />
                            <Button icon="trash" intent="danger" text={t("清除")} onClick={handleClear} />
                        </ButtonGroup>
                        <ButtonGroup>
                            <Button icon="draw" intent="primary" text={t("交互画区域")} onClick={startDrawPolygon} disabled={drawing} />
                            <Button icon="route" intent="primary" text={t("交互画路线")} onClick={startDrawRoute} disabled={drawing} />
                            {drawing && <Button icon="cross" text={t("取消")} onClick={cancelDraw} />}
                        </ButtonGroup>
                        {drawing && (
                            <div className="bp6-tag bp6-intent-primary" style={{ padding: "4px 10px" }}>
                                {t("请在地图上单击绘制，双击结束")}
                            </div>
                        )}
                    </div>

                    <Draggable handle=".bp6-panel-stack2-header" nodeRef={submenuContainerRef} bounds={{ left: 0, top: 0 }}>
                        <div ref={submenuContainerRef} className="absolute" style={{ left: "1rem", top: "1rem", bottom: "2rem", minWidth: "220px", zIndex: 10 }}>
                            <MainMenuPanelStack />
                        </div>
                    </Draggable>
                </div>
            </div>

            <div className="relative map-app-footer" style={{ height: '300px' }}>
                <div ref={resizerDomRef} className="layout-resizer" data-region="bottom" data-min={100} data-max={600}></div>
                <div className="absolute inset-0">
                    <BottomAreaContent />
                </div>
            </div>

        </div>
    );
}