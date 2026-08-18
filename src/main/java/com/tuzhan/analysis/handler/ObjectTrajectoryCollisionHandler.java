package com.tuzhan.analysis.handler;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
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

    /** 时间字符串格式：兼容 "2026/08/01 10:20:00"。 */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    /** 时间字符串按此时区换算为绝对时间戳（秒）。 */
    private static final ZoneId ZONE = ZoneId.of(JsonObjectMapper.DEFAULT_TIMEZONE);

    /** 单个多边形至少需要的顶点数。 */
    private static final int MIN_POLYGON_VERTICES = 3;

    /** 至少要圈选的区域数。 */
    private static final int MIN_AREAS = 2;

    /**
     * 单次碰撞查询的服务端最大执行时间（秒）。超时后由 ClickHouse 主动中断查询，
     * 作为 Java 侧无法可靠设置 statement timeout 时的兜底，防止大查询拖垮集群。
     */
    private static final int MAX_EXECUTION_TIME_SECONDS = 60;

    /** 单次查询使用的线程数上限，控制单查询对集群 CPU 的占用（0 表示不显式限制）。 */
    private static final int MAX_THREADS = 8;

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
            if (polygon == null || !polygon.isArray() || polygon.size() < MIN_POLYGON_VERTICES) {
                throw new BadRequestException("区域[" + (i+1) + "] 至少需要 " + MIN_POLYGON_VERTICES + " 个顶点");
            }
            for (int p = 0; p < polygon.size(); p++) {
                JsonNode point = polygon.get(p);
                if (point == null || !point.isArray() || point.size() < 2
                        || !point.get(0).isNumber() || !point.get(1).isNumber()) {
                    throw new BadRequestException("区域[" + (i+1) + "] 的顶点[" + p + "] 必须是 [lon, lat] 数值对");
                }
            }
            // 时间段校验（同时得到 start<=end 的保证）
            parseTimeRange(times.get(i), i);
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
                    long totalOverlapSeconds = rs.getLong("totalOverlapSeconds");
                    // segments: groupArray((area_idx, area_first, area_last, pointCount))
                    // ClickHouse JDBC 返回为数组，逐元素解析
                    List<Map<String, Object>> segments = parseSegments(rs.getObject("segments"), hitAreaCounters);

                    Map<String, Object> row = new LinkedHashMap<>(4);
                    row.put("idfaMd5", idfa);
                    row.put("matchedAreaCount", matchedAreaCount);
                    row.put("totalOverlapSeconds", totalOverlapSeconds);
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
            long[] range = parseTimeRange(times.get(i), i);
            JsonNode polygonNode = areas.get(i);
            String polygon = buildPolygonLiteral(polygonNode);
            double[] bbox = boundingBox(polygonNode); // [minLon, minLat, maxLon, maxLat]

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

        String settings = buildSettings();
        return "SELECT idfa_md5, "
                + "countDistinct(area_idx) AS matchedAreaCount, "
                + "sum(area_last - area_first) AS totalOverlapSeconds, "
                + "groupArray((area_idx, area_first, area_last, point_count)) AS segments "
                + "FROM (" + inner + ") "
                + "GROUP BY idfa_md5 "
                + "HAVING matchedAreaCount >= " + MIN_AREAS + " "
                + "ORDER BY matchedAreaCount DESC, totalOverlapSeconds DESC"
                + settings;
    }

    /**
     * 服务端限流/超时兜底：max_execution_time 让 ClickHouse 到点主动中断查询，
     * max_threads 控制单查询 CPU 占用，避免大查询拖垮集群。
     */
    private String buildSettings() {
        StringBuilder sb = new StringBuilder(" SETTINGS max_execution_time = ").append(MAX_EXECUTION_TIME_SECONDS);
        if (MAX_THREADS > 0) {
            sb.append(", max_threads = ").append(MAX_THREADS);
        }
        return sb.toString();
    }

    /**
     * 计算多边形外接矩形（bounding box），用于下推的 lon/lat 范围预过滤。
     * 返回 [minLon, minLat, maxLon, maxLat]。
     */
    private double[] boundingBox(JsonNode polygon) {
        double minLon = Double.POSITIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        for (int p = 0; p < polygon.size(); p++) {
            JsonNode point = polygon.get(p);
            double lon = point.get(0).asDouble();
            double lat = point.get(1).asDouble();
            if (lon < minLon) minLon = lon;
            if (lon > maxLon) maxLon = lon;
            if (lat < minLat) minLat = lat;
            if (lat > maxLat) maxLat = lat;
        }
        return new double[]{minLon, minLat, maxLon, maxLat};
    }

    /**
     * 把一个多边形的顶点数组转成 ClickHouse 数组字面量：[(lon,lat),(lon,lat),...]。
     */
    private String buildPolygonLiteral(JsonNode polygon) {
        StringBuilder sb = new StringBuilder("[");
        for (int p = 0; p < polygon.size(); p++) {
            JsonNode point = polygon.get(p);
            if (p > 0) {
                sb.append(',');
            }
            sb.append('(').append(point.get(0).asDouble()).append(',').append(point.get(1).asDouble()).append(')');
        }
        return sb.append(']').toString();
    }

    /**
     * 解析一个 ["start","end"] 时间段为 [startEpochSec, endEpochSec]，并保证 start<=end。
     */
    private long[] parseTimeRange(JsonNode range, int areaIndex) {
        if (range == null || !range.isArray() || range.size() < 2) {
            throw new BadRequestException("区域[" + areaIndex + "] 的时间段必须是 [start, end]");
        }
        long start = parseEpochSecond(range.get(0), areaIndex);
        long end = parseEpochSecond(range.get(1), areaIndex);
        if (start > end) {
            long tmp = start;
            start = end;
            end = tmp;
        }
        return new long[]{start, end};
    }

    private long parseEpochSecond(JsonNode timeNode, int areaIndex) {
        if (timeNode == null || !timeNode.isTextual()) {
            throw new BadRequestException("区域[" + areaIndex + "] 的时间必须是文本，格式 yyyy/MM/dd HH:mm:ss");
        }
        try {
            return LocalDateTime.parse(timeNode.asText().trim(), TIME_FMT).atZone(ZONE).toEpochSecond();
        } catch (Exception e) {
            throw new BadRequestException("区域[" + areaIndex + "] 的时间无法解析: " + timeNode.asText());
        }
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

        Object[] rows = toObjectArray(segmentsObj);
        if (rows == null) {
            return result;
        }

        for (Object rowObj : rows) {
            Object[] tuple = toObjectArray(rowObj);
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

    /**
     * 把 ClickHouse JDBC 返回的数组/tuple 统一转成 Object[]。可能是 Object[] 或 java.sql.Array。
     */
    private Object[] toObjectArray(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Object[]) {
            return (Object[]) obj;
        }
        if (obj instanceof java.sql.Array) {
            try {
                Object arr = ((java.sql.Array) obj).getArray();
                if (arr instanceof Object[]) {
                    return (Object[]) arr;
                }
            } catch (Exception e) {
                LOG.warn("Failed to unwrap java.sql.Array: {}", e.getMessage());
            }
        }
        return null;
    }

}
