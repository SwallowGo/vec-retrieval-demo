package com.twelvetimers.vector.entity;

/**
 * 向量化任务状态机。
 *
 * <pre>
 * 提交 ──▶ QUEUED ──▶ PROCESSING ──▶ SUCCESS
 *              │            └───────▶ FAILED（队满拒绝 / 执行异常）
 *              └────────────────────▶ FAILED
 * </pre>
 */
public enum TaskStatus {
    /** 已入队，等待工作线程消费 */
    QUEUED,
    /** 工作线程正在执行向量化 */
    PROCESSING,
    /** 向量化成功 */
    SUCCESS,
    /** 向量化失败（含队满拒绝） */
    FAILED
}
