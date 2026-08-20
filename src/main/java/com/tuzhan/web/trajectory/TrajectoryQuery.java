package com.tuzhan.web.trajectory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.tuzhan.trajectory.TrajectoryPoint;

/**
 * 轨迹查询
 */
public interface TrajectoryQuery {

    /**
     * 根据关键词搜索对象信息。
     * @param keyword 关键词
     * @param limit 最大返回数据量
     * @return
     */
    List<String> searchObjects(String keyword, int limit);

    /**
     * 指定对象+时间段，查询其轨迹数据。
     *
     * @param objectId
     * @param startTime
     * @param endTime
     * @return 轨迹点列表，无数据时返回空列表
     */
    List<TrajectoryPoint> queryObjectTrajectories(String objectId, Instant startTime, Instant endTime);

    /**
     * 查询目标对象历史上最后一次出现的位置(坐标+时间)
     * @param objectId 目标对象ID
     * @return 最后一次位置，如果不存在则返回 Optional.empty()
     */
    Optional<TrajectoryPoint> queryLastLocation(String objectId);
}
