#!/bin/zsh

PARQUET_DIR="/Users/szhang/Downloads/11"

for file in ${PARQUET_DIR}/*; do
    echo "正在导入: $file ..."
    cat "$file" | docker exec -i clickhouse clickhouse-client \
        --user idfa \
        --password 'idfa@2026' \
        --database idfa \
        --query="INSERT INTO idfa_gps_detail (idfa_md5, event_time, lon, lat) \
         SETTINGS \
             max_insert_block_size = 100000, \
             max_threads = 2, \
             max_insert_threads = 2, \
             input_format_parquet_use_native_reader = 1 \
         FORMAT Parquet"
done

echo "全部导入完成！"

# 上面的导入方式会触发docker-clickhouse oom，使用下面的单一文件导入：
# 将数据文件放到挂载的数据目录下 user_files/ 目录下。
docker exec -i clickhouse clickhouse-client \
    --user idfa \
    --password 'idfa@2026' \
    --database idfa \
    --query="
    INSERT INTO idfa_gps_detail (idfa_md5, event_time, lon, lat)
    SELECT idfa_md5, event_time, lon, lat
    FROM file('/var/lib/clickhouse/user_files/data0.parquet', 'Parquet')
    SETTINGS
        max_insert_block_size = 30000,
        max_threads = 1,
        max_insert_threads = 1,
        input_format_parquet_use_native_reader = 1;
    "