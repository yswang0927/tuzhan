package com.tuzhan.analysis.handler;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.tuzhan.asynctask.AsyncTaskType;
import com.tuzhan.asynctask.TaskExecuteResult;
import com.tuzhan.asynctask.handler.AsyncTaskHandler;
import com.tuzhan.asynctask.repository.AsyncTaskEntity;

/**
 * 人员轨迹碰撞：用户圈选至少两个区域+各自时间段，系统找出同时出现在两个以上区域的人员、在各个区域出现的时间段、轨迹重叠时长。
 */
@Component
public class ObjectTrajectoryCollisionHandler implements AsyncTaskHandler {

    @Override
    public AsyncTaskType supportType() {
        return AsyncTaskType.PERSON_TRAJECTORY_COLLISION;
    }

    @Override
    public void validate(JsonNode queryParams) {

    }

    @Override
    public TaskExecuteResult execute(AsyncTaskEntity task) throws Exception {
        return null;
    }
}
