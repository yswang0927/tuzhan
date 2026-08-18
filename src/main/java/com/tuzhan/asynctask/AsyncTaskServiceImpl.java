package com.tuzhan.asynctask;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import com.tuzhan.asynctask.handler.AsyncTaskHandler;
import com.tuzhan.asynctask.repository.AsyncTaskEntity;
import com.tuzhan.asynctask.repository.AsyncTaskRepository;

@Service
public class AsyncTaskServiceImpl implements AsyncTaskService {

    private final AsyncTaskRepository taskRepository;
    private final AsyncTaskExecutor taskExecutor;

    // spring会自动扫描实现了AnalysisTaskHandler接口的类(@Component)
    private final Map<AsyncTaskType, AsyncTaskHandler> handlersMap;

    public AsyncTaskServiceImpl(AsyncTaskRepository taskRepository,
                                AsyncTaskExecutor taskExecutor,
                                List<AsyncTaskHandler> handlers) {
        this.taskRepository = taskRepository;
        this.taskExecutor = taskExecutor;
        this.handlersMap = handlers.stream().collect(Collectors.toMap(AsyncTaskHandler::supportType, Function.identity()));
    }

    @Override
    public String createTask(CreateAsyncTaskRequest request, String creator) {
        if (request == null) {
            throw new IllegalArgumentException("CreateAsyncTaskRequest is null");
        }
        if (request.getTaskType() == null) {
            throw new IllegalArgumentException("taskType 不能为空");
        }
        if (request.getQueryParams() == null || request.getQueryParams().isNull()) {
            throw new IllegalArgumentException("queryParams 不能为空");
        }

        AsyncTaskHandler handler = this.handlersMap.get(request.getTaskType());
        if (handler == null) {
            throw new IllegalArgumentException("不支持的任务类型: " + request.getTaskType());
        }

        // 1. 参数校验（委托给具体 Handler）
        handler.validate(request.getQueryParams());

        // 2. 生成任务
        String taskId = UUID.randomUUID().toString().replace("-", "");

        AsyncTaskEntity taskEntity = new AsyncTaskEntity();
        taskEntity.setTaskId(taskId);
        taskEntity.setTaskType(request.getTaskType().name());
        taskEntity.setStatus(TaskStatus.PENDING.name());
        taskEntity.setProgress(0);
        taskEntity.setPriority(request.getPriority() != null ? request.getPriority() : 5);
        taskEntity.setQueryParams(request.getQueryParams().toString());
        taskEntity.setCreator(creator);
        taskEntity.setCreatedAt(Instant.now());
        taskEntity.setUpdatedAt(Instant.now());
        taskEntity.setRetryCount(0);
        taskEntity.setMaxRetries(request.getMaxRetries() != null ? request.getMaxRetries() : 0);
        taskEntity.setTimeoutSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 3600);

        this.taskRepository.createTask(taskEntity);

        return taskId;
    }

    @Override
    public Optional<AsyncTaskDetail> getTaskDetail(String taskId) {
        Optional<AsyncTaskEntity> taskEntityOptional = this.taskRepository.findTask(taskId);
        if (taskEntityOptional.isEmpty()) {
            return Optional.empty();
        }

        return Optional.ofNullable(AsyncTaskDetail.of(taskEntityOptional.get()));
    }

    @Override
    public void cancelTask(String taskId) {
        Optional<AsyncTaskDetail> taskDetail = getTaskDetail(taskId);
        if (taskDetail.isEmpty()) {
            return;
        }

        if (taskDetail.get().getStatus() != TaskStatus.PENDING && taskDetail.get().getStatus() != TaskStatus.RUNNING) {
            throw new RuntimeException("当前状态不允许取消");
        }

        // 先落 CANCELLED 状态：执行线程完成时的 updateSuccess/updateFailed 带 status=RUNNING 守卫，
        // 状态已被改走后这些收尾更新不会再把 CANCELLED 覆盖回去。
        this.taskRepository.updateStatus(taskId, TaskStatus.CANCELLED, Instant.now(), "用户取消");

        // 再中断本节点上正在执行的线程（协作式，依赖 handler 响应中断标志）。
        this.taskExecutor.interruptRunning(taskId);
    }

}