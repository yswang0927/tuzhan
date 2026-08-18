package com.tuzhan.analysis.handler;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
 * 区域人员分析：用户圈选单一区域+固定时间段，输出该区域内的所有人员、出现频次、首次/末次时间。
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>命中条件：在给定时间段内、落入圈选多边形的所有人员。</li>
 *   <li>频次：该人在区域内的定位点数 count()；首次/末次：min/max(event_time)。</li>
 *   <li>区域判定与聚合全部下推 ClickHouse（bbox 预过滤 + pointInPolygon + GROUP BY），
 *       结果用 ResultSetHandler 逐行流式写盘，几十万级结果内存恒定。</li>
 *   <li>输出格式：JSONL（每行一个 JSON 对象），文件为 {resultSaveDir}/{taskId}/result.jsonl。</li>
 * </ul>
 */
@Component
public class AreaObjectAnalysisHandler extends BaseRepository implements AsyncTaskHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AreaObjectAnalysisHandler.class);

    @Override
    public AsyncTaskType supportType() {
        return AsyncTaskType.AREA_PERSON_ANALYSIS;
    }

    @Override
    public void validate(JsonNode queryParams) {
        if (queryParams == null) {
            throw new BadRequestException("查询参数不能为空");
        }

        JsonNode area = queryParams.get("area");
        JsonNode time = queryParams.get("time");

        if (area == null || !area.isArray() || area.size() < AnalysisUtils.MIN_POLYGON_VERTICES) {
            throw new BadRequestException("区域至少需要 " + AnalysisUtils.MIN_POLYGON_VERTICES + " 个顶点");
        }
        for (int p = 0; p < area.size(); p++) {
            JsonNode point = area.get(p);
            if (point == null || !point.isArray() || point.size() < 2
                    || !point.get(0).isNumber() || !point.get(1).isNumber()) {
                throw new BadRequestException("区域的顶点[" + p + "] 必须是 [lon, lat] 数值对");
            }
        }
        // 时间段校验（同时得到 start<=end 的保证）
        AnalysisUtils.parseTimeRange(time);
    }

    @Override
    public TaskExecuteResult execute(AsyncTaskEntity task, JsonNode queryParams, Path resultSaveDir) throws Exception {
        JsonNode area = queryParams.get("area");
        JsonNode time = queryParams.get("time");

        // 1. 构造下推 SQL：区域内按人聚合，得到频次与首末时间。
        String sql = buildAnalysisSql(area, time);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Area person analysis SQL for task {}: {}", task.getTaskId(), sql);
        }

        // 2. 结果输出目录/文件
        Path taskDir = resultSaveDir.resolve(task.getTaskId());
        Files.createDirectories(taskDir);
        Path resultFile = taskDir.resolve("result.jsonl");

        // 3. 逐行流式消费 ResultSet 并写盘，内存恒定
        long totalPersons;
        try (BufferedWriter writer = Files.newBufferedWriter(resultFile, StandardCharsets.UTF_8)) {
            totalPersons = IDFA_JDBC.query(sql, rs -> {
                long persons = 0L;
                while (rs.next()) {
                    String idfa = rs.getString("idfa_md5");
                    long pointCount = rs.getLong("pointCount");
                    long firstTime = rs.getLong("firstTime");
                    long lastTime = rs.getLong("lastTime");

                    Map<String, Object> row = new LinkedHashMap<>(5);
                    row.put("objectId", idfa);
                    row.put("pointCount", pointCount);
                    row.put("firstTime", firstTime);
                    row.put("lastTime", lastTime);
                    row.put("stayedSeconds", lastTime - firstTime);

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

        LOG.info("Task {} area person analysis done, matched {} persons", task.getTaskId(), totalPersons);

        // 4. 汇总 meta
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalPersons", totalPersons);
        meta.put("format", "jsonl");

        return new TaskExecuteResult(resultFile.toString(), meta);
    }

    /**
     * 构造区域人员分析下推 SQL：先用 bbox + 时间/分区裁剪挡掉绝大多数点，再对幸存者跑 pointInPolygon，
     * 最后按 idfa 聚合出频次与首末时间。多边形顶点与时间段均解析为数值后拼接（无字符串注入风险）。
     */
    private String buildAnalysisSql(JsonNode area, JsonNode time) {
        long[] range = AnalysisUtils.parseTimeRange(time);
        String polygon = AnalysisUtils.buildPolygonLiteral(area);
        double[] bbox = AnalysisUtils.boundingBox(area); // [minLon, minLat, maxLon, maxLat]

        return "SELECT idfa_md5, "
                + "count() AS pointCount, "
                + "min(event_time) AS firstTime, "
                + "max(event_time) AS lastTime "
                + "FROM idfa_gps_detail "
                + "WHERE dt BETWEEN toDate(" + range[0] + ") AND toDate(" + range[1] + ") "
                + "AND event_time BETWEEN " + range[0] + " AND " + range[1] + " "
                // bbox 预过滤：先用廉价的经纬度矩形范围挡掉绝大多数点，再对幸存者跑 pointInPolygon。
                + "AND lon BETWEEN " + bbox[0] + " AND " + bbox[2] + " "
                + "AND lat BETWEEN " + bbox[1] + " AND " + bbox[3] + " "
                + "AND pointInPolygon((lon, lat), " + polygon + ") "
                + "GROUP BY idfa_md5 "
                + "ORDER BY pointCount DESC"
                + AnalysisUtils.buildClickHouseSettings();
    }

}
