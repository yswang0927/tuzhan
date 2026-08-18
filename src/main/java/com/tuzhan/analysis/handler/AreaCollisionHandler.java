package com.tuzhan.analysis.handler;

import java.nio.file.Path;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.tuzhan.asynctask.handler.AsyncTaskHandler;
import com.tuzhan.asynctask.AsyncTaskType;
import com.tuzhan.asynctask.TaskExecuteResult;
import com.tuzhan.asynctask.repository.AsyncTaskEntity;

/**
 * 区域碰撞分析
 * <p>用户圈选多个区域+指定多个时间区间，设置时间关系（交集/并集/差集），输出符合关系的人员列表，出现次数，时空分布热力图。</p>
 */
@Component
public class AreaCollisionHandler implements AsyncTaskHandler {

    @Override
    public AsyncTaskType supportType() {
        return AsyncTaskType.AREA_COLLISION;
    }

    @Override
    public void validate(JsonNode queryParams) {
        
    }

    @Override
    public TaskExecuteResult execute(AsyncTaskEntity task, JsonNode queryParams, Path resultSaveDir) throws Exception {
        return null;
    }

}
