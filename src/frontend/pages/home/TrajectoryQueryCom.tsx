import React, { useEffect, useState, useCallback, useRef } from "react";
import {
    Callout,
    FormGroup,
    Button,
    type PanelProps,
} from "@blueprintjs/core";
import { DateRangeInput, TimePrecision } from "@blueprintjs/datetime";
import { zhCN, zhTW, enUS } from "date-fns/locale";
import { useForm, Controller } from "react-hook-form";
import { format } from 'date-fns';
import { toast } from 'sonner';

import { getJson } from "@/utils/api";
import { useL10n } from "@/l10n";

import { ObjectSuggest } from "@/pages/common/ObjectSuggest";


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
export const LocationQueryPanel: React.FC<PanelProps<PanelEmptyProps>> = (props) => {
    const { t, lang } = useL10n();
    //const setTrajectoryData = useTrajectoryStore(state => state.setTrajectoryData);
    //const setTableLoading = useTrajectoryStore(state => state.setTableLoading);
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
        //setTableLoading(true);
        getJson('/api/trajectory/query-trajectories', {
            ...formData,
            startTime: formData.startTime ? format(formData.startTime, 'yyyy-MM-dd HH:mm:ss') : '',
            endTime: formData.endTime ? format(formData.endTime, 'yyyy-MM-dd HH:mm:ss') : '',
        })
            .then(data => {
                console.log(">>> data: ", data);
                //setTrajectoryData(data || []);
            })
            .catch(err => {
                console.error(">>> err: ", err);
                //setTrajectoryData([]);
            })
            .finally(() => {
                setQuerying(false);
                //setTableLoading(false);
            });
    };

    const startTime = watch('startTime');
    const endTime = watch('endTime');

    return (
        <div style={{ padding: '0.5rem 1rem' }}>
            <Callout compact={true}>{t('查询目标对象在指定时间范围内的轨迹。')}</Callout>
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
export const LastLocationPanel: React.FC<PanelProps<PanelEmptyProps>> = (props) => {
    const { t } = useL10n();
    //const setTrajectoryData = useTrajectoryStore(state => state.setTrajectoryData);
    //const setTableLoading = useTrajectoryStore(state => state.setTableLoading);
    const { handleSubmit, control, formState: { errors } } = useForm({
        defaultValues: {
            objectId: null as string | null,
        }
    });

    const [querying, setQuerying] = useState(false);

    const doQuery = (formData: any) => {
        console.log(">>> formData: ", formData);
        setQuerying(true);
        //setTableLoading(true);
        getJson('/api/trajectory/query-lastlocation', formData)
            .then(data => {
                console.log(">>> data: ", data);
                //setTrajectoryData(Array.isArray(data) ? data : data ? [data] : []);
            })
            .catch(err => {
                console.error(">>> err: ", err);
                //setTrajectoryData([]);
            })
            .finally(() => {
                setQuerying(false);
                //setTableLoading(false);
            });
    };

    return (
        <div style={{ padding: '0.5rem 1rem' }}>
            <Callout compact={true}>{t('查询目标对象历史上最后一次出现的坐标和时间。')}</Callout>
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