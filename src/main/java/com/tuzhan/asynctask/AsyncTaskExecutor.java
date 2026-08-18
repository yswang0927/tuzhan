package com.tuzhan.asynctask;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuzhan.asynctask.handler.AsyncTaskHandler;
import com.tuzhan.asynctask.repository.AsyncTaskEntity;
import com.tuzhan.asynctask.repository.AsyncTaskRepository;

@Component
@EnableScheduling
public class AsyncTaskExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(AsyncTaskExecutor.class);

    private final AsyncTaskRepository taskRepository;
    private final Map<AsyncTaskType, AsyncTaskHandler> handlersMap;
    private final ObjectMapper objectMapper;

    @Value("${tuzhan.task.result-dir:/tmp/tuzhan_tasks/}")
    private String resultDir;
    
    // 限制同时执行的异步任务线程数
    private final ExecutorService executorService = Executors.newFixedThreadPool(5);

    public AsyncTaskExecutor(AsyncTaskRepository taskRepository,
                             List<AsyncTaskHandler> handlers,
                             ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.handlersMap = handlers.stream()
                .collect(Collectors.toMap(AsyncTaskHandler::supportType, Function.identity()));
        this.objectMapper = objectMapper;
    }

    /**
     * 每 5 秒轮询一次 PENDING 状态的任务
     */
    @Scheduled(fixedDelay = 5000)
    public void pollTasks() {
        List<AsyncTaskEntity> pendingTasks = this.taskRepository.queryPendingTasks(20);
        if (CollectionUtils.isEmpty(pendingTasks)) {
            return;
        }

        for (AsyncTaskEntity task : pendingTasks) {
            this.taskRepository.updateToRunning(task.getTaskId(), Instant.now());
            executorService.submit(() -> dispatchTask(task));
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
            
        } catch (Exception e) {
            LOG.error("Failed to execute task " + task.getTaskId(), e);
            handleFailure(task, e.getMessage());
        }
    }
    
    private void handleFailure(AsyncTaskEntity task, String errorMsg) {
        final int retryCount = task.getRetryCount() + 1;
        if (retryCount < task.getMaxRetries()) {
            LOG.info("Task {} failed, will retry. Retry count: {}/{}", task.getTaskId(), task.getRetryCount(), task.getMaxRetries());
            this.taskRepository.increaseRetryCount(task.getTaskId(),
                    String.format("Failed: %s. Retry count: %d/%d.", errorMsg, task.getRetryCount(), task.getMaxRetries()));
        } else {
            LOG.error("Task {} failed after {} retries.", task.getTaskId(), task.getMaxRetries());
            this.taskRepository.updateFailed(task.getTaskId(), errorMsg, Instant.now());
        }
    }

}
