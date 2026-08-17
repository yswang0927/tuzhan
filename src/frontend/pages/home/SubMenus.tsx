import { CardList, Card, Icon, Classes, PanelStack, type Panel, type PanelProps } from "@blueprintjs/core";
import { useHomeStore } from "./store";
import { useEffect } from "react";
import { useMenusConfig, type SubMenuConfig } from "./menusConfig";

const DynamicMenuPanel = (props: PanelProps<{ mainId: string, submenus?: SubMenuConfig[] }>) => {
    const { submenus } = props;

    const openPanel = (submenu: SubMenuConfig) => {
        props.openPanel({
            title: submenu.name,
            renderPanel: submenu.panel as any,
            props: { id: submenu.id, title: submenu.name },
        });
    };

    if (!submenus || !Array.isArray(submenus)) {
        return <div className="p-4 text-gray-500">No submenus available</div>;
    }

    return (
        <CardList bordered={true} compact={true}>
            {submenus.map(item => (
                <Card interactive={true} key={item.name} onClick={() => openPanel(item)}>
                    <span>
                        <span className="menu-icon"><Icon icon={item.icon} /></span>
                        <span>{item.name}</span>
                    </span>
                    <Icon icon="chevron-right" className={Classes.TEXT_MUTED} />
                </Card>
            ))}
        </CardList>
    );
};

export const MainMenuPanelStack = () => {
    const menusConfig = useMenusConfig();
    const mainMenuId = useHomeStore(state => state.mainMenu);
    const setActiveSubMenuId = useHomeStore(state => state.setActiveSubMenuId);

    const activeMainMenuConfig = menusConfig.find(m => m.id === mainMenuId);

    // Reset sub-menu when main menu changes
    useEffect(() => {
        setActiveSubMenuId(null);
    }, [mainMenuId, setActiveSubMenuId]);

    if (!activeMainMenuConfig) {
        return <div className="p-4 bg-red-100 text-red-500">Error: Main menu config not found for {mainMenuId}</div>;
    }

    const initialPanel: Panel<any> = {
        title: activeMainMenuConfig.name,
        renderPanel: DynamicMenuPanel as any,
        props: { mainId: activeMainMenuConfig.id, submenus: activeMainMenuConfig.submenus }
    };

    return (
        <div className="map-app-search-panel">
            <PanelStack
                key={mainMenuId}
                initialPanel={initialPanel}
                showPanelHeader={true}
                renderActivePanelOnly={true}
                onOpen={(panel) => {
                    if (panel.props && (panel.props as any).id) {
                        setActiveSubMenuId((panel.props as any).id);
                    }
                }}
                onClose={() => {
                    setActiveSubMenuId(null);
                }}
            />
        </div>
    );
};

export const BottomAreaContent = () => {
    const { mainMenu, activeSubMenuId } = useHomeStore();
    const menusConfig = useMenusConfig();

    let content = null;

    const activeMainMenuConfig = menusConfig.find(m => m.id === mainMenu);
    if (activeMainMenuConfig && activeSubMenuId) {
        const activeSubMenu = activeMainMenuConfig.submenus.find(s => s.id === activeSubMenuId);
        if (activeSubMenu) {
            const FooterComponent = activeSubMenu.footer;
            content = <FooterComponent id={activeSubMenu.id} title={activeSubMenu.name} />;
        }
    }

    if (!content) {
        content = <div className="text-gray-500">请选择一个子菜单查看内容</div>;
    }

    return (
        <div className="p-4 h-full bg-white dark:bg-gray-800">
            {content}
        </div>
    );
};
