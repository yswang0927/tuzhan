package com.tuzhan.asynctask;

import java.util.Map;

/**
 * 分析结果
 */
public class TaskExecuteResult {

    /** 主结果文件或目录路径 */
    private String resultPath;

    /** 写入 result_meta 的摘要信息 */
    private Map<String, Object> meta;

    public TaskExecuteResult() {
    }

    public TaskExecuteResult(String resultPath, Map<String, Object> meta) {
        this.resultPath = resultPath;
        this.meta = meta;
    }

    public String getResultPath() {
        return resultPath;
    }

    public void setResultPath(String resultPath) {
        this.resultPath = resultPath;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public void setMeta(Map<String, Object> meta) {
        this.meta = meta;
    }
}
