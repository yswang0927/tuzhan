package com.tuzhan.web.trajectory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.tuzhan.repository.BaseRepository;
import com.tuzhan.trajectory.Trajectory;
import com.tuzhan.trajectory.TrajectoryPoint;
import com.tuzhan.trajectory.TrajectorySplitter;

@Service
public class TrajectoryQueryImpl extends BaseRepository implements TrajectoryQuery {

    private static final Logger LOG = LoggerFactory.getLogger(TrajectoryQueryImpl.class);

    @Override
    public List<String> searchObjects(String keyword, int limit) {
        String sql = "select idfa_md5 from idfa_gps_detail where 1=1 [and idfa_md5 like :kw] group by idfa_md5";
        try {
            return IDFA_JDBC.queryForList(String.class, sql,
                    StringUtils.hasText(keyword) ? Map.of("kw", keyword + '%') : Collections.emptyMap(),
                    1, Math.max(1, limit));
        } catch (Exception e) {
            LOG.error("Failed to search-objects: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    @Override
    public List<Trajectory> queryObjectTrajectories(String objectId, Instant startTime, Instant endTime) {
        if (!StringUtils.hasText(objectId)) {
            return Collections.emptyList();
        }

        if (startTime == null && endTime == null) {
            return Collections.emptyList();
        }

        String sql = "select idfa_md5 as objectId, event_time as eventTime, lon, lat from idfa_gps_detail " +
                "where idfa_md5 = :objectId [and event_time >= :stime] [and event_time <= :etime] order by event_time asc";

        Map<String, Object> params = new HashMap<>(4);
        params.put("objectId", objectId);

        long stime = 0;
        long etime = 0;
        Duration maxDuration = Duration.ofDays(30);

        if (startTime != null && endTime != null) {
            stime = startTime.getEpochSecond();
            etime = endTime.getEpochSecond();
            if (stime > etime) {
                long tmp = stime;
                stime = etime;
                etime = tmp;
            }
        }
        else if (startTime != null) {
            stime = startTime.getEpochSecond();
            etime = startTime.plus(maxDuration).getEpochSecond();
        }
        else if (endTime != null) {
            stime = endTime.minus(maxDuration).getEpochSecond();
            etime = endTime.getEpochSecond();
        }

        params.put("stime", stime);
        params.put("etime", etime);

        try {
            List<TrajectoryPoint> trajectoryPoints = IDFA_JDBC.queryForList(TrajectoryPoint.class, sql, params, 1, 5000);
            // 使用默认配置：时间间隔 30min、速度上限 85m/s、单步位移 50km、同时间片聚类半径 500m、最少点数 3
            return TrajectorySplitter.split(trajectoryPoints);
        } catch (Exception e) {
            LOG.error("Failed to query object(={}, {} ~ {}) trajectories.", objectId, startTime, endTime);
        }

        return Collections.emptyList();
    }

    @Override
    public Optional<TrajectoryPoint> queryLastLocation(String objectId) {
        if (!StringUtils.hasText(objectId)) {
            return Optional.empty();
        }

        String sql = "select idfa_md5 as objectId, event_time as eventTime, lon, lat from idfa_gps_detail " +
                "where idfa_md5 = ? ORDER BY dt DESC, event_time DESC limit 1";

        try {
            return Optional.ofNullable(IDFA_JDBC.queryForBean(TrajectoryPoint.class, sql, new Object[]{objectId}));
        } catch (Exception e) {
            LOG.error("Failed to query the object({}) last location: {}", objectId, e.getMessage());
        }
        return Optional.empty();
    }

}
