/**
 * 轨迹数据结构，与 clickhouse_gps_sql.txt 的 select 字段一一对应
 */
export interface TrajectoryData {
    objectId: string;     // 帐号唯一ID
    eventTime: string;   // 绝对时间戳(秒)
    lon: number;          // 经度
    lat: number;          // 纬度
}