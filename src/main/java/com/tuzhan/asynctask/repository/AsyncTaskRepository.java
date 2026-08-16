package com.tuzhan.asynctask.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.tuzhan.asynctask.TaskStatus;

/**
 * 异步任务记录增删改查操作。
 */
public interface AsyncTaskRepository {

    /**
     * 创建任务
     */
    boolean createTask(AsyncTaskEntity task);

    /**
     * 删除任务
     */
    boolean deleteTask(String taskId);

    /**
     * 更新为 RUNNING 状态
     */
    boolean updateToRunning(String taskId, Instant startedAt);

    /**
     * 更新为 SUCCESS
     */
    boolean updateSuccess(String taskId, String resultPath, String resultMeta, Instant finishedAt);

    /**
     * 更新为 FAILED
     */
    boolean updateFailed(String taskId, String errorMsg, Instant finishedAt);

    /**
     * 通用状态更新（用于取消等场景）
     */
    boolean updateStatus(String taskId, TaskStatus status, Instant updatedAt, String errorMsg);

    /**
     * 更新进度（可选，复杂任务中途更新进度时使用）
     */
    boolean updateProgress(String taskId, Integer progress, Instant updatedAt);

    /**
     * 增加重试次数
     */
    boolean increaseRetryCount(String taskId, String errorMsg);

    /**
     * 根据 taskId 查询
     */
    Optional<AsyncTaskEntity> findTask(String taskId);

    /**
     * 查询待执行的任务（可选，用于独立消费者模式）
     * 按优先级、创建时间排序，限制数量
     */
    List<AsyncTaskEntity> queryPendingTasks(int limit);

    /**
     * 查询运行超时的任务
     */
    List<AsyncTaskEntity> queryTimeoutTasks(Instant timeoutThreshold);

    /**
     * 查询过期的任务
     */
    List<AsyncTaskEntity> queryExpiredTasks(Instant expireThreshold);

}
