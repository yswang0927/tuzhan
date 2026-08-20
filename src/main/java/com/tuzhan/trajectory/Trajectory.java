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

    // 按 eventTime 升序排序的轨迹点列表（不可变）
    private final List<TrajectoryPoint> points;

    public Trajectory(String objectId, List<TrajectoryPoint> points) {
        this.objectId = Objects.requireNonNull(objectId, "objectId cannot be null");

        if (points == null || points.isEmpty()) {
            this.points = Collections.emptyList();
        } else {
            // 按 eventTime 升序排序
            List<TrajectoryPoint> sorted = new ArrayList<>(points);
            sorted.sort((p1, p2) -> Long.compare(p1.getEventTime(), p2.getEventTime()));
            this.points = Collections.unmodifiableList(sorted);
        }
    }

    public String getObjectId() {
        return objectId;
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