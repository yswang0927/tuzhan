package com.tuzhan.asynctask.repository;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;
import com.tuzhan.asynctask.TaskStatus;
import com.tuzhan.repository.BaseRepository;

@Repository
public class AsyncTaskRepositoryLocal extends BaseRepository implements AsyncTaskRepository {

    @Override
    public boolean createTask(AsyncTaskEntity task) {
        String sql = """
                INSERT INTO async_task
                (task_id, task_type, status, progress, priority, query_params,
                 creator, created_at, updated_at, retry_count, max_retries, estimated_cost, timeout_seconds)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?);
                """;

        Object[] params = {
                task.getTaskId(),
                task.getTaskType(),
                TaskStatus.PENDING.name(),
                0,
                task.getPriority(),
                task.getQueryParams(),
                task.getCreator(),
                task.getCreatedAt().getEpochSecond(),
                task.getUpdatedAt().getEpochSecond(),
                0,
                task.getMaxRetries() != null ? task.getMaxRetries() : 0,
                0,
                task.getTimeoutSeconds()
        };

        try {
            return LOCAL_JDBC.executeUpdate(sql, params) > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean deleteTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }

        String sql = "delete from async_task where task_id = ?";
        try {
            return LOCAL_JDBC.executeUpdate(sql, new Object[]{taskId}) > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean updateToRunning(String taskId, Instant startedAt) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }

        // 条件更新做原子抢占：只有仍处于 PENDING 的任务才能被本节点抢到，
        // 影响行数=1 才代表抢占成功，避免多实例部署时重复捞取同一任务。
        String sql = "update async_task set status = ?, started_at = ?, updated_at = ? where task_id = ? and status = ?";
        try {
            long now = startedAt.getEpochSecond();
            return LOCAL_JDBC.executeUpdate(sql, new Object[]{
                    TaskStatus.RUNNING.name(), now, now, taskId, TaskStatus.PENDING.name()
            }) > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean updateSuccess(String taskId, String resultPath, String resultMeta, Instant finishedAt) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }

        // 仅当任务仍处于 RUNNING 时才落 SUCCESS，避免把已被取消(CANCELLED)
        // 或已被看门狗改回的任务状态覆盖回去。
        String sql = "update async_task set progress = 100, status = ?, result_path = ?, result_meta = ?, finished_at = ?, updated_at = ? where task_id = ? and status = ?";
        long now = finishedAt.getEpochSecond();
        Object[] params = {
                TaskStatus.SUCCESS.name(),
                resultPath,
                resultMeta,
                now,
                now,
                taskId,
                TaskStatus.RUNNING.name()
        };

        try {
            return LOCAL_JDBC.executeUpdate(sql, params) > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean updateFailed(String taskId, String errorMsg, Instant finishedAt) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }

        // 同样加 RUNNING 守卫：已取消的任务即使执行线程抛异常，也不应被改成 FAILED。
        String sql = "update async_task set status = ?, finished_at = ?, updated_at = ?, error_msg = ? where task_id = ? and status = ?";
        long now = finishedAt.getEpochSecond();
        Object[] params = {
                TaskStatus.FAILED.name(),
                now,
                now,
                errorMsg,
                taskId,
                TaskStatus.RUNNING.name()
        };

        try {
            return LOCAL_JDBC.executeUpdate(sql, params) > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean updateStatus(String taskId, TaskStatus status, Instant updatedAt, String errorMsg) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }

        String sql = "update async_task set status = ?, updated_at = ?, error_msg = ? where task_id = ?";
        Object[] params = {
                status != null ? status.name() : null,
                updatedAt.getEpochSecond(),
                errorMsg,
                taskId
        };

        try {
            return LOCAL_JDBC.executeUpdate(sql, params) > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean updateProgress(String taskId, Integer progress, Instant updatedAt) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }

        String sql = "update async_task set progress = ?, updated_at = ? where task_id = ?";
        Object[] params = {
                progress != null ? progress.intValue() : 0,
                updatedAt.getEpochSecond(),
                taskId
        };

        try {
            return LOCAL_JDBC.executeUpdate(sql, params) > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean increaseRetryCount(String taskId, String errorMsg) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }

        String sql = "update async_task set retry_count = retry_count + 1, status = ?, error_msg = ?, updated_at = ? where task_id = ?";
        Object[] params = { TaskStatus.PENDING.name(), errorMsg, Instant.now().getEpochSecond(), taskId };

        try {
            return LOCAL_JDBC.executeUpdate(sql, params) > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<AsyncTaskEntity> findTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Optional.empty();
        }

        String sql = "select * from async_task where task_id = ?";
        try {
            return Optional.ofNullable(LOCAL_JDBC.queryForBean(AsyncTaskEntity.class, sql, new Object[]{taskId}));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<AsyncTaskEntity> queryPendingTasks(int limit) {
        if (limit <= 0) {
            limit = 1;
        }

        // priority 值越小优先级越高，同优先级按创建时间先到先得。
        String sql = "select * from async_task where status = ? order by priority asc, created_at asc";
        try {
            return LOCAL_JDBC.queryForList(AsyncTaskEntity.class, sql, new Object[] { TaskStatus.PENDING.name() }, 1, limit);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<AsyncTaskEntity> queryTimeoutTasks(Instant now) {
        if (now == null) {
            return Collections.emptyList();
        }

        // 基于任务实际开始时间(started_at)和其自带的 timeout_seconds 逐任务判定超时，
        // 未设置 timeout_seconds 时按默认 3600 秒兜底。
        String sql = "select * from async_task WHERE status = ? AND started_at IS NOT NULL "
                + "AND started_at + COALESCE(timeout_seconds, 3600) < ?";
        Object[] params = {
                TaskStatus.RUNNING.name(),
                now.getEpochSecond()
        };

        try {
            return LOCAL_JDBC.queryForList(AsyncTaskEntity.class, sql, params);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<AsyncTaskEntity> queryExpiredTasks(Instant expireThreshold) {
        if (expireThreshold == null) {
            return Collections.emptyList();
        }

        // 只清理已处于终态的任务，避免误删仍在排队(PENDING)或长时间运行(RUNNING)的任务。
        String sql = "select * from async_task WHERE created_at < ? AND status IN (?, ?, ?)";
        Object[] params = {
                expireThreshold.getEpochSecond(),
                TaskStatus.SUCCESS.name(),
                TaskStatus.FAILED.name(),
                TaskStatus.CANCELLED.name()
        };

        try {
            return LOCAL_JDBC.queryForList(AsyncTaskEntity.class, sql, params);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

}
