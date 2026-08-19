import React, { useCallback } from "react";

import { TrajectoryDataTable } from "@/pages/common/TrajectoryDataTable";
import type { TrajectoryData } from "@/pages/common/types";
import type { PointData } from "@/pages/common/OpenLayersMap";
import { useHomeStore } from "./store";

/**
 * 底部轨迹表格的连接容器。
 * - 从 store 读取查询到的轨迹数据渲染表格(纯展示组件 TrajectoryDataTable 不感知业务)
 * - 表格 onRowClick 时命令式调用地图 API，聚焦并高亮对应点，实现表格<->地图联动
 */
export const TrajectoryDataTableContainer: React.FC = () => {
    const data = useHomeStore(state => state.trajectoryData);
    const loading = useHomeStore(state => state.tableLoading);

    const handleRowClick = useCallback((row: TrajectoryData) => {
        const mapApi = useHomeStore.getState().mapApi;
        if (!mapApi || row.lon == null || row.lat == null) return;
        // TrajectoryData.eventTime 是字符串，转成地图 API 需要的时间戳(秒)
        const point: PointData = {
            objectId: row.objectId,
            eventTime: Number(row.eventTime) || 0,
            lon: row.lon,
            lat: row.lat,
        };
        //mapApi.drawPoint(point);   // 高亮动画
        mapApi.focusPoint(point);  // 聚焦缩放
    }, []);

    return (
        <TrajectoryDataTable
            data={data}
            loading={loading}
            onRowClick={handleRowClick}
        />
    );
};
