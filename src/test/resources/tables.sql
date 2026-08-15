CREATE TABLE idfa_gps_detail(
    idfa_md5 String COMMENT '账号唯一ID',
    event_time UInt64 COMMENT '绝对时间戳(秒)',
    lon Float64 COMMENT '经度',
    lat Float64 COMMENT '纬度',
    dt Date MATERIALIZED toDate(event_time) COMMENT '日期分区字段',
    hour UInt8 MATERIALIZED toHour(toDateTime(event_time)) COMMENT '小时',
    geohash7 String MATERIALIZED geohashEncode(lon, lat, 7) COMMENT '7位geohash用于快速聚类，精度~150米',
    geohash6 String MATERIALIZED geohashEncode(lon, lat, 6) COMMENT '6位geohash用于快速聚类，精度~600米',
    geohash5 String MATERIALIZED geohashEncode(lon, lat, 5) COMMENT '5位geohash用于快速聚类，精度~2.4公里'
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/idfa_gps_detail', '{replica}')
PARTITION BY toYYYYMM(dt)
ORDER BY (idfa_md5, dt, hour, event_time)
PRIMARY KEY (idfa_md5, dt)
TTL dt + INTERVAL 90 DAY DELETE;