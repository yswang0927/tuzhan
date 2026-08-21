package com.tuzhan.trajectory;

import java.util.*;

/**
 * 轨迹切分器。
 *
 * 处理流程（针对 ClickHouse user_gps_detail 这类数据：同一 idfa_md5 + event_time 存在多条并行观测，
 * event_time 规整到 10 分钟网格）：
 * <pre>
 *   1. 校验入参同属一个 objectId
 *   2. 按 event_time 升序分组
 *   3. 同一时间片做空间聚类，结合上一代表点做"连续性选择"，得到每时刻一个代表点
 *      —— 避免"北京 + 上海取平均 = 江苏幽灵点"
 *   4. GPS 跳点删除（三点法）：prev→curr、curr→next 都超速但 prev→next 正常时，curr 是毛刺，删除而非切断
 *   5. 分段：时间间隔过大 / 速度超限 / 单步位移超限 时切断
 *   6. 过滤点数不足的轨迹，输出结构化 Trajectory
 * </pre>
 */
public final class TrajectorySplitter {

    // 地球半径（米）
    private static final double EARTH_RADIUS = 6371000.0;

    private TrajectorySplitter() {}

    /**
     * 切分轨迹，返回结构化轨迹列表。
     *
     * @param points 原始点列表（必须同属一个 objectId）
     * @param config 切分配置，为 null 时使用默认配置
     * @return 按起始时间升序的轨迹列表；输入为空时返回空列表
     * @throws IllegalArgumentException 当 points 中存在不同的 objectId
     */
    public static List<Trajectory> split(List<TrajectoryPoint> points, TrajectorySplitConfig config) {
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }
        if (config == null) {
            config = TrajectorySplitConfig.defaults();
        }

        // 1. 校验：全部点必须同属一个 objectId
        String objectId = validateSameObject(points);

        // 2. 按 event_time 升序分组
        //    TreeMap 保证时间升序；单用户查询量级（数千点）下开销可忽略
        Map<Long, List<TrajectoryPoint>> byTime = new TreeMap<>();
        for (TrajectoryPoint p : points) {
            byTime.computeIfAbsent(p.getEventTime(), k -> new ArrayList<>()).add(p);
        }

        // 3. 同一时间片空间聚类 + 连续性选择，得到每时刻一个代表点（在线：依赖上一代表点）
        List<TrajectoryPoint> reps = selectRepresentatives(byTime, objectId, config);

        // 4. GPS 跳点删除（三点法）
        if (config.isRemoveJumpPoints()) {
            reps = removeJumpPoints(reps, config);
        }

        // 5. 分段
        List<List<TrajectoryPoint>> segments = segment(reps, config);

        // 6. 过滤 + 结构化
        List<Trajectory> result = new ArrayList<>();
        for (List<TrajectoryPoint> seg : segments) {
            if (seg.size() >= config.getMinPoints()) {
                result.add(new Trajectory(objectId, seg));
            }
        }
        return result;
    }

    /** 使用默认配置切分。 */
    public static List<Trajectory> split(List<TrajectoryPoint> points) {
        return split(points, TrajectorySplitConfig.defaults());
    }

    /** 校验所有点同属一个 objectId，返回该 objectId。 */
    private static String validateSameObject(List<TrajectoryPoint> points) {
        String objectId = points.get(0).getObjectId();
        for (TrajectoryPoint p : points) {
            if (!Objects.equals(objectId, p.getObjectId())) {
                throw new IllegalArgumentException(
                        "All points must belong to the same objectId, but found ["
                                + objectId + "] and [" + p.getObjectId() + "]");
            }
        }
        return objectId;
    }

    /**
     * 对每个 event_time 的点做空间聚类，并结合上一代表点选出本时刻的代表点。
     * <p>选择策略：
     * <ul>
     *   <li>只有一个簇：直接取其质心；</li>
     *   <li>多个簇且尚无上一代表点（轨迹起点）：取点数最多的簇质心；</li>
     *   <li>多个簇且有上一代表点：取质心离上一代表点最近的簇（连续性）。</li>
     * </ul>
     * 只在簇内部做算术平均质心——簇内点已确认空间邻近，不会造出跨区域幽灵点。
     */
    private static List<TrajectoryPoint> selectRepresentatives(
            Map<Long, List<TrajectoryPoint>> byTime, String objectId, TrajectorySplitConfig config) {

        List<TrajectoryPoint> reps = new ArrayList<>(byTime.size());
        TrajectoryPoint previous = null;

        for (Map.Entry<Long, List<TrajectoryPoint>> e : byTime.entrySet()) {
            List<List<TrajectoryPoint>> clusters =
                    cluster(e.getValue(), config.getSameTimeClusterRadiusMeter());

            List<TrajectoryPoint> chosen;
            if (clusters.size() == 1) {
                chosen = clusters.get(0);
            } else if (previous == null) {
                // 起点无参照：取最大簇（观测最密集处，最可能是真实位置）
                chosen = clusters.get(0);
                for (List<TrajectoryPoint> c : clusters) {
                    if (c.size() > chosen.size()) {
                        chosen = c;
                    }
                }
            } else {
                // 取质心离上一代表点最近的簇
                chosen = null;
                double best = Double.MAX_VALUE;
                for (List<TrajectoryPoint> c : clusters) {
                    TrajectoryPoint ctr = centroid(c, objectId, e.getKey());
                    double d = haversine(previous.getLon(), previous.getLat(), ctr.getLon(), ctr.getLat());
                    if (d < best) {
                        best = d;
                        chosen = c;
                    }
                }
            }

            TrajectoryPoint rep = centroid(chosen, objectId, e.getKey());
            reps.add(rep);
            previous = rep;
        }
        return reps;
    }

    /**
     * 全链（complete-linkage）空间聚类：一个点只有与簇内<b>所有</b>已有点的距离都 ≤ radius 时才并入该簇。
     * <p>相比单链，避免了 A—B—C 逐跳相邻却首尾相距 2R 的"链式吞并"——簇直径被钳制在 radius 内，
     * 保证同一簇内的点确实彼此邻近（真正的同一位置观测），而非一条被拉长的链。
     * <p>为保证结果与输入顺序无关（确定性），先按 (lon, lat) 排序再贪心归簇。
     * 同一时间片点数通常很少，O(n^2) 足够。
     */
    private static List<List<TrajectoryPoint>> cluster(List<TrajectoryPoint> points, double radiusMeter) {
        List<TrajectoryPoint> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble(TrajectoryPoint::getLon)
                .thenComparingDouble(TrajectoryPoint::getLat));

        List<List<TrajectoryPoint>> clusters = new ArrayList<>();
        for (TrajectoryPoint p : sorted) {
            List<TrajectoryPoint> target = null;
            for (List<TrajectoryPoint> c : clusters) {
                if (fitsAll(p, c, radiusMeter)) {
                    target = c;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                clusters.add(target);
            }
            target.add(p);
        }
        return clusters;
    }

    /** complete-linkage 判定：p 与簇内每一个点的距离都 ≤ radius。 */
    private static boolean fitsAll(TrajectoryPoint p, List<TrajectoryPoint> cluster, double radiusMeter) {
        for (TrajectoryPoint q : cluster) {
            if (haversine(p.getLon(), p.getLat(), q.getLon(), q.getLat()) > radiusMeter) {
                return false;
            }
        }
        return true;
    }

    /** 计算一组点的算术平均质心，生成代表点。 */
    private static TrajectoryPoint centroid(List<TrajectoryPoint> group, String objectId, long eventTime) {
        double sumLon = 0.0;
        double sumLat = 0.0;
        for (TrajectoryPoint p : group) {
            sumLon += p.getLon();
            sumLat += p.getLat();
        }
        int n = group.size();
        TrajectoryPoint c = new TrajectoryPoint();
        c.setObjectId(objectId);
        c.setEventTime(eventTime);
        c.setLon(sumLon / n);
        c.setLat(sumLat / n);
        return c;
    }

    /**
     * GPS 跳点删除（三点法）。
     * 对 prev → curr → next：若 prev→curr 与 curr→next 均超速，而 prev→next 正常，
     * 则 curr 判定为瞬时跳变毛刺，删除。删除后以 prev 为基准继续检查后续点。
     */
    private static List<TrajectoryPoint> removeJumpPoints(List<TrajectoryPoint> pts, TrajectorySplitConfig config) {
        if (pts.size() < 3) {
            return pts;
        }
        double maxSpeed = config.getMaxSpeedMeterPerSec();
        long maxGap = config.getMaxTimeGapSec();
        List<TrajectoryPoint> result = new ArrayList<>(pts.size());
        result.add(pts.get(0));

        int i = 1;
        while (i < pts.size() - 1) {
            TrajectoryPoint prev = result.get(result.size() - 1);
            TrajectoryPoint curr = pts.get(i);
            TrajectoryPoint next = pts.get(i + 1);

            if (isJump(prev, curr, next, maxSpeed, maxGap)) {
                // 丢弃 curr，prev 不变，继续检查 next
                i++;
            } else {
                result.add(curr);
                i++;
            }
        }
        // 末点始终保留（三点法覆盖不到，末点无 next 可判）
        result.add(pts.get(pts.size() - 1));
        return result;
    }

    /**
     * 三点跳变判定：两侧都超速、跨越正常，则中点为跳点。
     * <p>护栏：
     * <ul>
     *   <li>时间差非正：不判为跳点，交由分段处理；</li>
     *   <li>prev→curr 或 curr→next 跨越大时间间隔（> maxTimeGap）：这本就是轨迹边界，
     *       curr 很可能属于另一段轨迹，绝不能当毛刺删除，交由分段处理。</li>
     * </ul>
     */
    private static boolean isJump(TrajectoryPoint prev, TrajectoryPoint curr, TrajectoryPoint next,
                                  double maxSpeed, long maxTimeGapSec) {
        long dt1 = curr.getEventTime() - prev.getEventTime();
        long dt2 = next.getEventTime() - curr.getEventTime();
        long dt3 = next.getEventTime() - prev.getEventTime();
        if (dt1 <= 0 || dt2 <= 0 || dt3 <= 0) {
            return false;
        }
        // 跨越大时间间隔的点是轨迹边界候选，不是 GPS 毛刺，不删除
        if (dt1 > maxTimeGapSec || dt2 > maxTimeGapSec) {
            return false;
        }
        double s1 = haversine(prev.getLon(), prev.getLat(), curr.getLon(), curr.getLat()) / dt1;
        double s2 = haversine(curr.getLon(), curr.getLat(), next.getLon(), next.getLat()) / dt2;
        double s3 = haversine(prev.getLon(), prev.getLat(), next.getLon(), next.getLat()) / dt3;
        return s1 > maxSpeed && s2 > maxSpeed && s3 <= maxSpeed;
    }

    /**
     * 分段：遍历代表点，遇到断点开启新段。
     * 断点条件（满足其一）：时间间隔 > maxTimeGap，或位移速度 > maxSpeed，或单步位移 > maxStepDistance。
     */
    private static List<List<TrajectoryPoint>> segment(List<TrajectoryPoint> pts, TrajectorySplitConfig config) {
        List<List<TrajectoryPoint>> segments = new ArrayList<>();
        if (pts.isEmpty()) {
            return segments;
        }

        List<TrajectoryPoint> current = new ArrayList<>();
        current.add(pts.get(0));

        for (int i = 1; i < pts.size(); i++) {
            TrajectoryPoint prev = pts.get(i - 1);
            TrajectoryPoint curr = pts.get(i);

            long timeDiff = curr.getEventTime() - prev.getEventTime();
            double dist = haversine(prev.getLon(), prev.getLat(), curr.getLon(), curr.getLat());
            // 防御：时间非严格递增时直接切断，避免除零/负速度
            boolean cut;
            if (timeDiff <= 0) {
                cut = true;
            } else {
                double speed = dist / timeDiff;
                cut = timeDiff > config.getMaxTimeGapSec()
                        || speed > config.getMaxSpeedMeterPerSec()
                        || dist > config.getMaxStepDistanceMeter();
            }

            if (cut) {
                segments.add(current);
                current = new ArrayList<>();
            }
            current.add(curr);
        }
        segments.add(current);
        return segments;
    }

    /**
     * Haversine 公式计算两点距离（米）
     */
    public static double haversine(double lon1, double lat1, double lon2, double lat2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double dLat = Math.toRadians(lat2 - lat1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.asin(Math.sqrt(a));
        return EARTH_RADIUS * c;
    }
}

