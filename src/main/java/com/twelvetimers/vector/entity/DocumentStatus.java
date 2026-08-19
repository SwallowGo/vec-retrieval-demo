package com.twelvetimers.vector.entity;

/**
 * 文档状态。
 *
 * <p>状态流转：PENDING →（向量化完成）→ READY →（标记失效）→ INVALID；
 * 向量化失败 → FAILED。仅 READY 状态的文档参与检索。
 */
public enum DocumentStatus {
    /** 已入库，向量化任务尚未完成 */
    PENDING,
    /** 向量就绪，可参与检索 */
    READY,
    /** 已标记失效，不再参与检索 */
    INVALID,
    /** 向量化失败 */
    FAILED
}
