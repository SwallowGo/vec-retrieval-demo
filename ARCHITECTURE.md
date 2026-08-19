# 12Timers 向量检索微服务 — 架构设计文档

> 本文件为项目架构的唯一权威文档。沟通中任何设计调整都会同步更新本文档并在文末「变更记录」留痕。

## 1. 背景与考察点

中高级 Java 后端笔试题（AI 应用方向）。实现文本模拟向量化与向量检索微服务，考察：**并发编程、队列、数据结构与算法、参数校验、业务异常、内存与线程安全存储**。

核心需求一览：

| 模块 | 需求 |
|---|---|
| 文档入库 | 提交文档返回任务 ID，后台异步向量化，完成前不可检索 |
| 模拟向量化 | 256 维 float；同文本结果完全一致；空文本→零向量；模拟 CPU 耗时 |
| 任务状态 | 排队中 / 处理中 / 完成 / 失败 |
| 向量检索 | 同步接口；topK；渠道过滤；过滤失效文档；更新命中计数 |
| 文档管理 | 标记失效；列表与详情（元信息、向量就绪、失效状态） |
| 溯源统计 | 渠道（默认 `default`）、提交时间、完成时间、命中次数 |

技术约束：Java 17+、Spring、H2 文件模式（重启保留数据）、DDL 随项目自动建表、**队列纯 Java 自实现（不用中间件）**。

## 2. 总体架构

```
                        ┌─────────────────────────────────────────┐
                        │              REST API 层                │
                        │  DocumentController / TaskController    │
                        │  SearchController                       │
                        └──────┬──────────────┬──────────────┬────┘
                               │              │              │
                    ┌──────────▼───┐   ┌──────▼──────┐  ┌────▼─────────┐
                    │DocumentService│  │ SearchService│ │ TaskService  │
                    └──────────┬───┘   └──────┬──────┘  └────┬─────────┘
                               │              │              │
        ┌──────────────────────▼──────────────▼──────────────▼──────────┐
        │                     核心异步引擎（纯 Java 实现）                 │
        │  ┌──────────────┐   ┌─────────────────────────────────────┐   │
        │  │  提交任务(生产者)│──▶│ ArrayBlockingQueue<EmbeddingTask>  │◀──│  背压：队满即拒
        │  └──────────────┘   └──────────┬──────────────────────────┘   │
        │                     ThreadPoolExecutor(固定 N 工作线程)       │
        │                     ┌────────▼────────────────────────┐      │
        │                     │ EmbeddingWorker × N（消费者）     │      │
        │                     │ 状态机: QUEUED→PROCESSING→SUCCESS │      │
        │                     └─────────────────────────────────┘      │
        └──────────────────────────────────────────────────────────────┘
                               │                     │
                    ┌──────────▼──────────┐   ┌──────▼──────────────────┐
                    │ H2 文件模式（持久化）  │   │ 内存线程安全索引 ReadyIndex │
                    │ documents / tasks   │   │ volatile + ImmutableMap │
                    └─────────────────────┘   │  （只含就绪未失效文档向量）    │
                                              └─────────────────────────┘
```

**核心思路**：DB 是唯一事实来源（重启可恢复），内存索引是检索加速层（只读快照），异步引擎用 `BlockingQueue + 线程池` 自实现，不引任何中间件。

## 3. 核心设计决策

### 3.1 确定性模拟向量化算法

题目要求"同文本输出向量完全一致"，本质是**文本哈希派生向量**：

```
vectorize(text):
  空/纯空白文本 → 256 维全零向量
  否则:
    seed = SHA-256(text) 的前 8 字节 → long
    rand = new Random(seed)            // Random 同种子序列完全确定（JLS 保证）
    v[i] = rand.nextFloat() * 2 - 1    // 256 维，值域 [-1, 1]
    对 v 做 L2 归一化 → 单位向量
```

- ✅ 确定性：无时间、无随机数依赖，同文本永远同向量
- ✅ 归一化后 **余弦相似度 = 点积**，计算简化
- ✅ 零向量与任何向量相似度定义为 0（空文本永远排在最后）
- **CPU 耗时模拟**：可配置的忙循环（重复哈希 N 轮）+ 小段 sleep，让"异步处理"有意义

### 3.2 任务状态机

```
 提交 ──▶ QUEUED ──▶ PROCESSING ──▶ SUCCESS ──▶ (文档 READY，可检索)
               │            │
               │            └──▶ FAILED（记录 errorMsg）
               └──(队满拒绝)──▶ FAILED
```

- 状态流转用**条件更新**防重入：`UPDATE tasks SET status='PROCESSING' WHERE id=? AND status='QUEUED'`，影响行数=0 说明已被处理，跳过
- **启动恢复**：`ApplicationReadyEvent` 时把遗留的 QUEUED/PROCESSING 任务重置为 QUEUED 重新入队；因向量化是幂等的，重放安全
- **文档与任务 1:1**：同 docId 重复提交直接拒绝（唯一约束 + 业务校验），避免幂等歧义

### 3.3 内存线程安全索引 ✅（已确认方案）

```
ReadyIndexService:
  private volatile ImmutableMap<String, IndexedVector> snapshot;  // 不可变快照

  读（检索）: 取一次 snapshot 引用遍历 → 线程安全零锁
  写（完成/失效）: copy-on-write —— 基于旧快照构建新 ImmutableMap → volatile 发布
```

- 检索走内存索引（O(快照大小)），命中计数走 DB 原子更新（`hit_count = hit_count + 1`），内存与 DB 各自干擅长的事
- **发布顺序**：先 DB 事务提交，再更新索引 —— 保证"索引里有的数据 DB 里一定有"；DB 已就绪但索引未及更新的窗口期只是暂时搜不到，语义安全
- 应用重启时从 DB 重建索引

### 3.4 背压策略

`ArrayBlockingQueue`（容量可配，如 1024）+ `ThreadPoolExecutor` + **AbortPolicy**。队满时：
1. 捕获 `RejectedExecutionException` → 任务标记 FAILED（原因：队列已满）
2. 返回业务异常 `QUEUE_FULL`，不假排队、不丢任务

### 3.5 重复提交策略 ✅（默认方案：直接拒绝）

重复提交同一 docId：返回业务异常（409 + 文档已存在），任务与文档保持 1:1。备选方案为幂等返回原 taskId，如需要可调整。

## 4. REST API 设计

| 方法 | 路径 | 功能 | 请求 → 响应 |
|---|---|---|---|
| GET | `/` | 服务信息与接口清单 | → 服务名 + endpoints |
| POST | `/api/v1/documents` | 提交文档 | `{docId, text, channel?}` → `{taskId}` |
| GET | `/api/v1/tasks/{taskId}` | 任务状态 | → `{taskId, docId, status, errorMsg, 各时间戳, document?}` |
| POST | `/api/v1/search` | 向量检索（同步） | `{text, topK, channel?}` → `{items:[{docId, channel, score}]}` |
| POST | `/api/v1/documents/{docId}/invalidate` | 标记失效 | → 204 |
| GET | `/api/v1/documents` | 列表（分页+渠道/状态过滤） | → `{items, page, size, total}` |
| GET | `/api/v1/documents/{docId}` | 详情 | → 全量元信息 |

## 5. 数据模型（启动自动建表，DDL 随项目提供）

```sql
CREATE TABLE IF NOT EXISTS documents (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id        VARCHAR(64)  NOT NULL,          -- 唯一约束
  text          CLOB         NOT NULL,
  channel       VARCHAR(32)  NOT NULL DEFAULT 'default',
  status        VARCHAR(16)  NOT NULL,          -- PENDING / READY / INVALID / FAILED
  vector        VARBINARY(2048),                -- 256×4 bytes, ByteBuffer 序列化
  hit_count     BIGINT       NOT NULL DEFAULT 0,
  submit_time   TIMESTAMP    NOT NULL,
  complete_time TIMESTAMP,
  invalid_time  TIMESTAMP,
  CONSTRAINT uk_doc_id UNIQUE (doc_id)
);
CREATE INDEX idx_doc_channel_status ON documents(channel, status);

CREATE TABLE IF NOT EXISTS vector_tasks (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id     VARCHAR(64) NOT NULL,
  doc_id      VARCHAR(64) NOT NULL,
  status      VARCHAR(16) NOT NULL,             -- QUEUED / PROCESSING / SUCCESS / FAILED
  error_msg   VARCHAR(512),
  create_time TIMESTAMP NOT NULL,
  start_time  TIMESTAMP,
  finish_time TIMESTAMP,
  CONSTRAINT uk_task_id UNIQUE (task_id)
);
```

## 6. 关键流程

**入库（异步）**：

```
Client → 校验参数 → 查重(docId) → 插入 document(PENDING) + task(QUEUED)
      → 入队 → 返回 taskId
Worker: 出队 → 条件更新 PROCESSING → vectorize(忙循环+哈希) → 事务内写入 vector+READY
      → 更新索引 → task=SUCCESS
```

**检索（同步）**：

```
Client → 校验(text/topK) → vectorize(query) → 取索引快照(过滤渠道)
      → 逐条算余弦相似度 → 排序取 topK
      → UPDATE hit_count += 1 (批量原子更新，仅 topK 条) → 返回结果
```

## 7. 包结构

```
com.twelvetimers.vector
├── Application.java
├── config/           # 线程池、队列容量、模拟耗时等配置
├── controller/       # Document / Task / Search 三个 Controller
├── service/
│   ├── DocumentService / SearchService / TaskService
│   └── embedding/
│       ├── EmbeddingEngine      # 队列 + 线程池 + 启动恢复
│       ├── EmbeddingWorker      # 消费者：状态机驱动
│       └── VectorCalculator     # 确定性向量化算法
├── index/            # ReadyIndexService（内存线程安全索引）
├── entity/  repository/  dto/
└── exception/        # BusinessException + 全局异常处理
```

## 8. 并发安全清单（逐条对应考察点）

| 场景 | 方案 |
|---|---|
| 同文档重复提交 | doc_id 唯一约束 + 提交前业务查重 |
| 任务被重复消费 | 条件更新（乐观锁）防重入 |
| 检索读 vs 索引写 | volatile + 不可变快照（copy-on-write），读零锁 |
| 命中计数并发累加 | DB 原子 UPDATE，不经过内存变量 |
| 队列溢出 | 有界队列 + 拒绝策略 + 失败落库 |
| 重启后任务恢复 | 幂等重放 + 索引重建 |
| 检索与失效并发 | 快照语义：返回"检索时刻"的一致视图 |
| 向量化期间文档被失效 | 完成落库为条件更新（仅 PENDING→READY），失效文档不会被重新就绪 |
| 批量更新与脏检查混用 | `@Modifying(clearAutomatically=true)` 会清掉同事务内未 flush 的脏检查改动；完成落库统一为批量更新 |

## 9. 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| JDK | Java 17 | 题目要求 |
| 框架 | Spring Boot 3.3.x | Java 17 强制要求，JPA/H2 集成成熟 |
| ORM | Spring Data JPA | 题目二选一，选 JPA。理由：本题 SQL 复杂度极低（零联表、检索在内存做），MyBatis 强项用不上；JPA 免手写 CRUD、自动建表、事务集成无缝，契合 1 小时时间约束；防重入条件更新与命中计数原子累加用 `@Modifying` JPQL 完全覆盖。若后续出现复杂报表 SQL 可局部混用 MyBatis，架构上松耦合 |
| 存储 | H2 2.2 文件模式（`jdbc:h2:file:./data/vectordb`） | 重启保留数据 |
| 建表 | `schema.sql` + `spring.sql.init.mode=always` | 题目要求 DDL 随项目提供 |
| 工具 | Guava（ImmutableMap）、commons-lang3、Lombok | 题目允许 |
| 构建 | Maven | 通用 |
| 测试 | JUnit 5 + spring-boot-starter-test | 加分项 |

## 10. 实施计划（含 git 迭代记录）

| 提交 | 内容 | 体现的考察点 |
|---|---|---|
| commit 1 | 项目骨架：pom、yml、DDL、启动类 | 可运行项目 |
| commit 2 | 向量化算法 + 单测（确定性/零向量/维度） | 算法 |
| commit 3 | 异步引擎：队列+线程池+状态机+启动恢复 | 并发、队列 |
| commit 4 | REST API 全量 + 内存索引 + 检索（异常基础设施提前到此提交，见变更记录） | 数据结构、业务 |
| commit 5 | 参数校验注解 + 全局异常处理完善 | 参数校验、业务异常 |
| commit 6 | 并发集成测试 + ARCHITECTURE/README + AI 对话记录 | 文档、测试 |

## 11. 变更记录

| 日期 | 变更内容 | 原因 |
|---|---|---|
| 2026-08-19 | 初版创建；确认检索走内存索引方案；重复提交暂定直接拒绝（默认方案） | 与 AI 沟通梳理架构 |
| 2026-08-19 | 技术选型表补充 ORM 选 JPA 的完整理由 | 用户追问 JPA vs MyBatis 决策依据 |
| 2026-08-19 | 实施计划细化：异常基础设施（BusinessException/ErrorCode/基础处理器）提前到 commit 4，commit 5 专注校验注解与处理完善；并发安全清单补充"向量化期间被失效"与"批量更新与脏检查混用"两条 | 实施过程中的实际调整 |
| 2026-08-19 | 全局异常处理补充框架异常精确映射（无资源 404 / 方法不允许 405 / 媒体类型 415），避免兜底处理器把 Spring MVC 框架异常误判为 500；新增 GET / 根路径服务信息接口 | 用户实测发现访问根路径返回 500 的缺陷 |
