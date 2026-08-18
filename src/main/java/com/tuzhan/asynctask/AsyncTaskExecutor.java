package com.tuzhan.asynctask;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuzhan.asynctask.handler.AsyncTaskHandler;
import com.tuzhan.asynctask.repository.AsyncTaskEntity;
import com.tuzhan.asynctask.repository.AsyncTaskRepository;

import jakarta.annotation.PreDestroy;

@Component
public class AsyncTaskExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(AsyncTaskExecutor.class);

    /** 同时执行的异步任务线程数 */
    private static final int POOL_SIZE = 5;

    private final AsyncTaskRepository taskRepository;
    private final Map<AsyncTaskType, AsyncTaskHandler> handlersMap;
    private final ObjectMapper objectMapper;

    @Value("${tuzhan.task.result-dir:/tmp/tuzhan_tasks/}")
    private String resultDir;

    @Value("${tuzhan.tass.consumers:5}")
    private int consumerSize;

    private final ExecutorService executorService = Executors.newFixedThreadPool(POOL_SIZE);

    /** 正在执行(含线程池排队)的任务：taskId -> Future，用于取消/超时中断 */
    private final Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();

    public AsyncTaskExecutor(AsyncTaskRepository taskRepository,
                             List<AsyncTaskHandler> handlers,
                             ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.handlersMap = handlers.stream()
                .collect(Collectors.toMap(AsyncTaskHandler::supportType, Function.identity()));
        this.objectMapper = objectMapper;
    }

    /**
     * 每 5 秒轮询一次 PENDING 状态的任务。
     * 只按线程池剩余空闲容量取任务，避免把还在排队的任务提前标记为 RUNNING。
     */
    @Scheduled(fixedDelay = 5000)
    public void pollTasks() {
        int available = POOL_SIZE - this.runningTasks.size();
        if (available <= 0) {
            return;
        }

        List<AsyncTaskEntity> pendingTasks = this.taskRepository.queryPendingTasks(available);
        if (CollectionUtils.isEmpty(pendingTasks)) {
            return;
        }

        for (AsyncTaskEntity task : pendingTasks) {
            // 原子抢占：只有成功把 PENDING 改成 RUNNING(影响行数=1)才真正执行，
            // 多实例部署时其它节点的 updateToRunning 会返回 false，从而避免重复执行。
            boolean claimed = this.taskRepository.updateToRunning(task.getTaskId(), Instant.now());
            if (!claimed) {
                continue;
            }
            submitTask(task);
        }
    }

    private void submitTask(AsyncTaskEntity task) {
        final String taskId = task.getTaskId();
        // 用 FutureTask 手动构造，保证先注册到 runningTasks 再执行，
        // 避免任务过快结束时 remove 早于 put 导致的注册表泄漏。
        FutureTask<Void> future = new FutureTask<>(() -> {
            try {
                dispatchTask(task);
            } finally {
                this.runningTasks.remove(taskId);
            }
            return null;
        });

        this.runningTasks.put(taskId, future);

        try {
            this.executorService.execute(future);
        } catch (RejectedExecutionException e) {
            this.runningTasks.remove(taskId);
            LOG.error("Task {} rejected by executor, revert to failed", taskId, e);
            this.taskRepository.updateFailed(taskId, "Rejected by executor: " + e.getMessage(), Instant.now());
        }
    }

    private void dispatchTask(AsyncTaskEntity task) {
        LOG.info("Start dispatching async task: {} of type {}", task.getTaskId(), task.getTaskType());

        AsyncTaskHandler handler = this.handlersMap.get(AsyncTaskType.valueOf(task.getTaskType()));
        if (handler == null) {
            this.taskRepository.updateFailed(task.getTaskId(), String.format("Unsupported task type: %s", task.getTaskType()), Instant.now());
            return;
        }

        try {
            JsonNode queryParamsNode = objectMapper.readTree(task.getQueryParams());

            // 1. 参数校验阶段
            try {
                handler.validate(queryParamsNode);
            } catch (Exception e) {
                LOG.warn("Task {} validation failed: {}", task.getTaskId(), e.getMessage());
                this.taskRepository.updateFailed(task.getTaskId(), String.format("Validation failed: %s", e.getMessage()), Instant.now());
                return;
            }

            // 2. 执行核心逻辑
            TaskExecuteResult execResult = handler.execute(task, queryParamsNode, Paths.get(this.resultDir));

            // 3. 保存结果
            this.taskRepository.updateSuccess(task.getTaskId(),
                    execResult.getResultPath(),
                    objectMapper.writeValueAsString(execResult.getMeta()),
                    Instant.now());

            LOG.info("Task {} executed successfully", task.getTaskId());

        } catch (InterruptedException e) {
            // 被取消或超时中断：不写 FAILED，交由发起中断方(取消/看门狗)负责置终态。
            Thread.currentThread().interrupt();
            LOG.warn("Task {} was interrupted (cancelled or timed out)", task.getTaskId());
        } catch (Exception e) {
            LOG.error("Failed to execute task " + task.getTaskId(), e);
            handleFailure(task, e.getMessage());
        }
    }

    private void handleFailure(AsyncTaskEntity task, String errorMsg) {
        int retryCount = task.getRetryCount() != null ? task.getRetryCount() : 0;
        int maxRetries = task.getMaxRetries() != null ? task.getMaxRetries() : 0;
        if (retryCount < maxRetries) {
            LOG.info("Task {} failed, will retry. Retry count: {}/{}", task.getTaskId(), retryCount, maxRetries);
            this.taskRepository.increaseRetryCount(task.getTaskId(),
                    String.format("Failed: %s. Retry count: %d/%d.", errorMsg, retryCount, maxRetries));
        } else {
            LOG.error("Task {} failed after {} retries.", task.getTaskId(), maxRetries);
            this.taskRepository.updateFailed(task.getTaskId(), errorMsg, Instant.now());
        }
    }

    /**
     * 中断正在执行的任务线程（协作式取消，依赖 handler 响应中断标志）。
     *
     * @return true 表示该任务确实在本节点运行并已发出中断
     */
    public boolean interruptRunning(String taskId) {
        Future<?> future = this.runningTasks.get(taskId);
        if (future != null) {
            future.cancel(true);
            return true;
        }
        return false;
    }

    @PreDestroy
    public void shutdown() {
        this.executorService.shutdownNow();
        try {
            if (!this.executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.warn("Async task executor did not terminate within timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
