package com.tuzhan.trajectory;

/**
 * 轨迹点
 */
public class TrajectoryPoint {
    // 目标对象ID
    private String objectId;
    // 事件发生的时间点(时间戳秒数)
    private long eventTime;
    // 经度
    private double lon;
    // 纬度
    private double lat;

    // 此点属于哪个轨迹线(用于分组归并轨迹点到所属的线)
    private int trajId;

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

    public int getTrajId() {
        return trajId;
    }

    public void setTrajId(int trajId) {
        this.trajId = trajId;
    }
}
