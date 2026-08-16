package com.tuzhan.web.trajectory;

import java.time.Instant;

/**
 * 轨迹
 */
public class TrajectoryPoint {
    // 目标对象ID
    private String objectId;
    // 事件发生的时间点
    private Instant eventTime;
    // 经度
    private double lon;
    // 纬度
    private double lat;

    public String getObjectId() {
        return objectId;
    }

    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public void setEventTime(Instant eventTime) {
        this.eventTime = eventTime;
    }

    public double getLon() {
        return lon;
    }

    public void setLon(double lon) {
        this.lon = lon;
    }

    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }
}
