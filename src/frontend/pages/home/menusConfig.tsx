import React, { useMemo } from "react";
import { useL10n } from "@/l10n";
import { type PanelProps } from "@blueprintjs/core";

import { LocationQueryPanel, LastLocationPanel } from "./TrajectoryQueryCom";
import { TrajectoryDataTableContainer } from "./TrajectoryDataTableContainer";

export interface SubMenuConfig {
    id: string;
    name: string;
    icon: any; // blueprint IconName
    panel: React.ComponentType<PanelProps<any>>;
    footer: React.ComponentType<any>;
}

export interface MainMenuConfig {
    id: string;
    name: string;
    icon: any;
    submenus: SubMenuConfig[];
}

// Dummy generic panel
export const GenericContentPanel = (props: PanelProps<{ id: string; title: string; content?: React.ReactNode }>) => {
    return (
        <div>
            <h4>{props.title} content...</h4>
            {props.content ? props.content : <p>This is the panel for {props.title}.</p>}
        </div>
    );
};

// Dummy generic footer
export const GenericFooter = ({ id, title }: { id: string; title: string }) => {
    return <div>当前底部区域展示子项内容：{title} ({id})</div>;
};

export const useMenusConfig = (): MainMenuConfig[] => {
    const { t } = useL10n();

    return useMemo(() => [
        {
            id: 'trajectory',
            name: t('轨迹查询'),
            icon: 'path-search',
            submenus: [
                { id: 'person-location', name: t('人员轨迹定位'), icon: 'geolocation', panel: LocationQueryPanel, footer: TrajectoryDataTableContainer },
                { id: 'trajectory-tracking', name: t('轨迹回溯'), icon: 'history', panel: GenericContentPanel, footer: GenericFooter },
                { id: 'last-position', name: t('最后一次位置'), icon: 'map-marker', panel: LastLocationPanel, footer: TrajectoryDataTableContainer },
                { id: 'my-footprints', name: t('我的足迹'), icon: 'walk', panel: GenericContentPanel, footer: GenericFooter },
            ]
        },
        {
            id: 'collision',
            name: t('时空碰撞'),
            icon: 'bullseye',
            submenus: [
                { id: 'region-collision', name: t('区域碰撞分析'), icon: 'polygon-filter', panel: GenericContentPanel, footer: GenericFooter },
                { id: 'person-collision', name: t('人员轨迹碰撞'), icon: 'intersection', panel: GenericContentPanel, footer: GenericFooter },
                { id: 'region-person', name: t('区域人员分析'), icon: 'people', panel: GenericContentPanel, footer: GenericFooter },
                { id: 'first-appearance', name: t('首次出现人员'), icon: 'new-person', panel: GenericContentPanel, footer: GenericFooter },
            ]
        }
    ], [t]);
};
