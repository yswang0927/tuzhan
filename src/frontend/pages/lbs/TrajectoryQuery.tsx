import { useEffect, useState, useCallback, useRef } from "react";
import {
    Callout,
    Card,
    CardList,
    Classes,
    FormGroup,
    Button,
    Icon,
    PanelStack,
    type Panel,
    type PanelProps,
} from "@blueprintjs/core";
import { DateRangeInput, TimePrecision } from "@blueprintjs/datetime";
import { zhCN, zhTW, enUS } from "date-fns/locale";
import Draggable from 'react-draggable';
import { useForm, Controller } from "react-hook-form";
import { format } from 'date-fns';
import { toast } from 'sonner';

import { LogoIcon, TrajectoryLocIcon } from "@/utils/icons";
import { LayoutResizer } from "@/utils";
import { getJson } from "@/utils/api";
import { useL10n } from "@/l10n";

import GeoMap from "./map";
import { ObjectSuggest } from "./ObjectSuggest";
import type { TrajectoryData } from "./types";
import { TrajectoryDataTable } from "./TrajectoryDataTable";
import { useTrajectoryStore } from './store';


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
    const setTrajectoryData = useTrajectoryStore(state => state.setTrajectoryData);
    const setTableLoading = useTrajectoryStore(state => state.setTableLoading);
    const { register, handleSubmit, control, setValue, watch, formState: { errors } } = useForm({
        defaultValues: {
            objectId: null as string | null,
            startTime: null as Date | null,
            endTime: null as Date | null,
        }
    });

    const [querying, setQuerying] = useState(false);

    useEffect(() => {
        register('startTime', { required: t('请选择开始时间') });
        register('endTime', { required: t('请选择结束时间') });
    }, [register, t]);

    const doQuery = (formData: any) => {
        console.log(">>> formData: ", formData);
        setQuerying(true);
        setTableLoading(true);
        getJson('/api/trajectory/query-trajectories', {
            ...formData,
            startTime: formData.startTime ? format(formData.startTime, 'yyyy-MM-dd HH:mm:ss') : '',
            endTime: formData.endTime ? format(formData.endTime, 'yyyy-MM-dd HH:mm:ss') : '',
        })
        .then(data => {
            console.log(">>> data: ", data);
            setTrajectoryData(data || []);
        })
        .catch(err => {
            console.error(">>> err: ", err);
            setTrajectoryData([]);
        })
        .finally(() => {
            setQuerying(false);
            setTableLoading(false);
        });
    };

    const startTime = watch('startTime');
    const endTime = watch('endTime');

    return (
        <div style={{ padding: '0.5rem 1rem' }}>
            <form onSubmit={handleSubmit(doQuery)}>
                <FormGroup
                    label={t('选择目标对象')}
                    intent={errors.objectId ? "danger" : "none"}
                    helperText={errors.objectId?.message as string}
                >
                    <Controller
                        name="objectId"
                        control={control}
                        rules={{ required: t('请选择目标对象') }}
                        render={({ field }) => (
                            <ObjectSuggest onSelected={field.onChange} defaultValue={field.value} />
                        )}
                    />
                </FormGroup>

                <FormGroup
                    label={t('选择时间范围')}
                    intent={errors.startTime || errors.endTime ? "danger" : "none"}
                    helperText={(errors.startTime?.message || errors.endTime?.message) as string}
                >
                    <DateRangeInput
                        value={[startTime, endTime]}
                        onChange={(selectedDates) => {
                            setValue('startTime', selectedDates[0], { shouldValidate: true });
                            setValue('endTime', selectedDates[1], { shouldValidate: true });
                        }}
                        dateFnsFormat="yyyy-MM-dd HH:mm:ss"
                        startInputProps={{ placeholder: t('开始时间') }}
                        endInputProps={{ placeholder: t('结束时间') }}
                        timePickerProps={{ precision: TimePrecision.SECOND, showArrowButtons: false }}
                        reverseMonthAndYearMenus={true}
                        contiguousCalendarMonths={false}
                        locale={DATE_PICKER_LOCALES[lang]}
                    />
                </FormGroup>

                <div>
                    <Button
                        onClick={handleSubmit(doQuery, (errs) => console.log('Validation Errors:', errs))}
                        text={t('查询')}
                        intent="primary"
                        fill={true}
                        loading={querying}
                    />
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
    const { t } = useL10n();
    const setTrajectoryData = useTrajectoryStore(state => state.setTrajectoryData);
    const setTableLoading = useTrajectoryStore(state => state.setTableLoading);
    const { handleSubmit, control, formState: { errors } } = useForm({
        defaultValues: {
            objectId: null as string | null,
        }
    });

    const [querying, setQuerying] = useState(false);

    const doQuery = (formData: any) => {
        console.log(">>> formData: ", formData);
        setQuerying(true);
        setTableLoading(true);
        getJson('/api/trajectory/query-lastlocation', formData)
            .then(data => {
                console.log(">>> data: ", data);
                setTrajectoryData(Array.isArray(data) ? data : data ? [data] : []);
            })
            .catch(err => {
                console.error(">>> err: ", err);
                setTrajectoryData([]);
            })
            .finally(() => {
                setQuerying(false);
                setTableLoading(false);
            });
    };

    return (
        <div style={{ padding: '0.5rem 1rem' }}>
            <Callout compact={true}>{t('查询目标账号历史上最后一次出现的坐标和时间。')}</Callout>
            <form onSubmit={handleSubmit(doQuery)}>
                <FormGroup
                    label={t('选择目标对象')}
                    intent={errors.objectId ? "danger" : "none"}
                    helperText={errors.objectId?.message as string}
                >
                    <Controller
                        name="objectId"
                        control={control}
                        rules={{ required: t('请选择目标对象') }}
                        render={({ field }) => (
                            <ObjectSuggest onSelected={field.onChange} defaultValue={field.value} />
                        )}
                    />
                </FormGroup>

                <div>
                    <Button
                        onClick={handleSubmit(doQuery, (errs) => console.log('Validation Errors:', errs))}
                        text={t('查询')}
                        intent="primary"
                        fill={true}
                        loading={querying}
                    />
                </div>
            </form>
        </div>
    );
};

const MenuListPanel: React.FC<PanelProps<PanelEmptyProps>> = (props) => {
    const { openPanel, closePanel } = props;
    const { t } = useL10n();

    const MENUS = [
        { icon: <Icon icon="locate" />, name: t('人员轨迹定位'), panel: TrajectoryLocationPanel },
        { icon: <Icon icon="area-of-interest" />, name: t('轨迹回溯'), panel: null },
        { icon: <Icon icon="map-marker" />, name: t('最后一次位置'), panel: LastLocationPanel },
        { icon: <Icon icon="route" />, name: t('我的足迹'), panel: null },
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
    const trajectoryData = useTrajectoryStore(state => state.trajectoryData);
    const tableLoading = useTrajectoryStore(state => state.tableLoading);
    const searchPanelRef = useRef<HTMLDivElement|null>(null);
    const resizerDomRef = useRef<HTMLDivElement|null>(null);

    const initialPanel: Panel<PanelEmptyProps> = {
        props: {},
        renderPanel: MenuListPanel,
        title: t('轨迹查询'),
    };

    useEffect(() => {
        return () => {
            useTrajectoryStore.getState().reset();
        };
    }, []);

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

    return (
        <div className="map-app-panel">
            <div className="map-app-header">
                <div className="map-app-header-icon">
                    <LogoIcon />
                </div>
                <div className="map-app-header-title">{t('时空情报平台')}</div>
            </div>

            <div className="map-app-main relative">
                <div className="absolute inset-0">
                    <GeoMap />

                    <Draggable handle=".bp6-panel-stack2-header" nodeRef={searchPanelRef} bounds={{ left: 0, top: 0 }}>
                        <div ref={searchPanelRef} className="absolute map-app-search-panel" style={{ left: "1rem", top: "1rem", minWidth: "220px", zIndex: 10 }}>
                            <PanelStack
                                showPanelHeader={true}
                                renderActivePanelOnly={true}
                                initialPanel={initialPanel}
                            />
                        </div>
                    </Draggable>
                </div>
            </div>

            <div className="relative map-app-results" style={{ height: '300px' }}>
                <div ref={resizerDomRef} className="layout-resizer" data-region="bottom" data-min={100} data-max={600}></div>
                <div className="absolute inset-0">
                    <TrajectoryDataTable data={trajectoryData} loading={tableLoading} onRowClick={onDataTableRowClick} />
                </div>
            </div>

        </div>
    );
}