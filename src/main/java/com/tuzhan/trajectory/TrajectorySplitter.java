package com.tuzhan.trajectory;

import java.util.*;
import java.util.stream.Collectors;

public class TrajectorySplitter {

    // 地球半径（米）
    private static final double EARTH_RADIUS = 6371000.0;

    /**
     * 切分轨迹
     *
     * @param points              原始点列表（同一 objectId）
     * @param timeThresholdSec    时间间隔阈值（秒），默认建议 1800（30分钟）
     * @param maxSpeedMeterPerSec 速度上限（米/秒），相邻点位移速度超过该值视为异常跳变而切断，
     *                            默认建议 55（约 200km/h，可覆盖高速/高铁出行）
     *   只处理地面出行(步行/骑行/开车/高铁),想在"疑似飞行或数据跳变"处切断 → 取 ~85 m/s(约 300km/h,高铁上限) 比较合适,我之前填的 55 偏保守,高铁会被误切。
     *   也想把飞机当成连续轨迹保留 → 得放到 ~250 m/s 以上。
     *   只关心市内活动,想把任何城际长途都切开 → 取 ~35 m/s(约 120km/h) 就够。
     * @param minPoints           一条有效轨迹最少点数，默认 3
     * @return 切分并过滤后的点列表（已设置 trajId）
     */
    public static List<TrajectoryPoint> split(List<TrajectoryPoint> points,
                                       long timeThresholdSec,
                                       double maxSpeedMeterPerSec,
                                       int minPoints) {

        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 按 event_time 聚合去噪：同一时间截面存在多条并行观测记录，
        //    将其塌缩为一个质心代表点，得到"每时刻一个点"后再串线。
        //    聚合结果同时保证按时间升序（TreeMap 有序），无需再排序。
        List<TrajectoryPoint> sorted = aggregateByEventTime(points);

        // 2. 切分并分配 trajId
        int currentTrajId = 0;
        sorted.get(0).setTrajId(currentTrajId);

        for (int i = 1; i < sorted.size(); i++) {
            TrajectoryPoint prev = sorted.get(i - 1);
            TrajectoryPoint curr = sorted.get(i);

            long timeDiff = curr.getEventTime() - prev.getEventTime();
            double dist = haversine(prev.getLon(), prev.getLat(), curr.getLon(), curr.getLat());
            // 聚合后每个 event_time 仅一个点，相邻点时间必然不同，timeDiff > 0
            double speed = dist / timeDiff;
            // 切断条件：时间间隔过大（停歇断点） 或 位移速度超上限（异常跳变）
            if (timeDiff > timeThresholdSec || speed > maxSpeedMeterPerSec) {
                currentTrajId++;
            }
            curr.setTrajId(currentTrajId);
        }

        // 3. 过滤点数过少的轨迹
        Map<Integer, Long> countMap = sorted.stream()
                .collect(Collectors.groupingBy(TrajectoryPoint::getTrajId, Collectors.counting()));

        Set<Integer> validTrajIds = countMap.entrySet().stream()
                .filter(e -> e.getValue() >= minPoints)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        List<TrajectoryPoint> result = sorted.stream()
                .filter(p -> validTrajIds.contains(p.getTrajId()))
                .collect(Collectors.toList());

        // 4. 重新编号 trajId（从 0 开始连续）
        Map<Integer, Integer> idMapping = new HashMap<>();
        int newId = 0;
        for (TrajectoryPoint p : result) {
            if (!idMapping.containsKey(p.getTrajId())) {
                idMapping.put(p.getTrajId(), newId++);
            }
            p.setTrajId(idMapping.get(p.getTrajId()));
        }

        return result;
    }

    /**
     * 按 event_time 聚合去噪。
     * 同一 idfa_md5 在同一时间截面（10 分钟网格）会有多条相互独立的经纬度观测，
     * 这些点视为噪声，取算术平均质心塌缩为单个代表点。
     * 同一时刻的观测在地理上邻近，不会跨越 180° 经线，故算术平均质心是安全的。
     * 返回结果按 event_time 升序。
     */
    private static List<TrajectoryPoint> aggregateByEventTime(List<TrajectoryPoint> points) {
        // TreeMap 保证按 event_time 升序输出
        Map<Long, List<TrajectoryPoint>> byTime = new TreeMap<>();
        for (TrajectoryPoint p : points) {
            byTime.computeIfAbsent(p.getEventTime(), k -> new ArrayList<>()).add(p);
        }

        List<TrajectoryPoint> result = new ArrayList<>(byTime.size());
        for (Map.Entry<Long, List<TrajectoryPoint>> e : byTime.entrySet()) {
            List<TrajectoryPoint> group = e.getValue();
            double sumLon = 0.0;
            double sumLat = 0.0;
            for (TrajectoryPoint p : group) {
                sumLon += p.getLon();
                sumLat += p.getLat();
            }
            int n = group.size();

            TrajectoryPoint centroid = new TrajectoryPoint();
            centroid.setObjectId(group.get(0).getObjectId());
            centroid.setEventTime(e.getKey());
            centroid.setLon(sumLon / n);
            centroid.setLat(sumLat / n);
            result.add(centroid);
        }
        return result;
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

    /**
     * 按 trajId 分组，方便后续使用
     */
    public static Map<Integer, List<TrajectoryPoint>> groupByTrajId(List<TrajectoryPoint> points) {
        return points.stream()
                .collect(Collectors.groupingBy(TrajectoryPoint::getTrajId,
                        LinkedHashMap::new,   // 保持顺序
                        Collectors.toList()));
    }
}
