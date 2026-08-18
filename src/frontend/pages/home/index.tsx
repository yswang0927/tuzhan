import React, { useEffect, useRef } from "react";
import Draggable from "react-draggable";
import { Button } from "@blueprintjs/core";

import { LogoIcon } from "@/utils/icons";
import GeoMap from "@/pages/lbs/map";
import { OpenLayersMap } from "@/pages/common/OpenLayersMap";
import { useL10n } from "@/l10n";
import { LayoutResizer } from "@/utils";
import { useHomeStore } from "./store";
import { MainMenuPanelStack, BottomAreaContent } from "./SubMenus";
import { useMenusConfig } from "./menusConfig";

import "./style.css";

export default function Home() {
    const { t } = useL10n();
    const { mainMenu, setMainMenu } = useHomeStore();
    const menusConfig = useMenusConfig();
    const resizerDomRef = useRef<HTMLDivElement | null>(null);
    const submenuContainerRef = useRef<HTMLDivElement | null>(null);

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
                    <OpenLayersMap />

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