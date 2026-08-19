# 12Timers — 文本模拟向量化与向量检索微服务

中高级 Java 后端笔试题实现（AI 应用方向）：模拟 Embedding 向量化（不调用任何 LLM / Embedding API），
覆盖 **并发编程、队列、数据结构与算法、参数校验、业务异常、内存与线程安全存储** 六大考察点。

详细设计见 [ARCHITECTURE.md](ARCHITECTURE.md)，与 AI 的关键对话见 [docs/AI_CONVERSATION.md](docs/AI_CONVERSATION.md)。

## 功能一览

| 功能 | 说明 |
|---|---|
| 文档入库 | 提交文档 ID + 文本 + 渠道（默认 `default`），返回任务 ID，后台队列异步向量化 |
| 模拟向量化 | SHA-256 种子派生 **256 维 float 单位向量**：同文本结果完全一致，空文本零向量；忙循环哈希 + 延迟模拟 CPU 耗时 |
| 任务状态 | 排队中 QUEUED / 处理中 PROCESSING / 完成 SUCCESS / 失败 FAILED，完成后可拿到文档信息 |
| 向量检索 | 同步接口：查询文本 → 余弦相似度（归一化后即点积）→ 相似度降序 Top-K；渠道过滤；过滤失效文档 |
| 文档管理 | 标记失效（不再参与检索）、列表（分页+渠道/状态过滤）、详情（入库/完成时间、向量就绪、失效状态、命中次数） |
| 溯源统计 | 每篇文档记录渠道、提交时间、向量化完成时间、被检索命中次数（数据库原子累加） |

## 快速开始

**环境要求**：JDK 17+（已在 JDK 25 验证）、Maven 3.8+

```bash
# 方式一：直接启动
mvn spring-boot:run

# 方式二：打包运行（可直接提交的产物）
mvn clean package
java -jar target/12timers-0.0.1-SNAPSHOT.jar
```

启动后：
- **前端操作界面**：浏览器访问 `http://localhost:8080` —— 自包含单页（无外部依赖），
  支持文档入库（任务时间线实时轮询）、向量检索（相似度可视化）、任务状态查询、
  文档库（分页/过滤/详情/失效标记），覆盖全部接口功能
- 服务信息 JSON：`http://localhost:8080/api`
- H2 控制台：`http://localhost:8080/h2-console`（JDBC URL: `jdbc:h2:file:./data/vectordb`，用户 `sa`，密码空）
- 数据文件：`./data/vectordb.mv.db`（**文件持久化模式，重启保留数据**）
- 建表 DDL：`src/main/resources/schema.sql`（启动自动执行，`IF NOT EXISTS` 幂等）

## API 示例（curl）

```bash
# 1. 文档入库 → 202，返回任务 ID
curl -X POST http://localhost:8080/api/v1/documents \
  -H "Content-Type: application/json" \
  -d '{"docId": "doc-001", "text": "Java 并发编程线程池阻塞队列", "channel": "news"}'

# 2. 任务状态查询（异步执行中可轮询）
curl http://localhost:8080/api/v1/tasks/<taskId>

# 3. 向量检索（同步，topK 1~100，可选渠道过滤）
curl -X POST http://localhost:8080/api/v1/search \
  -H "Content-Type: application/json" \
  -d '{"text": "Java 并发编程", "topK": 5, "channel": "news"}'

# 4. 标记文档失效（不再参与检索）
curl -X POST http://localhost:8080/api/v1/documents/doc-001/invalidate

# 5. 文档列表 / 详情
curl "http://localhost:8080/api/v1/documents?page=1&size=20&channel=news&status=READY"
curl http://localhost:8080/api/v1/documents/doc-001
```

错误响应统一为 `{"code": "错误码", "message": "...", "timestamp": "..."}`；
错误码：`INVALID_PARAM`(400) / `DOC_NOT_FOUND`·`TASK_NOT_FOUND`(404) / `DOC_DUPLICATE`·`DOC_STATUS_NOT_ALLOWED`(409) / `QUEUE_FULL`(503) / `INTERNAL_ERROR`(500)。

## 核心设计要点

1. **确定性向量化**：`SHA-256(text) → long 种子 → Random 派生 256 维向量 → L2 归一化`。
   同文本任何时刻任何线程输出完全一致（JLS 保证 Random 同种子序列确定）；归一化后余弦相似度退化为点积。
2. **异步引擎（纯 Java，无中间件）**：`ArrayBlockingQueue + ThreadPoolExecutor + AbortPolicy` 背压；
   状态机条件更新防重入（`WHERE status = :expected` 影响行数判重）；事务边界拆分：
   短事务抢占 → 无事务 CPU 耗时计算（不占数据库连接）→ 短事务落库。
3. **内存线程安全索引**：`volatile + ImmutableMap` 快照，检索读零锁；写 copy-on-write 且
   `synchronized` 串行化防丢失更新；约定「先 DB 提交、后更新索引」。
4. **命中计数原子性**：`UPDATE ... SET hit_count = hit_count + 1` 由数据库原子执行，绕过应用层读-改-写。
5. **启动恢复**：遗留 QUEUED/PROCESSING 任务重置重放（向量化确定性幂等），索引从 DB 重建。
6. **并发兜底**：重复提交由业务查重 + 数据库唯一索引双层拦截；失效标记条件更新防竞态。

## 配置项（application.yml）

```yaml
vector:
  embedding:
    workers: 4                # 工作线程数
    queue-capacity: 1024      # 有界队列容量（队满即拒，返回 QUEUE_FULL）
    busy-rounds: 20000        # 模拟 CPU 耗时的哈希迭代轮数
    simulated-delay-ms: 100   # 模拟外部 Embedding API 延迟
```

## 测试

```bash
mvn test   # 26 例：算法单测 8 + 引擎集成 3 + 全流程集成 6 + HTTP 层 5 + 并发集成 4
```

## 项目结构

```
src/main/java/com/twelvetimers/vector
├── config/           # EmbeddingProperties 引擎配置
├── controller/       # Document / Task / Search 三个 Controller
├── dto/              # 请求/响应 record（含校验注解）
├── entity/           # DocumentEntity / VectorTaskEntity + 状态枚举
├── exception/        # ErrorCode / BusinessException / 全局异常处理
├── index/            # ReadyIndexService 内存线程安全索引
├── repository/       # Spring Data JPA（条件更新/原子累加 JPQL）
└── service/
    ├── DocumentService / SearchService / TaskService
    └── embedding/    # EmbeddingEngine / EmbeddingWorker / EmbeddingProcessor / VectorCalculator
```

## Git 迭代记录

| 提交 | 内容 |
|---|---|
| 9f0a593 | 项目骨架：pom、yml、DDL、启动类 |
| 9dbc95e | 确定性向量化算法 + 单测 |
| e91ad10 | 异步引擎：队列+线程池+状态机+启动恢复 |
| 5f78bb1 | REST API 全量 + 内存索引 + 检索 |
| b5434fa | 参数校验 + 异常体系完善 |
| (本提交) | 并发集成测试 + 文档 + AI 对话记录 |
