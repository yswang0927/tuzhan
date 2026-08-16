import React, { useState, useCallback } from "react";
import { Menu, MenuItem } from "@blueprintjs/core";
import {
  Cell,
  Column,
  Table,
  TableLoadingOption,
  type Region,
  Regions,
  SelectionModes,
  type MenuContext,
} from "@blueprintjs/table";

import { useL10n } from "@/l10n";
import { copyToClipboard } from "@/utils";
import type { TrajectoryData } from "./types";

import "@blueprintjs/table/lib/css/table.css";


export interface TrajectoryDataTableProps {
  data: TrajectoryData[];
  loading?: boolean;
  onRowClick?: (row: TrajectoryData) => void; // 点击或选中某一行时的回调函数，用于地图高亮联动
  onSelection?: (regions: Region[]) => void; // 传递给外部的 onSelection 回调
}

/**
 * 使用 @blueprintjs/table 高效展示大规模时空轨迹数据
 */
export const TrajectoryDataTable: React.FC<TrajectoryDataTableProps> = ({
  data = [],
  loading = false,
  onRowClick,
}) => {
  const { t } = useL10n();

  // 1. 维护当前选中的区域状态
  const [selectedRegions, setSelectedRegions] = useState<Region[]>([]);

  // 2. 将任何单元格选中自动转换为整行选中 (Select Entire Row)
  const selectedRegionTransform = useCallback((region: Region): Region => {
    if (region.rows != null) {
      return Regions.row(region.rows[0], region.rows[1]);
    }
    return region;
  }, []);

  // 3. 处理表格选择变更事件
  const handleSelection = useCallback((regions: Region[]) => {
    if (loading) return;

    setSelectedRegions(regions);

    // 获取选中的行索引并触发回调
    if (regions.length > 0 && regions[0].rows != null) {
      const [rowStart] = regions[0].rows;
      if (rowStart >= 0 && rowStart < data.length) {
        onRowClick?.(data[rowStart]);
      }
    }
  }, [loading, data, onRowClick]);

  // 获取特定单元格的文本值
  const getCellText = useCallback((row: number, col: number) => {
    const rowData = data[row];
    if (!rowData) return "";
    switch (col) {
      case 0:
        return rowData.objectId || "";
      case 1:
        return rowData.eventTime || "";
      case 2:
        return rowData.lon !== undefined ? rowData.lon.toFixed(6) : "";
      case 3:
        return rowData.lat !== undefined ? rowData.lat.toFixed(6) : "";
      default:
        return "";
    }
  }, [data]);

  // 右键菜单 1: 复制当前右键点击的单元格内容
  const handleCopyCell = useCallback((context: MenuContext) => {
    const target = context.getTarget();
    if (target.rows != null && target.cols != null) {
      const row = target.rows[0];
      const col = target.cols[0];
      const text = getCellText(row, col);
      if (text) {
        copyToClipboard(text);
      }
    }
  }, [getCellText]);

  // 右键菜单 2: 复制当前右键点击的整行内容 (Tab分隔)
  const handleCopyRow = useCallback((context: MenuContext) => {
    const target = context.getTarget();
    const rowIndex = target.rows != null ? target.rows[0] : null;
    if (rowIndex != null && rowIndex >= 0 && rowIndex < data.length) {
      const rowData = data[rowIndex];
      if (rowData) {
        const timeStr = rowData.eventTime || "";
        const lonStr = rowData.lon !== undefined ? rowData.lon.toFixed(6) : "";
        const latStr = rowData.lat !== undefined ? rowData.lat.toFixed(6) : "";
        const rowText = [rowData.objectId || "", timeStr, lonStr, latStr].join("\t");
        copyToClipboard(rowText);
      }
    }
  }, [data]);

  // 渲染表格 Body 右键菜单
  const renderBodyContextMenu = useCallback((context: MenuContext) => {
    return (
      <Menu>
        <MenuItem
          icon="duplicate"
          text={t("复制单元格内容")}
          onClick={() => handleCopyCell(context)}
        />
        <MenuItem
          icon="duplicate"
          text={t("复制整行内容")}
          onClick={() => handleCopyRow(context)}
        />
      </Menu>
    );
  }, [handleCopyCell, handleCopyRow]);

  // 4. 辅助函数：渲染常规 Cell
  const renderCell = (text: string, tooltipText?: string) => {
    return <Cell tooltip={tooltipText}>{text}</Cell>;
  };

  // 渲染 Object 列
  const renderIdfaCell = (rowIndex: number) => {
    const rowData = data[rowIndex];
    return renderCell(rowData?.objectId || "-", rowData?.objectId);
  };

  // 渲染时间列（将秒级时间戳转换为本地可读的日期格式）
  const renderTimeCell = (rowIndex: number) => {
    const rowData = data[rowIndex];
    if (!rowData || !rowData.eventTime) {
      return renderCell("-");
    }
    try {
      const formattedTime = new Date(rowData.eventTime).toLocaleString();
      return renderCell(formattedTime, formattedTime);
    } catch (e) {
      return renderCell(String(rowData.eventTime));
    }
  };

  // 渲染经度列
  const renderLonCell = (rowIndex: number) => {
    const rowData = data[rowIndex];
    const lonText = rowData?.lon !== undefined ? rowData.lon.toFixed(6) : "-";
    return renderCell(lonText);
  };

  // 渲染纬度列
  const renderLatCell = (rowIndex: number) => {
    const rowData = data[rowIndex];
    const latText = rowData?.lat !== undefined ? rowData.lat.toFixed(6) : "-";
    return renderCell(latText);
  };

  const numRows = loading ? 2 : data.length;

  const loadingOptions = loading
    ? [TableLoadingOption.CELLS, TableLoadingOption.COLUMN_HEADERS]
    : undefined;

  return (
    <div style={{ width: "100%", height: "100%", overflow: "hidden" }}>
      <Table
        numRows={numRows}
        enableRowHeader={true}
        loadingOptions={loadingOptions}
        selectionModes={SelectionModes.ROWS_AND_CELLS}
        selectedRegions={selectedRegions}
        onSelection={handleSelection}
        selectedRegionTransform={selectedRegionTransform}
        enableMultipleSelection={false}
        defaultRowHeight={30}
        enableColumnResizing={true}
        bodyContextMenuRenderer={renderBodyContextMenu}
      >
        <Column name={t("账号/ID")} cellRenderer={renderIdfaCell} />
        <Column name={t("时间")} cellRenderer={renderTimeCell} />
        <Column name={t("经度")} cellRenderer={renderLonCell} />
        <Column name={t("纬度")} cellRenderer={renderLatCell} />
      </Table>
    </div>
  );
};

export default TrajectoryDataTable;
