package com.tuzhan.trajectory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一条完整的轨迹（按 eventTime 升序排列的轨迹点集合）
 */
public class Trajectory {
    // 目标对象ID
    private final String objectId;

    // 全局唯一轨迹ID：objectId_startEventTime。空轨迹时为 null。
    private final String trajId;

    // 按 eventTime 升序排序的轨迹点列表（不可变）
    private final List<TrajectoryPoint> points;

    // 轨迹总里程（米），相邻点 haversine 距离累加
    private final double totalDistanceMeter;

    // 轨迹持续时长（秒），endTime - startTime
    private final long durationSec;

    public Trajectory(String objectId, List<TrajectoryPoint> points) {
        this.objectId = Objects.requireNonNull(objectId, "objectId cannot be null");

        if (points == null || points.isEmpty()) {
            this.points = Collections.emptyList();
            this.trajId = null;
            this.totalDistanceMeter = 0.0;
            this.durationSec = 0L;
        } else {
            // 按 eventTime 升序排序
            List<TrajectoryPoint> sorted = new ArrayList<>(points);
            sorted.sort((p1, p2) -> Long.compare(p1.getEventTime(), p2.getEventTime()));
            this.points = Collections.unmodifiableList(sorted);

            // 全局唯一ID：对象ID + 起始时间
            long start = sorted.get(0).getEventTime();
            long end = sorted.get(sorted.size() - 1).getEventTime();
            this.trajId = objectId + "_" + start;
            this.durationSec = end - start;

            // 累加里程
            double dist = 0.0;
            for (int i = 1; i < sorted.size(); i++) {
                TrajectoryPoint a = sorted.get(i - 1);
                TrajectoryPoint b = sorted.get(i);
                dist += TrajectorySplitter.haversine(a.getLon(), a.getLat(), b.getLon(), b.getLat());
            }
            this.totalDistanceMeter = dist;
        }
    }

    public String getObjectId() {
        return objectId;
    }

    /** 全局唯一轨迹ID（objectId_startEventTime），空轨迹为 null */
    public String getTrajId() {
        return trajId;
    }

    /** 轨迹总里程（米） */
    public double getTotalDistanceMeter() {
        return totalDistanceMeter;
    }

    /** 轨迹持续时长（秒） */
    public long getDurationSec() {
        return durationSec;
    }

    /**
     * 获取按时间升序排列的轨迹点列表（不可变）
     */
    public List<TrajectoryPoint> getPoints() {
        return points;
    }

    /**
     * 轨迹是否为空
     */
    public boolean isEmpty() {
        return points.isEmpty();
    }

    /**
     * 轨迹点数量
     */
    public int size() {
        return points.size();
    }

    /**
     * 获取轨迹起始时间（第一个点的时间），轨迹为空时返回 null
     */
    public Long getStartTime() {
        return points.isEmpty() ? null : points.get(0).getEventTime();
    }

    /**
     * 获取轨迹结束时间（最后一个点的时间），轨迹为空时返回 null
     */
    public Long getEndTime() {
        return points.isEmpty() ? null : points.get(points.size() - 1).getEventTime();
    }

    /**
     * 获取第一个轨迹点，轨迹为空时返回 null
     */
    public TrajectoryPoint getFirstPoint() {
        return points.isEmpty() ? null : points.get(0);
    }

    /**
     * 获取最后一个轨迹点，轨迹为空时返回 null
     */
    public TrajectoryPoint getLastPoint() {
        return points.isEmpty() ? null : points.get(points.size() - 1);
    }
}