-- =============================================================
-- 12Timers 向量检索微服务 建表 DDL（H2，启动自动执行）
-- 使用 IF NOT EXISTS：项目重启不丢数据、不报错
-- =============================================================

-- 文档表：原始文本 + 元信息 + 向量（256 维 float，共 1024 字节）
CREATE TABLE IF NOT EXISTS documents (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id        VARCHAR(64)  NOT NULL,
    text          CLOB         NOT NULL,
    channel       VARCHAR(32)  NOT NULL DEFAULT 'default',
    status        VARCHAR(16)  NOT NULL,            -- PENDING / READY / INVALID / FAILED
    vector        VARBINARY(2048),                  -- 就绪后写入，ByteBuffer 序列化
    hit_count     BIGINT       NOT NULL DEFAULT 0,  -- 被检索命中次数
    submit_time   TIMESTAMP    NOT NULL,
    complete_time TIMESTAMP,
    invalid_time  TIMESTAMP,
    CONSTRAINT uk_documents_doc_id UNIQUE (doc_id)
);

CREATE INDEX IF NOT EXISTS idx_documents_channel_status ON documents (channel, status);

-- 向量化任务表：异步流水线状态机
CREATE TABLE IF NOT EXISTS vector_tasks (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id     VARCHAR(64) NOT NULL,
    doc_id      VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL,               -- QUEUED / PROCESSING / SUCCESS / FAILED
    error_msg   VARCHAR(512),
    create_time TIMESTAMP   NOT NULL,
    start_time  TIMESTAMP,
    finish_time TIMESTAMP,
    CONSTRAINT uk_tasks_task_id UNIQUE (task_id)
);

CREATE INDEX IF NOT EXISTS idx_tasks_status ON vector_tasks (status);
