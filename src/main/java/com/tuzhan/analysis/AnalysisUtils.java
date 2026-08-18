package com.tuzhan.analysis;

import java.time.ZoneId;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.sisyphsu.dateparser.DateParserUtils;
import com.tuzhan.exception.BadRequestException;
import com.tuzhan.util.JsonObjectMapper;

public class AnalysisUtils {

    /** 时间字符串按此时区换算为绝对时间戳（秒）。 */
    public static final ZoneId ZONE = ZoneId.of(JsonObjectMapper.DEFAULT_TIMEZONE);

    /** 单个多边形至少需要的顶点数。 */
    public static final int MIN_POLYGON_VERTICES = 3;

    /**
     * 单次查询的服务端最大执行时间（秒）。超时后由 ClickHouse 主动中断查询，
     * 作为 Java 侧无法可靠设置 statement timeout 时的兜底，防止大查询拖垮集群。
     */
    public static final int MAX_EXECUTION_TIME_SECONDS = 60;

    /** 单次查询使用的线程数上限，控制单查询对集群 CPU 的占用（0 表示不显式限制）。 */
    public static final int MAX_THREADS = 8;

    private AnalysisUtils() {}

    /**
     * 计算多边形外接矩形（bounding box），用于下推的 lon/lat 范围预过滤。
     * 返回 [minLon, minLat, maxLon, maxLat]。
     */
    public static double[] boundingBox(JsonNode polygon) {
        double minLon = Double.POSITIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        for (int p = 0; p < polygon.size(); p++) {
            JsonNode point = polygon.get(p);
            double lon = point.get(0).asDouble();
            double lat = point.get(1).asDouble();
            if (lon < minLon) {
                minLon = lon;
            }
            if (lon > maxLon) {
                maxLon = lon;
            }
            if (lat < minLat) {
                minLat = lat;
            }
            if (lat > maxLat) {
                maxLat = lat;
            }
        }
        return new double[]{ minLon, minLat, maxLon, maxLat };
    }

    /**
     * 把一个多边形的顶点数组转成 ClickHouse 数组字面量：[(lon,lat),(lon,lat),...]。
     */
    public static String buildPolygonLiteral(JsonNode polygon) {
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
    public static long[] parseTimeRange(JsonNode range, int areaIndex) {
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

    private static long parseEpochSecond(JsonNode timeNode, int areaIndex) {
        if (timeNode == null || !timeNode.isTextual()) {
            throw new BadRequestException("区域[" + areaIndex + "] 的时间必须是文本");
        }
        try {
            return DateParserUtils.parseDateTime(timeNode.asText()).atZone(ZONE).toEpochSecond();
        } catch (Exception e) {
            throw new BadRequestException("区域[" + areaIndex + "] 的时间无法解析: " + timeNode.asText());
        }
    }

    /**
     * 解析一个 ["start","end"] 时间段为 [startEpochSec, endEpochSec]，并保证 start<=end。
     */
    public static long[] parseTimeRange(JsonNode range) {
        if (range == null || !range.isArray() || range.size() < 2) {
            throw new BadRequestException("时间段必须是 [start, end]");
        }
        long start = parseEpochSecond(range.get(0));
        long end = parseEpochSecond(range.get(1));
        if (start > end) {
            long tmp = start;
            start = end;
            end = tmp;
        }
        return new long[]{start, end};
    }

    private static long parseEpochSecond(JsonNode timeNode) {
        if (timeNode == null || !timeNode.isTextual()) {
            throw new BadRequestException("时间必须是文本");
        }
        try {
            return DateParserUtils.parseDateTime(timeNode.asText()).atZone(ZONE).toEpochSecond();
        } catch (Exception e) {
            throw new BadRequestException("时间无法解析: " + timeNode.asText());
        }
    }


    /**
     * 把 ClickHouse JDBC 返回的数组/tuple 统一转成 Object[]。可能是 Object[] 或 java.sql.Array。
     */
    public static Object[] toObjectArray(Object obj) {
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
                e.printStackTrace();
                //LOG.warn("Failed to unwrap java.sql.Array: {}", e.getMessage());
            }
        }
        return null;
    }

    /**
     * 服务端限流/超时兜底：max_execution_time 让 ClickHouse 到点主动中断查询，
     * max_threads 控制单查询 CPU 占用，避免大查询拖垮集群。
     */
    public static String buildClickHouseSettings() {
        StringBuilder sb = new StringBuilder(" SETTINGS max_execution_time = ").append(MAX_EXECUTION_TIME_SECONDS);
        if (MAX_THREADS > 0) {
            sb.append(", max_threads = ").append(MAX_THREADS);
        }
        return sb.toString();
    }

}
