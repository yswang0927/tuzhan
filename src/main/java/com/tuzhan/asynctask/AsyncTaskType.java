package com.tuzhan.asynctask;

/**
 * 分析任务类型
 */
public enum AsyncTaskType {
    TRAJECTORY_QUERY,                 // 单对象轨迹查询
    AREA_COLLISION,                   // 区域碰撞分析
    PERSON_TRAJECTORY_COLLISION,      // 人员轨迹碰撞
    AREA_PERSON_ANALYSIS,             // 区域人员分析
    FIRST_APPEARANCE                  // 首次出现人员
}
