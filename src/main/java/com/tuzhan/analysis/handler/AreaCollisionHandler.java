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
 * 区域碰撞分析：用户圈选多个区域 + 各自时间区间，设置时间关系（交集/并集/差集），输出符合关系的人员列表与出现次数。
 *
 * <p>集合关系（relation，作用于“各区域命中人群”这几个集合之间）：</p>
 * <ul>
 *   <li><b>union（并集）</b>：出现在<em>任意一个</em>区域的人（matchedAreaCount &ge; 1）。</li>
 *   <li><b>intersection（交集）</b>：出现在<em>全部</em>区域的人（matchedAreaCount = 区域数）。</li>
 *   <li><b>difference（差集）</b>：A\(其余) —— 只出现在<em>第 1 个</em>区域、其余区域都没出现的人。</li>
 * </ul>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>每个区域生成一个带时间/分区裁剪 + bbox 预过滤 + pointInPolygon 的子查询，UNION ALL 后外层按人聚合。</li>
 *   <li>集合关系全部由外层 HAVING 表达，判定与聚合都下推 ClickHouse，结果 ResultSetHandler 逐行流式写盘，内存恒定。</li>
 *   <li>出现次数：sum(各命中区域点数)；同时给出 matchedAreaCount 与各区域 segment 明细。</li>
 *   <li>输出格式：JSONL（每行一个 JSON 对象），文件为 {resultSaveDir}/{taskId}/result.jsonl。</li>
 * </ul>
 */
@Component
public class AreaCollisionHandler extends BaseRepository implements AsyncTaskHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AreaCollisionHandler.class);

    /** 至少要圈选的区域数。 */
    private static final int MIN_AREAS = 2;

    private static final String REL_UNION = "union";
    private static final String REL_INTERSECTION = "intersection";
    private static final String REL_DIFFERENCE = "difference";

    @Override
    public AsyncTaskType supportType() {
        return AsyncTaskType.AREA_COLLISION;
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
                throw new BadRequestException("区域[" + (i + 1) + "] 至少需要 " + AnalysisUtils.MIN_POLYGON_VERTICES + " 个顶点");
            }
            for (int p = 0; p < polygon.size(); p++) {
                JsonNode point = polygon.get(p);
                if (point == null || !point.isArray() || point.size() < 2
                        || !point.get(0).isNumber() || !point.get(1).isNumber()) {
                    throw new BadRequestException("区域[" + (i + 1) + "] 的顶点[" + p + "] 必须是 [lon, lat] 数值对");
                }
            }
            // 时间段校验（同时得到 start<=end 的保证）
            AnalysisUtils.parseTimeRange(times.get(i), i);
        }

        // 关系校验（归一化后必须是三者之一）
        normalizeRelation(queryParams.get("relation"));
    }

    @Override
    public TaskExecuteResult execute(AsyncTaskEntity task, JsonNode queryParams, Path resultSaveDir) throws Exception {
        JsonNode areas = queryParams.get("areas");
        JsonNode times = queryParams.get("times");
        String relation = normalizeRelation(queryParams.get("relation"));
        final int areaCount = areas.size();

        // 1. 构造下推 SQL：每区域一个 pointInPolygon 聚合子查询，UNION ALL 后按人合并，关系用 HAVING 表达。
        String sql = buildCollisionSql(areas, times, relation);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Area collision SQL for task {} (relation={}): {}", task.getTaskId(), relation, sql);
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
                    long occurrenceCount = rs.getLong("occurrenceCount");
                    // segments: groupArray((area_idx, area_first, area_last, point_count))
                    List<Map<String, Object>> segments = parseSegments(rs.getObject("segments"), hitAreaCounters);

                    Map<String, Object> row = new LinkedHashMap<>(4);
                    row.put("objectId", idfa);
                    row.put("matchedAreaCount", matchedAreaCount);
                    row.put("occurrenceCount", occurrenceCount);
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

        LOG.info("Task {} area collision ({}) done, matched {} persons across {} areas",
                task.getTaskId(), relation, totalPersons, areaCount);

        // 4. 汇总 meta
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalPersons", totalPersons);
        meta.put("areaCount", areaCount);
        meta.put("relation", relation);
        List<Long> perAreaHits = new ArrayList<>(areaCount);
        for (long c : hitAreaCounters) {
            perAreaHits.add(c);
        }
        meta.put("perAreaHits", perAreaHits);
        meta.put("format", "jsonl");

        return new TaskExecuteResult(resultFile.toString(), meta);
    }

    /**
     * 构造区域碰撞下推 SQL。每个区域生成一个带 pointInPolygon 过滤与时间裁剪的子查询，
     * UNION ALL 后外层按 idfa 聚合，集合关系由 HAVING 表达。
     * 多边形顶点与时间段均解析为数值后拼接（无字符串注入风险）。
     */
    private String buildCollisionSql(JsonNode areas, JsonNode times, String relation) {
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

        String having = buildHaving(relation, areas.size());

        return "SELECT idfa_md5, "
                + "countDistinct(area_idx) AS matchedAreaCount, "
                + "sum(point_count) AS occurrenceCount, "
                + "groupArray((area_idx, area_first, area_last, point_count)) AS segments "
                + "FROM (" + inner + ") "
                + "GROUP BY idfa_md5 "
                + "HAVING " + having + " "
                + "ORDER BY occurrenceCount DESC"
                + AnalysisUtils.buildClickHouseSettings();
    }

    /**
     * 根据集合关系生成 HAVING 条件（作用于外层按人聚合后的分组）。
     * <ul>
     *   <li>union：出现在任意区域，matchedAreaCount &ge; 1（分组存在即成立）。</li>
     *   <li>intersection：出现在全部区域，matchedAreaCount = 区域数。</li>
     *   <li>difference：A\(其余)，只出现在第 1 个区域（area_idx 恒为 0）。</li>
     * </ul>
     */
    private String buildHaving(String relation, int areaCount) {
        switch (relation) {
            case REL_INTERSECTION:
                return "matchedAreaCount = " + areaCount;
            case REL_DIFFERENCE:
                // 只命中区域 0：去重区域数为 1 且该区域下标为 0（min=max=0）。
                return "matchedAreaCount = 1 AND min(area_idx) = 0 AND max(area_idx) = 0";
            case REL_UNION:
            default:
                return "matchedAreaCount >= 1";
        }
    }

    /**
     * 归一化并校验集合关系。接受中英文别名，缺省为 intersection（区域碰撞最常见诉求）。
     */
    private String normalizeRelation(JsonNode relationNode) {
        if (relationNode == null || relationNode.isNull()) {
            return REL_INTERSECTION;
        }
        String raw = relationNode.asText("").trim().toLowerCase();
        switch (raw) {
            case "":
                return REL_INTERSECTION;
            case REL_UNION:
            case "并集":
            case "或":
                return REL_UNION;
            case REL_INTERSECTION:
            case "交集":
            case "与":
                return REL_INTERSECTION;
            case REL_DIFFERENCE:
            case "差集":
            case "非":
                return REL_DIFFERENCE;
            default:
                throw new BadRequestException("不支持的时间/集合关系: " + relationNode.asText()
                        + "，可选 union(并集)/intersection(交集)/difference(差集)");
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

            Map<String, Object> seg = new LinkedHashMap<>(5);
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
