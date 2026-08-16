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
                 creator, created_at, updated_at, retry_count, estimated_cost, timeout_seconds)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?);
                """;

        Object[] params = {
                task.getTaskId(),
                task.getTaskType(),
                TaskStatus.PENDING.name(),
                0,
                task.getPriority(),
                task.getQueryParams(),
                task.getCreator(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                0,
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

        String sql = "update async_task set status = ?, started_at = ? where task_id = ?";
        try {
            return LOCAL_JDBC.executeUpdate(sql, new Object[]{ TaskStatus.RUNNING.name(), startedAt, taskId }) > 0;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean updateSuccess(String taskId, String resultPath, String resultMeta, Instant finishedAt) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }

        String sql = "update async_task set progress = 100, status = ?, resultPath = ?, resultMeta = ?, finished_at = ? where task_id = ?";
        Object[] params = {
                TaskStatus.SUCCESS.name(),
                resultPath,
                resultMeta,
                finishedAt,
                taskId
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

        String sql = "update async_task set status = ?, finished_at = ?, errorMsg = ? where task_id = ?";
        Object[] params = {
                TaskStatus.FAILED.name(),
                finishedAt,
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
    public boolean updateStatus(String taskId, TaskStatus status, Instant updatedAt, String errorMsg) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }

        String sql = "update async_task set status = ?, updated_at = ?, errorMsg = ? where task_id = ?";
        Object[] params = {
                status != null ? status.name() : null,
                updatedAt,
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
                updatedAt,
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

        String sql = "update async_task set retry_count = retry_count + 1, status = ?, error_msg = ? where task_id = ?";
        Object[] params = { TaskStatus.PENDING.name(), errorMsg, taskId };

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
        String sql = "select * from async_task where status = ? order by created_at asc";
        try {
            return LOCAL_JDBC.queryForList(AsyncTaskEntity.class, sql, new Object[] { TaskStatus.PENDING.name() }, 1, limit);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<AsyncTaskEntity> queryTimeoutTasks(Instant timeoutThreshold) {
        if (timeoutThreshold == null) {
            return Collections.emptyList();
        }

        String sql = "select * from async_task WHERE status = ? AND updated_at < ?";
        Object[] params = {
                TaskStatus.RUNNING.name(),
                timeoutThreshold
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

        String sql = "select * from async_task WHERE created_at < ?";
        Object[] params = { expireThreshold };

        try {
            return LOCAL_JDBC.queryForList(AsyncTaskEntity.class, sql, params);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

}
