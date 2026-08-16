package com.tuzhan.asynctask;

import java.util.Optional;

/**
 * 通用分析任务服务
 */
public interface AsyncTaskService {

    /**
     * 创建分析任务，立即返回 taskId
     */
    String createTask(CreateAsyncTaskRequest request, String creator);

    /**
     * 查询任务详情/状态
     */
    Optional<AsyncTaskDetail> getTaskDetail(String taskId);

    /**
     * 取消任务（仅对 PENDING / RUNNING 生效）
     */
    void cancelTask(String taskId);
}