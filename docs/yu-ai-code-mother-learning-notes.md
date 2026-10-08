# yu-ai-code-mother 逐环节深挖笔记

> 配套文档：[yu-ai-code-mother-architecture-analysis.md](./yu-ai-code-mother-architecture-analysis.md)（全局架构，先读那份建立认知）
> 本文件为**阶段二**产物，按业务环节逐个深挖。所有路径均为相对仓库根目录，不使用绝对路径。
> 前端目录 `yu-ai-code-mother-frontend/` 按要求忽略。

## 环节目录

| 环节 | 名称 | 状态 | 覆盖的核心代码 |
|---|---|---|---|
| [1](#环节-1应用创建与生成类型智能路由) | 应用创建与生成类型智能路由 | ✅ 已完成 | `AppServiceImpl#createApp`、`AiCodeGenTypeRoutingService`(+Factory)、`CodeGenTypeEnum`、`prompt/codegen-routing-system-prompt.txt` |
| [2](#环节-2对话式代码生成sse-主链路) | 对话式代码生成（SSE 主链路） | ✅ 已完成 | `AppController#chatToGenCode`、`AppServiceImpl#chatToGenCode`、`AiCodeGeneratorFacade`、`StreamHandlerExecutor`、`SimpleTextStreamHandler`、`GlobalExceptionHandler#handleSseError` |
| [3](#环节-3流式工具调用与文件读写工具集) | 流式工具调用与文件读写工具集 | ✅ 已完成 | `dev/langchain4j` 补丁类、`ai/tools/*`、`ToolManager`、`JsonMessageStreamHandler`、`ReasoningStreamingChatModelConfig` |
| [4](#环节-4对话记忆与多租户-ai-服务实例管理) | 对话记忆与多租户 AI 服务实例管理 | ✅ 已完成 | `AiCodeGeneratorServiceFactory`、`ChatHistoryServiceImpl`、`RedisChatMemoryStoreConfig`、`SpringContextUtil` |
| [5](#环节-5代码解析与文件落盘) | 代码解析与文件落盘 | ✅ 已完成 | `core/parser/*`、`core/saver/*`、两个 Executor、`CodeFileSaverTemplate`、`AiCodeGeneratorFacade#processCodeStream`、`StaticResourceController` |
| [6](#环节-6vue-工程构建与一键部署) | Vue 工程构建与一键部署 | ✅ 已完成 | `core/builder/VueProjectBuilder`、`AppServiceImpl#deployApp`、`ProjectDownloadServiceImpl`、`codegen-vue-project-system-prompt.txt` |
| [7](#环节-7截图与对象存储) | 截图与对象存储 | ✅ 已完成 | `ScreenshotServiceImpl`、`utils/WebScreenshotUtils`、`manager/CosManager`、`config/CosClientConfig`、`AppServiceImpl#generateAppScreenshotAsync` |
| [8](#环节-8ai-可观测性) | AI 可观测性 | ✅ 已完成 | `monitor/*`、`StreamingChatModelConfig`、`ReasoningStreamingChatModelConfig`、`RoutingAiModelConfig`、`AppServiceImpl#chatToGenCode`、`application.yml#management`、`prometheus.yml`、`grafana/ai_model_grafana_config.json` |
| [9](#环节-9分布式限流与权限校验) | 分布式限流与权限校验 | ✅ 已完成 | `ratelimter/*`（注解/枚举/切面/`RedissonConfig`）、`annotation/AuthCheck`、`aop/AuthInterceptor`、`UserServiceImpl#getLoginUser`、`GlobalExceptionHandler`、`application.yml#session` |
| [10](#环节-10langgraph4j-工作流编排) | LangGraph4j 工作流编排 | ✅ 已完成 | `langgraph4j/CodeGenWorkflow`、`CodeGenConcurrentWorkflow`、`CodeGenSubgraphWorkflow`、`state/WorkflowContext`、`node/*`（6 个主节点）、`node/concurrent/*`、`tools/*`（4 个图片工具）、`ai/*`、`controller/WorkflowSseController` |
| [11](#环节-11缓存体系与对话历史查询优化) | 缓存体系与对话历史查询优化 | ✅ 已完成 | `config/RedisCacheManagerConfig`、`utils/CacheKeyUtils`、`AppController#listGoodAppVOByPage`、`ChatHistoryServiceImpl#getQueryWrapper`/`loadChatHistoryToMemory`、`ChatHistoryController`、`sql/create_table.sql` |
| [12](#环节-12微服务拆分与-dubbo-服务化) | 微服务拆分与 Dubbo 服务化 | ✅ 已完成 | `yu-ai-code-mother-microservice/pom.xml`、`yu-ai-code-client/*`、`InnerUserService`/`InnerScreenshotService`、`InnerUserServiceImpl`/`InnerScreenshotServiceImpl`、微服务版 `AppServiceImpl`、三个 `application.yml`、三个 `@EnableDubbo` 启动类 |

---

## 环节 1：应用创建与生成类型智能路由

### 1.1 这个环节解决什么问题

用户进入平台后做的第一件事是「创建一个应用」，提交的只有一个字段——**一句自然语言需求**（`initPrompt`）。平台必须在这句话里判断出：这个需求该用哪种代码生成方案来承接。

项目定义了三种方案，对应三套完全不同的后续流程（见架构文档 §3.7）：

| 方案 | 产物形态 | 后续流程差异 |
|---|---|---|
| `HTML` | 单个 HTML 文件（内联 CSS/JS） | 无工具调用，纯文本流 |
| `MULTI_FILE` | 多个文件（HTML/CSS/JS 分离） | 无工具调用，按标记切分文本 |
| `VUE_PROJECT` | 完整 Vue 工程 | 走 `reasoning` 模型 + 工具调用 + npm 构建 |

选错类型的代价不是「多写几行代码」，而是**整条链路走岔**——比如把一个「后台管理系统」判成 HTML，用户拿到的就是一个不可能维护的单文件。所以这个环节的本质是：**用一个极轻量的 LLM 调用，把不可逆的架构决策前置到创建应用这一刻**。

关键在于，项目**没有**让用户在下拉框里手选类型（`AppAddRequest` 只有一个字段，见 `model/dto/app/AppAddRequest.java#L16`），而是让模型来判。这是一次有意识的取舍：用一次额外 LLM 调用的延迟，换掉用户的理解成本。

### 1.2 技术点一：AiServices 动态代理 + 枚举结构化输出

**是什么。** `ai/AiCodeGenTypeRoutingService.java` 是一个**没有实现类的接口**：

```java
public interface AiCodeGenTypeRoutingService {

    @SystemMessage(fromResource = "prompt/codegen-routing-system-prompt.txt")
    CodeGenTypeEnum routeCodeGenType(String userPrompt);
}
```

方法返回的是**枚举类型**，不是 `String`。这个接口永远不会有 `implements`，它的实现体由 LangChain4j 在运行时用 `AiServices.builder()` 生成的 JDK 动态代理提供。

**为什么这里用它。** 三个理由，都能在代码里找到对应：

1. **返回值直接是枚举，省掉手工解析。** 对比一下传统写法：让模型返回 JSON 字符串 → `JSON.parse` → 取字段 → `getEnumByValue` → 判空 → 转枚举。这里全部由框架的「结构化输出」能力吃掉，调用方拿到手就是 `CodeGenTypeEnum`，可以放心 `switch`。
2. **提示词外置成资源文件。** `@SystemMessage(fromResource = ...)` 把提示词放在 `src/main/resources/prompt/codegen-routing-system-prompt.txt`，改提示词不用改 Java、不用重新编译逻辑代码，也不用在类里拼超长字符串。
3. **单参数无需 `@UserMessage`。** 注意这个方法**没有** `@UserMessage` 注解（对比 `ai/AiCodeGeneratorService.java#L56` 是显式标注的）。单参数场景下框架会把唯一的 `String` 参数当作用户消息，所以这里省略了注解——**这是项目内两种写法并存的地方，不要以为漏写了**。

提示词全文只有 11 行，是「给模型看的规格说明」而非「给模型看的代码」：

```
你是一个专业的代码生成方案路由器，需要根据用户需求返回最合适的代码生成类型。

可选的代码生成类型：
1. HTML - 适合简单的静态页面，单个 HTML 文件，包含内联 CSS 和 JS
2. MULTI_FILE - 适合简单的多文件静态页面，分离 HTML、CSS、JS 代码
3. VUE_PROJECT - 适合复杂的现代化前端项目

判断规则：
- 如果用户需求简单，只需要一个展示页面，选择 HTML
- 如果用户需要多个页面但不涉及复杂交互，选择 MULTI_FILE
- 如果用户需求复杂，涉及多页面、复杂交互、数据管理等，选择 VUE_PROJECT
```

> **⚠️ 本项目特有的坑（必看）：提示词里的名字和数据库里的值不是一回事。**
>
> 提示词里写的是 **`HTML` / `MULTI_FILE` / `VUE_PROJECT`**，这是枚举的**常量名**；而入库时调用的是 `selectedCodeGenType.getValue()`，存进去的是 **`html` / `multi_file` / `vue_project`**（见 `model/enums/CodeGenTypeEnum.java#L12-L14`）。两套名字长度不同、大小写不同。
>
> 后续所有反查都必须用 `CodeGenTypeEnum.getEnumByValue(codeGenType)`（只认 `value`，见 `#L30-L40`），不能用 `valueOf()`。**改枚举时这两处必须同步改**，否则模型返回的值映射不上，或者历史数据反查为 `null`。

### 1.3 技术点二：prototype 模型 Bean + 工厂方法绕过单例

**是什么。** 路由用的模型由 `config/RoutingAiModelConfig.java` 提供，注意它是一个 **`@Bean` + `@Scope("prototype")`** 的工厂方法：

```java
@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.routing-chat-model")
@Data
public class RoutingAiModelConfig {

    @Bean
    @Scope("prototype")
    public ChatModel routingChatModelPrototype() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey).modelName(modelName).baseUrl(baseUrl)
                .maxTokens(maxTokens).temperature(temperature)
                .logRequests(logRequests).logResponses(logResponses)
                .build();
    }
}
```

而取用它的 `ai/AiCodeGenTypeRoutingServiceFactory.java` 有两个入口，**它们返回的不是同一个对象**：

```java
// 入口 A：每次调用都新建（AppServiceImpl#createApp 走的是这条）
public AiCodeGenTypeRoutingService createAiCodeGenTypeRoutingService() {
    ChatModel chatModel = SpringContextUtil.getBean("routingChatModelPrototype", ChatModel.class);
    return AiServices.builder(AiCodeGenTypeRoutingService.class)
            .chatModel(chatModel)
            .build();
}

// 入口 B：注册一个单例 Bean（langgraph4j 的 RouterNode 走的是这条）
@Bean
public AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService() {
    return createAiCodeGenTypeRoutingService();
}
```

**为什么这里用它。**

- **`@Scope("prototype")` 是刻意的。** 如果用默认单例，全进程只有一份 `OpenAiChatModel`。而 LangChain4j 的模型对象内部持有 HTTP 客户端和配置，项目里**所有**模型 Bean（`streamingChatModelPrototype`、`reasoningStreamingChatModelPrototype`）统一都用 prototype，形成一致约定：**需要模型的地方，按名字取一个原型 Bean，而不是注入一个共享实例**。这与「AI Service 实例要按应用隔离」的整体设计是配套的（见环节 4）。
- **`@Scope` 只在按名字取 Bean 时生效。** 这里有个容易看错的地方：`@Scope("prototype")` 修饰的是 `routingChatModelPrototype()` 这个 Bean 定义，只有走 `getBean(...)` / `ObjectProvider` 才会每次都新建。如果哪天有人直接 `@Resource private ChatModel routingChatModelPrototype;` 注入——**注入的是单例**（Spring 会在启动时创建一次并缓存）。这是 Spring 的既有行为，不是本项目的 bug，但在读这段代码时极易误解。
- **入口 B 的注释写得很实在**：「默认提供一个 Bean」。因为 `langgraph4j/node/RouterNode.java#L27` 是在**静态方法**里取 Bean 的，只能按类型取（`SpringContextUtil.getBean(AiCodeGenTypeRoutingService.class)`），必须有唯一一个该类型的 Bean 存在，否则 `getBean(Class)` 会因找不到候选而报错。这个 `@Bean` 方法存在的意义就是**给按类型查找兜底**。

`utils/SpringContextUtil.java` 是这套机制的支撑：实现 `ApplicationContextAware`，把 `ApplicationContext` 存到 `static` 字段（`#L15-L20`），从而让静态方法也能拿 Bean。**这是静态上下文在项目里的唯一用途**，看到 `SpringContextUtil.getBean` 就应该意识到「此处拿不到 Spring 注入，只能靠静态上下文」。

### 1.4 代码走读：`AppServiceImpl#createApp`

入口在 `controller/AppController.java#L153-L160`，逻辑极薄——判空、取登录用户、调服务、包装响应：

```java
@PostMapping("/add")
public BaseResponse<Long> addApp(@RequestBody AppAddRequest appAddRequest, HttpServletRequest request) {
    ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR);
    // 获取当前登录用户
    User loginUser = userService.getLoginUser(request);
    Long appId = appService.createApp(appAddRequest, loginUser);
    return ResultUtils.success(appId);
}
```

注意两点：**没有 `@AuthCheck` 注解**，登录态完全靠 `userService.getLoginUser(request)` 内部抛异常来保证；**返回的是应用 ID**，前端拿到后立刻用它去建 SSE 连接（环节 2 的入口）。

核心逻辑在 `service/impl/AppServiceImpl.java#L118-L138`，共 6 步：

```java
@Override
public Long createApp(AppAddRequest appAddRequest, User loginUser) {
    // 参数校验
    String initPrompt = appAddRequest.getInitPrompt();
    ThrowUtils.throwIf(StrUtil.isBlank(initPrompt), ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");
    // 构造入库对象
    App app = new App();
    BeanUtil.copyProperties(appAddRequest, app);
    app.setUserId(loginUser.getId());
    // 应用名称暂时为 initPrompt 前 12 位
    app.setAppName(initPrompt.substring(0, Math.min(initPrompt.length(), 12)));
    // 使用 AI 智能选择代码生成类型（多例模式）
    AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService = aiCodeGenTypeRoutingServiceFactory.createAiCodeGenTypeRoutingService();
    CodeGenTypeEnum selectedCodeGenType = aiCodeGenTypeRoutingService.routeCodeGenType(initPrompt);
    app.setCodeGenType(selectedCodeGenType.getValue());
    // 插入数据库
    boolean result = this.save(app);
    ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
    log.info("应用创建成功，ID: {}, 类型: {}", app.getId(), selectedCodeGenType.getValue());
    return app.getId();
}
```

逐步拆解：

| 步 | 位置 | 做什么 | 要注意什么 |
|---|---|---|---|
| 1 | `#L121-L122` | 校验 `initPrompt` 非空白 | 用 `StrUtil.isBlank`（Hutool），空串和纯空格都拦 |
| 2 | `#L124-L126` | `new App()` + `BeanUtil.copyProperties` 把 DTO 拷进实体，再补 `userId` | 拷贝的是 `initPrompt` 一个字段；`userId` **不来自请求体**，只来自登录态（防越权） |
| 3 | `#L128` | 应用名 = `initPrompt` 前 12 个字符 | `Math.min` 防止短于 12 字时 `substring` 越界 |
| 4 | `#L130-L131` | 工厂造路由服务 → 调 LLM 判类型 | **同步阻塞调用**，是本接口延迟主要来源 |
| 5 | `#L132` | `setCodeGenType(枚举.getValue())` | 存的是 `html`/`multi_file`/`vue_project` |
| 6 | `#L134-L137` | `this.save(app)` 入库、失败抛错、打日志、返回 ID | `save` 来自 MyBatis-Flex 的 `ServiceImpl` |

**三处「代码没写但确实生效」的地方**（读代码时最容易以为是漏了）：

1. **`id` 不是数据库自增生成的。** `model/entity/App.java#L37` 标了 `@Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)`，MyBatis-Flex 会在 Java 侧先生成雪花 ID 再显式插入。而 `sql/create_table.sql#L35` 写的是 `bigint auto_increment primary key`——**两边不一致，以 Java 侧为准**。所以 `save` 之后 `app.getId()` 立刻就有值，不需要 `useGeneratedKeys`。
2. **`createTime` / `editTime` / `priority` / `isDelete` 全部没在 Java 里赋值。** 靠的是 DDL 的默认值：`editTime datetime default CURRENT_TIMESTAMP not null`、`createTime datetime default CURRENT_TIMESTAMP not null`、`priority int default 0 not null`、`isDelete tinyint default 0 not null`（`sql/create_table.sql#L42-L47`）。**这意味着这套代码换一个没有默认值的库会直接插入失败。**
3. **`updateTime` 的维护在数据库侧。** DDL 里是 `on update CURRENT_TIMESTAMP`，Java 永远不写这个字段。

**一处真实的健壮性缺口（与 LangGraph4j 侧写法不一致）：**

`createApp` 拿到 `selectedCodeGenType` 后**直接 `.getValue()`，既没判空也没有 try/catch**。对比 `langgraph4j/node/RouterNode.java#L24-L34`，同一个路由能力在那边是包了降级的：

```java
CodeGenTypeEnum generationType;
try {
    AiCodeGenTypeRoutingService routingService = SpringContextUtil.getBean(AiCodeGenTypeRoutingService.class);
    generationType = routingService.routeCodeGenType(context.getOriginalPrompt());
    log.info("AI智能路由完成，选择类型: {} ({})", generationType.getValue(), generationType.getText());
} catch (Exception e) {
    log.error("AI智能路由失败，使用默认HTML类型: {}", e.getMessage());
    generationType = CodeGenTypeEnum.HTML;   // ← 降级兜底
}
```

也就是说：**工作流里路由失败会退化成 HTML 继续跑，而创建应用接口里路由失败会直接 500**。如果模型偶发返回了无法映射的枚举值，或上游 API 超时，用户看到的就是「创建应用失败」。这是后续环节（环节 2/4）反复出现的主题——**主链路偏好「失败即报错」，工作流偏好「降级继续」**，两种风格在同一个仓库里并存，读的时候要分清当前在看哪一套。

### 1.5 动手验证

**准备。** 配置 `application.yml#L74-L80` 的 `routing-chat-model.api-key`（走的是阿里云 DashScope 的 OpenAI 兼容端点 `https://dashscope.aliyuncs.com/compatible-mode/v1`，模型 `qwen-turbo`），启动应用。注意**所有接口都带 `/api` 前缀**（`application.yml#L28` 的 `context-path`），端口 `8123`。

**验证 1：三种需求分别命中三种类型。** 先登录拿到会话 Cookie（登录接口不在本环节范围，`curl -c cookie.txt` 保存即可），然后连续发三个不同复杂度的需求：

```bash
# ① 简单展示页 → 预期 html
curl -X POST "http://localhost:8123/api/app/add" -b cookie.txt \
  -H "Content-Type: application/json" \
  -d '{"initPrompt":"做一个个人简介页面，展示我的头像、姓名和一段自我介绍"}'

# ② 多页面无复杂交互 → 预期 multi_file
curl -X POST "http://localhost:8123/api/app/add" -b cookie.txt \
  -H "Content-Type: application/json" \
  -d '{"initPrompt":"做一个公司官网，包含首页、关于我们、联系我们三个页面"}'

# ③ 复杂交互/数据管理 → 预期 vue_project
curl -X POST "http://localhost:8123/api/app/add" -b cookie.txt \
  -H "Content-Type: application/json" \
  -d '{"initPrompt":"做一个后台管理系统，需要登录、用户列表增删改查、数据统计图表"}'
```

**预期看到什么。** 三个响应体形如 `{"code":0,"data":1961xxxxxxxxxxxxxxxx,"message":"ok"}`（`data` 是雪花 ID，19 位数字）。**因为 `config/JsonConfig.java#L19-L27` 把 `Long` 序列化成了 String**，前端拿到的是字符串而不是数字——这是为了避免 JS 大整数精度丢失，19 位雪花 ID 用 `Number` 接会掉精度。

**验证 2：确认入库的值是 value 而不是常量名。**

```sql
select id, appName, codeGenType, priority, isDelete, createTime
from app
order by id desc
limit 5;
```

**预期看到什么。** `codeGenType` 列的值是**小写下划线**形式：`html` / `multi_file` / `vue_project`；`appName` 是提示词的**前 12 个字符**（中文按字符算，例如「做一个个人简介页面，展示我的」）；`priority` 为 `0`、`isDelete` 为 `0`、`createTime` 有值——**这三个字段 Java 代码里完全没写**，是 DDL 默认值填的（对应 1.4 的第 2 点）。

**验证 3：确认路由真的调了模型，而不是本地规则。** `application.yml#L79-L80` 打开了 `log-requests: true` / `log-responses: true`，应用日志里会打印这次路由的请求体（含完整提示词）和响应体。**预期看到**响应体是一个只包含枚举名的极短输出——这也解释了为什么 `max-tokens: 100` 就够了。

**验证 4：确认失败时的行为差异（对应 1.4 的健壮性缺口）。** 把 `routing-chat-model.api-key` 临时改成一个无效值再重启，然后调用 `/api/app/add`。**预期看到**接口直接返回业务异常/500，应用**没有被创建**——这印证了「主链路不降级」。同样条件下走 `/api/workflow/execute`（LangGraph4j 路由节点），日志里会出现 `AI智能路由失败，使用默认HTML类型` 且流程继续。

**验证 5：确认路由发生在写库之前。** 打开 SQL 日志（`mybatis-flex.configuration.log-impl`，见 `application-prod-sample.yml#L69-L71`），观察插入语句与模型调用日志的先后顺序。**预期看到**模型调用日志在前、`INSERT INTO app` 在后——说明类型是**算出来之后再入库的**，而不是先入库再回填。

### 1.6 自测题

1. `AiCodeGenTypeRoutingService` 没有实现类，`routeCodeGenType` 的实现在哪里？（提示：从 `AiCodeGenTypeRoutingServiceFactory` 入手）
2. `routeCodeGenType` 的返回类型是 `CodeGenTypeEnum` 而不是 `String`，这样做省掉了哪几步代码？代价是什么？
3. `AppAddRequest` 里为什么没有 `codeGenType` 字段？
4. 数据库中 `codeGenType` 列存的是 `HTML` 还是 `html`？分别对应 `CodeGenTypeEnum` 的哪个成员？
5. **（陷阱）** 提示词文件里写的是 `HTML`、`MULTI_FILE`、`VUE_PROJECT`，而入库存的是 `html`、`multi_file`、`vue_project`。如果我今天把 `CodeGenTypeEnum` 的 `value` 从 `"html"` 改成 `"html_single"`，会有什么后果？哪些地方必须同步改？
6. **（陷阱）** `RoutingAiModelConfig` 的 `routingChatModelPrototype()` 标了 `@Scope("prototype")`。如果我在某个 `@Service` 里写 `@Resource private ChatModel routingChatModelPrototype;`，拿到的是每次新建的实例还是单例？为什么？
7. `AiCodeGenTypeRoutingServiceFactory` 里为什么同时存在 `createAiCodeGenTypeRoutingService()` 和 `@Bean aiCodeGenTypeRoutingService()` 两个入口？`createApp` 走的是哪一个？`RouterNode` 走的是哪一个？
8. `RouterNode` 为什么必须用 `SpringContextUtil.getBean(...)` 而不能用 `@Resource` 注入？
9. **（陷阱）** `createApp` 里 `selectedCodeGenType` 如果为 `null`，会在哪一行抛异常？抛的是什么异常？`RouterNode` 在这种情况下会怎样？
10. `app.setAppName(initPrompt.substring(0, Math.min(initPrompt.length(), 12)))` 里的 `Math.min` 去掉会怎样？
11. `createApp` 插入记录时，`createTime`、`priority`、`isDelete` 是谁赋的值？如果换成 PostgreSQL 且没有写默认值，会发生什么？
12. `App` 实体的 `@Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)` 与 `create_table.sql` 里的 `bigint auto_increment` 冲突吗？以哪个为准？
13. **（陷阱）** 接口返回的 `appId` 在 JSON 里是数字还是字符串？为什么？（提示：`config/JsonConfig.java`）
14. `application.yml` 里 `routing-chat-model` 用的是 `qwen-turbo` 且 `max-tokens: 100`，而代码生成用的是 `deepseek-chat` / `deepseek-reasoner` 且 `max-tokens` 上万。为什么要给路由单独配一个小模型？
15. `AppController#addApp` 上没有 `@AuthCheck`，那未登录用户能创建应用吗？靠什么拦住？

<details>
<summary>参考答案（先自己答完再展开）</summary>

1. 没有手写实现类。由 `AiCodeGenTypeRoutingServiceFactory#createAiCodeGenTypeRoutingService()`（`ai/AiCodeGenTypeRoutingServiceFactory.java#L23-L28`）里的 `AiServices.builder(AiCodeGenTypeRoutingService.class).chatModel(chatModel).build()` 在运行时生成 JDK 动态代理来「实现」这个接口。
2. 省掉：解析模型返回的字符串 → 手工匹配枚举 → 判空 → 转类型。代价：把「输出格式是否合法」的约束完全交给了框架和提示词，代码里失去了中间校验点——这正是第 9 题缺口的来源。
3. 因为类型是**由 AI 根据 `initPrompt` 判出来的**，不是用户选的。若暴露给前端，用户就能绕过智能路由。见 `model/dto/app/AppAddRequest.java#L16`（只有 `initPrompt` 一个字段）。
4. 存的是 `html`（即 `getValue()`）。`HTML("原生 HTML 模式", "html")`——第一个参数是给前端展示的 `text`，第二个是入库的 `value`。见 `model/enums/CodeGenTypeEnum.java#L12-L14`。
5. 后果分两层：① **新数据**仍能创建（`setCodeGenType` 存新 value，不报错）；② **历史数据反查全部失败**——`getEnumByValue` 只比对 `value`（`CodeGenTypeEnum#L30-L40`），旧记录的 `"html"` 找不到对应枚举，返回 `null`，随后 `chatToGenCode` 会抛「应用代码生成类型错误」（`AppServiceImpl#L96-L98`），这些应用**再也无法对话**。必须同步改的地方：`CodeGenTypeEnum` 的 `value`、以及**存量数据的 `codeGenType` 列**（需要 UPDATE 迁移）。提示词用的是常量名，**不用改**——但反过来说，如果改的是常量名（`HTML`→`HTML_SINGLE`），则提示词必须同步改，否则模型返回的名字对不上。
6. 拿到的是**单例**。`@Scope("prototype")` 描述的是 Bean 定义的作用域，只有通过 `getBean()` / `ObjectProvider` / `@Lookup` 主动获取时才会每次新建；`@Resource` 注入走的是依赖注入流程，容器会在启动时创建一次并缓存。所以 `RoutingAiModelConfig` 的 prototype 语义**只有在 `SpringContextUtil.getBean("routingChatModelPrototype", ...)` 这种用法下才生效**——这正是工厂里那么写的原因。
7. 两个入口服务两类调用方：`createAiCodeGenTypeRoutingService()` 供**能正常注入的 Spring 组件**使用（`AppServiceImpl#L130` 走这条，每次新建，注释里叫「多例模式」）；`@Bean aiCodeGenTypeRoutingService()` 供**静态上下文按类型查找**使用（`RouterNode#L27` 的 `getBean(AiCodeGenTypeRoutingService.class)` 走这条，因为按类型找必须有唯一 Bean，否则容器启动/查找就报错）。
8. 因为 `RouterNode` 的代码在 `node_async(state -> {...})` 这个 **lambda 内部**，而 `RouterNode` 本身**不是 Spring Bean**（类上只有 `@Slf4j`，见 `langgraph4j/node/RouterNode.java#L16-L17`，且 `create()` 是 `static` 方法）。它由 `CodeGenWorkflow` 直接 `new`/静态调用创建，不经过容器，所以没有注入时机，只能靠 `SpringContextUtil` 从静态上下文里捞。
9. 在 `AppServiceImpl#L132` 抛 `NullPointerException`（对 `null` 调 `.getValue()`），不是业务异常，所以 `GlobalExceptionHandler` 会按未预期异常处理。`RouterNode` 不会出这个问题：它外面包了 `try/catch`，任何异常（含 NPE）都会落到 `generationType = CodeGenTypeEnum.HTML`（`RouterNode#L31-L34`）继续执行。**同一个能力，两条链路两种失败语义**，这是本环节最值得记住的差异。
10. 当 `initPrompt` 长度小于 12（例如「做个网页」4 个字）时会抛 `StringIndexOutOfBoundsException`。`Math.min(initPrompt.length(), 12)` 就是为短提示词兜底的。
11. 都不是 Java 赋的，是 **MySQL 表结构的默认值**：`editTime`/`createTime` 用 `default CURRENT_TIMESTAMP`，`priority` 用 `default 0`，`isDelete` 用 `default 0`（`sql/create_table.sql#L42-L47`）。换成没有这些默认值的库（且代码也不补），`priority`、`isDelete` 是 `not null` 列，插入会直接失败。
12. 不冲突，以 **Java 侧为准**。`KeyType.Generator` + `KeyGenerators.snowFlakeId` 表示 ID 由 MyBatis-Flex 在应用内生成后**显式插入**，DDL 里的 `auto_increment` 实际上不会被用到（但因为列是主键，留着也无害）。这也解释了为什么 `save` 之后 `app.getId()` 立刻可用。
13. 是**字符串**。`config/JsonConfig.java#L19-L27` 注册了 `@JsonComponent`，把 `Long` / `long` 统一序列化成 `ToStringSerializer`。原因是雪花 ID 是 19 位数字，超过 JS `Number.MAX_SAFE_INTEGER`（2^53-1，16 位），用数字传前端会掉精度。
14. 因为这是一个**分类任务**，不是生成任务：输入一句话，输出一个枚举名，输出长度十几个 token。用大模型是浪费——延迟高、成本高。所以单独配了 `qwen-turbo` + `max-tokens: 100`，并用 DashScope 的 OpenAI 兼容端点（`application.yml#L74-L80`），与代码生成的 `deepseek` 系列完全隔离。**这是「按任务难度分级选模型」的落地**：路由/质检用轻量模型，代码生成用重模型。
15. 不能。靠 `userService.getLoginUser(request)`（`AppController#L157`）——它从 Session 里取用户，取不到就抛未登录异常。`getLoginUser` 的实现见 `service/impl/UserServiceImpl.java#L112-L126`（先查 Session 缓存，再回查数据库）。所以「有没有 `@AuthCheck`」和「是否需要登录」是两回事：`@AuthCheck` 管的是**角色**（见 `annotation/AuthCheck.java` 的 `mustRole`），登录态是另一套机制。

</details>

### 1.7 本环节技术点清单

| 技术点 | 落地位置 | 作用 | 项目特有之处 |
|---|---|---|---|
| LangChain4j `AiServices` 动态代理 | `ai/AiCodeGenTypeRoutingService.java`、`AiCodeGenTypeRoutingServiceFactory#L25-L27` | 接口无实现类，运行时生成代理 | 全项目所有 AI 能力都走这个套路（路由/生成/质检/图片收集） |
| 枚举结构化输出 | `routeCodeGenType` 返回 `CodeGenTypeEnum` | 免手工解析 JSON | 提示词用常量名、入库用 `value`，**两套名字** |
| `@SystemMessage(fromResource)` | `ai/AiCodeGenTypeRoutingService.java#L20` | 提示词外置为 classpath 资源 | 7 个 prompt 文件统一放 `resources/prompt/` |
| 单参数免 `@UserMessage` | 同上 | 唯一 String 参数即用户消息 | 与 `AiCodeGeneratorService` 的显式标注写法**并存** |
| `@Scope("prototype")` 模型 Bean | `config/RoutingAiModelConfig.java#L36-L48` | 每次取用新建模型实例 | **仅 `getBean()` 生效**，注入时仍是单例 |
| 工厂 + 静态上下文 | `AiCodeGenTypeRoutingServiceFactory`、`utils/SpringContextUtil.java` | 静态方法里也能拿 Bean | 双入口设计：`create...()` 给注入方，`@Bean` 给按类型查找方 |
| 按任务分级选模型 | `application.yml#L74-L80`（qwen-turbo / 100 tokens）vs `#L57-L72`（deepseek / 8192~32768） | 分类任务用小模型，生成任务用大模型 | 路由走 DashScope，生成走 DeepSeek，**两个厂商** |
| 雪花 ID + 逻辑删除 | `model/entity/App.java#L37`、`#L107` | 主键生成、软删除 | 与 DDL 的 `auto_increment` 并存，**以 Java 为准** |
| DDL 默认值承担时间戳 | `sql/create_table.sql#L42-L47` | `createTime`/`editTime`/`priority`/`isDelete` | Java 侧完全不写这几个字段 |
| `Long` → String 序列化 | `config/JsonConfig.java#L19-L27` | 防雪花 ID 精度丢失 | 所有 ID 出参都是字符串 |
| 降级策略的两种风格 | `RouterNode#L31-L34`（降级 HTML）vs `AppServiceImpl#L132`（直接抛） | 失败语义 | 主链路报错、工作流降级，**同一能力两套语义** |

### 1.8 本环节产出

这个环节用「一次极轻量的 LLM 调用」把不可逆的架构决策前置了：用户只给一句自然语言，平台通过 `AiServices` 动态代理让 `qwen-turbo` 在 100 token 的预算内输出一个枚举，用结构化输出免掉手工解析，再把枚举的 `value` 落进 `app.codeGenType`——从此这个应用的全部后续行为（走不走工具调用、要不要 npm 构建、用不用推理模型）都由这个字段决定。为了让这个能力同时服务于「能注入的 Service」和「在静态 lambda 里跑的工作流节点」，项目做了双入口工厂 + prototype 模型 Bean + `SpringContextUtil` 静态上下文三层支撑，代价是同一个路由能力在两条链路上有**两种失败语义**：主链路直接报错，工作流降级成 HTML。读到这里应该能回答一个核心问题——**「应用创建后，是谁决定了它之后走哪条生成链路」**：答案是 `CodeGenTypeEnum` 的 `value` 字段，而它是被 AI 写进去的。

---

## 环节 2：对话式代码生成（SSE 主链路）

### 2.1 这个环节解决什么问题

环节 1 创建了应用并确定了生成类型，但那时**还没有生成任何代码**。用户真正看到代码产出，靠的是本环节这条链路：前端带着 `appId` 和一句话需求，建一条 SSE 长连接，服务端把大模型的输出**逐字推回去**，同时在流结束时把完整代码落盘。

这条链路要同时解决四个问题，缺一不可：

| 要解决的问题 | 落地手段 |
|---|---|
| 大模型输出是「挤牙膏」式的，不能等全部生成完再返回 | 用 SSE 逐块推送，首字节时间从分钟级降到秒级 |
| 三种生成类型产出的流结构完全不同（纯文本 vs 带工具调用的 JSON） | 上游用 `switch` 分流成两种流，下游再用 `StreamHandlerExecutor` 分流成两种处理器 |
| 流式接口报错时，前端已经收到 `200` 和响应头了，没法再返回 JSON 错误体 | `GlobalExceptionHandler` 单独开一条 SSE 错误通道，手写 `business-error` 事件帧 |
| 流结束后还要「做后续动作」（保存对话历史、落盘代码、构建 Vue 工程） | 用 Reactor 的 `doOnComplete` / `doFinally` 挂回调，而不是在 controller 里同步等待 |

**一句话概括这条链路的本质：把「一次阻塞的、可能持续几分钟的大模型调用」改造成「一条可控的、可挂回调的响应式流」，并且在这条流的首尾各塞进一段业务逻辑。**

### 2.2 技术点一：在 Spring MVC（Servlet 栈）上做 SSE

**是什么。** Controller 的方法签名是这样的（`controller/AppController.java#L58-L62`）：

```java
@GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60, message = "AI 对话请求过于频繁，请稍后再试")
public Flux<ServerSentEvent<String>> chatToGenCode(@RequestParam Long appId,
                                                   @RequestParam String message,
                                                   HttpServletRequest request) {
```

**返回类型是 `Flux<ServerSentEvent<String>>`，但这不等于项目用了 WebFlux。** 这是最容易误判的一点。查 `pom.xml` 可以确认：

- `pom.xml#L33-L36`：引入的是 `spring-boot-starter-web`（Servlet 栈，跑在 Tomcat 上）
- **没有** `spring-boot-starter-webflux`
- `pom.xml#L63-L67`：引入的是 `langchain4j-reactor`，它把 `reactor-core` 带进了 classpath

所以真实情况是：**web 层仍然是 Servlet/Tomcat，`Flux` 只是作为「响应体的数据类型」被 Spring MVC 识别并适配成 SSE 输出**。因为 `reactor-core` 在 classpath 上，Spring MVC 有能力消费它；这跟 WebFlux 是两套东西。

**为什么这里用它。** 三个理由：

1. **不需要为了一个流式接口换掉整个 web 栈。** 项目其余几十个接口都是普通同步接口，改成 WebFlux 会让全项目跟着变响应式（线程模型、拦截器、事务都要重审）。只在需要流式的地方用 `Flux` 做返回值，是成本最低的做法。
2. **`produces = MediaType.TEXT_EVENT_STREAM_VALUE` 决定了响应头。** 这一行让响应变成 `Content-Type: text/event-stream`，浏览器/前端的 `EventSource` 才会按 SSE 协议解析。同时它也成了后面错误处理的判定依据（见 2.4）。
3. **GET 方法 + query 参数。** 注意这是 `@GetMapping`，参数走 URL（`?appId=xxx&message=xxx`）而不是请求体。`EventSource` 原生只支持 GET，这是被前端技术选型倒逼的接口设计。

> **⚠️ 本项目特有的坑：`context-path` 是 `/api`。**
> `application.yml#L28` 配了 `server.servlet.context-path: /api`，所以真实路径是 `GET /api/app/chat/gen/code`，不是 `/app/chat/gen/code`。用 curl 或 Postman 直接测的时候漏掉 `/api` 会 404。另外注意 `GlobalExceptionHandler` 里判断 SSE 请求用的是 `uri.contains("/chat/gen/code")`（`exception/GlobalExceptionHandler.java#L64`）——它匹配的是**去掉 context-path 之后的部分**（`getRequestURI()` 不含 context-path），所以这个判断是能命中的，改路径时两处都要动。

### 2.3 技术点二：两级流模型——`Flux<String>` 与 `TokenStream` 的适配

**是什么。** 项目里存在**两种流式返回类型**，取决于生成类型（`core/AiCodeGeneratorFacade.java#L85-L102`）：

```java
return switch (codeGenTypeEnum) {
    case HTML -> {
        Flux<String> codeStream = aiCodeGeneratorService.generateHtmlCodeStream(userMessage);
        yield processCodeStream(codeStream, CodeGenTypeEnum.HTML, appId);
    }
    case MULTI_FILE -> {
        Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(userMessage);
        yield processCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId);
    }
    case VUE_PROJECT -> {
        TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage);
        yield processTokenStream(tokenStream, appId);
    }
    default -> {
        String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
    }
};
```

对照 `ai/AiCodeGeneratorService.java` 的接口定义，差异的根源在**方法签名**：

```java
@SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
Flux<String> generateHtmlCodeStream(String userMessage);          // 只有 userMessage

@SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
TokenStream generateVueProjectCodeStream(@MemoryId long appId, @UserMessage String userMessage);  // 多一个 appId
```

**为什么必须分成两种。**

| 维度 | `Flux<String>`（HTML / MULTI_FILE） | `TokenStream`（VUE_PROJECT） |
|---|---|---|
| 流里有什么 | 纯文本增量 | 文本增量 **+ 工具调用请求 + 工具执行结果** 三种事件 |
| 需要对话记忆吗 | 不需要 `@MemoryId`（记忆在实例级绑定，见下） | 需要，`@MemoryId long appId` |
| 用哪个模型 | `streamingChatModelPrototype`（deepseek-chat） | `reasoningStreamingChatModelPrototype`（deepseek-reasoner） |
| 是否挂工具 | 不挂 | `.tools(toolManager.getAllTools())` |
| 流结束后要做什么 | 解析 + 落盘 | 解析 + 落盘 **+ 同步执行 npm 构建** |

`Flux<String>` 表达不了「工具调用」这种结构化事件——它只能推文本。而 VUE_PROJECT 模式下模型会反复调用 `FileWriteTool` 等工具去写文件，这些过程必须让前端看到（否则用户面对的是一个几分钟不动的界面）。所以这里用了 LangChain4j 的 `TokenStream`，它能挂四类回调。

**适配方式：`Flux.create` 手工桥接。** `TokenStream` 不是 Reactor 类型，需要转成 `Flux` 才能和上层统一（`core/AiCodeGeneratorFacade.java#L112-L138`）：

```java
private Flux<String> processTokenStream(TokenStream tokenStream, Long appId) {
    return Flux.create(sink -> {
        tokenStream.onPartialResponse((String partialResponse) -> {
                    AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                    sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                })
                .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                    ToolRequestMessage toolRequestMessage = new ToolRequestMessage(toolExecutionRequest);
                    sink.next(JSONUtil.toJsonStr(toolRequestMessage));
                })
                .onToolExecuted((ToolExecution toolExecution) -> {
                    ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                    sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
                })
                .onCompleteResponse((ChatResponse response) -> {
                    // 执行 Vue 项目构建（同步执行，确保预览时项目已就绪）
                    String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId;
                    vueProjectBuilder.buildProject(projectPath);
                    sink.complete();
                })
                .onError((Throwable error) -> {
                    error.printStackTrace();
                    sink.error(error);
                })
                .start();
    });
}
```

这段代码有 **4 个必须点明的设计决策**：

1. **`Flux.create(sink -> ...)` 是「手动驱动」模式。** 不是在 map/filter 里做转换，而是拿到 `sink` 后，把 LangChain4j 的回调**手动翻译**成 `sink.next(...)`。`start()` 放在链式调用的最后，意思是「回调都注册好了，可以开始了」。
2. **`onPartialToolExecutionRequest` 不是 LangChain4j 的官方 API。** 它是本项目在 `src/main/java/dev/langchain4j/` 下打的**源码级补丁**新增的回调（详见环节 3）。这意味着：如果直接升级 LangChain4j 官方版本，这个方法不存在，本类**编译不过**。
3. **`onCompleteResponse` 里是同步阻塞的 npm 构建。** `vueProjectBuilder.buildProject(projectPath)` 会执行 `npm install` + `npm build`（超时分别是 300s / 180s，见环节 6），然后才 `sink.complete()`。**也就是说 SSE 连接在代码生成完之后还会继续挂着几分钟**，直到构建完成才发 `done` 事件。注释里写明了原因：「确保预览时项目已就绪」。代价是：长连接容易被 Nginx/网关按超时切断，且这段时间占用一个 Servlet 线程（Tomcat 默认 200 线程）。
4. **`onError` 里用的是 `error.printStackTrace()` 而不是 `log.error`。** 全项目唯一这么写的地方之一，生产环境这个异常**不会进日志系统**，只会打到标准输出。

**两个处理器的差别也值得单独记一笔**（`core/handler/StreamHandlerExecutor.java#L34-L43`）：

```java
return switch (codeGenType) {
    case VUE_PROJECT -> // 使用注入的组件实例
            jsonMessageStreamHandler.handle(originFlux, chatHistoryService, appId, loginUser);
    case HTML, MULTI_FILE -> // 简单文本处理器不需要依赖注入
            new SimpleTextStreamHandler().handle(originFlux, chatHistoryService, appId, loginUser);
};
```

注意：**`SimpleTextStreamHandler` 是 `new` 出来的，不是 Spring Bean**（`core/handler/SimpleTextStreamHandler.java` 类上只有 `@Slf4j`，没有 `@Component`），而 `JsonMessageStreamHandler` 是 `@Resource` 注入的。类注释解释了原因——「简单文本处理器不需要依赖注入」。**项目里没有 `StreamHandler` 接口**，这两个 Handler 没有共同父类型，完全靠这个 `switch` 分派（这一点与 `core/parser`、`core/saver` 都有接口的写法不同，容易误以为存在 `StreamHandler` 接口）。

### 2.4 代码走读：`AppServiceImpl#chatToGenCode` 的 8 步

Controller 层只做了「校验 → 取用户 → 调用 → 包装 SSE 帧」，真正的编排在 Service（`service/impl/AppServiceImpl.java#L81-L116`）：

```java
@Override
public Flux<String> chatToGenCode(Long appId, String message, User loginUser) {
    // 1. 参数校验
    ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
    ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "提示词不能为空");
    // 2. 查询应用信息
    App app = this.getById(appId);
    ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
    // 3. 权限校验，仅本人可以和自己的应用对话
    if (!app.getUserId().equals(loginUser.getId())) {
        throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
    }
    // 4. 获取应用的代码生成类型
    String codeGenType = app.getCodeGenType();
    CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
    if (codeGenTypeEnum == null) {
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
    }
    // 5. 在调用 AI 前，先保存用户消息到数据库中
    chatHistoryService.addChatMessage(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());
    // 6. 设置监控上下文（用户 ID 和应用 ID）
    MonitorContextHolder.setContext(
            MonitorContext.builder()
                    .userId(loginUser.getId().toString())
                    .appId(appId.toString())
                    .build()
    );
    // 7. 调用 AI 生成代码（流式）
    Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId);
    // 8. 收集 AI 响应的内容，并且在完成后保存记录到对话历史
    return streamHandlerExecutor.doExecute(codeStream, chatHistoryService, appId, loginUser, codeGenTypeEnum)
            .doFinally(signalType -> {
                // 流结束时清理（无论成功/失败/取消）
                MonitorContextHolder.clearContext();
            });
}
```

| 步 | 位置 | 做什么 | 关键点 |
|---|---|---|---|
| 1 | `#L84-L85` | 校验 `appId`、`message` | 这里抛的是**业务异常**，发生在流建立前，走的是普通 JSON 错误响应 |
| 2 | `#L87-L88` | `getById(appId)` 查应用 | MyBatis-Flex 的 `ServiceImpl` 自带方法 |
| 3 | `#L90-L92` | **越权校验：只有应用主人能对话** | 用 `app.getUserId().equals(loginUser.getId())` 而非 `==`（Long 是包装类型，`==` 比地址，必须用 `equals`） |
| 4 | `#L94-L98` | 反查 `CodeGenTypeEnum`，`null` 则报错 | 这里就是环节 1 埋下的伏笔：如果枚举 `value` 改了而存量数据没迁移，**这一步会拦住所有老应用** |
| 5 | `#L100` | **先存用户消息，再调 AI** | 顺序是刻意的，见下方说明 |
| 6 | `#L102-L107` | 往 `ThreadLocal` 写监控上下文 | 供环节 8 的 `ChatModelListener` 读取 |
| 7 | `#L109` | 生成流（含落盘逻辑） | 注意此方法**同步执行完 `switch`** 才返回 `Flux`，异常会在这里直接抛 |
| 8 | `#L111-L115` | 挂历史记录处理器 + `doFinally` 清理 | `doFinally` 覆盖成功/失败/取消三种终止 |

**第 5 步的顺序为什么重要。** 如果把「存用户消息」放在 `doOnComplete` 里，那么一旦 AI 调用失败，**用户说过的话就永久丢失了**，前端刷新后对话历史里没有这一轮提问。放在调用 AI 之前，保证「用户说了什么」这条事实不依赖 AI 是否成功。代价是：如果 AI 失败，库里会留下一条没有对应 AI 回复的孤立用户消息。

**第 8 步的 `doFinally` 为什么必须存在。** `MonitorContextHolder` 是 `ThreadLocal`（`monitor/MonitorContextHolder.java#L11`），Servlet 线程是**池化复用**的。如果不清理，下一个请求复用到同一线程时，`getContext()` 会拿到上一个用户的 `userId`/`appId`——**监控数据会张冠李戴**。`doFinally` 而不是 `doOnComplete`，是因为前者在「完成、异常、取消」三种情况下都会执行（注释也写明了这一点）。

**Controller 侧的 SSE 帧协议**（`controller/AppController.java#L70-L84`）：

```java
return contentFlux
        .map(chunk -> {
            Map<String, String> wrapper = Map.of("d", chunk);
            String jsonData = JSONUtil.toJsonStr(wrapper);
            return ServerSentEvent.<String>builder()
                    .data(jsonData)
                    .build();
        })
        .concatWith(Mono.just(
                // 发送结束事件
                ServerSentEvent.<String>builder()
                        .event("done")
                        .data("")
                        .build()
        ));
```

每个数据块都被包成 `{"d": "..."}` 再作为 SSE 的 `data` 字段。**外层是 SSE 协议，内层是自定义 JSON**——两层都要解析。注意 `Map.of("d", chunk)` 如果 `chunk` 为 `null` 会直接抛 NPE（`Map.of` 不接受 null 值），但上游 `sink.next` 不会推 null，所以实际不会触发。

最后一帧用 `concatWith` 追加一个 `event: done`，这是给前端的**结束信号**：前端收到 `done` 才关闭连接、才去刷新预览。注意它的 `data` 是空字符串，而错误帧的 `data` 是 `{}`（见下），两者格式不一致。

**错误帧：`GlobalExceptionHandler#handleSseError`**（`exception/GlobalExceptionHandler.java#L53-L94`）。这是本环节最「绕」的一段代码，值得单独理解：

```java
private boolean handleSseError(int errorCode, String errorMessage) {
    ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
        return false;
    }
    HttpServletRequest request = attributes.getRequest();
    HttpServletResponse response = attributes.getResponse();
    // 判断是否是SSE请求（通过Accept头或URL路径）
    String accept = request.getHeader("Accept");
    String uri = request.getRequestURI();
    if ((accept != null && accept.contains("text/event-stream")) ||
            uri.contains("/chat/gen/code")) {
        try {
            // 设置SSE响应头
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            // 构造错误消息的SSE格式
            Map<String, Object> errorData = Map.of(
                    "error", true,
                    "code", errorCode,
                    "message", errorMessage
            );
            String errorJson = JSONUtil.toJsonStr(errorData);
            // 发送业务错误事件（避免与标准error事件冲突）
            String sseData = "event: business-error\ndata: " + errorJson + "\n\n";
            response.getWriter().write(sseData);
            response.getWriter().flush();
            // 发送结束事件
            response.getWriter().write("event: done\ndata: {}\n\n");
            response.getWriter().flush();
            return true;
        } catch (IOException ioException) {
            log.error("Failed to write SSE error response", ioException);
            return true;
        }
    }
    return false;
}
```

拆开看：

- **判定方式：`Accept` 头含 `text/event-stream`，或 URI 含 `/chat/gen/code`。** 双条件是为了兜底——有些客户端（如 curl 不带 Accept）靠 URL 判断。
- **手动拼 SSE 文本。** 因为这时已经不能返回 `BaseResponse` JSON 了（客户端在按 SSE 解析），所以直接 `response.getWriter().write("event: business-error\ndata: {...}\n\n")`。**SSE 的帧格式就是「`event:` 行 + `data:` 行 + 空行」**，必须手写 `\n\n` 收尾。
- **为什么叫 `business-error` 而不是 `error`。** 注释写明「避免与标准 error 事件冲突」——`error` 是 `EventSource` 的内置事件名，用它会被浏览器的默认错误处理逻辑吃掉，前端拿不到自定义数据。
- **一定要补发 `done`。** 否则前端的「流结束」回调永远不触发，界面会一直转圈。注意这里的 `done` 帧 `data` 是 `{}`，而正常结束时是空字符串。
- **返回 `true` 时 handler 返回 `null`。** 看 `#L29-L31`：`if (handleSseError(...)) { return null; }`——已经手动写完响应了，不能再让 Spring 去序列化返回体。

**另一个「异常被吞掉」的地方**（`core/AiCodeGeneratorFacade.java#L154-L166`）：

```java
}).doOnComplete(() -> {
    // 流式返回完成后，保存代码
    try {
        String completeCode = codeBuilder.toString();
        Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
        File saveDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
        log.info("保存成功，目录为：{}", saveDir.getAbsolutePath());
    } catch (Exception e) {
        log.error("保存失败: {}", e.getMessage());
    }
});
```

**解析或落盘失败时只打日志，用户完全无感知**——SSE 流照样发 `done`，前端照样提示「生成成功」，但磁盘上没有代码。这是主链路里一个真实的可用性缺口。注意它用的是 `StringBuilder` 在 `doOnNext` 里累积全部文本（`#L150-L153`），**所以流式只是「给用户看」，落盘仍然要等全文收齐**。

### 2.5 动手验证

**准备。** 先按环节 1 的方法创建一个应用并记下 `appId`，登录态用 `-b cookie.txt` 带上。

**验证 1：观察 SSE 帧的真实格式（HTML 类型）。** 用 curl 的 `-N`（关闭缓冲）直接看原始字节流：

```bash
curl -N "http://localhost:8123/api/app/chat/gen/code?appId=你的appId&message=做一个个人主页" -b cookie.txt
```

**预期看到什么。** 一串这样的帧，逐帧吐出（不是一次性返回）：

```
data: {"d":"<!DOCTYPE html>"}

data: {"d":"\n<html lang=\"zh-CN\">"}

data: {"d":"\n<head>..."}

...

event: done
data: 
```

注意三点：① 每帧的 `data` 是 **JSON 对象**，里面才有真正的文本（双层解析）；② 帧与帧之间有空行；③ 最后一帧是 `event: done` 且 `data` 为空。**如果没有 `-N`，curl 会缓冲，看起来像一次性返回，会误判成「没走流式」。**

**验证 2：确认用户消息先于 AI 响应落库。** 在流还没结束时（生成大页面时很快就能观察到）另开一个终端查库：

```sql
select id, messageType, left(message, 30) as msg, createTime
from chat_history
where appId = 你的appId
order by id desc
limit 5;
```

**预期看到什么。** 对话进行中就能查到 `messageType = 'user'` 的那条记录；AI 记录要等流结束后才出现（由 `SimpleTextStreamHandler#L37-L41` 的 `doOnComplete` 写入）。这印证了 2.4 第 5 步的顺序设计。

**验证 3：确认错误帧的格式（业务异常）。** 用一个**不属于你的** `appId` 调用（或传 `appId=999999999`）：

```bash
curl -N "http://localhost:8123/api/app/chat/gen/code?appId=999999999&message=test" -b cookie.txt
```

**预期看到什么。** 注意：这里的异常发生在流建立**之前**（步骤 2/3），所以走的是 `businessExceptionHandler` → `handleSseError`，返回的是**手写的 SSE 文本**而不是 JSON：

```
event: business-error
data: {"code":40400,"error":true,"message":"应用不存在"}

event: done
data: {}
```

对比验证 1 的正常结束帧（`data: ` 为空）——**错误结束帧的 `data` 是 `{}`，两者不一致**，前端如果严格解析要留意。

**验证 4：确认 VUE_PROJECT 走的是另一条流，且结尾会卡住。** 创建一个 VUE_PROJECT 类型的应用（用环节 1 的第 ③ 个需求），然后：

```bash
curl -N "http://localhost:8123/api/app/chat/gen/code?appId=vueAppId&message=做一个带登录的后台管理" -b cookie.txt
```

**预期看到什么。** 与验证 1 完全不同：帧里会出现**三种** JSON（`ai_response` / `tool_request` / `tool_executed`，具体字段见环节 3），并且**文本生成完之后连接不会立刻关闭**——`onCompleteResponse` 里在同步跑 npm 构建，日志里能看到 `npm install` 相关输出，构建完成后才收到 `done`。**这是判断「你观察到的卡顿是 bug 还是设计」的关键证据。**

**验证 5：确认限流真的生效。** `@RateLimit(limitType = USER, rate = 5, rateInterval = 60)`（`AppController#L59`）表示同一用户 60 秒内最多 5 次。快速连发 6 次：

```bash
for i in $(seq 1 6); do
  curl -s -N "http://localhost:8123/api/app/chat/gen/code?appId=你的appId&message=t$i" -b cookie.txt | head -c 200
  echo "--- $i ---"
done
```

**预期看到什么。** 第 6 次返回 `AI 对话请求过于频繁，请稍后再试`。注意这是 `@Before` 切面在方法执行**之前**拦的（见环节 9），所以第 6 次**不会**产生任何 AI 调用，也不会写对话历史。

**验证 6：确认 `doFinally` 清理了 ThreadLocal。** 在 `AppServiceImpl#L114` 的 `clearContext()` 上加断点或临时日志，连续发两次请求。**预期看到什么。** 每次请求结束都会执行一次清理。如果注释掉这一行，多次请求后监控指标里的 `userId`/`appId` 会出现错配（环节 8 验证）。

### 2.6 自测题

1. 这个项目的 SSE 是用 WebFlux 实现的吗？请从 `pom.xml` 找证据说明。
2. 为什么 `chatToGenCode` 用 `@GetMapping` 而不是 `@PostMapping`？
3. 接口的真实 URL 是什么？为什么直接访问 `/app/chat/gen/code` 会 404？
4. SSE 帧里的 `data` 字段里装的是什么？为什么要包一层 `{"d": ...}`？
5. **（陷阱）** `SimpleTextStreamHandler` 上为什么没有 `@Component`？项目里存在 `StreamHandler` 接口吗？
6. `Flux<String>` 和 `TokenStream` 分别用在哪种生成类型上？为什么 VUE_PROJECT 不能用 `Flux<String>`？
7. `processTokenStream` 里为什么用 `Flux.create(sink -> ...)` 而不是 `map`/`flatMap`？
8. `onPartialToolExecutionRequest` 是 LangChain4j 的官方 API 吗？如果不是，它从哪来？升级 LangChain4j 会有什么后果？
9. **（陷阱）** VUE_PROJECT 模式下，代码生成完之后 SSE 连接为什么还会挂着几分钟？这样做的好处和风险各是什么？
10. `AppServiceImpl#chatToGenCode` 里，为什么「保存用户消息」要放在调用 AI **之前**？如果放到 `doOnComplete` 里会有什么问题？
11. `MonitorContextHolder.clearContext()` 为什么放在 `doFinally` 而不是 `doOnComplete`？如果完全不清理会怎样？
12. **（陷阱）** `AiCodeGeneratorFacade#processCodeStream` 里解析/落盘失败时，用户会看到什么？这是不是一个 bug？
13. `handleSseError` 里判断「是不是 SSE 请求」用了哪两个条件？为什么需要两个？
14. 错误帧的事件名叫 `business-error`，为什么不直接叫 `error`？错误帧之后为什么还要补发一个 `done`？
15. 正常结束帧是 `event: done` + `data:`（空），错误结束帧是 `event: done` + `data: {}`。这个不一致会带来什么问题？
16. 权限校验为什么用 `app.getUserId().equals(loginUser.getId())` 而不是 `==`？
17. `@RateLimit` 拦截请求时，会不会消耗一次 AI 调用额度、会不会写入对话历史？

<details>
<summary>参考答案（先自己答完再展开）</summary>

1. **不是。** `pom.xml#L33-L36` 只有 `spring-boot-starter-web`（Servlet 栈），**没有 `spring-boot-starter-webflux`**。能返回 `Flux` 是因为 `pom.xml#L63-L67` 的 `langchain4j-reactor` 把 `reactor-core` 带进了 classpath，Spring MVC 便能把 `Flux` 返回值适配成 SSE 输出。
2. 因为前端用 `EventSource`，而 `EventSource` **原生只支持 GET**。为了让前端不改用 fetch + ReadableStream 手写 SSE 解析，接口被设计成 GET + query 参数。
3. `GET http://localhost:8123/api/app/chat/gen/code`。因为 `application.yml#L28` 配了 `server.servlet.context-path: /api`，所有路径都自动带这个前缀。
4. 装的是**上游流推出的原始内容**——HTML/MULTI_FILE 模式下是模型输出的代码文本片段；VUE_PROJECT 模式下是一条条 JSON 字符串（`ai_response` / `tool_request` / `tool_executed`）。包一层 `{"d": ...}` 是自定义协议：外层 `data:` 是 SSE 协议要求，内层 JSON 是业务信封，前端需要 `JSON.parse` 两次才能拿到文本。
5. **没有 `StreamHandler` 接口。** 项目里只有 `StreamHandlerExecutor`、`SimpleTextStreamHandler`、`JsonMessageStreamHandler` 三个类，后两者**没有共同父类型**，靠 `StreamHandlerExecutor#L37-L42` 的 `switch` 分派。`SimpleTextStreamHandler` 不标 `@Component` 是因为它没有任何依赖（只用传入的参数），`new` 出来即可——类注释也写明了「简单文本处理器不需要依赖注入」。这跟 `core/parser`、`core/saver` 都有接口的风格**不一致**，容易误判。
6. `Flux<String>` 用于 HTML 和 MULTI_FILE（`generateHtmlCodeStream` / `generateMultiFileCodeStream`）；`TokenStream` 用于 VUE_PROJECT（`generateVueProjectCodeStream`）。VUE_PROJECT 不能用 `Flux<String>` 是因为它要传递**三类事件**（文本增量、工具调用请求、工具执行结果），`Flux<String>` 只能承载文本，表达不了「模型正在调用 FileWriteTool」这种结构化信息——而这恰恰是 VUE_PROJECT 模式下用户最需要看到的进度反馈。
7. 因为 `TokenStream` 不是 Reactor 类型，它是一套**回调式** API（`onPartialResponse`/`onToolExecuted`/...）。要在回调里把数据推给 Reactor，必须拿到 `sink` 引用，而 `sink` 只能在 `Flux.create` 的 lambda 参数里拿到。`map`/`flatMap` 是「有上游、做转换」的语义，这里根本没有 Reactor 上游，所以只能用 `create` 手动驱动。链尾的 `.start()` 是「注册完毕，开始执行」。
8. **不是官方 API。** 它由本项目在 `src/main/java/dev/langchain4j/` 下打的源码级补丁提供（覆盖了 `TokenStream`、`StreamingChatResponseHandler`、`OpenAiStreamingChatModel` 等 10 个类）。因为补丁类在 `src/main/java` 下，编译时会**覆盖**依赖 jar 里的同名类。后果：① 直接升级 LangChain4j 版本，`AiCodeGeneratorFacade#L118` 编译不过；② 补丁类与新版官方类的其他改动会丢失（相当于被迫锁版本）。
9. 因为 `onCompleteResponse` 回调里**同步执行**了 `vueProjectBuilder.buildProject(...)`，里面是 `npm install`（超时 300s）+ `npm build`（超时 180s），跑完才 `sink.complete()`。好处：前端收到 `done` 时项目已经构建好，预览不会白屏。风险：① 长连接被 Nginx/网关按 idle timeout 切断；② 这段时间占用一个 Tomcat 工作线程；③ 用户看到「生成完了但界面还在转圈」，体验上容易被当成卡死。
10. 为了保证**「用户说过什么」不依赖 AI 是否成功**。如果放在 `doOnComplete`，一旦 AI 调用失败（超时、限流、模型报错），这轮提问就永久丢失，用户刷新后对话历史里没有这条消息，体验上是「我明明发了但系统不认」。放前面则保证提问必留痕，代价是失败时会留下一条没有 AI 回复的孤立用户消息。
11. 因为 `doFinally` 覆盖**完成、异常、取消**三种终止信号，而 `doOnComplete` 只在正常完成时触发。如果 AI 报错或用户提前断开（前端关闭 EventSource），`doOnComplete` 不执行，`ThreadLocal` 就残留了。**完全不清理的后果**：Servlet 线程池复用线程，下一个请求（可能是另一个用户）会读到上一个用户的 `MonitorContext`，导致监控指标里的 `userId`/`appId` 张冠李戴——这是典型的 ThreadLocal 泄漏。
12. **用户会看到「生成成功」**：`doOnComplete` 里的异常被 `catch (Exception e) { log.error(...) }` 吞掉了，SSE 流照常发 `done`，前端照常提示成功。但**磁盘上没有任何代码文件**，用户点预览会 404。这**算一个真实的可用性缺口**（严格说是「静默失败」而非「数据错误」）：至少应该把失败信号推给前端，而不是只记日志。
13. 两个条件：① 请求头 `Accept` 包含 `text/event-stream`；② 请求 URI 包含 `/chat/gen/code`。需要两个是因为**并非所有客户端都会带 `Accept` 头**（例如 curl 默认 `Accept: */*`），这时只能靠 URL 路径兜底识别。反过来说，这个 `uri.contains` 是硬编码的路径片段——如果改了接口路径，这里必须同步改，否则错误处理会退化成返回 JSON，前端解析失败。
14. 因为 `error` 是 `EventSource` 的**内置事件名**，浏览器会用默认逻辑处理它，前端的自定义监听拿不到 payload。用 `business-error` 是为了走「自定义事件」通道，前端可以 `addEventListener('business-error', ...)` 拿到 `{error, code, message}`。补发 `done` 是因为前端的「流结束」逻辑统一挂在 `done` 事件上——不发的话界面会一直转圈。
15. 前端如果**统一按 `event: done` 收尾并解析其 `data`**，正常结束时空字符串 `""` 解析成 JSON 会抛异常（`JSON.parse("")` 报错），错误结束时 `{}` 反而正常。稳妥做法是判断 `data` 是否为空再解析，或统一成 `{}`。这属于接口协议层面的小瑕疵，实际影响取决于前端实现。
16. 因为 `getUserId()` 返回的是 `Long`（包装类型）。`==` 比较的是**对象引用**，两个数值相同但不同实例的 `Long` 会判为 `false`（除了 `-128~127` 的缓存区间会「碰巧」相等）。用 `equals` 才是比值。这是 Java 包装类型的经典陷阱。
17. **都不会。** `@RateLimit` 是 `@Before` 切面（`ratelimter/aspect/RateLimitAspect`），在方法执行**之前**拦截并抛异常，所以 `chatToGenCode` 根本没被调用：不产生 AI 调用、不写对话历史、不落盘。而且它抛出的异常发生在流建立前，会走 `handleSseError` 返回 `business-error` 帧。

</details>

### 2.7 本环节技术点清单

| 技术点 | 落地位置 | 作用 | 项目特有之处 |
|---|---|---|---|
| Spring MVC 上的 SSE | `controller/AppController.java#L58-L85` | 流式返回 | **Servlet 栈** + `Flux` 返回类型，靠 `langchain4j-reactor` 带入的 `reactor-core`，**无 WebFlux** |
| `ServerSentEvent` 构造 | `AppController#L74-L84` | 生成 SSE 帧 | 每帧 `data` 内层再包 `{"d": ...}` |
| `concatWith` 追加结束帧 | `AppController#L78-L84` | 通知前端结束 | `event: done`，正常结束 `data` 为空 |
| 两级流模型 | `AiCodeGeneratorFacade#L85-L102` | 类型分流 | `Flux<String>`（HTML/MULTI_FILE）vs `TokenStream`（VUE_PROJECT） |
| `TokenStream` → `Flux` 适配 | `AiCodeGeneratorFacade#L112-L138` | 桥接两套流 API | `Flux.create` 手动驱动 + `.start()`；依赖**非官方** `onPartialToolExecutionRequest` |
| 流结束挂业务回调 | `processCodeStream#L154-L166`、`SimpleTextStreamHandler#L37-L46` | 落盘、存历史 | **异常被吞**，只记日志 |
| `doFinally` 清理 ThreadLocal | `AppServiceImpl#L112-L115` | 防监控上下文泄漏 | 覆盖完成/异常/取消三种终止 |
| 先落用户消息 | `AppServiceImpl#L100` | 提问必留痕 | 顺序刻意，不依赖 AI 成功 |
| SSE 错误帧手写 | `GlobalExceptionHandler#L53-L94` | 流式接口的错误通道 | `business-error` 自定义事件 + 补发 `done`；靠 `Accept` 头或 URI 双条件判定 |
| `@RateLimit` 前置拦截 | `AppController#L59` | 防刷 | `@Before` 切面，拦截时**不消耗** AI 调用 |
| 同步 npm 构建挂在流尾 | `AiCodeGeneratorFacade#L126-L131` | 保证预览就绪 | 长连接延长数分钟，占 Tomcat 线程 |

### 2.8 本环节产出

这条主链路把「一次可能持续几分钟、中途还要调工具、最后还要跑 npm 构建的大模型调用」包装成了一条响应式流：Controller 用 `produces = text/event-stream` 声明协议、把每个数据块封成 `{"d": ...}`、在末尾补一帧 `event: done`；Service 在流的**前面**插入「先存用户消息 + 设置监控上下文」，在流的**后面**挂 `doFinally` 清理 `ThreadLocal`；`AiCodeGeneratorFacade` 按生成类型分成两条流——HTML/MULTI_FILE 用 `Flux<String>` 直通，VUE_PROJECT 用 `TokenStream` 承接工具调用事件再手工桥接成 `Flux`；最后 `StreamHandlerExecutor` 按类型选处理器落历史。整条链路最该记住的三个点：**它是 Servlet 栈上的 SSE（不是 WebFlux）**、**VUE_PROJECT 的流尾挂着同步 npm 构建（卡顿是设计不是 bug）**、**有两处异常被静默吞掉（落盘失败、`printStackTrace`）**。读到这里应该能回答：**「前端看到的每一个字，是从哪个类的哪一行推出去的」**——答案是 `AiCodeGeneratorFacade` 里 `sink.next(...)`，以及 `AppController` 里那个 `.map(chunk -> Map.of("d", chunk))`。

---

## 环节 3：流式工具调用与文件读写工具集

### 3.1 这个环节解决什么问题

HTML 和 MULTI_FILE 两种生成类型只需要模型「吐文本」，后端把整段文本捞回来正则解析、落盘即可（见环节 5）。但 VUE_PROJECT 要生成的是一个**能跑的 Vue 工程**——`index.html` + `package.json` + `vite.config.js` + `src/main.js` + `src/App.vue` + 一堆组件，几十个文件。

让模型一次性把几十个文件塞进一段文本里，格式几乎必崩。于是本项目换了一条路：**模型不输出文件内容，而是发出「工具调用请求」，由后端的 Java 代码真正去写文件。**

这带来三个必须解决的问题，就是本环节的全部内容：

1. **工具怎么定义、怎么让模型知道有这些工具** —— LangChain4j 的 `@Tool` / `@P` / `@ToolMemoryId` + `AiServices.builder().tools(...)`。
2. **模型说「我要写文件」这件事，用户在前端要看得见** —— 把工具调用过程转成三类 JSON 事件，混在 SSE 流里推出去。
3. **工具写文件的目录隔离** —— 每个应用写到自己的 `vue_project_{appId}` 目录，靠 `@ToolMemoryId` 把 `appId` 传进工具方法。

> 一句话：**HTML/MULTI_FILE 是「模型产出代码文本，后端解析」；VUE_PROJECT 是「模型指挥后端写代码」。** 这个分叉是理解整个项目 AI 层的关键。

### 3.2 技术点一：工具定义三件套与自动注册

**1）三个注解决定「模型能看到什么」**

工具方法定义在 `ai/tools/FileWriteTool.java#L26-L33`：

```java
@Tool("写入文件到指定路径")
public String writeFile(
        @P("文件的相对路径")
        String relativeFilePath,
        @P("要写入文件的内容")
        String content,
        @ToolMemoryId Long appId
) {
```

| 注解 | 来源 | 作用 | 本项目里的特殊之处 |
|---|---|---|---|
| `@Tool("描述")` | `dev.langchain4j.agent.tool.Tool` | 标注工具方法，描述会作为 Function Calling 的 schema 发给模型 | 描述是**中文**，直接决定模型选不选这个工具；描述里要写清「做什么」，不要写「怎么做」 |
| `@P("描述")` | `dev.langchain4j.agent.tool.P` | 标注参数描述，进 schema | 每个参数都写了中文描述 |
| `@ToolMemoryId` | `dev.langchain4j.agent.tool.ToolMemoryId` | 把 AI Service 方法上的 `@MemoryId` 值**注入**到工具方法参数 | **本项目工具目录隔离的唯一通道**，见下 |

`@ToolMemoryId` 是这里最值得讲的一点。AI Service 接口的声明是 `ai/AiCodeGeneratorService.java#L55-L56`：

```java
@SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
TokenStream generateVueProjectCodeStream(@MemoryId long appId, @UserMessage String userMessage);
```

调用方传进来的 `appId`（`AiCodeGeneratorFacade#L95`）会被 LangChain4j 记作 memoryId；工具方法参数上写了 `@ToolMemoryId Long appId`，LangChain4j 就把这个值塞进去。**模型从头到尾不知道、也无法伪造 appId**——这是目录隔离能成立的前提。

**2）注册：一个 `@Resource BaseTool[]` 就够**

`ai/tools/ToolManager.java#L22-L40`：

```java
private final Map<String, BaseTool> toolMap = new HashMap<>();

@Resource
private BaseTool[] tools;          // Spring 按类型注入所有 BaseTool 子类

@PostConstruct
public void initTools() {
    for (BaseTool tool : tools) {
        toolMap.put(tool.getToolName(), tool);
        log.info("注册工具: {} -> {}", tool.getToolName(), tool.getDisplayName());
    }
    log.info("工具管理器初始化完成，共注册 {} 个工具", toolMap.size());
}
```

注意这里的**双重身份**：工具类既是 Spring Bean（`@Component`，所以能被 `BaseTool[]` 收集），又是 LangChain4j 工具（有 `@Tool` 方法，所以能被 `AiServices` 识别）。两者靠 `getAllTools()` 返回实例数组打通。

这带来一个很好的扩展性：**新增一个工具，只需要写一个 `@Component` 类 extends `BaseTool` 并加 `@Tool` 方法，不需要改任何配置**。但反过来也有两个隐患：

- `toolMap.put` 不校验重复（`ToolManager#L36`），两个类返回同名 `getToolName()` 会**静默覆盖**，只保留后注册的那个（Spring 数组顺序不确定）。
- `getAllTools()` 返回的是**全部** `BaseTool`，所以「给模型几个工具」这件事是全局的，没法按 appId 或生成类型裁剪。

**3）工具只在 VUE_PROJECT 分支注册**

`ai/AiCodeGeneratorServiceFactory.java#L101-L131` 的 `switch` 是本环节最容易看漏的地方：

| 生成类型 | 注册工具 | 使用的模型 Bean | 配置前缀（`application.yml`） | 实际模型 |
|---|---|---|---|---|
| `VUE_PROJECT` | **有** `.tools(toolManager.getAllTools())`（L110） | `reasoningStreamingChatModelPrototype`（L105） | `langchain4j.open-ai.reasoning-streaming-chat-model` | `deepseek-reasoner`，max-tokens 32768，temperature 0.1 |
| `HTML` / `MULTI_FILE` | **没有**（L125-L131 整段没有 `.tools(...)`） | `streamingChatModelPrototype`（L124） | `langchain4j.open-ai.streaming-chat-model` | `deepseek-chat`，max-tokens 8192 |

所以「HTML 模式能不能调工具」的答案是**不能**，不是模型不想调，而是 `AiServices` 根本没注册工具 schema，模型压根不知道有工具存在。

顺带记两个 VUE_PROJECT 分支上的防护配置（`AiCodeGeneratorServiceFactory#L112-L116`）：

```java
// 处理工具调用幻觉问题
.hallucinatedToolNameStrategy(toolExecutionRequest ->
        ToolExecutionResultMessage.from(toolExecutionRequest,
                "Error: there is no tool called " + toolExecutionRequest.name())
)
.maxSequentialToolsInvocations(20)  // 最多连续调用 20 次工具
```

- `hallucinatedToolNameStrategy`：模型编了一个不存在的工具名（如 `write_file` 而不是 `writeFile`）时，不抛异常，而是回一条 `Error: there is no tool called xxx` 的**工具执行结果消息**，让模型自己纠正。这是「工具调用幻觉」的标准兜底。
- `maxSequentialToolsInvocations(20)`：连续工具调用的硬上限。为什么需要它，见 3.4 里的 `ExitTool`。

### 3.3 技术点二：工具调用事件如何变成三类流消息

**1）三种消息类型**

`ai/model/message/StreamMessageTypeEnum.java#L11-L13` 定义了 VUE_PROJECT 流里的全部消息类型：

```java
AI_RESPONSE("ai_response", "AI响应"),
TOOL_REQUEST("tool_request", "工具请求"),
TOOL_EXECUTED("tool_executed", "工具执行结果");
```

对应的三个 POJO 都继承 `StreamMessage`（只带一个 `type` 字段，`ai/model/message/StreamMessage.java#L13-L18`）：

| 类 | 额外字段 | 构造来源 | 用途 |
|---|---|---|---|
| `AiResponseMessage` | `data` | 直接传字符串（`AiResponseMessage#L17-L20`） | 模型输出的**文本增量**（生成计划、结尾提示） |
| `ToolRequestMessage` | `id` / `name` / `arguments` | `ToolExecutionRequest`（`ToolRequestMessage#L22-L27`） | 模型**想调用**某个工具 |
| `ToolExecutedMessage` | `id` / `name` / `arguments` / `result` | `ToolExecution`（`ToolExecutedMessage#L24-L30`） | 工具**已执行完**，带结果 |

**2）三个回调 → 三条 JSON**

`core/AiCodeGeneratorFacade.java#L112-L138`：

```java
private Flux<String> processTokenStream(TokenStream tokenStream, Long appId) {
    return Flux.create(sink -> {
        tokenStream.onPartialResponse((String partialResponse) -> {
                    AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                    sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                })
                .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                    ToolRequestMessage toolRequestMessage = new ToolRequestMessage(toolExecutionRequest);
                    sink.next(JSONUtil.toJsonStr(toolRequestMessage));
                })
                .onToolExecuted((ToolExecution toolExecution) -> {
                    ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                    sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
                })
                // ... onCompleteResponse / onError / start()
    });
}
```

`index` 参数被**丢弃**了——只有 `toolExecutionRequest` 被包进消息。这个细节后面会解释为什么。

**3）`onPartialToolExecutionRequest` 是补丁 API，完整链路在这里**

`dev/langchain4j/service/TokenStream.java#L30` 声明了这个方法：

```java
TokenStream onPartialToolExecutionRequest(BiConsumer<Integer, ToolExecutionRequest> toolExecutionRequestHandler);
```

**它不是 LangChain4j 官方 API**，是项目在 `src/main/java/dev/langchain4j/` 下打的源码级补丁（补丁类在 `src/main/java` 下，编译产物会覆盖依赖 jar 里的同名类）。触发链路如下：

| 步骤 | 类 / 行 | 做什么 |
|---|---|---|
| 1 | `dev/langchain4j/service/TokenStream.java#L30` | 接口声明（补丁新增） |
| 2 | `dev/langchain4j/service/AiServiceTokenStream.java#L80-L83` | 保存 handler 引用 |
| 3 | `dev/langchain4j/service/AiServiceStreamingResponseHandler.java#L109-L112` | 转发给 `partialToolExecutionRequestHandler` |
| 4 | `dev/langchain4j/model/chat/StreamingChatModel.java#L54-L56` | 默认实现转发（兼容其他模型实现） |
| 5 | `dev/langchain4j/model/openai/OpenAiStreamingChatModel.java#L182-L213` | **真正触发点**：遍历 `delta.toolCalls()`，参数非空就构造 `ToolExecutionRequest` 回调 |

第 5 步的关键代码（`OpenAiStreamingChatModel#L199-L209`）：

```java
String partialArguments = toolCall.function().arguments();
if (isNotNullOrEmpty(partialArguments)) {
    toolBuilder.appendArguments(partialArguments);

    ToolExecutionRequest partialToolExecutionRequest = ToolExecutionRequest.builder()
            .id(id)
            .name(name)
            .arguments(partialArguments)   // 注意：是本次增量，不是累计
            .build();
    handler.onPartialToolExecutionRequest(index, partialToolExecutionRequest);
}
```

**这是本环节最重要的一个坑**：回调里的 `arguments` 是**本次 SSE delta 的片段**，不是累计后的完整 JSON。比如模型分 3 次吐出 `{"relativeFilePath":"src/App.vue","content":"<temp`、`late>...`、`"}`，那 `onPartialToolExecutionRequest` 会被调用 3 次，三次的 `arguments` 分别是 `{"relativeFilePath":"src/App.vue","content":"<temp`、`late>...`、`"}`——**单独任何一次都不是合法 JSON**。

所以前端拿到 `tool_request` 只能用来显示「AI 正在调用 写入文件」，**不能解析参数**。真正可解析的参数只出现在 `tool_executed` 消息里（`ToolExecutedMessage#L28` 取自 `toolExecution.request().arguments()`，那是累积完整版）。

**4）两个补丁遗留问题**

- `dev/langchain4j/model/openai/OpenAiStreamingResponseBuilder.java#L113` 有一行 `System.out.println("OLOLO " + delta.toolCalls()); // TODO` —— 补丁调试时留下的打印，每次工具调用都会往控制台吐。不影响功能，属代码异味。
- 同文件 `#L39` 用 `Map<Integer, ToolExecutionRequestBuilder>` 按 `toolCall.index()` 聚合，`#L200-L210` 在 build 时按 index 遍历成列表——这是 OpenAI 流式 tool_calls 的标准聚合方式。

### 3.4 代码走读：从 `TokenStream` 到落库的一条消息

**主角：`core/handler/JsonMessageStreamHandler.java`**

它由 `StreamHandlerExecutor#L37-L42` 的 `switch` 在 `VUE_PROJECT` 分支被选中（`HTML`/`MULTI_FILE` 走 `SimpleTextStreamHandler`，见环节 2）。

`handle` 方法（`JsonMessageStreamHandler#L41-L64`）：

```java
public Flux<String> handle(Flux<String> originFlux,
                           ChatHistoryService chatHistoryService,
                           long appId, User loginUser) {
    StringBuilder chatHistoryStringBuilder = new StringBuilder();
    Set<String> seenToolIds = new HashSet<>();
    return originFlux
            .map(chunk -> handleJsonMessageChunk(chunk, chatHistoryStringBuilder, seenToolIds))
            .filter(StrUtil::isNotEmpty)   // 过滤空字串
            .doOnComplete(() -> {
                String aiResponse = chatHistoryStringBuilder.toString();
                chatHistoryService.addChatMessage(appId, aiResponse,
                        ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
            })
            .doOnError(error -> {
                String errorMessage = "AI回复失败: " + error.getMessage();
                chatHistoryService.addChatMessage(appId, errorMessage,
                        ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
            });
}
```

三个细节值得记住：

1. `chatHistoryStringBuilder` 是**闭包里的可变状态**，在 `map` 里被逐块 append。所以它既是「推给前端的流」，也是「要落库的完整文本」——一份数据两个出口。
2. `doOnError` 会把**错误信息当成 AI 回复落库**。用户下次进来会看到历史里有一条「AI回复失败: xxx」，这是刻意的（让失败可见），但也意味着历史里会混入非 AI 生成的内容。
3. 这里的落库是 `VUE_PROJECT` 专属的；`HTML`/`MULTI_FILE` 的落库在 `SimpleTextStreamHandler` 里。

**逐块解析：`handleJsonMessageChunk`（`#L69-L115`）**

```java
StreamMessage streamMessage = JSONUtil.toBean(chunk, StreamMessage.class);
StreamMessageTypeEnum typeEnum = StreamMessageTypeEnum.getEnumByValue(streamMessage.getType());
switch (typeEnum) {
    case AI_RESPONSE -> {
        AiResponseMessage aiMessage = JSONUtil.toBean(chunk, AiResponseMessage.class);
        String data = aiMessage.getData();
        chatHistoryStringBuilder.append(data);   // 进历史
        return data;                             // 进流
    }
    case TOOL_REQUEST -> {
        ToolRequestMessage toolRequestMessage = JSONUtil.toBean(chunk, ToolRequestMessage.class);
        String toolId = toolRequestMessage.getId();
        String toolName = toolRequestMessage.getName();
        if (toolId != null && !seenToolIds.contains(toolId)) {
            seenToolIds.add(toolId);
            BaseTool tool = toolManager.getTool(toolName);
            return tool.generateToolRequestResponse();   // 只进流，不进历史
        } else {
            return "";                                   // 重复的丢弃
        }
    }
    case TOOL_EXECUTED -> {
        ToolExecutedMessage toolExecutedMessage = JSONUtil.toBean(chunk, ToolExecutedMessage.class);
        JSONObject jsonObject = JSONUtil.parseObj(toolExecutedMessage.getArguments());
        String toolName = toolExecutedMessage.getName();
        BaseTool tool = toolManager.getTool(toolName);
        String result = tool.generateToolExecutedResult(jsonObject);
        String output = String.format("\n\n%s\n\n", result);
        chatHistoryStringBuilder.append(output);   // 进历史
        return output;                             // 进流
    }
    default -> {
        log.error("不支持的消息类型: {}", typeEnum);
        return "";
    }
}
```

四个要点：

- **`seenToolIds` 是必需的**。因为 `onPartialToolExecutionRequest` 每个 delta 触发一次（3.3 已说明），同一个工具调用会被推 N 次。没有这个 `Set`，用户会看到满屏重复的「[选择工具] 写入文件」。
- **`TOOL_REQUEST` 的展示文本只进流、不进历史**（`#L92` 直接 return，没有 append）。历史里只保留「实际写了什么文件」（`TOOL_EXECUTED` 的 output），不保留「模型想调工具」这个动作。这是刻意的：历史是给模型读的，塞进「[选择工具] 写入文件」这种话反而干扰。
- **`default ->` 分支实际不可达**。Java 对 `switch` 的枚举 selector 会先做 `selector.ordinal()`，若 `typeEnum` 为 `null` 会**直接抛 NPE**，不会走到 `default`。也就是说：如果补丁将来新增了消息类型，这里不是「打日志跳过」，而是整个流崩掉。这是个真实的防御缺口（`#L72-L73` 没有判空）。
- **`toolManager.getTool(toolName)` 没有判空**（`#L90`、`#L103`）。如果模型幻觉出一个不存在的工具名，`tool` 为 `null`，`tool.generateToolRequestResponse()` 直接 NPE。注意：`hallucinatedToolNameStrategy` 只能兜住**执行阶段**的幻觉，兜不住这里——因为 `tool_request` 事件是在**模型产出阶段**就推出去的，那时 LangChain4j 还没做工具名校验。**这是本环节第二个真实的可用性缺口。**

**工具基类：`ai/tools/BaseTool.java`**

```java
public abstract String getToolName();                                 // 英文名，对应方法名
public abstract String getDisplayName();                              // 中文名，给用户看
public String generateToolRequestResponse() {                         // 有默认实现
    return String.format("\n\n[选择工具] %s\n\n", getDisplayName());
}
public abstract String generateToolExecutedResult(JSONObject arguments);  // 执行结果格式化
```

`getToolName()` 的返回值必须与 `@Tool` 方法的**方法名一致**（`FileWriteTool#getToolName()` 返回 `"writeFile"`），因为 `JsonMessageStreamHandler` 是拿模型回调里的 `name` 去 `toolMap` 里查的——写错了就查不到，NPE。

**七个工具逐一过一遍**

| 工具 | 类 / 行 | `getToolName()` | `@Tool` 描述 | 关键实现 |
|---|---|---|---|---|
| 写入文件 | `ai/tools/FileWriteTool.java#L26-L59` | `writeFile` | 写入文件到指定路径 | 相对路径拼到 `vue_project_{appId}`；`Files.createDirectories` 建父目录；`CREATE + TRUNCATE_EXISTING` 覆盖写；**返回相对路径**（`#L52-L53` 注释：不能让 AI 把绝对路径回给用户） |
| 修改文件 | `ai/tools/FileModifyTool.java#L25-L61` | `modifyFile` | 修改文件内容，用新内容替换指定的旧内容 | `contains(oldContent)` 先校验（`#L46-L48`）→ `replace` 替换（`#L49`）；替换后无变化返回提示（`#L50-L52`） |
| 读取文件 | `ai/tools/FileReadTool.java#L24-L46` | `readFile` | 读取指定路径的文件内容 | 直接 `Files.readString` 返回全文 |
| 读取目录 | `ai/tools/FileDirReadTool.java#L42-L84` | `readDir` | 读取目录结构，获取指定目录下的所有文件和子目录信息 | `FileUtil.loopFiles` 递归 + `shouldIgnore` 过滤；按深度排序 + 缩进输出 |
| 删除文件 | `ai/tools/FileDeleteTool.java#L24-L56` | `deleteFile` | 删除指定路径的文件 | 三重校验：存在性（`#L37-L39`）→ 是普通文件（`#L40-L42`）→ 不在重要文件白名单（`#L44-L47`） |
| 退出 | `ai/tools/ExitTool.java#L31-L35` | `exit` | 当任务已完成或无需继续调用工具时，使用此工具退出操作，防止循环 | **无参数**，返回「不要继续调用工具，可以输出最终结果了」 |

**路径安全：三个工具都有同一个缺口**

`FileWriteTool#L35-L41`、`FileModifyTool#L36-L41`、`FileReadTool#L31-L36`、`FileDirReadTool#L49-L54`、`FileDeleteTool#L31-L36` 全都是一样的模式：

```java
Path path = Paths.get(relativeFilePath);
if (!path.isAbsolute()) {
    // 相对路径处理，创建基于 appId 的项目目录
    String projectDirName = "vue_project_" + appId;
    Path projectRoot = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName);
    path = projectRoot.resolve(relativeFilePath);
}
```

注意这个 `if (!path.isAbsolute())` 的**反向逻辑**：只要模型传的是**绝对路径**，`if` 不成立，`path` 保持原样——工具就会直接操作那个绝对路径。

也就是说：

- 模型可以传 `D:/somewhere/important.txt`，工具就真去写那儿。
- 模型可以传 `../../../../etc/passwd`（相对路径），`resolve` 之后虽然起点是 `vue_project_{appId}`，但 `..` 会往上跳，**没有做 `normalize()` + `startsWith(projectRoot)` 的穿越校验**。

`FileDeleteTool` 有一层白名单（`#L61-L74`）保护了 15 个文件：

```java
private boolean isImportantFile(String fileName) {
    String[] importantFiles = {
            "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
            "vite.config.js", "vite.config.ts", "vue.config.js",
            "tsconfig.json", "tsconfig.app.json", "tsconfig.node.json",
            "index.html", "main.js", "main.ts", "App.vue", ".gitignore", "README.md"
    };
    for (String important : importantFiles) {
        if (important.equalsIgnoreCase(fileName)) {
            return true;
        }
    }
    return false;
}
```

但这是**按文件名比对**的（`equalsIgnoreCase(fileName)`），保护的是「名字叫 `App.vue` 的文件」，而不是「项目根目录下的 `App.vue`」——`src/components/App.vue` 同样会被保护，而 `src/App.vue` 之外的重名文件也一并被保护。**粒度不准，但方向是对的**：至少 `index.html`、`main.js`、`package.json` 这几个删了项目就跑不起来的文件被拦住了。

> 结论：**工具的路径校验是「约定式」的，不是「强制式」的。** 它依赖模型听话（提示词里写了用相对路径），而不是靠代码保证。这是本项目 AI 层最值得指出的一处安全债。

**`FileDirReadTool` 的忽略清单（`#L30-L40`）**

```java
private static final Set<String> IGNORED_NAMES = Set.of(
        "node_modules", ".git", "dist", "build", ".DS_Store",
        ".env", "target", ".mvn", ".idea", ".vscode", "coverage"
);
private static final Set<String> IGNORED_EXTENSIONS = Set.of(
        ".log", ".tmp", ".cache", ".lock"
);
```

这份清单是**必需的**：不排除 `node_modules`，`readDir` 一次要返回几万个文件路径，直接把模型的上下文打爆（VUE_PROJECT 的 max-tokens 是 32768，`node_modules` 光路径就远超）。**这是「工具返回结果要控制体积」的一个具体实践。**

**`ExitTool`：为什么需要一个「退出」工具**

工具调用是**循环**的：模型调 `writeFile` → 后端返回「写入成功」→ 模型看到结果继续决定下一步。如果模型一直觉得「还有事没做完」，就会一直调工具。

`ExitTool#L31-L35` 给了模型一个明确的「我干完了」出口：

```java
@Tool("当任务已完成或无需继续调用工具时，使用此工具退出操作，防止循环")
public String exit() {
    log.info("AI 请求退出工具调用");
    return "不要继续调用工具，可以输出最终结果了";
}
```

它和 `maxSequentialToolsInvocations(20)`（`AiCodeGeneratorServiceFactory#L116`）是**双保险**：前者是「引导模型自己停」，后者是「模型不停就强制停」。两个都要有——只靠引导，模型可能不听话；只靠强制，会在正常生成到一半时被切断（20 次对 30 个文件的项目是够的，但边界很紧）。

**提示词侧的配合：`prompt/codegen-vue-project-system-prompt.txt`**

工具描述只告诉模型「有这个工具」，什么时候用、怎么用要靠 system prompt：

- `#L96`：`1）必须通过使用【文件写入工具】依次创建每个文件（而不是直接输出文件代码）。` —— **这一条是 VUE_PROJECT 能跑通的前提**。不写这句，模型会习惯性地把代码贴在回复里，一个文件都不会写。
- `#L107`：`5）输出的总 token 数必须小于 20000，文件总数量必须小于 30 个` —— 直接约束工具调用次数上限（30 个文件 < 20 次连续调用？不完全等价，但意图一致）。
- `#L119-L126`：修改场景的固定流程 —— 先【目录读取工具】看结构 → 再【文件读取工具】看内容 → 再用【文件修改工具】/【文件写入工具】/【文件删除工具】改。

注意提示词里用的是中文工具名（【文件写入工具】），而 `@Tool` 的描述是「写入文件到指定路径」——两者字面**不完全一致**，靠语义对应。这是个小瑕疵：如果描述改成「文件写入工具」，模型关联会更准。

### 3.5 动手验证

**验证 1：看流里到底有哪几种消息**

用 PowerShell 起一个 SSE 连接（`curl.exe` 避免被 PowerShell 的 `curl` 别名劫持）：

```powershell
curl.exe -N "http://localhost:8123/api/app/chat/gen/code?appId=1&message=做一个待办清单网站"
```

**预期看到**（`data:` 后面是被包了一层的 JSON，外层 `{"d": ...}` 是环节 2 说的业务信封）：

```text
data:{"d":"{\"type\":\"ai_response\",\"data\":\"好的，我来为你规划\"}"}
data:{"d":"{\"type\":\"tool_request\",\"id\":\"call_00_xxx\",\"name\":\"writeFile\",\"arguments\":\"{\\\"relat\"}"}
data:{"d":"{\"type\":\"tool_request\",\"id\":\"call_00_xxx\",\"name\":\"writeFile\",\"arguments\":\"iveFilePath\\\":\\\"package.json\\\"\"}"}
data:{"d":"{\"type\":\"tool_executed\",\"id\":\"call_00_xxx\",\"name\":\"writeFile\",\"arguments\":\"{\\\"relativeFilePath\\\":\\\"package.json\\\",\\\"content\\\":\\\"{...}\\\"}\",\"result\":\"文件写入成功: package.json\"}"}
...
event:done
data:
```

要盯住三件事：

1. 同一个 `id` 的 `tool_request` **出现多次**（上面例子里 `call_00_xxx` 出现了 2 次），且每次 `arguments` 都是**半截 JSON**。
2. `tool_executed` 里的 `arguments` 才是**完整可解析**的。
3. 前端实际看到的「[选择工具] 写入文件」文本，是 `JsonMessageStreamHandler` 对**第一次** `tool_request` 调 `generateToolRequestResponse()` 的结果——后续重复的被 `seenToolIds` 滤掉了，所以在终端里看不到（终端看的是原始 JSON，不是处理后的流）。

**验证 2：看文件真的落盘了**

```powershell
ls tmp/code_output/vue_project_1
```

**预期**：`index.html`、`package.json`、`vite.config.js`、`src/`、`public/` 等。注意目录名是 `vue_project_1`（`FileWriteTool#L38` 的 `"vue_project_" + appId`），不是 `1`。

再看一个具体文件的内容是不是完整（验证「半截 arguments 不影响落盘」）：

```powershell
cat tmp/code_output/vue_project_1/package.json
```

**预期**：完整合法的 JSON，说明真正写入磁盘的内容来自 LangChain4j **累积后**的完整参数，不是流式片段。

**验证 3：看对话历史里存了什么**

```sql
SELECT id, appId, messageType, LEFT(content, 300) AS preview, createTime
FROM chat_history
WHERE appId = 1
ORDER BY createTime DESC
LIMIT 5;
```

**预期**：`messageType` 为 `ai` 的那条，`content` 里应该能看到形如

```text
[工具调用] 写入文件 package.json
```json
{ ... }
```
```

的片段（来自 `FileWriteTool#generateToolExecutedResult`，`#L72-L82`），**但看不到 `[选择工具] 写入文件`**——因为 `TOOL_REQUEST` 的展示文本没进历史（`JsonMessageStreamHandler#L92`）。

如果历史里出现了 `[选择工具] xxx`，说明有人改了 `JsonMessageStreamHandler#L92` 那一行，得回头确认。

**验证 4：看 Redis 里的对话记忆**

```powershell
redis-cli --scan --pattern "*"
```

**预期**：能看到与 `appId=1` 相关的记忆键（`MessageWindowChatMemory` 的 `id` 就是 `appId`，见 `AiCodeGeneratorServiceFactory#L95`），TTL 由 `spring.data.redis.ttl` 控制（`RedisChatMemoryStoreConfig#L24`、`#L32`）。键的具体前缀由 LangChain4j community 的 `RedisChatMemoryStore` 决定，不在本项目代码里——所以这里只确认「有以 appId 为标识的键」，不要去猜前缀。

**验证 5：确认补丁的调试打印**

跑一次 VUE_PROJECT 生成，看后端控制台，**预期**能看到：

```text
OLOLO [ToolCall{index=0, id=..., function=...}]
```

它来自 `dev/langchain4j/model/openai/OpenAiStreamingResponseBuilder.java#L113`。这是补丁的遗留调试代码，看到它说明工具调用的流式聚合确实走到了补丁分支。

**验证 6：确认工具注册了几个**

启动日志里搜：

```text
注册工具:
```

**预期**：6 条（`writeFile`、`modifyFile`、`readFile`、`readDir`、`deleteFile`、`exit`），最后一条 `工具管理器初始化完成，共注册 6 个工具`。注意 `ToolManager` 在**应用启动时**就注册完了（`@PostConstruct`），与有没有请求无关。

### 3.6 自测题

<details>
<summary>题目（先自己答完再展开答案）</summary>

1. HTML 模式下模型能不能调用 `writeFile` 工具？为什么？
2. `FileWriteTool#writeFile` 的第三个参数 `@ToolMemoryId Long appId`，这个值是从哪来的？模型能改吗？
3. 新增一个「查询数据库」的工具，需要改哪些配置？
4. `onPartialToolExecutionRequest` 是 LangChain4j 官方 API 吗？它最终在哪个类的哪一行被触发？
5. **（陷阱）** `ToolRequestMessage` 里的 `arguments` 字段，前端能直接 `JSON.parse` 吗？为什么？
6. `JsonMessageStreamHandler` 里的 `seenToolIds` 是干什么的？去掉会怎样？
7. 为什么 `TOOL_REQUEST` 的展示文本只推给前端、不写进对话历史？
8. **（陷阱）** `handleJsonMessageChunk` 里的 `default -> log.error("不支持的消息类型")` 分支，什么情况下会执行？
9. **（陷阱）** 模型幻觉出一个不存在的工具名（如 `write_file`），`hallucinatedToolNameStrategy` 能兜住吗？`JsonMessageStreamHandler` 那一侧呢？
10. `FileModifyTool` 用的是 `replace` 而不是 `replaceFirst`，如果一个文件里有 3 处相同的 `oldContent`，会怎样？
11. **（陷阱）** 工具的路径校验够安全吗？模型传一个绝对路径 `D:/x.txt` 或 `../../../x.txt` 会发生什么？
12. `FileDeleteTool` 为什么要把 `index.html`、`main.js`、`package.json` 列进白名单？它的判断粒度有什么问题？
13. `ExitTool` 只有一个无参方法，为什么必须有它？它和 `maxSequentialToolsInvocations(20)` 是什么关系？
14. **（陷阱）** VUE_PROJECT 用的是哪个模型？和 HTML 模式的模型有什么区别？如果工具调用完全没反应，第一个该查哪里？
15. 补丁里 `OpenAiStreamingResponseBuilder#L113` 的 `System.out.println("OLOLO ...")` 是什么？会有什么影响？

</details>

<details>
<summary>参考答案</summary>

1. **不能。** 不是模型的问题，是 `AiCodeGeneratorServiceFactory#L125-L131` 的 HTML/MULTI_FILE 分支**没有** `.tools(...)`。没注册工具，发给模型的请求里就没有工具 schema，模型不知道工具存在。
2. 来自 AI Service 方法的 `@MemoryId` 参数。`AiCodeGeneratorService#generateVueProjectCodeStream(@MemoryId long appId, ...)` 的 `appId` 由 `AiCodeGeneratorFacade#L95` 传入，LangChain4j 通过 `@ToolMemoryId` 注入工具参数。**模型无法改、也无法伪造**——它不在工具 schema 里（`@ToolMemoryId` 参数不会作为可填参数发给模型），这是目录隔离能成立的关键。
3. **只需要写一个 `@Component` 类 extends `BaseTool`，加 `@Tool` 方法，并实现 `getToolName()`/`getDisplayName()`/`generateToolExecutedResult()`。** 不用改任何配置——`ToolManager#L27-L28` 的 `@Resource BaseTool[] tools` 会自动收集。但要注意：① `getToolName()` 必须和 `@Tool` 方法名一致；② 不能和其他工具重名（`ToolManager#L36` 静默覆盖）；③ 这个工具会立刻对**所有 appId** 生效，没有按应用裁剪的能力。
4. **不是官方 API。** 是项目在 `src/main/java/dev/langchain4j/` 下的源码级补丁。真正触发点是 `dev/langchain4j/model/openai/OpenAiStreamingChatModel.java#L182-L213` 的 `handle` 方法——遍历 `delta.toolCalls()`，`partialArguments` 非空就构造 `ToolExecutionRequest` 并回调（`#L209`）。
5. **不能。** 回调里传的是**本次 delta 的增量片段**（`OpenAiStreamingChatModel#L199-L207` 用的是 `partialArguments`，不是累积值），单独一段不是合法 JSON。可解析的完整参数只在 `tool_executed` 消息里（`ToolExecutedMessage#L28` 取自 `toolExecution.request().arguments()`，那是累积完整版）。
6. 用于**同一工具调用的去重**。因为 `onPartialToolExecutionRequest` 每个 delta 触发一次，同一个工具调用会推 N 次 `tool_request`。去掉的话，用户会看到重复 N 遍的「[选择工具] 写入文件」。
7. 因为对话历史是**给模型读的**。`[选择工具] 写入文件` 这种话对模型没有信息量，反而占用 context；而 `[工具调用] 写入文件 src/App.vue` + 代码内容（`TOOL_EXECUTED`）才是有用的「我上一轮改了什么」。另外历史里保留「模型想调工具」还会误导下一轮，让它以为工具没执行成功。
8. **永远不会执行。** Java 对 `switch` 的枚举 selector 会先取 `ordinal()`，`typeEnum` 为 `null` 时直接抛 `NullPointerException`，`default` 分支不可达（`#L72-L73` 没有判空）。所以如果补丁新增了消息类型，不是「打日志跳过」，而是整个 SSE 流崩掉。
9. `hallucinatedToolNameStrategy`（`AiCodeGeneratorServiceFactory#L112-L115`）只能兜住**工具执行阶段**——模型发出调用、LangChain4j 找不到工具时，回一条 `Error: there is no tool called xxx` 让它自我纠正。但 `JsonMessageStreamHandler#L90` 那一侧兜不住：`tool_request` 事件在**模型产出阶段**就推出来了，那时还没做工具名校验，`toolManager.getTool(toolName)` 返回 `null`，紧接着 `#L92` 的 `tool.generateToolRequestResponse()` 直接 NPE。**这是真实的缺口。**
10. **三处全部被替换。** `String#replace` 是替换所有出现（`replaceFirst` 才只替换第一处）。对代码修改来说这通常是想要的（比如把某个变量名全局改掉），但如果 `oldContent` 是 `App.vue` 这种短字符串，可能误伤——比如文件里既引用了 `App.vue` 组件又在注释里提了它，都会被换掉。工具本身没有「只改第 N 处」的能力。
11. **不够安全。** 校验是 `if (!path.isAbsolute())`——**绝对路径直接放行**（`FileWriteTool#L36-L41`）。模型传 `D:/x.txt` 就会写到那儿；传 `../../../x.txt` 虽然是相对路径，`resolve` 后 `..` 会往上跳，代码**没有** `normalize()` + `startsWith(projectRoot)` 的穿越校验。整个校验是「约定式」的（靠提示词让模型用相对路径），不是「强制式」的。这是本项目 AI 层最值得指出的一处安全债。
12. 因为这三个文件删了项目就跑不起来：`index.html` 是 Vite 入口、`main.js` 是应用挂载点、`package.json` 定义依赖和脚本。粒度问题是 `isImportantFile` 只比对**文件名**（`equalsIgnoreCase(fileName)`），不判断位置——`src/components/App.vue` 会被误保护，而 `src/main.js` 之外任何叫 `main.js` 的也一并被保护。保护过宽而非过窄，方向是对的。
13. 因为工具调用是**循环**的：模型调工具 → 拿到结果 → 决定下一步。没有出口，模型可能一直觉得「还有事没做完」。`ExitTool` 给模型一个显式的「我干完了」信号（返回「不要继续调用工具，可以输出最终结果了」）。`maxSequentialToolsInvocations(20)` 是强制上限，防止模型不听话时死循环。**两者是「引导 + 兜底」的关系，缺一不可**：只靠引导模型可能不停，只靠强制会在正常任务中被切断。
14. VUE_PROJECT 用 `reasoningStreamingChatModelPrototype`（`AiCodeGeneratorServiceFactory#L105`），绑定 `langchain4j.open-ai.reasoning-streaming-chat-model`，`application.yml` 里配的是 **`deepseek-reasoner`**（max-tokens 32768、temperature 0.1）；HTML/MULTI_FILE 用 `streamingChatModelPrototype` + **`deepseek-chat`**（max-tokens 8192）。工具调用完全没反应时，排查顺序：① 确认生成类型真的是 `VUE_PROJECT`（HTML 模式根本没注册工具）；② 看启动日志有没有「注册工具:」；③ 确认 `reasoning-streaming-chat-model` 配的模型是否支持 Function Calling——**这是配置层面的高风险点，本项目把推理模型和工具调用绑在一起，一旦该模型不支持 tools，VUE_PROJECT 会退化成只输出文本、一个文件都不写**。
15. 是补丁作者调试时留下的打印（`dev/langchain4j/model/openai/OpenAiStreamingResponseBuilder.java#L113`），每次工具调用都会往控制台输出一次 `OLOLO [ToolCall{...}]`。影响：① 生产环境刷日志；② 用 `System.out` 而非 `log`，绕过了日志框架的级别控制和格式化。功能上无害，但属于典型的「调试代码进了主干」。

</details>

### 3.7 本环节技术点清单

| 技术点 | 落地位置 | 作用 | 项目特有之处 |
|---|---|---|---|
| `@Tool` / `@P` 定义工具 | `ai/tools/FileWriteTool.java#L26-L33` 等 6 个工具 | 让模型知道有哪些工具、参数是什么 | 描述全中文；工具类同时是 Spring Bean |
| `@ToolMemoryId` 注入 appId | `ai/tools/FileWriteTool.java#L32` | 把 `@MemoryId` 传进工具方法 | **目录隔离的唯一通道**，模型无法伪造 |
| `@Resource BaseTool[]` 自动收集 | `ai/tools/ToolManager.java#L27-L40` | 工具注册，新增零配置 | 用 Spring 类型注入替代手写注册表；**不校验重名** |
| `AiServices.tools()` | `ai/AiCodeGeneratorServiceFactory.java#L110` | 注册工具 schema | **只在 VUE_PROJECT 分支注册**，HTML/MULTI_FILE 无工具 |
| 幻觉工具名兜底 | `ai/AiCodeGeneratorServiceFactory.java#L112-L115` | 工具名不存在时回错误消息 | 只兜执行阶段，兜不住 `tool_request` 的 NPE |
| 连续工具调用上限 | `ai/AiCodeGeneratorServiceFactory.java#L116` | 防死循环 | 与 `ExitTool` 构成「引导 + 兜底」双保险 |
| 三类流消息 | `ai/model/message/StreamMessageTypeEnum.java#L11-L13` | 区分文本/工具请求/工具结果 | `StreamMessage` 只带 `type`，子类各自带字段 |
| 补丁 API `onPartialToolExecutionRequest` | `dev/langchain4j/service/TokenStream.java#L30` | 流式透传工具调用 | **非官方 API**，源码级补丁覆盖 jar 同名类 |
| 工具调用真正触发点 | `dev/langchain4j/model/openai/OpenAiStreamingChatModel.java#L182-L213` | 遍历 delta 回调 | 传的是**增量参数**，不是累积值 |
| `seenToolIds` 去重 | `core/handler/JsonMessageStreamHandler.java#L47`、`#L86-L96` | 防止重复展示 | 因为同一个工具调用会被回调 N 次 |
| 请求/结果分别格式化 | `ai/tools/BaseTool.java#L30-L40` | 前端展示文本 | `TOOL_REQUEST` 只进流，`TOOL_EXECUTED` 进流 + 进历史 |
| 目录忽略清单 | `ai/tools/FileDirReadTool.java#L30-L40` | 控制 `readDir` 返回体积 | 不排 `node_modules` 会打爆 32768 的上下文 |
| 删除白名单 | `ai/tools/FileDeleteTool.java#L61-L74` | 保护 15 个关键文件 | 按**文件名**比对，粒度偏宽 |
| 路径校验缺口 | `FileWriteTool#L36-L41` 等 5 处 | 目录隔离 | `isAbsolute()` 反向逻辑 + 无穿越校验，**约定式而非强制式** |
| 推理模型 + 工具调用绑定 | `config/ReasoningStreamingChatModelConfig.java#L40-L53` | VUE_PROJECT 专用模型 | `deepseek-reasoner`，`@Scope("prototype")` |
| 提示词强制走工具 | `prompt/codegen-vue-project-system-prompt.txt#L96` | 让模型不直接输出代码 | 「必须通过【文件写入工具】依次创建每个文件」是 VUE_PROJECT 能跑通的前提 |

### 3.8 本环节产出

VUE_PROJECT 这条链路的本质，是**把「生成一个多文件工程」这件模型做不好的事，拆成「模型决策 + 后端执行」**：模型通过 Function Calling 发出 `writeFile` / `modifyFile` / `readDir` 等调用请求，真正落盘的是 Java 代码（`ai/tools/*`），每个工具都靠 `@ToolMemoryId` 拿到的 `appId` 把文件写进 `vue_project_{appId}` 目录。工具的定义靠 `@Tool` + `@P` 三件套，注册靠 `ToolManager` 里一句 `@Resource BaseTool[]`（新增工具零配置），而工具的**开关**在 `AiCodeGeneratorServiceFactory` 的 `switch` 里——HTML/MULTI_FILE 分支根本没注册工具，这是「HTML 模式不能调工具」的唯一原因。为了让用户看得见进度，项目又打了源码级补丁：`TokenStream#onPartialToolExecutionRequest` 一路从 `OpenAiStreamingChatModel#handle` 把 delta 里的工具调用透传出来，在 `AiCodeGeneratorFacade#processTokenStream` 包成 `tool_request` / `tool_executed` 两类 JSON 混进 SSE；`JsonMessageStreamHandler` 再用 `seenToolIds` 去掉重复（因为同一个调用会被回调 N 次）、只把 `tool_executed` 的结果写进对话历史。读到这里必须记住三个「不是 bug 但要知道」的点：**① `tool_request` 的 `arguments` 是半截 JSON，前端不能解析；② 工具的路径校验是约定式的，绝对路径和 `../` 都没拦；③ `hallucinatedToolNameStrategy` 兜不住 `JsonMessageStreamHandler#L90` 的 NPE**。读到这里应该能回答：**「模型说『我要写文件』到磁盘上真出现文件，中间经过了哪些类」**——答案是 `OpenAiStreamingChatModel#handle`（补丁触发）→ `AiServiceStreamingResponseHandler` → `AiServiceTokenStream` → `AiCodeGeneratorFacade#processTokenStream`（包 JSON）→ `AppController`（包 SSE 帧）→ 前端回显；而磁盘写入是 LangChain4j 在执行阶段自己调的 `FileWriteTool#writeFile`，**不经过 `Flux`**——这两条路径是并行的，别混。

---

## 环节 4：对话记忆与多租户 AI 服务实例管理

### 4.1 这个环节解决什么问题

到环节 3 为止，我们知道了「一次生成」是怎么跑的。但一个真实的 AI 应用还要回答三个问题，这三个问题就是本环节：

1. **AI Service 实例能不能复用？** 创建一个 `AiCodeGeneratorService` 要组装 chatModel、streamingChatModel、tools、guardrail、chatMemory——成本不低。每次请求都新建会浪费；但直接做成单例，记忆就会在用户之间串味。
2. **对话记忆存在哪？** 进程重启后，模型得记得用户上一轮说过「把导航栏改成蓝色」。
3. **数据库里已经存了历史，为什么还要 Redis？** —— 因为这两套东西的**读者不同**，这是本环节最容易搞混的地方。

> 一句话：**用 Caffeine 按 `appId + 生成类型` 缓存 AI Service 实例来做多租户隔离，用 Redis 持久化对话记忆，用 MySQL 存业务历史给前端看。** 三套东西各管一段，谁也不能替谁。

### 4.2 技术点一：Caffeine 缓存 AI Service 实例

`ai/AiCodeGeneratorServiceFactory.java#L52-L59`：

```java
private final Cache<String, AiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofMinutes(30))
        .expireAfterAccess(Duration.ofMinutes(10))
        .removalListener((key, value, cause) -> {
            log.debug("AI 服务实例被移除，缓存键: {}, 原因: {}", key, cause);
        })
        .build();
```

| 配置 | 值 | 含义 |
|---|---|---|
| `maximumSize` | 1000 | 最多缓存 1000 个实例，超了按 LRU 淘汰 |
| `expireAfterWrite` | 30 分钟 | 写入后 30 分钟过期 |
| `expireAfterAccess` | 10 分钟 | 10 分钟没被访问就过期 |
| `removalListener` | 打 debug 日志 | 只记日志，不做清理动作 |

注意 `maximumSize(1000)` 和 `expireAfterAccess(10min)` 组合的含义：**这是个「热应用常驻、冷应用淘汰」的策略**。1000 个实例的上限决定了这个方案在应用数超过 1000 时会开始互相淘汰——被淘汰的应用下次请求会重建实例、重新从 MySQL 灌记忆（见 4.4）。

取实例的入口（`AiCodeGeneratorServiceFactory#L78-L81`）：

```java
public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
    String cacheKey = buildCacheKey(appId, codeGenType);
    return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType));
}
```

`Cache#get(key, mappingFunction)` 是 Caffeine 的**原子加载**方法：同一个 key 并发进来，`createAiCodeGeneratorService` 只会被执行一次，其他线程阻塞等结果。**这是选 Caffeine 而不是自己写 `HashMap` + `synchronized` 的核心原因**——不需要手写双检锁。

缓存键（`#L155-L157`）：

```java
private String buildCacheKey(long appId, CodeGenTypeEnum codeGenType) {
    return appId + "_" + codeGenType.getValue();
}
```

**为什么键里要带 `codeGenType`？** 因为同一个 app 的 VUE_PROJECT 实例和 HTML 实例在**四个维度**上都不同：

| 维度 | VUE_PROJECT 实例 | HTML/MULTI_FILE 实例 |
|---|---|---|
| streamingChatModel | `reasoningStreamingChatModelPrototype`（`deepseek-reasoner`） | `streamingChatModelPrototype`（`deepseek-chat`） |
| tools | `toolManager.getAllTools()` | 无 |
| memory 装配方式 | `.chatMemoryProvider(memoryId -> chatMemory)` | `.chatMemory(chatMemory)` |
| 其他 | `hallucinatedToolNameStrategy`、`maxSequentialToolsInvocations(20)` | 无 |

如果键只用 `appId`，同一个 app 先做 VUE_PROJECT 再做 HTML 就会拿到错的服务实例。

**兼容老逻辑的重载与默认 Bean**（`#L67-L69`、`#L143-L146`）：

```java
public AiCodeGeneratorService getAiCodeGeneratorService(long appId) {
    return getAiCodeGeneratorService(appId, CodeGenTypeEnum.HTML);
}

@Bean
public AiCodeGeneratorService aiCodeGeneratorService() {
    return getAiCodeGeneratorService(0);
}
```

这个 `@Bean` 值得注意：它注册的是 **`appId = 0`** 的实例。作用是让别处可以直接 `@Resource AiCodeGeneratorService` 注入而不用改代码——但**它只适合「不需要记忆隔离」的场景**。真正的业务调用（`AiCodeGeneratorFacade#L54`、`#L84`）走的是 `getAiCodeGeneratorService(appId, type)`。

**陷阱**：`appId = 0` 这个实例会占用一个缓存键（`"0_HTML"`），而且它的记忆（`MessageWindowChatMemory.id(0)`）会和真实应用 `appId=0` 撞——虽然 `AppConstant` 里没有 0 号应用，但这是个「用魔法值占位」的写法，新增应用时若 appId 真从 0 开始就会出问题。

### 4.3 技术点二：对话记忆的三层结构（最容易搞混的一节）

项目里同时存在**两套历史数据**，它们长得像、名字像，但完全是两回事：

| 维度 | Redis 对话记忆（LangChain4j ChatMemory） | MySQL 业务历史（`chat_history` 表） |
|---|---|---|
| 承载对象 | `MessageWindowChatMemory` + `RedisChatMemoryStore` | `ChatHistory` 实体 |
| 谁读它 | **模型**（作为 context 拼进 prompt） | **前端**（渲染对话列表） |
| 存的内容 | `UserMessage` / `AiMessage` 对象（LangChain4j 序列化格式） | 纯文本 `message` 字段 |
| 条数 | 窗口 **20 条**（`maxMessages(20)`） | 全量，只做逻辑删除 |
| 生命周期 | TTL `spring.data.redis.ttl` = **3600 秒** | 永久 |
| 内容差异 | **只有用户提问和 AI 回复** | 含 `[工具调用] 写入文件 src/App.vue` 等工具结果 |
| 写入时机 | 由 LangChain4j 自动写（每次 `chatMemory.add`） | 由业务代码显式写（`addChatMessage`） |

**关键结论**：`chat_history` 表里的内容比 Redis 记忆**更全**（多了工具调用结果），而 Redis 记忆其实是**从 `chat_history` 表派生出来的**——每次创建新的 service 实例都会「先清 Redis、再从 MySQL 灌 20 条」（见 4.4）。Redis 在这里是**模型上下文的缓存**，不是主存储。

**记忆的构建**（`AiCodeGeneratorServiceFactory#L93-L100`）：

```java
MessageWindowChatMemory chatMemory = MessageWindowChatMemory
        .builder()
        .id(appId)                          // memoryId = appId
        .chatMemoryStore(redisChatMemoryStore)
        .maxMessages(20)
        .build();
// 从数据库中加载对话历史到记忆中
chatHistoryService.loadChatHistoryToMemory(appId, chatMemory, 20);
```

`.id(appId)` 是记忆的**身份**，`RedisChatMemoryStore` 用它拼 Redis 键。`.maxMessages(20)` 是滑动窗口：超过 20 条后最老的被挤出去（**只在内存窗口里挤，Redis 里仍会保留**——`MessageWindowChatMemory` 每次 `add` 都会把当前窗口整体写回 store）。

**两种 memory 装配方式的差异**（`#L109` vs `#L128`）：

```java
// VUE_PROJECT
.chatMemoryProvider(memoryId -> chatMemory)
// HTML / MULTI_FILE
.chatMemory(chatMemory)
```

为什么 VUE_PROJECT 必须用 `chatMemoryProvider`？因为它的 AI Service 方法签名带了 `@MemoryId`：

```java
TokenStream generateVueProjectCodeStream(@MemoryId long appId, @UserMessage String userMessage);
```

LangChain4j 的规则是：**方法上声明了 `@MemoryId`，就必须用 `chatMemoryProvider` 装配**（因为要按 memoryId 路由）。HTML/MULTI_FILE 的方法没有 `@MemoryId`，所以用 `chatMemory` 固定装配即可。

**陷阱**：`memoryId -> chatMemory` 这个 lambda **完全忽略了 `memoryId` 参数**，永远返回创建时闭包捕获的那个 `chatMemory`。那为什么还能隔离？因为**隔离靠的是「service 实例按 `appId_type` 分片」，不是靠 memoryId 路由**——每个缓存键对应一个独立实例，每个实例创建时 `new` 了自己的 `chatMemory`（`id` 是自己的 appId）。这个 lambda 只是为了让 API 满足要求，路由能力完全没用上。

> 记住这句话：**`chatMemoryProvider` 在这里是「形式上的 provider，实质上的常量」**。面试被问到「你怎么做多租户记忆隔离」时，答案要说「实例分片 + 记忆 id」，不能说「靠 chatMemoryProvider 路由」。

**Redis 记忆的配置**（`config/RedisChatMemoryStoreConfig.java`）：

```java
@Configuration
@ConfigurationProperties(prefix = "spring.data.redis")
@Data
public class RedisChatMemoryStoreConfig {

    private String host;
    private int port;
    private String password;
    private long ttl;

    @Bean
    public RedisChatMemoryStore redisChatMemoryStore() {
        RedisChatMemoryStore.Builder builder = RedisChatMemoryStore.builder()
                .host(host).port(port).password(password).ttl(ttl);
        if (StrUtil.isNotBlank(password)) {
            builder.user("default");
        }
        return builder.build();
    }
}
```

三个要点：

- **前缀复用了 Spring Boot 的 `spring.data.redis`**（`#L14`）。这样 `host`/`port`/`password` 不用重复配，直接用 `application.yml#L16-L22` 里的那份。
- **`ttl` 是项目自定义字段**。Spring Boot 的 `spring.data.redis` 下**没有** `ttl` 这个属性——它是本项目自己加的（`application.yml#L22`：`ttl: 3600 # 缓存过期时间（秒）`，单位是**秒**）。别和 `spring.session.timeout: 2592000`（session 30 天）搞混，那是两个完全不同的东西。
- `password` 为空时不设 `user`（`#L33-L35`）。这是 LangChain4j community 版 `RedisChatMemoryStore` 的要求：无密码时不需要指定用户名。

### 4.4 代码走读：`ChatHistoryServiceImpl`

**核心方法 `loadChatHistoryToMemory`（`service/impl/ChatHistoryServiceImpl.java#L94-L125`）**

```java
public int loadChatHistoryToMemory(Long appId, MessageWindowChatMemory chatMemory, int maxCount) {
    try {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq(ChatHistory::getAppId, appId)
                .orderBy(ChatHistory::getCreateTime, false)   // 降序：新的在前
                .limit(1, maxCount);
        List<ChatHistory> historyList = this.list(queryWrapper);
        if (CollUtil.isEmpty(historyList)) {
            return 0;
        }
        // 反转列表，确保按照时间正序（老的在前，新的在后）
        historyList = historyList.reversed();
        int loadedCount = 0;
        // 先清理历史缓存，防止重复加载
        chatMemory.clear();
        for (ChatHistory history : historyList) {
            if (ChatHistoryMessageTypeEnum.USER.getValue().equals(history.getMessageType())) {
                chatMemory.add(UserMessage.from(history.getMessage()));
            } else if (ChatHistoryMessageTypeEnum.AI.getValue().equals(history.getMessageType())) {
                chatMemory.add(AiMessage.from(history.getMessage()));
            }
            loadedCount++;
        }
        log.info("成功为 appId: {} 加载 {} 条历史消息", appId, loadedCount);
        return loadedCount;
    } catch (Exception e) {
        log.error("加载历史对话失败，appId: {}, error: {}", appId, e.getMessage(), e);
        // 加载失败不影响系统运行，只是没有历史上下文
        return 0;
    }
}
```

**七个必须点到的细节：**

1. **`orderBy(ChatHistory::getCreateTime, false)` + `reversed()`** —— 先用 SQL 拿「最新的 N 条」，再在内存里反转成正序。为什么不能直接 `orderBy(createTime, true)`（升序）+ `limit(N)`？因为升序 + limit 拿到的是**最老的 N 条**，不是最新的。这是「取最新 N 条但按正序喂给模型」的标准写法。
2. **`historyList.reversed()` 是 Java 21 的 API**（`SequencedCollection` 接口新增），不是 `Collections.reverse()`。它返回的是**视图**，不是新列表——对返回值后续的读取没问题，但如果有人拿到后去改，会改到原列表。这里只读，安全。
3. **`limit(1, maxCount)`** —— 这是 MyBatis-Flex 的 `limit(offset, rows)` 重载，生成 `LIMIT offset, rows`。**注意它跳过了 1 行**。如果语义真是 `LIMIT 1, 20`，那拿到的是「第 2 新到第 21 新」的 20 条，**最新那条被跳过了**。这行代码值得你在 4.6 里亲眼确认一次真实 SQL（见验证 3）——因为它直接决定记忆里有没有最新那条。
   - 一个可能的解释：调用时机上（`AppServiceImpl#L101` 先存用户消息 → `#L110` 再创建 service → 触发加载），**最新那条正是本轮刚存的用户消息**，而它本来就会作为 `@UserMessage` 参数传给模型，跳过它反而避免了重复。但代码里没有任何注释说明这个意图，**属于「巧合正确」**。
4. **`chatMemory.clear()` 在循环之前**（`#L109`），注释写「先清理历史缓存，防止重复加载」。它调用的是 `chatMemoryStore.deleteMessages(id)`——也就是**删掉 Redis 里那个键**，然后循环里的 `add` 会重新写回去。所以这一步的实质是「用 MySQL 的内容覆盖 Redis」。
5. **`clear()` 的位置有个小漏洞**：`CollUtil.isEmpty` 的提前 return（`#L101-L103`）在 `clear()` **之前**。所以如果某个 app 的 `chat_history` 被清空了、但 Redis 里还留着旧记忆，**Redis 不会被清掉**——下次创建实例时直接 return 0，旧记忆继续生效。这是个真实的边界 bug（影响面小，因为正常业务里历史不会被单独清空）。
6. **`loadedCount++` 在 `if/else` 之外**（`#L116`）。所以将来如果新增了第三种 `messageType`，它既不会被加入记忆，也会被计入 `loadedCount`——日志会虚高。当前只有 user/ai 两种，无实际影响。
7. **异常吞掉返回 0**（`#L120-L124`）。这是**有意的降级**：MySQL 挂了或查询超时，不该让用户连代码都生成不了，只是模型失去历史上下文。注释写得很清楚：「加载失败不影响系统运行」。这是本环节最值得学的一个工程实践——**记忆是增强能力，不是必需能力**。

**`addChatMessage`（`#L44-L61`）**

```java
public boolean addChatMessage(Long appId, String message, String messageType, Long userId) {
    ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
    ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "消息内容不能为空");
    ThrowUtils.throwIf(StrUtil.isBlank(messageType), ErrorCode.PARAMS_ERROR, "消息类型不能为空");
    ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户ID不能为空");
    ChatHistoryMessageTypeEnum messageTypeEnum = ChatHistoryMessageTypeEnum.getEnumByValue(messageType);
    ThrowUtils.throwIf(messageTypeEnum == null, ErrorCode.PARAMS_ERROR, "不支持的消息类型");

    ChatHistory chatHistory = ChatHistory.builder()
            .appId(appId).message(message).messageType(messageType).userId(userId)
            .build();
    return this.save(chatHistory);
}
```

- **校验用 `ThrowUtils.throwIf`，不是 Jakarta Validation 注解**。这是本项目的统一风格（`exception/ThrowUtils`），**项目里没有引入 `spring-boot-starter-validation`，也没有任何 `@NotNull` / `@Valid` 注解**——找校验逻辑要去 service 层找 `throwIf`，别去实体类上找注解。
- `createTime` / `updateTime` / `isDelete` **都没手动赋值**，靠数据库默认值。注意 `ChatHistory#L77-L78` 的 `isDelete` 标了 `isLogicDelete = true`——所以 `deleteByAppId`（`#L64-L69`）里的 `this.remove(queryWrapper)` 实际执行的是 **UPDATE**，不是 DELETE。
- 谁在调它？三处：`AppServiceImpl#L101`（存用户消息）、`SimpleTextStreamHandler`（HTML/MULTI_FILE 存 AI 回复）、`JsonMessageStreamHandler#L57`/`#L62`（VUE_PROJECT 存 AI 回复和错误信息）。**注意存储时机都在流结束回调里，不在流中间**。

**`@Resource @Lazy AppService appService`（`#L39-L41`）—— 打断循环依赖**

```java
@Resource
@Lazy
private AppService appService;
```

为什么需要 `@Lazy`？因为存在一个环：

```text
AppServiceImpl  ──依赖──>  ChatHistoryService   (AppServiceImpl#L68)
ChatHistoryServiceImpl ──依赖──>  AppService     (ChatHistoryServiceImpl#L41)
```

Spring 创建 `AppServiceImpl` 时要先拿到 `ChatHistoryServiceImpl`，创建 `ChatHistoryServiceImpl` 时又要拿到 `AppService`——回到起点。`@Lazy` 注入的是一个**代理对象**，第一次真正调用 `appService.getById(...)`（`#L79`）时才去容器里解析真实 Bean，环就被打破了。

**这是 Spring 里解决循环依赖的标准手段**（比 `@Autowired` 字段注入的「三级缓存兜底」更显式、更可控）。要注意的是：**`@Lazy` 只适合「其中一个方向的依赖不是必需的」场景**——这里 `ChatHistoryServiceImpl` 只在权限校验时需要 `AppService`，不是启动必需，所以合适。

**`listAppChatHistoryByPage`（`#L72-L91`）的权限校验**

```java
boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
boolean isCreator = app.getUserId().equals(loginUser.getId());
ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权查看该应用的对话历史");
```

- 用的是「**应用创建者或管理员**」双重判定，不是「登录即可」。
- `UserConstant.ADMIN_ROLE.equals(...)` 把常量写在前面（`#L81`）——**避免 `loginUser.getUserRole()` 为 `null` 时 NPE**。这是「常量在前」的经典写法，和环节 2 里 `app.getUserId().equals(loginUser.getId())` 是同一个思路（`Long` 要用 `equals` 不能用 `==`）。
- 游标分页（`lastCreateTime`）的细节放在环节 11，这里只需知道它**不用 `offset`**。

### 4.5 动手验证

**验证 1：确认 AI Service 实例确实被缓存复用**

启动后连续两次对同一个 app 发起生成（类型都用 VUE_PROJECT），看后端日志：

```text
为 appId: 1 创建新的 AI 服务实例
```

**预期**：这条日志**只出现一次**（`AiCodeGeneratorServiceFactory#L91`）。第二次请求命中 Caffeine 缓存，不打印。

再换一个生成类型（比如把 app 的 `codeGenType` 改成 HTML 后再生成），**预期**：又打印一次 `为 appId: 1 创建新的 AI 服务实例`——因为缓存键是 `1_HTML`，和 `1_VUE_PROJECT` 是两个 key。

**验证 2：确认历史被灌进记忆**

同一个 app 连续对话两轮，第二轮日志里找：

```text
成功为 appId: 1 加载 N 条历史消息
```

**预期**：第一次生成时 N 可能是 1 或 0（取决于 4.4 里说的 `limit(1, ...)` 语义），第二轮时 N 变大。**这个 N 的变化趋势就是「记忆在累积」的直接证据。**

**验证 3：亲眼确认 `limit(1, maxCount)` 的真实 SQL**

dev 环境 MyBatis-Flex 默认会打印 SQL（`application-prod-sample.yml#L69-L71` 里显式把 `mybatis-flex.configuration.log-impl` 设成空字符串来**关闭**它，反过来说明 dev 是开着的）。在控制台里搜 `chat_history`：

```sql
SELECT ... FROM `chat_history` WHERE `appId` = ? ORDER BY `createTime` DESC LIMIT ?, ?
```

**预期**：看到两个占位符，第一个的值就是 `limit(1, maxCount)` 传进去的 `1`。把实际参数对上去，确认「最新那条有没有被跳过」。

这一步不是走过场——它决定了你判断「记忆里到底有没有最新那条消息」，也决定了 4.4 第 3 点的结论是「刻意设计」还是「潜在 bug」。

**验证 4：看 Redis 记忆的键和 TTL**

```powershell
redis-cli --scan --pattern "*"
redis-cli ttl "<上一步扫出来的记忆键>"
```

**预期**：TTL 接近 **3600**（`application.yml#L22` 的 `spring.data.redis.ttl`）。如果 TTL 是 `-1`（永不过期），说明 `ttl` 没绑定上——检查 `RedisChatMemoryStoreConfig` 的 `@ConfigurationProperties(prefix = "spring.data.redis")` 和 `application.yml` 的层级是否对得上。

**验证 5：确认「清 Redis + 从 MySQL 重灌」这个动作**

开一个监听：

```powershell
redis-cli monitor
```

然后触发一次会让缓存失效的操作（最简单的是重启应用，或等 `expireAfterAccess` 10 分钟后再请求），观察输出。

**预期**：能看到针对记忆键的 `DEL`（来自 `chatMemory.clear()`，`ChatHistoryServiceImpl#L109`）紧跟一串 `SET`/`HSET`（来自循环里的 `chatMemory.add`）。**这就是「Redis 记忆是从 MySQL 派生」的现场证据。**

**验证 6：对比两套历史的内容差异**

```sql
-- 看 MySQL：应该能看到工具调用记录
SELECT messageType, LEFT(message, 120) FROM chat_history WHERE appId = 1 ORDER BY createTime DESC;
```

```powershell
-- 看 Redis：应该只有纯文本，没有 [工具调用]
redis-cli get "<记忆键>"
```

**预期**：MySQL 里的 AI 消息含 `[工具调用] 写入文件 src/App.vue`；Redis 里的记忆内容里**没有** `[选择工具]` 也没有 `[工具调用]` 这类标记（因为 `loadChatHistoryToMemory` 只把 `message` 字段原样包成 `AiMessage`……等等——这里要留意）。

> **注意一个反直觉的点**：`loadChatHistoryToMemory#L114` 是 `AiMessage.from(history.getMessage())`——把 MySQL 里那条**含 `[工具调用] ...` 的完整文本**原样灌进记忆。所以 **Redis 记忆里其实是带 `[工具调用]` 标记的**，因为它就是从 MySQL 复制过去的。真正不带标记的是「同一次会话中、实例未被重建时」由 LangChain4j 直接 `add` 进记忆的内容——但 VUE_PROJECT 的 AI 回复本身也是 `JsonMessageStreamHandler` 拼出来的带标记文本。所以两套内容的差异只在**条数**和**格式**上，不在「有没有工具标记」上。验证时以实际输出为准，别预设结论。

**验证 7：确认逻辑删除**

```sql
SELECT id, appId, isDelete FROM chat_history WHERE appId = 1;
```

调用一次删除应用（`AppServiceImpl#L301` 会触发 `chatHistoryService.deleteByAppId`），再查一次。

**预期**：`isDelete` 变成 `1`，行**没有物理消失**（`ChatHistory#L77-L78` 的 `isLogicDelete = true`）。后续查询会自动带上 `isDelete = 0` 条件。

### 4.6 自测题

<details>
<summary>题目（先自己答完再展开答案）</summary>

1. 为什么要用 Caffeine 缓存 `AiCodeGeneratorService`，而不是直接把它做成单例 Bean？
2. 缓存键为什么是 `appId + "_" + codeGenType.getValue()`，只用 `appId` 会出什么问题？
3. **（陷阱）** `chatMemoryProvider(memoryId -> chatMemory)` 里的 `memoryId` 参数被忽略了，那多租户隔离到底靠什么成立？
4. Redis 对话记忆和 MySQL `chat_history` 表，各自的服务对象是谁？为什么不能只留一个？
5. `loadChatHistoryToMemory` 为什么先降序查再 `reversed()`？直接升序查不行吗？
6. **（陷阱）** `limit(1, maxCount)` 里的 `1` 是什么？它会跳过一条记录吗？如果会，为什么没出问题？
7. `chatMemory.clear()` 这一行做了什么？它在循环之前还是之后，有什么区别？
8. `CollUtil.isEmpty(historyList)` 提前 return 的那段代码（`#L101-L103`）有什么隐患？
9. `@Resource @Lazy AppService appService` 里的 `@Lazy` 是干什么的？这个循环依赖具体是怎么形成的？
10. **（陷阱）** 这个项目的参数校验用什么做的？`@NotNull` / `@Valid` 注解在项目里存在吗？
11. `RedisChatMemoryStoreConfig` 的 `@ConfigurationProperties(prefix = "spring.data.redis")` 里，`ttl` 字段是 Spring Boot 提供的吗？单位是什么？
12. `MessageWindowChatMemory.maxMessages(20)` 超出 20 条后，Redis 里的数据会被删掉吗？
13. **（陷阱）** 为什么 VUE_PROJECT 用 `.chatMemoryProvider(...)` 而 HTML 用 `.chatMemory(...)`？能不能都用一个？
14. `loadChatHistoryToMemory` 捕获异常后返回 0，这是好设计还是坏设计？
15. `deleteByAppId` 里的 `this.remove(queryWrapper)` 执行的是 DELETE 还是 UPDATE？依据是什么？

</details>

<details>
<summary>参考答案</summary>

1. 因为单例**无法做记忆隔离**——所有 appId 共用一份 `chatMemory`，A 应用的用户会看到 B 应用的对话上下文。而每次请求都新建又太贵（要组装 model、tools、guardrail）。Caffeine 缓存正好是中间态：**按 appId 分片复用**。另外 `Cache#get(key, mappingFunction)` 是原子的，同一个 key 并发只创建一次，省掉了手写双检锁。
2. 同一个 app 可能先后用不同生成类型（`CodeGenTypeEnum` 有 HTML / MULTI_FILE / VUE_PROJECT）。两种类型的实例在 **streamingChatModel**（`deepseek-reasoner` vs `deepseek-chat`）、**tools**（有 / 无）、**memory 装配方式**（provider vs 固定）、**其他配置**（`maxSequentialToolsInvocations`）四个维度上都不同。只用 `appId` 做键，先跑 VUE_PROJECT 再跑 HTML 就会拿到错的服务实例。
3. **靠「service 实例按 `appId_type` 分片」**。每个缓存键对应一个独立实例，`createAiCodeGeneratorService` 在创建实例时 `new` 了自己的 `MessageWindowChatMemory`，`.id(appId)` 也设成了自己的 appId。`chatMemoryProvider` 在这里只是**为了满足「方法声明了 `@MemoryId` 就必须用 provider」这条 API 约束**，路由能力完全没用上——所以它是「形式上的 provider，实质上的常量」。
4. Redis 记忆服务的是**模型**（作为 context 拼进 prompt，需要 LangChain4j 的消息对象格式、需要 20 条窗口限制、需要 TTL）；`chat_history` 表服务的是**前端**（渲染对话列表，需要全量、需要纯文本、需要按用户权限过滤）。只留 Redis：前端拿不到完整历史、也没法按用户鉴权；只留 MySQL：每次请求都要查库 + 反序列化，而且 LangChain4j 需要 `ChatMemoryStore` 接口实现才能做窗口管理。
5. 因为要「取最新的 N 条」。SQL 的 `ORDER BY ... LIMIT N` 是先排序再截断：**升序 + limit 拿到的是最老的 N 条**，不是最新的。所以必须降序拿「最新的 N 条」，再在内存里反转成正序（模型需要老的在前、新的在后）。`reversed()` 是 Java 21 `SequencedCollection` 的新 API，返回的是视图。
6. `limit(offset, rows)` 的第一个参数是 **offset**，所以会跳过 1 条。没出大问题的可能原因是：调用时机上（`AppServiceImpl#L101` 先存本轮用户消息 → `#L110` 才创建 service → 触发加载），**被跳过的最新那条恰好是本轮刚存的用户消息**，而它本来就会通过 `@UserMessage` 参数传给模型，跳过反而避免了重复。但代码里没有任何注释说明这个意图，属于「巧合正确」——**必须用 4.5 验证 3 亲眼确认真实 SQL 才能下结论**。
7. `clear()` 调用 `chatMemoryStore.deleteMessages(id)`，即**删掉 Redis 里该 appId 的记忆键**；随后循环里的 `add` 会重新写回。放在循环前是对的（放后面等于白清）。它的实质是「**用 MySQL 的内容覆盖 Redis**」——这也是「Redis 记忆是 MySQL 历史的派生」这个结论的来源。
8. 隐患是：**它让 `clear()` 被跳过了**。如果一个 app 的 `chat_history` 被清空（或查询结果为空），但 Redis 里还留着旧记忆，这段代码会直接 `return 0`，Redis 里的脏记忆不会被清理，下次仍会生效。修法很简单：把 `chatMemory.clear()` 提到 `isEmpty` 判断之前。
9. `@Lazy` 注入的是**代理对象**，第一次真正调用方法时才去容器解析目标 Bean，从而**打破循环依赖**。环是：`AppServiceImpl` 依赖 `ChatHistoryService`（`AppServiceImpl#L68`），`ChatHistoryServiceImpl` 依赖 `AppService`（`ChatHistoryServiceImpl#L41`）。`@Lazy` 适合「其中一个方向的依赖不是启动必需」的场景——这里 `ChatHistoryServiceImpl` 只在权限校验（`#L79`）时才需要 `AppService`。
10. 用 **`ThrowUtils.throwIf(...)` 手动校验**（`exception/ThrowUtils`），在 service 层逐条判断。**项目里没有 Jakarta Validation**——没有引入 `spring-boot-starter-validation`，也没有 `@NotNull` / `@NotBlank` / `@Valid` 注解。所以找校验规则要去 service 方法体里找 `throwIf`，别去实体类上找注解。
11. **不是 Spring Boot 提供的**。Spring Boot 的 `spring.data.redis` 下没有 `ttl` 属性——`ttl` 是本项目自己在 `application.yml#L22` 加的（`ttl: 3600 # 缓存过期时间（秒）`），单位是**秒**，被 `RedisChatMemoryStoreConfig#L24` 的 `private long ttl` 绑定。别和 `spring.session.timeout: 2592000`（session 30 天，单位秒）搞混。
12. **内存窗口挤掉了，Redis 里仍会保留（但会被整体覆盖）**。`MessageWindowChatMemory` 的 `maxMessages(20)` 只限制**内存中的窗口大小**；每次 `add` 时它会把当前窗口整体 `updateMessages` 写回 store，所以 Redis 里存的也是「最近 20 条」，超出部分被覆盖掉。**更需要注意的是**：只要创建了新实例，`loadChatHistoryToMemory` 就会用 MySQL 的内容重灌，Redis 的历史因此始终受 MySQL 主导。
13. 因为 **VUE_PROJECT 的 AI Service 方法声明了 `@MemoryId`**（`AiCodeGeneratorService#L56`：`generateVueProjectCodeStream(@MemoryId long appId, ...)`），LangChain4j 要求带 `@MemoryId` 的方法必须用 `chatMemoryProvider` 装配（它需要按 memoryId 路由）。HTML/MULTI_FILE 的方法（`#L38`、`#L47`）没有 `@MemoryId`，用 `.chatMemory(chatMemory)` 固定装配即可。**不能都用一个**：给无 `@MemoryId` 的方法配 provider 拿不到 memoryId，给有 `@MemoryId` 的方法配固定 memory 会直接报错。
14. **好设计。** 对话记忆是「增强能力」而不是「必需能力」：MySQL 查询超时或连接池打满时，用户仍然应该能生成代码，只是模型失去上下文（表现为「不记得上一轮说过什么」）。用日志留痕 + 返回 0 降级，比抛异常让整个 SSE 流失败要好得多。这是**故障隔离**的典型应用。风险是异常被吞得比较彻底（`log.error` 之后无人感知），如果这类失败频繁发生，需要靠日志告警而不是靠用户反馈来发现。
15. **UPDATE。** 依据是 `ChatHistory#L77-L78`：`@Column(value = "isDelete", isLogicDelete = true)`——MyBatis-Flex 的 `isLogicDelete` 标记会把该字段作为逻辑删除列，`remove()` 生成的 SQL 是 `UPDATE chat_history SET isDelete = 1 WHERE ...`，不是 `DELETE`。后续所有查询会自动追加 `isDelete = 0` 条件。

</details>

### 4.7 本环节技术点清单

| 技术点 | 落地位置 | 作用 | 项目特有之处 |
|---|---|---|---|
| Caffeine 实例缓存 | `ai/AiCodeGeneratorServiceFactory.java#L52-L59` | 按 app 复用 AI Service | `maximumSize 1000` + 写后 30min + 访问后 10min；`removalListener` 只打日志 |
| 原子加载 | `AiCodeGeneratorServiceFactory#L80` | 并发只创建一次 | 用 `Cache#get(key, mappingFunction)` 替代手写双检锁 |
| 缓存键设计 | `AiCodeGeneratorServiceFactory#L155-L157` | 区分生成类型 | `appId + "_" + type`；同 app 不同类型是不同实例 |
| 默认占位 Bean | `AiCodeGeneratorServiceFactory#L143-L146` | 让别处能直接注入 | 注册的是 **`appId = 0`** 的实例，用魔法值占位 |
| 记忆窗口 | `AiCodeGeneratorServiceFactory#L93-L98` | 控制上下文长度 | `.id(appId)` + `.maxMessages(20)` |
| 两种 memory 装配 | `AiCodeGeneratorServiceFactory#L109` vs `#L128` | 适配 `@MemoryId` | provider 形式**忽略 memoryId 参数**，隔离靠实例分片 |
| Redis 记忆持久化 | `config/RedisChatMemoryStoreConfig.java` | 跨重启保留记忆 | **复用 `spring.data.redis` 前缀**；`ttl` 是项目自定义字段，单位秒 |
| 历史回灌 | `service/impl/ChatHistoryServiceImpl.java#L94-L125` | 从 MySQL 灌记忆 | 降序查 + `reversed()`（Java 21）；`clear()` 覆盖 Redis |
| 加载失败降级 | `ChatHistoryServiceImpl#L120-L124` | 记忆不可用不阻塞生成 | try-catch 吞异常返回 0，**故障隔离** |
| 逻辑删除 | `model/entity/ChatHistory.java#L77-L78` | 历史不物理删除 | `isLogicDelete = true`，`remove()` 实为 UPDATE |
| `@Lazy` 断环 | `ChatHistoryServiceImpl#L39-L41` | 解决循环依赖 | 环：`AppServiceImpl → ChatHistoryService → AppService` |
| 手动参数校验 | `ChatHistoryServiceImpl#L46-L52` | 入参校验 | 用 `ThrowUtils.throwIf`，**无 Jakarta Validation 注解** |
| 权限判定 | `ChatHistoryServiceImpl#L81-L83` | 历史只能自己看 | 管理员或创建者；常量在前防 NPE，`Long` 用 `equals` |
| 静态取 Bean | `utils/SpringContextUtil.java` | 非 Spring 上下文里取 Bean | `ApplicationContextAware` 静态持有；配合 `@Scope("prototype")` 取多例模型 |

### 4.8 本环节产出

多租户 AI 应用的三个经典问题，在这个项目里被拆成了三套独立机制：**实例隔离**靠 Caffeine——`AiCodeGeneratorServiceFactory` 用 `appId + "_" + 生成类型` 作键缓存 AI Service 实例（`maximumSize 1000`、写后 30 分钟、访问后 10 分钟），用 `Cache#get(key, mappingFunction)` 的原子性保证并发只创建一次；**上下文隔离**靠 `MessageWindowChatMemory`——每个实例创建时 `new` 一份自己的记忆（`.id(appId)`、`.maxMessages(20)`），**注意 `chatMemoryProvider(memoryId -> chatMemory)` 里那个 memoryId 参数是被忽略的，隔离真正靠的是「实例按 appId 分片」而不是路由**；**持久化**靠 `RedisChatMemoryStore`——`RedisChatMemoryStoreConfig` 复用 `spring.data.redis` 前缀（`ttl` 是本项目自己加的字段，单位秒），而 `ChatHistoryServiceImpl#loadChatHistoryToMemory` 在每次创建实例时「先 `clear()` 掉 Redis 键、再从 MySQL 降序查最新 20 条、`reversed()` 成正序灌进去」，所以**Redis 记忆实质上是 MySQL 历史的派生缓存**，MySQL 的 `chat_history` 才是给前端看的全量主存储（含工具调用记录、逻辑删除、按创建者/管理员鉴权）。两个最值得记住的工程实践：**一是加载历史失败时吞异常返回 0 的降级**（记忆是增强能力不是必需能力，MySQL 挂了不该让用户连代码都生成不了），**二是 `@Resource @Lazy AppService` 打破 `AppServiceImpl ↔ ChatHistoryServiceImpl` 的循环依赖**（用代理延迟到首次调用才解析）。读到这里应该能回答：**「用户上一轮说『把导航栏改成蓝色』，这一轮模型是怎么知道的」**——答案是：请求进来 → `getAiCodeGeneratorService(appId, type)` 命中/创建实例 → 创建时从 `chat_history` 表查出最近若干条 → `clear()` 后灌进 `MessageWindowChatMemory` → 记忆被序列化进 Redis → 本次调用时 LangChain4j 把记忆里的 `UserMessage`/`AiMessage` 一起拼进 prompt。

---

## 环节 5：代码解析与文件落盘

### 5.1 这个环节解决什么问题

模型吐出来的是**一段带 markdown 代码围栏的文本**，而平台的预览、下载、部署三件事都要求**磁盘上一组真实文件**。中间必须补上一步转换，这个环节就是干这个的：

1. **解析（parse）**：从模型返回的纯文本里把代码块切出来，塞进结构化的结果对象（`HtmlCodeResult` / `MultiFileCodeResult`）。
2. **落盘（save）**：按固定目录契约把结果对象写成真实文件。

这个环节还是「三种生成类型真正分道扬镳」的地方：

| 生成类型 | 解析 + 落盘 | 走哪条路 |
|---|---|---|
| `HTML` | 走 | 正则抠 `` ```html `` 块 → `tmp/code_output/html_{appId}/index.html` |
| `MULTI_FILE` | 走 | 正则分别抠 html/css/js → `tmp/code_output/multi_file_{appId}/{index.html,style.css,script.js}` |
| `VUE_PROJECT` | **不走** | 完全不经过 `core/parser` 与 `core/saver`，由 AI 通过文件写入工具直接落盘（见环节 3），路径 `tmp/code_output/vue_project_{appId}` |

判断依据在 [AiCodeGeneratorFacade.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L148-L167)：只有 `processCodeStream`（HTML / MULTI_FILE 共用）里才调用 `CodeParserExecutor` 和 `CodeFileSaverExecutor`；VUE 走的是另一个方法 `processTokenStream`（`#L112-L138`），里面只有工具调用回调 + 构建，没有任何 parser/saver 调用。

### 5.2 技术点一：策略接口 + 静态执行器分派

解析侧的结构是「一个接口 + 两个实现 + 一个分派器」，这是标准的**策略模式**：

```
core/parser/
├── CodeParser.java            ← 策略接口：T parseCode(String codeContent)
├── HtmlCodeParser.java        ← 策略实现：CodeParser<HtmlCodeResult>
├── MultiFileCodeParser.java   ← 策略实现：CodeParser<MultiFileCodeResult>
└── CodeParserExecutor.java    ← 分派器：按 CodeGenTypeEnum 选策略
```

接口本身极简，只有一个方法（[CodeParser.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/CodeParser.java#L8-L17)）：

```java
public interface CodeParser<T> {
    T parseCode(String codeContent);
}
```

注意泛型 `T` 的用法：**接口泛型化，但分派器把返回值统一退化成 `Object`**（[CodeParserExecutor.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/CodeParserExecutor.java#L26-L32)）：

```java
public static Object executeParser(String codeContent, CodeGenTypeEnum codeGenTypeEnum) {
    return switch (codeGenTypeEnum) {
        case HTML -> htmlCodeParser.parseCode(codeContent);
        case MULTI_FILE -> multiFileCodeParser.parseCode(codeContent);
        default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
    };
}
```

这是这个环节最需要点明的设计取舍：`switch` 表达式要求各分支返回同一个类型，而 `HtmlCodeResult` 和 `MultiFileCodeResult` 没有公共父类，所以只能用 `Object` 承接；类型信息在保存侧靠**强转**还原（`(HtmlCodeResult) codeResult`）。代价是编译期丢掉了类型检查，类型对不上时只能运行期抛 `ClassCastException`。

三个「项目特有」的细节：

- **执行器不是 Spring Bean**。`CodeParserExecutor` 没有 `@Component`，里面的策略实例是 `private static final` 手工 `new` 的（`#L15-L17`），方法也是 `static`。整个 `core/parser` 和 `core/saver` 包**完全没有 Spring 注解**，不参与依赖注入。
- **`default` 分支是兜底而不是「返回 null」**：`VUE_PROJECT` 落进 `default` 会直接抛 `BusinessException`。所以如果有人把 VUE 类型错传到这条链路上，报错信息是「不支持的代码生成类型」——**这是刻意让错误早暴露，不是漏写**。
- **新增一种类型要改两处 `switch`**：解析器一处、保存器一处（`CodeFileSaverExecutor#L32-L36`）。这是对开闭原则的妥协——用枚举 + `switch` 表达式换取了「所有分派点一眼可见」的可读性，没有引入 `Map<CodeGenTypeEnum, CodeParser>` 注册表。

### 5.3 技术点二：模板方法模式（`public final` 锁死骨架）

保存侧的结构是**模板方法模式**：

```
core/saver/
├── CodeFileSaverTemplate.java            ← 抽象模板：saveCode() 定义骨架
├── HtmlCodeFileSaverTemplate.java        ← 子类：填 3 个钩子
├── MultiFileCodeFileSaverTemplate.java   ← 子类：填 3 个钩子
└── CodeFileSaverExecutor.java            ← 分派器
```

骨架方法被显式声明为 `public final`（[CodeFileSaverTemplate.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/CodeFileSaverTemplate.java#L32-L41)）：

```java
public final File saveCode(T result, Long appId) {
    // 1. 验证输入
    validateInput(result);
    // 2. 构建唯一目录
    String baseDirPath = buildUniqueDir(appId);
    // 3. 保存文件（具体实现交给子类）
    saveFiles(result, baseDirPath);
    // 4. 返回文件目录对象
    return new File(baseDirPath);
}
```

四个步骤的执行顺序被 `final` 固化，子类**只能填内容、不能改流程**。子类需要提供的三个钩子：

| 钩子 | 修饰符 | 子类是否必须实现 | 作用 |
|---|---|---|---|
| `validateInput(T)` | `protected`（有默认实现） | 否，可覆盖 | 默认只校验 `result != null`（`#L62-L66`） |
| `saveFiles(T, String)` | `protected abstract` | 是 | 决定写哪几个文件、叫什么名 |
| `getCodeType()` | `protected abstract` | 是 | 决定目录名前缀 |

`writeToFile` 同样被声明成 `public final`（`#L50-L55`），是模板提供给子类的「受控工具方法」：

```java
public final void writeToFile(String dirPath, String filename, String content) {
    if (StrUtil.isNotBlank(content)) {
        String filePath = dirPath + File.separator + filename;
        FileUtil.writeString(content, filePath, StandardCharsets.UTF_8);
    }
}
```

**「内容为空就静默跳过、不建文件」**是这个方法最重要的语义，也是后面 5.6 里一道陷阱题的考点。

目录命名契约在 `buildUniqueDir`（`#L74-L83`）：

```java
String codeType = getCodeType().getValue();      // html / multi_file
String uniqueDirName = StrUtil.format("{}_{}", codeType, appId);
String dirPath = FILE_SAVE_ROOT_DIR + File.separator + uniqueDirName;
FileUtil.mkdir(dirPath);
```

**`{codeType}_{appId}` 这个格式是全项目共享的硬契约**，被至少四处按同样的规则拼路径，改一处必须四处同改：

| 位置 | 用途 |
|---|---|
| `CodeFileSaverTemplate#L79`（生成时创建） | `tmp/code_output/{codeType}_{appId}` |
| [AppController.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/AppController.java#L132-L135)（下载） | `codeGenType + "_" + appId` |
| [AppServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L159-L161)（部署） | `codeGenType + "_" + appId` |
| [CodeGeneratorNode.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/node/CodeGeneratorNode.java#L41)（工作流） | `String.format("%s/%s_%s", ROOT, generationType.getValue(), appId)` |

对比一下被 `@Deprecated` 的旧实现，能看出模板方法到底省了什么：[CodeFileSaver.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/CodeFileSaver.java#L30-L48) 里 `saveHtmlCodeResult` 和 `saveMultiFileCodeResult` 各自重复了一遍「建目录 + 写文件 + 返回 File」，而新实现把这三步抽到 `saveCode` 里，子类只剩下「写哪几个文件」这一行信息量。

### 5.4 代码走读：核心类

#### （1）`HtmlCodeParser`：正则 + 兜底

[HtmlCodeParser.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/HtmlCodeParser.java#L15-L29)：

```java
private static final Pattern HTML_CODE_PATTERN =
        Pattern.compile("```html\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

@Override
public HtmlCodeResult parseCode(String codeContent) {
    HtmlCodeResult result = new HtmlCodeResult();
    String htmlCode = extractHtmlCode(codeContent);
    if (htmlCode != null && !htmlCode.trim().isEmpty()) {
        result.setHtmlCode(htmlCode.trim());
    } else {
        // 如果没有找到代码块，将整个内容作为HTML
        result.setHtmlCode(codeContent.trim());
    }
    return result;
}
```

三个必须知道的语义：

- **`find()` 只调用一次 → 只取第一个匹配的代码块**。模型如果输出了两个 `` ```html `` 块，第二个会被直接丢掉（不是覆盖、不是拼接）。
- **`\s*\n` 里的 `\n` 不能省**。它要求语言标记后面必须跟一个换行符；模型如果写成 `` ```html <!DOCTYPE html> ``（同一行），这条正则匹配不到，会掉进「整段当 HTML」的兜底分支——此时 `index.html` 里会带着围栏和说明文字，浏览器仍能渲染出内容，但文件里混着 markdown 垃圾。
- **兜底分支是这个解析器唯一「容错」的地方**。`MultiFileCodeParser` 没有等价兜底（见下），所以 HTML 模式的容错性明显强于多文件模式。

`MultiFileCodeParser` 就是同一套正则复制三份（[MultiFileCodeParser.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/MultiFileCodeParser.java#L15-L17)），JS 那条多接受一个别名：

```java
private static final Pattern HTML_CODE_PATTERN = Pattern.compile("```html\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
private static final Pattern CSS_CODE_PATTERN  = Pattern.compile("```css\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
private static final Pattern JS_CODE_PATTERN   = Pattern.compile("```(?:js|javascript)\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
```

它的 `parseCode`（`#L20-L39`）对三种代码的处理是**非对称**的：HTML 为空时 `setHtmlCode` 不会被调用（结果对象里 `htmlCode` 保持 `null`），CSS/JS 同理——**它自己不做任何校验，校验全部推给了保存器**。这就是为什么「HTML 不能为空」这条规则写在 `HtmlCodeFileSaverTemplate#validateInput` 而不是解析器里。

#### （2）`CodeFileSaverTemplate`：目录、校验、写入

完整骨架已在 5.3 给出，这里补三个易被忽略的点：

- **`FILE_SAVE_ROOT_DIR` 是 `private static final` 但值来自 `AppConstant`**（`#L23`）。`AppConstant.CODE_OUTPUT_ROOT_DIR` 的定义是 `System.getProperty("user.dir") + "/tmp/code_output"`（[AppConstant.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/constant/AppConstant.java#L21)），因为含方法调用，**它不是编译期常量**，而是在 `AppConstant` 类初始化时才求值——所以 `tmp` 目录的绝对位置取决于**进程启动时的工作目录**，IDEA 里跑和 `java -jar` 从别的目录跑，落盘位置可能不同。
- **`buildUniqueDir` 的 Javadoc 是陈旧的**：注释写的是「`tmp/code_output/bizType_雪花 ID`」，实际代码用的是 `appId`。这句话是从废弃的 [CodeFileSaver.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/CodeFileSaver.java#L50-L61)（那里确实用 `IdUtil.getSnowflakeNextIdStr()`）复制过来忘了改。**别照着注释去理解行为**——这也说明改成雪花 ID 会立刻打断「按 appId 找目录」的四处调用方。
- **`mkdir` 而不是 `mkdirs` 的差异**：这里用的是 `FileUtil.mkdir(dirPath)`（Hutool 会递归创建父目录），所以 `tmp/code_output` 不存在时也能一次建好。

#### （3）两个子类：只有「写哪几个文件」不同

[HtmlCodeFileSaverTemplate.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/HtmlCodeFileSaverTemplate.java#L16-L33)：

```java
@Override
protected CodeGenTypeEnum getCodeType() { return CodeGenTypeEnum.HTML; }

@Override
protected void saveFiles(HtmlCodeResult result, String baseDirPath) {
    writeToFile(baseDirPath, "index.html", result.getHtmlCode());
}

@Override
protected void validateInput(HtmlCodeResult result) {
    super.validateInput(result);                       // 先跑父类的 null 校验
    if (StrUtil.isBlank(result.getHtmlCode())) {       // 再追加自己的非空校验
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HTML 代码不能为空");
    }
}
```

[MultiFileCodeFileSaverTemplate.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/MultiFileCodeFileSaverTemplate.java#L22-L38)：

```java
@Override
protected void saveFiles(MultiFileCodeResult result, String baseDirPath) {
    writeToFile(baseDirPath, "index.html", result.getHtmlCode());
    writeToFile(baseDirPath, "style.css", result.getCssCode());
    writeToFile(baseDirPath, "script.js", result.getJsCode());
}

@Override
protected void validateInput(MultiFileCodeResult result) {
    super.validateInput(result);
    // 至少要有 HTML 代码，CSS 和 JS 可以为空
    if (StrUtil.isBlank(result.getHtmlCode())) {
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HTML代码内容不能为空");
    }
}
```

两个子类都**先调 `super.validateInput(result)` 再追加自己的规则**，这是模板方法里覆盖钩子的正确写法（不写 `super` 就会丢掉 `result == null` 的保护）。文件名 `index.html` / `style.css` / `script.js` 是硬编码的——模型即使生成了 `app.css` 也不会被采纳，因为解析器只认 `` ```css `` 围栏、保存器只写这三个固定名字。

#### （4）`CodeFileSaverExecutor`：强转发生的地方

[CodeFileSaverExecutor.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/CodeFileSaverExecutor.java#L31-L37)：

```java
public static File executeSaver(Object codeResult, CodeGenTypeEnum codeGenType, Long appId) {
    return switch (codeGenType) {
        case HTML -> htmlCodeFileSaver.saveCode((HtmlCodeResult) codeResult, appId);
        case MULTI_FILE -> multiFileCodeFileSaver.saveCode((MultiFileCodeResult) codeResult, appId);
        default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型: " + codeGenType);
    };
}
```

入参是 `Object`，靠 `codeGenType` 决定强转成什么——**参数类型和强转目标之间没有任何编译期或运行期的交叉校验**。调用方必须保证「解析时用的类型」和「保存时用的类型」是同一个，否则这里直接 `ClassCastException`。

#### （5）调用链：解析和落盘在哪里被触发

真正的触发点在 [AiCodeGeneratorFacade.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L148-L167)：

```java
private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenType, Long appId) {
    // 字符串拼接器，用于当流式返回所有的代码之后，再保存代码
    StringBuilder codeBuilder = new StringBuilder();
    return codeStream.doOnNext(chunk -> {
        codeBuilder.append(chunk);          // 边流边攒，不落盘
    }).doOnComplete(() -> {
        try {
            String completeCode = codeBuilder.toString();
            Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
            File saveDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
            log.info("保存成功，目录为：{}", saveDir.getAbsolutePath());
        } catch (Exception e) {
            log.error("保存失败: {}", e.getMessage());
        }
    });
}
```

完整链路（以 HTML 模式为例）：

```
AppController#chatToGenCode
  └─ AppServiceImpl#chatToGenCode（L109）
       └─ AiCodeGeneratorFacade#generateAndSaveCodeStream（L79）
            └─ AiCodeGeneratorFacade#processCodeStream（L148）
                 ├─ doOnNext：每个 token 只 append 进 StringBuilder（内存里攒，不落盘）
                 └─ doOnComplete（流结束后才执行一次）
                      ├─ CodeParserExecutor.executeParser       → HtmlCodeResult
                      └─ CodeFileSaverExecutor.executeSaver     → File（tmp/code_output/html_{appId}）
```

这里有两个**必须点明的工程风险**：

1. **攒全量再落盘**。`StringBuilder` 把整段代码留在内存，流式只体现在「前端能看到打字机效果」，落盘反而是最后一次性写完的。好处是解析器能拿到完整文本（正则跨行匹配的前提），代价是长代码（Vue 工程那种几千行）内存占用更高——这也是 VUE 模式改用「工具调用边生成边写文件」的原因之一。
2. **`catch (Exception e) { log.error(...) }` 吞掉了所有异常**。落盘失败（磁盘满、目录被删、解析正则没匹配到导致校验抛错）时，异常不会往下游传播，**SSE 流仍然正常 complete，前端会以为生成成功**，用户看到的是一个没有代码的预览页。这是本环节最值得记住的坑：查「生成了但没文件」的问题，第一站是后端日志里的「保存失败」。

### 5.5 动手验证

**前置**：应用已启动（`http://localhost:8123/api`），已登录并创建过一个 HTML 类型应用。

**① 确认落盘目录契约**

```powershell
# 看目录名是不是 {codeType}_{appId}
Get-ChildItem e:\AI\GitHub\yu-ai-code-mother\tmp\code_output | Select-Object Name, LastWriteTime
```

预期：出现 `html_1`、`multi_file_2`、`vue_project_3` 这类**「类型_应用ID」**命名的目录（前提是从项目根目录启动；换目录启动时会落在那个目录下的 `tmp\code_output`）。看不到目录说明 5.4（5）里的异常被吞了，去日志搜「保存失败」。

**② 确认文件集合**

```powershell
Get-ChildItem e:\AI\GitHub\yu-ai-code-mother\tmp\code_output\multi_file_2
```

预期：`index.html`、`style.css`、`script.js`。如果模型没输出 `` ```css `` 围栏，**`style.css` 这个文件会不存在**（`writeToFile` 判空跳过），这是正常行为不是 bug。

**③ 确认 index.html 里没有 markdown 垃圾**

```powershell
Get-Content e:\AI\GitHub\yu-ai-code-mother\tmp\code_output\html_1\index.html -TotalCount 3
```

预期：第一行就是 `<!DOCTYPE html>` 或 `<html`。如果看到第一行是 `` ```html `` 或一句中文说明，说明正则没匹配上、走了「整段当 HTML」的兜底分支。

**④ 验证静态资源服务（预览链路）**

```powershell
# 带斜杠：应返回 index.html 内容
Invoke-WebRequest http://localhost:8123/api/static/html_1/ -UseBasicParsing |
  Select-Object StatusCode, @{n='ContentType';e={$_.Headers['Content-Type']}}

# 不带斜杠：应返回 301 + Location
Invoke-WebRequest http://localhost:8123/api/static/html_1 -UseBasicParsing -MaximumRedirection 0 |
  Select-Object StatusCode, @{n='Location';e={$_.Headers['Location']}}

# 不存在的文件：应返回 404
Invoke-WebRequest http://localhost:8123/api/static/html_1/nope.css -UseBasicParsing -SkipHttpErrorCheck |
  Select-Object StatusCode

# CSS 的 Content-Type
Invoke-WebRequest http://localhost:8123/api/static/multi_file_2/style.css -UseBasicParsing -SkipHttpErrorCheck |
  Select-Object StatusCode, @{n='ContentType';e={$_.Headers['Content-Type']}}
```

预期：分别得到 `200` + `text/html; charset=UTF-8`、`301` + Location 为原 URL 加 `/`、`404`、`200` + `text/css; charset=UTF-8`。注意路径变量名叫 `deployKey`，实际传的是 `tmp/code_output` 下的**目录名**（即 `{codeType}_{appId}`），不是数据库里的 `deployKey` 字段。

**⑤ 数据库侧对照**

```sql
-- 确认 codeGenType 的值和目录前缀完全一致
SELECT id, appName, codeGenType, deployKey, deployedTime
FROM app
ORDER BY id DESC
LIMIT 5;
```

预期：`codeGenType` 的值只有 `html` / `multi_file` / `vue_project` 三种，且与 `tmp/code_output` 下目录的前缀一一对应。`deployKey` 为空表示从未部署过；部署后它是 6 位随机串（`RandomUtil.randomString(6)`，`AppServiceImpl#L156`），并且只在 `tmp/code_deploy/{deployKey}` 里出现，**不会出现在生成目录的命名里**。

**⑥ 验证解析器的两个边界（不改代码就能复现）**

把模型返回想象成两种文本，直接对照 `CodeParserTest` 里的用例格式验证：

```powershell
# 跑仓库里现存的解析器单测（注意：它测的是废弃类 core/CodeParser）
mvn -q -Dtest=CodeParserTest test
```

预期：两个用例都通过。用例文本见 [CodeParserTest.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/test/java/com/yupi/yuaicodemother/core/CodeParserTest.java#L13-L27)：代码块前后都故意加了「随便写一段描述」，验证的正是「正则能跳过围栏外的废话」。

### 5.6 自测题

<details>
<summary>点击展开题目（14 题，含 4 道陷阱题）</summary>

1. `CodeParserExecutor.executeParser` 的返回类型为什么是 `Object`？这样设计的代价是什么？
2. 为什么 `CodeFileSaverTemplate#saveCode` 要声明成 `public final` 而不是 `public`？
3. `buildUniqueDir` 的注释写的是「`{bizType}_雪花 ID`」，实际代码用的是 `appId`。这说明什么？如果真改成雪花 ID 会破坏哪些功能？
4. 模型返回里出现了两个 `` ```html `` 代码块，落盘的 `index.html` 是哪一个？为什么？
5. HTML 模式（单文件）下，模型没有按格式输出 `` ```html `` 围栏，会怎样？
6. MULTI_FILE 模式下模型只给了 HTML，`style.css` 会存在吗？如果 HTML 里写了 `<link rel="stylesheet" href="style.css">`，预览会发生什么？
7. **（陷阱）** VUE_PROJECT 类型调用 `CodeParserExecutor.executeParser(code, VUE_PROJECT)` 会返回什么？
8. `writeToFile` 为什么要做 `StrUtil.isNotBlank(content)` 判空？不判会怎样？
9. `AppConstant.CODE_OUTPUT_ROOT_DIR` 这个值在什么时刻被确定？IDEA 启动和 `java -jar` 从别的目录启动，落盘位置一样吗？
10. **（陷阱）** 解析或保存过程中抛异常，前端 SSE 会收到错误事件吗？
11. `StaticResourceController` 的路径变量叫 `deployKey`，但它拼出来的目录其实是哪一个？预览读的目录和部署读的目录分别是哪个？
12. `core/CodeParser.java` 和 `core/CodeFileSaver.java` 已经标了 `@Deprecated`，为什么不能直接删？
13. 正则里的 `\s*\n` 中那个 `\n` 能不能省掉？举一个省掉后会行为变化的例子。
14. **（陷阱）** 正则加了 `Pattern.CASE_INSENSITIVE`，模型输出 `` ```HTML `` 能解析吗？那 `` ```Html `` 呢？CSS 和 JS 呢？

</details>

<details>
<summary>点击展开答案与解析</summary>

**1.** 因为 `switch` 表达式要求所有分支返回同一类型，而 `HtmlCodeResult` 与 `MultiFileCodeResult` 是两个互不相关的类（都在 `ai/model` 包下，`@Data` + `@Description`，没有公共父类）。代价是分派器丢掉了泛型信息，保存侧必须靠 `(HtmlCodeResult) codeResult` 强转还原，类型不匹配只能运行期抛 `ClassCastException`，编译器帮不上忙。要修的话得引入公共接口/父类，但这两个对象是给 LangChain4j 做结构化输出用的，加继承层级会污染 AI 模型定义。

**2.** 锁死骨架。模板方法模式的核心是「流程由父类定、内容由子类填」，如果 `saveCode` 只是 `public`，子类可以覆盖它，把「校验 → 建目录 → 写文件」的顺序改掉，模板方法就退化成普通继承。`public final` 让「四个步骤、固定顺序」成为不可绕过的契约；同理 `writeToFile` 也是 `final`，防止子类改写写入语义（比如去掉判空、换编码）。

**3.** 说明注释是从废弃类复制过来的陈旧文档。废弃的 [CodeFileSaver.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/CodeFileSaver.java#L57) 里确实用 `IdUtil.getSnowflakeNextIdStr()`，新模板改用 `appId` 后 Javadoc 忘了改。**读代码要以代码为准，不能以注释为准。** 真改成雪花 ID，会立刻打断四处按 `{codeType}_{appId}` 拼路径的调用方：生成时目录名和下载（`AppController#L134`）、部署（`AppServiceImpl#L160`）、工作流（`CodeGeneratorNode#L41`）算出来的目录名对不上，表现为「能生成但下载/部署报『应用代码不存在』」。

**4.** 第一个。`extractHtmlCode` 只调了一次 `matcher.find()`，取到第一个匹配就返回，后面的代码块既不覆盖也不拼接。这也是为什么提示词里要明确要求「只输出一个代码块」。

**5.** 走兜底分支：`extractHtmlCode` 返回 `null`，`result.setHtmlCode(codeContent.trim())`，也就是**把整段回复（含围栏、含解释文字）当成 HTML 存进 `index.html`**。浏览器仍会渲染出页面（HTML 解析器会忽略 markdown 垃圾），但文件不干净，下载下来给别人看会显得很怪。这是 HTML 模式特有的容错，`MultiFileCodeParser` 没有等价兜底。

**6.** `style.css` 不会存在。`saveFiles` 无条件调了 `writeToFile(baseDirPath, "style.css", result.getCssCode())`，但 `cssCode` 为 `null` 时 `StrUtil.isNotBlank` 为假，直接跳过，文件不创建。此时 HTML 里的 `<link href="style.css">` 请求会打到 `StaticResourceController`，`file.exists()` 为假 → 返回 `404`，页面无样式但不报错（浏览器对 404 的样式表是静默忽略）。注意校验规则是**「HTML 必须有，CSS/JS 可以没有」**，不是三个都要。

**7.** **抛异常，不会返回任何东西。** `CodeParserExecutor` 的 `switch` 只有 `HTML` 和 `MULTI_FILE` 两个分支，`VUE_PROJECT` 落进 `default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型")`。这是刻意设计：VUE 类型根本不该走这条链路，让它早失败而不是返回 `null` 到下游引发更难查的空指针。同理 `CodeFileSaverExecutor` 的 `default` 也会抛。

**8.** 因为「文件内容为空」和「这个文件不该存在」在业务上等价：模型只给了 HTML 时，不应该在磁盘上留一个 0 字节的 `style.css`（空文件会让下载的 zip 里多出垃圾文件，也会让「文件是否存在」这个判断失去意义）。不判空的后果是 `FileUtil.writeString(null, ...)` 的行为不可控（取决于 Hutool 内部实现，可能写空串也可能抛 NPE），落盘结果不确定。

**9.** 在 `AppConstant` 类**初始化时**确定（不是编译期）。`CODE_OUTPUT_ROOT_DIR = System.getProperty("user.dir") + "/tmp/code_output"` 含方法调用，不是编译期常量，`CodeFileSaverTemplate#L23` 的 `private static final String FILE_SAVE_ROOT_DIR = AppConstant.CODE_OUTPUT_ROOT_DIR` 同样在运行期赋值。`user.dir` 是**进程的工作目录**：IDEA 默认是项目根目录，`java -jar` 则是执行命令时所在的 shell 目录（不是 jar 所在目录）。所以从别处启动会把文件写到别处——这是排查「目录找不到」时第一个要确认的点。

**10.** **不会。** [AiCodeGeneratorFacade#L156-L165](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L154-L166) 的 `doOnComplete` 里整个逻辑被 `try { ... } catch (Exception e) { log.error("保存失败: {}", e.getMessage()); }` 包住，异常被吞掉、只打日志。下游 SSE 的 `onComplete` 照常发出，前端收到的是「正常结束」，于是用户看到生成完成但预览是空的。这是本环节最大的坑，也是「日志里出现『保存失败』但用户无感知」的根因。

**11.** 拼的是 `PREVIEW_ROOT_DIR + "/" + deployKey + resourcePath`，而 `PREVIEW_ROOT_DIR = AppConstant.CODE_OUTPUT_ROOT_DIR`（[StaticResourceController.java#L26](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/StaticResourceController.java#L26-L51)）。也就是说，**这里的 `deployKey` 实际语义是 `tmp/code_output` 下的目录名，也就是 `{codeType}_{appId}`**，参数名是从部署场景沿用过来的，属于命名误导。两个目录的分工是：**预览**读 `tmp/code_output`（`CODE_OUTPUT_ROOT_DIR`，生成产物原地预览）；**部署**把内容复制到 `tmp/code_deploy/{真 deployKey}`（`CODE_DEPLOY_ROOT_DIR`，`AppServiceImpl#L180-L182`），对外由 nginx 按 `code.deploy-host` 提供访问。

**12.** 因为 `src/test` 里还有引用：[CodeParserTest.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/test/java/com/yupi/yuaicodemother/core/CodeParserTest.java#L28) 直接调 `CodeParser.parseHtmlCode` / `parseMultiFileCode`。生产代码（`src/main`）已经全部改用 `core/parser` 下的新实现，但删掉旧类会让这个测试编译失败。所以这两个 `@Deprecated` 类是**「生产已下线、测试还挂着」**的中间状态，正确做法是把测试改写成测 `HtmlCodeParser`/`MultiFileCodeParser` 之后再删。

**13.** 不能省。`\s*\n` 的语义是「语言标记后允许有空白，但**必须换行**」。省掉 `\n` 后 `` ```html <!DOCTYPE html> ``（标记和代码同行）也能匹配上，看起来更宽容；但保留 `\n` 是有意的**格式约束**——提示词里明确要求模型「代码块另起一行」，正则用来强制这个格式，格式不对就走兜底/校验失败，从而暴露提示词退化问题。反过来，如果模型输出 `` ```html\r\n ``，`\s*` 会吃掉 `\r`，所以 CRLF 也能匹配。

**14.** 都能解析。`Pattern.CASE_INSENSITIVE` 让语言标记的大小写不敏感，`` ```HTML ``、`` ```Html `` 都能匹配 `html`；JS 那条 `(?:js|javascript)` 加上这个 flag 后，`` ```JS ``、`` ```JavaScript `` 也可以。注意这个 flag **只影响字面量的字母匹配**，不影响 `[\s\S]*?` 的贪婪语义——`[\s\S]` 本身已经是「任意字符含换行」的写法（不能用 `.`，因为默认 `.` 不匹配换行），非贪婪 `*?` 保证在遇到第一个 `` ``` `` 时停下。

</details>

### 5.7 本环节技术点清单

| 技术点 | 落地位置 | 作用 | 项目特有之处 |
|---|---|---|---|
| 策略模式 | `core/parser/CodeParser.java#L8-L17` | 抽象「文本 → 结构化结果」 | 泛型接口，但分派器返回 `Object`，类型信息在运行期靠强转还原 |
| 模板方法模式 | `core/saver/CodeFileSaverTemplate.java#L32-L41` | 固化「校验 → 建目录 → 写文件 → 返回」骨架 | `saveCode` 与 `writeToFile` 都是 `public final`，子类只能填 3 个钩子 |
| 静态分派器 | `CodeParserExecutor#L26-L32`、`CodeFileSaverExecutor#L31-L37` | 用 `switch` 表达式按枚举选实现 | 纯静态工具类、**不是 Spring Bean**；`default` 分支直接抛 `BusinessException` |
| 枚举驱动 | `CodeGenTypeEnum#L12-L14` | `value` 同时是目录前缀 | `html` / `multi_file` / `vue_project`，与磁盘目录名强绑定 |
| 正则提取 | `HtmlCodeParser#L15`、`MultiFileCodeParser#L15-L17` | 抠 markdown 代码围栏 | `\s*\n` 强制换行；`find()` 只取第一个块；JS 支持 `js\|javascript` 别名 |
| 容错兜底 | `HtmlCodeParser#L24-L26` | 正则失配时不丢内容 | 把整段回复当 HTML；**`MultiFileCodeParser` 无此兜底**，容错性不对称 |
| 非对称校验 | `HtmlCodeFileSaverTemplate#L27-L33`、`MultiFileCodeFileSaverTemplate#L32-L38` | 决定哪些文件必须存在 | 「HTML 必须有、CSS/JS 可空」；子类覆盖钩子时先调 `super` |
| 静默跳过空文件 | `CodeFileSaverTemplate#L50-L55` | 避免产生 0 字节垃圾文件 | 判空后**不创建文件**，而不是创建空文件 |
| 目录命名契约 | `CodeFileSaverTemplate#L74-L83` | 让「生成 / 下载 / 部署 / 工作流」能按规则反查路径 | `{codeType}_{appId}`，**四处调用方硬编码同一规则**；Javadoc 说雪花 ID 是陈旧注释 |
| 流式攒批落盘 | `AiCodeGeneratorFacade#L148-L167` | 流式只给前端看，落盘在流结束后一次性完成 | 正则跨行匹配要求拿到完整文本，所以不能边流边解析 |
| 异常吞没 | `AiCodeGeneratorFacade#L163-L165` | 落盘失败不影响已建立的 SSE 连接 | **只 `log.error`，前端仍收到正常完成**——排查「生成了没文件」的关键线索 |
| 静态资源服务 | `StaticResourceController#L32-L65` | 预览生成产物 | 从 `tmp/code_output` 读，路径变量名 `deployKey` 实为目录名；目录访问 301 补斜杠，默认补 `index.html`，按扩展名给 Content-Type |

### 5.8 本环节产出

这个环节是把「模型的一段文本」变成「磁盘上一组文件」的转换器，它的技术选型围绕一句话展开：**分派用枚举 `switch`，流程用模板方法锁死，内容用正则抠出来，落盘用固定命名契约。** 解析侧 `CodeParser<T>` 是策略接口、`CodeParserExecutor` 用 `switch` 表达式按 `CodeGenTypeEnum` 选实现（不是 Spring Bean、不是 Map 注册表，新增类型要改两处 `switch`）；保存侧 `CodeFileSaverTemplate#saveCode` 用 `public final` 固化「校验 → 建目录 → 写文件 → 返回」四步，子类只填 `getCodeType` / `validateInput` / `saveFiles` 三个钩子——这比废弃的 `CodeFileSaver`（两个静态方法各自重复一遍建目录+写文件）少了一大截重复代码。目录名 `{codeType}_{appId}` 是贯穿生成、下载（`AppController#L134`）、部署（`AppServiceImpl#L160`）、工作流（`CodeGeneratorNode#L41`）的硬契约，注释里那句「雪花 ID」是从旧实现复制来的陈旧文档，**以代码为准**。三个最容易踩的坑：**一是 `HtmlCodeParser` 的正则只取第一个代码块、`MultiFileCodeParser` 没有兜底分支**，格式一退化行为就不对称；**二是 `writeToFile` 判空后直接不建文件**，所以「CSS 缺失」表现为预览 404 而不是报错；**三是 `doOnComplete` 里 `catch (Exception)` 只打日志**，落盘失败时 SSE 照常完成、前端以为成功，查「生成了但没文件」必须去翻后端日志的「保存失败」。另外要记住 VUE_PROJECT **完全不经过这两个包**（走工具调用写文件），所以 `CodeParserExecutor` / `CodeFileSaverExecutor` 的 `default` 分支会对它抛异常——这是刻意让错误早暴露。读到这里应该能回答：**「用户说『做个个人主页』，HTML 模式下那个 `tmp/code_output/html_1/index.html` 到底是怎么被写出来的」**——答案是：`AiCodeGeneratorFacade#processCodeStream` 在 `doOnNext` 里把每个 token 攒进 `StringBuilder`，流结束后 `doOnComplete` 调 `CodeParserExecutor` 用 `\`\`\`html\s*\n([\s\S]*?)\`\`\`` 抠出代码块（抠不到就把整段当 HTML），再调 `CodeFileSaverExecutor` → `CodeFileSaverTemplate#saveCode` → `buildUniqueDir` 建 `html_1` 目录 → `writeToFile` 以 UTF-8 写 `index.html`，最后前端通过 `GET /api/static/html_1/` 由 `StaticResourceController` 读同一个目录把页面渲染出来。

---

## 环节 6：Vue 工程构建与一键部署

### 6.1 这个环节解决什么问题

`VUE_PROJECT` 模式的产物是**一份前端源码工程**（`index.html` + `package.json` + `vite.config.js` + `src/**`），而不是能直接丢给浏览器的静态页面。所以它比 HTML/MULTI_FILE 多出两个必须解决的问题：

1. **构建**：源码工程里是 `.vue` 单文件组件、ESM 的 `import`，浏览器根本不认识，必须先跑 `npm install` + `npm run build` 产出 `dist/` 里的静态资源。
2. **部署**：把「生成产物」变成「一个可以对外分享的 URL」。这需要复制到一个独立目录、给一个不可猜的短 key、再交给静态服务器（nginx）暴露出去。

一句话概括分工：**HTML/MULTI_FILE 生成完就能预览（原地读 `tmp/code_output`），VUE 必须先构建、再部署才能访问。**

### 6.2 技术点一：用 `Process` 调外部命令（超时 + 强杀）

Java 里跑 `npm` 只有一条路——起子进程。项目里唯一的实现是 [VueProjectBuilder.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/builder/VueProjectBuilder.java#L120-L147)：

```java
private boolean executeCommand(File workingDir, String command, int timeoutSeconds) {
    try {
        log.info("在目录 {} 中执行命令: {}", workingDir.getAbsolutePath(), command);
        Process process = RuntimeUtil.exec(
                null,
                workingDir,
                command.split("\\s+") // 命令分割为数组
        );
        // 等待进程完成，设置超时
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            log.error("命令执行超时（{}秒），强制终止进程", timeoutSeconds);
            process.destroyForcibly();
            return false;
        }
        int exitCode = process.exitValue();
        if (exitCode == 0) {
            log.info("命令执行成功: {}", command);
            return true;
        } else {
            log.error("命令执行失败，退出码: {}", exitCode);
            return false;
        }
    } catch (Exception e) {
        log.error("执行命令失败: {}, 错误信息: {}", command, e.getMessage());
        return false;
    }
}
```

四个关键点：

| 细节 | 代码位置 | 为什么这么做 |
|---|---|---|
| `RuntimeUtil.exec(null, workingDir, String...)` | `#L123-L127` | Hutool 对 `Runtime.exec` 的封装；`workingDir` 让 npm 在项目目录里执行（`npm install` 依赖 cwd 找 `package.json`） |
| `command.split("\\s+")` | `#L126` | **传数组而不是整串字符串**，避免走 shell、也避免 `Runtime.exec(String)` 内部按空格拆分的隐式行为 |
| `waitFor(timeout, SECONDS)` + `destroyForcibly()` | `#L129-L133` | 超时后强杀，防止一个卡死的 npm 永久占住线程 |
| 统一返回 `boolean` | `#L135-L146` | 失败一律 `log.error` + `return false`，**不抛异常**；`catch (Exception)` 兜住 `IOException` |

超时值是两个不同的数字（`#L75-L88`）：

```java
private boolean executeNpmInstall(File projectDir) {
    String command = String.format("%s install", buildCommand("npm"));
    return executeCommand(projectDir, command, 300); // 5分钟超时
}

private boolean executeNpmBuild(File projectDir) {
    String command = String.format("%s run build", buildCommand("npm"));
    return executeCommand(projectDir, command, 180); // 3分钟超时
}
```

**install 给 5 分钟、build 给 3 分钟**，理由是 install 要联网下载依赖（时间不可控），build 是纯本地编译（时间可预测）。这是「按操作性质给超时」而不是「统一给一个大值」。

还有一处平台适配（`#L96-L110`）：

```java
private String buildCommand(String baseCommand) {
    if (isWindows()) {
        return baseCommand + ".cmd";
    }
    return baseCommand;
}

private boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("windows");
}
```

Windows 上 `npm` 实际是 `npm.cmd` 批处理脚本，`Runtime.exec` 不走 shell 的 `PATHEXT` 解析，直接找 `npm` 会抛「找不到文件」。所以命令字符串实际是 `npm.cmd install`，Linux/Mac 上则是 `npm install`。

### 6.3 技术点二：虚拟线程做后台任务（以及一个「写了但没用」的方法）

`VueProjectBuilder` 提供了两个入口（`#L22-L31`、`#L39-L70`）：

```java
public void buildProjectAsync(String projectPath) {
    Thread.ofVirtual().name("vue-builder-" + System.currentTimeMillis())
            .start(() -> {
                try {
                    buildProject(projectPath);
                } catch (Exception e) {
                    log.error("异步构建 Vue 项目时发生异常: {}", e.getMessage(), e);
                }
            });
}
```

- 用 **JDK 21 虚拟线程**（`Thread.ofVirtual().name(...).start(...)`，builder 写法），不用 `@Async`、不用自定义 `ThreadPoolTaskExecutor`、不用消息队列。构建是「重 IO、长时间阻塞」的任务，正好是虚拟线程的适用场景。
- `try-catch` 把异常关在虚拟线程内部——异步任务抛异常没人接，必须自己吞掉并打日志。

**但这个方法是死代码。** 全项目搜索 `buildProjectAsync` 只有它自己的定义和内部对 `buildProject` 的调用，**没有任何业务代码调用它**。主链路上 Vue 的构建是**同步**的，在 [AiCodeGeneratorFacade.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L126-L131)：

```java
.onCompleteResponse((ChatResponse response) -> {
    // 执行 Vue 项目构建（同步执行，确保预览时项目已就绪）
    String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId;
    vueProjectBuilder.buildProject(projectPath);
    sink.complete();
})
```

**「同步执行，确保预览时项目已就绪」**这句注释解释了这个反直觉的选择：前端在 SSE 收到 `done` 之后会立刻去请求预览地址，如果构建在后台异步跑，`dist` 还没生成，预览必然是 404。代价是 **SSE 连接会被构建阻塞住**（`npm install` 首次执行可能几分钟），用户看到的是「生成完了但转圈很久才结束」——这也正是 `destroyForcibly` 超时保护存在的意义。

注意这里还有第二个坑：`processTokenStream` 里构建用的是硬编码路径 `CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId`，而工具写文件的目录也必须是同一个（见环节 3 的 `FileWriteTool`），两边靠 `vue_project_{appId}` 这个命名约定对齐。

### 6.4 代码走读

#### （1）`buildProject`：五道关卡，全部返回 boolean

[VueProjectBuilder.java#L39-L70](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/builder/VueProjectBuilder.java#L39-L70)：

```java
public boolean buildProject(String projectPath) {
    File projectDir = new File(projectPath);
    if (!projectDir.exists() || !projectDir.isDirectory()) {
        log.error("项目目录不存在：{}", projectPath);
        return false;
    }
    // 检查是否有 package.json 文件
    File packageJsonFile = new File(projectDir, "package.json");
    if (!packageJsonFile.exists()) {
        log.error("项目目录中没有 package.json 文件：{}", projectPath);
        return false;
    }
    log.info("开始构建 Vue 项目：{}", projectPath);
    if (!executeNpmInstall(projectDir)) { ... return false; }
    if (!executeNpmBuild(projectDir)) { ... return false; }
    // 验证 dist 目录是否生成
    File distDir = new File(projectDir, "dist");
    if (!distDir.exists() || !distDir.isDirectory()) { ... return false; }
    return true;
}
```

五道关卡依次是：目录存在 → `package.json` 存在 → install 成功 → build 成功 → `dist` 生成。**注意最后一道 `dist` 校验**：即使 npm 返回退出码 0，也要确认产物真的在，因为 `package.json` 里可能压根没配 `build` 脚本（`npm run build` 对不存在的脚本会失败，但换个写法就可能「成功但什么都没产出」）。

另外，**代码里没有任何地方消费子进程的 stdout/stderr**。所以 `npm` 的报错详情不会出现在后端日志里，日志里只有 `命令执行失败，退出码: 1`——排查构建失败时这是最大的障碍，需要手工到项目目录里重跑一遍 npm 才能看到真实原因。

#### （2）构建产物长什么样：契约来自提示词

「AI 要生成什么结构、必须能构建成功」这件事不是代码约束的，而是**写死在提示词**里的 [codegen-vue-project-system-prompt.txt](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/resources/prompt/codegen-vue-project-system-prompt.txt)。三条与构建直接相关的硬约束：

| 约束 | 提示词位置 | 与构建的关系 |
|---|---|---|
| 必须生成 `package.json`、`vite.config.js`、`src/**` 这套结构 | `#L14-L28` | 不生成 `package.json`，`buildProject` 第二道关卡直接 `return false` |
| `vite.config.js` **必须配 `base: './'`** | `#L41-L55` | 相对路径 base，才能部署到任意子路径（`http://<IP>/dist/{deployKey}/`）而不丢资源 |
| 路由**必须用 hash 模式** | `#L57-L67` | `createWebHashHistory()` 不需要服务端做 history fallback，静态服务器直接托管即可 |
| 依赖只用 `vue` / `vue-router`，**禁止状态管理库、类型校验库、格式化库** | `#L36`、`#L69-L85` | 依赖越少，`npm install` 越快越不容易失败 |

提示词里的「质量检验标准」（`#L109-L115`）直接对应代码的校验点：`npm install` 成功、`npm run dev` 能起、`npm run build` 成功、构建后能在任意子路径部署。**也就是说「能不能构建成功」是提示词和 Java 代码之间的口头契约，代码只负责验证结果，不负责保证生成质量。**

#### （3）`deployApp`：11 步的一键部署

[AppServiceImpl.java#L140-L197](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L140-L197)，入口是 `POST /api/app/deploy`（[AppController.java#L95-L109](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/AppController.java#L95-L109)），请求体 `{"appId": 3}`：

| 步 | 行号 | 做什么 | 关键点 |
|---|---|---|---|
| 1 | `#L143-L144` | 校验 `appId`、`loginUser` | 用 `ThrowUtils.throwIf` |
| 2 | `#L146-L147` | 查应用 | 不存在抛 `NOT_FOUND_ERROR` |
| 3 | `#L149-L151` | 权限校验 | **仅创建者本人可部署**（`app.getUserId().equals(loginUser.getId())`） |
| 4 | `#L153-L157` | 取/生成 `deployKey` | 为空才生成 `RandomUtil.randomString(6)`，**重复部署 URL 不变** |
| 5 | `#L159-L161` | 拼生成目录 | `codeGenType + "_" + appId`（与环节 5 的契约一致） |
| 6 | `#L163-L166` | 检查生成目录存在 | 不存在提示「请先生成应用」 |
| 7 | `#L168-L178` | **Vue 特判** | 构建 → 校验 `dist` → `sourceDir = distDir` |
| 8 | `#L180-L185` | 复制到部署目录 | `FileUtil.copyContent(sourceDir, new File(deployDirPath), true)` |
| 9 | `#L187-L192` | 更新数据库 | 写 `deployKey` + `deployedTime = LocalDateTime.now()` |
| 10 | `#L194` | 拼访问 URL | `String.format("%s/%s/", deployHost, deployKey)` |
| 11 | `#L195` | 异步截图 | `generateAppScreenshotAsync(appId, appDeployUrl)`（详见环节 7） |

第 7 步是三种类型的分水岭：

```java
CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT) {
    // Vue 项目需要构建
    boolean buildSuccess = vueProjectBuilder.buildProject(sourceDirPath);
    ThrowUtils.throwIf(!buildSuccess, ErrorCode.SYSTEM_ERROR, "Vue 项目构建失败，请重试");
    // 检查 dist 目录是否存在
    File distDir = new File(sourceDirPath, "dist");
    ThrowUtils.throwIf(!distDir.exists(), ErrorCode.SYSTEM_ERROR, "Vue 项目构建完成但未生成 dist 目录");
    // 构建完成后，需要将构建后的文件复制到部署目录
    sourceDir = distDir;
}
```

**这里把 `boolean` 转成了业务异常**（`ThrowUtils.throwIf(!buildSuccess, ...)`）——`VueProjectBuilder` 只负责返回真假，要不要中断请求由调用方决定。**注意 Vue 在部署时又构建了一次**：生成结束时（`AiCodeGeneratorFacade#L129`）已经构建过一次，这里为了「拿最新的 dist」再构建一次，代价是点部署要再等一遍 `npm install` + `npm run build`（npm 有缓存，第二次通常快很多）。

部署目录与生成目录是两个完全独立的根（[AppConstant.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/constant/AppConstant.java#L21-L31)）：

```
生成（源码/产物，预览用）  tmp/code_output/{codeType}_{appId}     ← 按 appId 命名
部署（对外访问，nginx 挂载）tmp/code_deploy/{deployKey}            ← 按 6 位随机 key 命名
```

第 8 步的复制**不先清空目标目录**（代码里没有 `delete` 动作），所以重复部署是「合并覆盖」而不是「替换」：模型在后续对话里删掉的文件，重新部署后仍会残留在部署目录里。hash 路由下通常不影响访问，但部署目录会和源码不一致。

#### （4）`ProjectDownloadServiceImpl`：下载是另一条出口

「一键部署」解决「给别人看」，[ProjectDownloadServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/ProjectDownloadServiceImpl.java#L26-L46) 解决「把代码拿走」。入口是 `GET /api/app/download/{appId}`（[AppController.java#L118-L144](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/AppController.java#L118-L144)，**仅创建者可下载**），打包逻辑用 Hutool 直接把目录压进响应流：

```java
private static final Set<String> IGNORED_NAMES = Set.of(
        "node_modules", ".git", "dist", "build", ".DS_Store", ".env",
        "target", ".mvn", ".idea", ".vscode"
);

private static final Set<String> IGNORED_EXTENSIONS = Set.of(".log", ".tmp", ".cache");

// 逐段校验：路径中任何一层命中黑名单就整体排除
private boolean isPathAllowed(Path projectRoot, Path fullPath) {
    Path relativePath = projectRoot.relativize(fullPath);
    for (Path part : relativePath) {
        String partName = part.toString();
        if (IGNORED_NAMES.contains(partName)) return false;
        if (IGNORED_EXTENSIONS.stream().anyMatch(ext -> partName.toLowerCase().endsWith(ext))) return false;
    }
    return true;
}
```

三个值得注意的点：

- **`dist` 被排除**：下载的是**源码工程**（拿到后要自己 `npm install && npm run build`），不是构建产物。
- **`.env` 被排除**：防止把密钥类文件打包出去，属于安全默认值。
- **`isPathAllowed` 遍历相对路径的每一段**，而不是只判断文件名——`node_modules` 可能出现在嵌套层级（如 `src/components/node_modules`），只判最末一段会漏。同时先 `relativize` 转相对路径，避免绝对路径里的祖先目录名意外命中黑名单。
- 压缩是**流式写响应**：`ZipUtil.zip(response.getOutputStream(), UTF_8, false, filter, projectDir)`（`#L68`），不落中间临时文件。

### 6.5 动手验证

**前置**：已生成过一个 Vue 类型应用（假设 `appId = 3`，对应目录 `tmp/code_output/vue_project_3`），本机已装 Node.js 18+。

**① 手工复现构建流程**

```powershell
Set-Location e:\AI\GitHub\yu-ai-code-mother\tmp\code_output\vue_project_3
Get-ChildItem                      # 应看到 index.html、package.json、vite.config.js、src/
npm install                        # 对应 executeNpmInstall（超时 300s）
npm run build                      # 对应 executeNpmBuild（超时 180s）
Get-ChildItem dist                 # 应看到 index.html + assets/
```

预期：`dist/index.html` 里的资源引用是**相对路径**（`./assets/xxx.js`），这是提示词里 `base: './'` 的效果——也是它能在 `http://<IP>/dist/{deployKey}/` 这种子路径下正常工作的原因。如果 `npm run build` 报找不到 `build` 脚本，说明模型生成的 `package.json` 里没有 `scripts.build`（提示词 `#L69-L85` 给了参考配置，但模型不保证照抄）。

**② 确认「同步构建」的存在感**

在应用里对 Vue 应用发一条生成消息，观察 SSE：`done` 事件会在 `npm install` + `npm run build` **全部跑完之后**才到达。后端日志会按顺序出现：

```
开始构建 Vue 项目：.../vue_project_3
执行 npm install...
命令执行成功: npm.cmd install
执行 npm run build...
命令执行成功: npm.cmd run build
Vue 项目构建成功，dist 目录：.../vue_project_3
```

预期：这几行出现在 `done` 之前。如果卡住超过 300 秒，日志会出现「命令执行超时（300秒），强制终止进程」，此时去任务管理器看，**可能仍有残留的 node 进程**（`destroyForcibly` 只杀直接子进程，`npm.cmd` 拉起的 node 不一定跟着退）。

**③ 触发一键部署**

```powershell
# 需要带登录 Cookie；先在浏览器登录后从 F12 复制 Cookie，或先调登录接口
Invoke-RestMethod -Uri "http://localhost:8123/api/app/deploy" -Method Post `
  -ContentType "application/json" `
  -Headers @{ Cookie = "session=你的会话值" } `
  -Body '{"appId":3}'
```

预期：返回 `{"code":0,"data":"http://localhost/<6位deployKey>/"}`。**dev 环境下这个 URL 指向 80 端口，本地没有额外静态服务器时打不开**——`code.deploy-host` 在 `application.yml` 里没有配置，走的是默认值 `http://localhost`（`AppServiceImpl#L57-L58`）。

**④ 检查部署目录**

```powershell
Get-ChildItem e:\AI\GitHub\yu-ai-code-mother\tmp\code_deploy
Get-ChildItem e:\AI\GitHub\yu-ai-code-mother\tmp\code_deploy\<6位deployKey>
```

预期：目录名是 6 位随机串，**内容直接是 `index.html` + `assets/`（来自 `dist`），而不是 `vue_project_3` 这一层**——因为 `FileUtil.copyContent` 复制的是目录内容而非目录本身，且 Vue 走的是 `sourceDir = distDir`。

**⑤ 本地起一个静态服务器验证 dist 可访问**

```powershell
Set-Location e:\AI\GitHub\yu-ai-code-mother\tmp\code_deploy\<6位deployKey>
npx --yes serve -l 3000 .
# 浏览器打开 http://localhost:3000/
```

预期：页面正常渲染、样式和图片都在（验证 `base: './'` 生效）。再点几次页面内的路由跳转，**地址栏会出现 `#/xxx`**，且刷新不 404（验证 hash 路由生效）。

**⑥ 数据库侧对照**

```sql
SELECT id, appName, codeGenType, deployKey, deployedTime, cover
FROM app WHERE id = 3;
```

预期：`deployKey` 是 6 位串、`deployedTime` 是本次部署时间、`cover` 稍后会被异步截图写入（见环节 7）。**再点一次部署**：`deployKey` 不变（`StrUtil.isBlank` 才生成），`deployedTime` 更新。

**⑦ 验证重复部署不清理旧文件**

```powershell
# 手工在部署目录里塞一个假文件，再点一次部署
"stale" | Out-File e:\AI\GitHub\yu-ai-code-mother\tmp\code_deploy\<6位deployKey>\stale.txt
# 重新调用 /api/app/deploy 后
Get-ChildItem e:\AI\GitHub\yu-ai-code-mother\tmp\code_deploy\<6位deployKey>\stale.txt
```

预期：`stale.txt` **仍然存在**——`copyContent` 是合并覆盖，代码里没有清空目标目录的动作。

**⑧ 验证下载过滤规则**

```powershell
# 下载 HTML 类型应用的代码包（仅创建者本人可下载）
Invoke-WebRequest -Uri "http://localhost:8123/api/app/download/1" `
  -Headers @{ Cookie = "session=你的会话值" } -OutFile app-1.zip
Expand-Archive app-1.zip -DestinationPath .\app-1-unzip -Force
Get-ChildItem .\app-1-unzip
```

预期：解压后能看到源码文件，**看不到 `node_modules`、`dist`、`.env`**。对 Vue 应用下载时同样不含 `node_modules`（所以包很小），拿到后需自己 `npm install`。

### 6.6 自测题

<details>
<summary>点击展开题目（14 题，含 4 道陷阱题）</summary>

1. `buildCommand` 为什么要在 Windows 上给 `npm` 加 `.cmd` 后缀？不加会怎样？
2. 为什么命令要 `command.split("\\s+")` 拆成数组传给 `RuntimeUtil.exec`，而不是拼成一整串字符串？
3. `executeCommand` 为什么返回 `boolean` 而不是抛异常？谁在消费这个布尔值？
4. 为什么 `npm install` 的超时是 300 秒、`npm run build` 是 180 秒，而不是统一一个值？
5. `process.destroyForcibly()` 之后立刻 `return false`，这样做有什么隐患？
6. **（陷阱）** `buildProjectAsync` 在主业务链路上被调用了吗？
7. Vue 工程生成结束时，构建是在哪一行触发的？注释为什么强调「同步执行」？
8. **（陷阱）** 部署一个 HTML 类型应用时，`vueProjectBuilder.buildProject` 会被调用吗？
9. `deployKey` 是什么时候生成的？第二次点部署，返回的 URL 会变吗？这样设计的利弊是什么？
10. 部署目录和生成目录分别是什么？为什么 VUE 部署时要多一道「检查 dist 目录」？
11. **（陷阱）** 重复部署同一个应用，上一次残留的旧文件会被自动清理吗？
12. `code.deploy-host` 在 dev 环境下的值是什么？本地部署后拿到的链接能直接打开吗？
13. 下载 zip 时 `node_modules`、`dist`、`.env` 都被过滤掉了，这样做的取舍是什么？
14. `isPathAllowed` 为什么要遍历相对路径的每一段，而不是只判断文件名？

</details>

<details>
<summary>点击展开答案与解析</summary>

**1.** Windows 上 `npm` 是 `npm.cmd` 批处理脚本，`Runtime.exec` 不经过 shell、不做 `PATHEXT` 扩展，直接找名为 `npm` 的可执行文件会抛 `IOException`（找不到文件），被 `catch (Exception)` 吞掉后表现为「执行命令失败: npm install, 错误信息: ...」。判断逻辑是 `System.getProperty("os.name").toLowerCase().contains("windows")`（`#L108-L110`），命令字符串最终是 `npm.cmd install`。

**2.** `Runtime.exec(String)` 内部会用 `StringTokenizer` 按空白字符拆分，且语义隐式、容易踩坑；显式传数组等于「我自己决定了 argv」，不经 shell，也就不会被 shell 元字符影响。注意这里仍然是用 `split("\\s+")` 手工拆的，所以如果某个参数本身带空格（比如带空格的路径）还是会被拆坏——`workingDir` 不受影响，因为它是独立参数而不是命令字符串的一部分。

**3.** 因为「构建失败」在业务上是**可预期结果**而不是程序错误：目录不存在、没有 `package.json`、install 失败、build 失败、没生成 `dist`，这五种情况都 `log.error` + `return false`，`executeCommand` 还额外用 `catch (Exception)` 兜住 `IOException`。消费方有两处：`AppServiceImpl#deployApp#L172` 用 `ThrowUtils.throwIf(!buildSuccess, ...)` **转成业务异常**返回给前端；`ProjectBuilderNode#L36-L47` 在工作流里构建失败会抛异常/降级。把「结果判断」留给调用方，是 `boolean` 返回值比抛异常更合适的原因。

**4.** 因为两个操作的性质不同：`npm install` 要联网下载依赖，耗时受网络和 registry 影响、不可预测；`npm run build` 是纯本地编译（依赖已装好），耗时相对稳定。所以给 install 更宽松的超时（300s）、给 build 更严格的超时（180s），避免把「网络慢但正常」的首次安装误杀。这是「按操作性质给超时」而不是「一刀切」。

**5.** 隐患是**强杀不彻底 + 调用方不等清理**。`destroyForcibly()` 只对直接子进程发强杀，而 Windows 下 `npm.cmd` 会再拉起 `node` 进程，`node` 未必跟着退出，可能留下占着 `node_modules` 的孤儿进程；同时方法立刻 `return false`，调用方（部署接口）会继续往下走或直接报错，没人等残留进程收尾。表现是「日志说超时了，但后台还在跑 npm」，下一次构建可能因为文件被占用而失败。代码里没有任何补偿动作（比如 `taskkill /T` 杀进程树）。

**6.** **没有。** 全项目只有 `VueProjectBuilder#L22` 的方法定义和它内部对 `buildProject` 的调用（`#L26`），没有任何业务代码调用 `buildProjectAsync`。主链路 Vue 生成走的是 [AiCodeGeneratorFacade#L129](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L126-L131) 的**同步** `buildProject`。所以它是一个「预留但未启用」的异步入口——读代码时不要以为构建是异步的。

**7.** 在 `processTokenStream` 的 `onCompleteResponse` 回调里（[AiCodeGeneratorFacade.java#L126-L131](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L126-L131)），路径是硬编码的 `CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId`。强调同步的原因：前端在 SSE 收到 `done` 后会立刻请求预览地址，如果构建异步进行，`dist` 还没生成，预览必然 404；同步构建能保证「done 到达时项目已就绪」。代价是 SSE 连接被构建阻塞（首次 `npm install` 可能几分钟），这正是超时保护存在的原因。

**8.** **不会。** `deployApp#L168-L178` 有 `if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT)` 特判，只有 Vue 类型才构建并把 `sourceDir` 改成 `dist`；HTML/MULTI_FILE 直接复制生成目录内容（`sourceDir` 保持 `tmp/code_output/html_{appId}`），因为它们的产物本身就是浏览器能直接吃的静态文件。同理，第 7 步的两道 `ThrowUtils.throwIf`（构建失败、dist 不存在）也只对 Vue 生效。

**9.** 在 `deployApp#L153-L157`：先读数据库里的 `deployKey`，**只有 `StrUtil.isBlank(deployKey)` 为真时才生成** `RandomUtil.randomString(6)`。所以同一应用第一次部署生成 key、之后一直复用，**URL 不变**。利：用户分享出去的链接永久有效，重新部署等于「原地更新」；弊：没有「换 key 下线旧版本」的能力，也无法通过改 key 做灰度——要换 URL 只能手工改数据库字段。

**10.** 生成目录是 `tmp/code_output/{codeType}_{appId}`（`AppConstant.CODE_OUTPUT_ROOT_DIR`，按 appId 命名，预览和下载读它）；部署目录是 `tmp/code_deploy/{deployKey}`（`AppConstant.CODE_DEPLOY_ROOT_DIR`，按 6 位随机 key 命名，对外由 nginx 按 `code.deploy-host` 暴露）。VUE 要多检查 `dist`，因为**源码工程不能被浏览器直接执行**——`src/main.js` 是 ESM 源码、`.vue` 文件浏览器不认识，只有 `npm run build` 之后的 `dist` 才是可部署的静态资源；HTML/MULTI_FILE 本身就是静态文件，所以不需要这步。

**11.** **不会清理。** `FileUtil.copyContent(sourceDir, new File(deployDirPath), true)` 是把源目录的**内容合并**到目标目录，代码里没有先删除 `deployDirPath` 的动作。所以模型在后续对话里删掉的文件（比如 `src/old-page.vue`、旧的 `assets/xxx-旧hash.js`）在重新部署后仍会留在部署目录里。hash 路由下通常不影响访问，但会让部署目录与源码不一致、目录越滚越大。要干净只能手工清目录再部署。

**12.** 默认值是 `http://localhost`——`@Value("${code.deploy-host:http://localhost}")`（`AppServiceImpl#L57-L58`），而 `application.yml` 里**没有** `code:` 配置节，只有 `application-prod-sample.yml#L79-L80` 里有 `deploy-host: http://<Your IP>/dist`。所以 dev 下返回的 URL 是 `http://localhost/{deployKey}/`，指向 **80 端口**，而后端跑在 8123（`server.port: 8123`、`context-path: /api`），本地没有额外静态服务器时这个链接打不开。生产样例里带了 `/dist` 后缀，说明 nginx 是把 `code_deploy` 挂到 `/dist` 路径下的。

**13.** 取舍是「给用户一份干净、体积小、可直接继续开发的源码工程」。`node_modules` 动辄几百 MB 且可重建、`dist`/`build`/`target` 是可再生成的产物、`.env` 可能含密钥，都不该进压缩包；代价是用户拿到包后必须先 `npm install` 才能跑，且如果模型只在构建产物里改过东西就会丢（本项目不会，改的都是源码）。过滤实现是「黑名单名字集合 + 黑名单扩展名集合」，扩展名判断用 `partName.toLowerCase()` 保证大小写不敏感。

**14.** 因为黑名单里存的是**目录名/文件名**（`node_modules`、`.git`、`dist`），而这类目录可能出现在任意层级（例如 `src/components/node_modules`、`packages/app/dist`）。只判断最末一段会漏掉嵌套的情况；逐段遍历才能保证「路径中任何一层命中黑名单就整体排除」。同时先 `projectRoot.relativize(fullPath)` 转成相对路径，避免**绝对路径里的祖先目录名**意外命中黑名单（比如项目放在名为 `target` 或 `.idea` 的上级目录下时会误杀整个包）。

</details>

### 6.7 本环节技术点清单

| 技术点 | 落地位置 | 作用 | 项目特有之处 |
|---|---|---|---|
| 外部命令调用 | `VueProjectBuilder#L120-L147` | 跑 `npm install` / `npm run build` | 用 Hutool `RuntimeUtil.exec(null, workingDir, String...)`；**完全不消费子进程输出**，日志里只有退出码 |
| 跨平台命令适配 | `VueProjectBuilder#L96-L110` | Windows 上拼 `npm.cmd` | `os.name` 字符串判断；**没有做 `npm` 路径可配置**，依赖环境变量 PATH |
| 命令参数拆分 | `VueProjectBuilder#L126` | `command.split("\\s+")` 转数组 | 传数组绕过 shell；但带空格的参数仍会被拆坏 |
| 超时与强杀 | `VueProjectBuilder#L129-L133` | 防止卡死的 npm 占住线程 | install 300s / build 180s；`destroyForcibly` **不杀进程树**，可能留孤儿 node |
| 布尔返回值而非异常 | `VueProjectBuilder#L39-L70` | 五种失败情形统一 `log.error` + `return false` | 由调用方决定是否转业务异常（`deployApp` 转、`ProjectBuilderNode` 抛/降级） |
| 虚拟线程（异步入口） | `VueProjectBuilder#L22-L31` | 预留的异步构建 | 用 `Thread.ofVirtual().name(...).start(...)`；**全项目无调用，是死代码** |
| 同步构建保就绪 | `AiCodeGeneratorFacade#L126-L131` | 保证 `done` 到达时 `dist` 已生成 | 硬编码路径 `CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId`；SSE 连接被构建阻塞 |
| 构建契约靠提示词 | `prompt/codegen-vue-project-system-prompt.txt#L14-L85` | 约束生成结构可构建 | `base: './'` + hash 路由 + 只允许 `vue`/`vue-router` 依赖；代码只验证不保证 |
| 生成/部署目录分离 | `AppConstant#L21-L31` | 源码目录与对外目录解耦 | `code_output/{type}_{appId}` vs `code_deploy/{deployKey}`；nginx 按 `code.deploy-host` 挂载 |
| 部署幂等 key | `AppServiceImpl#L153-L157` | 重复部署 URL 不变 | `StrUtil.isBlank` 才生成 `RandomUtil.randomString(6)`；没有换 key 下线的能力 |
| Vue 特判分支 | `AppServiceImpl#L168-L178` | 只有 Vue 需要构建 + 用 dist | 部署时**再构建一次**；两道 `ThrowUtils.throwIf` 把 boolean 转业务异常 |
| 目录合并复制 | `AppServiceImpl#L180-L185` | 把产物搬到部署目录 | `FileUtil.copyContent(..., true)` **不清理目标目录**，旧文件会残留 |
| 部署域名注入 | `AppServiceImpl#L57-L58`、`application-prod-sample.yml#L79-L80` | 拼对外访问 URL | `@Value("${code.deploy-host:http://localhost}")`，dev 走默认值指向 80 端口 |
| 打包下载过滤 | `ProjectDownloadServiceImpl#L26-L99` | 导出干净的源码 zip | 黑名单排除 `node_modules`/`dist`/`.env` 等；`relativize` + 逐段校验；`ZipUtil.zip` 流式写响应 |

### 6.8 本环节产出

这个环节解决的是「**源码工程 → 可访问 URL**」的最后一公里，技术选型可以概括为：**用子进程跑 npm，用虚拟线程兜后台，用目录分离做隔离，用随机 key 做对外标识。** 构建侧 `VueProjectBuilder` 是项目里唯一调外部命令的地方——Hutool 的 `RuntimeUtil.exec(null, workingDir, String...)` 起进程、`command.split("\\s+")` 传数组绕过 shell、Windows 上补 `.cmd`、`waitFor(300/180, SECONDS)` 加 `destroyForcibly` 做超时保护、五种失败情形统一 `return false` 而不是抛异常；**`buildProjectAsync` 那个虚拟线程入口是死代码，主链路的构建是同步的**，因为前端收到 `done` 就立刻拉预览，异步会导致必 404（`AiCodeGeneratorFacade#L129` 的注释就是这个意思），代价是 SSE 被 npm 阻塞——这也是超时必须存在的理由。部署侧 `AppServiceImpl#deployApp` 是一条 11 步的直线：鉴权 → 取/生成 `deployKey`（`StrUtil.isBlank` 才生成，所以**重复部署 URL 不变**）→ 按 `{codeType}_{appId}` 找生成目录 → **只有 `VUE_PROJECT` 才构建并把 `sourceDir` 换成 `dist`**（部署时又构建了一次）→ `FileUtil.copyContent` 把产物合并进 `tmp/code_deploy/{deployKey}` → 落库 `deployKey` + `deployedTime` → 拼 `{deploy-host}/{deployKey}/` → 异步截图。构建能不能成功其实**不是代码保证的，而是提示词契约**（`codegen-vue-project-system-prompt.txt` 强制 `package.json` 结构、`base: './'`、hash 路由、只允许两个依赖），Java 侧只做结果校验，而**子进程的 stdout/stderr 完全没人消费**，所以构建失败时日志里只有一句「退出码: 1」，必须手工重跑 npm 才能看到真因。四个最容易踩的坑：**一是 `destroyForcibly` 不杀进程树**，Windows 下可能留下占着 `node_modules` 的孤儿 node；**二是重复部署不清理目标目录**，删掉的文件会残留；**三是 dev 环境 `code.deploy-host` 默认 `http://localhost`（80 端口）**，而服务在 8123，本地拿到的链接打不开；**四是下载 zip 会过滤掉 `dist` 和 `node_modules`**，拿到的是需要自己 `npm install` 的源码工程。读到这里应该能回答：**「用户在 Vue 应用上点了一下『部署』，从点击到拿到一个能打开的 URL，中间到底发生了什么」**——答案是：`POST /api/app/deploy` → 鉴权 + 查库拿/生成 6 位 `deployKey` → 定位 `tmp/code_output/vue_project_{appId}` → `VueProjectBuilder` 同步跑 `npm.cmd install`（300s 超时）和 `npm.cmd run build`（180s 超时）并校验 `dist` 生成 → `FileUtil.copyContent(dist, tmp/code_deploy/{deployKey})` → 更新 `deployKey`/`deployedTime` → 返回 `{deploy-host}/{deployKey}/` → 虚拟线程异步截图回填 `cover`。

---

## 环节 7：截图与对象存储

### 7.1 这个环节解决什么问题

应用列表需要一个**封面图**，但平台没有「上传封面」的功能入口——封面只能由系统自动生成。于是有了这条链路：**部署成功后拿到访问 URL → 起无头浏览器截一张图 → 压缩 → 上传对象存储 → 把 URL 写回 `app.cover`**。

拆成两个独立子问题：

| 子问题 | 技术选择 | 落地位置 |
|---|---|---|
| 怎么截 | Selenium 4 + 无头 Chrome + WebDriverManager 自动管驱动 | `utils/WebScreenshotUtils` |
| 存哪里 | 腾讯云 COS（`cos_api` SDK） | `manager/CosManager` + `config/CosClientConfig` |
| 谁来编排 | `ScreenshotServiceImpl`（截图 → 上传 → 清理），由部署流程异步调用 | `service/impl/ScreenshotServiceImpl`、`AppServiceImpl#generateAppScreenshotAsync` |

触发点是部署的第 11 步（[AppServiceImpl.java#L195](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L195)）：`generateAppScreenshotAsync(appId, appDeployUrl)`。

### 7.2 技术点一：Selenium 无头浏览器截图

驱动初始化在 [WebScreenshotUtils.java#L93-L124](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/utils/WebScreenshotUtils.java#L93-L124)：

```java
private static WebDriver initChromeDriver(int width, int height) {
    try {
        // 自动管理 ChromeDriver
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless");                  // 无头模式
        options.addArguments("--disable-gpu");               // 禁用GPU
        options.addArguments("--no-sandbox");                // Docker 环境需要
        options.addArguments("--disable-dev-shm-usage");     // 禁用 /dev/shm
        options.addArguments(String.format("--window-size=%d,%d", width, height));
        options.addArguments("--disable-extensions");
        options.addArguments("--user-agent=Mozilla/5.0 ...");
        WebDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
        return driver;
    } catch (Exception e) {
        log.error("初始化 Chrome 浏览器失败", e);
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "初始化 Chrome 浏览器失败");
    }
}
```

四个要点：

| 参数/动作 | 作用 | 为什么需要 |
|---|---|---|
| `WebDriverManager.chromedriver().setup()` | 自动下载并匹配 ChromeDriver 版本 | 不用手工配 `webdriver.chrome.driver` 系统属性，也不用把驱动塞进仓库 |
| `--headless` | 无界面运行 | 服务端没有显示器，必须无头 |
| `--no-sandbox` + `--disable-dev-shm-usage` | 容器兼容 | Docker 里 Chrome 沙箱常因缺内核能力起不来；`/dev/shm` 默认只有 64MB，Chrome 用它做共享内存会崩 |
| `--window-size=1600,900` | 固定视口 | 截图尺寸可预期（默认 1600×900，硬编码在静态块的 `DEFAULT_WIDTH`/`DEFAULT_HEIGHT`） |

**等待策略是两层**（`#L167-L182`）：

```java
private static void waitForPageLoad(WebDriver webDriver) {
    try {
        WebDriverWait wait = new WebDriverWait(webDriver, Duration.ofSeconds(10));
        // 等待 document.readyState 为 complete
        wait.until(driver -> ((JavascriptExecutor) driver)
                .executeScript("return document.readyState").equals("complete"));
        // 额外等待一段时间，确保动态内容加载完成
        Thread.sleep(2000);
        log.info("页面加载完成");
    } catch (Exception e) {
        log.error("等待页面加载时出现异常，继续执行截图", e);
    }
}
```

`document.readyState === 'complete'` 只保证**静态资源加载完**，不保证 JS 渲染完成——而平台生成的页面大量依赖 JS 动态渲染（Vue/原生 JS），此时 DOM 可能还是空的。所以补一个 `Thread.sleep(2000)` 硬等。代价是每次截图固定多等 2 秒，而且这是**猜测式等待**，不是确定性方案；另外这个方法的 `catch` 是「吞异常继续截图」，所以等待超时不会中断流程，只会截到一张可能不完整的图。

**图片处理走「PNG 中转 → JPG 压缩」**（`#L60-L87`）：

```
1. 建临时目录 tmp/screenshots/{8位UUID}/
2. webDriver.get(url) → waitForPageLoad → getScreenshotAs(OutputType.BYTES) 拿 PNG 字节
3. 存成 {5位随机数}.png                      ← 原始图
4. ImgUtil.compress(png, jpg, 0.3f) 压成 30% 质量的 JPG  ← 最终产物
5. FileUtil.del(png)                          ← 删原始图
6. return 压缩图路径（失败返回 null）
```

Selenium 的 `getScreenshotAs` 只能输出 PNG（无损、体积大），而封面图不需要无损，所以先落地 PNG、再用 Hutool 压缩成质量 `0.3f` 的 JPG，**只上传压缩后的 JPG**（省带宽、省 COS 存储），原图立刻删掉。

### 7.3 技术点二：静态 `WebDriver` 单例——两个必须点明的缺陷

`WebScreenshotUtils` 里 driver 的持有方式是「静态字段 + 静态块初始化」（`#L30-L37`）：

```java
private static final WebDriver webDriver;

// 全局静态初始化，避免重复初始化驱动程序：
static {
    final int DEFAULT_WIDTH = 1600;
    final int DEFAULT_HEIGHT = 900;
    webDriver = initChromeDriver(DEFAULT_WIDTH, DEFAULT_HEIGHT);
}
```

设计意图是「全局只起一个浏览器，避免重复初始化」。但这个写法带来两个真实缺陷：

**缺陷一：`@PreDestroy` 永远不会被调用。** 类里确实写了销毁方法（`#L42-L45`）：

```java
@PreDestroy
public void destroy() {
    webDriver.quit();
}
```

但 `@PreDestroy` 只对**由 Spring 容器管理的 Bean 实例**生效，而：

- 类上只有 `@Slf4j`，**没有 `@Component` / `@Service`**，容器里不存在它的实例；
- 唯一的调用方式是静态调用 `WebScreenshotUtils.saveWebPageScreenshot(webUrl)`（`ScreenshotServiceImpl#L32`），根本不需要实例。

所以 `destroy()` 是个永远不会被执行的实例方法，**应用关闭后 Chrome 进程不会退出，会一直残留在系统里**。正确做法是把 driver 交给 Spring 管理（改成 `@Component` + 实例方法），或者注册 `Runtime.getRuntime().addShutdownHook(...)`。

**缺陷二：静态单例 + 并发调用 = 竞态。** 截图是在虚拟线程里执行的（见 7.4（4）），多个用户同时部署就会有多个线程同时调 `saveWebPageScreenshot`，而它们**共享同一个 `webDriver` 实例**；Selenium 的 `ChromeDriver` 不是线程安全的。两个线程同时 `webDriver.get(url)` 再 `getScreenshotAs(...)`，会出现「A 的封面截到了 B 的页面」或者直接抛异常——**这类问题在单人测试时完全看不出来，只有在并发部署时才暴露**。

还有一个容易忽略的 JVM 语义：**静态块里 `initChromeDriver` 抛的是 `BusinessException`**（`#L122`），静态初始化失败后，类会进入错误状态——第一次访问抛 `ExceptionInInitializerError`，之后再访问同一个类直接抛 `NoClassDefFoundError`。也就是说 Chrome 起不来的话，**截图能力在整个 JVM 生命周期内都废了，无法自愈**。

### 7.4 代码走读

#### （1）`ScreenshotServiceImpl`：截图 → 上传 → 清理

[ScreenshotServiceImpl.java#L26-L44](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ScreenshotServiceImpl.java#L26-L44)：

```java
@Override
public String generateAndUploadScreenshot(String webUrl) {
    ThrowUtils.throwIf(StrUtil.isBlank(webUrl), ErrorCode.PARAMS_ERROR, "截图的网址不能为空");
    log.info("开始生成网页截图，URL：{}", webUrl);
    // 本地截图
    String localScreenshotPath = WebScreenshotUtils.saveWebPageScreenshot(webUrl);
    ThrowUtils.throwIf(StrUtil.isBlank(localScreenshotPath), ErrorCode.OPERATION_ERROR, "生成网页截图失败");
    // 上传图片到 COS
    try {
        String cosUrl = uploadScreenshotToCos(localScreenshotPath);
        ThrowUtils.throwIf(StrUtil.isBlank(cosUrl), ErrorCode.OPERATION_ERROR, "上传截图到对象存储失败");
        log.info("截图上传成功，URL：{}", cosUrl);
        return cosUrl;
    } finally {
        // 清理本地文件
        cleanupLocalFile(localScreenshotPath);
    }
}
```

三段结构很清晰，两个细节值得记：

- **`finally` 保证本地文件必删**。`try` 块从「已经拿到本地路径」之后才开始，所以三种结局（上传成功 / 上传返回 null / 上传抛异常）都会走 `cleanupLocalFile`。注意如果 `saveWebPageScreenshot` 返回 null，异常在 `try` 之前就抛了，此时本来也没有文件要清。
- **两层 null 检查**：截图返回 null → 抛「生成网页截图失败」；上传返回 null → 抛「上传截图到对象存储失败」。上传侧的 null 来自 `CosManager#uploadFile`（`PutObjectResult` 为 null 时返回 null）。

#### （2）对象键设计：按日期分目录

[ScreenshotServiceImpl.java#L67-L74](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ScreenshotServiceImpl.java#L67-L74)：

```java
/**
 * 生成截图的对象存储键
 * 格式：/screenshots/2025/07/31/filename.jpg
 */
private String generateScreenshotKey(String fileName) {
    String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
    return String.format("/screenshots/%s/%s", datePath, fileName);
}
```

文件名是 `UUID.randomUUID().substring(0, 8) + "_compressed.jpg"`（`#L62`）。两个设计点：

- **按 `yyyy/MM/dd` 分目录**：避免单个前缀下堆积几十万个对象（影响控制台浏览和列举），也便于按天配生命周期规则。
- **文件名用 8 位 UUID 而不是 `appId`**：避免同一应用重复部署时同名覆盖（COS 同 key 上传会覆盖），同时让 URL 不可猜。

**注意 key 以 `/` 开头**，而 `CosManager` 是直接拼接的（`#L48`）：`String.format("%s%s", cosClientConfig.getHost(), key)` → 结果形如 `{host}/screenshots/2026/09/22/xxxxxxxx_compressed.jpg`。

#### （3）`CosClientConfig`：配置类与属性对象合体

[CosClientConfig.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/config/CosClientConfig.java#L19-L57)：

```java
@Configuration
@ConfigurationProperties(prefix = "cos.client")
@Data
public class CosClientConfig {

    private String host;
    private String secretId;
    private String secretKey;
    private String region;
    private String bucket;

    @Bean
    public COSClient cosClient() {
        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        return new COSClient(cred, clientConfig);
    }
}
```

- **同时标 `@Configuration` 和 `@ConfigurationProperties`**：Spring Boot 允许一个类既做配置类（`@Bean` 生效）又做外部配置的绑定目标，省掉了单独的属性类。代价是必须有 setter，所以 `@Data` 不能省。
- **`COSClient` 是单例 Bean**，由 Spring 管理生命周期（`@Bean` 默认 `destroyMethod = inferred`，会推断出 `COSClient#shutdown()` 在容器关闭时调用）。
- `CosManager` 是标准的「SDK 薄封装」：`putObject` 只做一件事——把 `bucket` + `key` + `file` 组成 `PutObjectRequest`；`uploadFile` 在成功时拼出访问 URL（`#L45-L55`）。**没有任何重试、超时、异常转换逻辑**，SDK 抛什么就往上抛。

#### （4）触发方：虚拟线程 + 无异常处理

[AppServiceImpl.java#L205-L218](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L205-L218)：

```java
@Override
public void generateAppScreenshotAsync(Long appId, String appUrl) {
    // 使用虚拟线程并执行
    Thread.startVirtualThread(() -> {
        // 调用截图服务生成截图并上传
        String screenshotUrl = screenshotService.generateAndUploadScreenshot(appUrl);
        // 更新数据库的封面
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setCover(screenshotUrl);
        boolean updated = this.updateById(updateApp);
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "更新应用封面字段失败");
    });
}
```

- 用 `Thread.startVirtualThread(...)`（JDK 21）而不是 `@Async` 或线程池——截图是「长时间阻塞的 IO 任务」，正是虚拟线程的适用场景。**注意它和 `VueProjectBuilder#buildProjectAsync` 里的 `Thread.ofVirtual().name(...).start(...)` 是同一个 API 的两种写法**（一个是快捷静态方法，一个是 builder）。
- **整段没有 try-catch**。`generateAndUploadScreenshot` 在截图失败/上传失败时会抛 `BusinessException`，在这个虚拟线程里就变成了**未捕获异常**：JVM 默认处理器打到 stderr，线程结束，后面的 `updateById` 不执行，`cover` 保持原值（null）。没有重试、没有补偿、没有告警。
- **所以截图是「尽力而为」的能力**：部署接口在 `#L195` 调用它之后立刻返回 URL，用户永远不会因为截图失败而收到报错，只会看到「应用卡片没有封面图」。

**还有一个时序风险**：截图紧跟部署触发，而部署的 URL（`{deploy-host}/{deployKey}/`）是由 nginx 提供服务的。如果 nginx 还没感知到新目录，或者 dev 环境下 `deploy-host` 默认是 `http://localhost`（80 端口）而本地根本没有静态服务器，`webDriver.get(url)` 就会失败 → 返回 null → 抛异常 → 封面为空。**排查「封面一直是空」时，第一步是用浏览器手工打开那个 URL 看能不能访问。**

### 7.5 动手验证

**前置**：本机已装 Chrome；`application.yml` 里的 `cos.client.*` 已填成真实值（默认是 `your-*` 占位符）。

**① 单独验证截图能力**

```powershell
# 仓库里现成的截图单测，目标站点是 https://www.codefather.cn
mvn -q -Dtest=WebScreenshotUtilsTest test
```

预期：测试通过（`assertNotNull(webPageScreenshot)`）。第一次跑会看到 WebDriverManager 下载 ChromeDriver 的日志。**注意这个测试跑完 JVM 会退出，但 Chrome 进程可能残留**——用下面这条命令确认：

```powershell
Get-Process chrome -ErrorAction SilentlyContinue | Select-Object Id, ProcessName, StartTime
```

预期：能看到残留的 `chrome` 进程（`@PreDestroy` 不生效的证据）。执行 `Stop-Process -Name chrome` 手工清理。

**② 验证截图中间产物目录**

```powershell
Get-ChildItem e:\AI\GitHub\yu-ai-code-mother\tmp\screenshots -Recurse -ErrorAction SilentlyContinue |
  Select-Object FullName, Length
```

预期：正常情况下目录里**应该没有文件**——`ScreenshotServiceImpl` 的 `finally` 会把压缩图删掉，`WebScreenshotUtils` 会删原始 PNG。如果看到 `_compressed.jpg` 残留，说明上传阶段抛异常且 `cleanupLocalFile` 也失败了（或进程被强杀）。目录名是 8 位 UUID 前缀。

**③ 触发一次部署并观察封面回填**

```powershell
# 1) 触发部署（带登录 Cookie）
Invoke-RestMethod -Uri "http://localhost:8123/api/app/deploy" -Method Post `
  -ContentType "application/json" `
  -Headers @{ Cookie = "session=你的会话值" } -Body '{"appId":1}'
```

后端日志预期依次出现：

```
开始生成网页截图，URL：http://localhost/xxxxxx/
原始截图保存成功：.../tmp/screenshots/xxxxxxxx/12345.png
压缩图片保存成功：.../tmp/screenshots/xxxxxxxx/67890_compressed.jpg
页面加载完成
文件上传到 COS 成功：67890_compressed.jpg -> your-custom-domain.com/screenshots/2026/09/22/xxxxxxxx_compressed.jpg
截图上传成功，URL：...
清理本地文件成功: .../67890_compressed.jpg
```

**注意最后那行 URL 里没有 `https://`**——因为 `application.yml#L84` 的占位值是 `host: your-custom-domain.com`，而 `CosManager` 只做字符串拼接、不补协议。

**④ 数据库确认 cover**

```sql
SELECT id, appName, deployKey, deployedTime, cover FROM app WHERE id = 1;
```

预期：`cover` 是上一步日志里的 URL。**如果 `cover` 为 NULL**，说明截图链路某一步失败了，按顺序排查：URL 能不能用浏览器打开 → COS 配置是否真实可用 → 后端日志有没有「网页截图失败」/「上传截图到对象存储失败」。

**⑤ 验证 URL 缺少协议会导致图片显示不出来**

```powershell
# 把日志里的 URL 直接当相对路径请求当前站点，预期 404
Invoke-WebRequest "http://localhost:8123/api/your-custom-domain.com/screenshots/2026/09/22/xxxxxxxx_compressed.jpg" `
  -SkipHttpErrorCheck | Select-Object StatusCode
```

预期：`404`。修法是给 `cos.client.host` 配上完整协议，例如 `https://<bucket>.cos.ap-shanghai.myqcloud.com`，然后重启应用再部署一次。

**⑥ 验证并发下的 driver 竞态（可选，能复现就说明问题真实存在）**

同时用两个浏览器/两个账号对两个应用点部署，然后看两边 `cover`：

```sql
SELECT id, cover FROM app WHERE id IN (1, 2);
```

预期：在静态单例 driver 的前提下，有一定概率出现「两个应用 cover 相同」或其中一个为空——这是 `webDriver` 非线程安全导致的串台。

### 7.6 自测题

<details>
<summary>点击展开题目（14 题，含 4 道陷阱题）</summary>

1. 截图用的 ChromeDriver 是从哪来的？代码里配了 `webdriver.chrome.driver` 系统属性吗？
2. `ChromeOptions` 里 `--no-sandbox` 和 `--disable-dev-shm-usage` 分别解决什么问题？
3. `waitForPageLoad` 里已经用 `WebDriverWait` 等了 `document.readyState === 'complete'`，为什么还要 `Thread.sleep(2000)`？
4. 为什么截图要「先存 PNG、再压缩成 JPG、再删 PNG」？为什么不直接存 JPG？
5. **（陷阱）** `WebScreenshotUtils#destroy()` 上的 `@PreDestroy` 会生效吗？为什么？
6. **（陷阱）** 多个用户同时部署，截图会并发执行吗？共享同一个 `webDriver` 会有什么后果？
7. 截图是在哪个线程执行的？为什么用虚拟线程而不是普通线程池？
8. `generateAndUploadScreenshot` 为什么用 `finally` 清理本地文件？如果 `saveWebPageScreenshot` 返回 null 会走 `finally` 吗？
9. COS 的对象键为什么要按 `yyyy/MM/dd` 分目录？文件名为什么用 UUID 而不是 `appId`？
10. **（陷阱）** `cos.client.host` 配成 `your-custom-domain.com`（不带协议）时，`app.cover` 里存的字符串会导致什么现象？
11. `CosClientConfig` 为什么能同时是 `@Configuration` 和 `@ConfigurationProperties`？为什么必须有 `@Data`？
12. 截图失败时用户会看到什么？会收到报错吗？
13. `generateAppScreenshotAsync` 里没有 try-catch，`generateAndUploadScreenshot` 抛的异常最终去了哪里？
14. 为什么截图在「部署」这一步触发，而不是在「生成代码」那一步？

</details>

<details>
<summary>点击展开答案与解析</summary>

**1.** 由 **WebDriverManager 自动下载并匹配**：`WebDriverManager.chromedriver().setup()`（`#L96`）。代码里**没有**配 `webdriver.chrome.driver` 系统属性，仓库里也没有驱动二进制——WebDriverManager 会根据本机 Chrome 版本自动挑一个兼容的 ChromeDriver 下载到本地缓存目录。好处是不用手工维护驱动版本，代价是首次运行依赖网络。

**2.** 都是**容器化部署的兼容参数**。`--no-sandbox`：Chrome 的沙箱机制需要内核能力（如 user namespace），在 Docker 里以 root 运行时常因缺权限起不来；`--disable-dev-shm-usage`：容器默认 `/dev/shm` 只有 64MB，Chrome 用它做共享内存，空间不够会直接崩溃，禁掉后改用 `/tmp`。本地 Windows 上留着这两个参数无害。

**3.** 因为 `document.readyState === 'complete'` 只代表**静态资源（HTML/CSS/JS/图片）加载完毕**，不代表页面已经渲染出内容。平台生成的页面大量依赖 JS 动态渲染，`readyState` 到 complete 时 DOM 可能还是空壳，截出来就是一张白页。所以额外 `Thread.sleep(2000)` 给渲染留时间。代价是每次截图固定多等 2 秒，且这是**猜测式等待**而非确定性方案（更稳的做法是 `wait.until(ExpectedConditions.visibilityOfElementLocated(...))` 等某个业务元素出现）。

**4.** 因为 Selenium 的 `getScreenshotAs(OutputType.BYTES)` **只能输出 PNG**（无损、体积大），而封面图不需要无损。所以流程是「PNG 落地做中间产物 → Hutool `ImgUtil.compress(png, jpg, 0.3f)` 转成 30% 质量的 JPG → 删掉 PNG → 只上传 JPG」。收益是上传带宽和 COS 存储都显著下降；代价是多一次本地磁盘写入和一次图片解码/编码。

**5.** **不会生效。** `@PreDestroy` 只对**由 Spring 容器管理的 Bean 实例**生效，而 `WebScreenshotUtils` 类上只有 `@Slf4j`、**没有 `@Component`/`@Service`**，容器里根本没有它的实例；调用方式也是静态的 `WebScreenshotUtils.saveWebPageScreenshot(...)`（`ScreenshotServiceImpl#L32`），不需要实例。所以 `destroy()` 是一个永远不会被执行的实例方法，**应用关闭后 Chrome 进程会残留**。修法：把 driver 改成 Spring 管理的 Bean（`@Component` + 实例方法 + `@PreDestroy`），或注册 JVM shutdown hook 手工 `quit()`。

**6.** **会并发，而且有竞态。** 截图在 `AppServiceImpl#generateAppScreenshotAsync` 里由 `Thread.startVirtualThread(...)` 起虚拟线程执行，多个用户同时部署就是多个线程并发调用；但 `webDriver` 是 `private static final` 的**全局单例**（静态块里只初始化一次），而 Selenium 的 `ChromeDriver` **不是线程安全**的。两个线程同时 `webDriver.get(url)` + `getScreenshotAs(...)` 会互相干扰，结果可能是「A 的封面截到了 B 的页面」或直接抛异常导致某一边 cover 为空。这类问题单机单人测试看不出来，只在并发部署时暴露——是 `ThreadLocal<WebDriver>` 或每次截图新建 driver（用池化管理）的典型场景。

**7.** 在 `Thread.startVirtualThread(...)` 起的**虚拟线程**里（`AppServiceImpl#L208-L217`）。截图是「纯阻塞 IO + 长时间等待」的任务（启动浏览器、加载页面、等 2 秒、上传 COS），用虚拟线程可以几乎零成本地挂起、不占平台线程，也不需要维护线程池参数。注意项目里虚拟线程有两种写法：这里的 `Thread.startVirtualThread(...)` 和 `VueProjectBuilder#buildProjectAsync` 的 `Thread.ofVirtual().name(...).start(...)`，是同一 API 家族的两种形式。

**8.** 因为本地文件只是**上传用的中转产物**，上传完就没价值了。用 `finally` 能保证「上传成功 / 上传返回 null 抛异常 / 上传过程抛异常」三种结局都会执行 `cleanupLocalFile`，否则 `tmp/screenshots/{uuid}/` 目录会无限堆积。**如果 `saveWebPageScreenshot` 返回 null，不会走 `finally`**——因为 `ThrowUtils.throwIf(StrUtil.isBlank(localScreenshotPath), ...)` 在 `try` 之前就把异常抛出去了，此时本地也本来没有文件需要清理（截图失败时路径就是 null）。

**9.** 按日期分目录是为了**控制单个前缀下的对象数量**（几十万个对象挤在一个前缀下会影响控制台浏览和列举性能），同时便于按天配置生命周期规则/做统计。文件名用 8 位 UUID（`UUID.randomUUID().substring(0, 8)`）而不是 `appId`，是因为**同一应用重复部署会反复上传**，用 appId 命名会同名覆盖（COS 同 key 上传即覆盖），而且 URL 可猜（`appId` 是自增的）；用随机串既不覆盖也不可猜。代价是每次部署都会产生一份新的截图对象，旧的成为孤儿（项目里没有任何清理逻辑）。

**10.** 存的是 `your-custom-domain.com/screenshots/2026/09/22/xxxxxxxx_compressed.jpg`——**一个没有协议前缀的字符串**。因为 `CosManager#uploadFile` 只做 `String.format("%s%s", host, key)` 拼接（`#L48`），代码里不会补 `https://`。前端拿到这个值塞进 `<img src="...">` 时，浏览器会把它当成**相对路径**（相对于当前站点）去请求，图片必然 404，表现为「封面图裂了」。正确配置是 `cos.client.host: https://<bucket>.cos.<region>.myqcloud.com`（带协议），这是 `application.yml#L84` 的占位值最容易踩的坑。

**11.** Spring Boot 允许一个类**既做配置类又做属性绑定目标**：`@Configuration` 让它被当作配置类处理（其中的 `@Bean` 方法生效、`COSClient` 被注册成单例），`@ConfigurationProperties(prefix = "cos.client")` 让它同时接收 `cos.client.*` 的绑定。这样省掉了一个单独的属性类。**必须有 `@Data`** 是因为属性绑定走的是 setter（宽松绑定），没有 setter 字段就绑不上；`@Data` 顺带生成 getter，供 `CosManager` 读 `host`/`bucket`。

**12.** 用户**不会收到任何报错**。`ScreenshotServiceImpl` 在截图或上传失败时会抛 `BusinessException`，但这个调用发生在虚拟线程里、且 `generateAppScreenshotAsync` 没有 try-catch，所以异常不会传回部署请求。部署接口照常返回成功 URL，只是 `app.cover` 保持 NULL——用户看到的现象是「应用列表里这张卡片没有封面图」，不会有任何错误提示。这是**典型的静默失败**：需要靠日志（「网页截图失败」/「上传截图到对象存储失败」）或数据库 `cover IS NULL` 来发现。

**13.** 变成该虚拟线程的**未捕获异常**，由 JVM 的默认未捕获异常处理器打印到 stderr，线程随即结束。后果是：后面的 `updateById`（回填 `cover`）**不会执行**，`cover` 保持原值；没有重试、没有补偿、没有告警。因为虚拟线程不由线程池托管、代码里也没设置 `UncaughtExceptionHandler`，异常没有任何上层捕获点。

**14.** 因为截图的 URL 是 `{deploy-host}/{deployKey}/`，**只有部署之后这个地址才存在**：部署把文件复制到 `tmp/code_deploy/{deployKey}`，再由 nginx 暴露出去。生成阶段只产出 `tmp/code_output/{type}_{appId}`，预览走的是另一个接口（`/api/static/{目录名}/`），两条路径完全不同——截部署地址才有对外分享的意义。副作用是截图紧跟部署触发，若 nginx 尚未生效或 dev 环境下该 URL 根本不可达，截图必然失败且无人感知（见第 12 题）。

</details>

### 7.7 本环节技术点清单

| 技术点 | 落地位置 | 作用 | 项目特有之处 |
|---|---|---|---|
| Selenium 无头截图 | `WebScreenshotUtils#L53-L88` | 把网页变成图片 | `selenium-java 4.33.0` + 无头 Chrome；固定 1600×900 |
| 驱动自动管理 | `WebScreenshotUtils#L96` | 免手工配驱动 | `WebDriverManager.chromedriver().setup()`，依赖网络下载 |
| 容器兼容参数 | `WebScreenshotUtils#L100-L112` | 让 Chrome 能在容器里跑 | `--no-sandbox`、`--disable-dev-shm-usage`、`--disable-gpu`、自定义 UA |
| 双层等待 | `WebScreenshotUtils#L167-L182` | 尽量截到渲染完的页面 | `readyState === complete` + 硬等 2 秒；**等待异常被吞、继续截图** |
| PNG→JPG 压缩 | `WebScreenshotUtils#L147-L160` | 减小上传体积 | Hutool `ImgUtil.compress(..., 0.3f)`；**原 PNG 用完即删** |
| 静态 driver 单例 | `WebScreenshotUtils#L30-L37` | 全局只起一个浏览器 | **`ChromeDriver` 非线程安全 + 虚拟线程并发调用 = 竞态**；静态块抛异常会导致类永久不可用 |
| `@PreDestroy` 失效 | `WebScreenshotUtils#L42-L45` | 本意是关闭浏览器 | 类**不是 Spring Bean**、调用是静态的，`destroy()` 永不执行 → **Chrome 进程泄漏** |
| 截图服务三段式 | `ScreenshotServiceImpl#L26-L44` | 截图 → 上传 → 清理 | `finally` 保证本地文件必删；两层 null 检查（截图失败 / 上传失败） |
| 对象键设计 | `ScreenshotServiceImpl#L62-L74` | COS 对象命名 | `/screenshots/yyyy/MM/dd/{8位UUID}_compressed.jpg`；**key 以 `/` 开头** |
| COS 客户端配置 | `CosClientConfig#L19-L57` | 装配 `COSClient` 单例 | `@Configuration` + `@ConfigurationProperties(prefix = "cos.client")` 合体，必须 `@Data` |
| SDK 薄封装 | `CosManager#L33-L55` | 上传并返回访问 URL | `String.format("%s%s", host, key)` 简单拼接，**不补协议**；无重试/超时/异常转换 |
| 虚拟线程异步截图 | `AppServiceImpl#L205-L218` | 不阻塞部署响应 | `Thread.startVirtualThread(...)`；**无 try-catch，失败静默** |
| 封面回填 | `AppServiceImpl#L212-L216` | 把 URL 写进 `app.cover` | 只更新 `id` + `cover` 两列；失败不重试 |

### 7.8 本环节产出

这个环节是「**部署完顺手给应用拍张照**」的自动化能力，选型可以概括为：**Selenium 无头 Chrome 负责截，Hutool 负责压，COS 负责存，虚拟线程负责不阻塞。** 截图侧 `WebScreenshotUtils` 用 `WebDriverManager.chromedriver().setup()` 免手工管驱动，`ChromeOptions` 里塞满容器兼容参数（`--headless` / `--no-sandbox` / `--disable-dev-shm-usage` / 固定 `--window-size=1600,900`），等待策略是「`readyState === complete` + 硬等 2 秒」的组合拳，产物走「PNG 中转 → `ImgUtil.compress(0.3f)` 压成 JPG → 删原图」；存储侧 `CosManager` 是标准的 SDK 薄封装，`CosClientConfig` 用 `@Configuration` + `@ConfigurationProperties(prefix = "cos.client")` 一个类同时充当配置类和属性对象（所以 `@Data` 不能省），对象键按 `/screenshots/yyyy/MM/dd/{8位UUID}_compressed.jpg` 组织（日期分目录控数量、UUID 防覆盖且不可猜）；编排侧 `ScreenshotServiceImpl` 是「截图 → 上传 → `finally` 清理本地文件」的三段式，由 `AppServiceImpl#generateAppScreenshotAsync` 用 `Thread.startVirtualThread` 异步触发，失败不影响部署接口的返回。四个最容易踩的坑：**一是 `WebScreenshotUtils` 不是 Spring Bean 而调用又是静态的，`@PreDestroy` 永远不会执行，Chrome 进程会残留**；**二是 `webDriver` 是静态单例而 `ChromeDriver` 非线程安全，多个虚拟线程并发截图会串台**（单机测试看不出来）；**三是 `cos.client.host` 必须自带协议**，配成 `your-custom-domain.com` 会让 `cover` 存下一个无协议字符串、前端 `<img>` 直接裂图；**四是整条截图链路失败是静默的**——虚拟线程里无 try-catch，用户只看到「封面是空的」而收不到任何报错。读到这里应该能回答：**「用户点了部署之后，应用列表里那张封面图是怎么来的」**——答案是：`deployApp#L195` 调 `generateAppScreenshotAsync` → `Thread.startVirtualThread` 起虚拟线程 → `ScreenshotServiceImpl#generateAndUploadScreenshot` 校验 URL → `WebScreenshotUtils.saveWebPageScreenshot` 复用静态无头 Chrome 打开 `{deploy-host}/{deployKey}/`、等渲染、截 PNG、压成 30% 质量 JPG、删 PNG → `CosManager#uploadFile` 把 JPG 传到 COS 并拼出 `{host}/screenshots/yyyy/MM/dd/xxx_compressed.jpg` → `finally` 删本地文件 → 回到虚拟线程把 URL 写进 `app.cover`。

---

## 环节 8：AI 可观测性

### 8.1 这个环节解决什么问题

前面 7 个环节讲的都是「功能怎么跑通」。但一个跑在线上、每天烧真金白银调大模型的系统，光能跑通是不够的，运营方必须随时能回答四个问题：

1. **今天一共调了多少次大模型？成功多少、失败多少？**
2. **烧了多少 Token？输入多少、输出多少？分别花在哪个模型上？**
3. **哪次调用慢？平均响应时间是多少？**
4. **是哪个用户、哪个应用烧掉的？**

这四个问题对应四类指标：**请求计数、Token 计数、响应耗时、错误计数**，而且每类指标都必须能按 `user_id` / `app_id` / `model_name` 维度下钻。本项目用 **LangChain4j 的 `ChatModelListener` 埋点 + Micrometer 采集 + Prometheus 拉取 + Grafana 展示**这条标准链路来解决，全部代码集中在 `monitor/` 包（4 个类）。

这个环节在架构上的定位是「**横切关注点**」：它不参与任何业务流程的分支判断，而是**挂在模型 Bean 上被动回调**，业务代码只负责在调用前把「我是谁、在给哪个应用干活」塞进一个上下文容器。

### 8.2 技术点一：ChatModelListener 三回调 + attributes 跨线程传参

#### 8.2.1 埋点是怎么挂上去的

LangChain4j 的模型构造器有一个 `listeners(...)` 方法，可以挂任意多个 `ChatModelListener`。本项目在**流式模型**的 Bean 定义里挂了监听器。

`config/StreamingChatModelConfig.java#L43-L56`：

```java
@Bean
@Scope("prototype")
public StreamingChatModel streamingChatModelPrototype() {
    return OpenAiStreamingChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .modelName(modelName)
            .maxTokens(maxTokens)
            .temperature(temperature)
            .logRequests(logRequests)
            .logResponses(logResponses)
            .listeners(List.of(aiModelMonitorListener))   // ← 埋点挂载点
            .build();
}
```

`config/ReasoningStreamingChatModelConfig.java#L42-L53` 是同样的写法（`reasoningStreamingChatModelPrototype`，用于 Vue 工程生成，带工具调用）。

**关键对照点**：`config/RoutingAiModelConfig.java#L36-L48` 构造的是 `OpenAiChatModel`（非流式，用于环节 1 的生成类型路由判断），**没有调用 `.listeners(...)`**。也就是说：

| 模型 Bean | 类型 | 是否挂监听器 | 后果 |
|---|---|---|---|
| `streamingChatModelPrototype` | `OpenAiStreamingChatModel` | ✅ `#L54` | HTML / MULTI_FILE 生成被监控 |
| `reasoningStreamingChatModelPrototype` | `OpenAiStreamingChatModel` | ✅ `#L51` | VUE_PROJECT 生成被监控 |
| `routingChatModelPrototype` | `OpenAiChatModel` | ❌ 无 | **环节 1 的路由判断调用完全不被监控**（不计请求数、不计 Token） |

`AiModelMonitorListener` 本身是 `@Component`（`monitor/AiModelMonitorListener.java#L18-L19`），两个 Config 用 `@Resource` 注入（`StreamingChatModelConfig#L23-L24`、`ReasoningStreamingChatModelConfig#L20-L21`）。因为模型 Bean 是 `@Scope("prototype")`（见环节 4），每个 `AiCodeGeneratorService` 实例都会拿到一份**新的模型对象**，但监听器实例是**共享的单例**——这一点很重要，下面讲缓存时会用到。

#### 8.2.2 三个回调分别在什么时候触发

`AiModelMonitorListener` 实现 `ChatModelListener` 的三个方法：

| 回调 | 位置 | 触发时机 | 干了什么 |
|---|---|---|---|
| `onRequest` | `#L29-L42` | 请求发出**前** | 存开始时间 + 存监控上下文 → `recordRequest(..., "started")` |
| `onResponse` | `#L44-L60` | 收到**完整响应后** | `recordRequest(..., "success")` + 记录耗时 + 记录 Token |
| `onError` | `#L62-L77` | 调用**抛异常时** | `recordRequest(..., "error")` + `recordError(...)` + 记录耗时 |

注意 `onRequest` 记的是 `"started"` 而不是 `"success"`，这是刻意的：**同一个请求会在 `onRequest` 和 `onResponse` 各记一次**，只不过 `status` 标签不同。所以 `ai_model_requests_total` 这个 Counter 的**真实语义是「事件数」而不是「请求数」**——`sum(ai_model_requests_total)` 会把每次调用数成两遍。Grafana 面板里也是这么用的（`grafana/ai_model_grafana_config.json#L88` 只查 `status="success"`，`#L266` 用 `or vector(0)` 兜底空值），这属于**约定俗成的用法，但看板作者必须知道这个坑**。

#### 8.2.3 为什么要把上下文塞进 `attributes`（本环节最核心的设计点）

`onRequest` 的第一件事是：

```java
// monitor/AiModelMonitorListener.java#L32-L37
requestContext.attributes().put(REQUEST_START_TIME_KEY, Instant.now());
MonitorContext monitorContext = MonitorContextHolder.getContext();
String userId = monitorContext.getUserId();
String appId = monitorContext.getAppId();
requestContext.attributes().put(MONITOR_CONTEXT_KEY, monitorContext);
```

`MonitorContext` 只有两个字段（`monitor/MonitorContext.java`）：`userId` 和 `appId`，都是 `String`。它由业务侧在调 AI 之前塞进 ThreadLocal：

```java
// service/impl/AppServiceImpl.java#L102-L107
MonitorContextHolder.setContext(
        MonitorContext.builder()
                .userId(loginUser.getId().toString())
                .appId(appId.toString())
                .build()
);
```

ThreadLocal 容器本身极简（`monitor/MonitorContextHolder.java#L11-L32`）：`private static final ThreadLocal<MonitorContext> CONTEXT_HOLDER = new ThreadLocal<>();` + `set` / `get` / `clear`（`clear` 调的是 `remove()`，不是 `set(null)`，能避免内存泄漏）。

**那为什么 `onRequest` 拿到 ThreadLocal 之后还要往 `attributes` 里再放一份？** 代码注释直接给了答案（`AiModelMonitorListener.java#L22-L24`）：

```java
// 用于监控上下文传递（因为请求和响应事件的触发不是同一个线程）
private static final String MONITOR_CONTEXT_KEY = "monitor_context";
```

ThreadLocal 是**线程私有**的，而 LangChain4j 的流式调用是异步的——`onResponse` 是在**消费流的那个线程**上被回调的，跟发起请求的线程不是同一个。所以：

- **`onResponse` 必须从 `responseContext.attributes()` 取**（`#L47-L51`），因为 ThreadLocal 在那个线程上是空的；
- `attributes` 是 LangChain4j 在同一个请求生命周期内传递的 `Map<Object, Object>`，**跨线程可用**，是这里唯一的「接力棒」。

这是一个非常典型的「ThreadLocal 在异步链路里失效」的案例，也是本项目可观测性模块里**唯一一处真正的技术难点**。

#### 8.2.4 但 `onError` 没有跟着改（不一致的隐患）

`onError` 的写法是（`#L63-L67`）：

```java
public void onError(ChatModelErrorContext errorContext) {
    // 从监控上下文中获取信息
    MonitorContext context = MonitorContextHolder.getContext();   // ← 又是 ThreadLocal！
    String userId = context.getUserId();
    String appId = context.getAppId();
```

`onResponse` 已经从 `attributes` 取值了，`onError` 却还在用 `MonitorContextHolder.getContext()`。按照代码自己的注释（「请求和响应事件的触发不是同一个线程」），**错误回调同样可能不在原线程上触发**，此时 `getContext()` 返回 `null`，紧接着 `context.getUserId()` 就是一次 **NPE**。

更微妙的是：这个 NPE 发生在**监听器内部**，它会被 LangChain4j 的回调分发逻辑吞掉或转成日志，**业务侧的异常处理完全感知不到**——用户看到的是「模型调用失败」，运维看到的是「错误指标没上报」，而根因是一条日志里的一行 NPE。

> 这是一个「**只会在出错路径上复现**」的问题：正常流程测不出来，压测也测不出来，只有模型真的报错时才炸。写代码时 `onResponse` 和 `onError` 一定要对照着写。

#### 8.2.5 清理时机

`MonitorContextHolder` 全项目只有 4 个触点：

| 动作 | 位置 |
|---|---|
| `setContext` | `service/impl/AppServiceImpl.java#L102-L107` |
| `getContext` | `monitor/AiModelMonitorListener.java#L34`（`onRequest`）、`#L65`（`onError`） |
| `clearContext` | `service/impl/AppServiceImpl.java#L112-L115`（`doFinally` 回调里） |

清理写在 `doFinally` 里是关键：`doFinally` 在 **onComplete / onError / cancel 三种信号**下都会执行，所以无论流正常结束、异常中断还是客户端断开（SSE 场景下很常见），ThreadLocal 都会被清掉，**不会有 ThreadLocal 残留污染下一次请求**（Tomcat 线程池复用线程，这点很重要）。

另一个值得注意的点：`createApp` 里调路由模型**没有**设置 `MonitorContext`。这不是漏写——因为 `RoutingAiModelConfig` 压根没挂监听器，所以那个调用链上不会有 `getContext()` 的调用，也就不会 NPE。**「没挂监听器」和「没设上下文」这两件事恰好互相兜住了**，但这是巧合而非设计，改动任意一侧都会引入 NPE。

### 8.3 技术点二：Micrometer 指标模型与指标缓存

#### 8.3.1 依赖与自动装配

`pom.xml#L113-L121` 引入两个依赖：

```xml
<!-- 监控 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

两者都是 **Spring Boot 官方 BOM 管理版本，pom 里不写 `<version>`**（对照 `#L125-L126` 的 MyBatis-Flex 是显式写了 `1.11.1` 的，可以印证这个区别）。

`micrometer-registry-prometheus` 是 Micrometer 的**注册表实现（Registry）**：Micrometer 提供统一门面（`MeterRegistry`），Prometheus 是其中一个后端。项目里**没有任何 Java 配置类去手工创建 `MeterRegistry`**，它由 Boot 的自动装配创建——这一点很容易被误判，因为 `AiModelMetricsCollector` 里是 `@Resource private MeterRegistry meterRegistry;`（`#L21-L22`），看起来像「项目自己装配的」，实际是 Boot 给的。

暴露端点靠配置（`src/main/resources/application.yml#L96-L104`）：

```yaml
# 监控
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: always
```

三个要点：
1. **只暴露了 3 个端点**（默认只暴露 `health`，`include` 是覆盖而非追加）——`info` 和 `prometheus` 是额外开的。
2. `show-details: always` 会让 `/actuator/health` **把数据库、Redis 等各组件的健康详情明文返回**，方便排查但**在生产环境属于信息泄露**（会暴露连接地址、异常堆栈），这也是 `application-prod-sample.yml` 里需要留意的配置项。
3. **端点前缀带了 context-path**：因为 `application.yml` 里配了 `server.servlet.context-path: /api`，所以真实地址是 `/api/actuator/prometheus`，而不是 `/actuator/prometheus`。`prometheus.yml#L26` 正是按带前缀的路径抓的。

#### 8.3.2 四类指标与 tags 设计

`monitor/AiModelMetricsCollector.java` 定义 4 个方法，对应 4 个指标名：

| 方法 | 指标名 | Meter 类型 | tags | 位置 |
|---|---|---|---|---|
| `recordRequest` | `ai_model_requests_total` | `Counter` | `user_id`、`app_id`、`model_name`、`status` | `#L33-L45` |
| `recordError` | `ai_model_errors_total` | `Counter` | `user_id`、`app_id`、`model_name`、`error_message` | `#L50-L62` |
| `recordTokenUsage` | `ai_model_tokens_total` | `Counter` | `user_id`、`app_id`、`model_name`、`token_type` | `#L67-L80` |
| `recordResponseTime` | `ai_model_response_duration_seconds` | `Timer` | `user_id`、`app_id`、`model_name` | `#L85-L96` |

几个必须点出的细节：

**① 指标名带 `_total` 后缀是 Prometheus 惯例。** Micrometer 在导出到 Prometheus 时还会自动加 `_total`（对 Counter），本项目**名字里已经手写了 `_total`**，最终抓到的名字要以下面 8.5 节实测为准——这也是排查「指标名字对不上」时的第一嫌疑点。

**② `token_type` 是「一条指标三种用途」。** `recordTokenUsage` 被调三次（`AiModelMonitorListener.java#L94-L96`）：

```java
aiModelMetricsCollector.recordTokenUsage(userId, appId, modelName, "input", tokenUsage.inputTokenCount());
aiModelMetricsCollector.recordTokenUsage(userId, appId, modelName, "output", tokenUsage.outputTokenCount());
aiModelMetricsCollector.recordTokenUsage(userId, appId, modelName, "total", tokenUsage.totalTokenCount());
```

用 `input` / `output` / `total` 三种 tag 值区分，而不是建三个指标。Grafana 里就是这么查的（`grafana/ai_model_grafana_config.json#L146` 查 `token_type="total"`，`#L367-L374` 按 `model_name` 分组分别画输入和输出曲线）。**`total` 不是 `input + output` 算出来的，而是模型直接返回的**，所以理论上可能出现三者对不上（缓存命中的 Token 不计入 total 之类），对账时要注意。

**③ `Timer` 记录的是 `Duration`。** `recordResponseTime` 收 `java.time.Duration`，`timer.record(duration)`（`#L95`）。Micrometer 的 `Timer` 导出到 Prometheus 会**自动展开成三个指标**：`_count`（次数）、`_sum`（总秒数）、`_max`（最大值）——所以「平均响应时间」的 PromQL 是 `sum(..._sum) / sum(..._count)`（`grafana/ai_model_grafana_config.json#L204` 正是这么写的），而**不是**一个叫 `_avg` 的指标。

**④ `error_message` 直接当 tag 是个危险设计。** `recordError` 把 `errorContext.error().getMessage()`（`AiModelMonitorListener.java#L70`）原样塞进 tag：

```java
// monitor/AiModelMetricsCollector.java#L51-L58
String key = String.format("%s_%s_%s_%s", userId, appId, modelName, errorMessage);
Counter counter = errorCountersCache.computeIfAbsent(key, k ->
        Counter.builder("ai_model_errors_total")
                ...
                .tag("error_message", errorMessage)
```

异常 message 是**高基数**数据（可能带堆栈片段、请求 ID、随机内容），每出现一个新 message 就会在 Prometheus 里新建一条时间序列，**把时序数据库打爆**（经典的 "cardinality explosion"）。而且 message 里还可能带引号、换行，Prometheus 标签值里的换行会被转义成 `\n`，看板里显示很难看。生产上更稳妥的做法是错误**分类**（如 `TIMEOUT` / `RATE_LIMIT` / `AUTH`）后再打 tag，原始 message 只进日志。

#### 8.3.3 为什么要给 Meter 加缓存

`AiModelMetricsCollector` 里有 4 个 `ConcurrentMap`（`#L24-L28`）：

```java
// 缓存已创建的指标，避免重复创建（按指标类型分离缓存）
private final ConcurrentMap<String, Counter> requestCountersCache = new ConcurrentHashMap<>();
private final ConcurrentMap<String, Counter> errorCountersCache = new ConcurrentHashMap<>();
private final ConcurrentMap<String, Counter> tokenCountersCache = new ConcurrentHashMap<>();
private final ConcurrentMap<String, Timer> responseTimersCache = new ConcurrentHashMap<>();
```

用法统一是 `computeIfAbsent(key, k -> Counter.builder(...).register(meterRegistry))`：

```java
// monitor/AiModelMetricsCollector.java#L34-L44
String key = String.format("%s_%s_%s_%s", userId, appId, modelName, status);
Counter counter = requestCountersCache.computeIfAbsent(key, k ->
        Counter.builder("ai_model_requests_total")
                .description("AI模型总请求次数")
                .tag("user_id", userId)
                .tag("app_id", appId)
                .tag("model_name", modelName)
                .tag("status", status)
                .register(meterRegistry)
);
counter.increment();
```

**为什么必须缓存？** 因为 Micrometer 的 `MeterRegistry` 内部虽然也有自己的 map，但**每次调用 `register()` 都要走一遍「构建 tag 列表 → 拼唯一 key → 查表 → 可能加锁」的流程**，在 SSE 流式场景下这个方法是**每个 token 之外每完成一次调用都会走**的路径，属于热路径。自己在外面用 `ConcurrentHashMap` 缓存住 `Counter` 引用，后续只做一次 `map.get` + `counter.increment()`，是标准的 Micrometer 用法优化。

注意这里的缓存 key 是**用 `String.format` 手工拼的**，格式为 `userId_appId_modelName_status` 这种下划线连接。这带来一个隐含约束：**如果 `userId` 或 `appId` 里本身含下划线，不同组合可能拼出相同的 key**（例如 `("a_b","c")` 与 `("a","b_c")`）。本项目里 `userId`/`appId` 都是数字，所以不会撞，但这是个**脆弱的拼接方式**，换成 `List.of(...).toString()` 或元组对象更稳。

还有一个「被优化掉的风险」值得提：**`computeIfAbsent` 的映射函数里调用了外部 `meterRegistry`**。`ConcurrentHashMap.computeIfAbsent` 在持有 bin 锁的情况下执行映射函数，而 `register()` 内部又会去操作 Micrometer 自己的并发容器——虽然两者不是同一把锁、本项目也没有嵌套调用同一个 `ConcurrentHashMap`，不会死锁，但这是**公认的「不要在 computeIfAbsent 里做重活」**的反例写法。

#### 8.3.4 配套的 Prometheus 与 Grafana

**`prometheus.yml`（仓库根目录）**：

```yaml
global:
  scrape_interval: 15s      # 全局抓取间隔
  evaluation_interval: 15s  # 规则评估间隔
...
scrape_configs:
  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']
  - job_name: 'yu-ai-code-mother'
    metrics_path: '/api/actuator/prometheus'  # Spring Boot Actuator 端点
    static_configs:
      - targets: ['localhost:8123']  # 应用服务器地址
    scrape_interval: 10s  # 每 10 秒抓取一次
    scrape_timeout: 10s   # 抓取超时时间
```

三个细节：
- `metrics_path` **带了 `/api` 前缀**，与 `context-path` 对应，漏了就是 404；
- job 级 `scrape_interval: 10s` **覆盖了全局的 15s**（Prometheus 的层级覆盖规则）；
- `targets: ['localhost:8123']` 是**写死的**，意味着这份配置只适用于「Prometheus 和本应用同机」的场景；容器化部署时 `localhost` 指的是 Prometheus 容器自身，必须改成服务名或宿主机 IP。
- `alerting.alertmanagers` 和 `rule_files` 都被注释掉了（`prometheus.yml#L7-L15`），即**项目没有配任何告警**，只有看板。

**`grafana/ai_model_grafana_config.json`**：一个可直接导入的 Dashboard（`"id": 5`），数据源是名为 `prometheus` 的 Prometheus（`"uid": "prometheus"`，即**要求 Grafana 里数据源的 uid 必须正好是 `prometheus`**，否则导入后所有面板报错）。面板结构：

| 区块 | 面板 | PromQL |
|---|---|---|
| 概览指标 | 总成功请求数 | `sum(ai_model_requests_total{status="success"})` |
| 概览指标 | 总 Token 消耗 | `sum(ai_model_tokens_total{token_type="total"})` |
| 概览指标 | 平均响应时间 | `sum(ai_model_response_duration_seconds_sum) / sum(ai_model_response_duration_seconds_count)` |
| 概览指标 | 总错误数 | `sum(ai_model_errors_total) or vector(0)` |
| Token 消耗分析 | Token 消耗累积趋势 | `sum by (model_name) (ai_model_tokens_total{token_type="input"})` / `..._output` |

注意最后一条用了 `or vector(0)`：**当没有任何错误时 `sum()` 返回空**，面板会显示 "No data"，`or vector(0)` 把它兜成 0。这是 Grafana 看板的常规技巧。

#### 8.3.5 项目里「未引入」的东西

按纪律明确列出，避免读者按常识脑补：

| 常见可观测性组件 | 本项目状态 |
|---|---|
| Micrometer Tracing / OpenTelemetry / Zipkin / SkyWalking（链路追踪） | **未引入**，pom 中无相关依赖，全项目无 `Tracer` / `Span` 使用 |
| `@Timed` / `@Counted` 等 Micrometer 注解 | **未使用**，全部指标都是手工 `Counter.builder(...)` 创建 |
| `MeterBinder` 自定义 Binder | **未使用** |
| 结构化日志 / ELK / Loki（日志采集） | **未引入** |
| Alertmanager 告警规则 | 配置文件中**被注释掉**，无告警 |
| AI 调用审计表（把每次调用的 Token 落库） | **无此表**，Token 数据只存在于 Prometheus 的时序里，**重启 Prometheus 前的历史可查，但无法按业务维度做 SQL 对账** |
| Prometheus / Grafana 的 Docker Compose 编排 | 仓库只有配置文件，**无 docker-compose 文件**（Prometheus 与 Grafana 需自行部署） |

### 8.4 代码走读

一次「HTML 模式流式生成」的完整埋点时序（从上到下即执行顺序）：

```
1. 请求线程：AppServiceImpl#chatToGenCode
   └─ #L102  MonitorContextHolder.setContext(MonitorContext{userId, appId})   ← ThreadLocal 有值
   └─ #L109  aiCodeGeneratorFacade.generateAndSaveCodeStream(...)              ← 只是组装 Flux，未真正发请求

2. 订阅线程（响应流被消费时）：LangChain4j 发起 HTTP 请求
   └─ AiModelMonitorListener#onRequest
        ├─ #L32   attributes.put("request_start_time", Instant.now())
        ├─ #L34   MonitorContextHolder.getContext()          ← 若与 set 不同线程则此处已 NPE
        ├─ #L37   attributes.put("monitor_context", monitorContext)   ← 跨线程接力棒
        └─ #L41   recordRequest(userId, appId, modelName, "started")

3. 流式 token 陆续返回 → AiCodeGeneratorFacade#processCodeStream 攒 StringBuilder
   （此处不埋点：token 级别没有指标）

4. 流结束：AiModelMonitorListener#onResponse
   └─ #L49   context = (MonitorContext) responseContext.attributes().get("monitor_context")  ← 从 attributes 取回
   └─ #L55   recordRequest(..., "success")
   └─ #L57   recordResponseTime(attributes, ...)     ← 从 attributes 取 start_time 算 Duration
   └─ #L59   recordTokenUsage(...)                   ← input / output / total 各记一次

   ── 若第 3 步抛异常，改走 onError ──
   └─ #L65   context = MonitorContextHolder.getContext()   ← ⚠️ ThreadLocal，跨线程时为 null → NPE

5. 请求线程：AppServiceImpl#chatToGenCode 的 doFinally 回调
   └─ #L114  MonitorContextHolder.clearContext()      ← remove()，三种信号都清理
```

`recordResponseTime` 的实现（`AiModelMonitorListener.java#L82-L86`）：

```java
private void recordResponseTime(Map<Object, Object> attributes, String userId, String appId, String modelName) {
    Instant startTime = (Instant) attributes.get(REQUEST_START_TIME_KEY);
    Duration responseTime = Duration.between(startTime, Instant.now());
    aiModelMetricsCollector.recordResponseTime(userId, appId, modelName, responseTime);
}
```

这里 **`startTime` 为 null 时 `Duration.between` 会直接 NPE**，没有兜底。它依赖「`onRequest` 一定先于 `onResponse`/`onError` 执行」这个约定——正常情况成立，但如果 `onRequest` 自己先 NPE 了（比如上面说的 ThreadLocal 问题），这里就会连续炸第二次。

再看 `onResponse` 的 Token 记录（`#L91-L98`）：

```java
TokenUsage tokenUsage = responseContext.chatResponse().metadata().tokenUsage();
if (tokenUsage != null) {
    aiModelMetricsCollector.recordTokenUsage(userId, appId, modelName, "input", tokenUsage.inputTokenCount());
    aiModelMetricsCollector.recordTokenUsage(userId, appId, modelName, "output", tokenUsage.outputTokenCount());
    aiModelMetricsCollector.recordTokenUsage(userId, appId, modelName, "total", tokenUsage.totalTokenCount());
}
```

**`tokenUsage` 做了 null 检查，但 `metadata()` 没做**。如果某些模型/代理（比如部分兼容 OpenAI 协议的第三方网关）不返回 `usage` 字段，`metadata()` 本身可能是 null，这一行依然会 NPE。这是一个「**只检查了最里面一层**」的典型遗漏——按防御性写法的顺序，应该从外往里逐层判空。

### 8.5 动手验证

#### 验证 1：应用起来了，端点通不通

```bash
# 注意带 context-path /api
curl -i http://localhost:8123/api/actuator/health
```

预期：HTTP 200，返回 JSON，且因为 `show-details: always`，`components` 里能看到 `db`、`redis` 等组件的详细状态（这一步同时验证了「健康详情明文暴露」这个配置确实生效）。

```bash
# 查看已暴露的端点列表
curl -s http://localhost:8123/api/actuator | head -40
```

预期：`_links` 里只有 `self`、`health`、`info`、`prometheus` 等少数几项——**不会**有 `env`、`beans`、`configprops`、`threaddump`，因为它们没在 `include` 列表里。

#### 验证 2：指标是否真的被采集到

```bash
# 先看有没有 AI 自定义指标（此时可能为空，因为还没调用过模型）
curl -s http://localhost:8123/api/actuator/prometheus | grep ai_model_
```

预期：**第一次调用模型之前，这里什么都没有**。原因是 Micrometer 的 `Counter`/`Timer` 是**懒注册**的——只有第一次 `register()` 之后才会出现在输出里。这是一个很容易误判为「配置错了」的点。

然后触发一次真实生成（需要先登录拿到 session，或用浏览器操作），再查：

```bash
curl -s http://localhost:8123/api/actuator/prometheus | grep ai_model_requests_total
```

预期看到类似（tag 顺序由 Micrometer 决定，不要按顺序匹配）：

```
ai_model_requests_total{app_id="1",model_name="qwen-max",status="started",user_id="1"} 1.0
ai_model_requests_total{app_id="1",model_name="qwen-max",status="success",user_id="1"} 1.0
```

**注意上面这两行：一次调用产生了 `started` 和 `success` 两条时间序列，各自计数 1。** 这就是 8.2.2 节说的「Counter 语义是事件数」的实证。

```bash
# Token 指标：应当有三条（input/output/total）
curl -s http://localhost:8123/api/actuator/prometheus | grep ai_model_tokens_total

# 耗时指标：Timer 会展开成 _count / _sum / _max 三条
curl -s http://localhost:8123/api/actuator/prometheus | grep ai_model_response_duration_seconds
```

预期 Token 三条形如：

```
ai_model_tokens_total{...,token_type="input",...} 1234.0
ai_model_tokens_total{...,token_type="output",...} 5678.0
ai_model_tokens_total{...,token_type="total",...} 6912.0
```

耗时预期形如（**单位是秒**，`_count` 是调用次数、`_sum` 是总秒数）：

```
ai_model_response_duration_seconds_count{...} 1.0
ai_model_response_duration_seconds_sum{...} 12.345
ai_model_response_duration_seconds_max{...} 12.345
```

自己算平均：`12.345 / 1.0 ≈ 12.3` 秒，和 Grafana 的「平均响应时间」面板应当一致。

#### 验证 3：验证「路由模型不被监控」

在平台上**新建一个应用**（会触发环节 1 的 `AiCodeGenTypeRoutingService` 调用 `routingChatModelPrototype`），然后：

```bash
curl -s http://localhost:8123/api/actuator/prometheus | grep ai_model_requests_total
```

预期：`ai_model_requests_total` 的**计数没有增加**（因为 `RoutingAiModelConfig` 没挂 listener）。这一步是验证「环节 1 的模型调用是监控盲区」的实证。

#### 验证 4：验证 ThreadLocal 清理

连续对同一个应用发起 **3 次**对话生成，然后：

```bash
curl -s http://localhost:8123/api/actuator/prometheus | grep 'ai_model_requests_total{.*status="success"'
```

预期：`success` 的计数精确等于 3（如果 ThreadLocal 没清理干净导致 appId 串了，会多出 `app_id="其它值"` 的时间序列）。**这是验证 `doFinally` + `remove()` 是否生效的最直接方法。**

#### 验证 5：Prometheus 是否抓得到

假设 Prometheus 已按 `prometheus.yml` 启动在 9090：

```bash
# 查看 target 是否 UP
curl -s 'http://localhost:9090/api/v1/targets' | grep -o '"health":"[a-z]*"'
```

预期：`"health":"up"`。如果出现 `"health":"down"` 且 error 是 `connection refused`，通常是 `targets` 里的 `localhost:8123` 与实际部署方式不符（容器场景，见 8.3.4）。

```bash
# 直接查 Prometheus 里的指标
curl -s 'http://localhost:9090/api/v1/query?query=sum(ai_model_tokens_total%7Btoken_type%3D%22total%22%7D)'
```

预期：`"value"` 数组里是一个累计 Token 数，和 Grafana「总Token消耗」面板一致。

#### 验证 6：Grafana 导入

1. Grafana → Data sources → 新增 Prometheus，**URL 填 `http://localhost:9090`**；
2. **把数据源的 uid 手工改成 `prometheus`**（这一步不能省，见 8.3.4）；
3. Dashboards → Import → 上传 `grafana/ai_model_grafana_config.json`。

预期：4 个概览面板有数；若「总错误数」显示 0 而不是 "No data"，说明 `or vector(0)` 兜底生效。

### 8.6 自测题

<details>
<summary>展开查看题目（共 12 题）</summary>

**Q1.** `AiModelMonitorListener` 为什么要把 `MonitorContext` 放进 `requestContext.attributes()`，而不是直接依赖 `MonitorContextHolder`？

**Q2.** 一次成功的模型调用，`ai_model_requests_total` 会增加几次？`status` 分别是什么？

**Q3.** `onResponse` 从 `responseContext.attributes()` 取上下文，`onError` 却用 `MonitorContextHolder.getContext()`，这个不一致会导致什么后果？为什么这个问题很难在测试环境发现？

**Q4.** `recordResponseTime` 里 `Instant startTime = (Instant) attributes.get(REQUEST_START_TIME_KEY);` 如果 `startTime` 为 null 会怎样？什么情况下会为 null？

**Q5.** `AiModelMetricsCollector` 为什么要在外面再套 4 个 `ConcurrentMap`？Micrometer 的 `MeterRegistry` 内部不是已经有缓存了吗？

**Q6.** `ai_model_tokens_total` 为什么要有 `token_type="total"` 这一条，直接用 `input + output` 算不行吗？

**Q7.** Grafana 里「平均响应时间」为什么写成 `sum(..._sum) / sum(..._count)`，而不是直接读某个 `_avg` 指标？

**Q8.** 为什么项目要在 `application.yml` 里显式写 `include: health,info,prometheus`？不写会怎样？

**Q9.** `prometheus.yml` 里 `metrics_path` 为什么是 `/api/actuator/prometheus` 而不是 `/actuator/prometheus`？

**Q10.** 用 `errorContext.error().getMessage()` 当 tag 有什么风险？

**Q11.** 环节 1 的「生成类型智能路由」调用了大模型，为什么这些调用在 Prometheus 里查不到？

**Q12.** `MonitorContextHolder.clearContext()` 为什么写在 `doFinally` 里而不是 `doOnComplete` 里？

**Q13（陷阱）.** 项目把 `MonitorContext` 的 `userId`/`appId` 存成 `String` 而不是 `Long`，是为了省内存吗？

**Q14（陷阱）.** 既然 `streamingChatModelPrototype` 是 `@Scope("prototype")`（每个 AI 服务实例一份新模型），而 `AiModelMonitorListener` 是单例，会不会出现「多个模型 Bean 的指标互相覆盖」？

</details>

<details>
<summary>展开查看答案与解析</summary>

**A1.** 因为 `onResponse` 与 `onRequest` **不在同一个线程上触发**（代码注释 `AiModelMonitorListener.java#L22-L24` 明确写了这一点）。`MonitorContextHolder` 底层是 `ThreadLocal`，线程私有；而 LangChain4j 的流式回调是在消费流的线程上执行的，ThreadLocal 在那个线程上是空的。`attributes` 是 LangChain4j 在同一请求生命周期内传递的 `Map`，**跨线程可用**，所以必须靠它接力。这是本模块最核心的设计点。

**A2.** **两次。** `onRequest` 记一次 `status="started"`（`#L41`），`onResponse` 再记一次 `status="success"`（`#L55`）。所以 `ai_model_requests_total` 的真实语义是「事件计数」而非「请求计数」。**陷阱在于**：`sum(ai_model_requests_total)` 会把每次调用数成两遍，必须按 `status` 过滤才有意义。Grafana 面板也是这么做的（只查 `status="success"`）。

**A3.** 后果：`onError` 里 `MonitorContextHolder.getContext()` 返回 `null`，紧接着 `context.getUserId()`（`#L66`）抛 **NPE**。这个 NPE 发生在**监听器内部**，会被 LangChain4j 的回调分发吞掉，业务侧感知不到——表现为「模型报错了，但错误指标没上报」，只留一行日志。**难发现的原因**：它只在**错误路径**上触发。正常流程、压测、功能测试都走不到 `onError`，只有模型真的超时/限流/返回非法格式时才炸。

**A4.** `Duration.between(null, Instant.now())` 抛 **NPE**（`#L83-L84`）。为 null 的情况：`onRequest` 没有执行成功（比如它自己在 `#L35` 就因 ThreadLocal 为空而 NPE 了，后面的 `attributes.put` 自然没执行），或者 LangChain4j 在极端情况下没有按 `onRequest → onError` 的顺序回调。**这段代码没有任何兜底**，属于「假设前置回调一定成功」的乐观写法。

**A5.** 虽然 `MeterRegistry` 内部确实有 meter 缓存，但**每次 `Counter.builder(...).register(...)` 都要走一遍「拼 tag 列表 → 生成唯一 key → 查表 → 可能加锁」的流程**。而 `recordRequest` / `recordTokenUsage` 处于 AI 调用的热路径上（一次生成会调用多次），每次重建 Meter 是纯浪费。外面用 `ConcurrentHashMap` 缓存住 Meter 引用后，后续只剩 `map.get` + `counter.increment()`，是最小代价的优化。**补充**：这种写法本身有个瑕疵——映射函数里调用了外部 `meterRegistry`，属于「不要在 `computeIfAbsent` 里做重活」的反例（虽然本项目不会死锁）。

**A6.** 因为 **`total` 是模型直接返回的字段**（`tokenUsage.totalTokenCount()`），不是算出来的。真实模型/网关的 `total` 未必等于 `input + output`（可能包含缓存命中、工具调用等额外计费项），对账时以哪个为准取决于业务口径。把它单独存一条，就是保留了「以模型返回为准」的可能性。**陷阱**：如果图省事只记 `input` 和 `output`，将来做费用核算时会和账单对不上。

**A7.** 因为 Micrometer 的 `Timer` 导出到 Prometheus 时**只展开成 `_count` / `_sum` / `_max` 三个指标，没有 `_avg`**。Prometheus 的查询模型是「对时间序列做聚合」，平均值的标准算法就是「总量 / 次数」，所以写成 `sum(_sum) / sum(_count)`。这也是为什么面板查询里必须先 `sum()` 再除——直接 `_sum / _count` 会因为 tag 维度不匹配而报错。

**A8.** Boot 的 Actuator **默认只通过 HTTP 暴露 `health`**（`include` 是**覆盖式**配置，不是追加）。不写的话 `prometheus` 端点虽然存在（因为引入了 registry 依赖，端点会被创建），但**HTTP 访问会 404**，Prometheus 直接抓不到数据。**这是「依赖引了但端点没开」的经典坑**。

**A9.** 因为 `application.yml` 里配置了 `server.servlet.context-path: /api`，所有 MVC 端点的路径前都要加这个前缀，Actuator 端点也不例外。真实地址是 `http://host:8123/api/actuator/prometheus`。**陷阱**：本地用 `curl /actuator/prometheus` 会 404，很容易误判成「端点没暴露」，然后去改 `management` 配置，方向就错了。

**A10.** 两大风险：**① 高基数爆炸**——异常 message 千变万化（含随机 ID、堆栈片段），每出现一个新 message 就新建一条 Prometheus 时间序列，会把时序库打爆；**② 可读性差**——message 里的换行/引号会被转义，看板里显示成 `\n` 一串。更稳妥的做法是把错误**归类**（`TIMEOUT` / `RATE_LIMIT` / `AUTH` 等固定枚举）后当 tag，原始 message 只进日志。

**A11.** 因为路由判断用的是 `routingChatModelPrototype`（`RoutingAiModelConfig#L36-L48`），它构造的是 `OpenAiChatModel`，**没有调用 `.listeners(...)`**，所以 `AiModelMonitorListener` 根本不会被回调。而另外两个流式模型 Bean（`StreamingChatModelConfig#L54`、`ReasoningStreamingChatModelConfig#L51`）都挂了监听器。**结论**：环节 1 的路由调用是监控盲区，它消耗的 Token 也不会被统计——做费用核算时这是个漏洞。

**A12.** 因为 `doFinally` 在 **onComplete / onError / cancel 三种信号**下都会执行，而 `doOnComplete` **只在正常完成时执行**。SSE 场景下客户端断开（cancel）非常常见，如果用 `doOnComplete`，用户关掉浏览器页面后 ThreadLocal 就会残留；Tomcat 线程池复用线程，下一个请求复用到这个线程时 `getContext()` 会读到**上一个用户的上一个应用**的上下文，导致指标串号。**这是「指标张冠李戴」类事故的经典成因。**

**A13.** **不是。** 存成 `String` 的真实原因是**为了适配指标的 tag 类型**：Prometheus 的标签值**只能是字符串**，`userId`/`appId` 是 `Long`，所以 `AppServiceImpl#L104-L105` 在 `setContext` 时就 `.toString()` 转好了，后面 `AiModelMetricsCollector` 直接用，避免在热路径上反复转换。省内存这个说法站不住脚——`String` 反而比 `Long` 占更多内存。**这个设计的副作用**：一旦某处忘了 `toString()`，编译期就会报错，所以实际上是一种「用类型换安全」的取舍。

**A14.** **不会覆盖，但会「重复计数」。** 指标的唯一性由 **指标名 + tag 组合**决定，与「哪个 Bean 实例产生的」无关。多个 prototype 模型 Bean 共享同一个单例监听器 → 同一个 `AiModelMetricsCollector` → 同一个 `MeterRegistry`，因此相同 `(userId, appId, modelName, status)` 的调用会**累加到同一条时间序列上**，这正是我们想要的（按业务维度聚合，而不是按 Bean 实例聚合）。**但如果多个模型 Bean 的 `modelName` 配成一样**，就无法从指标上区分是「普通流式模型」还是「推理流式模型」的消耗了——这也是配置 `application.yml` 时要注意的点。

</details>

### 8.7 本环节技术点清单

| 技术点 | 落地位置 | 作用 | 项目特有之处 |
|---|---|---|---|
| `ChatModelListener` 埋点 | `monitor/AiModelMonitorListener.java#L18-L19` | 在模型调用的三个生命周期回调里采集指标 | 挂载方式是 `.listeners(List.of(bean))`，写在模型 **Bean 定义**里而不是业务代码里 |
| 埋点挂载范围 | `StreamingChatModelConfig#L54`、`ReasoningStreamingChatModelConfig#L51` | 覆盖 HTML/MULTI_FILE/VUE 三种生成 | **`RoutingAiModelConfig` 未挂载 → 环节 1 路由调用是监控盲区** |
| `attributes` 跨线程传参 | `AiModelMonitorListener.java#L37`、`#L49` | 把监控上下文从请求线程传到回调线程 | 因为 `onRequest`/`onResponse` **不同线程**，ThreadLocal 失效，`attributes` 是唯一接力棒 |
| `onError` 不对称取值 | `AiModelMonitorListener.java#L65` | 记录错误指标 | **仍用 ThreadLocal，与 `onResponse` 不一致 → 错误路径 NPE 风险** |
| ThreadLocal 上下文 | `monitor/MonitorContextHolder.java#L11-L32` | 业务侧把 `userId`/`appId` 传给埋点 | 只有 `set`/`get`/`clear` 三个静态方法；`clear` 用 `remove()` 防泄漏 |
| 上下文清理时机 | `AppServiceImpl#L112-L115` | 流结束时清理 | 写在 `doFinally` 里，覆盖 complete/error/cancel 三种信号 |
| 上下文设置时机 | `AppServiceImpl#L102-L107` | 调 AI 前设置 | 只在 `chatToGenCode` 里设置；`createApp` 不设（因路由模型未挂监听器，恰好不 NPE） |
| Counter 指标 | `AiModelMetricsCollector#L33-L80` | 请求数 / 错误数 / Token 数 | `onRequest` 也计数（`status="started"`），**一次调用产生两条序列** |
| Timer 指标 | `AiModelMetricsCollector#L85-L96` | 记录响应耗时 | 收 `Duration`，导出为 `_count`/`_sum`/`_max` 三条，**无 `_avg`** |
| 指标缓存 | `AiModelMetricsCollector#L24-L28` | 避免热路径重复 `register()` | 4 个独立 `ConcurrentMap` + `computeIfAbsent`；**key 用下划线拼接，脆弱** |
| 高基数风险 | `AiModelMetricsCollector#L51-L58` | 错误指标 | `error_message` 直接当 tag，**异常 message 高基数会打爆时序库** |
| Token 三分法 | `AiModelMonitorListener#L91-L98` | 区分输入/输出/总量 | `token_type` 三值 tag；**`total` 来自模型返回而非计算** |
| 逐层判空不完整 | `AiModelMonitorListener#L92-L93` | 防 NPE | 只判了 `tokenUsage != null`，**`metadata()` 未判** |
| Actuator 端点暴露 | `application.yml#L96-L104` | 提供 `/actuator/prometheus` | `include` 是**覆盖式**配置；`show-details: always` 生产有信息泄露风险 |
| context-path 影响 | `application.yml`（`/api`）+ `prometheus.yml#L26` | 抓取地址 | 真实路径 `/api/actuator/prometheus`，**漏 `/api` 就 404** |
| Prometheus 抓取配置 | `prometheus.yml#L25-L30` | 拉取应用指标 | job 级 `scrape_interval: 10s` **覆盖全局 15s**；`targets` 写死 `localhost:8123` |
| 无告警 | `prometheus.yml#L7-L15` | — | `alerting` / `rule_files` **均被注释掉**，只有看板没有告警 |
| Grafana 看板 | `grafana/ai_model_grafana_config.json` | 可视化 | 数据源 uid 必须为 `prometheus`；错误面板用 `or vector(0)` 兜空值 |
| 未引入链路追踪 | pom.xml | — | **无 Micrometer Tracing / OpenTelemetry / Zipkin / SkyWalking**，只有指标没有 Trace |
| 未使用注解式埋点 | 全项目 | — | **无 `@Timed` / `@Counted` / 自定义 `MeterBinder`**，全部手工建 Meter |

### 8.8 本环节产出

这个环节是「**给烧钱的 AI 调用装一块电表**」，选型可以概括为：**LangChain4j 的 `ChatModelListener` 负责埋点，`ThreadLocal` + `attributes` 负责把「谁在用、给哪个应用用」传下去，Micrometer 负责统一计量，Prometheus 负责拉取存储，Grafana 负责展示。** 埋点位置选得很聪明——不是把统计代码散落在每个业务方法里，而是**挂在模型 Bean 的构造器上**（`.listeners(List.of(aiModelMonitorListener))`），业务侧只保留一行 `MonitorContextHolder.setContext(...)`，做到了「业务与监控解耦」。四个指标的设计（`ai_model_requests_total` / `ai_model_errors_total` / `ai_model_tokens_total` / `ai_model_response_duration_seconds`）都带齐了 `user_id`/`app_id`/`model_name` 三个下钻维度，配合 Grafana 面板能直接回答「哪个用户最烧钱」这个问题。最容易踩的坑有四个：**一是 ThreadLocal 在异步链路里失效**——`onRequest` 和 `onResponse` 不在同一个线程，所以必须把上下文塞进 `attributes` 接力（这是全模块最核心的一处设计）；**二是 `onError` 没有跟着改成从 `attributes` 取值**，还在用 `MonitorContextHolder.getContext()`，错误路径上会 NPE 且被吞掉，属于「只在故障时复现」的隐患；**三是 `error_message` 直接当 tag 会高基数打爆 Prometheus**；**四是 `RoutingAiModelConfig` 没挂监听器**，环节 1 的路由调用是彻底的监控盲区，Token 消耗统计不到。另外三个「引了依赖但没开」的细节也要记住：`include` 是覆盖式配置（不写 `prometheus` 就 404）、`/api` 前缀不能漏、Grafana 数据源 uid 必须正好是 `prometheus`。读到这里应该能回答：**「用户点一次生成，平台怎么知道这次调用烧了多少 Token、花了多长时间、算在谁头上」**——答案是：`AppServiceImpl#L102` 把 `userId`/`appId` 放进 `MonitorContextHolder`（ThreadLocal）→ 模型 Bean 触发 `AiModelMonitorListener#onRequest`，把开始时间和 `MonitorContext` 一起写进 `requestContext.attributes()`，记一次 `status="started"` → 流式响应结束触发 `onResponse`，**从 `responseContext.attributes()` 取回上下文**（跨线程接力），记一次 `status="success"`、用 `Duration.between(startTime, now)` 记耗时、按 `input`/`output`/`total` 记三次 Token → `AiModelMetricsCollector` 用 `computeIfAbsent` 从 4 个缓存里拿到 Meter 并 `increment()`/`record()` → Micrometer 经 Prometheus Registry 暴露在 `/api/actuator/prometheus` → Prometheus 每 10 秒抓一次 → Grafana 面板展示 → 最后 `AppServiceImpl#L114` 在 `doFinally` 里 `clearContext()`，避免线程复用导致下一次请求指标串号。

---

## 环节 9：分布式限流与权限校验

### 9.1 这个环节解决什么问题

前 8 个环节讲的都是「功能怎么跑通」，这一环节解决的是**「谁能调、能调多少次」**。

本项目有一个天然的高危接口：`GET /app/chat/gen/code`（AI 对话生成代码）。它有两个特点：

1. **每一次调用都真金白银地烧 Token**——没有限流的话，一个用户写个脚本循环调用就能把账户余额刷空；
2. **它是一个 SSE 长连接**——单个请求占用连接时间长，并发上来了会打满线程/连接池。

所以平台需要两道闸门：

| 闸门 | 要回答的问题 | 本项目方案 |
|---|---|---|
| **限流** | 同一个用户，1 分钟内最多能生成几次？ | `@RateLimit` 注解 + `RateLimitAspect` 切面 + Redisson `RRateLimiter` |
| **鉴权** | 这个接口/这个操作，当前用户有没有资格做？ | `@AuthCheck` 注解 + `AuthInterceptor` 切面 + Session 登录态 |

这两件事在实现上是**完全独立的两套 AOP 机制**，代码也放在不同包里：限流在 `ratelimter/`（注意包名拼写，少了个 `i`），鉴权在 `annotation/` + `aop/`。它们共同的技术底座是「**注解声明意图 + AOP 切面执行逻辑**」，业务代码里只留一行注解。

### 9.2 技术点一：Redisson 分布式限流

#### 9.2.1 注解定义

`ratelimter/annotation/RateLimit.java#L10-L37`：

```java
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流key前缀 */
    String key() default "";

    /** 每个时间窗口允许的请求数 */
    int rate() default 10;

    /** 时间窗口（秒） */
    int rateInterval() default 1;

    /** 限流类型 */
    RateLimitType limitType() default RateLimitType.USER;

    /** 限流提示信息 */
    String message() default "请求过于频繁，请稍后再试";
}
```

`@Target(ElementType.METHOD)`——**只能打在方法上**，不能打类或参数；`@Retention(RUNTIME)`——切面要靠反射读，必须保留到运行时。这两个是自定义注解能被 AOP 识别的**必要条件**，缺一个切面就静默失效。

三个限流维度由枚举定义（`ratelimter/enums/RateLimitType.java#L3-L19`）：`API`（接口级）、`USER`（用户级）、`IP`（IP 级）。**默认值是 `USER`**——这个默认值选得很合理，因为「按用户限流」是业务上最常见的诉求。

#### 9.2.2 全项目只有一处使用了限流

Grep `@RateLimit` 的结果只有一行：

```java
// controller/AppController.java#L59-L60
@GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60, message = "AI 对话请求过于频繁，请稍后再试")
public Flux<ServerSentEvent<String>> chatToGenCode(...)
```

即「**每个用户 60 秒内最多发起 5 次 AI 生成**」。

**必须点明的现状**：`/app/add`（创建应用）、`/app/deploy`（部署）、`/app/download/{appId}`（下载 ZIP）等接口**都没有加 `@RateLimit`**。而 `/app/add` 内部会调用路由模型（环节 1）、`/app/deploy` 会触发 `npm install`（环节 6）——两者都是重资源操作，却完全没有限流保护。**这套限流框架是「建好了但只用了一处」**。

#### 9.2.3 切面实现：`@Before` 前置拦截

`ratelimter/aspect/RateLimitAspect.java#L29-L52`：

```java
@Aspect
@Component
@Slf4j
public class RateLimitAspect {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private UserService userService;

    @Before("@annotation(rateLimit)")
    public void doBefore(JoinPoint point, RateLimit rateLimit) {
        String key = generateRateLimitKey(point, rateLimit);
        // 使用 Redisson 的分布式限流器
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
        rateLimiter.expire(Duration.ofHours(1)); // 1 小时后过期
        // 设置限流器参数：每个时间窗口允许的请求数和时间窗口
        rateLimiter.trySetRate(RateType.OVERALL, rateLimit.rate(), rateLimit.rateInterval(), RateIntervalUnit.SECONDS);
        // 尝试获取一个令牌，如果获取失败则限流
        if (!rateLimiter.tryAcquire(1)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, rateLimit.message());
        }
    }
```

逐点拆解：

**① 用 `@Before` 而不是 `@Around`。** 限流是「不通过就不执行」的语义，`@Before` 抛异常天然会中断原方法，比 `@Around` 里手工 `proceed()` 更简洁。**代价**：切面拿不到原方法的返回值，也做不了后置统计（比如「被限流了几次」）。这里够用。

**② 切点表达式是 `@annotation(rateLimit)`。** 这是「匹配所有标注了该注解的方法」的写法，参数名 `rateLimit` 与方法签名的第二个参数 `RateLimit rateLimit` **必须同名**——这是 Spring AOP 的**参数绑定约定**，改名就绑定失败（报 `IllegalArgumentException`）。这是新手最容易踩的坑。

**③ `RateType.OVERALL` 是分布式限流的关键。**

| RateType | 语义 | 效果 |
|---|---|---|
| `OVERALL` | 所有 Redisson 客户端**共享**同一个令牌桶 | 集群部署 3 个实例，总配额仍是 5 次/60 秒 |
| `PER_CLIENT` | 每个客户端**各自**维护令牌桶 | 3 个实例各 5 次 → 实际放行 15 次 |

项目选了 `OVERALL`，这才是真正的「分布式限流」。如果选 `PER_CLIENT`，那就退化成单机限流，多实例部署时配额会被放大 N 倍。**判断一个限流实现是不是真的分布式，就看这一行。**

**④ `trySetRate` 是「仅在不存在时设置」——这是个隐藏的坑。**

Redisson 的 `trySetRate` 语义是：**如果这个 key 对应的限流器还不存在，才设置参数；已存在则忽略**。它不像 `setRate` 那样强制覆盖。带来的后果是：

> 你把 `rate = 5` 改成 `rate = 10`，重启应用，**限流依然是 5**——因为 Redis 里的限流器已经存在了，`trySetRate` 直接跳过。必须等这个 key 在 Redis 里过期（或手工 `DEL`）才会用上新参数。

**⑤ `rateLimiter.expire(Duration.ofHours(1))` 每次请求都执行——TTL 被不断刷新。**

这行代码的本意是「1 小时后过期，避免 Redis 里堆积垃圾 key」。但它写在 `doBefore` 里，意味着**每一次请求都会把 TTL 重置为 1 小时**。所以：

- 一个**长期活跃**的用户（比如每隔 10 分钟用一次），它的限流 key **永远不会过期**；
- 真正会过期的只有「用完就再也不来」的用户。

换句话说，`expire` 在这里**起不到「清理热点 key」的作用**（热点 key 恰恰是最不会过期的），它只能清理冷 key——而冷 key 本来也不占什么资源。这是一个**意图与效果错位**的写法。（更合理的做法是只在 `trySetRate` 成功后设置一次 TTL，或干脆不给限流器设 TTL 而用 Redis 的 `maxmemory-policy` 兜底。）

#### 9.2.4 限流 key 的生成规则

`RateLimitAspect#L61-L102`：

```java
private String generateRateLimitKey(JoinPoint point, RateLimit rateLimit) {
    StringBuilder keyBuilder = new StringBuilder();
    keyBuilder.append("rate_limit:");
    // 添加自定义前缀
    if (!rateLimit.key().isEmpty()) {
        keyBuilder.append(rateLimit.key()).append(":");
    }
    // 根据限流类型生成不同的key
    switch (rateLimit.limitType()) {
        case API:
            MethodSignature signature = (MethodSignature) point.getSignature();
            Method method = signature.getMethod();
            keyBuilder.append("api:").append(method.getDeclaringClass().getSimpleName())
                    .append(".").append(method.getName());
            break;
        case USER:
            try {
                ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attributes != null) {
                    HttpServletRequest request = attributes.getRequest();
                    User loginUser = userService.getLoginUser(request);
                    keyBuilder.append("user:").append(loginUser.getId());
                } else {
                    keyBuilder.append("ip:").append(getClientIP());
                }
            } catch (BusinessException e) {
                // 未登录用户使用IP限流
                keyBuilder.append("ip:").append(getClientIP());
            }
            break;
        case IP:
            keyBuilder.append("ip:").append(getClientIP());
            break;
        default:
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的限流类型");
    }
    return keyBuilder.toString();
}
```

生成的 key 形如：

| 场景 | key 示例 |
|---|---|
| 已登录用户（USER 类型） | `rate_limit:user:1876543210` |
| 未登录降级（USER 类型） | `rate_limit:ip:192.168.1.100` |
| 带自定义前缀 | `rate_limit:chat:user:1876543210` |
| API 类型 | `rate_limit:api:AppController.chatToGenCode` |

**设计亮点**：`USER` 类型里做了**三级降级**——① 拿不到请求上下文（`attributes == null`）→ 用 IP；② `getLoginUser` 抛 `BusinessException`（未登录）→ 用 IP；③ 正常 → 用 userId。这样**未登录用户也能被限住**，不会出现「不登录就没有限流」的漏洞。**这是这套实现里最值得学的一点**。

**但降级到 IP 也有副作用**：同一个公司/学校出口 IP 后面的所有未登录用户会**共享同一个配额**——5 次/60 秒。这属于 NAT 环境下的经典误伤，线上真实存在（这也是为什么很多平台要求登录后才给用 AI 能力）。

**性能代价**：`userService.getLoginUser(request)` 会**查一次数据库**（见 9.3.2 节 `UserServiceImpl#L121` 的 `this.getById(userId)`）。也就是说**每一个被限流的请求都要额外打一次 DB**。对于这个「限流」这种纯内存/Redis 操作来说，这是一笔不小的开销——理想做法是只从 Session 里读 userId 而不回源查库。

**关于 `catch (BusinessException e)` 只捕获 `BusinessException`**：如果 `getLoginUser` 抛的是其它异常（比如 Redis 挂了导致 Session 读取失败），这个 catch 抓不住，会直接冒泡成 500。这里只兜了「未登录」这一种业务异常，属于**有意识的窄捕获**，但边界比较脆。

#### 9.2.5 客户端 IP 的获取

`RateLimitAspect#L109-L127`：

```java
private String getClientIP() {
    ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
        return "unknown";
    }
    HttpServletRequest request = attributes.getRequest();
    String ip = request.getHeader("X-Forwarded-For");
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
        ip = request.getHeader("X-Real-IP");
    }
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
        ip = request.getRemoteAddr();
    }
    // 处理多级代理的情况
    if (ip != null && ip.contains(",")) {
        ip = ip.split(",")[0].trim();
    }
    return ip != null ? ip : "unknown";
}
```

标准的「三级取 IP」写法：`X-Forwarded-For` → `X-Real-IP` → `getRemoteAddr()`，并且对多级代理的 `X-Forwarded-For`（形如 `client, proxy1, proxy2`）**取第一段**。

**必须点出的安全风险**：`X-Forwarded-For` / `X-Real-IP` 都是**客户端可以随意伪造的请求头**。如果没有前置的可信代理（Nginx 等）去重写这两个头，攻击者只要每次请求带一个不同的 `X-Forwarded-For: 1.2.3.4`，就能**绕过所有基于 IP 的限流**（因为每次都命中不同的限流 key）。

正确的做法是：只在**确定请求来自可信代理**时才信任这些头（Spring Boot 有 `server.forward-headers-strategy` 和 `ForwardedHeaderFilter` 可做统一处理），或者直接把 Nginx 层作为唯一的 IP 限流点。**本项目没有做这个校验**——如果直接暴露到公网，IP 限流形同虚设。

#### 9.2.6 Redisson 客户端配置

`ratelimter/config/RedissonConfig.java#L11-L46`：

```java
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private Integer redisPort;

    @Value("${spring.data.redis.password}")
    private String redisPassword;

    @Value("${spring.data.redis.database}")
    private Integer redisDatabase;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + redisHost + ":" + redisPort;
        SingleServerConfig singleServerConfig = config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisDatabase)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(10)
                .setIdleConnectionTimeout(30000)
                .setConnectTimeout(5000)
                .setTimeout(3000)
                .setRetryAttempts(3)
                .setRetryInterval(1500);
        // 如果有密码则设置密码
        if (redisPassword != null && !redisPassword.isEmpty()) {
            singleServerConfig.setPassword(redisPassword);
        }
        return Redisson.create(config);
    }
}
```

几个关键点：

**① 项目用的是裸 `redisson` 依赖，不是 `redisson-spring-boot-starter`。** `pom.xml#L46-L51`：

```xml
<!-- Redisson -->
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>3.50.0</version>
</dependency>
```

**这是全项目少数几个显式写了 `<version>` 的依赖**（因为它不在 Spring Boot BOM 里）。用裸依赖意味着**必须手工写 `Config` 并 `Redisson.create(config)`**——如果用 starter，Spring Boot 会自动从 `spring.data.redis.*` 装配一个 `RedissonClient`，这个配置类就完全不需要了。

**② 手工复用 `spring.data.redis.*` 的配置项。** 好处是 Redis 连接信息只有一份（Spring Session 用 `spring.data.redis.*`，Redisson 也从这里读），不会出现「Session 连 A 库、限流连 B 库」的错配。**代价**是这种复用是「靠人肉约定」的——Redisson 的官方 starter 会自动绑定这个前缀，这里是用 4 个 `@Value` 硬拼的，**新增配置项（如集群、哨兵、SSL）必须手工同步**。

**③ 地址拼接 `"redis://" + host + ":" + port`。** Redisson 的地址**必须带协议前缀**（`redis://` 或 `rediss://`），少了会抛 `IllegalArgumentException`。这跟环节 7 里 `cos.client.host` 必须自带协议是同一类坑——**"拼字符串"式的配置最容易漏协议**。

**④ 连接池与超时参数**（这些是 Redisson 的默认值或接近默认值，但写出来更明确）：

| 参数 | 值 | 含义 |
|---|---|---|
| `connectionMinimumIdleSize` | 1 | 最小空闲连接数 |
| `connectionPoolSize` | 10 | 最大连接数 |
| `idleConnectionTimeout` | 30000ms | 空闲连接超时 |
| `connectTimeout` | 5000ms | 建立连接超时 |
| `timeout` | 3000ms | **命令执行超时** |
| `retryAttempts` | 3 | 命令失败重试次数 |
| `retryInterval` | 1500ms | 重试间隔 |

**⑤ 只支持单机 Redis。** 用的是 `config.useSingleServer()`，**没有** `useClusterServers()` / `useSentinelServers()` / `useMasterSlaveServers()`。生产上如果用 Redis 集群，这个配置类必须重写。

**⑥ 密码为空的处理是对的**：`if (redisPassword != null && !redisPassword.isEmpty())` 两个条件都判了。因为 `application.yml#L21` 里 `password:` 后面是空的，`@Value` 注入进来的是**空字符串**而非 null——只判 `!= null` 会漏，这里两个都判是稳妥写法。

**⑦ Redisson 全项目只用于限流，没有用分布式锁。** Grep `RedissonClient` 只有 3 个命中（切面注入 1 处、切面使用 1 处、配置类 1 处）。**`RLock` / `getLock()` / `getFairLock()` 在全项目零使用**——即「**未使用 Redisson 分布式锁**」。这一点必须明确，避免读者看到 Redisson 就以为项目做了分布式锁。

### 9.3 技术点二：AOP 权限校验与 Session 登录态

#### 9.3.1 注解与切面

`annotation/AuthCheck.java#L8-L17`：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthCheck {

    /** 必须有某个角色 */
    String mustRole() default "";
}
```

`aop/AuthInterceptor.java#L19-L58`：

```java
@Aspect
@Component
public class AuthInterceptor {

    @Resource
    private UserService userService;

    @Around("@annotation(authCheck)")
    public Object doInterceptor(ProceedingJoinPoint joinPoint, AuthCheck authCheck) throws Throwable {
        String mustRole = authCheck.mustRole();
        RequestAttributes requestAttributes = RequestContextHolder.currentRequestAttributes();
        HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        UserRoleEnum mustRoleEnum = UserRoleEnum.getEnumByValue(mustRole);
        // 不需要权限，直接放行
        if (mustRoleEnum == null) {
            return joinPoint.proceed();
        }
        // 以下的代码：必须有这个权限才能通过
        UserRoleEnum userRoleEnum = UserRoleEnum.getEnumByValue(loginUser.getUserRole());
        // 没有权限，直接拒绝
        if (userRoleEnum == null) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 要求必须有管理员权限，但当前登录用户没有
        if (UserRoleEnum.ADMIN.equals(mustRoleEnum) && !UserRoleEnum.ADMIN.equals(userRoleEnum)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 通过普通用户的权限校验，放行
        return joinPoint.proceed();
    }
}
```

**第一个必须点明的坑：类名叫 `AuthInterceptor`，但它不是 Spring MVC 的 `HandlerInterceptor`。** 它没有任何 `implements HandlerInterceptor`，也没注册到 `WebMvcConfigurer#addInterceptors`，而是靠 `@Aspect` + `@Around` 实现的。命名极具误导性——**按名字去 `WebMvcConfig` 里找它的注册代码是找不到的**。

**为什么用 `@Around` 而不是 `@Before`？** 因为鉴权通过后必须**继续执行原方法**，`@Around` 里显式 `joinPoint.proceed()` 才能把原方法的返回值透传出去。如果用 `@Before`，虽然不抛异常也能放行，但无法拿到/修改返回值。**「既要拦又要放行」的场景，`@Around` 是唯一选择**（对比 9.2.3 的限流，只需要「拦」，所以用 `@Before`）。

**执行顺序**：先取 `mustRole` → 取当前用户（**这一步就要求必须登录**）→ 解析角色枚举 → 逐级判断。

**`@AuthCheck` 的第二个隐含语义：只要打了这个注解，就隐含「必须登录」。** 因为 `userService.getLoginUser(request)` 在角色判断**之前**执行（`#L40`），未登录会直接抛 `NOT_LOGIN_ERROR`（40100）。也就是说 `@AuthCheck(mustRole = "")` 等价于「仅要求登录」。

#### 9.3.2 角色判断逻辑的漏洞

仔细看 `#L53` 这一行：

```java
if (UserRoleEnum.ADMIN.equals(mustRoleEnum) && !UserRoleEnum.ADMIN.equals(userRoleEnum)) {
    throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
}
```

**只有 `mustRoleEnum == ADMIN` 时才会真正做角色比对。** 推演各种情况：

| `mustRole` | `mustRoleEnum` | 用户角色 | 实际结果 | 是否符合预期 |
|---|---|---|---|---|
| `"admin"` | ADMIN | admin | ✅ 放行 | ✅ |
| `"admin"` | ADMIN | user | ❌ 拒绝 | ✅ |
| `"user"` | USER | user | ✅ 放行 | ✅ |
| `"user"` | USER | **任意登录用户**（含 admin） | ✅ 放行 | ⚠️ 勉强 |
| `""`（默认） | null | 任意登录用户 | ✅ 放行 | ✅（等价仅登录） |
| `"vip"`（不存在） | null | 任意登录用户 | ✅ 放行 | ❌ **静默放行！** |

**最后一行是真正的坑**：如果谁写了 `@AuthCheck(mustRole = "vip")`，而 `UserRoleEnum` 里没有 `vip`，`getEnumByValue` 返回 null → `mustRoleEnum == null` → **直接 `proceed()` 放行，任何登录用户都能过**。注解写错一个字母，权限就没了，而且**编译不报错、启动不报错、日志不报错**。这是一个「**配置错误导致鉴权静默失效**」的典型设计缺陷。

更稳的设计应该是：`mustRole` 非空但解析不出枚举时**抛异常**（快速失败），而不是静默放行。

**另一个问题是角色模型太薄**：只有 `USER` / `ADMIN` 两个角色（`model/enums/UserRoleEnum.java#L9-L10`），判断逻辑是硬编码的「是不是 ADMIN」，**没有角色层级/权限点模型**。将来加第三种角色（比如 VIP），这个切面就得改代码。

#### 9.3.3 `@AuthCheck` 的使用现状

全项目 11 处使用，全部是 `mustRole = UserConstant.ADMIN_ROLE`：

| 文件 | 行号 | 接口 |
|---|---|---|
| `controller/UserController.java` | `#L95` | `POST /user/add` 创建用户 |
| `controller/UserController.java` | `#L113` | `GET /user/get` 根据 id 获取用户 |
| `controller/UserController.java` | `#L135` | `POST /user/delete` 删除用户 |
| `controller/UserController.java` | `#L148` | `POST /user/update` 更新用户 |
| `controller/UserController.java` | `#L166` | `POST /user/list/page/vo` 分页获取用户 |
| `controller/AppController.java` | `#L297` | `POST /app/admin/delete` 管理员删除应用 |
| `controller/AppController.java` | `#L317` | `POST /app/admin/update` 管理员更新应用 |
| `controller/AppController.java` | `#L342` | `POST /app/admin/list/page/vo` 管理员分页 |
| `controller/AppController.java` | `#L363` | `GET /app/admin/get/vo` 管理员查详情 |
| `controller/ChatHistoryController.java` | `#L63` | 管理员查看对话历史 |

`UserConstant.ADMIN_ROLE = "admin"`（`constant/UserConstant.java#L23`）——**用接口常量而不是枚举值**，所以 `mustRole` 是字符串。这也是为什么 `AuthInterceptor` 要调 `getEnumByValue` 转换：注解属性只能是基本类型/String/Class/枚举/注解/数组，这里选了 `String` 而不是直接写 `UserRoleEnum.ADMIN`，是一个可以简化但没简化的设计。

**注意「非管理员接口」的权限是怎么做的**：比如 `AppController#updateApp`（用户更新自己的应用）、`AppServiceImpl#chatToGenCode#L90-L92`（`if (!app.getUserId().equals(loginUser.getId()))`）——**这些是写在业务代码里的手工判断，不是注解**。也就是说项目的鉴权是「**注解管管理员，业务代码管数据归属**」的两层结构。这个分工要记住，否则会以为「没打 `@AuthCheck` 的接口就是没鉴权」。

#### 9.3.4 登录态：Redis Session + MD5 密码

**登录态方案是 Session，不是 JWT。** 这一点必须明确——项目**未引入 Spring Security、未引入 Sa-Token、未引入 JWT**（pom 中无相关依赖）。

Session 的存储配置（`src/main/resources/application.yml#L4-L8`）：

```yaml
# session 配置
spring:
  session:
    store-type: redis
    # session 30 天过期
    timeout: 2592000
```

```yaml
# application.yml#L26-L32
server:
  port: 8123
  servlet:
    context-path: /api
    # cookie 30 天过期
    session:
      cookie:
        max-age: 2592000
```

`spring-session-data-redis` 依赖在 `pom.xml#L42-L45`。三个要点：

1. **`store-type: redis` 让 Session 变成分布式共享**——多实例部署时，用户在 A 实例登录、请求打到 B 实例也能识别。这是「分布式 Session」的标准解法，也是本项目能水平扩展的前提。
2. **`timeout: 2592000` 是 30 天**（2592000 秒），服务端 Session 过期时间；
3. **`cookie.max-age: 2592000` 也是 30 天**，浏览器端 Cookie 存活时间。两个值**必须对齐**——如果 cookie 比 session 长，用户会带着一个「服务端已删除的 sessionId」访问，表现为「看起来已登录但一操作就跳登录页」。

**登录态写入**（`service/impl/UserServiceImpl.java#L105-L108`）：

```java
// 4. 如果用户存在，记录用户的登录态
request.getSession().setAttribute(USER_LOGIN_STATE, user);
// 5. 返回脱敏的用户信息
return this.getLoginUserVO(user);
```

`USER_LOGIN_STATE = "user_login"`（`constant/UserConstant.java#L11`）。

**这里有一个值得注意的点：存进 Session 的是完整的 `User` 实体对象**，而 `user` 是从数据库查出来的（`#L101`），**包含 `userPassword` 字段**。Session 存在 Redis 里，意味着**密码哈希被写进了 Redis**。虽然 Redis 通常是内网服务，但这是不必要的暴露面——正确的做法是 Session 里只存 `userId`，需要用户信息时再查（反正 `getLoginUser` 本来就会回源查库）。

**读取登录态并回源查库**（`service/impl/UserServiceImpl.java#L111-L126`）：

```java
@Override
public User getLoginUser(HttpServletRequest request) {
    // 先判断用户是否登录
    Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
    User currentUser = (User) userObj;
    if (currentUser == null || currentUser.getId() == null) {
        throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
    }
    // 从数据库查询当前用户信息
    long userId = currentUser.getId();
    currentUser = this.getById(userId);
    if (currentUser == null) {
        throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
    }
    return currentUser;
}
```

**这个方法是全项目鉴权链路的核心，有三个设计点：**

**① 「Session 只当凭证，数据以数据库为准」。** 拿到 Session 里的 userId 后，**再查一次数据库**返回最新用户信息。这样管理员改了用户角色，用户**下一次请求就立刻生效**，不用等 Session 过期。**代价是每次鉴权多一次 DB 查询**——`@AuthCheck` 的每个接口、限流切面的每次调用，都要多打一次 DB。这是一个明确的「**一致性换性能**」取舍。

**② 两层 null 检查对应两种失败**：Session 里没东西 → 未登录；Session 有 userId 但库里查不到 → 用户被删了。两种情况都抛 `NOT_LOGIN_ERROR(40100)`，对前端来说语义统一（都是「请重新登录」），处理起来简单。

**③ 这解释了为什么「限流切面会打 DB」**——`RateLimitAspect#L83` 调的 `userService.getLoginUser(request)` 就是它。所以一个「被限流拒绝」的请求，实际上**已经消耗了一次数据库查询**。限流本该是廉价操作，这里却带上了 DB 开销，是这套实现里最实际的性能问题。

**密码加密**（`service/impl/UserServiceImpl.java#L181-L186`）：

```java
@Override
public String getEncryptPassword(String userPassword) {
    // 盐值，混淆密码
    final String SALT = "yupi";
    return DigestUtils.md5DigestAsHex((userPassword + SALT).getBytes(StandardCharsets.UTF_8));
}
```

- 用的是 **Spring 自带的 `org.springframework.util.DigestUtils`**（不是 Hutool、也不是 BCrypt）；
- 算法是 **MD5**，加了一个**全局固定的硬编码盐值 `"yupi"`**；
- 登录时直接在 SQL 里比对密码（`#L98-L101`）：

```java
QueryWrapper queryWrapper = new QueryWrapper();
queryWrapper.eq("userAccount", userAccount);
queryWrapper.eq("userPassword", encryptPassword);
User user = this.mapper.selectOneByQuery(queryWrapper);
```

**必须明确指出的安全问题**：
1. **MD5 已被证明不适合存密码**（碰撞攻击 + GPU 暴力破解极快）；
2. **盐值是全局固定且硬编码在代码里**——同一个密码在所有用户那里哈希值相同，彩虹表可以预计算，盐值完全失去意义。正确做法是**每个用户一个随机盐**（并存在用户行里）；
3. **正确做法是用 BCrypt / Argon2 这类自带盐值和计算成本的自适应哈希**（Spring Security 的 `BCryptPasswordEncoder` 是标准选择）。

这是一个教学项目的典型取舍——**能跑通、逻辑清晰，但绝不能用于生产**。读者如果要把这套代码用到真实项目，密码存储必须换掉。

**登录接口也是「明文密码过网」**：`userLogin` 接收 `userPassword` 明文，靠 HTTPS 保护传输。这本身是常规做法，但要在 HTTPS 缺失时（本项目 `application.yml` 里没有 HTTPS 配置）意识到风险。

#### 9.3.5 限流异常的响应路径

限流抛的是 `BusinessException(ErrorCode.TOO_MANY_REQUEST, message)`，`TOO_MANY_REQUEST(42900, "请求过于频繁")`（`exception/ErrorCode.java#L12`）。

它由 `GlobalExceptionHandler#businessExceptionHandler`（`#L25-L34`）统一处理：

```java
@ExceptionHandler(BusinessException.class)
public BaseResponse<?> businessExceptionHandler(BusinessException e) {
    log.error("BusinessException", e);
    // 尝试处理 SSE 请求
    if (handleSseError(e.getCode(), e.getMessage())) {
        return null;
    }
    // 对于普通请求，返回标准 JSON 响应
    return ResultUtils.error(e.getCode(), e.getMessage());
}
```

**两个必须点明的行为：**

**① HTTP 状态码是 200，不是 429。** `ResultUtils.error(...)` 返回的是包装体 `{code: 42900, message: "...", data: null}`，**HTTP 状态码仍是 200**。所以前端不能靠 `response.status === 429` 判断限流，**必须解析 body 里的 `code`**。这是这类「统一响应体」项目的通病，也是前后端联调时最容易扯皮的点。

**② 限流异常走了 SSE 错误分支。** 因为 `chatToGenCode` 是 SSE 接口，`handleSseError`（`#L53` 起）会判断 `Accept` 头是否含 `text/event-stream` 或 URI 是否匹配，是的话走 SSE 的 error 事件（返回 `null` 让 Spring 不再写 JSON），不是则返回普通 JSON。**这是限流能对 SSE 接口生效的关键**——否则客户端拿到一个 JSON body 但期待 SSE 流，会直接解析失败。

#### 9.3.6 两个切面的执行顺序（未定义）

`RateLimitAspect`（`@Before`）和 `AuthInterceptor`（`@Around`）**都没有标注 `@Order`**。Spring AOP 在未指定 `@Order` 时，**多个切面的相对顺序是不确定的**（实践中通常与 Bean 名称/定义顺序相关，但这不是规范保证的行为）。

这带来一个真实的行为差异：

| 谁先执行 | 后果 |
|---|---|
| `AuthInterceptor` 先 | 未登录用户直接抛 `NOT_LOGIN_ERROR(40100)`，**不消耗限流额度** |
| `RateLimitAspect` 先 | 未登录用户走 IP 降级，**先消耗一次 IP 配额**，然后才被鉴权拒绝 |

在 `chatToGenCode` 这个接口上，`@RateLimit` 打在 `AppController` 的方法上，而 `@AuthCheck` 没打（该接口内部靠 `userService.getLoginUser(request)` 手工校验，见 `AppController#L68`）。所以这个具体接口上不会出现顺序问题。**但这是一个隐患**：如果将来给同一个方法同时打上 `@AuthCheck` 和 `@RateLimit`，就会依赖一个未定义的顺序。**正确做法是给两个切面显式加 `@Order`**（限流通常应该在最外层，避免无效请求穿透到后面的逻辑）。

### 9.4 代码走读

一次「已登录用户连续第 6 次调用 AI 生成」的完整执行链路：

```
1. Tomcat 接收 GET /api/app/chat/gen/code?appId=1&message=xxx

2. Spring Session 过滤器：用 cookie 里的 SESSION 去 Redis 取 session
   └─ 命中 → 把 session 绑定到当前请求（后续 request.getSession() 可直接用）

3. Spring MVC 路由到 AppController#chatToGenCode

4. AOP 切面链（顺序未定义，本接口只命中一个）：
   └─ RateLimitAspect#doBefore（@Before）
        ├─ #L42   generateRateLimitKey(point, rateLimit)
        │    └─ #L63   keyBuilder = "rate_limit:"
        │    └─ #L65   rateLimit.key() 为空 → 不加自定义前缀
        │    └─ #L79-L84  limitType = USER → 走 try 分支
        │         ├─ #L80   RequestContextHolder.getRequestAttributes()  → 非 null
        │         ├─ #L83   userService.getLoginUser(request)
        │         │         └─ UserServiceImpl#L114  session 取 "user_login" → 有值
        │         │         └─ UserServiceImpl#L121  this.getById(userId)  ← 多打一次 DB
        │         └─ #L84   keyBuilder.append("user:").append(loginUser.getId())
        │    → key = "rate_limit:user:1"
        ├─ #L44   redissonClient.getRateLimiter("rate_limit:user:1")   ← Redis 中的分布式令牌桶
        ├─ #L45   rateLimiter.expire(Duration.ofHours(1))              ← 每次请求都刷新 TTL
        ├─ #L47   rateLimiter.trySetRate(OVERALL, 5, 60, SECONDS)      ← 仅在不存在时生效
        └─ #L49   rateLimiter.tryAcquire(1)
             └─ 第 1~5 次 → 返回 true  → 方法继续执行
             └─ 第 6 次   → 返回 false → #L50 抛 BusinessException(TOO_MANY_REQUEST, "AI 对话请求过于频繁，请稍后再试")

5. 异常冒泡到 GlobalExceptionHandler#businessExceptionHandler（#L25）
   ├─ #L29   handleSseError(42900, "AI 对话请求过于频繁，请稍后再试")
   │    └─ Accept 含 text/event-stream → 以 SSE error 事件写回，返回 true
   └─ #L30   return null  ← 阻止 Spring 再写 JSON body
```

**关键观察：第 4 步里 `#L83` 的 `getLoginUser` 已经查过一次数据库**。也就是说，一个被限流拒绝的请求，成本是「1 次 Redis 命令 + 1 次 MySQL 查询 + 1 次 Redisson 命令」。**限流没有起到「保护后端资源」的作用**——它自己就是一笔后端资源开销。

对照一下 `@AuthCheck` 的链路（以 `POST /api/app/admin/delete` 为例）：

```
1. Spring MVC 路由到 AppController#deleteAppByAdmin
2. AuthInterceptor#doInterceptor（@Around）
   ├─ #L36   mustRole = "admin"
   ├─ #L37   RequestContextHolder.currentRequestAttributes()
   ├─ #L38   ((ServletRequestAttributes) requestAttributes).getRequest()
   ├─ #L40   userService.getLoginUser(request)        ← 未登录则抛 40100
   │         └─ UserServiceImpl#L114  session 取 "user_login"
   │         └─ UserServiceImpl#L121  this.getById(userId)   ← 查 DB 拿最新角色
   ├─ #L41   mustRoleEnum = UserRoleEnum.getEnumByValue("admin") = ADMIN
   ├─ #L43   mustRoleEnum != null → 不跳过
   ├─ #L47   userRoleEnum = getEnumByValue(loginUser.getUserRole())
   ├─ #L49   若为 null → 抛 NO_AUTH_ERROR(40101)
   ├─ #L53   若 userRoleEnum != ADMIN → 抛 NO_AUTH_ERROR(40101)
   └─ #L57   joinPoint.proceed()  → 执行原方法，返回值原样透出
3. 原方法体执行（#L299-L307 的删除逻辑）
```

**注意 `#L53` 这行是「唯一的角色比对」**——如果 `mustRole` 写成 `"user"`，`mustRoleEnum = USER`，`UserRoleEnum.ADMIN.equals(USER)` 为 false，**整个 if 不成立，直接放行**。所以这个切面实际只实现了「是不是管理员」一个判断，并没有通用的角色匹配能力（详见 9.3.2）。

### 9.5 动手验证

#### 验证 1：限流真的生效（最快的验证方式）

`chatToGenCode` 的配额是 5 次/60 秒。**不用真的登录**——未登录会降级到 IP 限流，配额同样是 5 次/60 秒。所以可以直接用 curl 连打 7 次：

```bash
# Windows PowerShell
1..7 | ForEach-Object {
  $r = curl.exe -s -N "http://localhost:8123/api/app/chat/gen/code?appId=1&message=test" `
       -H "Accept: text/event-stream"
  Write-Host "=== 第 $_ 次 ==="
  Write-Host $r
}
```

预期：
- 第 1~5 次：**不会**出现 `请求过于频繁`，而是出现 `未登录`（因为确实没登录，业务侧 `getLoginUser` 会拦下来）或 SSE 错误事件；
- 第 6~7 次：出现 `AI 对话请求过于频繁，请稍后再试`。

**这里有个很能说明问题的细节**：未登录的请求会**先被限流放行 5 次**（因为 `RateLimitAspect` 里未登录是降级到 IP，不抛异常），然后才在业务代码里被 `getLoginUser` 拒绝。所以你会看到前 5 次的报错是「未登录」、第 6 次起变成「请求过于频繁」——**这正好实证了「未登录用户也占用 IP 配额」这个设计**。

#### 验证 2：确认限流 key 在 Redis 里的样子

```bash
redis-cli -n 0 --scan --pattern 'rate_limit:*'
```

预期：出现 `rate_limit:ip:0:0:0:0:0:0:0:1`（本机 IPv6 回环）或 `rate_limit:ip:127.0.0.1` 这类 key。

```bash
# 看它的 TTL（单位：秒）
redis-cli -n 0 ttl "rate_limit:ip:0:0:0:0:0:0:0:1"
```

预期：**接近 3600 秒（1 小时）**，而不是 60 秒。**这一点很重要**：限流窗口是 60 秒，但 key 的 TTL 是 1 小时——两者不是一回事。每打一次请求，这个 TTL 就会被重置回 3600。

```bash
# 看限流器的内部结构（RRateLimiter 用 Hash 存令牌桶状态）
redis-cli -n 0 hgetall "rate_limit:ip:0:0:0:0:0:0:0:1"
```

预期：能看到 Redisson 存储的令牌桶字段（`rate`、`interval`、`type`、`permits` 等）。

#### 验证 3：验证「`trySetRate` 只在不存在时生效」

这是 9.2.3 节④的实证，非常值得亲手做一遍：

```bash
# 1. 先删掉 key，让限流器重建
redis-cli -n 0 del "rate_limit:ip:127.0.0.1"

# 2. 把 AppController#L60 的 rate = 5 改成 rate = 2，重启应用

# 3. 连打 3 次，观察第几次被限流
```

预期：**第 3 次就被限流**（说明新参数生效了，因为 key 被删了）。

然后**不删 key**，把 `rate` 改回 5，再重启，再连打：

预期：**第 3 次依然被限流**（因为 Redis 里的限流器已存在，`trySetRate` 被跳过，仍是旧参数 2）。**这一步就是「改了配置不生效」的现场复现**，排查线上「限流参数改不动」问题时，第一件事就是确认 key 有没有被清掉。

#### 验证 4：验证 `X-Forwarded-For` 可以伪造（绕过 IP 限流）

```bash
# 每次带不同的伪造 IP，观察是否每次都能通过限流
1..8 | ForEach-Object {
  curl.exe -s -N "http://localhost:8123/api/app/chat/gen/code?appId=1&message=test" `
    -H "Accept: text/event-stream" `
    -H "X-Forwarded-For: 10.0.0.$_" | Select-String "频繁|未登录"
}
```

预期：**8 次都显示「未登录」而不是「请求过于频繁」**——因为每个伪造 IP 都命中了一个全新的限流 key，配额从零开始。**这实证了 9.2.5 节说的安全风险**：在没有可信代理重写请求头的情况下，IP 限流可以被一行请求头绕过。

```bash
# 反证：带上真实 IP 的头，观察 key 的变化
redis-cli -n 0 --scan --pattern 'rate_limit:ip:10.0.0.*'
```

预期：能看到 `rate_limit:ip:10.0.0.1` ~ `rate_limit:ip:10.0.0.8` 共 8 个 key，每个的计数都只有 1。

#### 验证 5：验证鉴权注解

```bash
# 未登录访问管理员接口
curl.exe -i -X POST "http://localhost:8123/api/app/admin/delete" `
  -H "Content-Type: application/json" `
  -d '{"id":1}'
```

预期：HTTP **200**，body 是 `{"code":40100,"message":"未登录","data":null}`。**注意状态码是 200 不是 401**（见 9.3.5）。

登录普通用户后再访问：

预期：body 变成 `{"code":40101,"message":"无权限","data":null}`——说明角色判断走到了 `#L53` 的拒绝分支。

用管理员账号访问：预期正常返回 `{"code":0,...}`。

#### 验证 6：验证密码哈希

```bash
# 直接查库，看密码存的是什么
mysql -uroot -p123456 yu_ai_code_mother -e "select id, userAccount, userPassword, userRole from user limit 5;"
```

预期：`userPassword` 是 **32 位十六进制字符串**（MD5 输出长度固定 32），例如 `e10adc3949ba59abbe56e057f20f883e`。

手动验证盐值：

```bash
# 用 MD5("12345678" + "yupi") 算一遍，看是否和库里一致
echo -n "12345678yupi" | md5sum
```

预期：算出来的哈希与 `UserController#L101-L103` 新建用户（默认密码 `12345678`）后库里的值**完全一致**。**这实证了两件事**：① 盐值是固定的 `"yupi"`；② 同一个密码在库里哈希值相同（没有 per-user 盐）——也就是 9.3.4 节说的安全隐患。

#### 验证 7：验证 Session 存在 Redis 里

```bash
# 看 session key（Spring Session 默认前缀 spring:session）
redis-cli -n 0 --scan --pattern 'spring:session:*'
```

预期：登录后能看到 `spring:session:sessions:{sessionId}` 之类的 key。

```bash
redis-cli -n 0 type "spring:session:sessions:<sessionId>"
redis-cli -n 0 hgetall "spring:session:sessions:<sessionId>"
```

预期：类型是 `hash`，字段里包含 `sessionAttr:user_login`——**它的值就是被序列化的完整 `User` 对象**（含 `userPassword`）。这一步实证了 9.3.4 节说的「密码哈希被写进了 Redis」。

```bash
# 看 Session 过期时间（应为 30 天左右 = 2592000 秒）
redis-cli -n 0 ttl "spring:session:sessions:<sessionId>"
```

#### 验证 8：确认 Redisson 没有分布式锁

```bash
# 在代码里搜一遍（应该只有限流相关命中）
```

```
Grep  "RLock|getLock|getFairLock|tryLock"  → 0 命中
```

预期：**零命中**，确认项目「未使用 Redisson 分布式锁」，Redisson 的唯一用途是 `RRateLimiter`。

### 9.6 自测题

<details>
<summary>展开查看题目（共 13 题）</summary>

**Q1.** 限流切面用的是 `@Before`，鉴权切面用的是 `@Around`，为什么两者不一样？

**Q2.** `RateType.OVERALL` 和 `RateType.PER_CLIENT` 有什么区别？项目选了哪个，为什么这个选择决定了它是不是「真正的分布式限流」？

**Q3.** `trySetRate` 和 `setRate` 有什么区别？线上把限流参数从 5 改成 10 后重启，为什么不生效？

**Q4.** `rateLimiter.expire(Duration.ofHours(1))` 写在 `doBefore` 里，每次请求都会执行，这会导致什么后果？它和「60 秒的限流窗口」是同一个概念吗？

**Q5.** `@RateLimit(limitType = RateLimitType.USER)` 时，如果用户没登录，限流是怎么处理的？这样设计有什么好处、什么副作用？

**Q6.** 限流切面里调 `userService.getLoginUser(request)` 会带来什么性能代价？为什么说「限流本身没有起到保护后端资源的作用」？

**Q7.** `getClientIP()` 读了 `X-Forwarded-For`，这有什么安全风险？攻击者怎么绕过 IP 限流？

**Q8.** `AuthInterceptor` 这个名字有什么误导性？它和 Spring MVC 的 `HandlerInterceptor` 是什么关系？

**Q9.** `@AuthCheck(mustRole = "vip")` 而 `UserRoleEnum` 里没有 `vip`，会发生什么？为什么这是个危险的设计？

**Q10.** 项目里「非管理员接口」的权限校验（比如「只能修改自己的应用」）是怎么做的？为什么在 `WebMvcConfig` 里找不到 `AuthInterceptor` 的注册代码？

**Q11.** 限流被触发时，HTTP 状态码是多少？前端应该怎么判断「被限流了」？

**Q12.** `UserServiceImpl#getLoginUser` 拿到 Session 里的 userId 后为什么还要再查一次数据库？这带来什么好处和代价？

**Q13（陷阱）.** 项目引入了 Redisson，是不是说明它做了分布式锁来防止并发重复生成代码？

**Q14（陷阱）.** 项目用 Session 存登录态，是不是说明它「没有做分布式登录」，多实例部署会登录失效？

**Q15（陷阱）.** `getEncryptPassword` 里加了盐值 `"yupi"`，是不是说明这个密码存储方案是安全的？

</details>

<details>
<summary>展开查看答案与解析</summary>

**A1.** 因为两者的「拦截后是否需要放行」需求不同。**限流只需要「拦」**——不通过就抛异常中断，用 `@Before` 最简洁；**鉴权需要「拦 + 放行并透传返回值」**——`@Around` 里显式 `joinPoint.proceed()` 才能拿到原方法的返回值并返回给调用方（`AuthInterceptor#L44`、`#L57` 两处 `proceed()`）。如果用 `@Before`，即使不抛异常放行，也**拿不到返回值**，原方法的返回会被置空。

**A2.** `OVERALL` = 所有 Redisson 客户端**共享同一个令牌桶**（集群维度）；`PER_CLIENT` = 每个客户端**各自**维护令牌桶（实例维度）。项目选了 **`OVERALL`**（`RateLimitAspect#L47`）。这是关键：**只有 `OVERALL` 才是真正的分布式限流**——3 个实例部署时总配额仍是 5 次/60 秒；如果选 `PER_CLIENT`，实际放行会变成 15 次，配额被放大 N 倍，退化成单机限流。

**A3.** **`trySetRate` 是「仅当限流器不存在时才设置」**，已存在则静默忽略；`setRate` 才是强制覆盖。所以线上把 `rate` 从 5 改成 10 后重启，**如果 Redis 里那个 key 还在，新参数不会生效**，仍然是 5。必须等 key 过期（这里 TTL 是 1 小时，且每次请求都会被刷新，所以活跃用户永远不过期）或手工 `redis-cli del`。**排查「限流参数改不动」时，第一步就是确认 key 有没有被清掉。**

**A4.** 后果：**每一次请求都把 TTL 重置为 1 小时**，所以长期活跃用户的限流 key **永不过期**。而且 TTL（1 小时）和限流窗口（60 秒）**是两个完全不同的概念**——TTL 是 Redis key 的存活时间，限流窗口是令牌桶的补充周期。这行代码本意是「清理垃圾 key」，实际效果是「只清理了本来就不占资源的冷 key」，**意图与效果错位**。

**A5.** 未登录时**降级到 IP 限流**（`RateLimitAspect#L87` 或 `#L91`），配额相同（5 次/60 秒）。**好处**：不会出现「不登录就没有限流」的漏洞，匿名用户也能被限住。**副作用**：同一个 NAT 出口（公司、学校、公共 WiFi）后面的所有未登录用户**共享同一份配额**，会误伤——一个人用完了，同 IP 的其他人都被拒。这也是很多平台强制登录后才给用 AI 能力的原因。

**A6.** 代价：`getLoginUser` 内部会 `this.getById(userId)` **查一次 MySQL**（`UserServiceImpl#L121`）。所以每个被限流的请求成本是「1 次 Redis（Session）+ 1 次 MySQL（用户）+ 1 次 Redis（Redisson 限流）」。**限流本该是廉价的内存/Redis 操作**，这里却带上了 DB 开销，等于「限流自己就是一笔要保护的开销」——顺序错了：限流应该在最前面用最少的信息（如 Session 里的 userId）就把请求挡掉，而不是先去查库。

**A7.** `X-Forwarded-For` / `X-Real-IP` **是客户端可以随意伪造的请求头**，而 `getClientIP()` 无条件信任它们（`RateLimitAspect#L115-L118`）。攻击者只需每次请求带一个不同的 `X-Forwarded-For: 10.0.0.N`，就能让每次请求命中**全新的限流 key**，配额从零开始，**IP 限流完全失效**。正确做法是只在「请求确定来自可信代理」时才信任这些头（用 `ForwardedHeaderFilter` 或 `server.forward-headers-strategy`），或者把 IP 限流统一收到 Nginx 层做。

**A8.** 误导性在于**它根本不叫 `HandlerInterceptor` 的实现类**。它没有任何 `implements HandlerInterceptor`，也没有注册到 `WebMvcConfigurer#addInterceptors`，而是靠 `@Aspect` + `@Around("@annotation(authCheck)")` 实现的（`AuthInterceptor#L19-L21`、`#L34`）。所以**在 `WebMvcConfig` 里找它的注册代码是找不到的**——「拦截器」这个名字让人以为它是 MVC 层的，实际它是 AOP 层的。

**A9.** `UserRoleEnum.getEnumByValue("vip")` 返回 **null** → `mustRoleEnum == null` → `AuthInterceptor#L43-L45` **直接 `proceed()` 放行**。后果：**注解写错一个字母，鉴权就静默失效，任何登录用户都能通过**，而且编译不报错、启动不报错、日志不报错。危险之处在于「**失败方向反了**」——安全的默认行为应该是「解析不出角色就拒绝（fail-closed）」，而这里做成了「解析不出就放行（fail-open）」。

**A10.** 分两层：**注解层管「管理员权限」**（`@AuthCheck(mustRole = ADMIN_ROLE)`，11 处），**业务代码层管「数据归属」**（如 `AppServiceImpl#chatToGenCode#L90-L92` 的 `if (!app.getUserId().equals(loginUser.getId()))` 手工判断）。所以「没打 `@AuthCheck` 的接口」不等于「没鉴权」，可能是在方法体里手工判的。**找不到注册代码的原因见 A8**——`AuthInterceptor` 是 AOP 切面，不是 MVC 拦截器。

**A11.** HTTP 状态码是 **200**。限流抛的 `BusinessException(TOO_MANY_REQUEST)` 被 `GlobalExceptionHandler#businessExceptionHandler` 处理（`#L25-L34`），返回的是包装体 `{"code":42900,"message":"...","data":null}`，**HTTP 层仍是 200**。所以前端**必须解析 body 里的 `code` 字段**（或 `message`），不能靠 `response.status === 429` 判断。对 SSE 接口，`handleSseError` 会把它转成 SSE 的 error 事件写回（`#L29-L31`）。

**A12.** 好处：**「Session 只当凭证，数据以数据库为准」**——管理员改了用户角色，用户**下一次请求就立即生效**，不用等 Session 过期（否则要等 30 天）。代价：**每次鉴权多一次 DB 查询**，而鉴权发生在每个受保护接口上，还额外发生在限流切面里（A6），所以这个开销会被放大。

**A13.** **不是。** Grep `RLock` / `getLock` / `tryLock` / `getFairLock` 在全项目**零命中**。Redisson 在本项目里**唯一用途是 `RRateLimiter` 分布式限流**（`RedissonConfig#L27` 创建的 `RedissonClient` 只被 `RateLimitAspect#L44` 用到）。**项目未使用 Redisson 分布式锁**——不要看到 Redisson 就默认有分布式锁。

**A14.** **恰恰相反，`store-type: redis` 正是为了分布式登录。** 用的是 **Spring Session + Redis**（`application.yml#L4-L8`、`pom.xml#L42-L45`），Session 数据存在 Redis 里，**多实例部署时所有实例共享同一份 Session**，用户在 A 实例登录、请求打到 B 实例依然能识别——这正是分布式 Session 的标准解法。**「没用 JWT」不等于「没做分布式登录」**，Session 集中存储和 JWT 无状态是两条不同的路。

**A15.** **不是，这个方案是不安全的。** 三个问题：**① 算法是 MD5**，已被证明不适合存密码（GPU 暴力破解极快）；**② 盐值是全局固定且硬编码在代码里的常量 `"yupi"`**（`UserServiceImpl#L184`），同一个密码在所有用户那里的哈希值完全相同，彩虹表可以预计算，盐值等于没加；**③ 没有计算成本参数**，无法通过调大 work factor 抵抗算力增长。正确做法是 **BCrypt / Argon2 这类自适应哈希 + 每个用户一个随机盐**（盐值存在用户行里）。本项目是教学取舍，**绝不能用于生产**。

</details>

### 9.7 本环节技术点清单

| 技术点 | 落地位置 | 作用 | 项目特有之处 |
|---|---|---|---|
| 自定义限流注解 | `ratelimter/annotation/RateLimit.java#L10-L37` | 声明限流意图 | `@Target(METHOD)` + `@Retention(RUNTIME)`；5 个属性，**默认 `limitType = USER`** |
| 限流维度枚举 | `ratelimter/enums/RateLimitType.java#L3-L19` | API / USER / IP 三档 | 只有 3 个值，实际只用到 `USER` |
| 限流切面 | `ratelimter/aspect/RateLimitAspect.java#L29-L52` | 前置拦截 | 用 **`@Before`**（只需「拦」不需放行）；切点 `@annotation(rateLimit)` 的**参数名必须与方法参数同名** |
| 分布式令牌桶 | `RateLimitAspect#L44-L47` | Redisson `RRateLimiter` | **`RateType.OVERALL`** = 集群共享配额（真正的分布式限流）；`PER_CLIENT` 会退化成单机 |
| `trySetRate` 语义 | `RateLimitAspect#L47` | 设置限流参数 | **仅在 key 不存在时生效**，改参数重启不生效，必须先删 key |
| TTL 刷新 | `RateLimitAspect#L45` | 限流 key 过期 | `expire(1h)` **写在每次请求里** → TTL 不断重置，活跃 key 永不过期；且 TTL(1h) ≠ 限流窗口(60s) |
| 限流 key 生成 | `RateLimitAspect#L61-L102` | 拼 `rate_limit:...` | `rate_limit:` + 可选自定义前缀 + `user:{id}` / `ip:{ip}` / `api:{类.方法}` |
| 未登录降级 | `RateLimitAspect#L79-L92` | 匿名用户也能限流 | 三级降级（无上下文→IP、`BusinessException`→IP、正常→userId）；**副作用：NAT 同 IP 用户共享配额** |
| IP 获取 | `RateLimitAspect#L109-L127` | 取客户端 IP | `X-Forwarded-For` → `X-Real-IP` → `getRemoteAddr()`，多级代理取第一段；**未校验可信代理 → 可伪造绕过** |
| 限流只用于一处 | `controller/AppController.java#L59-L60` | AI 对话限流 5 次/60 秒 | **全项目唯一一处 `@RateLimit`**；`/app/add`、`/app/deploy` 等重接口**均无限流** |
| Redisson 客户端 | `ratelimter/config/RedissonConfig.java#L11-L46` | 手工装配 `RedissonClient` | 用**裸 `redisson` 依赖**（非 starter）→ 必须手写 `Config`；只配 `useSingleServer()`，**不支持集群/哨兵** |
| 配置复用 | `RedissonConfig#L14-L24` | 读 Redis 连接信息 | 用 4 个 `@Value` 复用 `spring.data.redis.*`，**靠人肉约定**，新增配置项要手工同步 |
| 地址拼接 | `RedissonConfig#L29` | `redis://host:port` | **必须带协议前缀**，漏了抛 `IllegalArgumentException`（同环节 7 的 COS host 坑） |
| Redisson 未用于锁 | 全项目 | — | **`RLock` / `getLock` / `tryLock` 零使用**，Redisson 仅用于限流 |
| 鉴权注解 | `annotation/AuthCheck.java#L8-L17` | 声明角色要求 | 只有 `mustRole` 一个属性（String 类型，默认 `""`） |
| 鉴权切面 | `aop/AuthInterceptor.java#L19-L58` | 环绕拦截 | **用 `@Around`**（要放行并透传返回值）；**类名有误导性，它不是 `HandlerInterceptor`** |
| 鉴权隐含登录 | `AuthInterceptor#L40` | 未登录拦截 | `getLoginUser` 在角色判断**之前**执行 → 打注解即隐含「必须登录」 |
| 角色判断漏洞 | `AuthInterceptor#L43-L45`、`#L53` | 管理员校验 | 只实现了「是不是 ADMIN」；**`mustRole` 解析不出枚举时静默放行（fail-open）** |
| 注解使用范围 | 11 处（`UserController` 5 处、`AppController` 4 处、`ChatHistoryController` 1 处等） | 管理员接口 | **全部是 `ADMIN_ROLE`**；非管理员接口的权限靠业务代码手工判断 |
| 分布式 Session | `application.yml#L4-L8` + `pom.xml#L42-L45` | 登录态存储 | `spring-session-data-redis`；`timeout` 与 `cookie.max-age` **都是 2592000（30 天），必须对齐** |
| 登录态写入 | `UserServiceImpl#L105-L108` | `session.setAttribute("user_login", user)` | **存的是完整 `User` 实体，含 `userPassword`** → 密码哈希进了 Redis |
| 登录态读取 | `UserServiceImpl#L111-L126` | 核心鉴权方法 | 「Session 只当凭证」→ **每次都 `getById` 回源查库**；两层 null 检查（未登录 / 用户被删） |
| 密码哈希 | `UserServiceImpl#L181-L186` | `MD5(password + "yupi")` | 用 Spring `DigestUtils`；**MD5 + 全局硬编码盐，不安全**，生产必须换 BCrypt |
| 登录密码比对 | `UserServiceImpl#L98-L101` | SQL 里比对哈希 | `queryWrapper.eq("userPassword", encryptPassword)`，在 WHERE 条件里比 |
| 限流异常码 | `exception/ErrorCode.java#L12` | `TOO_MANY_REQUEST(42900)` | **HTTP 状态码仍是 200**，前端必须解析 body 的 `code` |
| 异常响应分流 | `GlobalExceptionHandler#L25-L34`、`#L53` | JSON / SSE 两路 | 靠 `Accept` 头含 `text/event-stream` 判断，是限流能对 SSE 生效的关键 |
| 切面顺序未定义 | 两个切面均无 `@Order` | — | 同时打两个注解时执行顺序不确定；**限流通常应在最外层** |
| 未引入的鉴权框架 | pom.xml | — | **无 Spring Security / Sa-Token / JWT**（`jjwt`、`java-jwt` 等零依赖） |

### 9.8 本环节产出

这个环节是「**给烧钱的接口装两道闸门**」，选型可以概括为：**注解声明意图，AOP 切面执行逻辑，Redisson 令牌桶做分布式限流，Spring Session + Redis 做分布式登录态。** 限流侧的技术底座是 Redisson 的 `RRateLimiter`——它把令牌桶的状态放在 Redis 里，配 `RateType.OVERALL` 让**集群内所有实例共享同一份配额**，这是「分布式限流」与「单机限流」的分水岭；`trySetRate`（仅在不存在时设置）+ `expire`（TTL）这两个 API 的语义差异是排查「参数改不动」时的关键。鉴权侧的底座是「注解 + AOP + Session」，`@AuthCheck` 用 `@Around` 因为要放行并透传返回值，`@RateLimit` 用 `@Before` 因为只需拦截，这个选择差异体现的是「**是否需要控制方法执行**」这个判断标准。最容易踩的坑有五个：**一是 `trySetRate` 只在 key 不存在时生效**，改了 rate 参数重启不生效，必须先删 Redis key；**二是 `getClientIP()` 无条件信任 `X-Forwarded-For`**，攻击者每次换个伪造 IP 就能绕过 IP 限流；**三是 `@AuthCheck` 的角色解析失败时是「静默放行」而不是「拒绝」**（fail-open），注解写错一个字母权限就没了；**四是限流切面里调 `getLoginUser` 会额外查一次 MySQL**，让限流本身变成了需要被保护的开销；**五是密码用 MD5 + 全局硬编码盐值 `"yupi"`**，同一个密码在库里哈希值相同，完全不能用于生产。另外三个「容易看错」的细节也要记住：`AuthInterceptor` **不是** `HandlerInterceptor`（是 AOP 切面，去 `WebMvcConfig` 里找不到）；限流触发时 **HTTP 状态码是 200** 而不是 429（要解析 body 的 `code`）；项目**没有**用 Redisson 做分布式锁（`RLock` 零使用）。读到这里应该能回答：**「为什么一个用户不可能刷爆平台的 AI 额度，以及管理员接口是怎么被保护的」**——答案是：`AppController#L60` 上的 `@RateLimit(limitType = USER, rate = 5, rateInterval = 60)` 触发 `RateLimitAspect#doBefore`，它先拼出限流 key（已登录用 `rate_limit:user:{userId}`，未登录降级成 `rate_limit:ip:{ip}`），再用 `redissonClient.getRateLimiter(key)` 拿到 Redis 里的分布式令牌桶，`trySetRate(OVERALL, 5, 60, SECONDS)` 设置参数、`tryAcquire(1)` 抢令牌，抢不到就抛 `TOO_MANY_REQUEST(42900)`，经 `GlobalExceptionHandler` 分流成 SSE error 事件或 JSON；而管理员接口靠 `@AuthCheck(mustRole = UserConstant.ADMIN_ROLE)` 触发 `AuthInterceptor#doInterceptor`，它先用 `userService.getLoginUser(request)` 从 Redis Session 拿 userId **并回源查库拿最新角色**，再和 `UserRoleEnum.ADMIN` 比对，不通过就抛 `NO_AUTH_ERROR(40101)`；登录态本身由 Spring Session 写进 Redis（30 天过期），密码用 `MD5(明文 + "yupi")` 存储——**这套方案能跑通，但密码存储、IP 伪造、fail-open 鉴权这三处必须改造后才能上生产**。

---

## 环节 10：LangGraph4j 工作流编排

### 10.1 这个环节解决什么问题

环节 1~6 讲的主链路是「**一条龙**」：`AppServiceImpl#chatToGenCode` 直接调 `AiCodeGeneratorFacade`，把「路由 → 生成 → 解析落盘」串成一次方法调用。这条链路能跑，但它把「业务流程」写死在了 Java 代码里：

- 步骤是**顺序写死**的（先路由、再生成、再落盘），想在中间插一步「质检」就得改 `AiCodeGeneratorFacade`；
- 步骤之间的**数据传递靠方法参数**，一步产出多了就得不断加参数；
- **没有可视化**，想知道「这个需求走了哪几步」只能翻日志。

`langgraph4j` 包就是用来回答「**能不能把流程本身声明成一张图**」这个问题的：节点（Node）是执行单元，边（Edge）是流转方向，状态（State）是节点之间共享的数据袋，条件边（Conditional Edge）是运行时分支。图编译后可以导出 Mermaid 文本、可以流式输出每一步的中间结果。

**但在读这个包之前必须先记住一个结论**：`langgraph4j` 整包**没有接入主业务链路**。

验证方式（可自己跑一遍）：

```
Grep "langgraph4j" 全项目 src/main/java
```

结果只有两处命中，且都在同一个文件里：

- `controller/WorkflowSseController.java` 的 import（`CodeGenWorkflow`、`WorkflowContext`）。

也就是说，`langgraph4j` 包里那 26 个类（3 张工作流 + 11 个节点 + 4 个工具 + 3 个 AI 服务 + 状态/模型类），**唯一的对外出口是 `WorkflowSseController` 的三个演示接口**。用户在平台上点「生成应用」，走的是环节 2 的 `AiCodeGeneratorFacade`，一步都不会经过 `CodeGenWorkflow`。

所以本环节的定位是「**读一个已完成但未上线的能力模块**」——它的价值在于演示「工作流编排怎么写」，以及「如果要把主链路改成编排式，需要动哪些地方」。

---

### 10.2 技术点一：LangGraph4j 图模型（状态、节点、边、条件边）

#### 10.2.1 依赖与版本

`pom.xml#L73-L84` 引入了两个依赖，**都显式写了 version**（不在 Spring Boot BOM 管理范围内）：

| 依赖 | 版本 | 作用 |
|---|---|---|
| `org.bsc.langgraph4j:langgraph4j-core` | `1.6.0-rc2` | 图模型本体（`StateGraph`、`CompiledGraph` 等） |
| `org.bsc.langgraph4j:langgraph4j-studio-springboot` | `1.6.0-rc2` | LangGraph Studio 可视化调试的 Spring Boot 适配 |

注意 `1.6.0-rc2` 是 **RC 版本**（Release Candidate），不是正式版。生产项目用 RC 版依赖要评估升级风险。

#### 10.2.2 状态：`WorkflowContext` 与 `MessagesState<String>`

图的泛型参数是 `MessagesState<String>`（见 `CodeGenWorkflow#createWorkflow` 的 `new MessagesStateGraph<String>()`）。`MessagesState` 是 langgraph4j 的**预置状态类型**（`org.bsc.langgraph4j.prebuilt.MessagesState`），内部就是一个 `Map<String, Object>` 数据袋。

项目自己定义的状态类是 [WorkflowContext.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/state/WorkflowContext.java)（113 行）：

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowContext implements Serializable {

    public static final String WORKFLOW_CONTEXT_KEY = "workflowContext";

    private String currentStep;              // 当前步骤名（用于前端展示进度）
    private String originalPrompt;           // 用户原始需求
    private String imageListStr;             // 图片列表（JSON 字符串形态）
    private List<ImageResource> imageList;   // 图片列表（对象形态）
    private String enhancedPrompt;           // 增强后的提示词
    private CodeGenTypeEnum generationType;  // 路由出的生成类型
    private String generatedCodeDir;         // 代码落盘目录
    private String buildResultDir;           // 构建产物目录
    private QualityResult qualityResult;     // 质检结果
    private String errorMessage;             // 错误信息
    private ImageCollectionPlan imageCollectionPlan;  // 图片收集计划

    // 并发中间字段：4 类图片各自独立存放，最后聚合
    private List<ImageResource> contentImages;
    private List<ImageResource> illustrations;
    private List<ImageResource> diagrams;
    private List<ImageResource> logos;
}
```

**关键设计：状态怎么进出图。** `WorkflowContext` **不是**图的状态类型，它被**整体塞进** `MessagesState` 的一个 key 里。两个静态方法就是这个塞/取的约定（`WorkflowContext#L103-L112`）：

```java
static WorkflowContext getContext(MessagesState<String> state) {
    return (WorkflowContext) state.data().get(WORKFLOW_CONTEXT_KEY);
}

static Map<String, Object> saveContext(WorkflowContext context) {
    return Map.of(WORKFLOW_CONTEXT_KEY, context);
}
```

于是每个节点的写法都固定成三段式：

```java
// 以 RouterNode 为例
node_async(state -> {
    WorkflowContext context = WorkflowContext.getContext(state);  // 1. 取
    // 2. 改
    context.setGenerationType(type);
    context.setCurrentStep("智能路由");
    return WorkflowContext.saveContext(context);                   // 3. 存回去
})
```

**项目特有之处 / 坑**：

1. **节点里改的是同一个对象引用**。`getContext` 返回的是堆上的 `WorkflowContext` 实例，节点直接 `setXxx` 修改它，再原样存回。也就是说「状态是不可变传递」这个图框架的常规假设在这里**不成立**——节点之间共享的是可变对象。这在**串行**图里没问题（下一节点读到的就是上一节点改完的），但在**并发**图里就有隐患：多个节点同时 `set` 同一个 `context` 的不同字段，属于「共享可变状态 + 并发写」，见 10.3.2。
2. **`WorkflowContext` 实现了 `Serializable`**，但它被塞在 `Map.of(...)` 里。`Map.of` 返回的是**不可变 Map**，节点想直接改这个 Map 会抛 `UnsupportedOperationException`——所以必须「取出对象改字段，再重新 `saveContext`」，不能指望改 Map 本身。
3. **`currentStep` 字段是给前端看进度的**。每个节点最后都会 `setCurrentStep("xxx")`，配合 SSE 的 `step_completed` 事件，前端就能显示「图片收集 → 提示词增强 → 智能路由 → …」的进度条。

#### 10.2.3 节点与边：`CodeGenWorkflow` 的图结构

[CodeGenWorkflow.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/CodeGenWorkflow.java) 的 `createWorkflow()`（`#L36-L68`）建了 6 个节点：

| 节点名 | 节点类 | 干什么 |
|---|---|---|
| `image_collector` | `ImageCollectorNode` | 用 AI 规划要收集哪些图片，然后并发去搜/生成 |
| `prompt_enhancer` | `PromptEnhancerNode` | 把收集到的图片素材拼进用户提示词 |
| `router` | `RouterNode` | 判断该用 HTML / MULTI_FILE / VUE_PROJECT |
| `code_generator` | `CodeGeneratorNode` | 真正调 AI 生成代码并落盘 |
| `code_quality_check` | `CodeQualityCheckNode` | 读回生成的代码交给 AI 质检 |
| `project_builder` | `ProjectBuilderNode` | Vue 工程构建（`npm run build`） |

边（`#L49-L62`）：

```
START → image_collector → prompt_enhancer → router → code_generator → code_quality_check
                                                                          │
                        ┌─────────────────────────────────────────────────┤（条件边）
                        ▼                     ▼                           ▼
                   project_builder          END                    code_generator
                    （build）           （skip_build）                  （fail）
                        │
                        ▼
                       END
```

条件边是这段代码里最值得看的部分（`#L54-L60`）：

```java
.addConditionalEdges(
    "code_quality_check",
    edge_async(this::routeAfterQualityCheck),   // 路由函数
    Map.of(
        "build", "project_builder",
        "skip_build", END,
        "fail", "code_generator"                // ← 回边，重生成
    )
)
```

`routeAfterQualityCheck`（`#L235-L246`）的判断逻辑：

```java
private String routeAfterQualityCheck(MessagesState<String> state) {
    WorkflowContext context = WorkflowContext.getContext(state);
    QualityResult qualityResult = context.getQualityResult();
    if (qualityResult == null || !qualityResult.getIsValid()) {
        return "fail";       // 质检没过 → 回 code_generator 重新生成
    }
    return routeBuildOrSkip(context);
}
```

`routeBuildOrSkip`（`#L254-L263`）：HTML / MULTI_FILE 模式不需要构建，返回 `"skip_build"` 直接结束；只有 `VUE_PROJECT` 返回 `"build"`。

**这里有两个必须点出的问题：**

1. **`fail → code_generator` 这条回边没有重试次数上限。** 如果 AI 生成的代码反复质检不过（比如 prompt 与质检规则天然冲突），图会无限循环：生成 → 质检 → 失败 → 生成……直到 `CodeGeneratorNode#L39` 的 `codeStream.blockLast(Duration.ofMinutes(10))` 每次卡 10 分钟。**图上没有任何计数器或最大轮次限制**，这是编排式流程的典型缺陷——把控制流交给图之后，「循环退出条件」必须自己设计。
2. **`code_quality_check` 异常时走的是「放行」而不是「失败」**，见 10.4.3。

#### 10.2.4 节点不是 Spring Bean，靠 `SpringContextUtil` 拿 Bean

`CodeGenWorkflow` 类上**没有 `@Component`**（类声明只有 `@Slf4j`），`WorkflowSseController#L24` 里是 `new CodeGenWorkflow().executeWorkflow(prompt)` —— **每次请求 new 一个**。

节点类同样不是 Bean，所以节点里想用 Spring 容器里的服务（比如 `AiCodeGeneratorFacade`、`VueProjectBuilder`），只能走 `SpringContextUtil.getBean(...)` 手工取。例如：

- `RouterNode#L27`：`SpringContextUtil.getBean(AiCodeGenTypeRoutingService.class)`
- `CodeGeneratorNode#L32`：`SpringContextUtil.getBean(AiCodeGeneratorFacade.class)`
- `CodeQualityCheckNode#L43-L44`：`SpringContextUtil.getBean(CodeQualityCheckService.class)`
- `ProjectBuilderNode#L34`：`SpringContextUtil.getBean(VueProjectBuilder.class)`
- `ImageCollectorNode#L36-L37`：`SpringContextUtil.getBean(ImageCollectionPlanService.class)`

**这是「框架无关的图定义」和「Spring 依赖注入」之间的妥协**。节点写成静态工厂方法（`node_async(state -> ...)`）就没法用构造器注入，只能服务定位器（Service Locator）模式兜底。副作用是：**节点无法被单元测试直接注入 Mock**，必须先把 `SpringContextUtil` 的静态 `ApplicationContext` 初始化好。

顺带说明：那 4 个图片工具类（`ImageSearchTool` 等）**是 `@Component`**，也用了 `@Tool`/`@P` 注解，但在工作流里它们**是被直接 Java 调用的**（`SpringContextUtil.getBean(...)` 拿到实例后直接调方法），**`@Tool` 注解在这里没有起任何作用**——`@Tool` 是给 LangChain4j 的 AI 服务做「自动工具调用」用的，见环节 3。这里的复用只是「把同一批能力顺便用 Java 调一遍」。

---

### 10.3 技术点二：并发编排的两种实现与三种执行入口

`langgraph4j` 包里其实有**三张图**，用来演示「同一个流程的三种编排方式」：

| 类 | 节点数 | 图片收集的并发方式 | 特点 |
|---|---|---|---|
| `CodeGenWorkflow` | 6 | **节点内并发**：`ImageCollectorNode` 里用 `CompletableFuture.supplyAsync` | 最简单，并发藏在节点实现里 |
| `CodeGenConcurrentWorkflow` | 11 | **图级并发**：图片规划节点扇出 4 条边到 4 个收集节点 | 并发由图的拓扑表达，配独立线程池 |
| `CodeGenSubgraphWorkflow` | 11 | **子图并发**：4 个收集节点各自是一个未编译的子图 | 演示 langgraph4j 的子图特性 |

#### 10.3.1 方式一：节点内并发（`ImageCollectorNode`）

[ImageCollectorNode.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/node/ImageCollectorNode.java) 的做法是：

```java
// L36-L37：先让 AI 规划出「要收集哪些图片」
ImageCollectionPlan plan = SpringContextUtil.getBean(ImageCollectionPlanService.class)
        .planImageCollection(originalPrompt);

// L41-L73：4 类收集任务并发执行
CompletableFuture<List<ImageResource>> contentFuture = CompletableFuture.supplyAsync(
        () -> imageSearchTool.searchContentImages(...));
CompletableFuture<List<ImageResource>> illustrationFuture = CompletableFuture.supplyAsync(
        () -> undrawTool.searchIllustrations(...));
CompletableFuture<List<ImageResource>> diagramFuture = CompletableFuture.supplyAsync(
        () -> mermaidTool.generateMermaidDiagram(...));
CompletableFuture<List<ImageResource>> logoFuture = CompletableFuture.supplyAsync(
        () -> logoGeneratorTool.generateLogos(...));

// L76-L78：等全部完成
CompletableFuture.allOf(contentFuture, illustrationFuture, diagramFuture, logoFuture).join();
```

**坑**：`CompletableFuture.supplyAsync(...)` **不传 Executor 时用的是 `ForkJoinPool.commonPool()`**，也就是「CPU 核数 - 1」个线程的公共池。这 4 个任务里 3 个是**网络 IO**（Pexels 搜索、Undraw 搜索、DashScope 生图），只有 1 个是本地进程调用（mmdc）。用 `commonPool` 跑 IO 任务是反模式：

- 公共池线程数很少，一旦被 IO 阻塞住，**同 JVM 内其他用 `commonPool` 的代码（包括并行 Stream）会一起饿死**；
- 项目在别处已经大量使用 JDK 21 虚拟线程（`Thread.startVirtualThread`），这里却退回了平台线程池，**风格不统一**。

另外 `#L87-L89` 用 `catch (Exception e)` 把异常吞掉只打日志，`#L91-L93` 无条件 `setImageList(collectedImages)` —— 某类图片全失败时流程照样往下走。

#### 10.3.2 方式二：图级并发（`CodeGenConcurrentWorkflow`）

[CodeGenConcurrentWorkflow.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/CodeGenConcurrentWorkflow.java) 把「一个节点内并发」拆成「**一张图里并发**」：把图片收集拆成 5 个节点（`image_plan` 规划 + `content_image_collector` / `illustration_collector` / `diagram_collector` / `logo_collector` 四路收集 + `image_aggregator` 聚合）。

拓扑（`#L56-L65`）：

```
image_plan ──┬──► content_image_collector ──┐
             ├──► illustration_collector  ──┤
             ├──► diagram_collector       ──┼──► image_aggregator ──► prompt_enhancer ──► …
             └──► logo_collector          ──┘
```

一个节点 `addEdge` 到 4 个节点 = 扇出；4 个节点都 `addEdge` 到 `image_aggregator` = 汇聚（langgraph4j 会等 4 条边都到达才触发聚合节点）。

**关键配置**：并发节点需要一个线程池，通过 `RunnableConfig` 指定（`#L103-L111`）：

```java
ExecutorService pool = ExecutorBuilder.create()
        .setCorePoolSize(10)
        .setMaxPoolSize(20)
        .setWorkQueue(new LinkedBlockingQueue<>(100))
        .setThreadFactory(ThreadFactoryBuilder.create()
                .setNamePrefix("Parallel-Image-Collect")
                .build())
        .build();

RunnableConfig runnableConfig = RunnableConfig.builder()
        .addParallelNodeExecutor("image_plan", pool)   // ← 指定 image_plan 的并发执行器
        .build();

workflow.stream(Map.of(WORKFLOW_CONTEXT_KEY, initialContext), runnableConfig);
```

注意 `addParallelNodeExecutor` 的参数是**扇出源节点名**（`image_plan`），不是目标节点。线程池用 Hutool 的 `ExecutorBuilder` + `ThreadFactoryBuilder` 建，线程名前缀 `Parallel-Image-Collect` 方便在 jstack / 日志里定位。

**坑：这个线程池没有关闭。** 代码里建了 `pool` 却没有对应的 `shutdown()`，也没有做成 Spring Bean 交给容器管理生命周期。作为演示代码没问题，接入生产会泄漏线程。

**回到 10.2.2 提到的隐患**：并发节点共享同一个 `WorkflowContext` 实例。`ImagePlanNode` 写 `imageCollectionPlan`，4 个收集节点分别写 `contentImages` / `illustrations` / `diagrams` / `logos`（**4 个独立字段，刻意错开**），`ImageAggregatorNode` 再读这 4 个字段聚合成 `imageList`。**字段不重叠是这里能跑对的前提**——如果两个并发节点写同一个字段，就会丢更新。这是「共享可变状态」编排方式必须自己守住的纪律，框架不会帮你检查。

#### 10.3.3 方式三：子图（`CodeGenSubgraphWorkflow`）

[CodeGenSubgraphWorkflow.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/CodeGenSubgraphWorkflow.java) 里定义了 4 个方法（`#L34` 起），每个返回一个**只含 1 个节点的 `StateGraph`**：

```java
private StateGraph<MessagesState<String>> createContentImageSubgraph() {
    return new StateGraph<>(MessagesState.SCHEMA, MessagesState::new)
            .addNode("content_image_collector", node_async(new ContentImageCollectorNode()))
            .addEdge(START, "content_image_collector")
            .addEdge("content_image_collector", END);
}
```

然后在父图里**直接把未编译的 `StateGraph` 当节点加进去**（`#L101-L104`）：

```java
.addNode("content_image_subgraph", contentImageSubgraph)   // ← 没有 .compile()
```

**项目特有之处**：这里传的是**未编译的 `StateGraph`**，而不是 `CompiledGraph`。父图与子图**共享同一个 `MessagesState`**，所以子图节点里 `WorkflowContext.getContext(state)` 拿到的就是父图的 context，子图不需要额外传参。子图的价值是「把一组节点打包成一个可复用单元」——比如同一个「内容图片收集」子图既能放进并发工作流，也能放进别的流程。

`#L170-L196` 的 `executeWorkflow` 与 `CodeGenWorkflow` 的同名方法结构一致，同样打 MERMAID 日志 + `workflow.stream(...)` 循环。

#### 10.3.4 三个执行入口：同步 / Flux / SseEmitter

`CodeGenWorkflow` 提供了**三个执行方法**，对应三种输出方式：

| 方法 | 行号 | 返回 | 线程模型 | 输出方式 |
|---|---|---|---|---|
| `executeWorkflow(String)` | `#L73-L101` | `WorkflowContext` | 调用方线程（阻塞） | 无（只打日志） |
| `executeWorkflowWithFlux(String)` | `#L106-L151` | `Flux<String>` | `Thread.startVirtualThread` | Reactor `Flux.create` + `sink.next` |
| `executeWorkflowWithSse(String, SseEmitter)` | `#L169-L214` | `void` | `Thread.startVirtualThread` | `SseEmitter.send` |

**同步版**（`#L73-L101`）的骨架：

```java
WorkflowContext initialContext = WorkflowContext.builder()
        .originalPrompt(prompt)
        .currentStep("初始化")
        .build();
CompiledGraph<MessagesState<String>> workflow = createWorkflow();
log.info("工作流图: \n{}", workflow.getGraph(GraphRepresentation.Type.MERMAID).content());

WorkflowContext finalContext = null;
for (NodeOutput<MessagesState<String>> step : workflow.stream(
        Map.of(WorkflowContext.WORKFLOW_CONTEXT_KEY, initialContext))) {
    finalContext = WorkflowContext.getContext(step.state());
}
return finalContext;
```

三个细节：

1. **`workflow.getGraph(Type.MERMAID)`** 会把图的 Mermaid 文本打进日志，复制到 Mermaid Live Editor 就能看到流程图。这是 langgraph4j 提供的「自文档化」能力，**动手验证时值得看一眼**。
2. **`workflow.stream(...)` 返回的是每一步的 `NodeOutput`**，循环的每一步就是「一个节点执行完」。`step.state()` 是该步结束后的状态快照。
3. **`finalContext` 是循环结束后最后一次赋值**——因为节点改的是同一个 `WorkflowContext` 实例，其实第一轮拿到的引用就已经是最终对象了。

**Flux 版**（`#L106-L151`）用虚拟线程跑图，把每一步转成 SSE 格式塞进 `FluxSink`：

```java
return Flux.create(sink -> Thread.startVirtualThread(() -> {
    try {
        sink.next(formatSseEvent("workflow_start", Map.of("prompt", prompt)));
        for (NodeOutput<MessagesState<String>> step : workflow.stream(...)) {
            WorkflowContext ctx = WorkflowContext.getContext(step.state());
            sink.next(formatSseEvent("step_completed", Map.of(
                    "step", ctx.getCurrentStep(),
                    "context", ctx)));
        }
        sink.next(formatSseEvent("workflow_completed", Map.of("context", finalContext)));
        sink.complete();
    } catch (Exception e) {
        sink.next(formatSseEvent("workflow_error", Map.of("error", e.getMessage())));
        sink.error(e);
    }
}));
```

`formatSseEvent`（`#L156-L164`）就是手工拼 SSE 报文：

```java
private String formatSseEvent(String eventType, Object data) {
    String json = JSONUtil.toJsonStr(data);   // Hutool
    return "event: " + eventType + "\ndata: " + json + "\n\n";
}
```

**四种事件类型**：`workflow_start` / `step_completed` / `workflow_completed` / `workflow_error`。前端靠 `event:` 行区分事件种类。

**注意这里是 Reactor 的 `Flux`，但项目并没有引入 WebFlux**——`reactor-core` 是 `langchain4j-reactor` 带进来的（环节 2 已说明 SSE 走的是 Servlet 栈）。所以 `WorkflowSseController#execute-flux` 返回 `Flux<String>` 时，**能不能正确输出 SSE 取决于 Spring MVC 对 `Flux` 返回值类型的处理**，这条路径没有 `produces = TEXT_EVENT_STREAM_VALUE` 声明，属于「能编译但行为不确定」的写法。

**SseEmitter 版**（`#L169-L214`）是更稳妥的做法，和环节 2 主链路一致：

```java
public void executeWorkflowWithSse(String prompt, SseEmitter emitter) {
    SseEmitter sseEmitter = emitter != null ? emitter : new SseEmitter(30 * 60 * 1000L);  // 30 分钟超时
    Thread.startVirtualThread(() -> {
        try {
            sseEmitter.send(SseEmitter.event().name("workflow_start").data(...));
            for (NodeOutput<MessagesState<String>> step : workflow.stream(...)) {
                sseEmitter.send(SseEmitter.event().name("step_completed").data(...));
            }
            sseEmitter.send(SseEmitter.event().name("workflow_completed").data(...));
            sseEmitter.complete();
        } catch (Exception e) {
            sseEmitter.completeWithError(e);
        }
    });
}
```

**30 分钟超时**（`30 * 60 * 1000L`）是给「生成 Vue 工程 + 构建」留的余量；`sendSseEvent`（`#L219-L227`）封装了 `SseEmitter.event().name(x).data(y)` 的调用。

**坑**：`completeWithError(e)` 会让 Spring 以 error 结束这个 SSE 连接，客户端看到的是**连接异常断开**而不是一条结构化的 error 事件。更友好的做法是先 `send` 一个 `workflow_error` 事件再 `complete()`（Flux 版就是这么做的，两个版本行为不一致）。

---

### 10.4 代码走读

#### 10.4.1 入口：`WorkflowSseController`（全文 45 行）

[WorkflowSseController.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/WorkflowSseController.java)：

```java
@RestController
@RequestMapping("/workflow")
public class WorkflowSseController {

    @PostMapping("/execute")                    // #L23-L27
    public WorkflowContext executeWorkflow(@RequestParam String prompt) {
        return new CodeGenWorkflow().executeWorkflow(prompt);   // ← 每次 new
    }

    @GetMapping("/execute-flux")                // #L32-L36
    public Flux<String> executeWorkflowFlux(@RequestParam String prompt) {
        return new CodeGenWorkflow().executeWorkflowWithFlux(prompt);
    }

    @GetMapping("/execute-sse")                 // #L41-L45
    public SseEmitter executeWorkflowSse(@RequestParam String prompt) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        new CodeGenWorkflow().executeWorkflowWithSse(prompt, emitter);
        return emitter;
    }
}
```

**三处值得注意**：

1. **没有 `@AuthCheck`、没有 `@RateLimit`**。对比 `AppController#chatToGenCode` 上的 `@RateLimit(rate = 5, rateInterval = 60)`，这三个接口**完全裸奔**——虽然它调的是同样的 AI 模型、消耗同样的 token，却没有登录校验也没有限流。如果直接上线，这就是一个「**白嫖 AI 额度**」的入口。
2. **参数是 `@RequestParam`，不是 `@RequestBody`**。POST `/workflow/execute?prompt=xxx`，`prompt` 里带中文需要 URL 编码。
3. **`new CodeGenWorkflow()` 每次请求新建**。`CodeGenWorkflow` 无状态，new 的成本只是对象分配，但 `createWorkflow()` 会在每次调用时**重新构建整张图并 compile**——这是一笔纯重复开销。作为演示可接受，接入生产应做成单例 Bean（图编译一次复用）。

#### 10.4.2 `CodeGeneratorNode`：`appId` 硬编码 0

[CodeGeneratorNode.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/node/CodeGeneratorNode.java) 是本环节**最需要点出问题**的类：

```java
// L29：构造用户消息（含图片素材）
String userMessage = buildUserMessage(context);
// L32：从容器拿门面
AiCodeGeneratorFacade codeGeneratorFacade = SpringContextUtil.getBean(AiCodeGeneratorFacade.class);
CodeGenTypeEnum generationType = context.getGenerationType();

// L35：appId 硬编码！
Long appId = 0L;   // 注释原文：「先使用固定的 appId (后续再整合到业务中)」

// L37：调主链路的门面（和环节 2 用的是同一个）
TokenStream codeStream = codeGeneratorFacade.generateAndSaveCodeStream(
        userMessage, generationType, appId);

// L39：阻塞等流结束，最多 10 分钟
codeStream.blockLast(Duration.ofMinutes(10));

// L41：按约定推算落盘目录
context.setGeneratedCodeDir(String.format("%s/%s_%s",
        AppConstant.CODE_OUTPUT_ROOT_DIR, generationType.getValue(), appId));
```

**三个后果，必须理解清楚**：

1. **所有工作流生成的代码都落在 `code_output/{type}_0`**（例如 `code_output/html_0`、`code_output/vue_project_0`）。**同一个类型下多次运行会互相覆盖**——第二次生成的文件会盖掉第一次的。目录里的文件名由 AI 决定，名字相同就覆盖。
2. **不会往 `app` 表写任何数据**。`appId = 0L` 意味着生成的代码**不属于任何一个应用**，前端「我的应用」列表里看不到，也拿不到预览 URL、不会触发截图上传 COS。工作流跑完只在本地磁盘留下一堆文件。
3. **`buildUserMessage`（`#L54-L63`）在质检失败时替换提示词**：

```java
private String buildUserMessage(WorkflowContext context) {
    if (isQualityCheckFailed(context)) {
        return buildErrorFixPrompt(context);   // 用「修复」提示词
    }
    return context.getEnhancedPrompt();        // 正常用增强后的提示词
}
```

`isQualityCheckFailed`（`#L68-L73`）判断 `qualityResult != null && !getIsValid()`。`buildErrorFixPrompt`（`#L78-L92`）把上一次的错误列表和建议拼成「请修复以下问题」的提示词。**这是那条 `fail → code_generator` 回边能工作的原因**——回边的目标节点会检测到 `qualityResult.isValid == false`，从而切换成修复模式。但如 10.2.3 所说，**没有轮次上限**。

#### 10.4.3 三个节点的「异常降级」策略

这三个节点遇到异常都不中断流程，而是降级继续——**这是这个模块最统一的「设计决策」，也是最需要被质疑的地方**：

| 节点 | 异常时行为 | 行号 | 后果 |
|---|---|---|---|
| `RouterNode` | `generationType = CodeGenTypeEnum.HTML` | `#L31-L34` | 路由失败默认用最简单的 HTML 模式，**用户要 Vue 工程会得到单文件 HTML** |
| `CodeQualityCheckNode` | `isValid = true`（视为通过） | `#L47-L52` | 质检服务挂了 → **跳过质检直接构建**，注释原文「异常直接跳到下一个步骤」 |
| `ProjectBuilderNode` | `buildResultDir = generatedCodeDir` | `#L44-L47` | 构建失败 → **返回未构建的源码目录**，下游拿到的是 Vue 源码而不是 `dist` 产物 |

`CodeQualityCheckNode` 的这段最值得看：

```java
// L43-L44
QualityResult qualityResult = SpringContextUtil.getBean(CodeQualityCheckService.class)
        .checkCodeQuality(codeContent);

// L47-L52
} catch (Exception e) {
    log.error("代码质量检查失败", e);
    // 异常直接跳到下一个步骤
    return WorkflowContext.saveContext(context);   // ← 不改 qualityResult，保持 null
}
```

注意它 `catch` 里**没有设置 `qualityResult`**，所以 `qualityResult` 保持 `null`。那么回看 `routeAfterQualityCheck#L235-L246`：

```java
if (qualityResult == null || !qualityResult.getIsValid()) {
    return "fail";       // ← qualityResult == null 也走 fail！
}
```

**这里存在一个逻辑矛盾**：注释说「异常直接跳到下一个步骤」（意思是放行），但 `qualityResult` 为 `null` 时路由函数判定为 `"fail"`，会**回到 `code_generator` 重新生成**——正好和注释的意图相反。而且由于没有轮次上限，如果质检服务持续不可用，**图会无限循环生成**。

另外 `#L34-L40` 还有一层兜底：读出来的代码内容为空时，直接构造 `QualityResult.builder().isValid(false).errors(List.of("未找到代码文件")).build()`。

`readAndConcatenateCodeFiles`（`#L70-L95`）用 Hutool 的 `FileUtil.walkFiles` 遍历目录，`FileUtil.subPath` 拼相对路径，把代码文件拼成一个大字符串喂给 AI；`shouldSkipFile`（`#L100-L111`）跳过 `.` 开头的隐藏文件、`node_modules/`、`dist/`、`target/`、`.git/`；`CODE_EXTENSIONS`（`#L63-L65`）限定 9 种扩展名（`.html`/`.htm`/`.css`/`.js`/`.json`/`.vue`/`.ts`/`.jsx`/`.tsx`）。

#### 10.4.4 `ImageCollectorNode` 与图片收集规划

`ImageCollectorNode` 的第一步是「让 AI 决定要收集什么图片」：

```java
// L36-L37
ImageCollectionPlan plan = SpringContextUtil.getBean(ImageCollectionPlanService.class)
        .planImageCollection(originalPrompt);
```

`ImageCollectionPlanService`（接口，18 行）是个 LangChain4j AI 服务：

```java
@SystemMessage(fromResource = "prompt/image-collection-plan-system-prompt.txt")
ImageCollectionPlan planImageCollection(@UserMessage String userPrompt);
```

返回的结构化对象是 [ImageCollectionPlan.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/model/ImageCollectionPlan.java)（57 行），**4 个 List 字段 + 4 个 record**：

```java
@Data
public class ImageCollectionPlan implements Serializable {
    private List<ImageSearchTask> contentImageTasks;
    private List<IllustrationTask> illustrationTasks;
    private List<DiagramTask> diagramTasks;
    private List<LogoTask> logoTasks;

    public record ImageSearchTask(String query) implements Serializable {}
    public record IllustrationTask(String query) implements Serializable {}
    public record DiagramTask(String mermaidCode, String description) implements Serializable {}
    public record LogoTask(String description) implements Serializable {}
}
```

**用 `record` 做 AI 结构化输出的载体**是这里的一个亮点：`record` 天然不可变、字段少、语义清晰，比 `@Data` 类更适合「AI 填参数」的场景（AI 不会误改字段）。注释里还写明了每个任务对应哪个工具方法（例如 `DiagramTask` 注释「对应 `MermaidDiagramTool.generateMermaidDiagram(String, String)`」），**把「AI 输出」和「工具入参」的对应关系写在模型上**，是很好维护习惯。

两个 AI 服务的工厂（`ImageCollectionPlanServiceFactory`、`CodeQualityCheckServiceFactory`）都是 `@Configuration` + `@Bean` 模式：

```java
// CodeQualityCheckServiceFactory
@Configuration
public class CodeQualityCheckServiceFactory {

    @Resource(name = "openAiChatModel")          // ← 指定 Bean 名
    private ChatModel chatModel;

    @Bean
    public CodeQualityCheckService createCodeQualityCheckService() {
        return AiServices.builder(CodeQualityCheckService.class)
                .chatModel(chatModel)
                .build();
    }
}
```

**`@Resource(name = "openAiChatModel")` 这个 Bean 名很重要**：项目里**没有任何地方手写 `@Bean` 定义叫 `openAiChatModel` 的 Bean**，它由 `langchain4j-open-ai-spring-boot-starter` 自动装配（配置前缀 `langchain4j.open-ai.chat-model`，见架构文档）。它和环节 2/3 用的 `streamingChatModelPrototype` 是**两个不同的模型**：这个是**非流式**的 `deepseek-chat`，用于「图片规划」「代码质检」这类**要结构化返回值**的调用（流式模型不方便拿完整 JSON）。

#### 10.4.5 四个图片工具

| 工具类 | 行数 | 外部依赖 | 关键点 |
|---|---|---|---|
| `ImageSearchTool` | 59 | Pexels API | `PEXELS_API_URL = "https://api.pexels.com/v1/search"`；`@Value("${pexels.api-key}")`；`searchCount = 12`；取 `photos[].src.medium` |
| `UndrawIllustrationTool` | 66 | undraw.co | **URL 里含硬编码 buildId**（见下）；`.timeout(10000)`；取 `pageProps.initialResults[].media` |
| `MermaidDiagramTool` | 89 | **本机 `mmdc`** + COS | 调 Mermaid CLI 把代码转 SVG，再上传 COS |
| `LogoGeneratorTool` | 65 | DashScope 文生图 | `@Value("${dashscope.api-key:}")`；模型 `wan2.2-t2i-flash`；尺寸 `512*512` |

**`UndrawIllustrationTool` 的硬编码 buildId**：

```java
private static final String UNDRAW_API_URL =
    "https://undraw.co/_next/data/mMWmJSt23qpgo8cLTD_pB/search/%s.json?term=%s";
```

`mMWmJSt23qpgo8cLTD_pB` 是 undraw.co 用 Next.js 构建时生成的 **buildId**，每次上游重新部署都会变。也就是说 **undraw 一重新发布，这个接口就 404**，属于「写死外部站点内部实现」的典型脆弱依赖。（这类非官方 JSON 接口本身就是逆向出来的，不属于公开 API。）

**`MermaidDiagramTool` 依赖本机安装 `mmdc`**（`#L65-L88`）：

```java
FileUtil.createTempFile(...);
String cmdLine = String.format("%s -i %s -o %s -b transparent",
        SystemUtil.getOsInfo().isWindows() ? "mmdc.cmd" : "mmdc", inputFile, outputFile);
String result = RuntimeUtil.execForStr(cmdLine);
```

- 用 `SystemUtil.getOsInfo().isWindows()` 区分 `mmdc.cmd` / `mmdc`，**说明这个功能在 Windows 开发机上跑通过**；
- `RuntimeUtil.execForStr` 走的是**命令行**，意味着：**部署环境必须预装 Mermaid CLI + Node.js**，容器镜像里不装就是运行时失败；
- 转换出的 SVG 用 `RandomUtil.randomString(5)` 拼路径 `/mermaid/{随机5位}/{文件名}` 上传 COS（`#L44-L46`），**随机前缀是为了防同名覆盖**。

`LogoGeneratorTool` 的提示词里有一句值得注意的约束：

```java
String.format("生成 Logo，Logo 中禁止包含任何文字！Logo 介绍：%s", description);
```

**「禁止包含任何文字」是文生图模型的已知短板**（扩散模型画文字容易画成乱码），所以用提示词强行规避——这类「用 prompt 绕开模型缺陷」的技巧在 AI 应用里很常见。

---

### 10.5 动手验证

#### 验证 1：确认整包未接入主链路

```
Grep "langgraph4j" 全项目（仅 src/main/java）
```

预期：只命中 `controller/WorkflowSseController.java` 的 2 行 import。**如果命中了 `AiCodeGeneratorFacade` 或 `AppServiceImpl`，说明结论要修正**（当前代码没有）。

#### 验证 2：跑一次同步工作流（最直观）

```
curl -X POST "http://localhost:8123/api/workflow/execute?prompt=%E5%88%9B%E5%BB%BA%E4%B8%80%E4%B8%AA%E9%B1%BC%E7%9A%AE%E7%9A%84%E4%B8%AA%E4%BA%BA%E5%8D%9A%E5%AE%A2%E7%BD%91%E7%AB%99"
```

（`prompt` 值 = 「创建一个鱼皮的个人博客网站」的 URL 编码。）

**预期看到什么**：

1. 请求会**阻塞较久**（要经过图片收集 + AI 生成 + 质检 + 可能的构建），Flux/SSE 版体验更好；
2. 控制台会打印一行 `工作流图:` 后跟 Mermaid 文本，形如：

```
graph TD;
	__start__ --> image_collector;
	image_collector --> prompt_enhancer;
	prompt_enhancer --> router;
	router --> code_generator;
	code_generator --> code_quality_check;
	code_quality_check -.-> project_builder;
	code_quality_check -.-> __end__;
	code_quality_check -.-> code_generator;
	project_builder --> __end__;
```

把这段贴到 Mermaid Live Editor 就能看到图。**注意 `code_quality_check` 的三条虚线（条件边）**，这正好对应代码里的 `Map.of("build"/"skip_build"/"fail", ...)`。

3. 返回的 JSON 是 `WorkflowContext` 的序列化结果，看 `currentStep` 字段能知道走到哪一步。

#### 验证 3：确认 `appId = 0L` 的落盘位置

跑完之后去看代码输出目录（`AppConstant.CODE_OUTPUT_ROOT_DIR` 指向的路径，默认在项目临时目录下）：

```
dir /s /b tmp\code_output
```

**预期看到什么**：

- 出现 `code_output\html_0\`（或 `code_output\vue_project_0\`）目录，**目录名末尾是 `_0`**；
- 目录里有 `index.html` 之类的文件，且是 AI 生成的网站代码；
- **`app` 表里没有任何新记录**（可以 `SELECT * FROM app ORDER BY id DESC LIMIT 5;` 验证，看不到刚才的 prompt）；
- 再跑一次同一个接口，**文件名相同的文件被覆盖**，`createTime` 变成新的。

这条验证能把「硬编码 appId」的三个后果（目录恒为 `_0`、不入库、互相覆盖）一次性证实。

#### 验证 4：SSE 版看事件流

```
curl -N "http://localhost:8123/api/workflow/execute-sse?prompt=hello"
```

（`-N` 关闭 curl 缓冲，否则看不到流式效果。）

**预期看到什么**：按顺序出现

```
event:workflow_start
data:{...}

event:step_completed
data:{"step":"图片收集",...}

event:step_completed
data:{"step":"提示词增强",...}

...（每个节点一条 step_completed）

event:workflow_completed
data:{...}
```

**`data` 里的 `step` 字段值就是各节点 `setCurrentStep(...)` 写入的中文名**——可对照节点源码核对：`图片收集`（`ImageCollectorNode#L91`）、`提示词增强`（`PromptEnhancerNode#L52`）、`智能路由`、`图片计划`、`图片聚合`、`Logo生成` 等。

#### 验证 5：检查 `mmdc` 是否可用

```
mmdc --version
```

**预期看到什么**：输出 Mermaid CLI 版本号。**如果提示「不是内部或外部命令」，那么跑工作流时 `diagram_collector` 那一类图片会全部失败**——但根据 10.3.1 的异常吞掉逻辑，**流程不会中断**，只是 `imageList` 里少了架构图。这是一个「静默降级」的实例，值得亲手验证一次。

#### 验证 6：确认 `@RateLimit` / `@AuthCheck` 没打在这三个接口上

```
Grep "@RateLimit|@AuthCheck" controller/WorkflowSseController.java
```

预期：**零命中**。对比 `Grep "@RateLimit"` 全项目只有 1 处（在 `AppController#L60`）。

---

### 10.6 自测题

<details>
<summary><b>展开题目（13 题）</b></summary>

**Q1.** `langgraph4j` 包被主业务链路使用了吗？请给出判断依据。

**Q2.** `CodeGenWorkflow` 是 Spring Bean 吗？`WorkflowSseController` 里是怎么用它的？

**Q3.** `WorkflowContext` 是图的状态类型吗？节点里怎么读写它？

**Q4.** `CodeGenWorkflow` 里 `code_quality_check` 节点有几条出边？分别去哪？判定函数是什么？

**Q5.** 质检不通过时会怎样？这条回边有什么风险？

**Q6.** `CodeGeneratorNode` 里的 `appId` 是多少？为什么这是个问题？会造成哪三个具体后果？

**Q7.** 三个节点（Router / CodeQualityCheck / ProjectBuilder）遇到异常分别怎么处理？这是好设计还是坏设计？

**Q8.** `CodeQualityCheckNode` 的 catch 块注释说「异常直接跳到下一个步骤」，但实际会走哪条边？为什么？

**Q9.** `CodeGenWorkflow` 的三种执行入口分别是什么？后两种用什么线程跑图？

**Q10.** `ImageCollectorNode` 用什么方式并发收集 4 类图片？用的是虚拟线程吗？

**Q11.** `CodeGenConcurrentWorkflow` 的图级并发是怎么配置线程池的？参数传的是什么？

**Q12.** `CodeGenSubgraphWorkflow` 里子图是怎么加进父图的？子图和父图共享状态吗？

**Q13.** 那 4 个图片工具类上的 `@Tool` 注解在工作流里起作用了吗？为什么？

**Q14.** `CodeQualityCheckServiceFactory` 注入的 `openAiChatModel` 是哪里来的？为什么不用流式模型？

**Q15.** `UndrawIllustrationTool` 的 URL 有什么问题？`MermaidDiagramTool` 有什么部署要求？

</details>

<details>
<summary><b>展开答案与解析</b></summary>

**A1.** **没有**。全项目 `src/main/java` 下 grep `langgraph4j`，只命中 `controller/WorkflowSseController.java` 的 2 行 import（`CodeGenWorkflow`、`WorkflowContext`）。主链路「生成应用」走的是环节 2 的 `AiCodeGeneratorFacade`，一步都不经过 `CodeGenWorkflow`。所以整包是**已完成但未上线的能力模块**，对外只有 3 个演示接口。

**A2.** **不是**。类声明只有 `@Slf4j`，没有 `@Component`。`WorkflowSseController` 里三个接口都是 `new CodeGenWorkflow()`，**每次请求新建一个**。副作用是每次都要重新 `createWorkflow()` 构建并编译整张图（纯重复开销）。因为不是 Bean，节点里只能用 `SpringContextUtil.getBean(...)` 手工从容器取服务。

**A3.** **不是**。图的状态类型是 `MessagesState<String>`（langgraph4j 预置类型，本质是 `Map<String, Object>`）。`WorkflowContext` 被**整体塞进** `MessagesState` 的 `"workflowContext"` 这个 key 里。节点固定三段式：`WorkflowContext.getContext(state)` 取出 → `setXxx(...)` 修改 → `WorkflowContext.saveContext(context)` 返回。

**A4.** **3 条**。由 `addConditionalEdges("code_quality_check", edge_async(this::routeAfterQualityCheck), Map.of("build", "project_builder", "skip_build", END, "fail", "code_generator"))` 定义，判定函数是 `routeAfterQualityCheck`。逻辑：`qualityResult == null || !getIsValid()` → `"fail"`；否则 `routeBuildOrSkip` 判断类型，HTML/MULTI_FILE → `"skip_build"`，VUE_PROJECT → `"build"`。

**A5.** 走 `fail → code_generator` **回到生成节点重新生成**。`CodeGeneratorNode#buildUserMessage` 会检测到 `qualityResult.isValid == false`，改用 `buildErrorFixPrompt` 拼「请修复以下问题」的提示词。**风险是这条回边没有任何重试次数上限**——若质检规则与生成结果天然冲突（或质检服务持续异常），图会无限循环「生成 → 质检 → 失败 → 生成」，每轮生成还会 `blockLast(10 分钟)`。编排式流程必须自己设计循环退出条件，框架不管。

**A6.** **`appId = 0L` 硬编码**（`#L35`，注释原文「先使用固定的 appId (后续再整合到业务中)」）。三个后果：① 落盘目录恒为 `code_output/{type}_0`，**同类型多次运行互相覆盖**；② **不写 `app` 表**，生成结果不属于任何应用，前端看不到、无预览 URL、不触发截图上传；③ 无法按应用隔离，多人同时用会写到同一目录。

**A7.** 都不中断，降级继续：`RouterNode` 异常 → `generationType = HTML`（`#L31-L34`）；`CodeQualityCheckNode` 异常 → 不改 `qualityResult`（保持 null，注释说「跳到下一步」）（`#L47-L52`）；`ProjectBuilderNode` 异常 → `buildResultDir = generatedCodeDir` 返回源码目录（`#L44-L47`）。**作为「不中断用户请求」的取舍可以理解，但至少有两个问题**：一是降级后**用户不知道结果是降级的**（要了 Vue 得到 HTML、没构建却当构建成功）；二是 `CodeQualityCheckNode` 的降级意图与路由逻辑**互相矛盾**（见 A8）。

**A8.** 会走 **`"fail"`**（回 `code_generator`），而不是注释说的「跳到下一个步骤」。原因：catch 块**没有设置 `qualityResult`**，它保持 `null`；而 `routeAfterQualityCheck` 的条件是 `qualityResult == null || !qualityResult.getIsValid()` → `null` 也判定为 `"fail"`。**注释与代码意图相反**，且叠加「无轮次上限」后，质检服务持续不可用会导致无限循环生成。

**A9.** ① `executeWorkflow(String)`（`#L73-L101`）同步阻塞，返回 `WorkflowContext`；② `executeWorkflowWithFlux(String)`（`#L106-L151`）返回 `Flux<String>`，用 **`Thread.startVirtualThread`**（虚拟线程）跑图，通过 `Flux.create` + `sink.next` 推事件；③ `executeWorkflowWithSse(String, SseEmitter)`（`#L169-L214`）也用 **`Thread.startVirtualThread`**，直接 `SseEmitter.send`。后两者事件类型都是 `workflow_start` / `step_completed` / `workflow_completed` / `workflow_error`。

**A10.** 用 **`CompletableFuture.supplyAsync(...)`** 并发跑 4 类任务，再 `CompletableFuture.allOf(...).join()` 等齐。**不传 Executor 时用的是 `ForkJoinPool.commonPool()`，不是虚拟线程**。这是反模式：4 个任务里 3 个是网络 IO，用公共池跑 IO 会阻塞公共池线程，**影响同 JVM 内所有依赖 `commonPool` 的代码**（含并行 Stream）；而且和项目其他位置大量用虚拟线程的风格不一致。

**A11.** 用 Hutool 的 `ExecutorBuilder` 建线程池（core 10 / max 20 / `LinkedBlockingQueue(100)` / 线程名前缀 `Parallel-Image-Collect`），再通过 `RunnableConfig.builder().addParallelNodeExecutor("image_plan", pool).build()` 绑定，最后 `workflow.stream(state, runnableConfig)`。**参数传的是扇出源节点名 `image_plan`**（不是目标节点）。**坑：这个线程池没有 `shutdown()`，也没交给 Spring 管理生命周期**。

**A12.** 直接把**未编译的 `StateGraph`** 当节点加进去：`.addNode("content_image_subgraph", contentImageSubgraph)`，**没有调 `.compile()`**。子图与父图**共享同一个 `MessagesState`**，所以子图节点里 `WorkflowContext.getContext(state)` 能拿到父图 context，不需要额外传参。子图的价值是把一组节点打包成可复用单元。

**A13.** **没有起作用**。这 4 个工具类虽然都是 `@Component` 且方法上标了 `@Tool`/`@P`（LangChain4j 的工具注解），但在工作流里它们是**被直接 Java 调用**的：`SpringContextUtil.getBean(XxxTool.class)` 拿到实例后直接 `.searchContentImages(...)`。`@Tool` 注解只对「AI 服务自动工具调用」生效（环节 3），这里是纯 Java 方法调用，注解被忽略。

**A14.** 由 **`langchain4j-open-ai-spring-boot-starter` 自动装配**，配置前缀 `langchain4j.open-ai.chat-model`（项目里没有任何手写 `@Bean` 定义叫 `openAiChatModel` 的 Bean）。用非流式模型是因为**这两个场景要的是结构化返回值**（`ImageCollectionPlan`、`QualityResult`），需要等模型把完整 JSON 输出完再反序列化；流式模型拿到的是增量 token，不方便直接映射成对象。

**A15.** `UndrawIllustrationTool` 的 `UNDRAW_API_URL` 里**硬编码了 undraw.co 的 Next.js buildId**（`mMWmJSt23qpgo8cLTD_pB`）——上游每次重新部署 buildId 都会变，一变这个接口就 404。而且这本身是逆向出来的非公开接口，不稳定。`MermaidDiagramTool` 通过 `RuntimeUtil.execForStr` 调用本机命令 `mmdc`（Windows 用 `mmdc.cmd`），**部署环境必须预装 Mermaid CLI + Node.js**，否则架构图那一类图片会静默失败（异常被吞，流程继续）。

</details>

---

### 10.7 本环节技术点清单

| 技术点 | 落地位置 | 作用 | 项目特有之处 |
|---|---|---|---|
| LangGraph4j 依赖 | `pom.xml#L73-L84` | 图编排框架 | **`1.6.0-rc2` 是 RC 版**；两个依赖都**显式写 version**（不在 Boot BOM 内） |
| 图状态类型 | `CodeGenWorkflow#createWorkflow` | `new MessagesStateGraph<String>()` | 用预置 `MessagesState`，**不是自定义 State** |
| 业务状态类 | `state/WorkflowContext.java#L30` | 承载全部业务数据 | `WORKFLOW_CONTEXT_KEY = "workflowContext"`；**整体塞进 state 的一个 key** |
| 状态读写约定 | `WorkflowContext#L103-L112` | `getContext` / `saveContext` | `saveContext` 用 `Map.of(...)`（**不可变 Map**，只能改对象字段不能改 Map） |
| 节点写法 | 各 `node/*.java` | `node_async(state -> ...)` | 固定三段式：取 → 改 → 存回；**改的是同一对象引用**（共享可变状态） |
| 条件边 | `CodeGenWorkflow#L54-L60` | 质检后三路分支 | `build` / `skip_build` / `fail`；**`fail` 是回边且无重试上限** |
| Mermaid 自文档 | `CodeGenWorkflow#L76` | `getGraph(Type.MERMAID)` | 启动即打印图结构，可直接贴 Mermaid Live Editor |
| 图级并发线程池 | `CodeGenConcurrentWorkflow#L103-L111` | `RunnableConfig.addParallelNodeExecutor` | 参数是**扇出源节点名**（`image_plan`）；Hutool `ExecutorBuilder` 建池；**池未关闭** |
| 扇出/汇聚 | `CodeGenConcurrentWorkflow#L56-L65` | 1 节点 → 4 节点 → 1 节点 | 4 个并发节点**写 4 个互不重叠的字段**，靠字段错开避免丢更新 |
| 子图 | `CodeGenSubgraphWorkflow#L101-L104` | `addNode(name, 未编译 StateGraph)` | **不加 `.compile()`**；与父图**共享 `MessagesState`** |
| 节点内并发 | `ImageCollectorNode#L41-L78` | `CompletableFuture.supplyAsync` | **默认 `ForkJoinPool.commonPool`，不是虚拟线程**；异常被 `catch` 吞掉 |
| 虚拟线程执行 | `CodeGenWorkflow#L108`、`#L171` | `Thread.startVirtualThread` | Flux / SSE 两个入口用虚拟线程跑整张图 |
| SSE 手工拼报文 | `CodeGenWorkflow#L156-L164` | `"event: x\ndata: y\n\n"` | Hutool `JSONUtil.toJsonStr`；4 种事件类型 |
| SseEmitter 超时 | `CodeGenWorkflow#L170` | `30 * 60 * 1000L` | 30 分钟，为 Vue 构建留余量；**`completeWithError` 与 Flux 版行为不一致** |
| 演示接口 | `controller/WorkflowSseController.java` | `/workflow/execute`、`/execute-flux`、`/execute-sse` | **无 `@AuthCheck`、无 `@RateLimit`**；每次 `new CodeGenWorkflow()`；`@RequestParam` 传参 |
| `appId` 硬编码 | `CodeGeneratorNode#L35` | `Long appId = 0L` | 注释自述「后续再整合」；落盘恒为 `{type}_0`、**不入库、会互相覆盖** |
| 阻塞等待流 | `CodeGeneratorNode#L39` | `codeStream.blockLast(10 分钟)` | 工作流是**同步阻塞**的，与环节 2 的流式推送体验相反 |
| 修复提示词 | `CodeGeneratorNode#L54-L92` | `buildErrorFixPrompt` | 回边重生成时自动切「修复模式」，是 `fail` 边能工作的关键 |
| 路由降级 | `RouterNode#L31-L34` | 异常 → `HTML` | 用户要 Vue 工程会拿到单文件 HTML，**且无提示** |
| 质检降级 | `CodeQualityCheckNode#L47-L52` | 异常不改 `qualityResult` | 注释「跳到下一步」但**实际走 `fail` 回边**，注释与代码矛盾 |
| 质检内容读取 | `CodeQualityCheckNode#L63-L119` | 遍历目录拼代码 | 9 种扩展名；跳过 `node_modules/`、`dist/`、`target/`、`.git/`、`.` 开头 |
| 构建降级 | `ProjectBuilderNode#L44-L47` | 异常 → 返回源码目录 | 下游拿到的是 Vue 源码而非 `dist` 产物 |
| AI 服务工厂 | `ai/*Factory.java` | `@Configuration` + `@Bean` + `AiServices.builder()` | `@Resource(name = "openAiChatModel")`，该 Bean 由 **starter 自动装配**（非流式） |
| 结构化输出模型 | `model/ImageCollectionPlan.java`、`QualityResult.java` | 接 AI 返回值 | 用 **`record`** 承载任务参数（不可变、字段少）；注释标明任务与工具方法的对应关系 |
| 图片工具（Pexels） | `tools/ImageSearchTool.java` | 搜内容图片 | `searchCount = 12`；Hutool `HttpRequest` try-with-resources |
| 图片工具（undraw） | `tools/UndrawIllustrationTool.java` | 搜插画 | **URL 硬编码 Next.js buildId，上游一部署就失效** |
| 图片工具（Mermaid） | `tools/MermaidDiagramTool.java` | 代码转架构图 | **依赖本机 `mmdc`（Mermaid CLI + Node.js）**；SVG 上传 COS |
| 图片工具（Logo） | `tools/LogoGeneratorTool.java` | DashScope 文生图 | 模型 `wan2.2-t2i-flash`；提示词强制「禁止包含任何文字」 |
| `@Tool` 注解未生效 | 4 个工具类 | — | 工作流里是**直接 Java 调用**，`@Tool`/`@P` 只对 AI 自动工具调用生效 |
| Studio 调试配置 | `config/LangGraphStudioSampleConfig.java` | 接 LangGraph Studio | `extends AbstractLangGraphStudioConfig`；**类注释自述「实际上很难使用」** |
| 演示 main 类 | `WorkflowApp`、`SimpleWorkflowApp`、`demo/SimpleGraphApp` | 本地跑图 | `SimpleGraphApp` 引用的 `ResponderNode` **在仓库中不存在**（可能编译不过） |
| 未接入主链路 | 全项目 Grep | — | **唯一引用是 `WorkflowSseController`**；主链路仍走 `AiCodeGeneratorFacade` |

---

### 10.8 本环节产出

这个环节是「**把写死在 Java 里的流程，改写成一张可声明、可视化、可分支的图**」，技术底座是 **LangGraph4j 1.6.0-rc2**。它的核心抽象只有四个：**状态**（`MessagesState<String>`，本质是 Map）、**节点**（`node_async(state -> ...)` 的异步动作）、**边**（`addEdge` 定顺序）、**条件边**（`addConditionalEdges` + 路由函数做运行时分支）。项目在框架之上加了两条自己的约定：一是**用一个 `WorkflowContext` 装全部业务数据**，通过 `getContext`/`saveContext` 塞进 state 的 `"workflowContext"` key；二是**节点固定三段式写法**（取 → 改 → 存回）。因为节点不是 Spring Bean（`CodeGenWorkflow` 无 `@Component`），所有容器依赖都靠 `SpringContextUtil.getBean(...)` 手工取。图的价值体现在三处：`getGraph(Type.MERMAID)` 让流程**自文档化**、`workflow.stream(...)` 让每一步**可观测**、条件边让「质检失败就重新生成」这种**循环控制流**用声明的方式表达出来。项目还给了同一流程的三种编排对照——**节点内并发**（`CompletableFuture.supplyAsync`）、**图级并发**（扇出 4 条边 + `RunnableConfig.addParallelNodeExecutor` 绑线程池）、**子图**（未编译的 `StateGraph` 直接当节点，与父图共享状态）。最需要记住的是**这个包没有上线**：`langgraph4j` 整包在主链路里零引用，唯一出口是 `WorkflowSseController` 的三个演示接口，而这三个接口**既没有 `@AuthCheck` 也没有 `@RateLimit`**，`new CodeGenWorkflow()` 还是每次新建。同时它带着四个「演示代码的典型缺陷」：`CodeGeneratorNode#L35` 把 **`appId` 硬编码成 `0L`**（导致落盘目录恒为 `{type}_0`、结果不入 `app` 表、多次运行互相覆盖）、**`fail → code_generator` 回边没有重试上限**（叠加 `CodeQualityCheckNode` 异常时 `qualityResult` 保持 `null` 会被判定成 `fail`，注释说「跳到下一步」实际却回退，可能无限循环生成）、**三个节点统一「异常降级继续」**（路由失败给 HTML、质检失败当通过、构建失败返回源码目录，且用户无感知）、以及 **`ImageCollectorNode` 用 `ForkJoinPool.commonPool` 跑网络 IO**（与项目其他地方用虚拟线程的风格不一致）。读到这里应该能回答：**「这个项目有没有用工作流编排来组织 AI 生成流程，如果有，它为什么没有被用起来」**——答案是：有，`langgraph4j` 包里已经有完整实现（6 节点串行图 + 质检条件边 + 并发/子图两种变体 + 4 个图片工具 + 2 个 AI 结构化服务），但它**没有接入主业务链路**——用户点「生成应用」走的仍然是 `AppController#chatToGenCode → AppServiceImpl → AiCodeGeneratorFacade → StreamHandlerExecutor` 这条一条龙的流式链路，工作流唯一的入口 `WorkflowSseController` 是三个无鉴权、无限流的演示接口，且节点里 `appId = 0L` 的硬编码说明它**从未被当作正式功能接入过业务**；它现在的定位是「**框架能力的验证与演示**」，要真正用起来，需要解决三件事：把 `appId` 从上下文透传进节点、给回边加最大重试轮次、以及把工作流执行入口纳入登录与限流保护。

---

## 环节 11：缓存体系与对话历史查询优化

### 11.1 这个环节解决什么问题

前十个环节都在解决「功能怎么跑通」，这个环节解决的是「**跑通之后怎么不把数据库打死**」。项目里有两个读多写少的场景，各自用了不同手段：

| 场景 | 问题 | 手段 |
|---|---|---|
| 首页「精选应用」分页列表 | 全站访问量最高的接口，数据几乎不变，却每次查库 | **声明式缓存**（Spring Cache + Redis） |
| 某个应用的对话历史翻页 | 对话历史随对话无限增长，`LIMIT offset, size` 越翻越慢 | **游标分页**（keyset pagination）+ 复合索引 |

这两个问题在面试里是高频考点，而且项目的实现方式都有「一眼能看出是学习项目」的取舍，正好用来讲清楚「**为什么这么选、代价是什么**」。

顺带说一句：项目里其实有**四层**「数据暂存」，容易混在一起，先建立整体认知：

| 层 | 落地位置 | 存什么 | 过期/上限 |
|---|---|---|---|
| ① 本地进程缓存（Caffeine） | `ai/AiCodeGeneratorServiceFactory#L51-L58` | `AiCodeGeneratorService` 实例（内含 `MessageWindowChatMemory`） | `maximumSize(1000)`；写后 30 分钟 / 访问后 10 分钟 |
| ② Redis 对话记忆 | `config/RedisChatMemoryStoreConfig`（`spring.data.redis.ttl: 3600`） | 对话记忆的消息列表（`RedisChatMemoryStore`） | 3600 秒 |
| ③ Redis 声明式缓存 | `config/RedisCacheManagerConfig` | **业务接口的返回结果**（本环节主角） | 默认 30 分钟；`good_app_page` 5 分钟 |
| ④ MySQL | `app`、`chat_history` 表 | 真相源（source of truth） | 永久（逻辑删除） |

①②在环节 4 讲过，④是常规持久化，本环节重点讲③，并说明③和①②的区别：**①②缓存的是「对象/会话状态」，③缓存的是「HTTP 接口的返回值」**——前者需要自己写读写代码，后者只要打一个注解。

---

### 11.2 技术点一：Spring Cache 抽象 + `RedisCacheManager` 声明式缓存

#### 11.2.1 第一步是 `@EnableCaching`，否则注解静默失效

[YuAiCodeMotherApplication.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/YuAiCodeMotherApplication.java)：

```java
@EnableCaching                                          // L9
@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
@MapperScan("com.yupi.yuaicodemother.mapper")
public class YuAiCodeMotherApplication {
    public static void main(String[] args) {
        SpringApplication.run(YuAiCodeMotherApplication.class, args);
    }
}
```

**`@EnableCaching` 是必须的，不是可选的。** Spring Boot 的 `CacheAutoConfiguration` 只负责**创建 `CacheManager`**，它**不会**打开「缓存注解拦截」这个开关——开关由 `@EnableCaching` 引入的 `ProxyCachingConfiguration` 提供。少了这个注解，`@Cacheable` **不报错、不警告、就是完全不生效**，每次请求照样查库。

这是排查「缓存怎么没生效」的**第一站**，也是最容易忘的一步。而且注意：

- **项目没有引入 `spring-boot-starter-cache`**（`pom.xml` 里搜不到）。也不需要——`@EnableCaching` 来自 `spring-context`，`RedisCacheManager` 来自 `spring-data-redis`，两个都已经是现有依赖的传递依赖。
- 项目里 `@EnableCaching` 全仓**只有这 1 处**（Grep 可验证）。

#### 11.2.2 手写 `CacheManager` 覆盖 Boot 自动装配

[RedisCacheManagerConfig.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/config/RedisCacheManagerConfig.java)（全文 51 行）：

```java
@Configuration
public class RedisCacheManagerConfig {

    @Resource
    private RedisConnectionFactory redisConnectionFactory;   // L24-L25

    @Bean
    public CacheManager cacheManager() {                     // L27-L28
        // 配置 ObjectMapper 支持 Java8 时间类型
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());    // L30-L31

        // 默认配置
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))             // 默认 30 分钟过期
                .disableCachingNullValues()                   // 禁用 null 值缓存
                // key 使用 String 序列化器
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()));
//                // value 使用 JSON 序列化器（支持复杂对象）但是要注意开启后需要给序列化增加默认类型配置，否则无法反序列化
//                .serializeValuesWith(RedisSerializationContext.SerializationPair
//                        .fromSerializer(new GenericJackson2JsonRedisSerializer(objectMapper)));

        return RedisCacheManager.builder(redisConnectionFactory)
                .cacheDefaults(defaultConfig)
                // 针对 good_app_page 配置5分钟过期
                .withCacheConfiguration("good_app_page",
                        defaultConfig.entryTtl(Duration.ofMinutes(5)))
                .build();
    }
}
```

**要点逐条拆：**

1. **`@Bean` 方法名是 `cacheManager`，和 Spring Boot 的约定一致。** Boot 的 `RedisCacheConfiguration` 自动配置带 `@ConditionalOnMissingBean(CacheManager.class)`，用户自己定义的 `CacheManager` 会先生效、把它顶掉。所以**这个 Bean 是在「覆盖」自动装配，而不是「补充」**——名字保持一致能避免出现两个 `CacheManager` 导致注入歧义。
2. **`@Resource RedisConnectionFactory` 是 Boot 自动装配提供的**（`spring-boot-starter-data-redis` 根据 `spring.data.redis.*` 建 `LettuceConnectionFactory`），项目自己没有定义。
3. **`entryTtl(30 分钟)` 是兜底默认值，`withCacheConfiguration("good_app_page", 5 分钟)` 是给单个 cache 定制 TTL。** 这个「默认 + 局部覆盖」的写法是 `RedisCacheManager` 的标准用法：`cacheDefaults` 管所有未单独配置的 cache name，`withCacheConfiguration` 管指定的那个。
4. **`disableCachingNullValues()`**：方法返回 `null` 时不写缓存。这里其实拦不住什么——`listGoodAppVOByPage` 返回的是 `ResultUtils.success(...)`，永远非 null（查不到就是空列表）。这个方法主要防的是「把 null 也缓存成有效值」导致的逻辑错误。
5. **`serializeKeysWith(StringRedisSerializer)`**：Redis 的 key 用普通字符串。**这一步不能省**——如果不指定，key 也会走 JDK 序列化，`redis-cli` 里看到的是带 `\xac\xed` 头的乱码二进制，`KEYS`/`SCAN` 匹配和排查都会很痛苦。
6. **value 的序列化器被注释掉了**（`#L40-L42`）——**这是本环节最关键的一处**，见下节。

#### 11.2.3 为什么 JSON 序列化器被注释掉了（JDK 序列化的代价）

注释原文写得很清楚：

> `// value 使用 JSON 序列化器（支持复杂对象）但是要注意开启后需要给序列化增加默认类型配置，否则无法反序列化`

这段话背后的原因是 **Java 泛型擦除**：

- 缓存的方法返回类型是 `BaseResponse<Page<AppVO>>`；
- 用 `GenericJackson2JsonRedisSerializer` 把它序列化成 JSON 时，**JSON 里只有字段名和值，没有「这个对象是什么类」的信息**（`{"code":0,"data":{...}}`）；
- 反序列化时只能还原成 `LinkedHashMap`，**不知道要还原成 `BaseResponse` / `Page` / `AppVO`**，于是要么 `ClassCastException`，要么把 Map 当对象用时报错。

要解决就必须开启 Jackson 的**默认类型信息**（`activateDefaultTyping`），让 JSON 里带上 `@class` 字段——这会引入安全面（反序列化任意类型）和 JSON 体积膨胀。项目权衡之后，**选择了不配 JSON 序列化器，让 `RedisCacheConfiguration.defaultCacheConfig()` 的默认值生效，也就是 JDK 序列化（`JdkSerializationRedisSerializer`）**。

**JDK 序列化的三条代价，必须记住：**

| 代价 | 说明 |
|---|---|
| **值必须实现 `Serializable`** | 否则写入缓存时抛 `NotSerializableException`。已核实：`common/BaseResponse.java#L14`、`model/vo/AppVO.java#L12`、`model/vo/UserVO.java#L12` 都实现了 `Serializable`（**这类项目里给 VO/DTO 统一加 `implements Serializable` 的惯例，就是为了兼容缓存**） |
| **内容不可读、不可跨语言** | `redis-cli` 看到的是二进制；Python/Go 服务读不了这份缓存 |
| **对类结构变化敏感** | 类加字段/改包名、`serialVersionUID` 变化后，**旧缓存反序列化会失败**；上线新版本后要么等 TTL 过期，要么手动 `DEL` |

**实践结论**：这种「不配序列化器 → 悄悄退化成 JDK 序列化」的写法在学习和中小项目里很常见（省事、能跑），但生产项目更推荐「**配 JSON 序列化器 + 显式开默认类型**」或者「**干脆缓存自己定义的、扁平的 DTO**」，让缓存内容可读、可迁移。

#### 11.2.4 最终 Redis key 长什么样

`RedisCacheManager` 默认用 `CacheKeyPrefix.simple()`，即 `cacheName + "::"`，所以最终 key 是：

```
good_app_page::<32 位小写 md5>
```

- `good_app_page` 来自 `@Cacheable(value = "good_app_page")`，也就是 `withCacheConfiguration` 里配 5 分钟 TTL 的那个名字——**两处字符串必须完全一致**，写错了不会报错，只会用 30 分钟的默认 TTL（又一个「静默失效」的坑）。
- 后面的 md5 由 `CacheKeyUtils` 生成，见下节。

---

### 11.3 技术点二：`@Cacheable` 的 key 与 condition

#### 11.3.1 注解三要素

[AppController.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/AppController.java) 的 `#L266-L288`，**全项目唯一一处 `@Cacheable`**：

```java
@PostMapping("/good/list/page/vo")
@Cacheable(
        value = "good_app_page",                                                  // ① cache name
        key = "T(com.yupi.yuaicodemother.utils.CacheKeyUtils).generateKey(#appQueryRequest)",  // ② SpEL key
        condition = "#appQueryRequest.pageNum <= 10"                              // ③ 命中条件
)
public BaseResponse<Page<AppVO>> listGoodAppVOByPage(@RequestBody AppQueryRequest appQueryRequest) {
    ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
    long pageSize = appQueryRequest.getPageSize();
    ThrowUtils.throwIf(pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
    long pageNum = appQueryRequest.getPageNum();
    // 只查询精选的应用
    appQueryRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);   // L279：覆盖 priority = 99
    QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
    Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
    Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
    List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
    appVOPage.setRecords(appVOList);
    return ResultUtils.success(appVOPage);
}
```

| 属性 | 值 | 作用 |
|---|---|---|
| `value` | `good_app_page` | cache name，决定 Redis key 前缀 + 用哪套 TTL（5 分钟） |
| `key` | SpEL 调静态方法 | 决定同一个 cache 内的具体 key |
| `condition` | `#appQueryRequest.pageNum <= 10` | 为 `false` 时**跳过缓存**（既不读也不写） |

**`condition` 为什么是 `<= 10`？** 这是「**只缓存热数据**」的思路：精选应用列表的流量集中在前几页，翻到第 11 页往后是长尾；把这些页也缓存，只会占 Redis 内存、命中率极低，而且会让缓存里堆满一次性数据。用 `condition` 做「按请求参数决定要不要缓存」是 `@Cacheable` 里很实用的一个能力。

**`condition` 里的属性访问安全吗？** 安全。`@RequestBody` 默认 `required = true`，空 body 在参数解析阶段就抛 `HttpMessageNotReadableException`，所以 `appQueryRequest` 不会是 `null`，`#appQueryRequest.pageNum` 不会触发 SpEL 的空指针。**但要注意求值时机**：`condition` 在**方法体执行之前**求值，所以方法体里那句 `ThrowUtils.throwIf(appQueryRequest == null, ...)` 属于「防御性代码」，实际上拦不到 null。

#### 11.3.2 key 的生成：JSON → MD5

[CacheKeyUtils.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/utils/CacheKeyUtils.java)（全文 27 行）：

```java
public class CacheKeyUtils {

    public static String generateKey(Object obj) {
        if (obj == null) {
            return DigestUtil.md5Hex("null");
        }
        // 先转 JSON，再转 MD5
        String jsonStr = JSONUtil.toJsonStr(obj);
        return DigestUtil.md5Hex(jsonStr);
    }
}
```

- **`T(...)` 是 SpEL 的静态类引用语法**，`T(全限定类名).静态方法(参数)`。这是 `@Cacheable` key 里调用工具类的标准写法。
- **`#appQueryRequest`** 是 SpEL 的方法参数引用（`#参数名`）。**参数名要生效，编译时必须保留参数名信息**（`-parameters` 或 `-g`）；Spring Boot 的 Maven 插件默认加了 `-parameters`，所以这里能用 `#appQueryRequest`。如果参数名丢了，SpEL 会报 `Cannot resolve parameter name`——这是排查 `@Cacheable` key 报错时的常见原因。
- **为什么是「JSON + MD5」两步**：`AppQueryRequest` 转成 JSON 后可能很长（含 `initPrompt` 这种中文长文本），而且**直接当 key 会带 `{`、`"`、中文**等字符，虽然 Redis 支持二进制 key，但可读性、`KEYS` 匹配、客户端兼容性都成问题。MD5 之后固定 32 位小写十六进制，干净、等长、无特殊字符。**代价是不可读**——排查时得自己把请求对象转 JSON 再算一次 md5 才能对上 key。
- **`null` 兜底**：`md5Hex("null")` 而不是直接返回 `"null"`，保持 key 长度一致。

#### 11.3.3 key 粒度的反面案例（这段最值得学）

`generateKey` 拿的是**整个 `AppQueryRequest` 对象**，而 `AppQueryRequest` 的字段包括（`model/dto/app/AppQueryRequest.java`）：

```
id, appName, cover, initPrompt, codeGenType, deployKey, priority, userId
+ 继承自 PageRequest 的：pageNum, pageSize, sortField, sortOrder
```

问题在于：**方法体第一件事就是把 `priority` 覆盖成 99**（`#L279`），也就是说**客户端传的 `priority` 根本不影响结果**。但它**在 key 里**。于是：

- 客户端 A 不传 `priority` → key = `md5({...})`
- 客户端 B 传 `priority = 1` → key = `md5({...,"priority":1})`

**两个 key 不同，结果完全一样，Redis 里存了两份**。同理 `id`、`appName`、`cover` 等字段也都进 key——前端只要多传一个无用字段，就多一份缓存。

**正确做法**是让 key **只包含真正影响结果的字段**，比如：

```java
// 示意写法：只把「分页 + 排序」放进 key
key = "'good:' + #appQueryRequest.pageNum + ':' + #appQueryRequest.pageSize
       + ':' + #appQueryRequest.sortField + ':' + #appQueryRequest.sortOrder"
```

这是「缓存 key 设计」最重要的一条原则：**key 的粒度要精确等于「决定结果的最小输入集合」**——多了浪费内存、降低命中率，少了会串数据（更严重）。

#### 11.3.4 一个关键缺失：没有 `@CacheEvict`

全项目 Grep `@Cacheable|@CacheEvict|@CachePut`，**只命中 `AppController#L267` 一处 `@Cacheable`**，`@CacheEvict` 和 `@CachePut` **零命中**。

后果：**精选应用列表的缓存只能靠 TTL 自然过期，没有任何主动失效手段。** 具体表现：

- 管理员把某个应用的 `priority` 改成 99（进入精选），**最长 5 分钟内首页看不到**；
- 管理员删除了一个精选应用，**最长 5 分钟内首页还能看到它**（点进去会 404 或报错）；
- 想立刻生效只能手动 `DEL good_app_page::*`。

对「精选应用」这种容忍几分钟延迟的场景，**用 TTL 兜底是可以接受的设计**；但如果业务要求「改完立刻可见」，就必须在管理员的 update/delete 接口上加 `@CacheEvict(value = "good_app_page", allEntries = true)`。**读代码时看到「只有 `@Cacheable` 没有 `@CacheEvict`」，第一反应就该是「这个缓存的数据一致性窗口是 TTL」。**

---

### 11.4 技术点三：游标分页与复合索引

#### 11.4.1 为什么不用 `LIMIT offset, size`

传统分页（`LIMIT offset, size`）有两个绕不过的问题：

1. **越翻越慢**：`LIMIT 10000, 10` 需要**扫描并丢弃前 10000 行**再取 10 行。页号越大越慢，且慢的是数据库 CPU 和 IO。
2. **翻页过程中数据会漂移**：如果翻页期间有新数据插到前面（对话历史里这太常见了——用户边聊边翻），**第 2 页可能包含第 1 页看过的记录**（重复），也可能整条记录被挤过去而**漏掉**。

**游标分页（keyset pagination）**的思路是：不记「第几页」，而记「**上一页最后一条的位置**」，下一页查「比这个位置更早/更晚的记录」。

#### 11.4.2 项目的实现

请求参数在 [ChatHistoryQueryRequest.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/model/dto/chathistory/ChatHistoryQueryRequest.java) 的 `#L42-L46`：

```java
/**
 * 游标查询 - 最后一条记录的创建时间
 * 用于分页查询，获取早于此时间的记录
 */
private LocalDateTime lastCreateTime;
```

接口在 [ChatHistoryController.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/ChatHistoryController.java) 的 `#L46-L54`：

```java
@GetMapping("/app/{appId}")
public BaseResponse<Page<ChatHistory>> listAppChatHistory(@PathVariable Long appId,
                                                          @RequestParam(defaultValue = "10") int pageSize,
                                                          @RequestParam(required = false) LocalDateTime lastCreateTime,
                                                          HttpServletRequest request) {
    User loginUser = userService.getLoginUser(request);
    Page<ChatHistory> result = chatHistoryService.listAppChatHistoryByPage(appId, pageSize, lastCreateTime, loginUser);
    return ResultUtils.success(result);
}
```

SQL 条件在 [ChatHistoryServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ChatHistoryServiceImpl.java) 的 `getQueryWrapper#L153-L163`：

```java
// 游标查询逻辑 - 只使用 createTime 作为游标
if (lastCreateTime != null) {
    queryWrapper.lt("createTime", lastCreateTime);   // 严格小于 → 只要更早的
}
// 排序
if (StrUtil.isNotBlank(sortField)) {
    queryWrapper.orderBy(sortField, "ascend".equals(sortOrder));
} else {
    // 默认按创建时间降序排列
    queryWrapper.orderBy("createTime", false);
}
```

**最关键的一行在 `listAppChatHistoryByPage#L90`**：

```java
// 查询数据
return this.page(Page.of(1, pageSize), queryWrapper);   // ← 永远查「第 1 页」
```

**每次请求都查「第 1 页」**，靠 `lastCreateTime` 这个游标把窗口往前推。所以生成的 SQL 大致是：

```sql
SELECT ... FROM chat_history
WHERE appId = ? AND createTime < ? AND isDelete = 0
ORDER BY createTime DESC
LIMIT ?;          -- 没有 OFFSET
```

**没有 `OFFSET`**，这就是游标分页的核心特征——不管翻到第几屏，每次都是「从索引定位到游标位置，往后取 N 条」，**耗时恒定**。

（`isDelete = 0` 不是代码写的，是 MyBatis-Flex 自动追加的，见 11.4.5。）

#### 11.4.3 索引设计：`idx_appId_createTime`

[sql/create_table.sql](file:///e:/AI/GitHub/yu-ai-code-mother/sql/create_table.sql) 的 `#L53-L67`：

```sql
create table chat_history
(
    id          bigint auto_increment comment 'id' primary key,
    message     text                               not null comment '消息',
    messageType varchar(32)                        not null comment 'user/ai',
    appId       bigint                             not null comment '应用id',
    userId      bigint                             not null comment '创建用户id',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    INDEX idx_appId (appId),                       -- 提升基于应用的查询性能
    INDEX idx_createTime (createTime),             -- 提升基于时间的查询性能
    INDEX idx_appId_createTime (appId, createTime) -- 游标查询核心索引
) comment '对话历史' collate = utf8mb4_unicode_ci;
```

`idx_appId_createTime (appId, createTime)` 与游标查询的 SQL **完全对齐**，逐个条件对上：

| SQL 片段 | 索引如何发挥作用 |
|---|---|
| `WHERE appId = ?` | 复合索引**最左前缀**等值定位，直接跳到该应用的区间 |
| `AND createTime < ?` | 在 `appId` 区间内，`createTime` 是**第二列的范围条件**，继续在索引里做范围扫描 |
| `ORDER BY createTime DESC` | 索引内 `createTime` **天然有序** → 直接按索引顺序读即可，**不会出现 `Using filesort`** |
| `LIMIT ?` | 取够 N 条就停，**不需要扫描全区间** |

注释里写的「游标查询核心索引」，说的就是这个设计。**这也是「索引要跟着查询写」的教科书例子**——先确定访问模式（`appId + createTime 范围 + createTime 排序`），再设计复合索引，列顺序（等值在前、范围在后、排序列跟着范围列）不是随便排的。

**两处值得注意的地方：**

1. **`idx_appId (appId)` 是冗余索引。** 有了 `idx_appId_createTime (appId, createTime)`，**任何只用 `appId` 的查询都能靠复合索引的最左前缀完成**，单独的 `idx_appId` 不会再被优化器选中（除非为了覆盖索引/统计信息），只白白占写入成本。这是「加索引只加不减」留下的典型冗余。
2. **`idx_createTime (createTime)` 保留是有意义的**（跨应用按时间查，比如管理员按时间范围统计），但它和 `idx_appId_createTime` 不冗余，因为最左列不同。
3. **索引里没有 `isDelete`。** 因为逻辑删除条件 `isDelete = 0` 是框架自动追加的，索引定位到候选行后需要**回表**去读 `isDelete` 做过滤。行数不多时影响有限，但如果某个应用的历史里大量记录被逻辑删除，回表成本会上升——这类「逻辑删除 + 索引」的取舍是实际项目里经常要权衡的点。

#### 11.4.4 游标列选 `createTime` 的隐患

**`createTime` 是 `datetime` 类型，精度到秒**（`sql/create_table.sql#L61`：`datetime default CURRENT_TIMESTAMP`，没有 `(3)`/`(6)` 小数秒）。而游标条件是**严格小于**（`lt("createTime", lastCreateTime)`）。

于是：**同一秒内插入的多条记录，`createTime` 完全相同**，`createTime < lastCreateTime` 会把它们**全部排除**——也就是**漏数据**。

这个场景在对话历史里非常容易触发：

- 用户发一条消息 → 存 `user` 消息 → AI 开始回复 → 存 `ai` 消息。**这两条完全可能落在同一秒**（尤其是短回复）；
- 用户快速连发两条消息。

**更稳的方案是用主键做游标**。注意 [ChatHistory.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/model/entity/ChatHistory.java) 的 `#L36`：

```java
@Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
private Long id;
```

主键是**雪花 ID**（`KeyGenerators.snowFlakeId`）。雪花 ID 是「时间戳 + 机器号 + 序列号」拼出来的，**单调递增且唯一**，天生适合当游标：

```sql
-- 更稳的写法：用 id 当游标，天然唯一
WHERE appId = ? AND id < ? ORDER BY id DESC LIMIT ?
```

配合 `INDEX idx_appId_id (appId, id)`（或直接 `(appId, id)` 复合索引），就彻底避免了「同秒重复导致漏数据」。**这是「有雪花 ID 却用时间列做游标」的一个可惜之处**——项目已经把最合适的游标列（`id`）准备好了，但没用上。

#### 11.4.5 `loadChatHistoryToMemory`：另一个「取最近 N 条」的查询

`ChatHistoryServiceImpl#L93-L125` 是给 AI 记忆加载历史用的（环节 4 已讲用途，这里只看查询写法）：

```java
QueryWrapper queryWrapper = QueryWrapper.create()
        .eq(ChatHistory::getAppId, appId)
        .orderBy(ChatHistory::getCreateTime, false)
        .limit(1, maxCount);                       // L99
List<ChatHistory> historyList = this.list(queryWrapper);
if (CollUtil.isEmpty(historyList)) {
    return 0;
}
// 反转列表，确保按照时间正序（老的在前，新的在后）
historyList = historyList.reversed();          // L105
```

三处细节：

1. **查询用 `ChatHistory::getAppId` 这种 Lambda 方法引用**（而不是像 `getQueryWrapper` 那样传字符串 `"appId"`）。**两种写法项目里都有**——Lambda 写法有编译期检查（字段改名会编译失败），字符串写法拼错了只会在运行时才炸。同一个类里风格不统一，读的时候要留意。
2. **`orderBy(createTime, false)` 是降序 → 取「最近的 N 条」→ 再 `reversed()` 翻成正序**塞进记忆。这个「倒着查、正着用」是分页取尾部的标准套路。
3. **`.limit(1, maxCount)` 这个写法需要确认语义。** MyBatis-Flex 的 `limit` 存在 `limit(rows)` 和 `limit(offset, rows)` 两种重载：如果命中后者，`offset = 1` 意味着**跳过第一条（也就是最新的一条）**，与「取最近 maxCount 条」的意图不符。**不要靠猜——用 11.5 的验证 5 打开 SQL 日志，看实际生成的是 `LIMIT 20` 还是 `LIMIT 1, 20`。**

另外这段还有一个**调用时机**上的特点：`loadChatHistoryToMemory` 只在 `AiCodeGeneratorServiceFactory#createAiCodeGeneratorService`（Caffeine 未命中、要新建 AI 服务实例时）里被调用一次（`#L99`），**不是每次对话都查库**。之后对话记忆的读写走的是 Redis（`RedisChatMemoryStore`），MySQL 里的 `chat_history` 表退化成「冷数据存档」。这正是 11.1 那张四层表的价值：**同一份对话数据，在 MySQL（全量真相源）、Redis（活跃记忆）、Caffeine（服务实例）里各存了一份，职责和生命周期完全不同。**

#### 11.4.6 两个 `getQueryWrapper` 的差异

项目里有两个同名方法，写法有区别，容易看混：

| | `ChatHistoryServiceImpl#getQueryWrapper`（`#L134-L165`） | `AppServiceImpl#getQueryWrapper`（`#L258-L282`） |
|---|---|---|
| 入参为 null | 返回空 `QueryWrapper`（放行） | **抛 `BusinessException(PARAMS_ERROR)`** |
| 排序字段 | `isNotBlank(sortField)` 判断，**有默认排序分支**（`createTime` 降序） | 直接 `orderBy(sortField, ...)`，**没有默认分支** |
| 游标 | 有 `lastCreateTime` → `lt("createTime", ...)` | 无 |

**`sortField` 直接来自请求体**（`PageRequest.sortField`），而 `orderBy(sortField, ...)` 是把它**当列名**用（列名无法用 `?` 参数化）。两个方法都**没有对 `sortField` 做白名单校验**。这种「把用户输入当列名」的写法在生产项目里通常必须加白名单（例如只允许 `createTime`、`updateTime`、`id`），否则要么报「未知列」错误，要么成为注入面。**这里只指出事实和风险点，具体 MyBatis-Flex 对列名做了多少转义/校验，建议按实际依赖版本用 11.5 的验证 6 亲手确认。**

---

### 11.5 动手验证

#### 验证 1：确认 `@EnableCaching` 存在（否则下面全是白测）

```
Grep "@EnableCaching" src/main/java
```

预期：只命中 `YuAiCodeMotherApplication.java#L9` 一处。**如果这里零命中，`@Cacheable` 是完全不生效的，后面所有缓存验证都会失败。**

同时确认全项目缓存注解的分布：

```
Grep "@Cacheable|@CacheEvict|@CachePut" src/main/java
```

预期：`@Cacheable` 1 处（`AppController#L267`），**`@CacheEvict`/`@CachePut` 零命中** → 印证「没有主动失效，只能靠 TTL」。

#### 验证 2：打开 SQL 日志（后续验证的前提）

项目 `application.yml` 里 `spring.profiles.active: local`，但仓库里**只有 `application-prod-sample.yml`，没有 `application-local.yml`**（本地配置不入库）。想看到 SQL，在本地配置里加：

```yaml
mybatis-flex:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
```

（生产模板 `application-prod-sample.yml#L69-L71` 里这项是空字符串，即**生产关闭 SQL 日志**。）

#### 验证 3：证明缓存真的生效（第一次查库、第二次不查）

连续调两次同一个请求：

```
curl -X POST "http://localhost:8123/api/app/good/list/page/vo" -H "Content-Type: application/json" -d "{\"pageNum\":1,\"pageSize\":10}"
curl -X POST "http://localhost:8123/api/app/good/list/page/vo" -H "Content-Type: application/json" -d "{\"pageNum\":1,\"pageSize\":10}"
```

**预期看到什么**：

- **第一次**：控制台打印 `SELECT ... FROM app WHERE priority = 99 ... ORDER BY ... LIMIT ?`；
- **第二次**：控制台**没有任何 SQL**，但接口照样返回数据 → 命中缓存；
- 两次返回的 JSON **完全相同**。

**如果第二次仍然打印 SQL**，按顺序检查：① `@EnableCaching` 在不在；② `@Cacheable` 的 `value` 和 `withCacheConfiguration` 里的名字是否一致（不一致不会报错，只是 TTL 变 30 分钟）；③ `pageNum` 是不是 ≤ 10。

#### 验证 4：直接看 Redis 里的 key 和 TTL

```
redis-cli -n 0 KEYS "good_app_page::*"
redis-cli -n 0 TTL good_app_page::<上一步输出的 key 后缀>
redis-cli -n 0 TYPE good_app_page::<...>
```

**预期看到什么**：

- key 形如 `good_app_page::` + **32 位小写十六进制**（md5）；
- `TTL` 返回 **300 上下**（5 分钟，`withCacheConfiguration` 配的值）——**如果返回 1800 左右，说明 cache name 没对上，用的是默认 30 分钟**；
- `TYPE` 返回 `string`；
- `GET` 出来的内容是**二进制乱码**（JDK 序列化的 `\xac\xed` 开头）→ 印证 11.2.3 的结论。

#### 验证 5：验证 `condition` 不缓存第 11 页

```
curl -X POST "http://localhost:8123/api/app/good/list/page/vo" -H "Content-Type: application/json" -d "{\"pageNum\":11,\"pageSize\":10}"
redis-cli -n 0 KEYS "good_app_page::*"
```

**预期看到什么**：接口正常返回，但 **Redis 里不会多出这个请求对应的 key**（`condition` 为 false → 既不读也不写缓存）。再调一次第 11 页，**SQL 会再次打印**（没有缓存）。

#### 验证 6：看游标分页生成的 SQL（同时确认 `.limit(1, maxCount)` 的语义）

1. 触发一次带游标的历史查询：

```
curl -N "http://localhost:8123/api/chatHistory/app/1?pageSize=2" -b "<登录后的 Cookie>"
```

（需要先登录拿 Cookie，因为接口会调 `getUserService.getLoginUser`。）

**预期看到什么**：SQL 形如

```sql
SELECT ... FROM chat_history WHERE appId = ? AND isDelete = 0
ORDER BY createTime DESC LIMIT ?
```

**注意有没有 `OFFSET`——应该没有**，这是游标分页的特征。`isDelete = 0` 是 MyBatis-Flex 自动加的（`ChatHistory#L77` 的 `@Column(value = "isDelete", isLogicDelete = true)`），代码里没写。

2. 再用返回结果里最后一条的 `createTime` 作为下一页游标：

```
curl -N "http://localhost:8123/api/chatHistory/app/1?pageSize=2&lastCreateTime=2026-09-22T10:00:00"
```

**预期看到什么**：SQL 里多出 `AND createTime < ?`，**依然没有 OFFSET**。

3. 顺便验证 `loadChatHistoryToMemory` 的 `.limit(1, maxCount)`：**新建一个 appId 的应用并发起一次对话**（触发 Caffeine 未命中 → 新建 AI 服务实例 → 调 `loadChatHistoryToMemory`），在 SQL 日志里找这条查询，看它是 `LIMIT 20` 还是 `LIMIT 1, 20`。**这一步能直接证实或推翻 11.4.5 里那个「offset=1 会不会跳过最新一条」的疑问。**

#### 验证 7：用 `EXPLAIN` 确认索引被用上

```sql
EXPLAIN SELECT id, message, messageType, appId, userId, createTime
FROM chat_history
WHERE appId = 1 AND createTime < '2026-09-22 10:00:00' AND isDelete = 0
ORDER BY createTime DESC
LIMIT 10;
```

**预期看到什么**：

- `key` 列是 `idx_appId_createTime`（**不是 `idx_appId`，也不是 `idx_createTime`**）；
- `Extra` 列**没有 `Using filesort`**（排序靠索引顺序完成）；
- `type` 是 `range`（范围扫描）。

再对比一下「没有复合索引会怎样」——可以先 `EXPLAIN` 一个只用 `createTime` 的查询，看它选 `idx_createTime`，理解「优化器按最左前缀选索引」的行为。

#### 验证 8：验证「同秒游标会漏数据」

```sql
-- 插两条 createTime 完全相同的记录
INSERT INTO chat_history (message, messageType, appId, userId, createTime)
VALUES ('msg-A', 'user', 999, 1, '2026-01-01 12:00:00'),
       ('msg-B', 'ai',   999, 1, '2026-01-01 12:00:00');

-- 模拟游标翻页：以这一秒为游标往下翻
SELECT id, message FROM chat_history
WHERE appId = 999 AND createTime < '2026-01-01 12:00:00'
ORDER BY createTime DESC;
```

**预期看到什么**：两条 `12:00:00` 的记录**都不会出现**在结果里——如果你把它们当作「已展示过的一页」，那么它们就被彻底跳过了。**这就是「用非唯一列做游标」的漏数据实证。**

#### 验证 9：确认缓存没有主动失效

```
Grep "good_app_page" src/main/java
```

预期：只命中 `AppController#L268`（`value`）和 `RedisCacheManagerConfig#L47`（`withCacheConfiguration`）两处。**没有任何地方删除/更新这个缓存** → 印证 11.3.4。

想亲眼看一致性窗口，可以：调一次接口（写入缓存）→ 直接改库 `UPDATE app SET priority = 99 WHERE id = ?` → 立刻再调接口，**返回的仍是旧数据**；等 5 分钟后再调，才看到新数据。

---

### 11.6 自测题

<details>
<summary><b>展开题目（15 题）</b></summary>

**Q1.** `@Cacheable` 要生效，除了依赖和配置，还必须有什么？少了会怎样？

**Q2.** 项目引入了 `spring-boot-starter-cache` 吗？`CacheManager` 是谁提供的？

**Q3.** `RedisCacheManagerConfig` 里的 `CacheManager` Bean 是在「补充」还是「覆盖」Spring Boot 的自动装配？判断依据是什么？

**Q4.** 为什么 value 的 JSON 序列化器被注释掉了？注释里那句话的技术含义是什么？

**Q5.** 不配 value 序列化器，实际用的是什么序列化器？带来哪三条代价？

**Q6.** 为什么 `serializeKeysWith(StringRedisSerializer)` 不能省？

**Q7.** 最终 Redis key 长什么样？`good_app_page` 这个字符串在项目里出现在几个地方？写错会怎样？

**Q8.** `@Cacheable` 的 key 为什么要「先转 JSON 再 MD5」？代价是什么？

**Q9.** 这个 key 的粒度有什么问题？正确的粒度原则是什么？

**Q10.** `condition = "#appQueryRequest.pageNum <= 10"` 的作用是什么？为什么这么设计？

**Q11.** 项目有没有缓存主动失效？如果没有，一致性窗口是多少？

**Q12.** 游标分页和 `LIMIT offset, size` 分页相比，解决了哪两个问题？

**Q13.** `listAppChatHistoryByPage` 里的 `Page.of(1, pageSize)` 为什么永远是 1？

**Q14.** `idx_appId_createTime` 这个复合索引为什么能同时满足「等值 + 范围 + 排序」？表里的 `idx_appId` 有什么问题？

**Q15.** 用 `createTime` 做游标有什么隐患？项目里本来有更合适的游标列吗？

</details>

<details>
<summary><b>展开答案与解析</b></summary>

**A1.** 必须有 **`@EnableCaching`**（在 `YuAiCodeMotherApplication#L9`）。Spring Boot 的 `CacheAutoConfiguration` **只负责创建 `CacheManager`，不负责打开缓存注解拦截**——拦截逻辑由 `@EnableCaching` 引入的 `ProxyCachingConfiguration` 提供。少了它，`@Cacheable` **不报错、不警告、完全不生效**，每次请求照样查库。这是排查「缓存没生效」的第一站。

**A2.** **没有引入** `spring-boot-starter-cache`（`pom.xml` 里搜不到）。也不需要：`@EnableCaching` 来自 `spring-context`，`RedisCacheManager`/`RedisCacheConfiguration` 来自 `spring-data-redis`，都是现有依赖的传递依赖。`CacheManager` 由项目自己的 `RedisCacheManagerConfig#cacheManager()`（`#L27-L50`）提供，`RedisConnectionFactory` 由 Boot 自动装配提供。

**A3.** **覆盖**。Boot 的 `RedisCacheConfiguration` 自动配置带 `@ConditionalOnMissingBean(CacheManager.class)`，用户配置类里的 `CacheManager` Bean 会先生效、让自动配置退让。另外 `@Bean` 方法名取 `cacheManager` 与 Boot 约定一致，可以避免出现两个 `CacheManager` 导致注入歧义。

**A4.** 因为**泛型擦除**。方法返回 `BaseResponse<Page<AppVO>>`，用 `GenericJackson2JsonRedisSerializer` 序列化出的 JSON 里**只有字段没有类型信息**，反序列化时只能还原成 `LinkedHashMap`，无法还原成 `BaseResponse`/`Page`/`AppVO`，会抛 `ClassCastException` 或用起来报错。要解决必须开启 Jackson 的**默认类型信息**（`activateDefaultTyping`，JSON 里带 `@class`），代价是体积膨胀 + 反序列化任意类型的安全面。注释原文「开启后需要给序列化增加默认类型配置，否则无法反序列化」说的就是这个。

**A5.** 用的是 `RedisCacheConfiguration.defaultCacheConfig()` 的默认值，即 **JDK 序列化（`JdkSerializationRedisSerializer`）**。三条代价：① **被缓存的返回值必须实现 `Serializable`**，否则抛 `NotSerializableException`；② 内容**二进制不可读、不可跨语言**（`redis-cli` 看到 `\xac\xed` 乱码）；③ **对类结构变化敏感**，加字段/改包名/`serialVersionUID` 变化后旧缓存反序列化失败，只能等 TTL 过期或手动 `DEL`。

**A6.** 不指定的话 **key 也会走 JDK 序列化**，Redis 里看到的是带 `\xac\xed` 头的二进制乱码，`KEYS`/`SCAN` 匹配、人工排查、其他语言客户端读取都会很痛苦。指定 `StringRedisSerializer` 后 key 就是普通可读字符串。

**A7.** 形如 **`good_app_page::<32 位小写 md5>`**（`RedisCacheManager` 默认 `CacheKeyPrefix.simple()` = `cacheName + "::"`）。`good_app_page` 出现在**两处**：`AppController#L268` 的 `@Cacheable(value=...)` 和 `RedisCacheManagerConfig#L47` 的 `withCacheConfiguration("good_app_page", ...)`。**两处必须完全一致**——写错了不会报错，只是这个 cache 拿不到 5 分钟的特制 TTL，退化成默认 30 分钟。

**A8.** 因为对象转 JSON 后**可能很长**（`AppQueryRequest` 里有 `initPrompt` 这种中文长文本），而且**带 `{`、`"`、中文等特殊字符**；MD5 之后固定 **32 位小写十六进制**，等长、干净、无特殊字符，适合当 Redis key。代价是**不可读**——排查时要自己把请求对象转 JSON 再算一次 md5 才能对上 key。

**A9.** key 用的是**整个请求对象**（`id`、`appName`、`cover`、`initPrompt`、`codeGenType`、`deployKey`、`priority`、`userId` + `pageNum`/`pageSize`/`sortField`/`sortOrder`），但方法体第一行就把 `priority` 覆盖成 99（`#L279`），**客户端传的 `priority` 不影响结果却进了 key** → 传不同 `priority` 会产生不同 key、拿到相同结果，Redis 里白存多份。同理其他无用字段也会造成 key 污染。**正确原则：key 的粒度要精确等于「决定结果的最小输入集合」**——多了浪费内存降低命中率，少了会串数据。

**A10.** 作用是「**只缓存前 10 页**」：为 `false` 时**跳过缓存**（既不读也不写）。设计意图是「只缓存热数据」——精选列表流量集中在前几页，翻到第 11 页往后是长尾，缓存它们只会占 Redis 内存、命中率极低。这是 `@Cacheable` 里「按请求参数决定要不要缓存」的实用能力。

**A11.** **没有**。全项目 Grep `@CacheEvict`/`@CachePut` **零命中**，只有 `AppController#L267` 一处 `@Cacheable`。所以一致性窗口就是 **TTL = 5 分钟**（`good_app_page` 配的值）：管理员改 `priority` 让应用进精选、或删除精选应用，**最长 5 分钟内首页看到的还是旧数据**，想立刻生效只能手动 `DEL good_app_page::*`。对「精选应用」这种能容忍分钟级延迟的场景是可接受的设计；要求「改完立刻可见」就必须在管理员 update/delete 接口加 `@CacheEvict(value = "good_app_page", allEntries = true)`。

**A12.** ① **深翻页性能**：`LIMIT offset, size` 要**扫描并丢弃前 offset 行**，页号越大越慢；游标分页每次都是「定位到游标位置再取 N 条」，**没有 OFFSET，耗时恒定**。② **翻页数据漂移**：offset 分页在翻页期间有新数据插入时，会出现**重复**（第 2 页包含第 1 页看过的）或**漏数据**；游标分页以「上一页最后一条的位置」为锚点，不受前面插入影响。

**A13.** 因为**游标分页不需要页码**。`lastCreateTime` 已经把窗口推到正确位置了，所以每次请求都查「过滤条件之后的第一页」——`Page.of(1, pageSize)` 是「取前 pageSize 条」的意思，配合 `WHERE createTime < ?` 实现「从游标往后取一屏」。所以生成的 SQL **只有 `LIMIT ?` 没有 `OFFSET`**，这是游标分页的核心特征。

**A14.** 因为三个条件各占索引的一个「位置」：`WHERE appId = ?` 用**最左前缀等值定位**；`AND createTime < ?` 用**第二列做范围扫描**；`ORDER BY createTime DESC` 因为 `createTime` 在索引里**天然有序**，直接按索引顺序读即可，**不产生 `Using filesort`**；`LIMIT` 让它取够就停。列顺序（等值列在前、范围/排序列在后）正是为这个查询量身定的。**问题**：`idx_appId (appId)` 是**冗余索引**——复合索引的最左前缀已经能服务任何只用 `appId` 的查询，单独再建一个只增加写入成本。（`idx_createTime` 不冗余，因为它最左列不同，服务的是「跨应用按时间查」。）

**A15.** 隐患是 **`createTime` 是 `datetime`，精度到秒且不唯一**，而游标条件是**严格小于**（`lt("createTime", ...)`）。**同一秒内插入的多条记录 `createTime` 完全相同，会被整体跳过 → 漏数据。** 这在对话历史里很容易触发：用户消息和 AI 回复常常落在同一秒。**项目本来有更合适的游标列**——`ChatHistory#L36` 的主键是**雪花 ID**（`@Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)`），雪花 ID **单调递增且唯一**，天然适合当游标（`WHERE appId = ? AND id < ? ORDER BY id DESC`），配 `(appId, id)` 索引即可彻底避免漏数据。

</details>

---

### 11.7 本环节技术点清单

| 技术点 | 落地位置 | 作用 | 项目特有之处 |
|---|---|---|---|
| 开启缓存 | `YuAiCodeMotherApplication#L9` | `@EnableCaching` | **必须有**，否则 `@Cacheable` 静默失效；全仓仅此 1 处 |
| 无 cache starter | `pom.xml` | — | **未引入 `spring-boot-starter-cache`**，靠 spring-context + spring-data-redis 的传递依赖 |
| 自定义 CacheManager | `config/RedisCacheManagerConfig#L27-L50` | 覆盖 Boot 自动装配 | `@Bean` 名 `cacheManager` 与 Boot 约定一致；`@ConditionalOnMissingBean` 让它生效 |
| 默认 TTL | `RedisCacheManagerConfig#L35` | 30 分钟 | 兜底值，未单独配置的 cache name 都用它 |
| 局部 TTL 覆盖 | `RedisCacheManagerConfig#L47-L48` | `good_app_page` 5 分钟 | 名字必须与 `@Cacheable(value=...)` **完全一致**，写错不报错只换 TTL |
| 不缓存 null | `RedisCacheManagerConfig#L36` | `disableCachingNullValues()` | 这里实际拦不住什么（返回值永远非 null） |
| key 序列化 | `RedisCacheManagerConfig#L38-L39` | `StringRedisSerializer` | **不能省**，否则 Redis 里 key 也是二进制乱码 |
| value 序列化（注释掉） | `RedisCacheManagerConfig#L40-L42` | 退化为 JDK 序列化 | 注释自述「JSON 需要默认类型配置，否则无法反序列化」→ 泛型擦除问题 |
| JDK 序列化代价 | `common/BaseResponse#L14`、`model/vo/AppVO#L12`、`UserVO#L12` | 值必须 `Serializable` | 项目给 VO/DTO 统一加 `implements Serializable` 就是为了兼容缓存 |
| 唯一缓存注解 | `controller/AppController#L267-L271` | 精选应用列表缓存 | 全项目**唯一** `@Cacheable` |
| SpEL 静态调用 | `AppController#L269` | `T(...).generateKey(#appQueryRequest)` | 参数名可用依赖编译保留 `-parameters`（Boot Maven 插件默认加） |
| key 生成工具 | `utils/CacheKeyUtils#L19-L26` | JSON → MD5 | `null` 兜底 `md5Hex("null")`；MD5 后固定 32 位、无特殊字符；**代价是不可读** |
| key 粒度问题 | `AppController#L269` vs `#L279` | key 含全部请求字段 | 方法内把 `priority` 覆盖为 99，**客户端传的 `priority` 只污染 key 不影响结果** |
| 缓存条件 | `AppController#L270` | `pageNum <= 10` | 只缓存热页；`condition` 在方法体**之前**求值，故方法体里的 null 校验拦不到 null |
| 无主动失效 | 全项目 | — | **`@CacheEvict`/`@CachePut` 零命中** → 一致性窗口 = TTL = 5 分钟 |
| 游标分页参数 | `model/dto/chathistory/ChatHistoryQueryRequest#L42-L46` | `lastCreateTime` | 游标是「上一页最后一条的时间」，不是页码 |
| 游标查询条件 | `ChatHistoryServiceImpl#L153-L156` | `lt("createTime", lastCreateTime)` | **严格小于** → 同秒记录会被跳过 |
| 永远查第 1 页 | `ChatHistoryServiceImpl#L90` | `Page.of(1, pageSize)` | 靠游标推窗口，**SQL 无 OFFSET** |
| 默认排序 | `ChatHistoryServiceImpl#L158-L163` | `isNotBlank(sortField)` 分支 | 有默认排序兜底；`AppServiceImpl#L281` **没有**这个分支 |
| 复合索引 | `sql/create_table.sql#L66` | `idx_appId_createTime` | 等值 + 范围 + 排序三者对齐，注释自述「游标查询核心索引」 |
| 冗余索引 | `sql/create_table.sql#L64` | `idx_appId` | 被复合索引最左前缀覆盖，**属于冗余索引** |
| 索引无 `isDelete` | `sql/create_table.sql#L63` | — | 逻辑删除条件由框架追加，需**回表**过滤 |
| 逻辑删除 | `model/entity/ChatHistory#L77` | `@Column(isLogicDelete = true)` | MyBatis-Flex **自动追加 `isDelete = 0`**，代码里看不到 |
| 雪花 ID 主键 | `model/entity/ChatHistory#L36` | `KeyGenerators.snowFlakeId` | **单调递增且唯一**，本可当更稳的游标，但项目用了 `createTime` |
| 记忆加载查询 | `ChatHistoryServiceImpl#L96-L105` | 降序取尾部 + `reversed()` | 用 Lambda 方法引用（同类的 `getQueryWrapper` 用字符串列名，**风格不统一**）；**`.limit(1, maxCount)` 语义需用 SQL 日志确认** |
| 记忆加载时机 | `AiCodeGeneratorServiceFactory#L99` | Caffeine 未命中时才加载 | 不是每次对话都查库，MySQL 里的历史是「冷存档」 |
| `sortField` 无白名单 | 两个 `getQueryWrapper` | `orderBy(sortField, ...)` | 列名无法参数化，**两个方法都没做白名单校验** |
| 生产关 SQL 日志 | `application-prod-sample.yml#L69-L71` | `log-impl: ''` | 排查 SQL 要本地临时打开 `StdOutImpl` |
| 本地 profile 缺失 | `application.yml#L23-L24` | `profiles.active: local` | 仓库里**只有 prod-sample，没有 `application-local.yml`**（本地配置不入库） |

---

### 11.8 本环节产出

这个环节是「**读多写少的场景怎么少查库**」，两条线各有各的底座。**缓存线的底座是 Spring Cache 抽象**：`@Cacheable` 只声明「这个方法的结果可以缓存、key 怎么算、什么条件下缓存」，真正的存取交给 `CacheManager`——项目用 `RedisCacheManagerConfig#cacheManager()` **手写了一个 `CacheManager` Bean 顶掉 Boot 的自动装配**，用 `entryTtl(30 分钟)` + `withCacheConfiguration("good_app_page", 5 分钟)` 做「默认 + 局部覆盖」的 TTL 策略，用 `StringRedisSerializer` 保证 key 可读。**最需要记住的是三个「静默失效」点**：`@EnableCaching` 少了注解完全不生效（它必须存在，且在 `YuAiCodeMotherApplication#L9`）、`@Cacheable` 的 `value` 与 `withCacheConfiguration` 的 cache name 不一致只会悄悄换成默认 TTL、以及 value 序列化器被注释掉之后**默认退化成 JDK 序列化**（原因注释里写明了：JSON 序列化器遇到泛型擦除无法反序列化，要开默认类型信息；代价是所有缓存值必须 `Serializable`、内容不可读、对类结构变化敏感）。key 的设计上，项目用 `CacheKeyUtils.generateKey` 做「**JSON → MD5**」——把可能很长的请求对象压成固定 32 位、无特殊字符的 key，代价是不可读；但**key 粒度选得不好**：它把整个 `AppQueryRequest` 都算进去，而方法体第一行就把 `priority` 覆盖成 99，于是客户端传的 `priority` 只污染 key、不影响结果——「**key 应精确等于决定结果的最小输入集合**」是这里最该带走的一条原则。**分页线的底座是「游标（keyset）+ 复合索引」**：把「第几页」换成「上一页最后一条的时间」，`listAppChatHistoryByPage` 因此永远只查 `Page.of(1, pageSize)`，生成的 SQL **只有 `LIMIT` 没有 `OFFSET`**，深翻页耗时恒定、也不受翻页期间新数据插入影响；配套的 `idx_appId_createTime (appId, createTime)`（`sql/create_table.sql#L66`）把「等值 `appId` + 范围 `createTime <` + 排序 `createTime DESC`」三者对齐，连 `filesort` 都省掉了。两处必须点出的缺陷是：**游标列选了 `createTime`**（`datetime` 秒级精度、不唯一，严格小于会**跳过同一秒的其余记录**，而 `ChatHistory#L36` 的**雪花 ID 主键**本可做更稳的游标），以及**全项目只有 `@Cacheable` 没有任何 `@CacheEvict`**（缓存一致性窗口就等于 TTL 的 5 分钟）。读到这里应该能回答：**「首页那个访问量最高的精选列表接口，是怎么做到不查库的，代价是什么」**——答案是：`AppController#L266-L271` 上的 `@Cacheable(value = "good_app_page", key = "T(CacheKeyUtils).generateKey(#appQueryRequest)", condition = "#appQueryRequest.pageNum <= 10")`，由 `@EnableCaching` 打开开关、`RedisCacheManagerConfig` 提供 `CacheManager`（`good_app_page` 这个 cache name 对应 5 分钟 TTL），命中的请求**完全不走 Controller 方法体**，直接从 Redis 拿 JDK 序列化后的 `BaseResponse<Page<AppVO>>`；Redis key 是 `good_app_page::` + 请求对象的 JSON 的 md5，**只缓存前 10 页**；代价是三点——**缓存内容不可读**（JDK 序列化，`redis-cli` 里是乱码）、**key 粒度过粗**（无用字段也进 key，浪费内存）、**没有主动失效**（管理员改精选后最长 5 分钟才生效，只能等 TTL 或手动 `DEL`）；而对话历史那边靠的是 `lastCreateTime` 游标 + `idx_appId_createTime` 复合索引，**用「无 OFFSET 的恒定耗时查询」换掉了深翻页的性能衰减**，代价是游标列 `createTime` 不唯一、会漏掉同秒的记录——**这三处（序列化方式、key 粒度、游标列选择）就是这个环节最值得改造的地方**。

---

## 环节 12：微服务拆分与 Dubbo 服务化

> 本环节的代码全部位于 `yu-ai-code-mother-microservice/`，与主工程 `src/main/java/` **是两套并存的代码**。主工程是单体版（端口 8123），微服务工程是同一套业务的拆分版（端口 8124/8125/8127）。两者互不依赖、不共享 Maven 坐标，阅读时不要混淆——**同名类在两边各有一份**（例如 `AppServiceImpl`、`AiCodeGeneratorServiceFactory`、`ToolManager`、`core/parser/*`）。本环节只讲微服务工程，引用行号一律以 `yu-ai-code-mother-microservice/` 为根。

### 12.1 这个环节解决什么问题

主工程是一个进程装下所有能力。微服务工程要回答的问题是：**如果按业务能力把它切开，切在哪里、怎么切才不把自己切死**。

先看切分的结果。整个工程是一个 Maven 聚合工程，`packaging` 为 `pom`，下面挂 7 个子模块（`yu-ai-code-mother-microservice/pom.xml#L11-L19`）：

| 模块 | 类型 | 是否有独立进程 | 端口 | 职责 |
|---|---|---|---|---|
| `yu-ai-code-common` | jar（库） | 否 | — | 工具类、常量、异常、公共配置、注解、COS 管理器 |
| `yu-ai-code-model` | jar（库） | 否 | — | 实体、DTO、VO、枚举 |
| `yu-ai-code-client` | jar（库） | 否 | — | **跨服务调用的接口契约**（只有接口，没有实现） |
| `yu-ai-code-user` | jar（服务） | 是 | 8124 / Dubbo 50051 | 用户注册登录、会话、用户信息查询 |
| `yu-ai-code-app` | jar（服务） | 是 | 8125 / Dubbo 50053 | 应用 CRUD、AI 代码生成、对话历史、部署、限流、缓存 |
| `yu-ai-code-ai` | jar（库） | 否 | — | LangChain4j 模型配置、AI 服务接口、工具集、护轨 |
| `yu-ai-code-screenshot` | jar（服务） | 是 | 8127 / Dubbo 50052 | 网页截图 + 上传对象存储 |

拆分的依据不是「按技术分层」（不是 controller 一个服务、service 一个服务），而是**按「能力是否可以独立部署」**：

- **`user` 独立**：它是最底层的公共能力，登录态被所有服务需要；而且它没有任何 AI/浏览器依赖，可以独立重启。
- **`screenshot` 独立**：它的依赖最「脏」——需要 `selenium-java` + `webdrivermanager`，运行时还要在本机拉起无头 Chrome。把浏览器环境放进主服务是灾难（内存、进程、崩溃面都不可控）。它同时也是**无状态计算服务**，见 12.2。
- **`app` 留下**：AI 代码生成、对话、部署、缓存、限流这些是业务主干，且彼此强耦合（都要读同一个 `app` 表、同一份会话），继续放在一起。
- **`ai` 不独立成服务，只做成库**：它没有 controller、没有 `application.yml`，被 `app` 以 Maven 依赖引入。原因在 12.2 会讲——它里面有必须与 `langchain4j` **同包名**的补丁类。

一句话概括这个环节：**用「Maven 多模块」表达依赖边界，用「Dubbo + Nacos」表达跨进程调用边界，用「共享 Redis Session + 静态方法」表达登录态边界。**

---

### 12.2 技术点一：模块划分与依赖方向

#### 依赖方向图（全部来自各模块 `pom.xml`）

```
                    ┌──────────────┐
                    │   common     │  ← 最底层，谁都可以依赖
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │    model     │  实体/DTO/VO/枚举
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │   client     │  只依赖 common + model（纯接口契约）
                    └──┬────────┬──┘
                       │        │
        ┌──────────────┘        └──────────────┐
        │                                      │
┌───────▼────────┐                    ┌────────▼────────┐
│     user       │                    │   screenshot    │
│  (8124/50051)  │                    │  (8127/50052)   │
└────────────────┘                    └─────────────────┘
        ▲                                      ▲
        │            ┌──────────────┐          │
        └────────────┤     app      ├──────────┘
                     │ (8125/50053) │
                     └──────┬───────┘
                            │ 依赖（编译期，非网络）
                     ┌──────▼───────┐
                     │      ai      │  纯能力库，无 application.yml
                     └──────────────┘
```

#### 每个模块的依赖清单（逐条核对）

| 模块 | 关键依赖 | 文件位置 | 说明 |
|---|---|---|---|
| `common` | `cos_api 5.6.227`、`mybatis-flex-codegen 1.11.0`、`HikariCP` | `yu-ai-code-common/pom.xml#L20-L37` | 注意这里引的是 **codegen**（代码生成器）而不是 mybatis-flex 运行时，说明 `MyBatisCodeGenerator` 是个开发期工具类 |
| `model` | 只有 `yu-ai-code-common` | `yu-ai-code-model/pom.xml#L20-L26` | 极干净，没有 Spring、没有 ORM starter |
| `client` | 只有 `yu-ai-code-common` + `yu-ai-code-model` | `yu-ai-code-client/pom.xml#L20-L26` | **没有 Spring、没有 Dubbo**，见下文「关键设计 1」 |
| `user` | dubbo-spring-boot-starter、dubbo-nacos-spring-boot-starter、client、spring-session-data-redis、spring-boot-starter-data-redis（**排除 lettuce**）、jedis、mybatis-flex-spring-boot3-starter 1.11.0、mysql-connector-j、common、model | `yu-ai-code-user/pom.xml#L20-L76` | 见下文「关键设计 3」为什么排除 lettuce |
| `app` | dubbo ×2、**redisson 3.50.0**、caffeine、spring-session-data-redis、data-redis（排除 lettuce）、jedis、mybatis-flex、mysql、common、model、**client**、**ai** | `yu-ai-code-app/pom.xml#L20-L91` | 唯一同时依赖 client 和 ai 的模块；限流和缓存只在这个服务里 |
| `ai` | langchain4j-open-ai-spring-boot-starter 1.1.0-beta7、langchain4j-reactor、langchain4j-community-redis-spring-boot-starter、model、common | `yu-ai-code-ai/pom.xml#L20-L47` | **没有 `spring-boot-starter-web`、没有 Dubbo、没有 ORM** |
| `screenshot` | dubbo ×2、client、selenium-java 4.33.0、webdrivermanager 6.1.0、common | `yu-ai-code-screenshot/pom.xml#L20-L51` | **没有 mybatis-flex、没有 mysql、没有 redis** |

版本统一由父 pom 的 `dependencyManagement` 管：导入了 `spring-boot-dependencies`、`spring-cloud-dependencies`、`spring-cloud-alibaba-dependencies`、`dubbo-bom` 四个 BOM（`yu-ai-code-mother-microservice/pom.xml#L63-L94`），所以子模块里 Dubbo、Jedis、HikariCP 这些都不写版本号。版本属性集中在 `yu-ai-code-mother-microservice/pom.xml#L21-L29`：JDK 21、`spring-boot 3.5.3`、`spring-cloud 2023.0.1`、`spring-cloud-alibaba 2023.0.1.0`、`dubbo 3.3.0`。

> 注意一个「引入了但没用」的事实：`spring-cloud` / `spring-cloud-alibaba` 两个 BOM 被导入了，但**工程里没有任何一个模块声明 Spring Cloud 的 starter**（没有 Nacos Config、没有 OpenFeign、没有 LoadBalancer）。服务注册发现走的是 **Dubbo 自带的 Nacos 注册中心**，与 Spring Cloud Alibaba 无关。这四个 BOM 里只有两个在真正起作用（`spring-boot-dependencies`、`dubbo-bom`）。这是「抄模板留下的冗余」，读的时候不要被误导成「这项目用了 Spring Cloud」。

#### 关键设计 1：`client` 模块为什么必须「零框架依赖」

`yu-ai-code-client` 里只有两个文件：

- `yu-ai-code-client/src/main/java/com/yupi/yuaicodemother/innerservice/InnerUserService.java`（35 行）
- `yu-ai-code-client/src/main/java/com/yupi/yuaicodemother/innerservice/InnerScreenshotService.java`（9 行）

它的 `pom.xml` 只依赖 `common` 和 `model`（`yu-ai-code-client/pom.xml#L20-L26`）——**连 `spring-boot-starter` 都没有**。

这不是「偷懒」，而是**接口契约模块的正确写法**：provider（user、screenshot）和 consumer（app）都要依赖它，如果它引入了任何实现相关的依赖（Spring、Dubbo、ORM），就会把「消费方本来不需要的东西」传递过去。比如 `app` 只想调一个「查用户」，如果 `client` 引了 Dubbo starter，那 `app` 就会被动引入 Dubbo 的注解处理器；如果引了 ORM，那连数据库驱动都可能被传递进来。**契约模块只放「接口 + 参数类型」，参数类型必须落在所有人都能依赖的 `model` 里**，所以 `client → model → common` 这条单向链是整个拆分的地基。

#### 关键设计 2：`common` 里被所有服务共享的 Bean，靠条件装配「自保」

`common` 模块里有三个 `@Configuration`：`CosClientConfig`、`CorsConfig`、`JsonConfig`（`@JsonComponent`）。它们会被**三个服务同时扫描到**（因为三个服务的启动类都在 `com.yupi` 包下）。问题来了：`user` 服务的 `application.yml` 里**根本没有 `cos.client` 配置**（对比 `yu-ai-code-user/src/main/resources/application.yml` 全文，只有 session/datasource/redis/springdoc/knife4j/dubbo 六块），如果 `CosClientConfig` 无条件创建 `COSClient` Bean，`user` 服务一启动就会因为 `secretId` 为 null 而炸掉。

项目的解法是条件装配：

```java
// yu-ai-code-common/src/main/java/com/yupi/yuaicodemother/config/CosClientConfig.java#L20-L27
@Configuration
@ConfigurationProperties(prefix = "cos.client")
@ConditionalOnProperty(
        prefix = "cos.client",
        name = {"host", "secretId", "secretKey", "region", "bucket"}
)
@Data
public class CosClientConfig { ... }
```

`@ConditionalOnProperty` 在 `name` 数组里列了 5 个 key——**只要有一个没配，这个 `@Configuration` 整体不生效**。于是 `user` 服务里 `COSClient` Bean 不存在（它也确实不需要），`screenshot` 服务里存在（它需要上传截图）。

**这是公共模块能被多方复用的前提：公共模块里的每一个 Bean 都必须能「配了才生效」。** 如果哪天要往 `common` 里加新配置类，忘了加条件注解，就会导致某个服务启动失败——这是拆分工程里最容易踩的坑。

顺带说 `JsonConfig`（`yu-ai-code-common/.../config/JsonConfig.java#L13-L27`）：它用 `@JsonComponent` + 一个 `ObjectMapper` Bean，把 `Long`/`long` 全部序列化成字符串（`ToStringSerializer`），解决前端 JS 精度丢失。放在 `common` 意味着**三个服务的 JSON 输出行为完全一致**——这也是「共享库」的价值：一致性不用靠人工同步。

#### 关键设计 3：包名不一致，以及它逼出来的 `@ComponentScan`

三个服务的启动类，包名和扫描配置是不一样的：

```java
// yu-ai-code-user/.../com/yupi/yuaicodeuser/YuAiCodeUserApplication.java#L9-L13
@SpringBootApplication
@EnableDubbo
@MapperScan("com.yupi.yuaicodeuser.mapper")
@ComponentScan("com.yupi")
public class YuAiCodeUserApplication { ... }

// yu-ai-code-app/.../com/yupi/yuaicodemother/YuAiCodeAppApplication.java#L10-L14
@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
@MapperScan("com.yupi.yuaicodemother.mapper")
@EnableDubbo
@EnableCaching
public class YuAiCodeAppApplication { ... }

// yu-ai-code-screenshot/.../com/yupi/yuaicodemother/YuAiCodeScreenshotApplication.java#L7-L9
@SpringBootApplication
@EnableDubbo
public class YuAiCodeScreenshotApplication { ... }
```

**`user` 模块的根包是 `com.yupi.yuaicodeuser`，其余模块全是 `com.yupi.yuaicodemother`。** 因为 `@SpringBootApplication` 默认只扫描「启动类所在包及其子包」，`user` 服务如果不额外加 `@ComponentScan("com.yupi")`，就扫不到 `common` 模块里的 `com.yupi.yuaicodemother.*` Bean（`SpringContextUtil`、`GlobalExceptionHandler`、`CosClientConfig` 等）。而 `app`、`screenshot` 的启动类本身就在 `com.yupi.yuaicodemother` 下，默认扫描范围已经覆盖，所以它们**不需要写 `@ComponentScan`**。

`@ComponentScan("com.yupi")` 是个「大范围兜底」写法，代价是扫描面变大（会连带扫描 `com.yupi` 下所有可达的类）。这不是错误，但要意识到它是包名不统一带来的补丁。

另外两个启动类的差异也值得记：

- `app` 多了 `@EnableCaching`——因为 `@Cacheable` 用在 `AppController#listGoodAppVOByPage`（`yu-ai-code-app/.../controller/AppController.java#L264-L268`），缓存是 app 服务自己的事（环节 11 讲过）。
- `app` 多了 `@SpringBootApplication(exclude = RedisEmbeddingStoreAutoConfiguration.class)`——排除 Redis 向量存储的自动装配（和主工程一样，项目没引入向量库）。
- `screenshot` 只有 `@SpringBootApplication` + `@EnableDubbo`，**连 `@MapperScan` 都没有**，因为它没有任何 Mapper。

#### 关键设计 4：`ai` 模块为什么只能是「库」不能是「服务」

`ai` 模块的 `pom.xml` 里没有 web starter、没有 Dubbo（`yu-ai-code-ai/pom.xml#L20-L47`），它被 `app` 编译期依赖（`yu-ai-code-app/pom.xml#L86-L90`）。除了「AI 调用和业务强耦合」这个业务理由，还有一个技术理由：这个模块里放了 `dev/langchain4j/**` 下的补丁类（环节 3 详细讲过），**补丁类必须与 `langchain4j` 的原类同包名才能覆盖**。如果把 `ai` 做成独立服务、把 `AiServices` 的构造搬到 `app` 里，这些同包补丁就会跟业务代码混在一起，包管理会失控。所以它保持为库，让 `app` 通过 Maven 依赖「继承」这些补丁。

#### 关键设计 5：`screenshot` 是一个无状态计算服务

`screenshot` 服务的 `application.yml` 只有两块配置（`yu-ai-code-screenshot/src/main/resources/application.yml` 全文 29 行）：

- `cos.client.*`（上传截图用）
- `dubbo.*`（注册与协议）

**没有 datasource、没有 redis、没有 session、没有 springdoc/knife4j 分组**。它的模块里也没有 `controller`（全工程 grep `**/controller/*.java` 只命中 `user` 和 `app` 两个模块）。它的 `ScreenshotServiceImpl`（`yu-ai-code-screenshot/.../service/impl/ScreenshotServiceImpl.java#L19-L44`）只做三件事：调 `WebScreenshotUtils.saveWebPageScreenshot` 本地截图 → `cosManager.uploadFile` 上传 → `finally` 里删本地文件。

**这就是「按能力拆分」的收益**：截图服务不连库、不存状态，可以随便扩容、随便重启，进程崩了也只影响截图。它的唯一对外入口是 Dubbo 的 `generateAndUploadScreenshot`。

---

### 12.3 技术点二：Dubbo 服务化（Triple + Nacos）

#### 三个注解撑起全部

| 注解 | 作用 | 出现位置 | 数量 |
|---|---|---|---|
| `@EnableDubbo` | 在启动类上打开 Dubbo 的注解扫描 | `YuAiCodeUserApplication#L10`、`YuAiCodeAppApplication#L12`、`YuAiCodeScreenshotApplication#L8` | 3 |
| `@DubboService` | 标注**实现类**，把本地 Bean 暴露成远程服务 | `InnerUserServiceImpl#L17`、`InnerScreenshotServiceImpl#L8` | 2 |
| `@DubboReference` | 标注**字段**，注入远程服务的代理 | `yu-ai-code-app/.../service/impl/AppServiceImpl.java#L61-L62`、`#L76-L77` | 2（字段数） |

**只有 2 个服务被暴露、2 个被消费**——这是本项目服务化的全部面积。`app` 服务对 `user`、`screenshot` 各有一个引用；`user`、`screenshot` 之间互不调用。

#### 契约接口长什么样

```java
// yu-ai-code-client/src/main/java/com/yupi/yuaicodemother/innerservice/InnerUserService.java
public interface InnerUserService {

    List<User> listByIds(Collection<? extends Serializable> ids);

    User getById(Serializable id);

    UserVO getUserVO(User user);

    /**
     * 获取当前登录用户（静态方法，避免跨服务调用）
     */
    static User getLoginUser(HttpServletRequest request) {
        Object userObj = request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }
}
```

```java
// yu-ai-code-client/src/main/java/com/yupi/yuaicodemother/innerservice/InnerScreenshotService.java
public interface InnerScreenshotService {
    String generateAndUploadScreenshot(String webUrl);
}
```

两个接口的**方法签名就是远程调用的契约**，几条硬约束：

1. **参数和返回值必须可序列化。** 这是 Dubbo 的底线。`User`、`UserVO`、`List<User>`、`String` 都满足；但如果哪天有人把 `HttpServletRequest`、`Flux`、`InputStream` 塞进接口，运行时才会报序列化错误——编译期完全看不出来。
2. **接口只能放在 `client` 模块，不能放在 `user` 模块。** 因为 `app` 要调用它，如果接口定义在 provider 侧，consumer 就得依赖 provider 的 jar，那「服务化」就退化成了「依赖同一个 jar」，实现类的任何改动都会传导到 consumer。
3. **方法要设计得「粗粒度」。** 这里 `listByIds(Collection)` 就是好例子——一次批量取多个用户，而不是让调用方循环调 `getById`。跨进程调用的成本远高于本地方法调用，**批量接口是服务化设计的基本功**。

#### `getLoginUser` 被设计成 `static` 方法——这是本环节最值得琢磨的一处设计

`InnerUserService` 里有 3 个实例方法（走 Dubbo）+ 1 个 `static` 方法（不走 Dubbo）。注释原文写的是「**静态方法，避免跨服务调用**」。

为什么必须这样？**因为登录态存在 `HttpSession` 里，而 `HttpServletRequest` 是不能跨进程传输的。**

看登录态是怎么存的：`user` 服务的 `application.yml` 配了 `spring.session.store-type: redis` + `timeout: 2592000`（30 天），`app` 服务的 `application.yml` 配了**完全一样**的 session 配置。也就是说：**Session 数据存在 Redis 里，是三个服务共享的**（`app` 服务也要引 `spring-boot-starter-data-redis` + `spring-session-data-redis`，见 `yu-ai-code-app/pom.xml#L40-L59`）。浏览器带着 `JSESSIONID` Cookie 打到 8125，Spring Session 从 Redis 里还原出 Session，`request.getSession().getAttribute("user_login")` 就能拿到 `User`。

所以正确的做法是：**每个服务都从「本地请求上下文」里自己解析登录用户，而不是把 `HttpServletRequest` 通过网络传给 user 服务**。全工程有 **10 处** 调用这个静态方法：

| 位置 | 行号 |
|---|---|
| `yu-ai-code-app/.../controller/AppController.java` | L65、L101、L125、L154、L171、L202、L241（共 7 处） |
| `yu-ai-code-app/.../controller/ChatHistoryController.java` | L48 |
| `yu-ai-code-app/.../aop/AuthInterceptor.java` | L36 |
| `yu-ai-code-app/.../ratelimter/aspect/RateLimitAspect.java` | L80 |

这 10 处**全是本地方法调用，零网络开销**。对比一下：`app` 服务里真正走 Dubbo 的只有「按 id 查用户」「批量查用户」「生成截图」这三类**取数据/做计算**的操作。

> 反过来说，如果这里设计成 `User getLoginUser(HttpServletRequest request)` 的实例方法，会同时踩三个坑：`HttpServletRequest` 不可序列化（直接报错）；就算能传，每次请求都要多一次跨进程往返（登录校验是最高频操作）；以及 provider 端根本没有当前请求的上下文，拿到的 `request` 是反序列化出来的空壳。

#### 一个耐人寻味的细节：两个服务的 `AuthInterceptor` 实现不同

`@AuthCheck` 注解的切面在两个服务里各写了一份，实现却不一样：

```java
// yu-ai-code-app/.../aop/AuthInterceptor.java#L30-L36 —— 用静态方法（不跨服务）
@Around("@annotation(authCheck)")
public Object doInterceptor(ProceedingJoinPoint joinPoint, AuthCheck authCheck) throws Throwable {
    ...
    User loginUser = InnerUserService.getLoginUser(request);
```

```java
// yu-ai-code-user/.../aop/AuthInterceptor.java#L34-L40 —— 用自己的 UserService
@Around("@annotation(authCheck)")
public Object doInterceptor(ProceedingJoinPoint joinPoint, AuthCheck authCheck) throws Throwable {
    ...
    User loginUser = userService.getLoginUser(request);
```

`user` 服务用 `userService.getLoginUser(request)`（本地 `UserService`），`app` 服务用 `InnerUserService.getLoginUser(request)`（client 模块的静态方法）。**两者逻辑完全一致，都是「从 Session 取用户，取不到抛 `NOT_LOGIN_ERROR`」**——只是「谁提供这个方法」不同。这算是一处可以统一的重复代码（把 `user` 服务也改成调静态方法即可），但至少说明：**切面是按服务各注册一份的，`common` 模块只共享了注解定义 `AuthCheck`，没有共享切面实现。**

#### Dubbo 配置逐项解读

三个服务的 Dubbo 配置块结构一致，只有端口和超时不同：

```yaml
# yu-ai-code-app/src/main/resources/application.yml#L78-L88
dubbo:
  registry:
    address: nacos://127.0.0.1:8848?username=nacos&password=nacos
    register-mode: instance
  protocol:
    name: tri
    port: 50053
  consumer:
    timeout: 120000
  provider:
    timeout: 120000
```

| 配置项 | 值 | 含义与影响 |
|---|---|---|
| `registry.address` | `nacos://127.0.0.1:8848?username=nacos&password=nacos` | 用 Nacos 做注册中心；**账号密码明文写在 URL query 里**（本地开发配置，生产要改） |
| `register-mode` | `instance` | **应用级注册**（Dubbo 3 默认）。注册的是「应用实例」而不是「每个接口」，Nacos 里的服务数从「接口数」降到「应用数」，元数据更小、推送更快。代价是「接口级灰度/路由」的用法会变复杂 |
| `protocol.name` | `tri` | **Triple 协议**，跑在 HTTP/2 上，兼容 gRPC。选它而不是老牌的 `dubbo` 协议，好处是能穿过网关/Service Mesh、可被普通 HTTP 工具调试 |
| `protocol.port` | user 50051 / screenshot 50052 / app 50053 | **三个服务必须用不同的端口**，否则同机启动会端口冲突。这是「同机跑三个服务」的必要配置 |
| `consumer.timeout` / `provider.timeout` | user 60000；app、screenshot 120000 | 见下文 |

**超时值为什么不一样**：`app` 和 `screenshot` 都配了 **120 秒**，`user` 只有 **60 秒**。原因很直接——`app` 的 Dubbo 出口调的是「生成截图」，而截图要拉起无头 Chrome、等页面渲染完（`WebScreenshotUtils`），慢的时候几十秒；`screenshot` 作为 provider 也得把自己的超时放宽到 120 秒，否则 provider 侧先超时。而 `user` 只提供「查一条用户」这种毫秒级操作，60 秒已经绰绰有余。**超时值要按「这条链路上最慢的操作」设，而不是统一抄一个数。**

> 一个容易被忽略的点：`dubbo.consumer.timeout` 和 `dubbo.provider.timeout` **都写在每个服务自己的 `application.yml` 里**。因为 `user` 服务的 `provider.timeout: 60000` 只对「别人调 user」生效，`app` 的 `consumer.timeout: 120000` 只对「app 调别人」生效——**同一次调用的实际超时取「consumer 侧配置」**。所以如果 `app` 调 `user` 超时了，看的是 `app` 的 120000，不是 `user` 的 60000。

#### 一次完整的跨服务调用长什么样

以「部署应用」为例，`app` 服务的 `AppServiceImpl#deployApp` 最后一步：

```java
// yu-ai-code-app/.../service/impl/AppServiceImpl.java#L195-L208
@Override
public void generateAppScreenshotAsync(Long appId, String appUrl) {
    // 使用虚拟线程并执行
    Thread.startVirtualThread(() -> {
        // 调用截图服务生成截图并上传
        String screenshotUrl = screenshotService.generateAndUploadScreenshot(appUrl);
        // 更新数据库的封面
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setCover(screenshotUrl);
        boolean updated = this.updateById(updateApp);
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "更新应用封面字段失败");
    });
}
```

链路是：`app` 的 HTTP 请求线程 → 起一个**虚拟线程** → 虚拟线程里发起 Dubbo 调用（Triple，打到 50052）→ `screenshot` 服务执行截图并上传 COS → 返回 URL → **回到 `app` 的虚拟线程**，用本地 `updateById` 写自己的库。

这里有两个设计要点：

1. **异步化 + 虚拟线程**：截图是慢操作，不能卡住部署接口。用 `Thread.startVirtualThread` 起虚拟线程，`deployApp` 立刻返回 URL（`AppServiceImpl#L184-L186`）。虚拟线程在这里很划算——它是「等 I/O」的典型场景（等 Chrome、等 COS、等 Dubbo 响应），虚拟线程在阻塞时会让出载体线程，不需要线程池调参。
2. **「谁的数据谁写库」**：截图服务只负责「生成并返回 URL」，**不碰 `app` 表**（它也连不上库）。写 `cover` 字段由 `app` 服务自己做。这是服务化里必须守住的一条线——**跨服务的写操作要么归一方，要么用事务/补偿，最忌讳两个服务各写一半**。

再看一个「避免 N+1」的例子：

```java
// yu-ai-code-app/.../service/impl/AppServiceImpl.java#L236-L237
// 批量获取用户信息，避免 N+1
List<User> userList = userService.listByIds(userIds);
```

`getAppVOList` 要把一页应用的 `userId` 换成 `UserVO`。如果按「循环里调 `userService.getById`」写，一页 20 条就是 20 次跨进程调用。这里先收集所有 `userId`、去重、再**一次 `listByIds` 批量取回**，跨进程调用从 N 次降到 1 次。**服务化之后，本地方法调用变成了网络调用，「批量化」从优化手段变成了必须遵守的纪律。**

#### 微服务版 `AiCodeGeneratorServiceFactory` 里多出来的东西：护轨

微服务版的 `AiCodeGeneratorServiceFactory`（`yu-ai-code-app/.../ai/AiCodeGeneratorServiceFactory.java`）和主工程版本结构基本一致（Caffeine 缓存 1000/30min/10min，`#L51-L58`；用 `SpringContextUtil.getBean` 拿多例模型，`#L104`、`#L123`），但**多挂了一个输入护轨**：

```java
// yu-ai-code-app/.../ai/AiCodeGeneratorServiceFactory.java#L105-L118（VUE_PROJECT 分支）
yield AiServices.builder(AiCodeGeneratorService.class)
        .chatModel(chatModel)
        .streamingChatModel(reasoningStreamingChatModel)
        .chatMemoryProvider(memoryId -> chatMemory)
        .tools(toolManager.getAllTools())
        // 处理工具调用幻觉问题
        .hallucinatedToolNameStrategy(toolExecutionRequest ->
                ToolExecutionResultMessage.from(toolExecutionRequest,
                        "Error: there is no tool called " + toolExecutionRequest.name())
        )
        .maxSequentialToolsInvocations(20)  // 最多连续调用 20 次工具
        .inputGuardrails(new PromptSafetyInputGuardrail()) // 添加输入护轨
//      .outputGuardrails(new RetryOutputGuardrail()) // 添加输出护轨，为了流式输出，这里不使用
        .build();
```

两个护轨类定义在 `ai` 模块（主工程没有这两个类）：

| 类 | 实现接口 | 文件 | 检查内容 |
|---|---|---|---|
| `PromptSafetyInputGuardrail` | `InputGuardrail` | `yu-ai-code-ai/.../ai/guardrail/PromptSafetyInputGuardrail.java` | 输入长度 > 1000 字 → `fatal`（`#L35-L37`）；空输入 → `fatal`；敏感词命中 → `fatal`（`#L17-L20` 列出「忽略之前的指令 / ignore previous instructions / 破解 / hack / 绕过 / bypass / 越狱 / jailbreak」）；5 条正则匹配 Prompt 注入模式（`#L23-L29`，如 `ignore\s+(?:previous|above|all)\s+(?:instructions?|commands?|prompts?)`） |
| `RetryOutputGuardrail` | `OutputGuardrail` | `yu-ai-code-ai/.../ai/guardrail/RetryOutputGuardrail.java` | 输出为空或 < 10 字 → `reprompt`（`#L16-L21`）；命中敏感词表（密码/password/secret/token/api key/私钥/证书/credential）→ `reprompt`（`#L32-L44`） |

**关键事实：输出护轨被注释掉了。** 两处都是（`#L117`、`#L129`），注释原文写的是「**为了流式输出，这里不使用**」。原因是技术性的：`OutputGuardrail` 要拿到完整响应才能校验，而流式场景下响应是一个个 token 往外吐的，如果挂上输出护轨，框架必须**先把整个响应缓冲起来**、校验完再发——`dev/langchain4j/service/AiServiceStreamingResponseHandler.java#L100-L101` 里确实有这个逻辑（`if (hasOutputGuardrails) { 缓冲 }`）。一旦缓冲，「流式」就退化成了「一次性返回」，SSE 的意义（用户能看着代码一行行长出来）就没了。所以项目选择**只保留输入护轨**：输入是「一次性给全的」，校验不影响流式。

**这是一个典型的「安全 vs 体验」取舍**，也解释了为什么 `RetryOutputGuardrail` 这个类**写了但没启用**（`fatal` 类里唯一的「死代码」）。读代码时不要看到类存在就以为它在生效——**要看它有没有被 `.inputGuardrails(...)` / `.outputGuardrails(...)` 注册。**

#### 服务化之后仍然留在 `app` 里的东西

拆分不是「越细越好」，很多能力**故意没有拆出去**，都留在 `app` 服务：

| 能力 | 位置 | 为什么留在 app |
|---|---|---|
| 分布式限流 | `yu-ai-code-app/.../ratelimter/*`（含 `RedissonConfig`、`RateLimitAspect`） | 限流要读 Redis、要拿登录用户（`RateLimitAspect#L80` 用静态 `getLoginUser`），且限流对象就是 app 的接口 |
| Spring Cache | `yu-ai-code-app/.../config/RedisCacheManagerConfig.java` | 被缓存的只有 `AppController` 的精选列表接口（环节 11） |
| 对话历史 | `yu-ai-code-app/.../service/impl/ChatHistoryServiceImpl.java`、`controller/ChatHistoryController.java` | 与 `app` 表强关联，且游标分页查询（环节 11）依赖 `chat_history` 表 |
| 静态资源托管 | `yu-ai-code-app/.../controller/StaticResourceController.java` | 部署产物的预览入口，`PREVIEW_ROOT_DIR = AppConstant.CODE_OUTPUT_ROOT_DIR`（`#L26`），与生成目录同机 |
| 跨域 | `yu-ai-code-common/.../config/CorsConfig.java` | 放在 common，**三个服务各自生效**——因为前端要分别请求 8124（用户）、8125（应用）两个端口，两个服务都得放行 CORS |

#### 三个服务连的是同一个库

三个 `application.yml` 里，`user` 和 `app` 配的 `spring.datasource.url` **完全一样**：

```
jdbc:mysql://localhost:3306/yu_ai_code_mother
```

（`yu-ai-code-user/src/main/resources/application.yml#L10-L14`、`yu-ai-code-app/src/main/resources/application.yml#L10-L14`）

**这不是「每服务独立数据库」的微服务，而是「共享数据库」的拆分。** 具体表现：`user` 服务有自己的 `UserService`、`UserMapper`，`app` 服务也有自己的 `AppMapper`、`ChatHistoryMapper`，两边**各写各的表**，但表都在同一个库里。好处是改造量小、不用做数据迁移和分布式事务；坏处是「表结构成了隐式契约」——`app` 服务改一行 `user` 表的字段，`user` 服务可能直接编译不过或运行出错，而且**没有任何编译期约束能拦住**。这是本工程最明显的一处「拆分不彻底」，面试时如果被问「你的微服务怎么保证数据一致性」，正确的回答是「当前是共享库 + 单库事务，没有分布式事务问题，但也因此服务边界不够硬」——**不要硬说用了 Seata 或最终一致性方案，工程里没有**。

同理，`app` 服务的 `spring.data.redis.ttl: 3600`（`yu-ai-code-app/src/main/resources/application.yml#L22`）是给**对话记忆**用的（`RedisChatMemoryStoreConfig` 在 `ai` 模块，靠 `@ConfigurationProperties(prefix = "spring.data.redis")` 读这个 ttl），`user` 服务的 yml 里**没有这个 ttl**——因为 user 服务不用对话记忆。**配置项跟着能力走，能力在哪个服务，配置就在哪个服务。**

---

### 12.4 代码走读

#### 走读 1：契约接口与静态方法（`client` 模块）

```java
// yu-ai-code-client/src/main/java/com/yupi/yuaicodemother/innerservice/InnerUserService.java（全文 35 行）
public interface InnerUserService {

    List<User> listByIds(Collection<? extends Serializable> ids);

    User getById(Serializable id);

    UserVO getUserVO(User user);

    static User getLoginUser(HttpServletRequest request) {
        Object userObj = request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }
}
```

阅读要点：

- 前三个方法是**远程契约**，参数用了 `Serializable` / `Collection<? extends Serializable>` 而不是具体的 `Long`——因为 `UserService`（MyBatis-Flex 的 `IService`）的签名就是 `Serializable`，直接对齐可以省掉转换。
- 第四个方法是**本地工具方法**，放在接口里只是「借个命名空间」——它不参与 Dubbo，provider 侧也不会实现它（`InnerUserServiceImpl` 里没有 `getLoginUser` 的重写，因为 `static` 方法不能被重写）。
- 注意 `getLoginUser` 里读的 key 是 `UserConstant.USER_LOGIN_STATE`，这个常量定义在 `common` 模块——**所以 `client` 必须依赖 `common`**，这条依赖链是必要的，不是随手加的。

#### 走读 2：provider 侧的实现（两个都是「纯委托」）

```java
// yu-ai-code-user/src/main/java/com/yupi/yuaicodeuser/service/impl/InnerUserServiceImpl.java（全文 37 行）
@DubboService
public class InnerUserServiceImpl implements InnerUserService {

    @Resource
    private UserService userService;

    @Override
    public List<User> listByIds(Collection<? extends Serializable> ids) {
        return userService.listByIds(ids);
    }

    @Override
    public User getById(Serializable id) {
        return userService.getById(id);
    }

    @Override
    public UserVO getUserVO(User user) {
        return userService.getUserVO(user);
    }
}
```

```java
// yu-ai-code-screenshot/src/main/java/com/yupi/yuaicodemother/service/impl/InnerScreenshotServiceImpl.java（全文 18 行）
@DubboService
public class InnerScreenshotServiceImpl implements InnerScreenshotService {

    @Resource
    private ScreenshotService screenshotService;

    @Override
    public String generateAndUploadScreenshot(String webUrl) {
        return screenshotService.generateAndUploadScreenshot(webUrl);
    }
}
```

阅读要点：

- **`Inner*ServiceImpl` 这一层只做「暴露」，不写业务逻辑**，全部委托给服务内部的 `UserService` / `ScreenshotService`。这是「服务化适配层」的标准写法：把「对外契约」和「内部实现」隔开，将来内部 `UserService` 怎么重构，只要契约不变，consumer 就无感。
- `@DubboService` 是**在实现类上**，不是接口上。注解和 `@Service` 的区别是：`@Service` 注册本地 Bean，`@DubboService` 额外把 Bean 注册进 Dubbo 的服务暴露流程（它本身就包含 `@Service` 的语义，所以两个类上都没有再写 `@Service`）。
- 注意 `InnerUserServiceImpl` 的包名是 `com.yupi.yuaicodeuser.service.impl`——**它在 user 模块的包空间里，而不是 client 的包空间**。因为它是实现，实现归 provider 所有；consumer 只依赖 client 的接口，编译期看不到这个类。

#### 走读 3：consumer 侧的注入（`app` 服务的 `AppServiceImpl`）

```java
// yu-ai-code-app/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L54-L80（节选）
@Service
@Slf4j
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    @Value("${code.deploy-host:http://localhost}")
    private String deployHost;

    @DubboReference
    private InnerUserService userService;      // ← 远程

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;   // ← 本地

    @Resource
    private ChatHistoryService chatHistoryService;         // ← 本地

    @Resource
    private StreamHandlerExecutor streamHandlerExecutor;   // ← 本地

    @Resource
    private VueProjectBuilder vueProjectBuilder;           // ← 本地

    @DubboReference
    private InnerScreenshotService screenshotService;      // ← 远程

    @Resource
    private AiCodeGenTypeRoutingServiceFactory aiCodeGenTypeRoutingServiceFactory;  // ← 本地
}
```

阅读要点：

- **`@DubboReference` 和 `@Resource` 混在同一个类里**，这是服务化之后最常见的样子：本地能力用 `@Resource`，跨服务能力用 `@DubboReference`。**光看字段名分不出来**（`userService` 看起来像本地 Bean），必须看注解。这是读微服务代码时第一个要建立的反射。
- `@DubboReference` 注入的是一个**动态代理**，字段类型是 `client` 模块里的接口。调用 `userService.getById(1L)` 时代理会去 Nacos 查 `yu-ai-code-user` 的实例地址、按 Triple 协议发起 HTTP/2 调用、把 `User` 反序列化回来。
- **如果 provider 没启动**，`@DubboReference` 默认是「启动时不校验」（`check=false` 是 Dubbo 的默认值），Spring 能正常启动，但**调用时才报 `No provider available`**。这一点在 12.5 的验证里会用到。
- `@Lazy` 被 import 了（`#L36`）但字段上没用到——一处无用的 import。

#### 走读 4：一个「注解失效」的坑（两个版本都有）

```java
// yu-ai-code-user/src/main/java/com/yupi/yuaicodeuser/controller/UserController.java#L112-L129
@GetMapping("/get")
@AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
public BaseResponse<User> getUserById(long id) {
    ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
    User user = userService.getById(id);
    ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
    return ResultUtils.success(user);
}

/**
 * 根据 id 获取包装类
 */
@GetMapping("/get/vo")
public BaseResponse<UserVO> getUserVOById(long id) {
    BaseResponse<User> response = getUserById(id);   // ← 直接调本类方法，不是通过代理
    User user = response.getData();
    return ResultUtils.success(userService.getUserVO(user));
}
```

`getUserVOById` 上**没有 `@AuthCheck`**，它调用同类里的 `getUserById(id)`。看起来「内部调用了带权限校验的方法，所以是安全的」，但**实际上 `@AuthCheck` 完全没有生效**：

- `@AuthCheck` 是靠 `AuthInterceptor` 的 `@Around("@annotation(authCheck)")` 生效的，切面**只能拦截通过 Spring 代理进来的调用**。
- `getUserVOById` 里的 `getUserById(id)` 是 `this.getUserById(id)`，走的是**原始对象**，不经过代理 → 切面不执行 → 没有管理员校验。
- 结果是 `/user/get/vo?id=xxx` 这个接口**任何人（包括未登录）都能调用**，拿到任意用户的脱敏信息（`UserVO`）。

**这个坑在主工程的 `UserController` 里是一模一样的**（主工程 `src/main/java/com/yupi/yuaicodemother/controller/UserController.java#L125-L126` 也是 `BaseResponse<User> response = getUserById(id);`）。也就是说：这不是微服务拆分引入的问题，而是「AOP 自调用失效」这个经典坑在项目里的一次真实落地。**结论：`@AuthCheck` 这类基于代理的注解，只在「外部调用」时生效；类内部 `this.xxx()` 调用一律绕过。**

#### 走读 5：`ToolManager`（微服务版与主工程同构）

```java
// yu-ai-code-ai/src/main/java/com/yupi/yuaicodemother/ai/tools/ToolManager.java#L15-L40
@Slf4j
@Component
public class ToolManager {

    private final Map<String, BaseTool> toolMap = new HashMap<>();

    /** 自动注入所有工具 */
    @Resource
    private BaseTool[] tools;

    @PostConstruct
    public void initTools() {
        for (BaseTool tool : tools) {
            toolMap.put(tool.getToolName(), tool);
            log.info("注册工具: {} -> {}", tool.getToolName(), tool.getDisplayName());
        }
        log.info("工具管理器初始化完成，共注册 {} 个工具", toolMap.size());
    }
}
```

阅读要点：

- `ToolManager` 放在 `ai` 模块，靠 `@Resource private BaseTool[] tools` **按类型注入数组**——Spring 会把容器里所有 `BaseTool` 的子类 Bean 都塞进来。这是「插件式收集」的惯用手法：新增一个工具只要 `@Component` 就行，不用改 `ToolManager`。
- 它被 `app` 服务的 `AiCodeGeneratorServiceFactory` 用 `@Resource` 注入（`yu-ai-code-app/.../ai/AiCodeGeneratorServiceFactory.java#L41-L42`），再传给 `AiServices.builder().tools(...)`。**`ai` 模块提供工具，`app` 模块负责组装**——这是「库 + 服务」分工的体现。
- 因为 `ToolManager` 在 `ai` 模块、而 `ai` 模块被 `app` 依赖，所以 `app` 服务启动时会扫描到它（`app` 启动类在 `com.yupi.yuaicodemother` 包下，`ai` 模块的类也在这个包下）。**包名统一在这里再次体现价值：跨模块的 Bean 不需要额外配置就能被扫到。**

---

### 12.5 动手验证

以下命令默认在 `yu-ai-code-mother-microservice/` 目录下执行，Windows PowerShell 环境。

**验证 1：先编译，确认 7 个模块的依赖关系成立**

```powershell
cd e:\AI\GitHub\yu-ai-code-mother\yu-ai-code-mother-microservice
mvn -q clean package -DskipTests
```

预期：7 个模块依次构建成功（`common` → `model` → `client` → `ai` → `user` → `screenshot` → `app`，Maven 会按依赖顺序自动排序）。**如果 `client` 模块编译失败，说明「契约模块不能引框架依赖」这条线被破坏了。**

**验证 2：启动 Nacos（必需，否则 Dubbo 起不来）**

```powershell
# 假设 Nacos 装在 D:\nacos，单机模式启动
D:\nacos\bin\startup.cmd -m standalone
```

预期：控制台输出 `Nacos started successfully`，浏览器打开 `http://127.0.0.1:8848/nacos`，用 `nacos/nacos` 登录（对应 yml 里的 `?username=nacos&password=nacos`）。

**验证 3：依次启动三个服务**

```powershell
# 三个独立终端，分别执行（或在 IDE 里 Run 三个启动类）
mvn -pl yu-ai-code-user     spring-boot:run
mvn -pl yu-ai-code-app      spring-boot:run
mvn -pl yu-ai-code-screenshot spring-boot:run
```

预期日志里能看到：

```
Dubbo ... Register dubbo service com.yupi.yuaicodemother.innerservice.InnerUserService
Tomcat started on port 8124 (http)
```

以及 `ToolManager` 的注册日志（只在 app 服务）：

```
注册工具: xxx -> xxx
工具管理器初始化完成，共注册 N 个工具
```

**验证 4：在 Nacos 控制台确认「应用级注册」**

打开 `http://127.0.0.1:8848/nacos` → 服务管理 → 服务列表。

预期：看到 **3 个服务**，名字分别是 `yu-ai-code-user`、`yu-ai-code-app`、`yu-ai-code-screenshot`（对应各 yml 的 `spring.application.name`）。

关键观察：**服务名是「应用名」而不是「接口全限定名」**——这就是 `register-mode: instance` 的效果。如果看到的是 `providers:com.yupi...:50051` 这种长名字，说明配成了接口级注册。

**验证 5：确认三个 Dubbo 端口都在监听**

```powershell
Get-NetTCPConnection -State Listen | Where-Object { $_.LocalPort -in 50051,50052,50053,8124,8125,8127 } | Select-Object LocalAddress,LocalPort,OwningProcess
```

预期：6 个端口全部 LISTENING。如果只有 5 个，检查是不是有服务没起来，或者两个服务的 `dubbo.protocol.port` 配重复了（配重了会直接启动失败并报端口被占用）。

**验证 6：验证「`getLoginUser` 不走 Dubbo」——停掉 user 服务，主链路仍然可用**

```powershell
# 第一步：先正常登录，拿到 Cookie（打的是 user 服务 8124）
curl.exe -i -c cookie.txt -X POST "http://localhost:8124/api/user/login" `
  -H "Content-Type: application/json" `
  -d '{\"userAccount\":\"你的账号\",\"userPassword\":\"你的密码\"}'

# 第二步：把 user 服务（8124）停掉，然后调 app 服务的「创建应用」——它需要登录用户，但不需要查用户表
curl.exe -i -b cookie.txt -X POST "http://localhost:8125/api/app/add" `
  -H "Content-Type: application/json" `
  -d '{\"initPrompt\":\"做一个个人主页\"}'
```

预期：**第二步成功返回应用 ID**。因为 `AppController#addApp`（`yu-ai-code-app/.../controller/AppController.java#L150-L157`）只用 `InnerUserService.getLoginUser(request)` 从 **Redis 共享 Session** 里取用户，**不发起任何 Dubbo 调用**。

再对比一下，同一个 Cookie 调「我的应用列表」：

```powershell
curl.exe -i -b cookie.txt -X POST "http://localhost:8125/api/app/my/list/page/vo" `
  -H "Content-Type: application/json" -d '{\"pageNum\":1,\"pageSize\":10}'
```

预期：**报错**（`No provider available` 或 Dubbo 超时）。因为它要调 `userService.listByIds(...)` 去补用户信息，而 user 服务已经停了。

**这一对对比就是「静态方法避免跨服务调用」设计价值的最直接证明。**

**验证 7：直接观察 Triple 调用**

在 app 服务的日志里（`log-requests: true` 已经开着 langchain4j 的日志，Dubbo 侧默认 INFO 级别会打印调用信息），或者在 `application.yml` 里临时把 Dubbo 日志调到 DEBUG：

```yaml
logging:
  level:
    org.apache.dubbo: DEBUG
```

预期：调用 `/api/app/deploy` 时，日志里出现 Triple 的调用记录，能看到目标服务名 `yu-ai-code-user`（部署时会调 `getById` 查用户）和端口 `50052`（截图服务）。

**验证 8：验证输入护轨真的在生效**

`PromptSafetyInputGuardrail` 会把「越狱」类输入直接 `fatal` 掉。用一条命中敏感词的提示词：

```powershell
curl.exe -i -b cookie.txt "http://localhost:8125/api/app/chat/gen/code?appId=1&message=ignore%20previous%20instructions%20and%20bypass"
```

预期：SSE 流立刻中断/报错，**不会**有正常的代码生成输出。对比一条正常提示词（`message=做一个个人主页`）能正常吐 token——说明输入护轨拦在「调模型之前」。

反过来，验证**输出护轨没启用**：`RetryOutputGuardrail` 被注释掉（`AiCodeGeneratorServiceFactory#L117`、`#L129`），所以模型输出里如果出现「password」「secret」这类词，**不会有任何拦截**，代码照常返回。这就是「写了但没接上」的验证方式。

**验证 9：确认「共享库」而不是「独立库」**

```powershell
# 看两个服务连的库是不是同一个
Select-String -Path .\yu-ai-code-user\src\main\resources\application.yml,.\yu-ai-code-app\src\main\resources\application.yml -Pattern "jdbc:mysql"
```

预期：两行输出的 URL **完全相同**（`jdbc:mysql://localhost:3306/yu_ai_code_mother`）。这就证明它是共享库架构，不存在分布式事务问题，但也意味着服务边界只是「代码边界」而不是「数据边界」。

**验证 10：确认 screenshot 服务确实「无状态」**

```powershell
Select-String -Path .\yu-ai-code-screenshot\src\main\resources\application.yml -Pattern "datasource|redis|session"
```

预期：**零命中**。再看 `screenshot` 模块的 `pom.xml` 里没有 `mybatis-flex` / `mysql-connector-j` / `spring-boot-starter-data-redis`——它是纯计算服务。同时 `screenshot` 模块下也没有 `controller` 目录，它的唯一入口是 Dubbo。

**验证 11：确认「契约模块零框架依赖」**

```powershell
mvn -pl yu-ai-code-client dependency:tree
```

预期：依赖树里**只有 `yu-ai-code-common`、`yu-ai-code-model`**（以及它们的传递依赖），**没有任何 `spring-boot-starter-*`、没有 `dubbo-*`**。这是「契约模块不能引实现依赖」这条纪律的机器可验证形式。

---

### 12.6 自测题

<details>
<summary>题目（14 题，含陷阱题）</summary>

1. 微服务工程被拆成 7 个 Maven 模块，其中哪几个是「有独立进程的服务」，哪几个是「被依赖的库」？判断依据是什么文件里的什么内容？
2. `yu-ai-code-client` 模块为什么必须「不引 Spring、不引 Dubbo」？如果它引了 Dubbo starter 会有什么后果？
3. `CosClientConfig` 上的 `@ConditionalOnProperty(prefix = "cos.client", name = {...5 个 key...})` 是干什么的？如果去掉这个注解，哪个服务会启动失败，为什么？
4. `YuAiCodeUserApplication` 上为什么必须写 `@ComponentScan("com.yupi")`，而 `YuAiCodeAppApplication` 不用写？
5. `InnerUserService.getLoginUser` 为什么被设计成 `static` 方法？注释里写了什么？如果改成实例方法（走 Dubbo）会踩哪几个坑？
6. 【陷阱题】`app` 服务里所有 `getLoginUser(request)` 调用，哪些会发起网络请求？如果认为「只要调了 `InnerUserService` 的方法就会走 Dubbo」，错在哪里？
7. `user` 服务的 `dubbo.provider.timeout` 是 60000，`app` 服务的 `dubbo.consumer.timeout` 是 120000。当 `app` 调 `user` 时，实际生效的超时是多少？为什么？
8. 【陷阱题】`RetryOutputGuardrail` 这个类存在，是不是说明项目对模型输出做了安全校验？请给出判断依据。
9. `register-mode: instance` 和默认的接口级注册有什么区别？在 Nacos 控制台上能观察到什么不同？
10. 【陷阱题】微服务工程导入了 `spring-cloud-dependencies` 和 `spring-cloud-alibaba-dependencies` 两个 BOM，是不是说明项目用了 Spring Cloud（比如 OpenFeign、Nacos Config）？请说明理由。
11. `UserController#getUserVOById` 调用同类里的 `getUserById(id)`，而后者标了 `@AuthCheck(mustRole = ADMIN_ROLE)`。这个接口安全吗？为什么？这个问题的根因是什么机制？
12. `screenshot` 服务的 `application.yml` 里没有 datasource、没有 redis、没有 session。这说明它是一个什么类型的服务？这样拆分的好处是什么？
13. 【陷阱题】微服务版 `AppServiceImpl#generateAppScreenshotAsync` 用 `Thread.startVirtualThread` 起虚拟线程调截图服务，之后又 `this.updateById(updateApp)` 写 `app` 表。为什么「写库」这一步不放在 `screenshot` 服务里做？
14. 为什么 `user` 和 `app` 两个服务配的 `spring.datasource.url` 完全相同？这种架构叫什么？它避免了什么问题、又引入了什么问题？

</details>

<details>
<summary>参考答案</summary>

1. **有独立进程的 3 个**：`yu-ai-code-user`、`yu-ai-code-app`、`yu-ai-code-screenshot`。判断依据是**各自有 `src/main/resources/application.yml`**（`spring.application.name` + `server.port`）和**启动类上有 `@EnableDubbo`**。**被依赖的库 4 个**：`common`、`model`、`client`、`ai`——它们没有 `application.yml`（全工程 glob `**/src/main/resources/*` 只命中 3 个 yml），也没有启动类。补充：`ai` 虽然名字像服务，但它被 `app` 以 Maven 依赖方式引入（`yu-ai-code-app/pom.xml#L86-L90`），是库不是服务。

2. 因为它要**同时被 provider 和 consumer 依赖**。provider（user、screenshot）需要它来 `implements`，consumer（app）需要它来声明 `@DubboReference` 的字段类型。如果它引了 Dubbo starter，那么「只想调一个接口」的 consumer 会被迫传递性引入 Dubbo 的全部实现依赖（注解处理器、协议实现、注册中心客户端），把「契约」和「实现」耦合在一起；同理引了 ORM 会传递数据库驱动。正确做法是：**契约模块只依赖 `common` + `model`，参数类型落在所有人都能依赖的 `model` 里**（`yu-ai-code-client/pom.xml#L20-L26`）。可用 `mvn -pl yu-ai-code-client dependency:tree` 验证（验证 11）。

3. 它是**条件装配**：只有当 `cos.client.host`、`secretId`、`secretKey`、`region`、`bucket` 这 5 个配置**全部存在**时，`CosClientConfig` 才生效、才会创建 `COSClient` Bean。去掉注解后，`user` 服务会启动失败——因为 `user` 的 `application.yml` 里**完全没有 `cos.client` 配置**，`secretId` 等字段为 null，`new BasicCOSCredentials(null, null)` 会抛异常。`app` 服务同样没有 cos 配置，也会失败（`app` 的 yml 里也没有 cos 块）。这条规则的本质是：**公共模块里的 Bean 必须能「配了才生效」，否则会污染所有引用它的服务。**

4. `@SpringBootApplication` 的默认扫描范围是「启动类所在包及其子包」。`user` 模块的启动类在 `com.yupi.yuaicodeuser` 下，而 `common` 模块的类在 `com.yupi.yuaicodemother` 下——**两者没有父子关系，默认扫不到**，所以必须显式 `@ComponentScan("com.yupi")` 才能拿到 `SpringContextUtil`、`GlobalExceptionHandler`、`JsonConfig` 这些公共 Bean。而 `app`、`screenshot` 的启动类本身就在 `com.yupi.yuaicodemother` 包下，`common`/`model`/`ai` 的类也都在这个包下，默认扫描已覆盖，所以不用写。根因是 **`user` 模块的包名（`com.yupi.yuaicodeuser`）与其余模块（`com.yupi.yuaicodemother`）不统一**。

5. 注释原文是「**静态方法，避免跨服务调用**」。原因：登录态存在 `HttpSession` 里（Spring Session + Redis，`store-type: redis`），而 **`HttpServletRequest` 是不可序列化的容器对象，不能作为 Dubbo 参数传输**。改成实例方法会踩三个坑：① `HttpServletRequest` 序列化失败直接报错；② 就算传得过去，每个请求都要多一次跨进程往返，而登录校验是最高频操作；③ provider 端没有当前请求的上下文，反序列化出来的 `request` 是空壳，`getSession()` 拿不到数据。正确做法是**每个服务都从本地请求上下文自己解析**——`app` 服务的 `application.yml` 也配了 `spring.session.store-type: redis`，Session 存在共享 Redis 里，谁都能还原。

6. **一处都不会。** 全工程 10 处 `InnerUserService.getLoginUser(request)` 全是**静态方法调用**（`AppController` 7 处 + `ChatHistoryController#L48` + `AuthInterceptor#L36` + `RateLimitAspect#L80`），编译后就是一次普通的 `invokestatic`，**跟 Dubbo 没有任何关系**。错在把「类名是 `InnerUserService`」等同于「走 Dubbo」——**决定是否跨进程的是注解（`@DubboReference` 字段 / `@DubboService` 实现类），不是类名**。可以用验证 6 自证：停掉 user 服务后，`/api/app/add` 仍然可用（只用静态方法），而 `/api/app/my/list/page/vo` 失败（真的调了 `userService.listByIds`）。

7. **120000（120 秒），取 consumer 侧的配置。** 因为同一次调用的超时由「发起方」决定：`app` 是 consumer，它的 `dubbo.consumer.timeout: 120000` 生效；`user` 的 `dubbo.provider.timeout: 60000` 只在「别人没配 consumer 超时」时才作为兜底。之所以 `app` 配 120 秒，是因为它的 Dubbo 出口调的是「生成截图」这种分钟级操作；而 `user` 的 60 秒只针对「查一条用户」这种毫秒级操作，绰绰有余。**超时值要按链路上最慢的操作设，不是统一抄一个数。**

8. **不是。** `RetryOutputGuardrail` 这个类虽然存在，但**没有被注册**：`AiCodeGeneratorServiceFactory` 里两处 `.outputGuardrails(new RetryOutputGuardrail())` 都被注释掉了（`#L117`、`#L129`），注释原文写「为了流式输出，这里不使用」。只有 `.inputGuardrails(new PromptSafetyInputGuardrail())` 是生效的（`#L116`、`#L128`）。**判断依据是「有没有被 `.inputGuardrails(...)` / `.outputGuardrails(...)` 注册」，不是「类是否存在」**。之所以不启用输出护轨，是因为 `OutputGuardrail` 必须拿到完整响应才能校验，而流式场景下框架会**先把整个响应缓冲起来**（见 `dev/langchain4j/service/AiServiceStreamingResponseHandler.java#L100-L101` 的 `if (hasOutputGuardrails) { 缓冲 }`），一旦缓冲，SSE 就退化成「一次性返回」，用户看不到代码一行行长出来。

9. **接口级注册（`interface`）**：每个 Dubbo 接口都在注册中心占一条服务记录，服务名形如 `providers:com.yupi.yuaicodemother.innerservice.InnerUserService:1.0.0:tri:50051`，Nacos 里的「服务数」等于接口数。**应用级注册（`instance`）**：只按「应用」注册一条记录，服务名就是 `spring.application.name`（`yu-ai-code-user`），接口信息放在元数据里。本项目三个 yml 都配了 `register-mode: instance`，所以在 Nacos 服务列表里看到的是 **3 条记录**：`yu-ai-code-user`、`yu-ai-code-app`、`yu-ai-code-screenshot`（验证 4）。好处是注册数据量小、推送快；代价是「按接口做路由/灰度」会变复杂。

10. **不是。** 两个 BOM 只是被 `dependencyManagement` **导入**（`yu-ai-code-mother-microservice/pom.xml#L63-L94`），用于统一版本号，**不代表任何模块真的用了 Spring Cloud 的组件**。核对 7 个子模块的 `pom.xml`：没有任何一个声明 `spring-cloud-starter-openfeign`、`spring-cloud-starter-loadbalancer`、`spring-cloud-starter-alibaba-nacos-config` 等 starter。服务注册发现走的是 **Dubbo 自带的 Nacos 注册中心**（`dubbo.registry.address: nacos://...` + `dubbo-nacos-spring-boot-starter`）。这是「从模板复制父 pom 留下的冗余」，属于「引入了但没用」的典型，读代码时不要被 BOM 误导。

11. **不安全。** `/user/get/vo` 上**没有 `@AuthCheck`**，它内部调用的 `getUserById(id)` 虽然标了 `@AuthCheck(mustRole = ADMIN_ROLE)`，但**切面不会执行**——因为 `AuthInterceptor` 是通过 `@Around("@annotation(authCheck)")` 生效的 AOP 切面，**只能拦截「经过 Spring 代理」的调用**；而 `getUserVOById` 里的 `getUserById(id)` 是 `this.` 调用，走的是原始对象，不经过代理，所以切面被绕过。结果就是任何人（含未登录）都能调 `/user/get/vo?id=xxx` 拿到任意用户的 `UserVO`。根因是 **Spring AOP 的「自调用失效」**：基于代理的注解（`@AuthCheck`、`@Transactional`、`@Cacheable` 同理）只在外部调用时生效，类内部 `this.xxx()` 一律绕过。补充：**这个坑在主工程里一模一样**（`src/main/java/com/yupi/yuaicodemother/controller/UserController.java#L125-L126`），不是微服务拆分引入的。

12. 说明它是一个**无状态计算型服务**：不连数据库（无 datasource）、不存会话（无 redis/session），只依赖外部的 COS（上传结果）和 Dubbo（对外暴露）。它的 `ScreenshotServiceImpl` 只做「本地截图 → 上传 COS → 删本地文件」三件事（`yu-ai-code-screenshot/.../service/impl/ScreenshotServiceImpl.java#L19-L44`），处理完不留任何本地状态。**好处**：① 可以随意水平扩容（无状态）；② 把最重的依赖（`selenium-java` + `webdrivermanager` + 本机无头 Chrome）从主服务隔离出去，Chrome 崩了、内存爆了都不影响 `app` 服务；③ 重启/部署代价低（不用等数据库连接池初始化）。验证方式见验证 10。

13. 因为**「谁的数据谁写库」是服务化里必须守住的边界**。`app` 表属于 `app` 服务，`screenshot` 服务**连数据库都没有**（无 datasource 配置、pom 里没有 `mybatis-flex` / `mysql-connector-j`），它只负责「给定 URL，返回截图地址」，是一个纯粹的计算函数。如果让 `screenshot` 去写 `app` 表，就得给它配数据源、把 `app` 表的 schema 复制过去——**表结构立刻变成跨服务的隐式契约**，`app` 服务改一个字段，`screenshot` 可能就写坏了，而且没有编译期约束。所以正确做法是：`app` 起虚拟线程发起 Dubbo 调用拿到 URL，**回到自己的线程里用自己的 `updateById` 写自己的表**（`AppServiceImpl#L195-L208`）。这样即使截图失败，也只是少了个封面，不会产生「半写」的脏数据。

14. 因为它是**共享数据库（shared database）**架构：两个服务连的是同一个 MySQL 库（`jdbc:mysql://localhost:3306/yu_ai_code_mother`），只是**各写各的表**——`user` 服务管 `user` 表，`app` 服务管 `app`、`chat_history` 表。**避免了**「分布式事务」问题：所有写操作都在同一个库里，一个本地 `@Transactional` 就够，不需要 Seata/TCC/最终一致性方案（工程里也确实没有引入这些）。**引入了**「表结构成为隐式契约」的问题：表结构没有编译期约束，`app` 服务改 `user` 表的字段，`user` 服务可能运行期才报错；而且服务边界只是「代码边界」，无法独立演进数据库（不能给 `user` 表单独分库分表而不影响 `app`）。面试时如果被问「怎么保证数据一致性」，正确回答是「当前是共享库 + 单库事务，因此不存在分布式事务问题，代价是服务边界不够硬」——**不要硬说有 Seata 或最终一致性，工程里没有。**

</details>

---

### 12.7 本环节技术点清单

| 技术点 | 落地位置 | 作用 | 项目特有之处 |
|---|---|---|---|
| Maven 聚合多模块 | `yu-ai-code-mother-microservice/pom.xml#L11-L19`（`packaging: pom` + 7 个 `<module>`） | 用模块表达依赖边界 | 7 个模块里只有 3 个是服务，4 个是库；`ai` 名字像服务实为库 |
| 契约模块（零框架依赖） | `yu-ai-code-client/pom.xml#L20-L26` | provider / consumer 共享接口定义 | **只依赖 `common` + `model`**，无 Spring 无 Dubbo；可用 `dependency:tree` 验证 |
| 依赖方向收敛 | `common → model → client`；`ai`、`user`、`screenshot`、`app` 各自向上依赖 | 避免循环依赖、避免实现泄漏 | `common` 是最底层，被所有模块依赖 |
| 条件装配保护公共 Bean | `yu-ai-code-common/.../config/CosClientConfig.java#L20-L27` | 让公共模块能被「没配该项的服务」安全引用 | `@ConditionalOnProperty` 列了 5 个 key，缺一不生效；`user` 服务靠它才能启动 |
| 公共 JSON 配置 | `yu-ai-code-common/.../config/JsonConfig.java#L13-L27` | `Long` → String，防前端精度丢失 | `@JsonComponent`，三个服务行为一致 |
| 包名不统一 + 显式扫描 | `YuAiCodeUserApplication#L11-L12`（`@MapperScan` + `@ComponentScan("com.yupi")`） | 让 `user` 服务能扫到 `common` 的 Bean | `user` 根包是 `com.yupi.yuaicodeuser`，其余是 `com.yupi.yuaicodemother` |
| `@EnableDubbo` | 三个启动类（`#L10` / `#L12` / `#L8`） | 开启 Dubbo 注解扫描 | 三个服务各一份，缺一则该服务的 `@DubboService` 不生效 |
| `@DubboService` | `InnerUserServiceImpl#L17`、`InnerScreenshotServiceImpl#L8` | 把本地 Bean 暴露为远程服务 | **只有 2 个**；都只做「纯委托」，不写业务逻辑 |
| `@DubboReference` | `yu-ai-code-app/.../AppServiceImpl.java#L61-L62`、`#L76-L77` | 注入远程服务代理 | **只有 2 个字段**；与 `@Resource` 混用，**光看字段名分不出远程/本地** |
| Triple 协议 | 三个 yml 的 `dubbo.protocol.name: tri` | HTTP/2 上的 RPC，兼容 gRPC | 端口必须错开：50051 / 50052 / 50053 |
| 应用级注册 | 三个 yml 的 `dubbo.registry.register-mode: instance` | 按应用注册，减小注册中心数据量 | Nacos 里只看到 3 个服务名（`spring.application.name`） |
| Nacos 注册中心 | `dubbo.registry.address: nacos://127.0.0.1:8848?username=nacos&password=nacos` | 服务发现 | **账号密码明文写在 URL 里**，本地开发配置 |
| 差异化超时 | user 60000 / app、screenshot 120000 | 按链路最慢操作设超时 | **同一次调用取 consumer 侧的值**；`app` 调 `user` 时生效的是 120000 |
| 静态方法规避跨服务调用 | `InnerUserService#getLoginUser`（`client` 模块） | 登录态从本地 Session 取，不走网络 | 全工程 **10 处**调用（`AppController` 7 + `ChatHistoryController` 1 + `AuthInterceptor` 1 + `RateLimitAspect` 1） |
| 共享 Redis Session | 三个 yml 的 `spring.session.store-type: redis` + `timeout: 2592000` | 让每个服务都能本地还原登录态 | `app` 服务也要引 `spring-session-data-redis` + jedis，**不是为了自己存 Session，而是为了读** |
| 批量化跨服务调用 | `yu-ai-code-app/.../AppServiceImpl.java#L236-L237` | `listByIds` 替代循环 `getById`，避免 N+1 | 服务化后「批量接口」从优化变成纪律 |
| 虚拟线程 + 跨服务异步 | `yu-ai-code-app/.../AppServiceImpl.java#L195-L208` | 慢操作（截图）异步化，不阻塞 HTTP 线程 | 虚拟线程里发起 Dubbo 调用，回来后写本地库 |
| 谁的数据谁写库 | `AppServiceImpl#L195-L208` vs `ScreenshotServiceImpl` | 截图服务只返回 URL，不碰 `app` 表 | `screenshot` 服务**连数据库都没有** |
| 无状态计算服务 | `yu-ai-code-screenshot/src/main/resources/application.yml`（仅 cos + dubbo） | 可随意扩容、隔离浏览器依赖 | 无 datasource / redis / session，也**没有 controller** |
| 输入护轨（生效） | `yu-ai-code-app/.../AiCodeGeneratorServiceFactory.java#L116`、`#L128` | 拦截 Prompt 注入、超长输入 | `PromptSafetyInputGuardrail` 在 `ai` 模块；长度 > 1000 直接 `fatal` |
| 输出护轨（未启用） | 同上 `#L117`、`#L129`（**被注释**） | — | 注释原文「为了流式输出，这里不使用」；**类存在但没接上，属于死代码** |
| 共享数据库 | `user` / `app` 两个 yml 的 `jdbc:mysql://localhost:3306/yu_ai_code_mother` | 单库事务，无分布式事务 | 服务边界只是代码边界；表结构成隐式契约；**未引入 Seata / 最终一致性方案** |
| 配置跟着能力走 | `app` yml 有 `spring.data.redis.ttl: 3600`，`user` yml 没有 | 对话记忆的 TTL 只在需要的服务里配 | `RedisChatMemoryStoreConfig` 在 `ai` 模块，靠 `@ConfigurationProperties(prefix = "spring.data.redis")` 读取 |
| AOP 自调用失效（坑） | `yu-ai-code-user/.../controller/UserController.java#L124-L129` | `@AuthCheck` 被 `this.` 调用绕过 | **主工程同样存在**（`src/main/java/.../UserController.java#L125-L126`）；`/user/get/vo` 无鉴权 |
| 重复的切面实现 | `yu-ai-code-app/.../aop/AuthInterceptor.java#L36` vs `yu-ai-code-user/.../aop/AuthInterceptor.java#L40` | 两个服务各注册一份 `@AuthCheck` 切面 | 逻辑一致但取用户方式不同（静态方法 vs 本地 `UserService`），可统一 |
| 冗余 BOM 导入 | `yu-ai-code-mother-microservice/pom.xml#L63-L94` | 统一版本号 | `spring-cloud` / `spring-cloud-alibaba` **导入了但没用**，无任何 Cloud starter |

---

### 12.8 本环节产出

这个环节是「**一个能跑的单体，怎么变成一堆能独立跑的服务，而又不把自己拆死**」。整套拆分的底座是**三层边界**：第一层是 **Maven 模块边界**——`common` 沉到最底、`model` 只依赖 `common`、`client` 只依赖前两者且**一个框架依赖都不引**，于是「契约」和「实现」在编译期就分开了；`ai` 因为要放与 `langchain4j` **同包名**的补丁类，只能做成库而不是服务；`screenshot` 因为要拉无头 Chrome，被隔离成**无 datasource、无 redis、无 session、无 controller** 的纯计算服务——「一个服务该不该独立，看它的依赖脏不脏」。第二层是 **Dubbo 的服务边界**——只有 2 个 `@DubboService`、2 个 `@DubboReference`，靠 Triple 协议（端口 50051/50052/50053）和 Nacos 的应用级注册（`register-mode: instance`）连通；超时按链路最慢操作差异化配置（`user` 60 秒、`app`/`screenshot` 120 秒），而且**同一次调用取 consumer 侧的值**。第三层是最容易被忽略、也最见功力的 **登录态边界**——`InnerUserService#getLoginUser` 被刻意写成 `static` 方法，注释里那句「**静态方法，避免跨服务调用**」是整份代码里最有价值的一行注释：因为 `HttpServletRequest` 不可序列化、且每个服务都能从**共享的 Redis Session** 里自己还原登录态，所以全工程 10 处取用户**全是本地调用、零网络开销**，只有「查用户数据」「生成截图」这类真正需要数据的操作才走 Dubbo。三处必须点出的「不彻底」和「坑」是：**共享数据库**（两个服务连同一个 MySQL，避免了分布式事务但让表结构变成隐式契约，且工程里没有任何 Seata/最终一致性方案）、**`RetryOutputGuardrail` 写了但被注释掉**（注释原文「为了流式输出，这里不使用」——输出护轨要求先缓冲完整响应，会毁掉 SSE）、以及 **`/user/get/vo` 因为 `this.` 自调用绕过了 `@AuthCheck` 而完全无鉴权**（主工程同样存在，根因是 Spring AOP 的代理机制）。读到这里应该能回答：**「这个项目为什么可以把截图拆出去、却把 AI 生成留在主服务里；拆出去之后，登录用户是怎么被两个服务同时拿到的」**——答案是：拆分的依据是**依赖的脏程度**（截图要浏览器环境、无状态，所以独立；AI 生成与 `app` 表、对话历史、缓存、限流强耦合，且需要同包补丁类，所以留在 `app` 或做成库）；登录用户靠的是 **Spring Session + 共享 Redis**——`user` 和 `app` 都配了 `store-type: redis`，浏览器带着 `JSESSIONID` 打到任意一个服务，Spring Session 都能从 Redis 还原出 `User`，所以 `InnerUserService#getLoginUser(request)` 这个**静态方法**在每个服务本地执行就够了，**不需要（也不能）把 `HttpServletRequest` 通过 Dubbo 传给 user 服务**；而真正需要数据的操作（`getById` / `listByIds` / `generateAndUploadScreenshot`）才走 Triple 协议跨进程——**「登录态走本地、业务数据走网络」这条分界线，就是这个微服务拆分最核心的设计决策**。

---

<!-- NEXT_SECTION_PLACEHOLDER -->
