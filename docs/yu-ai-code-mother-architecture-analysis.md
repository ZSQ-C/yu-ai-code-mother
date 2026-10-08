# yu-ai-code-mother 架构与实现详解

> 分析对象：仓库根目录 `e:\AI\GitHub\yu-ai-code-mother`
> 分析范围：后端全部 Java 代码 + 配置 + SQL + 监控配置。**前端目录 `yu-ai-code-mother-frontend/` 按要求忽略。**
> 所有路径均为相对仓库根目录。

## 目录

- [1. 项目概览与技术栈](#1-项目概览与技术栈)
  - [1.1 基础信息](#11-基础信息)
  - [1.2 模块划分](#12-模块划分)
  - [1.3 核心第三方依赖](#13-核心第三方依赖)
  - [1.4 明确「未使用」的技术](#14-明确未使用的技术)
- [2. 整体架构分层](#2-整体架构分层)
- [3. 单体主工程详解](#3-单体主工程详解)
  - [3.1 启动类](#31-启动类)
  - [3.2 包结构总览](#32-包结构总览)
  - [3.3 主链路 A：对话生成代码（SSE 流式）](#33-主链路-a对话生成代码sse-流式)
  - [3.4 主链路 B：创建应用与 AI 智能路由](#34-主链路-b创建应用与-ai-智能路由)
  - [3.5 主链路 C：一键部署 + 异步截图](#35-主链路-c一键部署--异步截图)
  - [3.6 主链路 D：LangGraph4j 工作流](#36-主链路-dlanggraph4j-工作流)
  - [3.7 代码生成类型的四重分发（本项目最核心的扩展点）](#37-代码生成类型的四重分发本项目最核心的扩展点)
- [4. 微服务工程详解](#4-微服务工程详解)
- [5. 框架接口引用详解](#5-框架接口引用详解)
  - [5.1 表 A：项目内定义的业务接口](#51-表-a项目内定义的业务接口)
  - [5.2 表 B：对第三方框架扩展点的实现](#52-表-b对第三方框架扩展点的实现)
  - [5.3 表 C：多实现 + 路由结构](#53-表-c多实现--路由结构)
- [6. 注解引用详解](#6-注解引用详解)
- [7. 配置体系与 SPI 扩展点](#7-配置体系与-spi-扩展点)
- [8. 设计模式与工程亮点总结](#8-设计模式与工程亮点总结)
- [9. 附：阅读顺序建议](#9-附阅读顺序建议)
- [10. 附：逐环节深挖计划](#10-附逐环节深挖计划)

---

## 1. 项目概览与技术栈

### 1.1 基础信息

| 项目 | 值 | 依据 |
|---|---|---|
| 项目名 | yu-ai-code-mother（AI 零代码应用生成平台） | `pom.xml#L14`、`README.md#L1` |
| 定位 | 用户输入需求描述 → AI 生成可运行的 HTML / 多文件 / Vue 工程 → 在线预览、可视化编辑、一键部署分享 | `README.md#L20-L48` |
| 构建工具 | Maven（含 `mvnw` / `mvnw.cmd` 包装器） | 仓库根目录 |
| 父 POM | `spring-boot-starter-parent` **3.5.4** | `pom.xml#L5-L10` |
| JDK | **21**（`<java.version>21</java.version>`） | `pom.xml#L30` |
| 打包形态 | **单 Maven 模块单体应用**（根 `pom.xml` 没有 `<modules>` 段），另有 1 个**独立**的多模块微服务工程 | `pom.xml` 全文 |
| 主源码规模 | `src/main/java` 下约 **154 个 Java 文件**，其中 10 个是 `dev.langchain4j` 包下的**源码级补丁类**（见 §3.7.5），自有业务代码约 144 个 | `src/main/java` 目录树 |
| 测试规模 | 14 个测试类，集中在 `src/test/java/.../ai`、`core`、`langgraph4j`、`utils` | `src/test/java` |
| 前端 | Vue 3 + Vite + TypeScript（`yu-ai-code-mother-frontend/`，本文忽略） | 目录内 `package.json`、`vite.config.ts` |

### 1.2 模块划分

**构建层面只有两个 Maven 工程，且互不聚合：**

| 模块 / 工程 | 职责 | 依赖关系 |
|---|---|---|
| 根工程 `yu-ai-code-mother`（`pom.xml` + `src/`） | 单体后端：用户、应用、对话历史、AI 代码生成、Vue 构建、部署、截图、监控、限流 | 不依赖 `yu-ai-code-mother-microservice`，二者无任何 Maven 父子/依赖关系 |
| `yu-ai-code-mother-microservice`（独立父 POM，含 7 个子模块） | 同一业务的**微服务化重构版**：用 Dubbo + Nacos 把 user / app / ai / screenshot 拆成独立可启动服务 | 独立工程，见 §4 |

**微服务工程内部 7 个子模块**（`yu-ai-code-mother-microservice/pom.xml#L11-L19`）：

| 子模块 | 打包形态 | 职责 |
|---|---|---|
| `yu-ai-code-common` | jar（库） | 通用返回体、异常、注解 `@AuthCheck`、Cors/Json/COS 配置、`CosManager`、`SpringContextUtil`、代码生成器 |
| `yu-ai-code-model` | jar（库） | 实体、DTO、VO、枚举，全工程共享 |
| `yu-ai-code-client` | jar（库） | **Dubbo 服务接口定义层**（`innerservice` 包），无实现、无启动类 |
| `yu-ai-code-user` | 可启动应用 | 用户服务，Dubbo Provider（`@DubboService`） |
| `yu-ai-code-app` | 可启动应用 | 主业务入口（应用、对话历史、AI 编排、部署、限流） |
| `yu-ai-code-ai` | jar（库） | AI 代码生成接口、模型配置、工具集、prompt 资源 |
| `yu-ai-code-screenshot` | 可启动应用 | 截图服务，Dubbo Provider |

### 1.3 核心第三方依赖

全部来自根 `pom.xml#L32-L164`。版本未标注者为 Spring Boot BOM 托管。

| 依赖 | 版本 | 在本项目中的用途（代码依据） |
|---|---|---|
| `spring-boot-starter-web` | 3.5.4 BOM | Spring MVC + 内嵌 Tomcat，所有 `controller` 包 |
| `spring-boot-starter-aop` | BOM | 支撑 `aop/AuthInterceptor.java`、`ratelimter/aspect/RateLimitAspect.java` |
| `spring-session-data-redis` | BOM | Session 存 Redis，`application.yml#L5-L8` `store-type: redis`；登录态写入见 `service/impl/UserServiceImpl.java#L106` |
| `redisson` | **3.50.0** | 分布式限流器 `RRateLimiter`，`ratelimter/aspect/RateLimitAspect.java#L44-L51`；客户端配置 `ratelimter/config/RedissonConfig.java` |
| `langchain4j` | **1.1.0** | AI Service 声明式接口（`@SystemMessage` / `@UserMessage` / `@MemoryId`）、`AiServices.builder`、工具调用 |
| `langchain4j-open-ai-spring-boot-starter` | 1.1.0-beta7 | 提供 `openAiChatModel` Bean（`AiCodeGeneratorServiceFactory.java#L33` 按名注入） |
| `langchain4j-reactor` | 1.1.0-beta7 | 支撑 AI Service 方法返回 `Flux<String>`（`ai/AiCodeGeneratorService.java#L38`、`#L47`） |
| `langchain4j-community-redis-spring-boot-starter` | 1.1.0-beta7 | 提供 `RedisChatMemoryStore`（Redis 持久化对话记忆），配置类 `config/RedisChatMemoryStoreConfig.java` |
| `langgraph4j-core` | **1.6.0-rc2** | 工作流引擎：`StateGraph` / `MessagesStateGraph` / `AsyncNodeAction` / 条件边，见 `langgraph4j/` 整包 |
| `langgraph4j-studio-springboot` | 1.6.0-rc2 | 工作流可视化调试，`langgraph4j/config/LangGraphStudioSampleConfig.java` |
| `dashscope-sdk-java` | **2.21.1** | 阿里云通义万相文生图，用于生成 Logo，`langgraph4j/tools/LogoGeneratorTool.java#L38-L46` |
| `selenium-java` | **4.33.0** | 无头 Chrome 网页截图，`utils/WebScreenshotUtils.java#L93-L124` |
| `webdrivermanager` | **6.1.0** | 自动下载/管理 ChromeDriver，`utils/WebScreenshotUtils.java#L96` |
| `cos_api` | **5.6.227** | 腾讯云对象存储，上传截图与 Mermaid 架构图，`manager/CosManager.java` |
| `caffeine` | BOM | 本地缓存：AI Service 实例缓存（`ai/AiCodeGeneratorServiceFactory.java#L52-L59`） |
| `spring-boot-starter-actuator` | BOM | 暴露 `/actuator/prometheus`，`application.yml#L97-L104` |
| `micrometer-registry-prometheus` | BOM | AI 指标注册表 `MeterRegistry`，`monitor/AiModelMetricsCollector.java` |
| `mybatis-flex-spring-boot3-starter` | **1.11.1** | ORM：`BaseMapper` / `IService` / `ServiceImpl` / `QueryWrapper`，全 `mapper`、`service` 包 |
| `mybatis-flex-codegen` | 1.11.1 | 代码生成器，`generator/MyBatisCodeGenerator.java`（`main` 方法，非运行时） |
| `HikariCP` | **4.0.3**（显式覆盖） | 连接池；`generator/MyBatisCodeGenerator.java#L27-L30` 直接用它构造数据源 |
| `hutool-all` | **5.8.38** | 文件/HTTP/ZIP/JSON/加密/线程等工具，几乎每个类都在用 |
| `knife4j-openapi3-jakarta-spring-boot-starter` | **4.4.0** | 接口文档，`application.yml#L33-L43` |
| `mysql-connector-j` | BOM，`runtime` | MySQL 驱动 |
| `lombok` | **1.18.36**（`optional` + 编译器注解处理器） | `pom.xml#L168-L180` 显式配置 `annotationProcessorPaths` |
| `spring-boot-starter-test` | BOM，`test` | 单元测试 |

### 1.4 明确「未使用」的技术

以下技术在 README 的营销文案或常见同类项目中常出现，但**本仓库单体工程里确实没有引入**，请勿在面试/文档中臆造：

| 技术 | 结论 | 实际替代方案 |
|---|---|---|
| Spring AI | **未引入**（`pom.xml` 无任何 `spring-ai-*`） | 使用 **LangChain4j 1.1.0** |
| RocketMQ / Kafka / RabbitMQ | **未引入** | 异步用 **JDK 虚拟线程**（`AppServiceImpl.java#L209`、`VueProjectBuilder.java#L23`）+ **Reactor Flux/SSE** |
| Spring Cloud / Nacos / Dubbo（单体） | 单体**未引入** | 单体是进程内 Bean 调用；微服务工程才引入（§4） |
| Elasticsearch / Milvus / 向量库 / RAG | **未引入**（代码里无 embedding、无检索） | 无检索需求；`RedisEmbeddingStoreAutoConfiguration` 被**主动排除**（`YuAiCodeMotherApplication.java#L10`） |
| MinIO | **未引入** | 使用 **腾讯云 COS**（`manager/CosManager.java`） |
| Spring Security / Sa-Token / JWT | **未引入** | `HttpSession` + 自定义 `@AuthCheck` + AOP（`aop/AuthInterceptor.java`） |
| Redis 分布式锁 | **未引入** | Redis 仅用于 Session、Spring Cache、对话记忆、Redisson 限流四件事 |
| Sentinel / Resilience4j | **未引入** | 自研 `@RateLimit` + Redisson 限流 |
| OpenFeign / RestTemplate 远程调用 | **未引入** | 单体无远程调用；微服务用 Dubbo |

---

## 2. 整体架构分层

本项目**不是**教科书式 Controller→Service→Mapper 三段式，实际落位如下（全部指到具体包）：

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 接入层  controller/ + aop/ + ratelimter/aspect/                          │
│   AppController / UserController / ChatHistoryController /               │
│   StaticResourceController / WorkflowSseController / HealthController    │
│   横切：AuthInterceptor(@Around) 、RateLimitAspect(@Before)              │
│   全局出口：exception/GlobalExceptionHandler(@RestControllerAdvice)      │
├─────────────────────────────────────────────────────────────────────────┤
│ 编排层  service/impl/AppServiceImpl.java  +  core/AiCodeGeneratorFacade  │
│   AppServiceImpl：业务编排（鉴权、落库、监控上下文、调用 AI、委托流处理）  │
│   AiCodeGeneratorFacade：AI 能力门面（按生成类型分发 + 流式结果处理）      │
│   core/handler/StreamHandlerExecutor：流式结果 → 对话历史 的二次加工      │
├─────────────────────────────────────────────────────────────────────────┤
│ 领域层  ai/  +  langgraph4j/  +  core/parser/ + core/saver/ + builder/   │
│   ai/：AI Service 接口 + 工厂 + 工具集 + 护轨 + 流式消息模型             │
│   langgraph4j/：图编排（节点/边/状态），当前是独立可运行能力，未接入主链路 │
│   core/parser/、core/saver/：策略 + 模板方法，把 LLM 输出变成磁盘文件     │
│   core/builder/VueProjectBuilder：调用外部 npm 构建                       │
├─────────────────────────────────────────────────────────────────────────┤
│ 数据层  mapper/ + model/entity/ + resources/mapper/*.xml                 │
│   MyBatis-Flex：BaseMapper 接口 + 空 XML（实际用 QueryWrapper 编程式查询）│
├─────────────────────────────────────────────────────────────────────────┤
│ 基础设施层  config/ + manager/ + utils/ + monitor/ + ratelimter/config/  │
│   config/：模型 Bean、Redis 缓存、COS 客户端、跨域、JSON 序列化           │
│   manager/CosManager、utils/WebScreenshotUtils、utils/SpringContextUtil  │
│   monitor/：Micrometer 指标 + ChatModelListener + ThreadLocal 上下文      │
└─────────────────────────────────────────────────────────────────────────┘
```

**调用边界**：单体应用，所有调用都是**进程内方法调用**，没有 RPC、没有消息队列、没有远程服务调用。唯一的「进程外」交互是：

1. 调 LLM HTTP API（DeepSeek / 阿里云 DashScope 兼容模式）；
2. 调 Pexels / Undraw HTTP API 搜图；
3. 调腾讯云 COS 上传文件；
4. `Runtime.exec` 执行本机 `npm install` / `npm run build` / `mmdc`（Mermaid CLI）。

---

## 3. 单体主工程详解

### 3.1 启动类

`src/main/java/com/yupi/yuaicodemother/YuAiCodeMotherApplication.java`：

```java
@EnableCaching
@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
@MapperScan("com.yupi.yuaicodemother.mapper")
public class YuAiCodeMotherApplication {
    public static void main(String[] args) {
        SpringApplication.run(YuAiCodeMotherApplication.class, args);
    }
}
```

三个非默认点，必须注意：

| 项 | 说明 |
|---|---|
| `@EnableCaching` | 开启 Spring Cache，配合 `RedisCacheManagerConfig` 的 `CacheManager` Bean 生效（`AppController.java#L267-L271` 使用） |
| `exclude = RedisEmbeddingStoreAutoConfiguration.class` | **主动关掉** `langchain4j-community-redis` 的向量存储自动装配。本项目只用它的 `RedisChatMemoryStore`，不用 embedding，若不排除会因缺少配置而启动失败 |
| `@MapperScan("com.yupi.yuaicodemother.mapper")` | 三个 Mapper 接口都无 `@Mapper` 注解，**完全依赖这个扫描** |

### 3.2 包结构总览

| 包 | 职责 | 关键类 |
|---|---|---|
| `ai` | AI Service 声明式接口 + 实例工厂 | `AiCodeGeneratorService`、`AiCodeGeneratorServiceFactory`、`AiCodeGenTypeRoutingService(Factory)` |
| `ai.guardrail` | LangChain4j 护轨 | `PromptSafetyInputGuardrail`、`RetryOutputGuardrail` |
| `ai.model` / `ai.model.message` | AI 结构化输出模型 + 流式消息协议 | `HtmlCodeResult`、`MultiFileCodeResult`、`StreamMessage`、`AiResponseMessage`、`ToolRequestMessage`、`ToolExecutedMessage`、`StreamMessageTypeEnum` |
| `ai.tools` | LLM 可调用的文件操作工具 | `BaseTool`、`ToolManager`、`FileWriteTool`、`FileReadTool`、`FileModifyTool`、`FileDeleteTool`、`FileDirReadTool`、`ExitTool` |
| `annotation` | 自定义注解 | `AuthCheck` |
| `aop` | 权限切面 | `AuthInterceptor` |
| `common` | 统一响应与分页基类 | `BaseResponse`、`ResultUtils`、`PageRequest`、`DeleteRequest` |
| `config` | 全部 Bean 装配 | `StreamingChatModelConfig`、`ReasoningStreamingChatModelConfig`、`RoutingAiModelConfig`、`RedisChatMemoryStoreConfig`、`RedisCacheManagerConfig`、`JsonConfig`、`CorsConfig`、`CosClientConfig` |
| `constant` | 常量 | `AppConstant`、`UserConstant` |
| `controller` | HTTP 入口 | `AppController`、`UserController`、`ChatHistoryController`、`StaticResourceController`、`WorkflowSseController`、`HealthController` |
| `core` | AI 生成结果的解析/保存门面 | `AiCodeGeneratorFacade`、`CodeParser`（`@Deprecated`）、`CodeFileSaver`（`@Deprecated`） |
| `core.builder` | 外部命令构建 | `VueProjectBuilder` |
| `core.handler` | 流式结果加工 | `StreamHandlerExecutor`、`JsonMessageStreamHandler`、`SimpleTextStreamHandler` |
| `core.parser` | 策略：LLM 文本 → 结果对象 | `CodeParser<T>`、`HtmlCodeParser`、`MultiFileCodeParser`、`CodeParserExecutor` |
| `core.saver` | 模板方法：结果对象 → 磁盘文件 | `CodeFileSaverTemplate<T>`、`HtmlCodeFileSaverTemplate`、`MultiFileCodeFileSaverTemplate`、`CodeFileSaverExecutor` |
| `exception` | 异常体系 | `BusinessException`、`ErrorCode`、`ThrowUtils`、`GlobalExceptionHandler` |
| `generator` | 离线代码生成器（`main`） | `MyBatisCodeGenerator` |
| `langgraph4j` | 工作流编排（独立能力） | `CodeGenWorkflow`、`CodeGenConcurrentWorkflow`、`CodeGenSubgraphWorkflow`、`node/*`、`state/WorkflowContext` |
| `manager` | 第三方 SDK 封装 | `CosManager` |
| `mapper` | MyBatis-Flex Mapper | `AppMapper`、`ChatHistoryMapper`、`UserMapper` |
| `model.dto/entity/enums/vo` | 传输对象 / 实体 / 枚举 / 视图对象 | `App`、`User`、`ChatHistory`、`CodeGenTypeEnum`、`AppVO` 等 |
| `monitor` | AI 可观测性 | `AiModelMonitorListener`、`AiModelMetricsCollector`、`MonitorContext(Holder)` |
| `ratelimter` | 限流（注意包名拼写为 `ratelimter`，非 `ratelimiter`） | `RateLimit`、`RateLimitAspect`、`RateLimitType`、`RedissonConfig` |
| `service` / `service.impl` | 业务服务 | `AppService(Impl)`、`UserService(Impl)`、`ChatHistoryService(Impl)`、`ScreenshotService(Impl)`、`ProjectDownloadService(Impl)` |
| `utils` | 工具 | `SpringContextUtil`、`WebScreenshotUtils`、`CacheKeyUtils` |

### 3.3 主链路 A：对话生成代码（SSE 流式）

这是项目最核心的端到端链路。入口：`GET /api/app/chat/gen/code?appId=&message=`。

#### 逐步说明

**① 接入 + 限流**
`controller/AppController.java#L59-L86`：

```java
@GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60, message = "AI 对话请求过于频繁，请稍后再试")
public Flux<ServerSentEvent<String>> chatToGenCode(@RequestParam Long appId,
                                                   @RequestParam String message,
                                                   HttpServletRequest request) {
    ...
    User loginUser = userService.getLoginUser(request);
    Flux<String> contentFlux = appService.chatToGenCode(appId, message, loginUser);
    return contentFlux.map(chunk -> {
                Map<String, String> wrapper = Map.of("d", chunk);
                return ServerSentEvent.<String>builder().data(JSONUtil.toJsonStr(wrapper)).build();
            })
            .concatWith(Mono.just(ServerSentEvent.<String>builder().event("done").data("").build()));
}
```

要点：
- 返回值是 `Flux<ServerSentEvent<String>>`，`produces = text/event-stream`，走 **Spring MVC 的响应式返回值支持**（不是 WebFlux，`pom.xml` 里没有 `spring-boot-starter-webflux`）。
- 每个 chunk 被包成 `{"d": "..."}` 的 JSON，**这是本项目特有的约定**，前端按 `d` 取值；流末尾追加一个 `event: done` 事件作为结束标志。
- `@RateLimit` 是自定义注解，`limitType = USER`，**每 60 秒 5 次**。

**② 限流切面（横切，先于方法体执行）**
`ratelimter/aspect/RateLimitAspect.java#L40-L52`：

```java
@Before("@annotation(rateLimit)")
public void doBefore(JoinPoint point, RateLimit rateLimit) {
    String key = generateRateLimitKey(point, rateLimit);
    RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
    rateLimiter.expire(Duration.ofHours(1));
    rateLimiter.trySetRate(RateType.OVERALL, rateLimit.rate(), rateLimit.rateInterval(), RateIntervalUnit.SECONDS);
    if (!rateLimiter.tryAcquire(1)) {
        throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, rateLimit.message());
    }
}
```

- 用 Redisson `RRateLimiter` 做**分布式**限流，key 前缀 `rate_limit:`。
- key 生成规则见 `#L61-L102`：`API` → 类名.方法名；`USER` → `user:{userId}`，**取不到请求上下文或未登录时降级为 `ip:{ip}`**；`IP` → 客户端 IP（优先 `X-Forwarded-For`，其次 `X-Real-IP`，最后 `RemoteAddr`，多级代理取第一段）。

**③ 业务编排**
`service/impl/AppServiceImpl.java#L83-L117`，共 8 步：

| 步骤 | 代码位置 | 做什么 |
|---|---|---|
| 1 参数校验 | L85-L86 | `ThrowUtils.throwIf` 校验 appId、message |
| 2 查应用 | L88-L89 | `this.getById(appId)` |
| 3 权限校验 | L91-L93 | **仅应用创建者**可对话 |
| 4 取生成类型 | L95-L99 | `CodeGenTypeEnum.getEnumByValue(app.getCodeGenType())` |
| 5 **先落用户消息** | L101 | `chatHistoryService.addChatMessage(...USER...)`，在调 AI 之前写库 |
| 6 设置监控上下文 | L103-L108 | `MonitorContextHolder.setContext(MonitorContext.builder().userId().appId().build())` |
| 7 调 AI 门面 | L110 | `aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId)` |
| 8 包装流 + 收尾 | L112-L116 | `streamHandlerExecutor.doExecute(...)`，`doFinally` 里 `MonitorContextHolder.clearContext()` |

**④ AI 门面按类型分发**
`core/AiCodeGeneratorFacade.java#L79-L103`：

```java
return switch (codeGenTypeEnum) {
    case HTML -> { Flux<String> codeStream = aiCodeGeneratorService.generateHtmlCodeStream(userMessage);
                   yield processCodeStream(codeStream, CodeGenTypeEnum.HTML, appId); }
    case MULTI_FILE -> { Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(userMessage);
                         yield processCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId); }
    case VUE_PROJECT -> { TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage);
                          yield processTokenStream(tokenStream, appId); }
    default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的生成类型：" + codeGenTypeEnum.getValue());
};
```

**⑤ 取 AI 服务实例（带缓存 + 对话记忆）**
`ai/AiCodeGeneratorServiceFactory.java#L78-L136`：

```java
public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
    String cacheKey = buildCacheKey(appId, codeGenType);   // appId + "_" + codeGenType.getValue()
    return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType));
}
```

Caffeine 缓存策略（`#L52-L59`）：`maximumSize(1000)`、`expireAfterWrite(30min)`、`expireAfterAccess(10min)`，并注册了 `removalListener` 打 debug 日志。

`createAiCodeGeneratorService`（`#L90-L136`）关键动作：

```java
MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
        .id(appId).chatMemoryStore(redisChatMemoryStore).maxMessages(20).build();
chatHistoryService.loadChatHistoryToMemory(appId, chatMemory, 20);
...
case VUE_PROJECT -> {
    StreamingChatModel reasoningStreamingChatModel =
        SpringContextUtil.getBean("reasoningStreamingChatModelPrototype", StreamingChatModel.class);
    yield AiServices.builder(AiCodeGeneratorService.class)
            .chatModel(chatModel)
            .streamingChatModel(reasoningStreamingChatModel)
            .chatMemoryProvider(memoryId -> chatMemory)
            .tools(toolManager.getAllTools())
            .hallucinatedToolNameStrategy(toolExecutionRequest ->
                    ToolExecutionResultMessage.from(toolExecutionRequest,
                            "Error: there is no tool called " + toolExecutionRequest.name()))
            .maxSequentialToolsInvocations(20)
            .inputGuardrails(new PromptSafetyInputGuardrail())
            .build();
}
case HTML, MULTI_FILE -> {
    StreamingChatModel openAiStreamingChatModel =
        SpringContextUtil.getBean("streamingChatModelPrototype", StreamingChatModel.class);
    yield AiServices.builder(AiCodeGeneratorService.class)
            .chatModel(chatModel).streamingChatModel(openAiStreamingChatModel)
            .chatMemory(chatMemory)
            .inputGuardrails(new PromptSafetyInputGuardrail())
            .build();
}
```

**这里有 5 个必须点明的项目特有约定：**

1. `@Scope("prototype")`：两个 `StreamingChatModel` Bean 都声明为原型（`config/StreamingChatModelConfig.java#L43-L44`、`config/ReasoningStreamingChatModelConfig.java#L40-L41`），且**通过 `SpringContextUtil.getBean(name, class)` 手动获取**，目的是「使用多例模式的 StreamingChatModel 解决并发问题」（源码注释原文）。若用单例，多个 appId 的流式请求会共享同一模型实例。
2. **只有 VUE_PROJECT 用 `.tools(...)`**，HTML / MULTI_FILE 不带工具，且用 `.chatMemory(...)` 而非 `.chatMemoryProvider(...)`。
3. `hallucinatedToolNameStrategy`：模型编造不存在的工具名时，不抛异常，而是返回一条 `Error: there is no tool called xxx` 的工具结果，让模型自我纠正。
4. `maxSequentialToolsInvocations(20)`：限制连续工具调用次数，防死循环。
5. `outputGuardrails` **被注释掉**（`#L118`、`#L130`），源码注释写「为了流式输出，这里不使用」——即 `RetryOutputGuardrail` 类存在但**运行时未启用**。

**⑥ 流式结果处理（两条分支）**

分支 1：HTML / MULTI_FILE → `processCodeStream`（`AiCodeGeneratorFacade.java#L148-L167`）

```java
StringBuilder codeBuilder = new StringBuilder();
return codeStream.doOnNext(codeBuilder::append)
        .doOnComplete(() -> {
            try {
                String completeCode = codeBuilder.toString();
                Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
                File saveDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
                log.info("保存成功，目录为：{}", saveDir.getAbsolutePath());
            } catch (Exception e) {
                log.error("保存失败: {}", e.getMessage());   // 注意：吞异常，只记日志
            }
        });
```

分支 2：VUE_PROJECT → `processTokenStream`（`#L112-L138`）

```java
return Flux.create(sink -> {
    tokenStream.onPartialResponse(partialResponse -> {
                    sink.next(JSONUtil.toJsonStr(new AiResponseMessage(partialResponse))); })
            .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                    sink.next(JSONUtil.toJsonStr(new ToolRequestMessage(toolExecutionRequest))); })
            .onToolExecuted(toolExecution -> {
                    sink.next(JSONUtil.toJsonStr(new ToolExecutedMessage(toolExecution))); })
            .onCompleteResponse(response -> {
                    String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId;
                    vueProjectBuilder.buildProject(projectPath);   // 同步构建，确保预览时项目已就绪
                    sink.complete(); })
            .onError(error -> { error.printStackTrace(); sink.error(error); })
            .start();
});
```

- 三种事件用 JSON 序列化后**混在同一条流**里，靠 `type` 字段区分（`ai/model/message/StreamMessageTypeEnum.java`：`ai_response` / `tool_request` / `tool_executed`）。
- 完成回调里**同步**执行 `npm install + npm run build`，源码注释明确「确保预览时项目已就绪」。这是主链路里最耗时的同步阻塞点。

**⑦ 流式结果二次加工（写对话历史）**
`core/handler/StreamHandlerExecutor.java#L34-L43` 按类型选处理器：

```java
return switch (codeGenType) {
    case VUE_PROJECT -> jsonMessageStreamHandler.handle(originFlux, chatHistoryService, appId, loginUser);
    case HTML, MULTI_FILE -> new SimpleTextStreamHandler().handle(originFlux, chatHistoryService, appId, loginUser);
};
```

- `SimpleTextStreamHandler`（**没有 `@Component`，每次 `new`**）：把所有 chunk 拼成 `StringBuilder`，`doOnComplete` 时作为一条 AI 消息入库；`doOnError` 时把 `"AI回复失败: " + msg` 入库。
- `JsonMessageStreamHandler`（`@Component`，有 `@Resource ToolManager`，**必须注入所以是单例**）：见 `#L41-L115`。

`JsonMessageStreamHandler` 是链路里最精细的一段，逻辑如下：

| 消息类型 | 处理 |
|---|---|
| `AI_RESPONSE` | 直接追加到 `chatHistoryStringBuilder` 并透传给前端 |
| `TOOL_REQUEST` | 用 `seenToolIds` 去重——同一个 `toolId` 只在**第一次**返回，之后返回空串。第一次时用 `toolManager.getTool(toolName)` 拿到工具实例，调用 `tool.generateToolRequestResponse()` 生成 `[选择工具] xxx` 文本 |
| `TOOL_EXECUTED` | 解析 `arguments` JSON，调 `tool.generateToolExecutedResult(jsonObject)`，包成 `"\n\n" + result + "\n\n"`，同时追加到历史与透传 |

`doOnComplete` 时把累计的 `chatHistoryStringBuilder` 作为**一条** AI 消息入库（不是每条工具事件一条）。

**⑧ 收尾**
`AppServiceImpl.java#L113-L116` 的 `doFinally` 清理 `MonitorContextHolder`——**无论成功/失败/取消都执行**，避免线程复用导致上下文串号。

#### 链路 A 全流程图

```
GET /api/app/chat/gen/code
  └─ RateLimitAspect(@Before)  →  Redisson RRateLimiter.tryAcquire(1)
      └─ AppController.chatToGenCode            [AppController.java#L59]
          └─ UserService.getLoginUser           [UserServiceImpl.java#L112]  (Session → MySQL)
          └─ AppServiceImpl.chatToGenCode       [AppServiceImpl.java#L83]
              ├─ ChatHistoryService.addChatMessage(USER)   →  MySQL
              ├─ MonitorContextHolder.setContext           →  ThreadLocal
              ├─ AiCodeGeneratorFacade.generateAndSaveCodeStream  [Facade#L79]
              │   ├─ AiCodeGeneratorServiceFactory.getAiCodeGeneratorService  [Factory#L78]
              │   │   └─ Caffeine serviceCache.get(key, loader)   ← 本地缓存
              │   │       └─ createAiCodeGeneratorService          [Factory#L90]
              │   │           ├─ MessageWindowChatMemory(maxMessages=20) + RedisChatMemoryStore
              │   │           ├─ ChatHistoryService.loadChatHistoryToMemory(20)  →  MySQL → Memory
              │   │           ├─ SpringContextUtil.getBean("xxxPrototype")  ← 原型 StreamingChatModel
              │   │           └─ AiServices.builder(...)  ← 动态代理生成 AiCodeGeneratorService 实现
              │   ├─ HTML/MULTI_FILE : Flux<String> ─→ processCodeStream   [Facade#L148]
              │   │       └─ doOnComplete: CodeParserExecutor → CodeFileSaverExecutor → 磁盘
              │   └─ VUE_PROJECT     : TokenStream  ─→ processTokenStream  [Facade#L112]
              │           └─ onPartialResponse / onPartialToolExecutionRequest / onToolExecuted
              │              → Flux<JSON字符串>
              │           └─ onCompleteResponse: VueProjectBuilder.buildProject (同步 npm build)
              └─ StreamHandlerExecutor.doExecute    [Executor#L34]
                  ├─ SimpleTextStreamHandler   (HTML/MULTI_FILE)
                  └─ JsonMessageStreamHandler  (VUE_PROJECT, 含工具去重)
                      └─ doOnComplete / doOnError → ChatHistoryService.addChatMessage(AI)
          └─ .map(chunk → {"d": chunk}) → SSE
          └─ .concatWith(event: done)
      └─ doFinally → MonitorContextHolder.clearContext()
```

#### 链路 A 上的异步/缓存/外部调用标注

| 类型 | 位置 | 说明 |
|---|---|---|
| 本地缓存 | `AiCodeGeneratorServiceFactory#L52` | Caffeine 缓存 AI Service 实例 |
| 分布式缓存 | `RedisCacheManagerConfig` + `@Cacheable` | 精选应用分页缓存（不在本链路，见 §3.3 说明） |
| Redis 读写 | `RedisChatMemoryStore` | 对话记忆持久化 |
| 线程局部变量 | `MonitorContextHolder` | ThreadLocal 透传监控上下文 |
| 虚拟线程 | `AppServiceImpl#L209`、`VueProjectBuilder#L23` | 部署截图异步、Vue 异步构建 |
| 阻塞调用 | `VueProjectBuilder#L123-L129` | `Runtime.exec` + `waitFor(timeout)`，**占满当前请求线程** |
| 外部 HTTP | LLM API（DeepSeek / DashScope） | 由 LangChain4j 发起 |
| 无消息队列 | — | 全项目无 MQ |

### 3.4 主链路 B：创建应用与 AI 智能路由

入口：`POST /api/app/add`。

```
AppController.addApp                          [AppController.java#L153-L160]
  └─ UserService.getLoginUser
  └─ AppServiceImpl.createApp                 [AppServiceImpl.java#L120-L139]
      ├─ 校验 initPrompt 非空
      ├─ BeanUtil.copyProperties(appAddRequest, app)
      ├─ app.setAppName(initPrompt.substring(0, min(len,12)))   ← 应用名默认取提示词前 12 字
      ├─ AiCodeGenTypeRoutingServiceFactory.createAiCodeGenTypeRoutingService()  [Factory#L23]
      │     └─ SpringContextUtil.getBean("routingChatModelPrototype", ChatModel.class)  ← 原型
      │     └─ AiServices.builder(AiCodeGenTypeRoutingService.class).chatModel(...).build()
      ├─ aiCodeGenTypeRoutingService.routeCodeGenType(initPrompt)   ← AI 返回枚举
      ├─ app.setCodeGenType(selectedCodeGenType.getValue())
      └─ this.save(app)   →  MySQL，雪花 ID
```

**关键设计点：**

1. `AiCodeGenTypeRoutingService`（`ai/AiCodeGenTypeRoutingService.java#L20-L21`）的返回类型直接是**枚举**：

```java
@SystemMessage(fromResource = "prompt/codegen-routing-system-prompt.txt")
CodeGenTypeEnum routeCodeGenType(String userPrompt);
```

LangChain4j 会把 LLM 输出反序列化成 `CodeGenTypeEnum`（结构化输出）。prompt 文件是 `src/main/resources/prompt/codegen-routing-system-prompt.txt`。

2. 路由用的是**独立的小模型**：`routing-chat-model` → 阿里云百炼兼容模式的 `qwen-turbo`，`max-tokens: 100`（`application.yml#L73-L80`）。这是一个**成本/时延优化点**：分类任务不需要 deepseek-chat 的算力。
3. 工厂方法 `createAiCodeGenTypeRoutingService()` 每次 `new` 一个 AI Service，**但 `routingChatModelPrototype` 也是 prototype**，所以每次都是全新实例，规避并发。
4. 同时 `AiCodeGenTypeRoutingServiceFactory#L33-L36` 也注册了一个 `@Bean`，供 `RouterNode` 通过 `SpringContextUtil.getBean(AiCodeGenTypeRoutingService.class)` 使用。

### 3.5 主链路 C：一键部署 + 异步截图

入口：`POST /api/app/deploy`。`AppServiceImpl.deployApp`（`#L142-L198`）11 步：

| 步骤 | 位置 | 说明 |
|---|---|---|
| 1-3 | L144-L152 | 参数、存在性、**仅本人可部署** |
| 4 | L154-L158 | `deployKey` 为空则 `RandomUtil.randomString(6)`（6 位字母+数字）；DB 上有 `uk_deployKey` 唯一索引（`sql/create_table.sql#L48`） |
| 5-6 | L160-L167 | 拼源码目录 `tmp/code_output/{codeGenType}_{appId}` 并校验存在 |
| 7 | L169-L179 | **Vue 特判**：`vueProjectBuilder.buildProject(sourceDirPath)` → 校验 `dist` 存在 → `sourceDir = distDir` |
| 8 | L181-L186 | `FileUtil.copyContent(sourceDir, deployDirPath, true)`，部署目录 `tmp/code_deploy/{deployKey}` |
| 9 | L188-L193 | 更新 `deployKey`、`deployedTime` |
| 10 | L195 | `String.format("%s/%s/", deployHost, deployKey)`，`deployHost` 来自 `@Value("${code.deploy-host:http://localhost}")`（`#L58-L59`） |
| 11 | L196 | `generateAppScreenshotAsync(appId, appDeployUrl)` |

**异步截图**（`#L206-L219`）：

```java
@Override
public void generateAppScreenshotAsync(Long appId, String appUrl) {
    Thread.startVirtualThread(() -> {
        String screenshotUrl = screenshotService.generateAndUploadScreenshot(appUrl);
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setCover(screenshotUrl);
        boolean updated = this.updateById(updateApp);
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "更新应用封面字段失败");
    });
}
```

**JDK 21 虚拟线程**，无返回值、无异常向上传递（异常只会打到日志）。

**截图链路**（`service/impl/ScreenshotServiceImpl.java#L27-L44`）：

```
generateAndUploadScreenshot(webUrl)
  ├─ WebScreenshotUtils.saveWebPageScreenshot(webUrl)      [WebScreenshotUtils.java#L53]
  │    ├─ static 代码块初始化全局单例 WebDriver（1600x900 无头 Chrome）  [#L30-L37]
  │    ├─ driver.get(url)
  │    ├─ waitForPageLoad: WebDriverWait 等 document.readyState == complete，再 Thread.sleep(2000)
  │    ├─ getScreenshotAs(BYTES) → 存 PNG
  │    ├─ ImgUtil.compress(质量 0.3f) → 存 JPG
  │    └─ 删除原始 PNG，返回压缩后路径
  ├─ uploadScreenshotToCos: cosKey = /screenshots/yyyy/MM/dd/{uuid8}_compressed.jpg
  │    └─ CosManager.uploadFile(key, file) → url = host + key
  └─ finally: cleanupLocalFile(本地临时文件)
```

**坑点（项目特有）：**
- `WebScreenshotUtils` 的 `WebDriver` 是 **static 单例**（`#L30`），`@PreDestroy destroy()`（`#L42-L45`）**对 static 字段无效**——`@PreDestroy` 只对 Spring 管理的 Bean 生效，而该类并未注册为 Bean（无 `@Component`），所以这个方法实际上永远不会被调用。
- `WebScreenshotUtils` 没有任何 Spring 注解，是纯静态工具类；`ScreenshotServiceImpl` 才是 Bean。
- Chrome 启动参数包含 `--no-sandbox` / `--disable-dev-shm-usage`（`#L104-L106`），是**为 Docker 环境准备的**。

### 3.6 主链路 D：LangGraph4j 工作流

**重要定位说明**：`langgraph4j/` 整包目前是**独立可运行的能力**，通过 `WorkflowSseController` 单独暴露，**没有接入 §3.3 的主业务链路**（`AppController` 不调用任何 workflow 类）。

#### 3.6.1 入口
`controller/WorkflowSseController.java#L23-L45`，三个接口：

| 接口 | 返回 | 说明 |
|---|---|---|
| `POST /workflow/execute` | `WorkflowContext` | 同步跑完返回最终上下文 |
| `GET /workflow/execute-flux` | `Flux<String>` | 虚拟线程里跑，每步完成推一个 SSE 文本事件 |
| `GET /workflow/execute-sse` | `SseEmitter` | 同上，用 `SseEmitter`（超时 30 分钟） |

注意：三个方法都是 `new CodeGenWorkflow()`，**每次请求重新建图**（`createWorkflow()` 里 `compile()`）。

#### 3.6.2 三个工作流的图结构

**① `CodeGenWorkflow`（线性 + 质检条件边）** —— `langgraph4j/CodeGenWorkflow.java#L36-L68`

```
START → image_collector → prompt_enhancer → router → code_generator → code_quality_check
                                                                          │ (条件边)
                                          ┌───────────────────────────────┼──────────────────────┐
                                       "fail"→ code_generator        "build"→ project_builder   "skip_build"→ END
                                                                          └→ END
```

条件边用 `edge_async(this::routeAfterQualityCheck)` + `Map.of("build",..., "skip_build",..., "fail",...)`。路由函数（`#L235-L263`）：质检 `isValid` 为假 → `fail`；否则 HTML/MULTI_FILE → `skip_build`，VUE_PROJECT → `build`。

**② `CodeGenConcurrentWorkflow`（并发分支）** —— `langgraph4j/CodeGenConcurrentWorkflow.java#L34-L86`

```
START → image_plan ─┬→ content_image_collector ─┐
                    ├→ illustration_collector   ─┤
                    ├→ diagram_collector        ─┼→ image_aggregator → prompt_enhancer → router
                    └→ logo_collector           ─┘        → code_generator → code_quality_check → (条件边)
```

并发靠 `RunnableConfig`（`#L103-L111`）：

```java
ExecutorService pool = ExecutorBuilder.create()
        .setCorePoolSize(10).setMaxPoolSize(20)
        .setWorkQueue(new LinkedBlockingQueue<>(100))
        .setThreadFactory(ThreadFactoryBuilder.create().setNamePrefix("Parallel-Image-Collect").build())
        .build();
RunnableConfig runnableConfig = RunnableConfig.builder()
        .addParallelNodeExecutor("image_plan", pool)
        .build();
```

**③ `CodeGenSubgraphWorkflow`（子图复用）** —— `langgraph4j/CodeGenSubgraphWorkflow.java#L27-L144`
把 4 个「单节点子图」（`createContentImageSubgraph()` 等，各自 `START → 单节点 → END`）通过 `addNode(name, stateGraph)` 挂到父图上，**父子图共享同一份 `MessagesState`**（源码注释 `#L85`：「跟父图完全共享状态」）。

#### 3.6.3 状态载体

`langgraph4j/state/WorkflowContext.java`：

- 存储 key：`public static final String WORKFLOW_CONTEXT_KEY = "workflowContext"`（`#L30`），所有节点通过 `WorkflowContext.getContext(state)` / `WorkflowContext.saveContext(context)` 读写。
- 字段（`#L35-L93`）：`currentStep`、`originalPrompt`、`imageListStr`、`imageList`、`enhancedPrompt`、`generationType`、`generatedCodeDir`、`buildResultDir`、`qualityResult`、`errorMessage`、`imageCollectionPlan`，以及并发分支的 4 个中间字段 `contentImages` / `illustrations` / `diagrams` / `logos`。

#### 3.6.4 节点清单

| 节点类 | 路径 | 读 | 写 | 调用 |
|---|---|---|---|---|
| `ImageCollectorNode` | `node/ImageCollectorNode.java#L28-L95` | `originalPrompt` | `imageList`、`currentStep` | `ImageCollectionPlanService.planImageCollection` → `CompletableFuture.supplyAsync` 并发跑 4 类工具 |
| `PromptEnhancerNode` | `node/PromptEnhancerNode.java#L21-L57` | `originalPrompt`、`imageList`、`imageListStr` | `enhancedPrompt` | 纯字符串拼接，把图片 URL 列表追加到提示词 |
| `RouterNode` | `node/RouterNode.java#L19-L41` | `originalPrompt` | `generationType` | `AiCodeGenTypeRoutingService`；**异常降级为 `HTML`** |
| `CodeGeneratorNode` | `node/CodeGeneratorNode.java#L24-L49` | `enhancedPrompt`、`qualityResult`、`generationType` | `generatedCodeDir` | `AiCodeGeneratorFacade.generateAndSaveCodeStream(...).blockLast(10min)`；**appId 硬编码为 0L**（源码注释「后续再整合到业务中」）；质检失败时用错误信息重建提示词（`#L54-L92`） |
| `CodeQualityCheckNode` | `node/CodeQualityCheckNode.java#L25-L58` | `generatedCodeDir` | `qualityResult`、`currentStep` | `FileUtil.walkFiles` 拼接代码 → `CodeQualityCheckService.checkCodeQuality`；**异常时降级为 `isValid=true`**（`#L49-L51`，注释「异常直接跳到下一个步骤」） |
| `ProjectBuilderNode` | `node/ProjectBuilderNode.java#L23-L53` | `generatedCodeDir` | `buildResultDir` | `VueProjectBuilder.buildProject`；异常时 `buildResultDir = generatedCodeDir` |
| `ImagePlanNode` | `node/concurrent/ImagePlanNode.java#L19-L35` | `originalPrompt` | `imageCollectionPlan` | `ImageCollectionPlanService` |
| `ContentImageCollectorNode` | `node/concurrent/ContentImageCollectorNode.java` | `imageCollectionPlan` | `contentImages` | `ImageSearchTool` |
| `IllustrationCollectorNode` | `node/concurrent/IllustrationCollectorNode.java` | `imageCollectionPlan` | `illustrations` | `UndrawIllustrationTool` |
| `DiagramCollectorNode` | `node/concurrent/DiagramCollectorNode.java` | `imageCollectionPlan` | `diagrams` | `MermaidDiagramTool` |
| `LogoCollectorNode` | `node/concurrent/LogoCollectorNode.java` | `imageCollectionPlan` | `logos` | `LogoGeneratorTool` |
| `ImageAggregatorNode` | `node/concurrent/ImageAggregatorNode.java` | 4 个中间字段 | `imageList` | 纯聚合 |

**所有节点都用 `node_async(state -> {...})` 包装，且全部通过 `SpringContextUtil.getBean(...)` 拿依赖**——因为节点是 `new` 出来的普通对象，不是 Spring Bean。

#### 3.6.5 工作流里的 4 个 AI 服务

| 接口 | 工厂 | prompt 资源 | 用途 |
|---|---|---|---|
| `ImageCollectionPlanService#planImageCollection` | `ImageCollectionPlanServiceFactory#L18-L23` | `prompt/image-collection-plan-system-prompt.txt` | 返回结构化 `ImageCollectionPlan` |
| `CodeQualityCheckService#checkCodeQuality` | `CodeQualityCheckServiceFactory#L23-L28` | `prompt/code-quality-check-system-prompt.txt` | 返回结构化 `QualityResult` |
| `ImageCollectionService#collectImages` | `ImageCollectionServiceFactory#L39-L50` | `prompt/image-collection-system-prompt.txt` | **已定义但当前无任何节点/类调用**（全仓 grep 只有自身与工厂） |
| （`AiCodeGenTypeRoutingService`） | 复用主链路工厂 | `prompt/codegen-routing-system-prompt.txt` | `RouterNode` 使用 |

#### 3.6.6 图片/架构图工具

| 工具 | 路径 | 外部服务 | 返回 |
|---|---|---|---|
| `ImageSearchTool#searchContentImages` | `langgraph4j/tools/ImageSearchTool.java#L32-L59` | `https://api.pexels.com/v1/search`，Header `Authorization: {pexels.api-key}`，`per_page=12` | `List<ImageResource>`（category=CONTENT） |
| `UndrawIllustrationTool#searchIllustrations` | `langgraph4j/tools/UndrawIllustrationTool.java#L29-L65` | `https://undraw.co/_next/data/.../search/{term}.json`，timeout 10s | category=ILLUSTRATION |
| `LogoGeneratorTool#generateLogos` | `langgraph4j/tools/LogoGeneratorTool.java#L33-L64` | 阿里云 DashScope `ImageSynthesis`，`dashscope.image-model`（默认 `wan2.2-t2i-flash`），`size=512*512`，`n=1` | category=LOGO；提示词强制「Logo 中禁止包含任何文字」 |
| `MermaidDiagramTool#generateMermaidDiagram` | `langgraph4j/tools/MermaidDiagramTool.java#L35-L88` | 本机执行 `mmdc`（Windows 下 `mmdc.cmd`）把 `.mmd` 转 `.svg`，再上传 COS（key `/mermaid/{random5}/{文件名}`） | category=ARCHITECTURE |

#### 3.6.7 demo 与 Studio

- `langgraph4j/demo/`：`SimpleGraphApp`（`main` 方法，手写 `StateGraph` 示例）、`GreeterNode`、`SimpleState`——**教学示例，不参与运行**。
- `langgraph4j/config/LangGraphStudioSampleConfig.java`：继承 `AbstractLangGraphStudioConfig`，把 `CodeGenWorkflow` 的 `stateGraph` 暴露给 LangGraph Studio 做可视化。源码注释直言「实际上很难使用」。
- `src/main/resources/static/test-flux-workflow.html`、`test-sseemitter-workflow.html`：两个手写测试页，用于手动验证 `execute-flux` / `execute-sse`。

### 3.7 代码生成类型的四重分发（本项目最核心的扩展点）

`CodeGenTypeEnum`（`model/enums/CodeGenTypeEnum.java#L12-L14`）只有三个值：`HTML("html")`、`MULTI_FILE("multi_file")`、`VUE_PROJECT("vue_project")`。但**同一个枚举在四个地方被 switch 分发**，读代码时必须四处对齐：

| # | 分发点 | 文件:行 | 依据字段 | 作用 |
|---|---|---|---|---|
| 1 | `AiCodeGeneratorServiceFactory.createAiCodeGeneratorService` | `ai/AiCodeGeneratorServiceFactory.java#L101-L135` | `codeGenType` | 决定用哪个 StreamingChatModel、是否挂工具 |
| 2 | `AiCodeGeneratorFacade.generateAndSaveCodeStream` | `core/AiCodeGeneratorFacade.java#L85-L102` | `codeGenTypeEnum` | 决定走 `Flux<String>` 还是 `TokenStream` |
| 3 | `StreamHandlerExecutor.doExecute` | `core/handler/StreamHandlerExecutor.java#L37-L42` | `codeGenType` | 决定用哪个流处理器写对话历史 |
| 4 | `CodeParserExecutor.executeParser` / `CodeFileSaverExecutor.executeSaver` | `core/parser/CodeParserExecutor.java#L27-L31`、`core/saver/CodeFileSaverExecutor.java#L32-L36` | 枚举 | 决定解析策略与落盘策略（**只支持 HTML / MULTI_FILE，Vue 不走这里**） |

**新增一种生成类型的改动清单**（供扩展时参考）：枚举加值 → 4 处 switch 全部补分支 → 新增 `CodeParser<T>` + `CodeFileSaverTemplate<T>` 实现并注册到 Executor → 新增 prompt 文件。

---

## 4. 微服务工程详解

`yu-ai-code-mother-microservice/` 是**独立 Maven 工程**（不在根 `pom.xml` 的 `<modules>` 里），是同一业务的微服务化重构版。

### 4.1 版本与技术栈

| 项 | 值 | 依据 |
|---|---|---|
| JDK | 21 | `yu-ai-code-mother-microservice/pom.xml#L22-L23` |
| Spring Boot | **3.5.3**（注意与单体的 3.5.4 不同） | 同文件 `#L25` |
| Spring Cloud | 2023.0.1 | `#L26` |
| Spring Cloud Alibaba | 2023.0.1.0 | `#L27` |
| Dubbo | **3.3.0** | `#L28` |
| BOM 导入 | `spring-boot-dependencies` / `spring-cloud-dependencies` / `spring-cloud-alibaba-dependencies` / `dubbo-bom` | `#L63-L94` |
| 编译插件 | `maven-compiler-plugin 3.14.0`，**开启 `<parameters>true</parameters>`** | `#L98-L105` |

`<parameters>true</parameters>` 是 Dubbo/Spring 参数名反射的必要条件，单体工程没有这项配置。

### 4.2 依赖方向

```
common ← model ← client ← user
                  ↑        ← app
                  ↑        ← screenshot
                  ↑        ← ai
app 依赖: common, model, client, ai          (yu-ai-code-app/pom.xml#L20-L90)
user 依赖: common, model, client? → 实际: Dubbo, Nacos, Spring Session Redis, Jedis,
          MyBatis-Flex, MySQL, common, model   (yu-ai-code-user/pom.xml#L20-L76)
screenshot 依赖: Dubbo, Nacos, client, Selenium, webdrivermanager, common  (pom.xml#L20-L51)
ai 依赖: LangChain4j OpenAI starter, reactor, Redis community starter, common, model  (pom.xml#L20-L47)
client 依赖: 仅 common, model                (yu-ai-code-client/pom.xml#L20-L31)
```

依赖方向是**单向**的：`client` 只放接口，`app` 依赖 `client` 拿到接口、`user`/`screenshot` 依赖 `client` 提供实现——典型的**接口与实现分离**，避免 app 直接依赖 user 的实现模块。

### 4.3 Dubbo 服务化

**接口定义（`yu-ai-code-client`）**

| 接口 | 路径 | 方法 |
|---|---|---|
| `InnerUserService` | `yu-ai-code-client/src/main/java/com/yupi/yuaicodemother/innerservice/InnerUserService.java#L18-L34` | 用户查询与 VO 转换；含静态登录用户获取逻辑（避免跨服务调用） |
| `InnerScreenshotService` | `yu-ai-code-client/src/main/java/com/yupi/yuaicodemother/innerservice/InnerScreenshotService.java#L6-L8` | `generateAndUploadScreenshot(String webUrl)` |

**服务提供者（`@DubboService`）**

| 实现类 | 路径 | 行为 |
|---|---|---|
| `InnerUserServiceImpl` | `yu-ai-code-user/.../service/impl/InnerUserServiceImpl.java#L17-L36` | 委托本地 `UserService` |
| `InnerScreenshotServiceImpl` | `yu-ai-code-screenshot/.../service/impl/InnerScreenshotServiceImpl.java#L8-L17` | 委托 `ScreenshotService.generateAndUploadScreenshot` |

**服务消费者（`@DubboReference`）**

`yu-ai-code-app/.../service/impl/AppServiceImpl.java#L61-L77` 注入两个 `@DubboReference`；`#L184-L207` 部署完成后调用 `screenshotService.generateAndUploadScreenshot(appUrl)`。

**Dubbo 配置**（各模块 `application.yml`）：

| 模块 | 注册中心 | 协议 | 端口 | 超时 |
|---|---|---|---|---|
| user | Nacos | tri | 50051 | 60000ms |
| screenshot | Nacos | tri | 50052 | 120000ms |
| app | Nacos | tri | 50053 | 120000ms |

（`yu-ai-code-user/src/main/resources/application.yml#L42-L53`、`yu-ai-code-screenshot/.../application.yml#L18-L29`、`yu-ai-code-app/.../application.yml#L77-L88`）

### 4.4 可启动模块

| 模块 | 启动类 | 路径 |
|---|---|---|
| app | `YuAiCodeAppApplication` | `yu-ai-code-app/.../YuAiCodeAppApplication.java#L10`，`@SpringBootApplication(exclude = RedisEmbeddingStoreAutoConfiguration.class)` |
| user | `YuAiCodeUserApplication` | `yu-ai-code-user/.../YuAiCodeUserApplication.java#L9-L12`，额外扫描 `com.yupi` 包以发现 Dubbo 组件 |
| screenshot | `YuAiCodeScreenshotApplication` | `yu-ai-code-screenshot/.../YuAiCodeScreenshotApplication.java#L7` |

### 4.5 与单体版的实质差异

| 维度 | 单体版 | 微服务版 |
|---|---|---|
| 模块组织 | 单模块，全包 `com.yupi.yuaicodemother` | 7 模块，user 模块根包改为 `com.yupi.yuaicodeuser` |
| 跨域调用 | 进程内 Bean | Dubbo RPC（Nacos 注册，tri 协议） |
| 截图 | 直接注入 `ScreenshotService` | `@DubboReference InnerScreenshotService` |
| 用户查询 | 直接注入 `UserService` | `@DubboReference InnerUserService` |
| LangGraph4j | 有完整 `langgraph4j/` 包 | **未引入**（`langgraph4j` 相关类全仓无） |
| 网关 | 无 | **未引入**（无 gateway 模块、无 `spring-cloud-starter-gateway`） |
| 配置中心 | 无 | 仅用 Nacos 做**注册中心**，未见 `spring-cloud-starter-alibaba-nacos-config` 的配置中心用法 |
| AI 逻辑位置 | `ai/` 包在主工程内 | 抽到独立 `yu-ai-code-ai` 模块（LangChain4j 接口、模型配置、工具集、prompt 资源），app 模块依赖它 |

---

## 5. 框架接口引用详解

### 5.1 表 A：项目内定义的业务接口

| 接口 | 路径 | 实现类 / 生成方式 | 谁在用（依赖倒置点） |
|---|---|---|---|
| `AiCodeGeneratorService` | `ai/AiCodeGeneratorService.java#L11` | **无手写实现**，由 `AiCodeGeneratorServiceFactory#L106/L125` 的 `AiServices.builder(...)` 生成 JDK 动态代理 | `core/AiCodeGeneratorFacade.java#L54/#L84` |
| `AiCodeGenTypeRoutingService` | `ai/AiCodeGenTypeRoutingService.java#L12` | `AiCodeGenTypeRoutingServiceFactory`（`AiServices.builder`，`#L25-L27`） | `service/impl/AppServiceImpl.java#L131-L132`、`langgraph4j/node/RouterNode.java#L27-L29` |
| `ImageCollectionPlanService` | `langgraph4j/ai/ImageCollectionPlanService.java#L10` | `ImageCollectionPlanServiceFactory#L19-L22` | `langgraph4j/node/ImageCollectorNode.java#L36`、`node/concurrent/ImagePlanNode.java#L25` |
| `CodeQualityCheckService` | `langgraph4j/ai/CodeQualityCheckService.java#L10` | `CodeQualityCheckServiceFactory#L24-L27` | `langgraph4j/node/CodeQualityCheckNode.java#L43` |
| `ImageCollectionService` | `langgraph4j/ai/ImageCollectionService.java#L10` | `ImageCollectionServiceFactory#L40-L49`（挂了 4 个工具） | **无调用方**（定义了但未接入节点） |
| `AppService` | `service/AppService.java#L19` | `service/impl/AppServiceImpl.java#L56` | `controller/AppController.java#L51` |
| `UserService` | `service/UserService.java` | `service/impl/UserServiceImpl.java#L34` | `AppController`、`UserController`、`ChatHistoryController`、`aop/AuthInterceptor.java#L24`、`ratelimter/aspect/RateLimitAspect.java#L38` |
| `ChatHistoryService` | `service/ChatHistoryService.java` | `service/impl/ChatHistoryServiceImpl.java#L37` | `AppServiceImpl#L68`、`core/handler/*StreamHandler` |
| `ScreenshotService` | `service/ScreenshotService.java#L6` | `service/impl/ScreenshotServiceImpl.java#L21` | `AppServiceImpl#L77` |
| `ProjectDownloadService` | `service/ProjectDownloadService.java#L5` | `service/ProjectDownloadServiceImpl.java#L21` | `AppController#L57` |
| `CodeParser<T>` | `core/parser/CodeParser.java#L8` | `HtmlCodeParser`、`MultiFileCodeParser` | `core/parser/CodeParserExecutor.java#L15-L17`（**静态字段持有，非 Spring Bean**） |
| `CodeFileSaverTemplate<T>`（抽象类） | `core/saver/CodeFileSaverTemplate.java#L18` | `HtmlCodeFileSaverTemplate`、`MultiFileCodeFileSaverTemplate` | `core/saver/CodeFileSaverExecutor.java#L19-L21`（同上） |
| `BaseTool`（抽象类） | `ai/tools/BaseTool.java#L9` | `FileWriteTool`、`FileReadTool`、`FileModifyTool`、`FileDeleteTool`、`FileDirReadTool`、`ExitTool` | `ToolManager#L28` 用 `@Resource BaseTool[] tools` **数组自动注入全部实现** |
| `ChatHistoryMapper` / `AppMapper` / `UserMapper` | `mapper/` | MyBatis-Flex 动态生成 | `ServiceImpl` 基类 + `@MapperScan` |

### 5.2 表 B：对第三方框架扩展点的实现

| 框架扩展点 | 实现类 | 路径 | 扩展目的 |
|---|---|---|---|
| `dev.langchain4j.model.chat.listener.ChatModelListener` | `AiModelMonitorListener` | `monitor/AiModelMonitorListener.java#L19` | 在 `onRequest` / `onResponse` / `onError` 三个回调里采集请求数、错误数、Token 消耗、响应耗时，写进 Micrometer。通过 `config/*ChatModelConfig` 的 `.listeners(List.of(aiModelMonitorListener))` 注册 |
| `dev.langchain4j.guardrail.InputGuardrail` | `PromptSafetyInputGuardrail` | `ai/guardrail/PromptSafetyInputGuardrail.java#L14` | 输入侧防注入：长度 >1000 拦截、空值拦截、9 个敏感词拦截、5 条正则注入模式拦截。已启用（`Factory#L117`、`#L129`） |
| `dev.langchain4j.guardrail.OutputGuardrail` | `RetryOutputGuardrail` | `ai/guardrail/RetryOutputGuardrail.java#L10` | 输出侧重试（空/过短/含敏感词则 reprompt）。**已实现但被注释掉，未启用**（`Factory#L118`、`#L130`） |
| `org.springframework.web.servlet.config.annotation.WebMvcConfigurer` | `CorsConfig` | `config/CorsConfig.java#L11` | 全局跨域；**注意用 `allowedOriginPatterns("*")` 而非 `allowedOrigins("*")`**，否则与 `allowCredentials(true)` 冲突（源码注释明确写了这点） |
| `org.springframework.context.ApplicationContextAware` | `SpringContextUtil` | `utils/SpringContextUtil.java#L13` | 让非 Spring 管理的对象（LangGraph 节点）能取 Bean |
| `@RestControllerAdvice`（Spring MVC 全局异常） | `GlobalExceptionHandler` | `exception/GlobalExceptionHandler.java#L21` | 统一异常出口；**特殊之处**：`handleSseError`（`#L53-L94`）判断 `Accept: text/event-stream` 或 URI 含 `/chat/gen/code`，若是 SSE 请求则直接往 `HttpServletResponse` 写 `event: business-error` + `event: done` 并返回 `null`，而不是返回 JSON |
| `com.mybatisflex.core.BaseMapper<T>` | `AppMapper` / `ChatHistoryMapper` / `UserMapper` | `mapper/*.java` | 仅声明接口，**XML 全空**（`resources/mapper/AppMapper.xml` 只有空 `<mapper>`），实际查询全部用 `QueryWrapper` 编程式构建 |
| `com.mybatisflex.core.service.IService<T>` | `AppService` / `UserService` / `ChatHistoryService` | `service/*.java` | 继承获得 `page` / `getById` / `save` / `updateById` / `remove` 等 CRUD |
| `org.aspectj.lang.annotation.Aspect` + `@Around("@annotation(authCheck)")` | `AuthInterceptor` | `aop/AuthInterceptor.java#L34-L58` | 角色校验：`mustRole` 为空放行；否则要求 `UserRoleEnum.ADMIN` 匹配 |
| `@Aspect` + `@Before("@annotation(rateLimit)")` | `RateLimitAspect` | `ratelimter/aspect/RateLimitAspect.java#L40-L52` | 限流 |
| `org.springframework.cache.annotation.EnableCaching` + `@Cacheable` | 启动类 + `AppController#L267-L271` | — | 精选应用分页缓存 |
| `org.bsc.langgraph4j.action.AsyncNodeAction` | 12 个 Node 类 | `langgraph4j/node/**` | 每个节点用 `node_async(state -> {...})` 包装 |
| `org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async` | `CodeGenWorkflow#L55` 等 | — | 条件边路由函数 |
| `org.bsc.langgraph4j.studio.springboot.AbstractLangGraphStudioConfig` | `LangGraphStudioSampleConfig` | `langgraph4j/config/LangGraphStudioSampleConfig.java#L13` | 暴露图给 LangGraph Studio |
| `@JsonComponent`（Spring Boot Jackson 扩展） | `JsonConfig` | `config/JsonConfig.java#L13-L14` | 注册 `Long → String` 序列化器，**解决前端 JS 大整数（雪花 ID）精度丢失** |

### 5.3 表 C：多实现 + 路由结构

**① 代码生成类型路由（4 个分发点，见 §3.7）**

```
CodeGenTypeEnum (HTML / MULTI_FILE / VUE_PROJECT)
        │
        ├─ AiCodeGeneratorServiceFactory  → 选模型 + 是否挂工具
        ├─ AiCodeGeneratorFacade          → Flux<String> vs TokenStream
        ├─ StreamHandlerExecutor          → SimpleTextStreamHandler vs JsonMessageStreamHandler
        └─ CodeParserExecutor / CodeFileSaverExecutor → 解析策略 + 落盘策略
```

**② 限流维度路由**（`ratelimter/enums/RateLimitType.java`：`API` / `USER` / `IP`）
路由实现 `RateLimitAspect.generateRateLimitKey#L69-L100`，**未登录/无请求上下文时 USER 自动降级为 IP**。

**③ 多模型路由（同一个 AI 能力配不同模型）**

| 配置前缀 | 模型 Bean | 指向 | 用途 |
|---|---|---|---|
| `langchain4j.open-ai.chat-model` | `openAiChatModel`（starter 提供，非本项目定义） | DeepSeek `deepseek-chat` | 非流式调用（图片规划、质检） |
| `langchain4j.open-ai.streaming-chat-model` | `streamingChatModelPrototype`（prototype） | DeepSeek `deepseek-chat` | HTML / MULTI_FILE 流式 |
| `langchain4j.open-ai.reasoning-streaming-chat-model` | `reasoningStreamingChatModelPrototype`（prototype） | DeepSeek `deepseek-reasoner`，temperature 0.1，max-tokens 32768 | VUE_PROJECT 流式 + 工具调用 |
| `langchain4j.open-ai.routing-chat-model` | `routingChatModelPrototype`（prototype） | 阿里云百炼 `qwen-turbo`，max-tokens 100 | 生成类型分类 |

**④ 工具集合路由**
`ai/tools/ToolManager.java`：`@Resource BaseTool[] tools`（`#L28`）自动收集所有 `BaseTool` 子类，`@PostConstruct initTools()`（`#L33-L40`）建 `Map<toolName, BaseTool>`。同一份工具集合：
- 通过 `.tools(toolManager.getAllTools())` 交给 LangChain4j（`Factory#L110`）；
- 通过 `toolManager.getTool(name)` 反查，用于把工具调用渲染成人类可读文本（`JsonMessageStreamHandler#L90`、`#L103`）。

---

## 6. 注解引用详解

### 6.1 项目自定义注解

| 注解 | 定义位置 | 用在哪 | 作用 | 项目里的特殊用法 |
|---|---|---|---|---|
| `@AuthCheck` | `annotation/AuthCheck.java#L10-L17` | `AppController#L297/#L317/#L342/#L363`、`UserController#L95/#L113/#L135/#L148/#L166`、`ChatHistoryController#L63` | 方法级角色校验 | 属性是 **`String mustRole()`**（不是枚举），默认空串。切面 `AuthInterceptor#L43-L45` 里 `mustRoleEnum == null` 即放行——**意味着写错角色字符串会静默放行** |
| `@RateLimit` | `ratelimter/annotation/RateLimit.java#L12-L38` | 仅 `AppController#L60` 一处 | 限流 | 5 个属性：`key`（默认空）、`rate`（默认 10）、`rateInterval`（默认 1 秒）、`limitType`（默认 USER）、`message` |

### 6.2 Spring / Spring Boot 注解

| 注解 | 位置 | 说明 |
|---|---|---|
| `@SpringBootApplication(exclude=...)` | `YuAiCodeMotherApplication#L10` | 排除 `RedisEmbeddingStoreAutoConfiguration` |
| `@EnableCaching` | 同上 `#L9` | 开启 Spring Cache |
| `@MapperScan` | 同上 `#L11` | 扫描 `com.yupi.yuaicodemother.mapper` |
| `@Configuration` | `config/` 8 个类 + `ai/AiCodeGeneratorServiceFactory` + `ai/AiCodeGenTypeRoutingServiceFactory` + `langgraph4j/ai/*Factory` + `ratelimter/config/RedissonConfig` + `langgraph4j/config/LangGraphStudioSampleConfig` | 注意：**`AiCodeGeneratorServiceFactory` 本身也是 `@Configuration`**，因为它同时提供 `@Bean` |
| `@ConfigurationProperties(prefix=...)` | 5 个类，见 §7.1 | 注意这些类同时带 `@Configuration`，**不是** `@Component` + `@EnableConfigurationProperties` 的写法 |
| `@Bean` | 各 Config 类 | 关键：`streamingChatModelPrototype`、`reasoningStreamingChatModelPrototype`、`routingChatModelPrototype`、`redisChatMemoryStore`、`cacheManager`、`cosClient`、`redissonClient`、`aiCodeGeneratorService`、`aiCodeGenTypeRoutingService` |
| `@Scope("prototype")` | `StreamingChatModelConfig#L44`、`ReasoningStreamingChatModelConfig#L41`、`RoutingAiModelConfig#L37` | **解决并发共享模型实例问题** |
| `@Component` | `manager/CosManager`、`utils/SpringContextUtil`、`monitor/*`、`ai/tools/*`、`core/handler/JsonMessageStreamHandler`、`core/handler/StreamHandlerExecutor`、`core/builder/VueProjectBuilder`、`ratelimter/aspect/RateLimitAspect`、`aop/AuthInterceptor` | — |
| `@Service` | `service/impl/*`、`core/AiCodeGeneratorFacade` | 注意 `ProjectDownloadServiceImpl` 位于 `service` 包（不在 `service/impl`） |
| `@RestController` / `@RequestMapping` | `controller/*` | `@RequestMapping` 前缀：`/app`、`/user`、`/chatHistory`、`/static`、`/workflow`、`/health`；全局前缀 `/api` 来自 `application.yml#L28` |
| `@GetMapping` / `@PostMapping` / `@PathVariable` / `@RequestParam` / `@RequestBody` | `controller/*` | — |
| `@RestControllerAdvice` / `@ExceptionHandler` | `exception/GlobalExceptionHandler#L21/#L25/#L36` | 处理 `BusinessException` 与 `RuntimeException` |
| `@Resource` | 全项目依赖注入统一用它（`jakarta.annotation.Resource`），**未使用 `@Autowired`** | 按名字注入，所以 `@Resource(name = "openAiChatModel")` 这种写法出现在 `AiCodeGeneratorServiceFactory#L33`、`ImageCollectionPlanServiceFactory#L15`、`CodeQualityCheckServiceFactory#L17` |
| `@Value` | `AppServiceImpl#L58`（`code.deploy-host`）、`ImageSearchTool#L28`（`pexels.api-key`）、`LogoGeneratorTool#L26/L29`（`dashscope.api-key` / `dashscope.image-model`）、`ratelimter/config/RedissonConfig#L14-L24`（4 个 redis 配置） | — |
| `@Lazy` | `ChatHistoryServiceImpl#L40` | 打断 `ChatHistoryService ↔ AppService` 的循环依赖 |
| `@Cacheable` | `AppController#L267-L271` | `value="good_app_page"`、`key` 用 SpEL 调 `CacheKeyUtils.generateKey(#appQueryRequest)`、`condition="#appQueryRequest.pageNum <= 10"` |
| `@JsonComponent` | `config/JsonConfig#L13` | Long→String |
| `@Aspect` / `@Around` / `@Before` | `aop/AuthInterceptor`、`ratelimter/aspect/RateLimitAspect` | — |
| `@PostConstruct` / `@PreDestroy` | `ToolManager#L33`（注册工具）、`WebScreenshotUtils#L42`（**失效，见 §3.5**） | — |

### 6.3 ORM（MyBatis-Flex）注解

| 注解 | 位置 | 说明 |
|---|---|---|
| `@Table("app"/"user"/"chat_history")` | `model/entity/*.java` | 表名 |
| `@Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)` | `App#L37`、`User#L36`、`ChatHistory#L36` | **主键用雪花 ID 生成**（不是 DB 自增；注意 `sql/create_table.sql` 里写的是 `bigint auto_increment`，实际由 MyBatis-Flex 生成 ID 后插入） |
| `@Column("xxx")` | 实体字段 | 驼峰字段映射到驼峰列名（DB 列名本身就是驼峰，如 `appName`） |
| `@Column(value="isDelete", isLogicDelete=true)` | `App#L107`、`User#L96`、`ChatHistory#L77` | **逻辑删除**，配合 `MyBatisCodeGenerator#L56` 的 `setLogicDeleteColumn("isDelete")` |

### 6.4 AI 框架（LangChain4j）注解

| 注解 | 位置 | 说明 |
|---|---|---|
| `@SystemMessage(fromResource = "prompt/xxx.txt")` | `ai/AiCodeGeneratorService#L19/#L28/#L37/#L46/#L55`、`ai/AiCodeGenTypeRoutingService#L20`、`langgraph4j/ai/*` | 系统提示词从 classpath 资源加载，共 7 个 prompt 文件 |
| `@UserMessage` | `AiCodeGeneratorService#L56`、`langgraph4j/ai/ImageCollectionPlanService#L16` 等 | 显式标记用户消息参数 |
| `@MemoryId` | `ai/AiCodeGeneratorService#L56`（`long appId`） | **用 appId 作为对话记忆 ID**，配合 `chatMemoryProvider(memoryId -> chatMemory)` |
| `@Tool("...")` | `ai/tools/*`（每个工具一个方法）、`langgraph4j/tools/*` | 声明为 LLM 可调用工具，字符串是给模型看的描述 |
| `@P("...")` | 同上，标注每个参数 | 参数说明 |
| `@ToolMemoryId` | `ai/tools/FileWriteTool#L32` 等 5 个文件工具 | **把 `@MemoryId` 的值（appId）注入工具方法**，用于定位 `vue_project_{appId}` 目录 |

### 6.5 Lombok / 校验 / 序列化 / JDK 注解

| 注解 | 说明 |
|---|---|
| `@Data` / `@Builder` / `@Getter` / `@NoArgsConstructor` / `@AllArgsConstructor` | 全项目广泛使用 |
| `@Slf4j` | 全项目统一日志（**未使用 `System.out`**，唯一例外是 `dev/langchain4j/model/openai/OpenAiStreamingResponseBuilder.java#L113` 的调试 `System.out.println("OLOLO ...")`，属于补丁类残留） |
| `@EqualsAndHashCode(callSuper = true)` | 流式消息子类（`AiResponseMessage`、`ToolRequestMessage` 等）继承 `StreamMessage` 时必须加 |
| `@Deprecated` | `core/CodeParser`、`core/CodeFileSaver`（旧的静态工具版，已被 `core/parser` + `core/saver` 取代，但代码仍保留） |
| `@Serial` | 所有 `Serializable` 类的 `serialVersionUID` 字段 |
| `@Hidden` | `GlobalExceptionHandler#L20`（`io.swagger.v3.oas.annotations.Hidden`），把异常处理器从接口文档里隐藏 |
| Jakarta Validation（`@NotNull` 等） | **未使用**。参数校验全部是手写 `ThrowUtils.throwIf(...)`（`exception/ThrowUtils.java`） |

---

## 7. 配置体系与 SPI 扩展点

### 7.1 配置类总表

| 配置类 | 前缀 / 来源 | 关键字段 | 绑定位置 |
|---|---|---|---|
| `config/StreamingChatModelConfig` | `langchain4j.open-ai.streaming-chat-model` | `baseUrl`、`apiKey`、`modelName`、`maxTokens`、`temperature`、`logRequests`、`logResponses` | `application.yml#L57-L63` |
| `config/ReasoningStreamingChatModelConfig` | `langchain4j.open-ai.reasoning-streaming-chat-model` | 同上（默认 `logRequests/logResponses = false`） | `application.yml#L64-L72` |
| `config/RoutingAiModelConfig` | `langchain4j.open-ai.routing-chat-model` | 同上 | `application.yml#L73-L80` |
| `config/RedisChatMemoryStoreConfig` | **`spring.data.redis`**（复用 Spring 自带前缀） | `host`、`port`、`password`、`ttl` | `application.yml#L15-L22`；产出 `RedisChatMemoryStore` Bean |
| `config/CosClientConfig` | `cos.client` | `host`、`secretId`、`secretKey`、`region`、`bucket` | `application.yml#L81-L88` |
| `config/RedisCacheManagerConfig` | 无（纯 `@Configuration`） | 默认 TTL 30 分钟、`disableCachingNullValues`、key 用 `StringRedisSerializer`；`good_app_page` 单独 5 分钟 | `config/RedisCacheManagerConfig.java#L34-L49` |
| `config/JsonConfig` | 无（`@JsonComponent`） | `Long` / `long` → `ToStringSerializer` | `config/JsonConfig.java#L19-L27` |
| `config/CorsConfig` | 无（`WebMvcConfigurer`） | `/**`、`allowCredentials(true)`、`allowedOriginPatterns("*")`、GET/POST/PUT/DELETE/OPTIONS | `config/CorsConfig.java#L14-L23` |
| `ratelimter/config/RedissonConfig` | 无（`@Value` 注入 4 项） | 单机模式、连接池 10、`connectTimeout 5000`、`timeout 3000`、`retryAttempts 3`、`retryInterval 1500` | `ratelimter/config/RedissonConfig.java#L27-L45` |
| `langgraph4j/config/LangGraphStudioSampleConfig` | 无 | 暴露 `CodeGenWorkflow` 图 | `langgraph4j/config/...java#L17-L24` |

**关键非默认配置项（必须注意）：**

| 配置项 | 值 | 位置 | 影响 |
|---|---|---|---|
| `server.servlet.context-path` | `/api` | `application.yml#L28` | **所有接口都带 `/api` 前缀** |
| `server.port` | `8123` | `#L26` | — |
| `spring.session.store-type` | `redis` | `#L6` | Session 落 Redis |
| `spring.session.timeout` | `2592000`（30 天） | `#L8` | — |
| `server.servlet.session.cookie.max-age` | `2592000` | `#L30-L32` | Cookie 30 天 |
| `spring.data.redis.ttl` | `3600` | `#L22` | **这是自定义项**，被 `RedisChatMemoryStoreConfig#L24` 消费，控制对话记忆在 Redis 的过期时间 |
| `spring.profiles.active` | `local` | `#L24` | **但仓库里没有 `application-local.yml`**，该 profile 实际不加载任何额外文件 |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | `#L100-L101` | 决定 `prometheus.yml` 抓取 `/api/actuator/prometheus` 能成功 |
| `springdoc.group-configs.packages-to-scan` | `com.yupi.yuaicodemother.controller` | `#L34-L38` | 接口文档只扫 controller 包 |
| `mybatis-flex.configuration.log-impl` | prod 下设为 `''` | `application-prod-sample.yml#L69-L71` | 生产关闭 SQL 日志 |
| `knife4j.basic.enable` | prod 下 `true` + `username/password` | `application-prod-sample.yml#L73-L77` | 生产给接口文档加 Basic Auth |
| `code.deploy-host` | prod 下 `http://<IP>/dist` | `application-prod-sample.yml#L79-L80` | 部署访问域名，对应 `AppServiceImpl#L58` 的 `@Value("${code.deploy-host:http://localhost}")` |

### 7.2 带开关语义的配置

| 开关 | 关闭后行为 | 位置 |
|---|---|---|
| `@SpringBootApplication(exclude = RedisEmbeddingStoreAutoConfiguration.class)` | **这是硬编码开关**，无法通过配置打开。若移除，需要额外提供 Redis 向量库配置，否则启动失败 | `YuAiCodeMotherApplication.java#L10` |
| `langchain4j.open-ai.*.log-requests` / `log-responses` | 关闭后不打印 LLM 请求/响应全文。local 为 `true`，prod sample 为 `false` | `application.yml#L54-L55` vs `application-prod-sample.yml#L27-L28` |
| `management.endpoints.web.exposure.include` | 若去掉 `prometheus`，`/api/actuator/prometheus` 返回 404，Prometheus 抓取失败 | `application.yml#L100-L101` |
| `knife4j.enable` | `false` 时接口文档不可访问 | `application.yml#L41` |
| `mybatis-flex.configuration.log-impl` | 设为 `''` 时不输出 SQL | `application-prod-sample.yml#L71` |
| **代码级开关（非配置）** | | |
| `Factory#L118` / `#L130` 注释掉 `.outputGuardrails(...)` | 输出护轨失效，`RetryOutputGuardrail` 不生效（原因：流式输出） | `ai/AiCodeGeneratorServiceFactory.java` |
| `Factory#L51` `//` `strict-json-schema` / `response-format` | 在 `application.yml#L51-L52` 被注释，结构化输出不走严格 JSON Schema 模式 | `application.yml` |

### 7.3 SPI 注册内容

**结论：项目没有自己的 `META-INF/spring.factories`，也没有 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。** `src/main/resources` 下只有 `application*.yml`、`mapper/*.xml`、`prompt/*.txt`、`static/*.html`。

项目与「自动装配」相关的唯一动作是**排除**第三方自动配置：

```java
@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
```

即：`langchain4j-community-redis-spring-boot-starter` 通过自身 jar 里的 SPI 文件注册了 `RedisEmbeddingStoreAutoConfiguration`，本项目把它排除，只保留该 starter 里的 `RedisChatMemoryStore` 类（手动 `@Bean` 装配，见 `RedisChatMemoryStoreConfig#L26-L37`）。

---

## 8. 设计模式与工程亮点总结

### 8.1 设计模式清单

| 模式 | 落地点 | 解决什么问题 |
|---|---|---|
| **模板方法** | `core/saver/CodeFileSaverTemplate.java#L32`（`public final File saveCode(...)`）→ 子类只实现 `saveFiles` + `getCodeType` + 可选覆盖 `validateInput` | 固定「校验 → 建目录 → 写文件 → 返回 File」骨架，HTML 与多文件的差异被隔离到子类 |
| **策略 + 执行器（简单工厂）** | `CodeParser<T>` + `CodeParserExecutor`；`CodeFileSaverTemplate<T>` + `CodeFileSaverExecutor`；`StreamHandlerExecutor` | 用 `switch` 把「枚举 → 具体实现」的映射集中到一处；**注意 Executor 用 `static final` 字段持有实现，不参与 Spring 容器** |
| **工厂 + 动态代理** | `AiCodeGeneratorServiceFactory`、`AiCodeGenTypeRoutingServiceFactory`、`langgraph4j/ai/*Factory` | LangChain4j 的 AI Service 是接口，必须由 `AiServices.builder(...).build()` 生成代理实现 |
| **门面（Facade）** | `core/AiCodeGeneratorFacade.java` | 把「取服务实例 → 按类型生成 → 解析 → 落盘 → 转流」5 件事收敛成一个方法给 `AppServiceImpl` 用 |
| **建造者** | `AiServices.builder(...)`、`MessageWindowChatMemory.builder()`、`WorkflowContext.builder()`、`ImageResource.builder()`、`QualityResult.builder()` | 复杂对象构造 |
| **图/状态机编排** | `langgraph4j/*Workflow` 的 `MessagesStateGraph` + 节点 + 条件边 | 把「串行 + 并发 + 回退重试」的 AI 流程显式建模成图，而不是层层 if/else |
| **观察者** | `monitor/AiModelMonitorListener implements ChatModelListener` | 在不侵入业务代码的前提下采集模型指标 |
| **代理 / AOP** | `aop/AuthInterceptor`（`@Around`）、`ratelimter/aspect/RateLimitAspect`（`@Before`） | 权限与限流横切 |
| **注册表 / 享元** | `ai/tools/ToolManager` 的 `Map<String, BaseTool>` | 工具名 ↔ 实例，既给 LLM 调用，也给「工具调用结果文本化」用 |
| **原型** | `@Scope("prototype")` 的三个 ChatModel Bean | 避免并发请求共享同一个模型实例 |
| **ThreadLocal 上下文** | `monitor/MonitorContextHolder` | 跨方法透传 userId/appId，且不污染方法签名 |
| **适配器** | `ScreenshotServiceImpl`（封装 Selenium + 压缩 + COS）、`CosManager`（封装 COS SDK） | 把外部 SDK 的复杂性关在服务内部 |
| **装饰器/回调链** | `Flux.doOnNext/doOnComplete/doOnError/doFinally`、`TokenStream.onXxx().start()` | 在不改变返回值类型的前提下附加副作用（收集代码、写历史、清上下文） |

### 8.2 工程实践要点（10 条）

**1）AI Service 实例按 `appId + 生成类型` 缓存，且缓存键包含类型**
`AiCodeGeneratorServiceFactory#L52-L59` + `#L155-L157`。Caffeine 三重策略（最多 1000 个 / 写后 30 分钟 / 访问后 10 分钟）。**为什么值得学**：AI Service 实例持有 `MessageWindowChatMemory`，是「每个应用一份对话上下文」的载体；缓存键漏掉 `codeGenType` 会导致同一应用换类型时记忆串用。

**2）用 `@Scope("prototype")` + `SpringContextUtil.getBean(name, class)` 解决并发**
`config/StreamingChatModelConfig#L43-L44`。源码注释直白写着「使用多例模式的 StreamingChatModel 解决并发问题」。**代价**：每次创建实例，且绕过了构造注入，属于刻意的权衡。

**3）双通道对话记忆：Redis 持久化 + MySQL 回灌**
`RedisChatMemoryStore`（`RedisChatMemoryStoreConfig`）+ `ChatHistoryServiceImpl.loadChatHistoryToMemory`（`#L94-L125`）。回灌时先 `chatMemory.clear()` 防重复，按 `createTime` 倒序取最近 20 条再 `reversed()` 成正序。**整个方法包在 try/catch 里，失败只记日志返回 0**——源码注释：「加载失败不影响系统运行，只是没有历史上下文」。

**4）工具调用事件的流式透传 + 幂等去重**
`JsonMessageStreamHandler#L69-L115` 用 `Set<String> seenToolIds` 保证同一 `toolId` 的 `[选择工具] xxx` 只输出一次。**为什么需要**：流式工具调用会分片推送同一个 toolId 的多个片段，不去重前端会重复渲染。

**5）工具调用的三重防幻觉**
- `hallucinatedToolNameStrategy`：编造工具名 → 回一条 Error 工具结果让模型自纠（`Factory#L112-L115`）；
- `maxSequentialToolsInvocations(20)`：硬上限（`Factory#L116`）；
- `ExitTool`：给模型一个「主动停止调工具」的出口（`ai/tools/ExitTool.java#L31-L35`）。

**6）监控上下文用「ThreadLocal 存 + attributes 传」双保险**
`AiModelMonitorListener#L30-L42` 在 `onRequest` 里既读 `MonitorContextHolder`，又把 `MonitorContext` 塞进 `requestContext.attributes()`；`onResponse` 时**从 attributes 取**（`#L49`）而不是从 ThreadLocal 取——源码注释写明原因：「请求和响应事件的触发不是同一个线程」。清理放在 `AppServiceImpl#L113-L116` 的 `doFinally`。

**7）分布式限流的维度路由与降级**
`RateLimitAspect#L69-L100`：USER 维度取不到登录态时**不报错，而是降级为 IP 限流**。这是一个务实的设计——限流失败不应该把请求变成 500。

**8）SSE 与普通 JSON 响应的统一异常出口**
`GlobalExceptionHandler#L53-L94`：靠 `Accept` 头 + URI 双重判定，是 SSE 请求就手写 `text/event-stream` 响应体并 `flush()`，否则返回标准 `BaseResponse`。**这是 MVC 下同时支持 SSE 和 JSON 的典型处理方式**。

**9）缓存键与缓存条件精细化**
`AppController#L267-L271`：
```java
@Cacheable(value = "good_app_page",
           key = "T(com.yupi.yuaicodemother.utils.CacheKeyUtils).generateKey(#appQueryRequest)",
           condition = "#appQueryRequest.pageNum <= 10")
```
- key 用 SpEL 调静态方法做 JSON→MD5（`utils/CacheKeyUtils.java#L19-L26`），避免参数对象当 key 时的 `hashCode` 不稳定问题；
- `condition` 只缓存前 10 页——**深分页不缓存**，控制内存占用；
- TTL 在 `RedisCacheManagerConfig#L47-L48` 单独给 `good_app_page` 配 5 分钟（比默认 30 分钟短，因为精选列表变化相对频繁）。
- 注意 `#L40-L42` 的 `serializeValuesWith(JSON)` **被注释掉了**，源码注释解释：开启后需要额外配置默认类型信息，否则无法反序列化。当前 value 用的是 Redis 默认 JDK 序列化。

**10）外部命令与外部服务的健壮性处理**
- `VueProjectBuilder#L120-L147`：`npm install` 超时 300s、`npm run build` 超时 180s，超时 `process.destroyForcibly()`；按 OS 选 `npm.cmd`（`#L96-L101`）；每步失败都返回 `false` 并记日志，不抛异常。
- `WebScreenshotUtils#L167-L182`：`waitForPageLoad` 里 `WebDriverWait` 之后**再 `Thread.sleep(2000)`**，等动态内容；整个方法 catch 住异常继续截图。
- `ScreenshotServiceImpl#L35-L43`：上传成功后 `finally` 删本地临时文件。
- `MermaidDiagramTool#L47-L48`：上传 COS 后删临时 SVG。
- `LogoGeneratorTool#L60-L62` / `ImageSearchTool#L55-L57` / `UndrawIllustrationTool#L61-L63`：所有外部调用异常都只记日志，返回空列表——**图片收集失败不阻断主流程**。

**附：其他值得注意的实现细节**

| 细节 | 位置 | 说明 |
|---|---|---|
| 批量查询避免 N+1 | `AppServiceImpl#L239-L255` | `getAppVOList` 先收集 `userIds` 一次查完再 `toMap`，源码注释明确写「避免 N+1 查询问题」 |
| 游标分页 | `ChatHistoryServiceImpl#L154-L156` + `sql/create_table.sql#L66` | `lt("createTime", lastCreateTime)` 配 `idx_appId_createTime` 复合索引 |
| 删除应用时级联删历史 | `AppServiceImpl#L290-L307` | 先删 `chat_history`，**失败只记日志不阻断**，再删 `app` |
| 文件工具的路径沙箱 | `ai/tools/FileWriteTool#L35-L41` 等 | 相对路径统一 resolve 到 `tmp/code_output/vue_project_{appId}`，**appId 来自 `@ToolMemoryId`** |
| 文件删除白名单 | `ai/tools/FileDeleteTool#L61-L74` | 禁止删除 `package.json`、`vite.config.ts`、`index.html`、`main.ts`、`App.vue` 等 16 个关键文件 |
| 目录读取过滤 | `ai/tools/FileDirReadTool#L30-L40` | 忽略 `node_modules`、`.git`、`dist`、`target`、`.env` 等；忽略 `.log/.tmp/.cache/.lock` 扩展名 |
| 打包下载过滤 | `service/ProjectDownloadServiceImpl#L26-L46` | ZIP 时按路径每一段过滤，避免把 `node_modules` 打进包 |
| 静态资源预览 | `controller/StaticResourceController#L32-L65` | `GET /api/static/{deployKey}/**`，目录无斜杠时 301 重定向，`/` 映射到 `index.html`，按扩展名返回带 UTF-8 的 Content-Type |
| 健康检查 | `controller/HealthController#L13-L15` | `GET /api/health/` → `"ok"`（与 actuator 的 `/actuator/health` 是两套） |

---

## 9. 附：阅读顺序建议

给中级 Java 开发的一条从零到能改代码的路线，共 6 站：

**第 0 站：建立全局地图（15 分钟）**
1. `pom.xml` —— 先看依赖清单，明确「有 LangChain4j、没有 Spring AI；有 MyBatis-Flex、没有 MyBatis-Plus；有 Redisson、没有 Spring Security」。
2. `src/main/resources/application.yml` —— 记住三件事：`context-path=/api`、`port=8123`、有 4 组 AI 模型配置。
3. `sql/create_table.sql` —— 3 张表，`app` 表是核心。
4. `YuAiCodeMotherApplication.java` —— 记住那个 `exclude`。

**第 1 站：读主链路 A（最重要，1~2 小时）**
按顺序读，每一环都确认「输入是什么、输出是什么」：

```
controller/AppController.java#L59-L86
  → service/impl/AppServiceImpl.java#L83-L117
    → core/AiCodeGeneratorFacade.java   （全文）
      → ai/AiCodeGeneratorServiceFactory.java#L78-L136   ← 全项目最难的一段
        → ai/AiCodeGeneratorService.java  （接口，5 个方法）
    → core/handler/StreamHandlerExecutor.java → JsonMessageStreamHandler.java
```
读完后应能回答：`appId` 是怎么变成对话记忆的？工具调用信息怎么到前端的？

**第 2 站：读「LLM 输出 → 磁盘文件」（40 分钟）**
```
core/parser/CodeParser.java（接口）→ HtmlCodeParser / MultiFileCodeParser → CodeParserExecutor
core/saver/CodeFileSaverTemplate.java（模板方法骨架）→ 两个子类 → CodeFileSaverExecutor
core/builder/VueProjectBuilder.java
```
读完应能回答：Vue 类型为什么**不**走 parser/saver？

**第 3 站：读工具集与部署（40 分钟）**
```
ai/tools/BaseTool.java → ToolManager.java → 6 个工具实现
service/impl/AppServiceImpl.java#L142-L219（部署 + 异步截图）
  → service/impl/ScreenshotServiceImpl.java → utils/WebScreenshotUtils.java → manager/CosManager.java
controller/StaticResourceController.java
```

**第 4 站：读横切与基础设施（40 分钟）**
```
annotation/AuthCheck.java + aop/AuthInterceptor.java
ratelimter/ 整包
monitor/ 整包
config/ 整包
exception/GlobalExceptionHandler.java
```

**第 5 站：读 LangGraph4j 工作流（1 小时，可最后读）**
```
controller/WorkflowSseController.java
  → langgraph4j/CodeGenWorkflow.java（先读这一个，图最简单）
    → langgraph4j/state/WorkflowContext.java
    → langgraph4j/node/ 下的 6 个主节点
  → CodeGenConcurrentWorkflow.java（并发）
  → CodeGenSubgraphWorkflow.java（子图）
  → langgraph4j/tools/ 4 个图片工具
```
**先明确它没接入主业务链路**，再读，否则会一直找「谁调用了它」。

**第 6 站：读微服务工程（1 小时，可选）**
```
yu-ai-code-mother-microservice/pom.xml（7 个模块 + 4 个 BOM）
  → yu-ai-code-client/.../innerservice/（接口定义）
  → yu-ai-code-user/.../InnerUserServiceImpl.java（@DubboService）
  → yu-ai-code-app/.../AppServiceImpl.java（@DubboReference）
  → 各模块 application.yml 的 dubbo 段
```

**改代码前的 3 个必查清单：**

| 要改的东西 | 必须同步改的位置 |
|---|---|
| 新增一种代码生成类型 | `CodeGenTypeEnum` + §3.7 的 4 个 switch + parser/saver 实现 + prompt 文件 |
| 新增一个 LLM 工具 | 继承 `BaseTool` + `@Component`（`ToolManager` 自动收集）+ 实现 `getToolName/getDisplayName/generateToolExecutedResult`；若给 VUE_PROJECT 用，确认 `AiCodeGeneratorServiceFactory#L110` 的 `.tools(...)` 已覆盖 |
| 新增一个 AI 能力（新模型） | 新建 `XxxConfig`（`@ConfigurationProperties` + `@Configuration` + `@Bean @Scope("prototype")`）+ 在 `application.yml` 加配置段 + 工厂里用 `SpringContextUtil.getBean("xxxPrototype", ...)` 取 |

---

## 10. 附：逐环节深挖计划

按要求，架构文档阶段到此结束。业务环节数量超过 5 个，深挖阶段采用**两阶段方式**，落盘文件规划为 `docs/yu-ai-code-mother-learning-notes.md`（该文件在深挖阶段创建，顶部维护环节目录表、每完成一个环节立即写入）。

**环节目录（拟定，编号连续）：**

| 环节 | 名称 | 覆盖的核心代码 |
|---|---|---|
| 1 | 应用创建与生成类型智能路由 | `AppServiceImpl#createApp`、`AiCodeGenTypeRoutingService`(+Factory)、`CodeGenTypeEnum`、`prompt/codegen-routing-system-prompt.txt` |
| 2 | 对话式代码生成（SSE 主链路） | `AppController#chatToGenCode`、`AppServiceImpl#chatToGenCode`、`AiCodeGeneratorFacade#generateAndSaveCodeStream`、`StreamHandlerExecutor`、`SimpleTextStreamHandler` |
| 3 | 流式工具调用与文件读写工具集 | `AiCodeGeneratorFacade#processTokenStream`、`dev/langchain4j` 补丁类、`ai/tools/*`（6 个工具）、`ToolManager`、`JsonMessageStreamHandler`、`ai/model/message/*` |
| 4 | 对话记忆与多租户 AI 服务实例管理 | `AiCodeGeneratorServiceFactory`、`AiCodeGeneratorService`、`ChatHistoryServiceImpl`、`RedisChatMemoryStoreConfig`、`AiCodeGenTypeRoutingServiceFactory` |
| 5 | 代码解析与文件落盘 | `core/parser/*`、`core/saver/*`、`CodeParserExecutor`、`CodeFileSaverExecutor`、`CodeFileSaverTemplate`、`AppConstant` |
| 6 | Vue 工程构建与一键部署 | `core/builder/VueProjectBuilder`、`AppServiceImpl#deployApp`、`CodeDeployResult`、`AppConstant#CODE_DEPLOY_ROOT_DIR` |
| 7 | 截图与对象存储 | `ScreenshotServiceImpl`、`utils/WebScreenshotUtils`、`manager/CosManager`、`CosClientConfig`、`AppServiceImpl#generateAppScreenshotAsync` |
| 8 | AI 可观测性 | `monitor/AiModelMonitorListener`、`monitor/AiModelMetricsCollector`、`monitor/MonitorContext(Holder)`、`application.yml#management`、`prometheus.yml`、`grafana/ai_model_grafana_config.json` |
| 9 | 分布式限流与权限校验 | `ratelimter/annotation/RateLimit`、`ratelimter/aspect/RateLimitAspect`、`ratelimter/config/RedissonConfig`、`annotation/AuthCheck`、`aop/AuthInterceptor`、`UserServiceImpl#getLoginUser` |
| 10 | LangGraph4j 工作流编排 | `langgraph4j/CodeGenWorkflow`、`CodeGenConcurrentWorkflow`、`CodeGenSubgraphWorkflow`、`state/WorkflowContext`、`node/*`、`tools/*`、`controller/WorkflowSseController` |
| 11 | 缓存体系与对话历史查询优化 | `RedisCacheManagerConfig`、`AppController#listGoodAppVOByPage`、`utils/CacheKeyUtils`、`ChatHistoryServiceImpl#getQueryWrapper`、`sql/create_table.sql#idx_appId_createTime` |
| 12 | 微服务拆分与 Dubbo 服务化 | `yu-ai-code-mother-microservice/pom.xml`、`yu-ai-code-client/.../innerservice/*`、`InnerUserServiceImpl`、`InnerScreenshotServiceImpl`、`yu-ai-code-app/.../AppServiceImpl` |

每个环节统一按 N.1 解决什么问题 → N.2/N.3 技术点 → N.4 代码走读 → N.5 动手验证 → N.6 自测题（8~15 题，含陷阱题）→ N.7 技术点清单 → N.8 本环节产出 的固定结构展开，子节编号独立计数、连续不跳号，文内代码引用统一使用相对路径。

**阅读本文档的建议姿势：** 第 1~2 章建立全局认知，第 3 章按主链路顺序读一遍（不要跳读，链路是串起来的），第 5 章当成字典查（写代码时回查「这个接口有谁实现」），第 8 章是面试/复盘时最值得反复看的部分，第 9 章是真正开始读源码时的路线图。

**全文结束。**