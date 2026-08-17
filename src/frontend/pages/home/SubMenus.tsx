import { Button, Menu, MenuItem, PanelStack2, type Panel, type PanelProps } from "@blueprintjs/core";
import { useHomeStore } from "./store";
import { useEffect, useState } from "react";
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
        <Menu>
            {submenus.map(submenu => (
                <MenuItem 
                    key={submenu.id}
                    icon={submenu.icon} 
                    text={submenu.name} 
                    onClick={() => openPanel(submenu)} 
                />
            ))}
        </Menu>
    );
};

export const MainMenuPanelStack = () => {
    const menusConfig = useMenusConfig();
    const mainMenuId = useHomeStore(state => state.mainMenu);
    const setActiveSubMenuId = useHomeStore(state => state.setActiveSubMenuId);
    
    const activeMainMenuConfig = menusConfig.find(m => m.id === mainMenuId);

    // Initial stack based on active main menu
    const [stack, setStack] = useState<Panel<any>[]>([{
        title: activeMainMenuConfig?.name || 'Menu',
        renderPanel: DynamicMenuPanel,
        props: { mainId: activeMainMenuConfig?.id || 'main', submenus: activeMainMenuConfig?.submenus || [] }
    }]);

    // Reset stack when main menu changes
    useEffect(() => {
        const newActiveMainMenuConfig = menusConfig.find(m => m.id === mainMenuId);
        if (newActiveMainMenuConfig) {
            setStack([{
                title: newActiveMainMenuConfig.name,
                renderPanel: DynamicMenuPanel,
                props: { mainId: newActiveMainMenuConfig.id, submenus: newActiveMainMenuConfig.submenus }
            }]);
            setActiveSubMenuId(null);
        }
    }, [mainMenuId, menusConfig, setActiveSubMenuId]);

    // Sync active panel ID to store
    useEffect(() => {
        const currentPanel = stack[stack.length - 1];
        // Skip the root menu panel
        if (currentPanel && currentPanel.props && currentPanel.props.id) {
            setActiveSubMenuId(currentPanel.props.id);
        } else {
            setActiveSubMenuId(null);
        }
    }, [stack, setActiveSubMenuId]);

    if (!activeMainMenuConfig) {
        return <div className="p-4 bg-red-100 text-red-500">Error: Main menu config not found for {mainMenuId}</div>;
    }

    return (
        <div style={{ height: "400px", backgroundColor: "var(--bp-colors-gray5)", borderRadius: "6px", boxShadow: "0 0 4px rgba(0,0,0,0.2)" }}>
            <PanelStack2
                stack={stack}
                onOpen={(panel) => setStack(prev => [...prev, panel])}
                onClose={() => setStack(prev => prev.slice(0, -1))}
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
