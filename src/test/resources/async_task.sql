CREATE TABLE async_task (
    task_id         VARCHAR(64)  PRIMARY KEY,
    task_type       VARCHAR(50)  NOT NULL,                  -- 任务类型（见枚举）
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',-- PENDING/RUNNING/SUCCESS/FAILED/CANCELLED
    progress        INT          NOT NULL DEFAULT 0,        -- 0-100
    priority        INT          NOT NULL DEFAULT 5,        -- 优先级（可选）

-- 核心：差异化查询参数
    query_params    TEXT        NOT NULL,

-- 结果相关
    result_path     VARCHAR(512),                           -- 主结果文件/目录地址（OSS/本地）
    result_meta     TEXT,                                  -- 结果摘要信息（人数、文件列表、热力图地址等）
    error_msg       TEXT,

-- 审计与时间
    creator         VARCHAR(64)  NOT NULL,
    created_at      bigint  NOT NULL DEFAULT 0,
    updated_at      bigint  NOT NULL DEFAULT 0,
    started_at      bigint,
    finished_at     bigint,
    retry_count     INT          NOT NULL DEFAULT 0,
    max_retries     INT          NOT NULL DEFAULT 0,

-- 可选扩展
    estimated_cost  INT,                                    -- 预估资源消耗（用于调度）
    timeout_seconds INT          DEFAULT 3600
);

-- 索引
CREATE INDEX idx_async_task_status_created ON async_task(status, created_at);
CREATE INDEX idx_async_task_type ON async_task(task_type);
CREATE INDEX idx_async_task_creator ON async_task(creator);
