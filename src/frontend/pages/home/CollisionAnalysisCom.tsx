import React, { useEffect, useState, useCallback, useRef } from "react";
import {
    Callout,
    Card,
    FormGroup,
    Button,
    type PanelProps,
} from "@blueprintjs/core";
import { DateRangeInput, TimePrecision } from "@blueprintjs/datetime";
import { zhCN, zhTW, enUS } from "date-fns/locale";
import { useForm, Controller } from "react-hook-form";
import { toast } from 'sonner';

import { formatDate, hexToRgba } from "@/utils";
import { useFetch } from "@/utils/api";
import { useL10n } from "@/l10n";
import type {DrawResult} from "@/pages/common/OpenLayersMap";
import {useHomeStore} from "./store";

interface PanelEmptyProps {
    // empty props
}

interface AreaProps {
    id: string;
    coords: Array<[number,number]>; // 多边形顶点坐标[[lon,lat],[...]]
    color: string;
    dates: [Date | null, Date | null];
}

const COLORS = ["#147EB3", "#29A634", "#D1980B", "#D33D17", "#9D3F9D", "#00A396", "#DB2C6F", "#8EB125", "#946638", "#7961DB"];

export const AreaCollisionFormPanel: React.FC<PanelProps<PanelEmptyProps>> = (props) => {
    const {t} = useL10n();
    // 存储在地图上绘制的多边形区域
    const [areas, setAreas] = useState<AreaProps[]>([]);
    const [drawing, setDrawing] = useState(false);

    const getAreaColor = () => {
        const exists = areas.map(area => area.color);
        const availableColors = COLORS.filter(item => !exists.includes(item));
        return availableColors.length > 0 ? availableColors[0] : COLORS[ Math.floor(Math.random() * COLORS.length)];
    };

    const startDrawArea = () => {
        const mapApi = useHomeStore.getState().mapApi;
        if (!mapApi) {
            toast(t('请等待地图加载完毕'));
            return;
        }

        setDrawing(true);
        const newArea:AreaProps = {
            id: `area-${Date.now()}`,
            color: getAreaColor(),
            coords: [],
            dates: [null, null]
        };

        mapApi?.startDraw("Polygon", {
            lineColor: newArea.color,
            fillColor: hexToRgba(newArea.color, 0.2),
            onFinish: (result: DrawResult) => {
                setDrawing(false);
                setAreas(prev => [...prev, { ...newArea, coords: result.coordinates }]);
                mapApi?.drawPolygon(newArea.id, result.coordinates);
            },
        });
    };

    return (
        <div className="flex flex-col gap-md padding-md">
            <Callout compact={true}>{t('Hello')}</Callout>
            <div className="flex justify-between nowrap">
                <div>{areas.length} {t('个区域')}</div>
                <div>
                    <Button icon="polygon-filter" variant="outlined" intent="primary" text={t('绘制区域')} onClick={startDrawArea} disabled={drawing} />
                </div>
            </div>
            <div className="flex flex-col gap">
                {areas.map((area, i) => {
                    return (
                        <Card key={area.id} compact={true} style={{border: `1px solid ${area.color}`, backgroundColor: hexToRgba(area.color, 0.2)}}>
                            <div><span style={{display:'inline-block',width:'8px',height:'8px',borderRadius:'50%',backgroundColor:area.color,marginRight:'4px'}}></span>区域{i+1}</div>
                            <div>
                                <DateRangeInput
                                    value={area.dates}
                                    onChange={(selectedDates) => {
                                        setAreas(prev => prev.map(a =>
                                            a.id === area.id ? { ...a, dates: [selectedDates[0], selectedDates[1]] } : a
                                        ));
                                    }}
                                    dateFnsFormat="yyyy/MM/dd HH:mm:ss"
                                    startInputProps={{ placeholder: t('开始时间') }}
                                    endInputProps={{ placeholder: t('结束时间') }}
                                    timePickerProps={{ precision: TimePrecision.SECOND, showArrowButtons: false }}
                                    reverseMonthAndYearMenus={true}
                                    contiguousCalendarMonths={false}
                                />
                            </div>
                            <div>{area.coords.length} {t('个顶点')}</div>
                        </Card>
                    );
                })}
            </div>

            <div>
                <div>时间关系</div>
            </div>
        </div>
    );
};