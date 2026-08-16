# 空间分析异步任务架构融合方案 (V4)

您提供的这份参考设计方案非常专业，它的核心思想（策略模式+统一任务入口）与我们刚刚实施的 **V3 架构不谋而合**，但在**字段设计的精细度**和**接口定义的规范性**上，这份参考方案比我们的 V3 更加完善和贴近实战。

我仔细拜读了这份方案，建议我们**立刻吸收它的精华**，将我们的系统升级到 V4 版本。

## 吸收的亮点与改进计划

### 1. 表结构升级：引入进度与元数据
参考方案中的几个扩展字段非常实用，我们需要加进 `AsyncTask` 实体中：
*   **`progress` (进度)**: 我们的旧版只有状态流转，加上 `progress (0-100)` 后，对于跨度很大的区域碰撞分析，前端能展示友好的进度条。
*   **`resultMeta` (结果摘要 JSON)**: 这是一个极好的设计。主文件路径存在 `resultPath`，而像“碰撞出的人员总数”、“热力图的 OSS 地址”等短小精悍的摘要信息存放在 `resultMeta` 中。前端请求状态时可以直接拿到元数据展示面板，而不用去下载巨大的结果文件。
*   **`priority` (优先级)**: 可以用于未来调度器的优先级排序。

### 2. 执行器接口升级：规范化流转
参考方案中的 `AnalysisTaskHandler` (也就是我们的 `ITaskExecutor`) 设计得更规范：
*   **增加 `validate` 阶段**: 在任务真正扔给 ClickHouse 跑之前，先在线程里执行一次 `validate(JsonNode)`，如果有参数格式错误直接 FAILED，避免浪费底层计算资源。
*   **规范返回值 `TaskExecuteResult`**: 我们之前返回的是泛泛的 `Object` 然后统一转 JSON 落盘。采用规范的 `TaskExecuteResult` 后，执行器可以精确控制哪些存入文件 (`resultPath`)，哪些存入数据库 (`meta`)，哪些更新进度 (`progress`)。

### 3. 首次出现人员的高危预警
参考方案中对“首次出现人员（新面孔）”的警告极其精准。如果在 ClickHouse 中几十亿条数据做 `NOT IN` 或 `NOT EXISTS` 的全量历史回溯，绝对会灾难性地拖垮集群。
*   **我们的应对策略**：同意参考方案的建议。如果是做这个功能，我们在 `execute` 逻辑中，强制加上时间回溯限制（例如 `WHERE dt > 过去180天`），或者未来在 ClickHouse 端建立物化视图来辅助。

---

## Proposed Changes (拟修改清单)

如果吸收这些优秀的设计，我们的代码需要做如下调整：

1.  **修改 Entity `AsyncTask.java`**:
    *   增加 `progress` (Integer)
    *   增加 `resultMeta` (String, columnDefinition = "TEXT")
    *   增加 `priority` (Integer)
    *   将 `taskParams` 更名为 `queryParams` 以对齐业界习惯。
2.  **升级接口 `ITaskExecutor.java`**:
    *   引入 `validate(JsonNode queryParams)` 方法。
    *   引入 `TaskExecuteResult` 类作为 `execute` 的统一返回值（包含 `resultPath`, `meta` 等）。
3.  **升级调度器 `AsyncTaskDispatcher.java`**:
    *   在执行时，先调用 `validate`。
    *   将 `TaskExecuteResult` 的信息分别写入 `AsyncTask` 实体并保存。
    *   支持解析并保存 `resultMeta`。

## 用户审核请求

> [!IMPORTANT]
> 1. **是否采纳融合方案？** 这个参考设计非常优秀，它完美补足了我们在进度跟踪和摘要元数据上的缺失。您是否同意我按照上述的【拟修改清单】将我们的代码更新为融合后的 V4 版本？
> 2. **目录存储规范**：参考方案建议结果按任务单独建立文件夹 (`/{taskId}/persons.csv`)，而不是之前的一个孤立的 JSON。这在产生热力图等多个文件时很有用。我将会在新的 Executor 中采用这个路径规范。
