[33mcommit 613a741a7f8a0535f4a47a1255916e7957d125f3[m[33m ([m[1;36mHEAD -> [m[1;32mmain[m[33m)[m
Author: SwallowGo <wy15521305463@153.com>
Date:   Wed Aug 19 20:45:28 2026 +0800

    docs: 架构文档补充与真实向量数据库/RAG 的对应关系与适用边界
    
    - 新增 §2.1：本项目（DB 持久化 + 内存索引 + 精确遍历）与真实向量库
      （HNSW ANN 检索）的对比表；FAISS in-process 等真实世界对应物；
      万级文档适用边界；SearchService/EmbeddingCompletionListener 解耦
      支撑的替换演进路径
    
    Co-Authored-By: Claude <noreply@anthropic.com>

[33mcommit de1ea1af59a1e33f1d09202c75ec65079e64bc6b[m
Author: SwallowGo <wy15521305463@153.com>
Date:   Wed Aug 19 16:20:57 2026 +0800

    feat: 前端操作界面（自包含单页，覆盖全部接口功能）
    
    - static/index.html：原生 HTML/CSS/JS，无任何外部依赖，随 jar 部署，
      启动后浏览器访问 / 即操作界面
    - 四个标签页：
      · 文档入库：提交后任务时间线实时轮询（排队中→处理中→完成/失败）
      · 向量检索：相似度可视化条，topK 与渠道过滤
      · 任务查询：状态/时间戳/失败原因/关联文档信息
      · 文档库：分页+渠道/状态过滤、详情、一键失效
    - 首页健康检查指示服务状态；错误统一 toast 展示 {code, message}
    - GET / 由静态首页接管，JSON 服务信息移至 GET /api（Controller 与静态
      资源同路径时 Controller 优先，避免遮蔽首页）
    - 文档同步：ARCHITECTURE API 表与变更记录、README 使用说明
    - 验证：/ → 200 text/html，/api → 200 JSON，检索接口回归正常
    
    Co-Authored-By: Claude <noreply@anthropic.com>

[33mcommit dfbd2020336d6dc29e38773a2fb7e273a8a74001[m
Author: SwallowGo <wy15521305463@153.com>
Date:   Wed Aug 19 16:10:24 2026 +0800

    fix: 访问根路径返回 500（兜底异常处理器误吞 Spring MVC 框架异常）
    
    - 根因：Boot 3.2+ 对未匹配路径抛 NoResourceFoundException（本应 404），
      被 @ExceptionHandler(Exception.class) 兜底误判为 500
    - GlobalExceptionHandler 增加框架异常精确映射：无资源 404、请求方法不允许
      405、媒体类型不支持 415，均先于兜底处理
    - ErrorCode 新增 PATH_NOT_FOUND / METHOD_NOT_ALLOWED / MEDIA_TYPE_NOT_SUPPORTED
    - 新增 GET / 根路径：返回服务信息与接口清单，浏览器访问友好
    - ARCHITECTURE.md 同步：API 表补 GET /，变更记录留痕
    - 验证：/ → 200，/nonexistent → 404，POST 接口 GET 访问 → 405，检索回归正常
    
    Co-Authored-By: Claude <noreply@anthropic.com>

[33mcommit e7998bddc2f37443fddcd68bea31f4e224d0c631[m
Author: SwallowGo <wy15521305463@153.com>
Date:   Wed Aug 19 16:01:02 2026 +0800

    test: 并发集成测试 + 项目文档（README/架构同步/AI 对话记录）
    
    - 并发测试 4 例：32 并发提交全就绪且索引无丢失更新、16 并发重复提交恰好一
      个成功、40 并发检索命中计数精确累加、检索与失效并发互不干扰
    - README.md：功能一览/快速开始/curl 示例/设计要点/配置项/项目结构
    - ARCHITECTURE.md 同步：实施计划落地为实际提交、并发安全清单补充
      「向量化期间被失效」「批量更新与脏检查混用」两条
    - docs/AI_CONVERSATION.md：关键对话与踩坑记录（满足提交要求）
    - 端到端验证：jar + 文件库全流程 curl 通过，重启后数据保留、索引重建
    
    Co-Authored-By: Claude <noreply@anthropic.com>

[33mcommit b5434fac973c23df79397639f9c9429a0034d89b[m
Author: SwallowGo <wy15521305463@153.com>
Date:   Wed Aug 19 15:54:46 2026 +0800

    feat: 参数校验 + 异常体系完善（DTO 注解校验与全局异常映射）
    
    - DTO 校验：SubmitDocumentRequest/SearchRequest 加 @NotBlank/@Size/@Min/@Max；
      控制器 @Valid 校验请求体、@Validated + @NotBlank 校验路径参数
    - GlobalExceptionHandler 完善：DTO 校验失败（字段级错误信息汇总）、
      路径/查询参数校验、非法 JSON、参数类型不匹配 → 400 INVALID_PARAM；
      DataIntegrityViolationException 兜底 409（并发重复提交穿透查重时由唯一索引拦下）；
      未预期异常兜底 500 不泄漏内部细节
    - MockMvc 测试 5 例：缺少字段 400/topK 越界 400/非法 JSON 400/202+409 提交/404 业务异常
    
    Co-Authored-By: Claude <noreply@anthropic.com>

[33mcommit 5f78bb17586695242f13d8ff5bb32b8365646587[m
Author: SwallowGo <wy15521305463@153.com>
Date:   Wed Aug 19 15:53:00 2026 +0800

    feat: REST API 全量 + 内存线程安全索引 + 向量检索
    
    - ReadyIndexService：volatile + ImmutableMap 快照，读零锁；copy-on-write 写 +
      synchronized 串行化防丢失更新；启动从 DB 合并式重建（与重放回调共用写锁）
    - DocumentService：入库异步提交（队满背压落库 FAILED 后报 QUEUE_FULL）、
      失效标记（条件更新防竞态、幂等、事务提交后移除索引）、列表（Specification
      动态过滤+分页）、详情（文档+任务）
    - SearchService：查询向量化 → 快照遍历小顶堆 Top-K（O(N log K)）→
      hit_count 数据库原子累加；渠道过滤；topK 越界业务异常
    - 控制器：POST /documents(202)、POST /documents/{docId}/invalidate(204)、
      GET /documents、GET /documents/{docId}、GET /tasks/{taskId}、POST /search
    - 错误码体系 BusinessException/ErrorCode/GlobalExceptionHandler（基础版，
      校验注解与兜底处理见 commit 5）
    - 修复：@Modifying 批量更新与脏检查混用导致 clear 清掉未 flush 改动的坑 ——
      完成落库统一为批量更新
    - 集成测试 6 例：全流程检索排序与命中计数/渠道过滤/失效过滤/重复提交/列表详情/参数异常
    
    Co-Authored-By: Claude <noreply@anthropic.com>

[33mcommit e91ad100c1576336fec39ffa21040d7ddd73f1b4[m
Author: SwallowGo <wy15521305463@153.com>
Date:   Wed Aug 19 15:39:13 2026 +0800

    feat: 异步向量化引擎（自建线程池+阻塞队列+状态机+启动恢复）
    
    - EmbeddingEngine：ArrayBlockingQueue + ThreadPoolExecutor + AbortPolicy 背压，
      工作线程 Guava ThreadFactoryBuilder 命名
    - EmbeddingProcessor：状态机条件更新防重入（QUEUED→PROCESSING 影响行数判重），
      事务边界拆分：短事务抢占 → 无事务 CPU 耗时模拟（忙循环哈希+延迟）→ 短事务落库
    - 完成回调 EmbeddingCompletionListener：事务提交后发布，为内存索引预留解耦点
    - 启动恢复：遗留 QUEUED/PROCESSING 任务重置重放（向量化确定性幂等）
    - 实体/枚举/仓库：documents + vector_tasks，含 hit_count 原子累加 JPQL
    - 集成测试 3 例：异步完成/失败路径/启动恢复
    - 修复：maven-compiler-plugin 3.13+ 需显式声明 Lombok annotationProcessorPaths
    
    Co-Authored-By: Claude <noreply@anthropic.com>

[33mcommit 9dbc95eee9ecc92391d71038bb3fc0d77b13f3d2[m
Author: SwallowGo <wy15521305463@153.com>
Date:   Wed Aug 19 15:30:15 2026 +0800

    feat: 确定性模拟向量化算法（SHA-256 种子派生 256 维单位向量）
    
    - VectorCalculator：同文本输出完全一致；空文本零向量；L2 归一化后余弦相似度退化为点积
    - VectorCodec：float[] 与 VARBINARY 字节互转（大端序，1024 字节）
    - 单测 8 例：确定性/维度/零向量/归一化/相似度值域/非法入参/编解码往返
    
    Co-Authored-By: Claude <noreply@anthropic.com>

[33mcommit 9f0a593716b455596a3531b94c87f6d626b792e8[m
Author: SwallowGo <wy15521305463@153.com>
Date:   Wed Aug 19 15:28:19 2026 +0800

    feat: 初始化项目骨架（Spring Boot 3.5 + H2 文件模式 + DDL 自动建表）
    
    - Maven：Java 17 编译目标，web/data-jpa/validation/h2/guava/commons-lang3/lombok
    - application.yml：H2 文件持久化模式（jdbc:h2:file:./data/vectordb），schema.sql 启动自动执行
    - 建表 DDL：documents（向量 VARBINARY 存储）+ vector_tasks（任务状态机）
    - 已验证：mvn package 通过、jar 启动成功（2.5s）、H2 数据文件落地
    
    Co-Authored-By: Claude <noreply@anthropic.com>
