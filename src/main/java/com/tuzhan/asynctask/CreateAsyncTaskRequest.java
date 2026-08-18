package com.tuzhan.asynctask;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 创建任务请求
 */
public class CreateAsyncTaskRequest {
    private AsyncTaskType taskType;
    private JsonNode queryParams;
    private Integer priority = 5;      // 可选，默认 5
    private Integer maxRetries;        // 可选，默认 0（不重试）
    private Integer timeoutSeconds;    // 可选

    public AsyncTaskType getTaskType() {
        return taskType;
    }

    public void setTaskType(AsyncTaskType taskType) {
        this.taskType = taskType;
    }

    public JsonNode getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(JsonNode queryParams) {
        this.queryParams = queryParams;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}