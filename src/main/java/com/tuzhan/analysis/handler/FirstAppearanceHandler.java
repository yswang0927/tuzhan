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
 * 首次出现人员：指定区域+时间段，找出在该段时间内出现但历史从未出现过的人员（即：新面孔）、首次出现时间。
 *
 * <p>语义定义：</p>
 * <ul>
 *   <li>候选：时间段 [start, end] 内落入圈选区域的人员（区域受限，pointInPolygon 判定）。</li>
 *   <li>“历史从未出现”：在 start 之前的<strong>全量数据（不限区域）</strong>中没有任何定位记录，
 *       即该人第一次进入我们的观测范围就发生在本时间段——这才是“新面孔”。</li>
 *   <li>结果 = 候选集 LEFT ANTI JOIN 历史集，取候选中历史无记录的人；firstTime 即其区域内首次时间，
 *       因其为全局新面孔，该时间也等于其真正的首次出现时间。</li>
 *   <li>数据约束：底表 TTL 为 90 天，故“历史”最远只能回溯到保留窗口边界，超出部分无从判定。</li>
 *   <li>输出格式：JSONL（每行一个 JSON 对象），文件为 {resultSaveDir}/{taskId}/result.jsonl。</li>
 * </ul>
 */
@Component
public class FirstAppearanceHandler extends BaseRepository implements AsyncTaskHandler {
    private static final Logger LOG = LoggerFactory.getLogger(FirstAppearanceHandler.class);

    @Override
    public AsyncTaskType supportType() {
        return AsyncTaskType.FIRST_APPEARANCE;
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

        // 1. 构造下推 SQL：区域内候选 LEFT ANTI JOIN 历史出现者，得到新面孔。
        String sql = buildFirstAppearanceSql(area, time);
        if (LOG.isDebugEnabled()) {
            LOG.debug("First appearance SQL for task {}: {}", task.getTaskId(), sql);
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
                    long firstTime = rs.getLong("firstTime");
                    long lastTime = rs.getLong("lastTime");
                    long pointCount = rs.getLong("pointCount");

                    Map<String, Object> row = new LinkedHashMap<>(5);
                    row.put("objectId", idfa);
                    row.put("firstTime", firstTime);
                    row.put("lastTime", lastTime);
                    row.put("stayedSeconds", lastTime - firstTime);
                    row.put("pointCount", pointCount);

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

        LOG.info("Task {} first appearance done, matched {} new persons", task.getTaskId(), totalPersons);

        // 4. 汇总 meta
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("totalPersons", totalPersons);
        meta.put("format", "jsonl");

        return new TaskExecuteResult(resultFile.toString(), meta);
    }

    /**
     * 构造首次出现人员下推 SQL。
     * <p>候选子查询：时间段内区域命中者，聚合出首末时间与频次。</p>
     * <p>历史子查询：start 之前在全量数据里出现过的人（仅取 idfa_md5，无空间过滤）。</p>
     * <p>LEFT ANTI JOIN：保留候选中历史无记录的人。多边形顶点与时间段均解析为数值后拼接（无字符串注入风险）。</p>
     */
    private String buildFirstAppearanceSql(JsonNode area, JsonNode time) {
        long[] range = AnalysisUtils.parseTimeRange(time);
        long start = range[0];
        long end = range[1];
        String polygon = AnalysisUtils.buildPolygonLiteral(area);
        double[] bbox = AnalysisUtils.boundingBox(area); // [minLon, minLat, maxLon, maxLat]

        // 候选：时间段内落入区域的人员
        String candidate = "SELECT idfa_md5, "
                + "min(event_time) AS firstTime, "
                + "max(event_time) AS lastTime, "
                + "count() AS pointCount "
                + "FROM idfa_gps_detail "
                + "WHERE dt BETWEEN toDate(" + start + ") AND toDate(" + end + ") "
                + "AND event_time BETWEEN " + start + " AND " + end + " "
                // bbox 预过滤：先用廉价的经纬度矩形范围挡掉绝大多数点，再对幸存者跑 pointInPolygon。
                + "AND lon BETWEEN " + bbox[0] + " AND " + bbox[2] + " "
                + "AND lat BETWEEN " + bbox[1] + " AND " + bbox[3] + " "
                + "AND pointInPolygon((lon, lat), " + polygon + ") "
                + "GROUP BY idfa_md5";

        // 历史：start 之前出现过的人（不限区域）。dt <= toDate(start) 做分区裁剪，event_time < start 精确切边界。
        String history = "SELECT idfa_md5 "
                + "FROM idfa_gps_detail "
                + "WHERE dt <= toDate(" + start + ") AND event_time < " + start + " "
                + "GROUP BY idfa_md5";

        // LEFT ANTI JOIN：候选中在历史里找不到匹配（即从未出现过）的人才保留。
        return "SELECT c.idfa_md5 AS idfa_md5, c.firstTime AS firstTime, c.lastTime AS lastTime, c.pointCount AS pointCount "
                + "FROM (" + candidate + ") AS c "
                + "LEFT ANTI JOIN (" + history + ") AS h USING (idfa_md5) "
                + "ORDER BY firstTime ASC"
                + AnalysisUtils.buildClickHouseSettings();
    }

}
