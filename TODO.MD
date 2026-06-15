# 最新已完成的开发任务 (非常硬核！)

已完成的任务目标 (Completed Milestones)
这部分是你简历的核心，展示了你解决复杂技术问题的能力：
加一个 Grafana Dashboard 的配置，展示你如何监控这些 Redis 的命中率和 Kafka 的消费延迟。


实现分布式事务的最终一致性 (Transactional Outbox Pattern)：
采用了 Outbox Pattern 解决了数据库更新与消息发送之间的“双写”一致性问题。
通过 OutboxProcessor 结合数据库 SKIP LOCKED（高性能任务抢占）实现准实时的事件转发，避免了分布式锁的开销。
设计端到端的全链路追踪 (End-to-End Distributed Tracing)：
基于 Spring Boot 3 + Micrometer Tracing + OpenTelemetry 实现了完整的链路追踪。
硬核点：手动处理了异步任务（Scheduled Task）中的 Trace 上下文恢复，确保了从 Web 请求到后台处理、再到 Kafka 消息的 traceId 保持一致。
遵循 W3C Trace Context 标准，编写了自定义 Kafka 拦截器注入追踪头。
高并发下的幂等性保障 (Multi-level Idempotency)：
设计了 Redis (分布式缓存) + Database (唯一约束) 的双层幂等校验机制。
利用客户端生成的 requestId 实现接口防重，保护了金融业务免受网络重试导致的“双花（Double Spend）”风险。
分布式唯一 ID 生成 (Distributed ID Generation)：
实现了类 Snowflake (雪花算法) 的 ID 生成逻辑，确保在分布式环境下生成趋势递增且全局唯一的业务流水号，提升了数据库索引效率。
可监测性增强 (Observability)：
通过 Baggage 机制将业务标识（如 transactionId）绑定至全链路上下文，支持在 Zipkin/Kibana 中通过业务单号一键检索所有关联的技术日志。
2. 建议完成的后续目标 (To-Do / Roadmap)
为了让这份简历在德国 IT 市场无懈可击，建议你后续补充以下内容：

- [ ]单元测试与集成测试 (Testing Culture)：
重要性：德国公司对测试的执着超乎想象。
建议：引入 Testcontainers 编写集成测试，模拟真实的 PostgreSQL 和 Kafka 容器环境，验证 Outbox 流程在异常断电/重启后的恢复能力。
消费者侧的逻辑实现 (Event Consumer)：

这能展示你如何处理 分布式下的消费幂等性（即同一条消息被消费两次怎么办）。
API 自动化文档 (OpenAPI/Swagger)：
- [ ]集成 springdoc-openapi。在德国，清晰的接口定义（Contract-First）是团队协作的基础。
安全防御 (Security)：
- [ ]虽然是演示项目，但如果能加入基础的 Spring Security + JWT，或者简单的权限校验，会显得更加专业。
部署与 CI/CD：
编写一个 Dockerfile 和简单的 docker-compose.yml。
如果能展示你懂 Prometheus + Grafana 如何监控这些导出的指标（Metrics），那你的竞争力将直逼 Senior 水平。

Outbox Pattern (发件箱模式) 完整实现：
AccountController 将事件写入 OutboxEvent 表，确保了业务操作和事件发布的原子性。
OutboxProcessor 通过 @Scheduled 定时任务，以高频轮询 + SKIP LOCKED 的方式，可靠地将 OutboxEvent 推送到 Kafka。这解决了分布式事务中的“双写问题”和“消息丢失问题”，是业界公认的生产级方案。
分布式链路追踪 (Micrometer Tracing + OpenTelemetry)：
依赖集成：build.gradle 已引入 micrometer-tracing-bridge-otel 和 opentelemetry-exporter-zipkin。
配置完善：application.yml 已开启 observation 和 tracing，并配置 W3C 传播协议和日志格式。
跨数据库持久化 Trace Context：OutboxEvent 实体中新增 traceId 和 spanId 字段，解决了 ThreadLocal 无法跨越数据库边界的问题。
Controller 捕获 Trace：AccountController 在写入 Outbox 前，捕获当前请求的 traceId 和 spanId 并持久化。
OutboxProcessor 恢复 Trace：OutboxProcessor 在发送 Kafka 消息时，利用 Observation 机制，手动恢复了之前持久化的 Trace Context，确保了异步链路的完整性。
业务 ID 关联 (Baggage)：在 AccountController 和 OutboxProcessor 中，通过 Baggage 将 transactionId 绑定到链路中，实现了业务 ID 与技术链路的关联查询。
日志关联 (MDC)：AccountService 中使用 MDC.put("bizId", transactionId)，确保消费者日志中包含业务 ID。
端到端幂等性：
Redis 性能优化层：在 AccountController 中使用 Redis setIfAbsent 快速拦截重复请求，保护数据库。
数据库层幂等：通过检查 OutboxEvent 和 ProcessedTransaction 表，确保请求不会重复处理。
分布式 ID 生成：使用 Snowflake 算法生成趋势递增的 transactionId，用于全局对账和性能索引。
Kafka 异步处理：核心转账逻辑通过 Kafka 异步驱动，提高了系统吞吐量和解耦性。
乐观锁 (@Version)：Account 实体中引入 @Version 字段，防止高并发下的数据冲突。
BigDecimal 精度处理：在金融交易中正确使用 BigDecimal，避免浮点数精度问题。
Docker Compose 基础设施：完整的 ELK (Elasticsearch, Kibana)、Prometheus、Grafana、Kafka、PostgreSQL、Redis、ClickHouse 栈，为可观测性和数据分析打下坚实基础。
待开发任务 (硬核加分项)
2. ClickHouse 数据沉淀 (审计闭环) - [硬核加分项]
任务：编写一个 Kafka Consumer，专门监听 banking-transfers 主题，并将处理后的交易数据（ProcessedTransaction）流式写入 ClickHouse。
面试点：
OLAP 引擎：解释 ClickHouse 作为列式存储数据库，如何高效处理海量交易数据的实时分析和审计查询，远超传统关系型数据库。
数据湖/数据仓库：展示你对数据架构的理解，将 OLTP (PostgreSQL) 和 OLAP (ClickHouse) 进行有效分离。
3. Micrometer Metrics (可观测性) - [德国面试必看]
任务：在 TransferInternalService 中引入 Micrometer 埋点，记录关键业务指标，例如：
transfer.success.total (Counter)：转账成功总数。
transfer.failure.total (Counter)：转账失败总数（区分业务失败和系统失败）。
transfer.duration (Timer)：转账操作的耗时。
面试点：
SLO/SLA：如何通过这些指标定义服务的 SLO (Service Level Objectives) 和 SLA (Service Level Agreements)。
Grafana Dashboard：如何配置 Grafana Dashboard 来实时监控转账的 TPS、成功率、错误率和延迟百分位。
- [ ] 4. 架构测试 (ArchUnit) - [体现职业化]
任务：编写一个 ArchUnit 测试，强制规定 Controller 绝对不能直接调用 Repository。
面试点：
代码质量：你如何自动化地维护代码架构的整洁性，防止架构腐化。
分层原则：强调你对分层架构的理解和遵守。
- [ ] 5. Java 21 虚拟线程 (Virtual Threads) - [前沿技术亮点]
任务：将项目中 I/O 密集型操作（如数据库访问、Kafka 消息发送/接收）的线程池替换为使用虚拟线程。
面试点：
高并发 I/O：如何利用虚拟线程在高并发 I/O 场景下显著提升吞吐量，减少线程上下文切换开销。
资源利用率：虚拟线程如何以更低的资源消耗支持更多的并发连接。
- [ ] 6. API 文档 (Swagger/OpenAPI) - [面试必看]
任务：集成 Springdoc OpenAPI (Swagger)，为你的 REST API 生成交互式文档。
面试点：
API 契约：如何确保前后端协作的效率和 API 契约的一致性。
开发体验：展示你对开发者体验的重视。
7. 全局异常处理 (Global Exception Handling)
任务：实现一个 @ControllerAdvice，统一处理应用中的异常，返回友好的错误信息，并隐藏内部堆栈信息。
面试点：
用户体验：如何提升 API 的健壮性和用户体验。
安全性：防止敏感信息泄露。


E. 分布式链路追踪 (Distributed Tracing) - [硬核进阶]
任务：集成 Micrometer Tracing，实现 TraceId 跨 Kafka 传播。
面试点：如何解决异步消息队列导致的链路中断问题？（回答：通过 Kafka Headers 传递 Trace Context）。
在异步架构（尤其是引入 Kafka 后）中，普通的 HTTP TraceId 会在消息队列处断掉。要让这个项目在面试中显得“不可替代”，你需要实现 “全链路上下文传播”：

技术栈建议：使用 Micrometer Tracing (Spring Boot 3 标配) + OpenTelemetry 协议 + Grafana Tempo (或 Jaeger)。
硬核实现点：
Trace 穿透 Kafka：你需要通过 KafkaTemplate 的 ProducerInterceptor 将当前的 traceId 注入到 Kafka 的 Headers 中。
日志关联 (Log Correlation)：在 Kibana 中，通过一个 traceId 不仅能看到 Web 层的请求，还能看到后续 Kafka Consumer 异步转账的所有日志。
业务 ID 映射：将你的 Snowflake ID (transactionId) 关联到 Span 的 Baggage 中。这样在监控系统中搜索业务单号，就能直接拉出整条技术链路。
为了实现全链路追踪，我们需要做以下几件事：
添加 Micrometer Tracing 和 OpenTelemetry 依赖：Spring Boot 3 推荐使用 Micrometer Tracing 配合 OpenTelemetry (OTel) 作为其桥接。
配置 application.yml：启用 Tracing 并指定导出器（例如，到 Zipkin 或直接通过 OTLP 到 Grafana Tempo/Jaeger）。
实现 Kafka ProducerInterceptor：这是最关键的一步，用于将当前的 Trace Context 注入到 Kafka 消息的 Header 中，实现跨服务链路追踪。
更新 AccountService 中的日志：确保日志中包含 traceId 和 spanId，以便在 Kibana 中进行日志关联。

F. 最终一致性闭环 (Outbox Relay)
任务：编写一个定时任务或使用 Change Data Capture (CDC)，将 Outbox 表中的数据可靠地推送到 Kafka。
面试点：如何保证“数据库更新”和“消息发送”的原子性？

B. ClickHouse 数据沉淀 (审计闭环)
你有 ClickHouse 容器，但还没代码。

任务：写一个 Kafka 消费者，专门把 banking-transfers 的事件同步到 ClickHouse。
面试点：为什么要用 ClickHouse 做审计？（回答：OLAP 引擎处理亿级流水查询性能远超 Postgres）。
C. 可观测性集成 (Observability)
德国工程师对“代码在生产环境怎么监控”有着近乎偏执的要求。

任务：在 TransferInternalService 中引入 Micrometer 埋点，记录转账成功/失败的 Counter。
面试点：你如何配置 Grafana 监控转账的 TPS 和错误率？
D. 架构测试 (ArchUnit)
这是最能体现你职业化的一点。

任务：写一个 ArchUnit 测试，强制规定 Controller 绝对不能直接调用 AccountRepository。
面试点：你如何自动化地维护代码架构的整洁性？

A 核心业务：Java 21 虚拟线程 (Virtual Threads)
不要使用传统的 FixedThreadPool。既然你的项目追求 High Performance，必须直接上 Java 21。

面试点: “如何利用虚拟线程在高并发 I/O（如等待数据库和 Kafka）时显著提升吞吐量？”

A. 提升工程健壮性 (Robustness & Observability)
全面集成测试 (Integration Testing with Testcontainers)：德国公司非常看重测试。不要只写 Unit Test，尝试使用 Testcontainers 运行真实的数据库环境进行集成测试。这能体现你对生产环境一致性的理解。
可观测性方案 (Monitoring & Logging)：集成 Prometheus 和 Grafana。在代码中实现自定义的 Prometheus Metrics（例如：接口延迟百分位、业务处理成功率）。并在简历中写明：“通过 Grafana Dashboard 实现了对系统核心指标的实时监控”。
B. 应对德国/欧洲市场特定需求 (Compliance & Localization)
GDPR 合规性设计 (Data Privacy)：这是在德国工作的必修课。在项目中实现一个“数据导出”或“彻底注销账号（Right to be forgotten）”的功能，并在 README 中说明你是如何处理敏感数据加密（PII）的。
多语言支持 (i18n)：至少支持英语和德语（EN/DE）。这能体现你对产品国际化的考虑。
C. 性能优化与架构进阶 (Performance & Scalability)
分布式缓存策略：引入 Redis，并实现 缓存旁路（Cache Aside） 或 延迟双删 逻辑，解决高并发下的数据一致性问题。
异步解耦 (Messaging Queue)：如果你的项目中包含发邮件、生成报表等耗时操作，请引入 RabbitMQ 或 Kafka 进行异步处理。这能体现你具备处理复杂分布式系统的潜力。

你现在搭建的是现代分布式系统的**“可观测性全家桶”**。在德国银行这种对稳定性要求变态的地方，这是标配：

Prometheus + Grafana (指标层 - Metrics)：回答**“系统健康吗？”**。看到的是数字和曲线（如：每秒转账多少次、报错率多少）。
ELK (日志层 - Logging)：回答**“到底发生了什么？”**。看到的是详细的文字证据（日志内容）。
Micrometer Tracing (追踪层 - Tracing)：回答**“哪里慢了？”**。看到的是调用链条（从 A 服务到 B 服务的耗时）。
ClickHouse (分析层 - Analytics)：回答**“业务趋势是什么？”**。看到的是海量数据的审计和统计。
如果你只用其中一个，你的系统就像少了眼睛或耳朵。 面试时，你可以自豪地说：“我构建了一个全方位的可观测性架构，确保任何一笔坏账都有迹可循。”
————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————
1. 核心概念大白话
Micrometer: 它是一个“门面（Facade）”库。就像 SLF4J 是日志的统一接口一样，Micrometer 是指标（Metrics）和链路追踪（Tracing）的统一接口。Spring Boot 3 之后，官方放弃了 Spring Cloud Sleuth，改用 Micrometer Tracing。
Span (跨度): 链路追踪的最小单位。一个请求从开始到结束叫一个 Trace (追踪)，而其中每一个具体的操作（比如查数据库、调 API、发消息）都是一个 Span。Span 里记录了开始时间、结束时间、操作名和 ID。
Baggage (行李): 就像你坐飞机，你的行李会跟着你跨越所有的中转站。Baggage 是在整个链路中随请求传递的键值对。你可以把 transactionId 塞进 Baggage，这样无论请求传到哪个微服务，都能直接拿到它。
MDC (Mapped Diagnostic Context): 这是日志框架（Logback/Log4j2）提供的一种机制，允许你把一些变量（比如 traceId）绑定到当前线程。日志模板里配置 %X{traceId}，打印日志时就会自动带上，不需要你手动在 log.info 里传参。
Kibana: ELK 栈里的可视化界面。Elasticsearch 存数据，Kibana 负责展示。你在搜索框搜一个 traceId，Kibana 就能把所有带这个 ID 的日志排好序展现给你。
2. 引入的两个包是什么？
gradle
implementation 'io.micrometer:micrometer-tracing-bridge-otel'
implementation 'io.opentelemetry:opentelemetry-exporter-zipkin'
Bridge-Otel: Micrometer 只是接口，它需要一个具体的实现。OpenTelemetry (OTel) 是目前的国际标准。这个包的作用是把 Micrometer 的指令翻译成 OpenTelemetry 能听懂的话。
Exporter-Zipkin: 链路追踪产生的数据（Spans）需要发给一个后台存储和展示。虽然包名叫 Zipkin，但它其实支持所有兼容 Zipkin 格式的后端（比如你 Docker 里的 Grafana Tempo 或 Jaeger）。
3. W3C vs B3：为什么用 W3C？
B3: 这是由 Zipkin 创造的旧标准，请求头长这样：X-B3-TraceId, X-B3-SpanId。
W3C (Trace Context): 这是 W3C 组织定义的官方标准。请求头叫 traceparent。
区别: 德国和欧洲的银行、大型企业级项目现在都在推行 Open Standards。W3C 标准更严谨，且由于是官方标准，不同厂商（如 AWS、Azure、Google Cloud）的监控工具都能无缝对接。在面试中提 W3C，代表你遵循的是最新的工业标准。
4. 为什么链路会断？（重点）
这是最关键的底层原理：ThreadLocal。

Tracing 信息（TraceId, SpanId）在 Java 中是存储在 ThreadLocal 变量里的。这意味着：这些信息只能在当前线程中流转。

Controller 线程: 处理 HTTP 请求，有 TraceId。它把数据存入数据库（Outbox 表）。
背景线程 (OutboxProcessor): 它是通过 @Scheduled 启动的独立线程，或者是从线程池里拿出来的。它启动时，它的 ThreadLocal 是空的！
后果: 如果你不在数据库里存一份 traceId，当 OutboxProcessor 发消息给 Kafka 时，它会认为这是一个全新的请求，从而生成一个新的 TraceId。你原本的链路就从这里“断开”了。
5. 为什么允许为 null？
java
.traceId(currentSpan != null ? currentSpan.context().traceId() : null)
这是防御性编程。 如果某个请求不是通过正常的 HTTP 路径进来的（比如你手动调了一个内部方法，或者是在测试环境下），这时候可能根本没有活跃的 Span。如果不判空，直接 currentSpan.context() 就会报 NullPointerException 导致交易失败。银行系统宁可丢掉链路追踪，也不能让交易崩掉。

MeterRegistry 是什么？
它是 Micrometer 库的核心注册表。
类比：如果 log.info() 是写日记（记录发生了什么），那么 meterRegistry.counter() 就是在做记账/统计（记录发生了多少次）。
作用：它不存业务数据，它只存“次数”、“耗时”等指标数据。

Micrometer、Tracer、Prometheus 到底是什么关系？
其实这套体系就是一个**“插座”和“电器”**的关系。
Micrometer (门面/接口)：它是 Spring 官方用的一个统一标准接口。你可以把它理解成一个“全能转换插头”。它不管你后面接的是什么，它只负责在 Java 代码里提供统一的写法。
Micrometer Metrics (MeterRegistry)：这是用来数数的。比如“这个接口被点了几次？”。它处理的是聚合数据（数字）。
Micrometer Tracing (Tracer)：这是用来连线的。比如“请求 A 从 Controller 到了 Service 再到了数据库”。它处理的是链路数据（ID）。
Prometheus (数据库)：它是一个专门存数字的数据库。它每隔 15 秒来问你的 Java 程序：“哎，那个计数器现在到几了？” 然后把数字记在自己的小本本上。
Grafana (画图板)：它连上 Prometheus，把那些数字变成你看到的曲线图。
总结： Micrometer 是你在代码里写的那行命令，Prometheus 是存钱罐，Grafana 是展示你有多少钱的屏幕。

Logback 负责生产数据。
Filebeat 负责运送数据。
Elasticsearch 负责存储索引。
Kibana 负责让你通过界面“爽搜”。

什么是虚拟线程？（老板也听得懂的解释）
在 Java 21 之前，Java 的线程（我们称之为“平台线程”）与操作系统的内核线程是 1:1 绑定的。

昂贵：创建一个内核线程需要分配约 1MB 的栈内存。
有限：一台服务器开几千个线程可能就到极限了，再多就会 OOM（内存溢出）或者 CPU 都在忙着切换线程上下文（Context Switch），而不干正事。
虚拟线程（Project Loom） 引入了 M:N 调度模型：

极其廉价：它是由 JVM 管理的内存对象，不是操作系统管理的资源。创建一个虚拟线程只需要几百字节到几 KB。你可以轻轻松松在一台普通笔记本上开启 100 万个虚拟线程。
核心逻辑：当一个虚拟线程遇到“阻塞操作”（比如等数据库返回数据、等 Kafka 响应、或者 Thread.sleep）时，JVM 会把这个虚拟线程“挂起”，让出底层的操作系统线程去干别的活。等数据回来了，JVM 再把它接回来继续跑。
一句话总结：它让你能用最简单的“阻塞式代码”，跑出只有“异步响应式代码（如 WebFlux）”才能达到的极高性能。

————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————

正规企业级项目是如何处理的？正规的开发流程中，我们采用“开发/测试期自动，生产期严格控制”的隔离策略。1. 多环境配置隔离（最核心的做法）我们通过 Spring 提供的 Profiles（多环境隔离） 机制，让不同的环境读取不同的配置。本地/测试环境 (application-dev.yml)：yamlspring:
  jpa:
    hibernate:
      ddl-auto: update # 本地开发求快，让它自动建表
请谨慎使用此类代码。生产环境 (application-prod.yml)：yamlspring:
  jpa:
    hibernate:
      ddl-auto: validate # 生产环境极其严格，只校验不修改，不一致直接报错拦截
请谨慎使用此类代码。2. 使用专业的数据库版本管理工具（工业界标准）在实际生产中，我们不依赖 Hibernate 建表，而是将所有的表结构变更写成 SQL 脚本文件，交给专业的版本控制工具来随着应用启动自动按顺序执行。目前最主流的两大神器是：Flyway（极其流行、简单好用）Liquibase以 Flyway 为例，你只需要在项目中做两件事：引入依赖：在 build.gradle 中加一行 implementation 'org.flywaydb:flyway-core'。存放脚本：在 src/main/resources/db/migration/ 目录下存放你的 SQL 升级脚本，起名叫 V1__create_outbox_table.sql。当你的 Jar 包在生产环境启动时，Flyway 会率先联网或在本地数据库里检查，发现没有 outbox_events 表，它就会自动执行这个 SQL 脚本去创建表。创建完后，Hibernate 再去 validate 校验，发现表已经存在了，应用顺利启动！


面试谈资补充： 如果面试官问：“你为什么要手动在实体类存 traceId？” 你可以回答：“因为 ThreadLocal 无法跨越数据库持久化边界。在 Outbox 模式下，事件会在数据库中暂存，原本的线程上下文会消失。我通过在数据库中显式存储并手动恢复 Context，确保了分布式系统在异步解耦的情况下依然能保持完整的观测性（Observability）。” 这一句话就足以证明你是专家

数字化银行账户管理系统 (FinTech - 侧重分布式事务)
    • 业务场景： 一个模拟数字银行后端，处理用户开户、跨行转账、多币种转换和账单生成。
    • 硬核点：
        ◦ 分布式事务处理： 确保转账时 A 减钱和 B 加钱的原子性。
        ◦ PDF 账单自动生成： 后端异步生成带水印的 PDF 账单。
        ◦ 容器编排： Java(Spring) + PostgreSQL + Redis (存放 Session) + Docker。
    • 面试谈资： “我如何处理高并发下的转账死锁，以及如何保证金融数据的一致性。”
Digital Banking 项目实战路线图
    • 建议在 Docker Compose 中加入 Prometheus 和 Grafana，并通过 Spring Boot Actuator 暴露指标。在德国，没监控的系统是不允许上线的。 
我们将项目分为四个核心阶段。目前你正处于 Phase 1 的收尾阶段。
Phase 1: Core Foundation & Security (当前阶段)
目标： 构建健壮的数据模型和统一的通信协议。
    • [x] Entity Design: 使用 JPA & Lombok 构建 Account 模型（已完成）。
    • [x] Layered Architecture: Controller -> Service -> Repository 三层解耦（已完成）。
    • [x] Data Integrity: 数据库约束与 Bean Validation 双重防御（已完成）。
    • [ ] Global Exception Handling: 统一异常拦截器，隐藏系统堆栈（正在进行）。
    • [ ] API Documentation: 集成 Swagger/OpenAPI 3.0（还没做，面试必看）。
Phase 2: Transaction Logic (硬核业务逻辑)
目标： 处理银行最核心的业务——钱的流动。
    • [ ] Money Operation: 实现 Deposit（存款）和 Withdraw（取款）接口。
    • [ ] Transactional Safety: 使用 @Transactional 保证转账的原子性（简历高频词）。
    • [ ] Currency Handling: 使用 BigDecimal 避免浮点数精度丢失（硬核细节）。
    • [ ] Concurrency Control: 学习乐观锁 @Version 解决“两个人同时取一笔钱”的问题。
Phase 3: Identity & Security (保安系统)
目标： 别让任何人都能随便改余额。
    • [ ] Spring Security & JWT: 实现用户登录、Token 签发。
    • [ ] Role-Based Access Control (RBAC)：只有管理员能删账号，用户只能查自己的余额。
    • [ ] Password Encoding: 使用 BCrypt 加密存储密码。
Phase 4: Production Readiness (交付标准)
目标： 证明你的代码不是在实验室跑的，而是能上生产环境的。
    • [ ] Unit Testing: 使用 JUnit 5 & Mockito 编写 Service 层测试（德国人极其看重测试覆盖率）。
    • [ ] Database Migration: 使用 Flyway 或 Liquibase 管理数据库版本更新（硬核加分项）。
    • [ ] Dockerization: 编写 Dockerfile，一键部署整个环境。