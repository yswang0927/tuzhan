package com.tuzhan.asynctask.handler;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuzhan.asynctask.AsyncTaskType;
import com.tuzhan.asynctask.TaskExecuteResult;
import com.tuzhan.asynctask.repository.AsyncTaskEntity;

/**
 * 分析任务处理器接口（策略模式）
 */
public interface AsyncTaskHandler {

    /**
     * 支持的任务类型
     */
    AsyncTaskType supportType();

    /**
     * 参数校验（不同任务规则不同）
     * 校验失败直接抛业务异常
     */
    void validate(JsonNode queryParams);

    /**
     * 执行分析逻辑
     *
     * @param task 当前任务
     * @param queryParams 查询参数
     * @param resultSaveDir 结果保存的基础目录
     * @return 执行结果（路径 + 元数据）
     */
    TaskExecuteResult execute(AsyncTaskEntity task, JsonNode queryParams, Path resultSaveDir) throws Exception;

}