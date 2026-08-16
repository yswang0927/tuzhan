package com.tuzhan.asynctask;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuzhan.asynctask.repository.AsyncTaskEntity;
import com.tuzhan.util.JsonObjectMapper;

import java.time.Instant;

/**
 * 任务状态返回 VO
 */
public class AsyncTaskDetail {

    private String taskId;
    private AsyncTaskType taskType;
    private TaskStatus status;
    private Integer progress;
    private String resultPath;
    private JsonNode resultMeta;
    private String errorMsg;
    private String creator;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;

    public static AsyncTaskDetail of(AsyncTaskEntity taskEntity) {
        if (taskEntity == null) {
            return null;
        }

        AsyncTaskDetail taskDetail = new AsyncTaskDetail();
        taskDetail.setTaskId(taskEntity.getTaskId());
        taskDetail.setTaskType(AsyncTaskType.valueOf(taskEntity.getTaskType()));
        taskDetail.setStatus(TaskStatus.valueOf(taskEntity.getStatus()));
        taskDetail.setProgress(taskEntity.getProgress());
        taskDetail.setResultPath(taskEntity.getResultPath());
        taskDetail.setResultMeta(JsonObjectMapper.parse(taskEntity.getResultMeta()));
        taskDetail.setErrorMsg(taskEntity.getErrorMsg());
        taskDetail.setCreator(taskEntity.getCreator());
        taskDetail.setCreatedAt(taskEntity.getCreatedAt());
        taskDetail.setStartedAt(taskEntity.getStartedAt());
        taskDetail.setFinishedAt(taskEntity.getFinishedAt());

        return taskDetail;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public AsyncTaskType getTaskType() { return taskType; }
    public void setTaskType(AsyncTaskType taskType) { this.taskType = taskType; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public String getResultPath() { return resultPath; }
    public void setResultPath(String resultPath) { this.resultPath = resultPath; }
    public JsonNode getResultMeta() { return resultMeta; }
    public void setResultMeta(JsonNode resultMeta) { this.resultMeta = resultMeta; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public String getCreator() { return creator; }
    public void setCreator(String creator) { this.creator = creator; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}