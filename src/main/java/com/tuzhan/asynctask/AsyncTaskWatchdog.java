package com.tuzhan.asynctask;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.tuzhan.asynctask.repository.AsyncTaskEntity;
import com.tuzhan.asynctask.repository.AsyncTaskRepository;

@Component
@EnableScheduling
public class AsyncTaskWatchdog {
    private static final Logger LOG = LoggerFactory.getLogger(AsyncTaskWatchdog.class);

    private final AsyncTaskRepository taskRepository;

    public AsyncTaskWatchdog(AsyncTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /**
     * 每分钟检查一次超时任务
     */
    @Scheduled(fixedDelay = 60000)
    public void checkTimeoutTasks() {
        // 查找超过 30 分钟还在 RUNNING 的任务 (由于新的分析任务可能比较长，放宽到30分钟)
        Instant timeoutThreshold = Instant.now().minus(Duration.ofMinutes(30));
        List<AsyncTaskEntity> timeoutTasks = this.taskRepository.queryTimeoutTasks(timeoutThreshold);

        if (timeoutTasks.isEmpty()) {
            return;
        }

        LOG.info("Found {} timeout async tasks", timeoutTasks.size());

        for (AsyncTaskEntity task : timeoutTasks) {
            if (task.getRetryCount() < task.getMaxRetries()) {
                String errorMsg = String.format("Task %s timed out, will retry. Retry count: %d/%d",
                        task.getTaskId(), task.getRetryCount(), task.getMaxRetries());
                LOG.warn(errorMsg);
                this.taskRepository.increaseRetryCount(task.getTaskId(), errorMsg);
            } else {
                String errMsg = String.format("Task %s failed after %d retries due to timeout.", task.getTaskId(), task.getMaxRetries());
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
                // 如果存在结果目录，先删除磁盘目录及内部文件
                if (task.getResultPath() != null && task.getStatus().equals(TaskStatus.SUCCESS.name())) {
                    File resultDir = new File(task.getResultPath());
                    if (resultDir.exists() && resultDir.isDirectory()) {
                        File[] files = resultDir.listFiles();
                        if (files != null) {
                            for (File f : files) {
                                f.delete();
                            }
                        }
                        if (resultDir.delete()) {
                            LOG.debug("Deleted result directory: {}", resultDir.getAbsolutePath());
                        } else {
                            LOG.warn("Failed to delete result directory: {}", resultDir.getAbsolutePath());
                        }
                    }
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
}
