package com.tuzhan.web.trajectory;

/**
 * 轨迹
 */
public class TrajectoryPoint {
    // 目标对象ID
    private String objectId;
    // 事件发生的时间点
    private long eventTime;
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

    public long getEventTime() {
        return eventTime;
    }

    public void setEventTime(long eventTime) {
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
