# 与 AI 的关键对话记录

> 按提交要求，将开发过程中与 AI（Claude Code）的关键对话整理如下。
> 日期：2026-08-19

## 一、需求梳理阶段

**人**：帮我梳理下 request.txt 的要求。

**AI**：梳理出岗位考察点（并发编程、队列、数据结构算法、参数校验、业务异常、内存线程安全存储）、
六大功能模块（入库 / 模拟向量化 / 任务状态 / 向量检索 / 文档管理 / 溯源统计）、技术约束
（Java 17+、H2 文件模式、DDL 自动建表、队列纯 Java 实现）、加分项与提交要求，
并提示了 7 个设计要点（确定性向量化、检索快照一致性、命中计数并发安全、docId 幂等、
队列设计、检索性能、参数校验）。

**人**：要的，先梳理一个架构出来。

**AI**：给出总体架构（REST API 层 / Service 层 / 纯 Java 异步引擎 / H2 + 内存索引）、
核心设计决策（SHA-256 种子派生向量的确定性算法、任务状态机条件更新防重入、
volatile+ImmutableMap 内存索引、背压策略）、API 设计、DDL、并发安全清单、技术选型
（JPA vs MyBatis 初选 JPA）、分 6 个 git 提交的实施计划。

**人**：内存索引实现（确认检索走内存索引），这里刚刚梳理的内容也要同步持久化输出，
后续我们沟通中如果有内容调整也要同步这个文档变更。

**AI**：将架构写入 `ARCHITECTURE.md`，并承诺后续所有设计调整同步该文档 + 变更记录留痕。

## 二、关键设计决策问答

**人**：这里 ORM 框架为什么选 JPA 而不选 MyBatis？

**AI**：核心逻辑是本题 SQL 复杂度极低 —— 全流程 6 类 SQL 全部单表、零联表，
检索相似度在内存算不进 SQL，MyBatis 的强项（多表 join、动态 SQL、手写调优）完全用不上；
而 JPA 免手写 CRUD、自动建表、事务无缝集成，命中 1 小时时间约束；并发场景
（条件更新防重入、命中计数原子累加）用 `@Modifying` JPQL 完全覆盖；
若后续出现复杂报表 SQL 可局部混用 MyBatis，架构松耦合。已同步进 ARCHITECTURE.md。

**人**：ok。这里是动态维护数据库的向量内容到内存中做搜索。一般Agent项目也是这样实现的吗？RAG相关的功能，还是向量数据库支持搜索？

**AI**：一般 Agent/RAG 项目检索交给专用向量数据库（Milvus / Qdrant / Weaviate / pgvector），
本项目是它的"教学简化版"：`ReadyIndexService` 就是向量库内存索引的雏形（向量常驻内存、
持久化落盘），真实世界对应物是 FAISS in-process 模式（进程内建索引、手动持久化，中小型
RAG 应用常用）。适用边界：万级文档精确遍历无压力，十万级开始需要 ANN（HNSW/FAISS），
百万级换独立向量库集群。替换演进路径：把 `ReadyIndexService` 换成向量库客户端即可，
`SearchService` 与 `EmbeddingCompletionListener` 的解耦保证业务层不变。已同步进
ARCHITECTURE.md §2.1。

## 三、实施过程中的关键问题与修复（真实踩坑记录）

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1 | 编译报"找不到符号"（Lombok getter/setter 全丢） | maven-compiler-plugin 3.13+ 不再自动从 classpath 发现注解处理器 | pom 显式声明 `annotationProcessorPaths` |
| 2 | Guava 版本缺失 | Spring Boot 3.5 BOM 不再管理 Guava 版本 | 显式锁定 33.7.1-jre |
| 3 | `markInvalidIfStatus` 报 "No EntityManager with actual transaction" | `@Modifying(flushAutomatically=true)` 在无显式事务时 flush 失败 | 去掉不必要的 flushAutomatically，失效操作用 TransactionTemplate 显式包事务 |
| 4 | 任务永远卡在 PROCESSING，文档却已 READY | `@Modifying(clearAutomatically=true)` 批量更新会清空持久化上下文，把同一事务内实体脏检查的未 flush 改动（任务 SUCCESS）一并丢弃 | 完成落库统一改为批量更新（任务、文档都走 @Modifying），避开脏检查与 clear 的冲突 |
| 5 | 并发测试编译失败 | 返回 void 的 lambda 走 Runnable，`CountDownLatch.await()` 的 InterruptedException 无处声明 | 测试内统一 `awaitLatch` 辅助方法 |
| 6 | 集成测试断言"未命中文档计数=0"失败 | topK=3 与文档数相同，全部文档都被返回 | 修正测试：topK=2 验证未命中文档不计数 |
| 7 | 启动服务访问 `/` 直接返回 500，提示 No static resource | Spring Boot 3.2+ 对未匹配路径抛 `NoResourceFoundException`（本应 404），被 `@ExceptionHandler(Exception.class)` 兜底误判为 500 | GlobalExceptionHandler 增加框架异常精确映射（无资源 404 / 方法不允许 405 / 媒体类型不支持 415），先于兜底处理；新增 `GET /` 返回服务信息 |

## 四、决策记录

| 决策点 | 结论 |
|---|---|
| 检索实现 | 内存线程安全索引（volatile + ImmutableMap 快照，读零锁，写 copy-on-write + synchronized） |
| 重复提交同一 docId | 直接拒绝（409 DOC_DUPLICATE），任务与文档保持 1:1 |
| 队满背压 | AbortPolicy 拒绝 → 任务/文档落库 FAILED → 返回 QUEUE_FULL(503) |
| 事务边界 | 入库不入长事务；处理器拆"抢占（短事务）→ CPU 计算（无事务）→ 落库（短事务）" |
| 索引发布顺序 | 先 DB 提交、后更新索引；启动重建与恢复重放共用写锁 |
| 命中计数 | 数据库原子 UPDATE，不经过内存变量 |

## 五、功能迭代与使用问答

**人**：这里可以简单做个前端页面实现相关的接口功能。

**AI**：自包含单页 `static/index.html`（原生 HTML/CSS/JS、零外部依赖、随 jar 部署），
四个标签页覆盖全部接口：文档入库（任务时间线实时轮询）、向量检索（相似度可视化条）、
任务查询、文档库（分页过滤/详情/一键失效）；根路径 `/` 由静态首页接管，JSON 服务信息
移至 `GET /api`（Controller 与静态资源同路径时 Controller 优先，避免遮蔽首页）；
验证 `/` → 200 text/html、`/api` → 200 JSON、检索回归正常。
