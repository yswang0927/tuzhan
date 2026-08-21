package com.tuzhan.trajectory;

/**
 * 轨迹切分配置。
 * 默认值面向"从稀疏 GPS 原始点恢复地面出行轨迹并上图"的场景。
 */
public class TrajectorySplitConfig {

    /** 最大轨迹时间间隔（秒）：相邻代表点时间差超过该值判定为断点，切分轨迹。默认 1800（30 分钟）。 */
    private long maxTimeGapSec = 1800L;

    /** 速度上限（米/秒）：相邻代表点位移速度超过该值视为异常。默认 85（约 306km/h，覆盖高铁）。 */
    private double maxSpeedMeterPerSec = 85.0;

    /** 单步位移上限（米）：相邻代表点直线距离超过该值即切断，用于拦截稀疏采样下速度未超限的远距离跳变。默认 50000（50km）。 */
    private double maxStepDistanceMeter = 50000.0;

    /** 同一时间片空间聚类半径（米）：同一 event_time 内点距离小于该值归为同一空间簇。默认 500。 */
    private double sameTimeClusterRadiusMeter = 500.0;

    /** 一条有效轨迹的最少点数，低于该值的轨迹被丢弃。默认 3。 */
    private int minPoints = 3;

    /** 是否启用 GPS 跳点删除（三点法）。默认 true。 */
    private boolean removeJumpPoints = true;

    public static TrajectorySplitConfig defaults() {
        return new TrajectorySplitConfig();
    }

    public long getMaxTimeGapSec() { return maxTimeGapSec; }
    public TrajectorySplitConfig setMaxTimeGapSec(long v) { this.maxTimeGapSec = v; return this; }

    public double getMaxSpeedMeterPerSec() { return maxSpeedMeterPerSec; }
    public TrajectorySplitConfig setMaxSpeedMeterPerSec(double v) { this.maxSpeedMeterPerSec = v; return this; }

    public double getMaxStepDistanceMeter() { return maxStepDistanceMeter; }
    public TrajectorySplitConfig setMaxStepDistanceMeter(double v) { this.maxStepDistanceMeter = v; return this; }

    public double getSameTimeClusterRadiusMeter() { return sameTimeClusterRadiusMeter; }
    public TrajectorySplitConfig setSameTimeClusterRadiusMeter(double v) { this.sameTimeClusterRadiusMeter = v; return this; }

    public int getMinPoints() { return minPoints; }
    public TrajectorySplitConfig setMinPoints(int v) { this.minPoints = v; return this; }

    public boolean isRemoveJumpPoints() { return removeJumpPoints; }
    public TrajectorySplitConfig setRemoveJumpPoints(boolean v) { this.removeJumpPoints = v; return this; }
}
