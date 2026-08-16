package com.tuzhan.analysis.handler;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.tuzhan.asynctask.AsyncTaskType;
import com.tuzhan.asynctask.TaskExecuteResult;
import com.tuzhan.asynctask.handler.AsyncTaskHandler;
import com.tuzhan.asynctask.repository.AsyncTaskEntity;

/**
 * 区域人员分析：用户圈选单一区域+固定时间段，输出该区域内的所有人员、出现频次、首次/末次时间。
 */
@Component
public class AreaObjectAnalysisHandler implements AsyncTaskHandler {

    @Override
    public AsyncTaskType supportType() {
        return AsyncTaskType.AREA_PERSON_ANALYSIS;
    }

    @Override
    public void validate(JsonNode queryParams) {

    }

    @Override
    public TaskExecuteResult execute(AsyncTaskEntity task) throws Exception {
        return null;
    }

}
