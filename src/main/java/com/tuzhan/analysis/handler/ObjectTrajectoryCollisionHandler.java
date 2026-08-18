package com.tuzhan.analysis.handler;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuzhan.analysis.AnalysisUtils;
import com.tuzhan.asynctask.AsyncTaskType;
import com.tuzhan.asynctask.TaskExecuteResult;
import com.tuzhan.asynctask.handler.AsyncTaskHandler;
import com.tuzhan.asynctask.repository.AsyncTaskEntity;
import com.tuzhan.exception.BadRequestException;
import com.tuzhan.repository.BaseRepository;
import com.tuzhan.util.JsonObjectMapper;

/**
 * 人员轨迹碰撞：用户圈选至少两个区域+各自时间段，系统找出同时出现在两个以上区域的人员、在各个区域出现的时间段、轨迹重叠时长。
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>命中条件：出现在 &ge;2 个选中区域的人员。</li>
 *   <li>重叠时长：人员在每个命中区域的滞留时长 (max(event_time)-min(event_time)) 之和。</li>
 *   <li>区域判定、跨区域合并、汇总全部下推 ClickHouse（pointInPolygon + UNION ALL + GROUP BY HAVING），
 *       结果用 ResultSetHandler 逐行流式写盘，几十万级结果内存恒定。</li>
 *   <li>输出格式：JSONL（每行一个 JSON 对象），文件为 {resultSaveDir}/{taskId}/result.jsonl。</li>
 * </ul>
 */
@Component
public class ObjectTrajectoryCollisionHandler extends BaseRepository implements AsyncTaskHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ObjectTrajectoryCollisionHandler.class);

    /** 至少要圈选的区域数。 */
    private static final int MIN_AREAS = 2;

    @Override
    public AsyncTaskType supportType() {
        return AsyncTaskType.PERSON_TRAJECTORY_COLLISION;
    }

    @Override
    public void validate(JsonNode queryParams) {
        if (queryParams == null) {
            throw new BadRequestException("查询参数不能为空");
        }

        JsonNode areas = queryParams.get("areas");
        JsonNode times = queryParams.get("times");

        if (areas == null || !areas.isArray() || areas.size() < MIN_AREAS) {
            throw new BadRequestException("至少需要圈选 " + MIN_AREAS + " 个区域");
        }
        if (times == null || !times.isArray() || times.size() != areas.size()) {
            throw new BadRequestException("times 必须与 areas 一一对应，数量需一致");
        }

        for (int i = 0; i < areas.size(); i++) {
            JsonNode polygon = areas.get(i);
            if (polygon == null || !polygon.isArray() || polygon.size() < AnalysisUtils.MIN_POLYGON_VERTICES) {
                throw new BadRequestException("区域[" + (i+1) + "] 至少需要 " + AnalysisUtils.MIN_POLYGON_VERTICES + " 个顶点");
            }
            for (int p = 0; p < polygon.size(); p++) {
                JsonNode point = polygon.get(p);
                if (point == null || !point.isArray() || point.size() < 2
                        || !point.get(0).isNumber() || !point.get(1).isNumber()) {
                    throw new BadRequestException("区域[" + (i+1) + "] 的顶点[" + p + "] 必须是 [lon, lat] 数值对");
                }
            }
            // 时间段校验（同时得到 start<=end 的保证）
            AnalysisUtils.parseTimeRange(times.get(i), i);
        }
    }

    @Override
    public TaskExecuteResult execute(AsyncTaskEntity task, JsonNode queryParams, Path resultSaveDir) throws Exception {
        JsonNode areas = queryParams.get("areas");
        JsonNode times = queryParams.get("times");
        final int areaCount = areas.size();

        // 1. 构造下推 SQL：每个区域一个子查询做 pointInPolygon 聚合，UNION ALL 后按人合并。
        String sql = buildCollisionSql(areas, times);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Trajectory collision SQL for task {}: {}", task.getTaskId(), sql);
        }

        // 2. 结果输出目录/文件
        Path taskDir = resultSaveDir.resolve(task.getTaskId());
        Files.createDirectories(taskDir);
        Path resultFile = taskDir.resolve("result.jsonl");

        // 3. 逐行流式消费 ResultSet 并写盘，内存恒定
        long[] hitAreaCounters = new long[areaCount]; // 各区域命中人数（去重后按人计一次）
        long totalPersons;
        try (BufferedWriter writer = Files.newBufferedWriter(resultFile, StandardCharsets.UTF_8)) {
            totalPersons = IDFA_JDBC.query(sql, rs -> {
                long persons = 0L;
                while (rs.next()) {
                    String idfa = rs.getString("idfa_md5");
                    int matchedAreaCount = rs.getInt("matchedAreaCount");
                    long overlapSeconds = rs.getLong("overlapSeconds");
                    long overlapStart = rs.getLong("overlapStart");
                    long overlapEnd = rs.getLong("overlapEnd");
                    boolean overlapped = overlapSeconds > 0;
                    // segments: groupArray((area_idx, area_first, area_last, pointCount))
                    // ClickHouse JDBC 返回为数组，逐元素解析
                    List<Map<String, Object>> segments = parseSegments(rs.getObject("segments"), hitAreaCounters);

                    Map<String, Object> row = new LinkedHashMap<>(6);
                    row.put("objectId", idfa);
                    row.put("matchedAreaCount", matchedAreaCount);
                    row.put("overlapSeconds", overlapSeconds);
                    // 仅在各区域时间窗真正相交时才输出重叠区间，否则为 null 避免误读。
                    row.put("overlapStart", overlapped ? overlapStart : null);
                    row.put("overlapEnd", overlapped ? overlapEnd : null);
                    row.put("segments", segments);

                    try {
                        writer.write(JsonObjectMapper.stringify(row));
                        writer.write('\n');
                    } catch (Exception e) {
                        throw new java.sql.SQLException("写入结果行失败: " + e.getMessage(), e);
                    }
                    persons++;
                }
                return persons;
            });
        }

        LOG.info("Task {} trajectory collision done, matched {} persons across {} areas",
                task.getTaskId(), totalPersons, areaCount);

        // 4. 汇总 meta
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalPersons", totalPersons);
        meta.put("areaCount", areaCount);
        List<Long> perAreaHits = new ArrayList<>(areaCount);
        for (long c : hitAreaCounters) {
            perAreaHits.add(c);
        }
        meta.put("perAreaHits", perAreaHits);
        meta.put("format", "jsonl");

        return new TaskExecuteResult(resultFile.toString(), meta);
    }

    /**
     * 构造碰撞下推 SQL。每个区域生成一个带 pointInPolygon 过滤与时间裁剪的子查询，
     * UNION ALL 后外层按 idfa 聚合，保留命中 &ge;2 个区域的人员。
     * 多边形顶点与时间段均解析为数值后拼接（无字符串注入风险）。
     */
    private String buildCollisionSql(JsonNode areas, JsonNode times) {
        StringBuilder inner = new StringBuilder();
        for (int i = 0; i < areas.size(); i++) {
            long[] range = AnalysisUtils.parseTimeRange(times.get(i), i);
            JsonNode polygonNode = areas.get(i);
            String polygon = AnalysisUtils.buildPolygonLiteral(polygonNode);
            double[] bbox = AnalysisUtils.boundingBox(polygonNode); // [minLon, minLat, maxLon, maxLat]

            if (i > 0) {
                inner.append(" UNION ALL ");
            }
            inner.append("SELECT idfa_md5, ").append(i).append(" AS area_idx, ")
                 .append("min(event_time) AS area_first, max(event_time) AS area_last, count() AS point_count ")
                 .append("FROM idfa_gps_detail ")
                 .append("WHERE dt BETWEEN toDate(").append(range[0]).append(") AND toDate(").append(range[1]).append(") ")
                 .append("AND event_time BETWEEN ").append(range[0]).append(" AND ").append(range[1]).append(' ')
                 // bbox 预过滤：先用廉价的经纬度矩形范围挡掉绝大多数点，再对幸存者跑 pointInPolygon。
                 .append("AND lon BETWEEN ").append(bbox[0]).append(" AND ").append(bbox[2]).append(' ')
                 .append("AND lat BETWEEN ").append(bbox[1]).append(" AND ").append(bbox[3]).append(' ')
                 .append("AND pointInPolygon((lon, lat), ").append(polygon).append(") ")
                 .append("GROUP BY idfa_md5");
        }

        String settings = AnalysisUtils.buildClickHouseSettings();
        // 轨迹重叠时长 = 各命中区域时间窗口 [area_first, area_last] 的交集长度：
        //   overlap = min(area_last) - max(area_first)，若时间窗互不相交则为 0（greatest 兜底）。
        return "SELECT idfa_md5, "
                + "countDistinct(area_idx) AS matchedAreaCount, "
                + "greatest(min(area_last) - max(area_first), 0) AS overlapSeconds, "
                + "max(area_first) AS overlapStart, "
                + "min(area_last) AS overlapEnd, "
                + "groupArray((area_idx, area_first, area_last, point_count)) AS segments "
                + "FROM (" + inner + ") "
                + "GROUP BY idfa_md5 "
                + "HAVING matchedAreaCount >= " + MIN_AREAS + " "
                + "ORDER BY matchedAreaCount DESC, overlapSeconds DESC"
                + settings;
    }

    /**
     * 解析 groupArray((area_idx, area_first, area_last, point_count)) 返回的数组。
     * ClickHouse JDBC 通常将其映射为 Object[]，其中每个元素是一个 tuple（Object[] 长度 4）。
     * 同时按区域累加去重命中人数（每个区域对同一人只计一次）。
     */
    private List<Map<String, Object>> parseSegments(Object segmentsObj, long[] hitAreaCounters) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (segmentsObj == null) {
            return result;
        }

        Object[] rows = AnalysisUtils.toObjectArray(segmentsObj);
        if (rows == null) {
            return result;
        }

        for (Object rowObj : rows) {
            Object[] tuple = AnalysisUtils.toObjectArray(rowObj);
            if (tuple == null || tuple.length < 4) {
                continue;
            }
            int areaIndex = ((Number) tuple[0]).intValue();
            long firstTime = ((Number) tuple[1]).longValue();
            long lastTime = ((Number) tuple[2]).longValue();
            long pointCount = ((Number) tuple[3]).longValue();

            if (areaIndex >= 0 && areaIndex < hitAreaCounters.length) {
                hitAreaCounters[areaIndex]++;
            }

            Map<String, Object> seg = new LinkedHashMap<>(4);
            seg.put("areaIndex", areaIndex);
            seg.put("firstTime", firstTime);
            seg.put("lastTime", lastTime);
            seg.put("stayedSeconds", lastTime - firstTime);
            seg.put("pointCount", pointCount);
            result.add(seg);
        }
        return result;
    }


}
