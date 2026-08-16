package com.tuzhan.analysis.handler;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.tuzhan.asynctask.AsyncTaskType;
import com.tuzhan.asynctask.TaskExecuteResult;
import com.tuzhan.asynctask.handler.AsyncTaskHandler;
import com.tuzhan.asynctask.repository.AsyncTaskEntity;

/**
 * 首次出现人员：指定区域+时间段，找出在该段时间内出现但历史从未出现过的人员（即：新面孔）、首次出现时间、历史轨迹缺失提示。
 */
@Component
public class FirstAppearanceHandler implements AsyncTaskHandler {
    @Override
    public AsyncTaskType supportType() {
        return AsyncTaskType.FIRST_APPEARANCE;
    }

    @Override
    public void validate(JsonNode queryParams) {

    }

    @Override
    public TaskExecuteResult execute(AsyncTaskEntity task) throws Exception {
        return null;
    }

}
