package com.tuzhan.web.trajectory;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.github.sisyphsu.dateparser.DateParserUtils;
import com.tuzhan.trajectory.TrajectoryPoint;

@RestController
@RequestMapping("/api/trajectory")
public class TrajectoryController {

    private final TrajectoryQuery trajectoryQuery;

    public TrajectoryController(TrajectoryQuery trajectoryQuery) {
        this.trajectoryQuery = trajectoryQuery;
    }

    /**
     * 搜索对象信息(/api/trajectory/objects?obj=xxx)
     * @param obj 搜索关键词
     * @return 对象信息列表
     */
    @GetMapping("/objects")
    public List<String> searchObjects(@RequestParam(name="obj", required = false) String obj) {
        return this.trajectoryQuery.searchObjects(obj, 100);
    }

    /**
     * 查询目标对象轨迹信息(/api/trajectory/query-trajectories?objectId=xx&startTime=xx&endTime=xx)
     * @return
     */
    @GetMapping("/query-trajectories")
    public List<TrajectoryPoint> queryObjectTrajectories(@RequestParam(name="objectId") String objectId,
                                                         @RequestParam(name="startTime") String startTime,
                                                         @RequestParam(name="endTime") String endTime) {
        Instant stime = null;
        Instant etime = null;

        ZoneId zoneId = ZoneId.of("Asia/Shanghai");
        if (startTime != null) {
            stime = DateParserUtils.parseDateTime(startTime).atZone(zoneId).toInstant();
        }

        if (endTime != null) {
            etime = DateParserUtils.parseDateTime(endTime).atZone(zoneId).toInstant();
        }

        return this.trajectoryQuery.queryObjectTrajectories(objectId, stime, etime);
    }

    /**
     * 查询目标对象的最后一次位置(/api/trajectory/query-lastlocation?objectId=xxx)
     * @param objectId 目标对象
     * @return 最后一次位置，可能为null
     */
    @GetMapping("/query-lastlocation")
    public TrajectoryPoint searchLastLocation(@RequestParam(name="objectId") String objectId) {
        Optional<TrajectoryPoint> trajectoryPoint = this.trajectoryQuery.queryLastLocation(objectId);
        return trajectoryPoint.isEmpty() ? null : trajectoryPoint.get();
    }

}
