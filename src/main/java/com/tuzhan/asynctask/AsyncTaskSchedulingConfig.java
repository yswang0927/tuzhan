package com.tuzhan.asynctask;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 异步任务模块的定时调度开关。
 * 统一在此开启 @EnableScheduling，供 {@link AsyncTaskExecutor} 的轮询和
 * {@link AsyncTaskWatchdog} 的超时/清理定时任务使用，避免散落多处重复标注。
 */
@Configuration
@EnableScheduling
public class AsyncTaskSchedulingConfig {
}
