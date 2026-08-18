package com.tuzhan.asynctask;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.tuzhan.asynctask.repository.AsyncTaskEntity;
import com.tuzhan.asynctask.repository.AsyncTaskRepository;

@Component
public class AsyncTaskWatchdog {
    private static final Logger LOG = LoggerFactory.getLogger(AsyncTaskWatchdog.class);

    private final AsyncTaskRepository taskRepository;
    private final AsyncTaskExecutor taskExecutor;

    public AsyncTaskWatchdog(AsyncTaskRepository taskRepository, AsyncTaskExecutor taskExecutor) {
        this.taskRepository = taskRepository;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 每分钟检查一次超时任务
     */
    @Scheduled(fixedDelay = 60000)
    public void checkTimeoutTasks() {
        // 超时判定下沉到仓储：基于每个任务的 started_at + timeout_seconds 逐任务比较，
        // 不再使用统一硬编码阈值。
        List<AsyncTaskEntity> timeoutTasks = this.taskRepository.queryTimeoutTasks(Instant.now());

        if (timeoutTasks.isEmpty()) {
            return;
        }

        LOG.info("Found {} timeout async tasks", timeoutTasks.size());

        for (AsyncTaskEntity task : timeoutTasks) {
            // 先中断本节点上仍在执行的线程，防止超时改状态后原线程跑完再把状态覆盖回去。
            boolean interrupted = this.taskExecutor.interruptRunning(task.getTaskId());
            if (interrupted) {
                LOG.warn("Interrupted running thread of timed-out task {}", task.getTaskId());
            }

            int retryCount = task.getRetryCount() != null ? task.getRetryCount() : 0;
            int maxRetries = task.getMaxRetries() != null ? task.getMaxRetries() : 0;
            if (retryCount < maxRetries) {
                String errorMsg = String.format("Task %s timed out, will retry. Retry count: %d/%d",
                        task.getTaskId(), retryCount, maxRetries);
                LOG.warn(errorMsg);
                this.taskRepository.increaseRetryCount(task.getTaskId(), errorMsg);
            } else {
                String errMsg = String.format("Task %s failed after %d retries due to timeout.", task.getTaskId(), maxRetries);
                LOG.error(errMsg);
                this.taskRepository.updateFailed(task.getTaskId(), errMsg, Instant.now());
            }
        }
    }

    /**
     * 每天凌晨 2 点执行，清理 7 天前的过期任务记录和对应的结果文件
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupExpiredTasks() {
        Instant expireThreshold = Instant.now().minus(Duration.ofDays(7));
        List<AsyncTaskEntity> expiredTasks = this.taskRepository.queryExpiredTasks(expireThreshold);

        if (expiredTasks.isEmpty()) {
            return;
        }

        LOG.info("Start cleaning up {} expired async tasks (older than 7 days)", expiredTasks.size());

        int deletedCount = 0;
        for (AsyncTaskEntity task : expiredTasks) {
            try {
                // resultPath 指向结果文件(如 {dir}/{taskId}/result.jsonl)，
                // 清理时删除其所在的任务目录及目录内所有文件。
                if (task.getResultPath() != null && task.getStatus().equals(TaskStatus.SUCCESS.name())) {
                    File resultFile = new File(task.getResultPath());
                    File taskDir = resultFile.isDirectory() ? resultFile : resultFile.getParentFile();
                    deleteDirectory(taskDir);
                }

                // 删除数据库记录
                this.taskRepository.deleteTask(task.getTaskId());
                deletedCount++;
            } catch (Exception e) {
                LOG.error("Error occurred while cleaning up task " + task.getTaskId(), e);
            }
        }

        LOG.info("Cleanup finished. Deleted {} expired task records.", deletedCount);
    }

    /**
     * 递归删除目录及其内部所有文件/子目录。
     */
    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteDirectory(child);
                } else {
                    child.delete();
                }
            }
        }
        if (dir.delete()) {
            LOG.debug("Deleted result directory: {}", dir.getAbsolutePath());
        } else {
            LOG.warn("Failed to delete result directory: {}", dir.getAbsolutePath());
        }
    }
}
