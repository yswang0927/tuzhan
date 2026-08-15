import { useEffect, useState, useCallback, useRef } from "react";
import {
    Callout,
    Card,
    CardList,
    Classes,
    Elevation,
    FormGroup,
    Button,
    MenuItem,
    NonIdealState,
    NonIdealStateIconSize,
    Spinner,
    SpinnerSize,
    Icon,
    PanelStack,
    type Panel,
    type PanelProps,
} from "@blueprintjs/core";
import {DateRangeInput, TimePrecision} from "@blueprintjs/datetime";
import { zhCN, zhTW, enUS } from "date-fns/locale";
import Draggable from 'react-draggable';

import { TrajectoryLocIcon } from "@/utils/icons";
import { LayoutResizer } from "@/utils";
import { useL10n } from "@/l10n";

import GeoMap from "./map";
import { ObjectSuggest } from "./ObjectSuggest";
import { TrajectoryDataTable, type TrajectoryData } from "./TrajectoryDataTable";


const DATE_PICKER_LOCALES = {
    'zh-CN': zhCN,
    'zh-TW': zhTW,
    'en': enUS,
    'en-US': enUS
};


interface PanelEmptyProps {
    // empty props
}

/**
 * 人员轨迹定位。
 * 指定对象ID + 时间段， 查询并可视化其轨迹路线，按数据源用不同的图标展示，
 * 时间倒序列表展示详情。
 */
const TrajectoryLocationPanel: React.FC<PanelProps<PanelEmptyProps>> = (props) => {
    const { t, lang } = useL10n();

    return (
        <div style={{padding: '0.5rem 1rem'}}>
            <h3 style={{marginTop:0}}>{t('人员轨迹定位')}</h3>
            <form>
            <FormGroup label={t('选择目标对象')}>
                <ObjectSuggest />
            </FormGroup>

            <FormGroup label={t('选择时间范围')}>
                <DateRangeInput
                    dateFnsFormat="yyyy-MM-dd HH:mm:ss"
                    startInputProps={{ placeholder: t('开始时间') }}
                    endInputProps={{ placeholder: t('结束时间') }}
                    timePickerProps={{precision: TimePrecision.SECOND, showArrowButtons: false}}
                    reverseMonthAndYearMenus={true}
                    contiguousCalendarMonths={false}
                    locale={DATE_PICKER_LOCALES[lang]}
                />
            </FormGroup>

            <div>
                <Button text={t('查询')} intent="primary" fill={true}/>
            </div>
            </form>
        </div>
    );
};

/**
 * 最后一次位置
 * 查询目标账号历史上最后一次出现的坐标和时间。
 */
const LastLocationPanel: React.FC<PanelProps<PanelEmptyProps>> = (props) => {
    const { t, lang } = useL10n();

    return (
        <div style={{padding: '0.5rem 1rem'}}>
            <Callout compact={true}>查询目标账号历史上最后一次出现的坐标和时间。</Callout>
            <form>
                <FormGroup label={t('选择目标对象')}>
                    <ObjectSuggest />
                </FormGroup>

                <div>
                    <Button text={t('查询')} intent="primary" fill={true}/>
                </div>
            </form>
        </div>
    );
};

const MenuListPanel: React.FC<PanelProps<PanelEmptyProps>> = (props) => {
    const { openPanel, closePanel } = props;
    const { t } = useL10n();

    const MENUS = [
        {icon: <Icon icon="locate" />, name: t('轨迹定位'), panel: TrajectoryLocationPanel},
        {icon: <Icon icon="area-of-interest" />, name: t('轨迹回溯'), panel: null},
        {icon: <Icon icon="map-marker" />, name: t('最后一次位置'), panel: LastLocationPanel},
        {icon: <Icon icon="route" />, name: t('我的足迹'), panel: null},
    ];

    const doOpenPanel = (item: any) => {
        if (!item.panel) {
            return;
        }
        openPanel({
            props: {},
            renderPanel: item.panel,
            title: item.name,
        })
    };

    return (
        <CardList bordered={true} compact={true}>
        {MENUS.map(item => (
            <Card interactive={true} key={item.name} onClick={() => doOpenPanel(item)}>
                <span>
                    <span className="menu-icon">{item.icon}</span>
                    <span>{item.name}</span>
                </span>
                <Icon icon="chevron-right" className={Classes.TEXT_MUTED} />
            </Card>
        ))}
    </CardList>
    );
};


export default function TrajectoryQuery() {
    const { t } = useL10n();
    const searchPanelRef = useRef<HTMLDivElement>(null);
    const resizerDomRef = useRef<HTMLDivElement>(null);

    const initialPanel: Panel<PanelEmptyProps> = {
        props: {},
        renderPanel: MenuListPanel,
        title: t('轨迹查询'),
    };

    useEffect(() => {
        if (!resizerDomRef.current) {
            return;
        }
        const layoutResizer = new LayoutResizer({
            key: "resizer1", // 如果配置了,则可以自动记忆
            trigger: resizerDomRef.current,
            target: resizerDomRef.current?.parentElement
        });
        return () => {
            layoutResizer && layoutResizer.destroy();
        };
    }, []);

    const onDataTableRowClick = (rowData: TrajectoryData) => {
        console.log('>>> on-row-click: ', rowData);
    };

    const testData:TrajectoryData[] = [
        {
            "idfa_md5" : "ffffffff-fef3-1c1b-ffff-ffffb6993790",
            "event_time" : 1786506000,
            "lon" : -79.4673,
            "lat" : 34.7599
        },
        {
            "idfa_md5" : "ffffffff-fdc9-c112-0000-00004a687ab6",
            "event_time" : 1786503600,
            "lon" : -81.4034,
            "lat" : 41.6325
        },
        {
            "idfa_md5" : "ffffffff-fdc1-8aa2-ffff-ffffbb36e133",
            "event_time" : 1786504200,
            "lon" : -86.4013,
            "lat" : 39.7668
        },
        {
            "idfa_md5" : "ffffffff-fd20-2d0c-ffff-ffff8cdbf811",
            "event_time" : 1786504800,
            "lon" : -94.5936,
            "lat" : 39.0397
        }
    ];

    return (
        <div className="map-app-panel">
            <div className="map-app-header">
                <div className="map-app-header-icon">
                    <TrajectoryLocIcon />
                </div>
                <div className="map-app-header-title">{t('轨迹查询')}</div>
            </div>

            <div className="map-app-main relative">
                <div className="absolute inset-0">
                    <GeoMap />

                    <Draggable handle=".bp6-panel-stack2-header" nodeRef={searchPanelRef} bounds={{left: 0, top: 0}}>
                    <div ref={searchPanelRef} className="absolute map-app-search-panel" style={{left: "1rem", top: "1rem", minWidth: "220px", zIndex: 10}}>
                        <PanelStack 
                            showPanelHeader={true}
                            renderActivePanelOnly={true}
                            initialPanel={initialPanel}
                        />
                    </div>
                    </Draggable>
                </div>
            </div>

            <div className="relative map-app-results" style={{height:'300px'}}>
                <div ref={resizerDomRef} className="layout-resizer" data-region="bottom" data-min={100} data-max={600}></div>
                <div className="absolute inset-0">
                    <TrajectoryDataTable data={testData} loading={false} onRowClick={onDataTableRowClick} />
                </div>
            </div>

        </div>
    );
}