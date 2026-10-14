# AI 代码生成平台「从零复现」路线讲解（阶段 0 ~ 6）

> 本文档由一次完整问答整理而成，回答一个核心问题：**「单个流程的每一步都能看懂，但把应用服务、对话历史服务、部署服务等放在一起就乱了，想自己对照项目复现，却不知道从何下手」**。
>
> 项目：`yu-ai-code-mother`（Spring Boot 3.5.4 + JDK 21 + LangChain4j + MyBatis-Flex）
> 复现前提（已与需求方确认的默认口径）：目标是**学习验证**；范围**先做核心闭环**（App CRUD + 对话 + HTML 生成落盘 + 预览），其余功能列为后续可选阶段；**沿用原技术栈**；写码方式为**对照源码边看边写**。

---

## 〇、问题诊断：为什么"乱"

**根因一句话：你在按「功能」理解服务，但这份代码是按「资源」组织的。**

- 按功能看：生成代码、存对话、部署、下载、截图，像是五摊子事互相穿插；
- 按资源看：**`App` 是核心资源**，上述五件事都是"围绕应用的子操作"，所以它们全都挂在 `AppService` 上——这才是 `AppService` 看起来"什么都在管"的原因。

**服务一共只有 5 个，不多也不乱**，乱的只是没找到"各自负责哪个资源"这条主线：

| Service | 负责的资源 | 一句话 |
|---|---|---|
| `UserService` | 用户 | 注册 / 登录 / 取当前登录用户 |
| `AppService` | **应用** | 应用 CRUD + 生成 + 部署 |
| `ChatHistoryService` | 对话消息 | 消息增 / 查 / 回填记忆 |
| `ProjectDownloadService` | 代码包 | 生成目录压成 zip |
| `ScreenshotService` | 封面图 | 截图 + 上传对象存储 |

**"顺序"一词其实混着三种不同的顺序**，这是混乱的直接来源：

| 编号 | 是哪一种顺序 | 含义 |
|---|---|---|
| ① | 运行时生命周期时序 | 一个应用从创建到部署的完整时间线（谁触发、经过哪些服务） |
| ② | 模块依赖分层 | Controller / Service / Facade / 工具层之间谁调谁、谁不该调谁 |
| ③ | 复现编码顺序 | 自己动手时先写哪块、后写哪块（**真正卡住的点**） |

**求解思路**：先用 ①② 建立全局视图，再由它推出 ③。

---

## 一、全局地图 A：一个应用的一生（运行时生命周期时序）

```
① 注册/登录          UserController → UserService → user 表            建立 session
        │
        ▼
② 创建应用           POST /app/add
   （含 AI 决策）      AppController → AppService#createApp
        │                ├─ AiCodeGenTypeRoutingService（qwen-turbo 判断类型）
        ▼                └─ 写 app 表：codeGenType 从此固定，决定后续所有分岔
③ 对话生成（核心）    GET /app/chat/gen/code（SSE）
                      AppController → AppService#chatToGenCode
                        ├─ ChatHistoryService：用户消息先落库
                        ├─ AiCodeGeneratorFacade#generateAndSaveCodeStream
                        │     └─ 三岔口：HTML/多文件（攒批→正则→模板落盘）
                        │                VUE（工具调用直写 + npm build）
                        │     └─ 落盘 → tmp/code_output/{codeGenType}_{appId}/
                        └─ StreamHandlerExecutor：AI 消息落库
        │
        ▼
④ 预览               GET /api/static/{codeGenType}_{appId}/
                      StaticResourceController → 直接读 ③ 的目录（不经过 Service）
        │
        ▼
⑤ 查历史             GET /app/chat/history（游标分页）→ ChatHistoryController → ChatHistoryService
        │
        ▼
⑥ 部署               POST /app/deploy → AppService#deployApp
                        ├─ VUE 先 build → dist
                        ├─ 复制到 tmp/code_deploy/{deployKey}/（deployKey 复用）
                        └─ 更新 app 表 → 异步虚拟线程 → ⑦
        │
        ▼
⑦ 生成封面（异步）   ScreenshotService → WebScreenshotUtils → CosManager → 回写 app.cover
        │
        ▼
⑧ 下载代码包         GET /app/download/{appId} → ProjectDownloadService（黑名单过滤 → 流式 zip）
        │
        ▼
⑨ 精选 / 管理        GET /app/good/list（@Cacheable 分页缓存）
                      GET /app/admin/list · DELETE /app/admin/delete（管理员）
```

---

## 二、全局地图 B：服务分层依赖（谁调谁）

方向只能从上往下调。

```
Controller 层（只做参数校验 + 转发）
  AppController · UserController · ChatHistoryController · StaticResourceController
  HealthController · WorkflowSseController
        │
        ▼
Service 层（业务编排）
  AppService ──┬─► UserService
               ├─► ChatHistoryService
               ├─► AiCodeGeneratorFacade
               ├─► StreamHandlerExecutor
               ├─► ScreenshotService
               └─► VueProjectBuilder
  ChatHistoryService ──►（读 app 做属主校验）
  ScreenshotService  ──► WebScreenshotUtils ──► CosManager
  ProjectDownloadService（纯工具型：只读文件系统 + zip）
        │
        ▼
Component / Core 层（能力实现，不做业务编排）
  AiCodeGeneratorFacade（三入口：非流式 / 流式 / TokenStream）
    ├─ ai/AiCodeGeneratorServiceFactory（多例 + Caffeine 缓存 + 记忆回填）
    ├─ core/parser（CodeParserExecutor → 两个 Parser）
    ├─ core/saver（CodeFileSaverExecutor → 两个 Template）
    └─ core/builder/VueProjectBuilder
  core/handler：SimpleTextStreamHandler（HTML/多文件）· JsonMessageStreamHandler（VUE）
  ai/tools：ToolManager + 6 个 BaseTool 子类
  ai/guardrail · manager/CosManager · monitor
        │
        ▼
数据层：Mapper → MySQL（app / user / chat_history）｜Redis｜COS
```

**三条红线（复现时最容易违反）**

1. Controller 只能调 Service，不能直接调 Facade / parser / saver。
2. `core/parser`、`core/saver` 不许依赖任何 Service（纯计算 / 纯落盘）。
3. 有状态编排进 Service/Facade；无状态纯计算类不注入容器。

---

## 三、全局地图 C：复现阶段总表

| 阶段 | 主题 | 核心产出 | 验收要点 |
|---|---|---|---|
| 0 | 工程骨架 + 基础设施 | 统一响应 / 异常 / 权限外壳 | 后续接口能"一行断言 + 一行成功返回"写完 |
| 1 | 用户体系 | 注册 / 登录 / 当前用户 | 带凭据能取到当前用户，不带能正确报未登录 |
| 2 | App 资源 CRUD | 应用增删改查 + 属主校验 | 只能改删自己的应用 |
| 3 | AI 最小生成闭环（非流式 HTML） | 一句需求 → 磁盘上真文件 | 磁盘出现 `html_{appId}/index.html` |
| 4 | 流式 SSE + 预览 | 打字机效果 + 可访问预览 | 流式输出 + 预览页可打开 |
| 5 | 对话历史与记忆 | 多轮记忆 + 历史分页 | 第二轮能引用第一轮内容 |
| 6 | 功能扩展（可逐个做 / 可跳过） | AI 路由 / 多文件 / VUE / 部署封面 / 下载 / 缓存限流监控 / 工作流 | 各子项独立验收 |

---

## 阶段 0 讲透：工程骨架 + 基础设施

**一句话**：阶段 0 只干一件事——给整个项目装一个"外壳"：统一的响应格式、统一的异常出口、统一的权限入口。它不含任何业务，但决定了后面每一行业务代码的写法模板。

**为什么必须先做**：后面每个 Controller 都要 `return ResultUtils.success(...)`，每个 Service 都要 `ThrowUtils.throwIf(...)`。外壳不先立，业务代码会写成"一种接口一种返回格式"。

### 0.1 依赖总览（pom.xml 分层解读）

| 层 | 依赖 | 作用 | 阶段 |
|---|---|---|---|
| 基础 | `spring-boot-starter-web` | HTTP + Tomcat | P0 |
| 基础 | `spring-boot-starter-aop` | `@AuthCheck` 切面、限流 | P0 |
| 基础 | `lombok`、`hutool-all` | 样板消除 + 工具库 | P0 |
| 基础 | `knife4j-openapi3-jakarta-spring-boot-starter` | 接口文档 `/doc.html` | P0（可选） |
| 数据 | `mybatis-flex-spring-boot3-starter` | **ORM（本项目用 MyBatis-Flex）** | P0 |
| 数据 | `mysql-connector-j`、`HikariCP` | 驱动 + 连接池 | P0 |
| 数据 | `mybatis-flex-codegen` | 代码生成器 | P0（可省） |
| 会话 | `spring-session-data-redis` | session 存 Redis | P0/P1 |
| 会话 | `redisson` | 分布式能力（限流等） | P1+/P6 |
| 会话 | `caffeine` | 本地缓存（AI 服务实例缓存） | P5 |
| AI | `langchain4j` 1.1.0 + 三个 starter | 模型接入 / Reactor 适配 / Redis 记忆 | P3/P5 |
| AI | `dashscope-sdk-java` | 图片生成 / 路由模型 | P6 |
| AI | `langgraph4j-core` / `-studio-springboot` | 工作流（可跳过） | P6.7 |
| 扩展 | `selenium-java` + `webdrivermanager` | 网页截图 | P6.4 |
| 扩展 | `cos_api` | 对象存储 | P6.4 |
| 运维 | `actuator` + `micrometer-registry-prometheus` | 监控指标 | P6.6 |

**复现建议**：阶段 0 只加"基础 + 数据 + 会话"；AI / 扩展类依赖等对应阶段再加，避免一次性引入版本冲突。

**版本注意点**

1. **LangChain4j 主包与 starter 版本必须对齐**：主包 `1.1.0`，starter 为 `1.1.0-beta7`（预发布）。只引 starter 不显式引主包，会因传递依赖版本不一致出现 `NoSuchMethodError`。
2. **`HikariCP` 被显式钉在 4.0.3**，覆盖了 Boot 3.5.4 管理的 5.x —— 疑似历史遗留，复现建议直接删掉该显式版本。
3. **`mybatis-flex-codegen`** 是开发期反向生成工具，运行期用不到，初期可不加。

### 0.2 application.yml 分块解读

| 块 | 关键项 | 作用 / 坑 |
|---|---|---|
| `spring.session` | `store-type: redis`、`timeout: 2592000`（30 天） | 会话存 Redis，重启不丢；**必须先有 Redis 才能启动** |
| `spring.datasource` | `jdbc:mysql://localhost:3306/yu_ai_code_mother` | 库名固定；账号密码明文（Demo 可接受） |
| `spring.data.redis` | `ttl: 3600` | 缓存 3600s，**必须与 AI 记忆过期策略对齐** |
| `server` | `port: 8123`、`context-path: /api` | **所有接口带 `/api` 前缀**，前端 baseURL 必须一致 |
| `springdoc`/`knife4j` | 只扫 `...controller` | Service 不出现在文档里 |
| `langchain4j.open-ai` | **四套模型配置** | 见下表 |
| `cos`/`pexels`/`dashscope`/`management` | 第三方 Key + 监控 | P6 才用 |

**四套模型配置（最值得记住的部分）**

| 配置前缀 | 模型 | 供应商 | max-tokens | 谁用 |
|---|---|---|---|---|
| `chat-model` | deepseek-chat | DeepSeek | 8192 | 非流式生成（结构化输出） |
| `streaming-chat-model` | deepseek-chat | DeepSeek | 8192 | HTML / 多文件流式生成 |
| `reasoning-streaming-chat-model` | deepseek-reasoner | DeepSeek | 32768、temp 0.1 | VUE 工程（工具调用 / 推理） |
| `routing-chat-model` | qwen-turbo | 阿里 DashScope | 100 | AI 选生成类型（一次分类） |

**为什么同一件事配四套？** 生成代码 / 推理规划 / 三选一分类是三种不同任务，成本差几十倍；用一套模型干所有事 = 用贵模型做选择题。

**注意**：API Key 全是 `<Your API Key>` 占位符，必须自行申请替换；`log-requests/log-responses: true` 开发期方便看提示词，**生产必须关**。

### 0.3 启动类：三个注解

```java
@EnableCaching
@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
@MapperScan("com.yupi.yuaicodemother.mapper")
public class YuAiCodeMotherApplication { ... }
```

| 注解 | 作用 | 不写 / 写错的后果 |
|---|---|---|
| `@SpringBootApplication` | 自动装配起点 | 必须 |
| `@MapperScan("...mapper")` | 批量扫描 Mapper | 不写要给每个 Mapper 加 `@Mapper`；**包名写错启动即报找不到 Mapper** |
| `@EnableCaching` | 开启注解式缓存 | 精选分页缓存（P6）依赖它；**Caffeine 那层不靠它**（手工 new） |
| `exclude = RedisEmbeddingStoreAutoConfiguration` | 排除 embedding 自动配置 | **关键坑**：引了 `langchain4j-community-redis` 会捎带开启向量库自动配置，项目不用；不排除可能启动报错 |

### 0.4 统一外壳：六个类的流水线

```
业务代码
  └─ ThrowUtils.throwIf(条件, ErrorCode.XXX, "消息")   ← ① 声明式断言
        └─ new BusinessException(errorCode, message)   ← ② 携带 code+message 的运行时异常
              └─ @RestControllerAdvice 捕获             ← ③ 统一出口
                    └─ ResultUtils.error(code, msg)      ← ④ 组装
                          └─ BaseResponse{code,data,message} ← ⑤ 统一响应体
```

**① ErrorCode 是业务码，不是 HTTP 码**

```java
SUCCESS(0, "ok"),
PARAMS_ERROR(40000, "请求参数错误"),
NOT_LOGIN_ERROR(40100, "未登录"),
NO_AUTH_ERROR(40101, "无权限"),
TOO_MANY_REQUEST(42900, "请求过于频繁"),
NOT_FOUND_ERROR(40400, "请求数据不存在"),
SYSTEM_ERROR(50000, "系统内部异常"),
OPERATION_ERROR(50001, "操作失败");
```

`40000` 不是 HTTP 400，是业务码；HTTP 状态仍是 200。前端只需判断 `body.code`，不用同时处理两套语义。

**② BusinessException 继承 `RuntimeException`**：用运行时异常 + 全局处理器 = 任何一层的 throw 都直达统一出口，业务代码不被 `catch` 淹没。

**③ ThrowUtils 让断言变成一行**：语义是"**条件成立就抛异常**"（与 assert 直觉相反）。

```java
ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 错误");
ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "提示词不能为空");
```

**④⑤ BaseResponse + ResultUtils**：`code/data/message` 三字段 + 静态工厂方法，保证所有接口出口形状一致。

**⑥ GlobalExceptionHandler：必须懂的 SSE 特判**

```java
private boolean handleSseError(int errorCode, String errorMessage) {
    String accept = request.getHeader("Accept");
    String uri = request.getRequestURI();
    if ((accept != null && accept.contains("text/event-stream")) ||
            uri.contains("/chat/gen/code")) {
        response.setContentType("text/event-stream");
        String sseData = "event: business-error\ndata: " + errorJson + "\n\n";
        response.getWriter().write(sseData);
        return true;   // 已处理，不再返回 JSON
    }
    return false;
}
```

**为什么 SSE 不能走普通 JSON 返回**：SSE 响应头在流开始时已提交（committed），再让 Spring 写 JSON body 会失败或污染流，只能在已打开的流里追加 `business-error` 事件。

两个可改进点：条件里 `uri.contains("/chat/gen/code")` 是**硬编码路径**（接口路径一改即静默失效）；错误后紧接着发 `done`，前端需区分"正常 done"与"错误 done"。

### 0.5 权限入口：@AuthCheck + AuthInterceptor

`@AuthCheck` 是**方法级注解**（`mustRole()` 默认空串）；`AuthInterceptor` 是 `@Around("@annotation(authCheck)")` 的 **AOP 环绕切面**。

```java
@Around("@annotation(authCheck)")
public Object doInterceptor(ProceedingJoinPoint joinPoint, AuthCheck authCheck) throws Throwable {
    String mustRole = authCheck.mustRole();
    User loginUser = userService.getLoginUser(request);        // ← 先要求登录
    UserRoleEnum mustRoleEnum = UserRoleEnum.getEnumByValue(mustRole);
    if (mustRoleEnum == null) { return joinPoint.proceed(); }  // 没写 role → 只要登录就放行
    UserRoleEnum userRoleEnum = UserRoleEnum.getEnumByValue(loginUser.getUserRole());
    if (userRoleEnum == null) { throw new BusinessException(ErrorCode.NO_AUTH_ERROR); }
    if (UserRoleEnum.ADMIN.equals(mustRoleEnum) && !UserRoleEnum.ADMIN.equals(userRoleEnum)) {
        throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
    }
    return joinPoint.proceed();
}
```

三个必须理解的语义：

1. **`@AuthCheck` 什么都不写 = 只要登录**（`getLoginUser` 在判断 role 之前执行，未登录先抛 `NOT_LOGIN_ERROR`）。这个"顺带登录校验"是隐式的，容易误读。
2. **它是"加法"不是"减法"**：只能表达"必须有 X 权限"，不能表达"否认"。更细的属主校验（只能改自己的应用）仍需在 Service 里手写。
3. **没有全局默认拦截**：忘了加注解的接口就是公开的 —— 写 Controller 时先想"要不要加 `@AuthCheck`"。

### 0.6 其余基础设施

| 类 | 作用 |
|---|---|
| `PageRequest` | 分页请求基类，各 QueryRequest 继承 |
| `DeleteRequest` | 通用删除请求（id） |
| `UserConstant` | `USER_LOGIN_STATE` 等 session 键名、默认/管理员角色 |
| `AppConstant` | **`CODE_OUTPUT_ROOT_DIR` 等目录常量 → P3 起贯穿全项目** |

### 0.7 阶段 0 验收标准

| # | 验收项 | 怎么验 |
|---|---|---|
| 1 | 应用能启动 | 日志出现 Tomcat started on port 8123 |
| 2 | 统一响应生效 | 临时接口返回 `ResultUtils.success("ok")` → `{"code":0,"data":"ok"}` |
| 3 | 统一异常生效 | `ThrowUtils.throwIf(true, PARAMS_ERROR, "测试")` → `{"code":40000,"message":"测试"}`，HTTP 仍 200 |
| 4 | 权限机制生效 | 标 `@AuthCheck` 的接口未登录访问 → `code:40100` |
| 5 | 连库 / Redis 连通 | 启动无连接报错 |
| 6 | 目录常量就位 | `AppConstant` 输出根目录已定义 |

### 0.8 阶段 0 坑清单

| # | 坑 | 表现 | 处理 |
|---|---|---|---|
| 1 | LangChain4j 主包/starter 版本错配 | `NoSuchMethodError` | 版本对齐 |
| 2 | 未排除 embedding 自动配置 | 启动报向量库相关错 | 保留 `exclude` |
| 3 | HikariCP 被降级到 4.0.3 | 依赖异常 | 删除显式版本 |
| 4 | API Key 全是占位符 | 首次调 AI 401 | 自行填 Key |
| 5 | `log-requests/responses` 未关 | 泄露提示词/数据 | 生产改 false |
| 6 | `profiles.active: local` 无对应文件 | 看似缺配置 | 正常（local 即默认配置） |
| 7 | 全局异常里路径硬编码 | 改路径后 SSE 错误失效 | 改为条件判断 |
| 8 | `@AuthCheck` 忘加 = 接口公开 | 越权访问 | 写接口先想权限 |

### 0.9 心法 + Checklist

**心法**：阶段 0 的价值不在功能，而在把所有接口的出口收敛成一种形状。判断标准只有一条：**后面写任何接口，是不是都能"一行 `throwIf` + 一行 `return ResultUtils.success`"写完？**

| # | 自问 | 答案 |
|---|---|---|
| 1 | 持久层框架是什么？ | MyBatis-Flex（不是 MyBatis-Plus） |
| 2 | `ErrorCode` 的 40000 是 HTTP 码吗？ | 不是，是业务码；HTTP 仍 200 |
| 3 | 为什么用运行时异常？ | 免去每层 try-catch，直达全局出口 |
| 4 | `ThrowUtils.throwIf` 语义？ | 条件**成立**时抛异常 |
| 5 | 全局异常为何特判 SSE？ | SSE 已提交，只能往流里写 `business-error` |
| 6 | `@AuthCheck` 不写 mustRole？ | **仍要求登录**，只是不校验角色 |
| 7 | 为何 exclude embedding 自动配置？ | redis 社区 starter 捎带了向量库配置 |
| 8 | 四套模型配置为何分开？ | 生成/推理/分类任务量级差几十倍 |

---

## 阶段 1 讲透：用户体系

**一句话**：阶段 1 做的是"**身份**"——让系统随时能回答"现在是谁在操作"。它是后面每个接口的前置依赖：P3 生成要写"谁的应用"、P6 部署要校验"是不是应用的主人"。

### 1.1 数据模型：user 表

`sql/create_table.sql` 中的 user 表：

```sql
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    userName     varchar(256)                           null comment '用户昵称',
    userAvatar   varchar(1024)                          null comment '用户头像',
    userProfile  varchar(512)                           null comment '用户简介',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin',
    editTime     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName)
) comment '用户' collate = utf8mb4_unicode_ci;
```

**三个设计点**：

1. **`uk_userAccount` 唯一索引**：数据库层面兜底"账号不重复"——Service 里虽然有查重逻辑，但并发下仍可能穿透，唯一约束是最后一道防线。
2. **`isDelete` 逻辑删除**：不物理删数据，MyBatis-Flex 会自动在查询里加 `isDelete = 0`。
3. **`updateTime ... on update CURRENT_TIMESTAMP`**：交给数据库自动维护更新时间。

### 1.2 分层结构

```
UserController（/user）
   ├─ POST /register   → userRegister
   ├─ POST /login      → userLogin + session 写入
   ├─ GET  /get/login  → getLoginUser（当前登录用户）
   ├─ POST /logout     → userLogout
   ├─ POST /add        → @AuthCheck(admin)
   ├─ GET  /get        → @AuthCheck(admin)
   ├─ GET  /get/vo     → getUserVOById
   ├─ POST /delete     → @AuthCheck(admin)
   ├─ POST /update     → @AuthCheck(admin)
   └─ POST /list/page/vo → @AuthCheck(admin)
        │
        ▼
UserService extends IService<User>（MyBatis-Flex 的 IService，自带 save/getById/updateById/page）
        │
        ▼
UserServiceImpl extends ServiceImpl<UserMapper, User>
        │
        ▼
UserMapper（@MapperScan 扫到）
```

**关键认知**：`UserService extends IService<User>` —— MyBatis-Flex 的 `IService` 已经提供了通用 CRUD（`save` / `getById` / `updateById` / `removeById` / `page`）。所以 `UserServiceImpl` **只写通用 CRUD 之外的东西**（注册、登录、加盐加密、脱敏 VO），这就是为什么它看着不长。

### 1.3 注册：四步

```java
public long userRegister(String userAccount, String userPassword, String checkPassword) {
    // 1. 校验参数
    if (StrUtil.hasBlank(userAccount, userPassword, checkPassword)) { throw ... }
    if (userAccount.length() < 4) { throw ... "账号长度过短" }
    if (userPassword.length() < 8 || checkPassword.length() < 8) { throw ... "密码长度过短" }
    if (!userPassword.equals(checkPassword)) { throw ... "两次输入的密码不一致" }
    // 2. 查询用户是否已存在
    QueryWrapper queryWrapper = new QueryWrapper();
    queryWrapper.eq("userAccount", userAccount);
    long count = this.mapper.selectCountByQuery(queryWrapper);
    if (count > 0) { throw ... "账号重复" }
    // 3. 加密密码
    String encryptPassword = getEncryptPassword(userPassword);
    // 4. 创建用户，插入数据库
    User user = new User();
    user.setUserAccount(userAccount);
    user.setUserPassword(encryptPassword);
    user.setUserName("无名");
    user.setUserRole(UserRoleEnum.USER.getValue());
    boolean saveResult = this.save(user);
    if (!saveResult) { throw ... "注册失败，数据库错误" }
    return user.getId();
}
```

**顺序为什么是"先查重、再加密、再插入"？** 查重必须在插入前（否则唯一索引会抛异常，难转成友好提示）；加密必须在插入前（库里不能有明文）。

**为什么两次密码校验放在 Service 而不放前端？** 前端校验只是体验，**安全校验必须在服务端**。

### 1.4 登录：五步 + session

```java
public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
    // 1. 校验参数（同注册）
    // 2. 加密
    String encryptPassword = getEncryptPassword(userPassword);
    // 3. 查询用户是否存在（账号 + 密文同时匹配）
    QueryWrapper queryWrapper = new QueryWrapper();
    queryWrapper.eq("userAccount", userAccount);
    queryWrapper.eq("userPassword", encryptPassword);
    User user = this.mapper.selectOneByQuery(queryWrapper);
    if (user == null) { throw ... "用户不存在或密码错误" }   // ← 不区分是账号错还是密码错
    // 4. 记录登录态
    request.getSession().setAttribute(USER_LOGIN_STATE, user);
    // 5. 返回脱敏信息
    return this.getLoginUserVO(user);
}
```

三个设计点：

1. **账号 + 密文一起查**：不在代码里比对明文密码，减少密码在内存中的暴露面。
2. **错误信息不区分"账号不存在"和"密码错误"**：防止账号枚举攻击（攻击者无法通过错误信息判断账号是否存在）。
3. **登录态写入 session**（`USER_LOGIN_STATE`）：因为配了 `spring-session-data-redis`，这个 session 自动落 Redis，服务重启/多实例共享仍然有效。

**为什么用 session 而不是 JWT？** Demo 场景下 session 更简单：注销 = 服务端删 key（JWT 无法真正失效）；且 session 存在 Redis 里天然支持多实例。代价是每次请求要查一次 Redis —— 对这个项目量级完全够用。

### 1.5 getLoginUser：全项目最常被调用的方法

```java
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
    if (currentUser == null) { throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR); }
    return currentUser;
}
```

**关键设计：session 里只存用户对象，但每次都回查数据库。**

- 为什么不直接用 session 里的对象？因为 session 里的数据是"登录那一刻的快照"——用户改了昵称/头像，或**被管理员删除了**，session 不会自动更新。回查数据库保证拿到的是最新状态（且能发现"用户已被删除"→ 再次抛未登录）。
- 代价：每个需要登录的接口多一次数据库查询。**这是"正确性换性能"的取舍**，量级不大时可接受；如果要优化，可以加一层 Redis 缓存用户信息。

**为什么它这么重要**：它被 `AuthInterceptor`（权限）、`AppServiceImpl`（属主）、`ChatHistoryServiceImpl` 等频繁调用，是"身份"的唯一来源。

### 1.6 加盐加密：为什么不能用裸 MD5

```java
public String getEncryptPassword(String userPassword) {
    final String SALT = "yupi";   // 盐值，混淆密码
    return DigestUtils.md5DigestAsHex((userPassword + SALT).getBytes(StandardCharsets.UTF_8));
}
```

- **裸 MD5 的问题**：同样的密码永远得到同样的哈希，撞库/彩虹表一击即破。
- **加固定盐**：让"同一密码"在不同系统里哈希不同，挡住彩虹表。
- **仍不够（真实缺陷）**：**固定盐 + MD5 依然不安全** —— 盐如果泄露（写在代码里，等于公开），且 MD5 计算太快，可被暴力破解。生产级做法是**每用户随机盐 + BCrypt/Argon2**（自带盐、可调计算强度）。
- **为什么这里还用 MD5？** 教学 Demo 的历史写法（鱼皮系列项目一脉相承）。**复现时照抄可以，但你要知道它的安全级别**。

### 1.7 脱敏：为什么要有 UserVO / LoginUserVO

`getLoginUserVO` 和 `getUserVO` 做同一件事：`BeanUtil.copyProperties(user, vo)`，**把 User 转成不含密码的 VO**。

**为什么不能直接返回 `User`？** 因为 `User` 里有 `userPassword`。一旦某个接口 `return user`，密码哈希就随 JSON 返回给前端了（[UserController#getUserById](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/UserController.java#L112-L119) 就直接返回了 `User`——**这其实是本项目的一处瑕疵，管理员接口返回了密码字段**，虽然调用者是管理员，仍不推荐）。

**正确做法**：对外一律用 VO；`User` 只在服务端内部流转。

### 1.8 Controller 写法示范（复现时照这个模板）

```java
@PostMapping("/register")
public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {
    ThrowUtils.throwIf(userRegisterRequest == null, ErrorCode.PARAMS_ERROR);
    String userAccount = userRegisterRequest.getUserAccount();
    String userPassword = userRegisterRequest.getUserPassword();
    String checkPassword = userRegisterRequest.getCheckPassword();
    long result = userService.userRegister(userAccount, userPassword, checkPassword);
    return ResultUtils.success(result);
}
```

这段完美示范了阶段 0 立的规矩：**先 `throwIf` 校验，再调 Service，最后 `ResultUtils.success` 包装**。Controller **不写任何业务逻辑**（不做密码比对、不查数据库），只做"拆参数 → 转发 → 包装"。

### 1.9 阶段 1 验收标准

| # | 验收项 | 怎么验 |
|---|---|---|
| 1 | 注册成功 | POST `/api/user/register` → 返回新用户 id；库里出现该用户且 `userPassword` 是密文 |
| 2 | 注册校验 | 两次密码不一致 / 账号 < 4 位 / 密码 < 8 位 / 账号重复 → 分别报对应错 |
| 3 | 登录成功 | POST `/api/user/login` → 返回脱敏信息，且 Redis 里出现 session |
| 4 | 取当前用户 | 带 Cookie 调 `/api/user/get/login` → 返回自己；不带 → `code:40100` |
| 5 | 注销 | POST `/api/user/logout` → 再调 get/login 报未登录 |
| 6 | 管理员权限 | 普通用户调 `@AuthCheck(admin)` 接口 → `code:40101` |

### 1.10 阶段 1 坑清单

| # | 坑 | 表现 | 处理 |
|---|---|---|---|
| 1 | 固定盐 + MD5 仍不安全 | 可被暴力破解 | 生产改 BCrypt/Argon2 |
| 2 | 直接返回 `User` 会带出密码 | 密码哈希随 JSON 返回 | 对外一律 VO |
| 3 | session 快照不更新 | 改了昵称仍显示旧的 | 每次 `getById` 回查（本项目已做） |
| 4 | 登录错误信息过于具体 | 账号枚举风险 | 合并为"用户不存在或密码错误" |
| 5 | 忘了给管理接口加 `@AuthCheck` | 越权 | 逐个检查 |
| 6 | 只靠前端做校验 | 可绕过 | 服务端必须重复校验 |
| 7 | 密码长度、账号长度校验放 Controller | 逻辑分散 | 统一放 Service |

### 1.11 心法 + Checklist

**心法**：阶段 1 的产物不是"注册登录功能"，而是**一个全项目可复用的"取当前用户"入口**（`getLoginUser`）。它的三个特征——**session 只存身份、每次回查数据库、拿不到就抛 `NOT_LOGIN`**——会被后面所有业务接口继承。

| # | 自问 | 答案 |
|---|---|---|
| 1 | `UserService extends IService<User>` 意味着什么？ | 通用 CRUD 由框架提供，只需写注册/登录/加密/脱敏 |
| 2 | 为什么登录时账号和密文一起查？ | 减少明文密码暴露；且不暴露"账号是否存在" |
| 3 | 为什么 session 里存了用户还要回查数据库？ | 快照会过期（改昵称、被删），需最新状态 |
| 4 | 加固定盐的 MD5 安全吗？ | 不安全，仅比裸 MD5 好；生产用 BCrypt |
| 5 | 为什么要有 UserVO / LoginUserVO？ | 防 `userPassword` 随响应泄露 |
| 6 | Controller 应写多少业务逻辑？ | 0 —— 只做参数校验、转发、包装 |
| 7 | 注销为什么比 JWT 简单？ | session 是服务端状态，删 key 即失效 |

---

## 阶段 2 讲透：App 资源 CRUD

**一句话**：阶段 2 是第一个「真正的业务域」——把 **App** 这个核心资源管起来（增删改查 + 属主校验），并为阶段 3 准备好 `codeGenType` 这个贯穿全项目的关键字段。

**为什么排在用户体系之后**：App 要记录"属于谁"（`userId`），还要做"只能改自己的"属主校验，都依赖阶段 1 的 `getLoginUser`。

### 2.1 数据模型：app 表

```sql
create table app
(
    id           bigint auto_increment comment 'id' primary key,
    appName      varchar(256)                       null comment '应用名称',
    cover        varchar(512)                       null comment '应用封面',
    initPrompt   text                               null comment '应用初始化的 prompt',
    codeGenType  varchar(64)                        null comment '代码生成类型（枚举）',
    deployKey    varchar(64)                        null comment '部署标识',
    deployedTime datetime                           null comment '部署时间',
    priority     int      default 0                 not null comment '优先级',
    userId       bigint                             not null comment '创建用户id',
    editTime     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_deployKey (deployKey),
    INDEX idx_appName (appName),
    INDEX idx_userId (userId)
) comment '应用' collate = utf8mb4_unicode_ci;
```

| 字段 | 属于哪个阶段 | 说明 |
|---|---|---|
| `codeGenType` | **P2 写好，P3/P4 才用** | 决定后续所有 `switch` 分岔的源头 |
| `initPrompt` | P2 | 创建时的需求描述，AI 路由与生成的输入 |
| `cover` | P2 建字段，P6.4 才写值 | 部署后异步截图回填 |
| `deployKey` | P6.4 | `uk_deployKey` 唯一索引，访问路径的一部分 |
| `priority` | P2/P6.6 | 99 = 精选（`AppConstant.GOOD_APP_PRIORITY`） |
| `userId` | P2 | 属主，配合 `idx_userId` |

### 2.2 端点清单（AppController，/app）

| 端点 | 方法 | 权限 | 说明 |
|---|---|---|---|
| `/chat/gen/code` | GET(SSE) | 登录 + **@RateLimit(USER, 5次/60s)** | 对话生成（P3/P4 实现） |
| `/add` | POST | 登录 | 创建应用（调 AI 路由定类型） |
| `/update` | POST | 登录 + **属主** | **只能改 appName** |
| `/delete` | POST | 登录 + **属主或管理员** | 级联删对话历史 |
| `/get/vo` | GET | **无**（公开） | 应用详情（VO 含作者信息） |
| `/my/list/page/vo` | POST | 登录 | 我的应用分页（强制 userId=自己） |
| `/good/list/page/vo` | POST | 无 | 精选应用分页（**@Cacheable**） |
| `/deploy` | POST | 登录 + **属主** | 部署（P6.4） |
| `/download/{appId}` | GET | 登录 + **属主（管理员也不行）** | 下载代码包（P6.5） |
| `/admin/delete` | POST | **管理员** | 管理员删除 |
| `/admin/update` | POST | **管理员** | 可改 priority 等更多字段 |
| `/admin/list/page/vo` | POST | **管理员** | 管理端列表 |
| `/admin/get/vo` | GET | **管理员** | 管理端详情 |

### 2.3 createApp：三个动作

```java
public Long createApp(AppAddRequest appAddRequest, User loginUser) {
    String initPrompt = appAddRequest.getInitPrompt();
    ThrowUtils.throwIf(StrUtil.isBlank(initPrompt), ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");
    App app = new App();
    BeanUtil.copyProperties(appAddRequest, app);      // ① DTO → Entity
    app.setUserId(loginUser.getId());                 // ② 属主 = 当前登录用户（前端传不了）
    app.setAppName(initPrompt.substring(0, Math.min(initPrompt.length(), 12)));  // ③ 暂用前 12 字命名
    // ④ AI 智能选择生成类型（P6.1 详解）
    AiCodeGenTypeRoutingService routingService = aiCodeGenTypeRoutingServiceFactory.createAiCodeGenTypeRoutingService();
    CodeGenTypeEnum selectedCodeGenType = routingService.routeCodeGenType(initPrompt);
    app.setCodeGenType(selectedCodeGenType.getValue());
    ThrowUtils.throwIf(!this.save(app), ErrorCode.OPERATION_ERROR);
    return app.getId();
}
```

要点：

1. **`setUserId(loginUser.getId())` 必须由服务端设置**——**绝不能信任前端传的 userId**，否则可以伪造"我的应用是别人创建的"。这是"服务端赋值"的基本原则。
2. **`appName` 是临时截取**：真正的命名需求应让 AI 生成或用户输入，这里用 `initPrompt` 前 12 字占位。**`substring` 是字符级截取**，中文安全，但纯英文需求会截成半个单词（体验瑕疵，不是 bug）。
3. **AI 路由失败 = 创建失败**（无兜底），这是 `createApp` 链路的已知行为（对比 LangGraph 工作流节点的兜底策略，见 P6.7）。
4. **`codeGenType` 在这一步写入数据库**——它是"应用的一生"里第一个被固化的决策，后续生成/下载/部署全靠它反拼目录。

### 2.4 属主校验：四处对比（本阶段最重要的心智模型）

同一个"是不是我的应用"判断，在项目里出现了**四种不同强度**：

| 位置 | 判断条件 | 强度 |
|---|---|---|
| `/update` | `oldApp.getUserId().equals(loginUser.getId())` | 仅属主 |
| `/delete` | 属主 **或** `ADMIN_ROLE` | 属主或管理员 |
| `/deploy` | 仅属主（`deployApp` 内） | 仅属主 |
| `/download/{appId}` | 仅属主（**管理员也不行**） | 仅属主（最严） |
| `/get/vo` | **无校验**（公开可读） | 无 |

**为什么下载连管理员都不行？** 注释写明"只有应用创建者可以下载代码"——把"代码"视为用户的私有产物，管理员只承担平台治理（删违规应用），不应能带走用户代码。这是**有意的隐私边界**，不是漏写。

**为什么 `get/vo` 可以公开？** 因为精选应用需要对外展示。**副作用**：任意 `id` 都能被枚举访问，未公开应用的 `initPrompt`（可能含用户隐私）也会被读到——**这是本项目真实存在的信息暴露面**。

**为什么校验写在 Controller 而不在 Service（除 deploy）？** 这是一种"就地校验"的写法：`update/delete/download` 在 Controller 里查完应用再判属主；`deploy/chat` 把校验放进 Service。**不一致**——复现时建议统一放 Service（业务规则应属于业务层），但要知道原项目是混用的。

### 2.5 VO 与 N+1：一处"优化没做彻底"的真实问题

`getAppVO` 会把 `initPrompt` 等字段拷进 VO，**并额外查一次用户表**补上作者信息：

```java
public AppVO getAppVO(App app) {
    AppVO appVO = new AppVO();
    BeanUtil.copyProperties(app, appVO);
    Long userId = app.getUserId();
    if (userId != null) {
        User user = userService.getById(userId);      // ← 每条记录一次查询
        appVO.setUser(userService.getUserVO(user));
    }
    return appVO;
}
```

`getAppVOList` 的注释写着"批量获取用户信息，**避免 N+1 查询问题**"，它确实预先查了一次 `listByIds` 建了 `userVOMap`：

```java
Set<Long> userIds = appList.stream().map(App::getUserId).collect(Collectors.toSet());
Map<Long, UserVO> userVOMap = userService.listByIds(userIds).stream()
        .collect(Collectors.toMap(User::getId, userService::getUserVO));
return appList.stream().map(app -> {
    AppVO appVO = getAppVO(app);          // ← 但这里内部又查了一次 user！
    appVO.setUser(userVOMap.get(app.getUserId()));   // ← 然后才覆盖成批量结果
    return appVO;
}).collect(Collectors.toList());
```

**问题**：`getAppVO(app)` 内部仍然执行了 `userService.getById(userId)`——**N+1 并没有被消除**，批量查询的结果只是"覆盖"掉了刚查出来的值。也就是说：**每页 20 条应用，仍然会多打 20 次用户查询**。这是一个"看起来优化了、实际没生效"的典型例子，值得作为复现时的反面教材（正确做法：让 `getAppVO` 支持传入已查好的 User，或干脆用 JOIN）。

### 2.6 getQueryWrapper：统一条件构造 + 两个注意点

```java
return QueryWrapper.create()
        .eq("id", id)
        .like("appName", appName)
        .like("cover", cover)
        .like("initPrompt", initPrompt)
        .eq("codeGenType", codeGenType)
        .eq("deployKey", deployKey)
        .eq("priority", priority)
        .eq("userId", userId)
        .orderBy(sortField, "ascend".equals(sortOrder));
```

1. **`orderBy(sortField, ...)` 的字段名来自前端请求**——列名参与 SQL 拼接，属于**排序字段注入的潜在风险点**（应做列名白名单/合法性校验）。
2. **对 `initPrompt`（text 类型）用 `like`** —— 大字段模糊匹配性能差；且它与 `cover` 一起被 `like` 匹配，实际业务里几乎不会用到。

**这个方法的定位**：把"请求 → 查询条件"的翻译集中到一处，Controller 无论查我的列表还是管理端列表都复用它（`getQueryWrapper` 是同名模式，UserService 里也一样）。

### 2.7 删除的级联：override removeById

```java
@Override
public boolean removeById(Serializable id) {
    if (id == null) return false;
    long appId = Long.parseLong(id.toString());
    if (appId <= 0) return false;
    // 先删除关联的对话历史
    try {
        chatHistoryService.deleteByAppId(appId);
    } catch (Exception e) {
        log.error("删除应用关联的对话历史失败：{}", e.getMessage());   // ← 吞掉异常
    }
    return super.removeById(id);
}
```

**为什么 override 框架的 `removeById`？** 因为"删应用"不只是删一行数据，还要清理它的对话历史——把级联逻辑**收在 Service 内部**，所有调用方（`/delete`、`/admin/delete`）自动获得一致行为。这正是前面"按资源组织"的体现：**AppService 负责 App 这个资源的一切**。

**坑**：级联删除用 `try-catch` 吞异常——**对话历史删失败时，应用照样被删掉**，留下孤儿对话数据（`chat_history.appId` 指向不存在的应用）。这是"保证主流程不被附加清理阻塞"的取舍，但会积累脏数据。

### 2.8 精选列表 + 注解缓存

```java
@PostMapping("/good/list/page/vo")
@Cacheable(
        value = "good_app_page",
        key = "T(com.yupi.yuaicodemother.utils.CacheKeyUtils).generateKey(#appQueryRequest)",
        condition = "#appQueryRequest.pageNum <= 10"
)
public BaseResponse<Page<AppVO>> listGoodAppVOByPage(@RequestBody AppQueryRequest appQueryRequest) {
    appQueryRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);   // 只查精选
    ...
}
```

三个设计点：

| 设计 | 为什么 |
|---|---|
| `condition = "#appQueryRequest.pageNum <= 10"` | 只缓存前 10 页——精选应用通常只看前几页，深分页缓存没价值且会撑爆缓存 |
| `key` 用 `CacheKeyUtils.generateKey(请求对象)` | 把"请求参数"整体作为缓存键（比手写 `#req.pageNum + '-' + #req.appName` 更稳） |
| `value = "good_app_page"` | 对应 Redis 缓存区，TTL 来自 `spring.data.redis.ttl: 3600` |

**真实的坑：缓存没有主动失效。** 管理员把某应用 `priority` 设为 99（精选）后，`good_app_page` 缓存**不会立即清除**，用户要等 TTL 过期（最长 1 小时）才看到新精选。这与前面讲过的"多层缓存过期策略必须对齐"是同类问题。修法是管理员更新 priority 时显式 `@CacheEvict(value="good_app_page", allEntries=true)`。

### 2.9 阶段 2 验收标准

| # | 验收项 | 怎么验 |
|---|---|---|
| 1 | 创建成功 | POST `/api/app/add` 返回 id；库里 `userId` 是当前登录用户 |
| 2 | 类型字段落库 | 记录体里 `codeGenType` 有值（P2 阶段可先手工指定 HTML） |
| 3 | 我的列表隔离 | 用户 A 调 `/my/list/page/vo` 只能看到自己的应用 |
| 4 | 越权被拦 | 用户 B 用 A 的 appId 调 `/update`、`/delete`、`/deploy`、`/download` → `code:40101` |
| 5 | 删除级联 | 删除应用后，其 `chat_history` 记录也被删 |
| 6 | 分页上限 | `/my/list/page/vo` 传 `pageSize>20` → 报错 |
| 7 | VO 脱敏与作者 | 返回体里是 `AppVO`（含 `user` 字段，且 user 里无密码） |

### 2.10 阶段 2 坑清单

| # | 坑 | 表现 | 处理 |
|---|---|---|---|
| 1 | 信任前端 userId | 可伪造应用归属 | 服务端强制 `setUserId(loginUser)` |
| 2 | 属主校验写法不统一 | 有的在 Controller、有的在 Service | 建议统一收进 Service |
| 3 | `getAppVOList` 的 N+1 未真正消除 | 每页多打 N 次用户查询 | 让 VO 组装支持外部传入 User |
| 4 | 级联删对话历史吞异常 | 残留孤儿对话数据 | 记录并补偿，或加外键约束 |
| 5 | `orderBy(sortField)` 列名注入面 | 前端可传任意排序字段 | 列名白名单校验 |
| 6 | 精选缓存无失效 | 设为精选后 1 小时内不生效 | 管理员更新时 `@CacheEvict` |
| 7 | `get/vo` 无鉴权 | 可枚举读到未公开应用的 initPrompt | 敏感字段按优先级过滤 |
| 8 | appName 机械截取 | 英文需求被截半个单词 | 后续改为 AI 生成名称 |
| 9 | 创建时 AI 路由失败即创建失败 | 模型抖动导致建应用失败 | 加兜底或重试 |

### 2.11 心法 + Checklist

**心法**：阶段 2 教会你两件事——① **"谁能碰这条数据"必须显式写出来**（四种强度不是随手写的，是业务边界）；② **DTO→Entity 的赋值里，"归属类"字段必须服务端决定**（`userId` 一旦信任前端，整个权限体系就塌了）。

| # | 自问 | 答案 |
|---|---|---|
| 1 | `codeGenType` 何时写入、谁决定？ | `createApp` 时由 AI 路由决定（P6.1）；P2 可先手工指定 |
| 2 | 为什么 `setUserId` 不能来自前端？ | 可伪造归属，权限体系失效 |
| 3 | 下载为什么管理员也不行？ | 代码视为用户私有产物，有意的隐私边界 |
| 4 | `getAppVOList` 真的避免了 N+1 吗？ | 没有——内部 `getAppVO` 仍逐条查 user |
| 5 | 精选缓存为什么只缓存前 10 页？ | 深分页命中率低、防缓存膨胀 |
| 6 | 删除应用会删什么？ | 应用 + 其全部对话历史（override `removeById`） |
| 7 | 为什么查询条件集中在 `getQueryWrapper`？ | 我的列表/管理端列表/精选列表共用一套翻译 |

---

## 阶段 3 讲透：AI 最小生成闭环（非流式 HTML）

> 目标：先不看流式、不看 SSE、不看记忆，**只把「一句话 → 一个文件」这条最短路径跑通**。这是整个 AI 模块的地基，后面所有花哨的东西都是在这条链路上加包装。

### 3.1 先看这条链路有多长

非流式入口只有一个：`AiCodeGeneratorFacade#generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId)`（[AiCodeGeneratorFacade.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L49-L69)）。

```text
调用方（目前只有单测 AiCodeGeneratorFacadeTest）
  └─ AiCodeGeneratorFacade#generateAndSaveCode          ← 门面：按类型分派
       ├─ AiCodeGeneratorServiceFactory#getAiCodeGeneratorService(appId, type)  ← 拿"带记忆的代理对象"
       ├─ AiCodeGeneratorService#generateHtmlCode(userMessage)                  ← 一次同步 AI 调用（结构化输出）
       └─ CodeFileSaverExecutor#executeSaver(result, HTML, appId)               ← 落盘
```

**关键事实（易被误解）**：生产主链路**并不走**非流式方法。全项目搜索 `generateAndSaveCode` 的结果是——只有 [AiCodeGeneratorFacadeTest.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/test/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacadeTest.java#L20-L22) 在调它。真正的业务入口是 `AppServiceImpl#chatToGenCode`，走的是 `generateAndSaveCodeStream`。

**为什么还要保留非流式？** 三条理由，都要能答出来：
1. **它是流式版本的"参照实现"**——流式 = 非流式 + 攒批 + 实时推送，看懂非流式再看流式，复杂度立刻降一半；
2. **它是可测试的**——同步返回 `File`，单测好写（流式返回 `Flux`，不 `blockLast` 就永远不执行）；
3. **它是降级路径**——SSE 通道不可用时可以退回同步（本项目没做，但设计上留着缝）。

### 3.2 第一层：LangChain4j 的"声明式 AI Service"

这个项目**没有**手写 HTTP 去调大模型，而是用 LangChain4j 的 `AiServices` 动态代理。核心是三个文件：

| 文件 | 角色 |
|---|---|
| [AiCodeGeneratorService.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/AiCodeGeneratorService.java) | **只写接口，不写实现**。方法上标注 `@SystemMessage` / `@UserMessage` |
| [AiCodeGeneratorServiceFactory.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/AiCodeGeneratorServiceFactory.java) | 用 `AiServices.builder(接口).build()` 在运行时**生成代理对象** |
| [AiCodeGeneratorFacade.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java) | 业务侧统一入口 |

接口长得极简（[AiCodeGeneratorService.java#L19-L38](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/AiCodeGeneratorService.java#L19-L38)）：

```java
@SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
HtmlCodeResult generateHtmlCode(String userMessage);

@SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
Flux<String> generateHtmlCodeStream(String userMessage);
```

这里是**第一个大心法**：**同一个需求，两种返回类型，就是两个方法**。
- 返回 `HtmlCodeResult`（自定义 POJO）→ LangChain4j 知道你要**结构化输出**，会在提示词里自动追加 schema 描述，并把模型输出反序列化成对象；
- 返回 `Flux<String>` → 它知道你要**逐 token 原文**，直接把流透传给你，不做解析。

**反过来会怎样？** 如果只写流式方法、想自己解析字符串成对象——你要自己写正则、自己处理模型可能不听话的情况；如果只写结构化方法、想流式——拿不到中间态，前端只能干等。所以**两种都要写**，按场景选。

第二个心法是 **`fromResource`**：提示词不写在 Java 里，而是放 [codegen-html-system-prompt.txt](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/resources/prompt/codegen-html-system-prompt.txt)，用资源路径注入。好处是改提示词不用改代码、不用重新编译逻辑、可版本化、可 diff。

### 3.3 结构化输出：HtmlCodeResult 的 @Description 是"契约"

```java
@Description("生成 HTML 代码文件的结果")
public class HtmlCodeResult {
    @Description("HTML代码")
    private String htmlCode;
    @Description("生成代码的描述")
    private String description;
}
```

见 [HtmlCodeResult.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/model/HtmlCodeResult.java#L9-L24)。

追问三连：
- **`@Description` 有什么用？** 它就是喂给模型看的字段说明。模型不知道 `htmlCode` 是啥，但你写成"HTML代码"，它就知道该往这个字段填整段 HTML。
- **不写会怎样？** 字段名会直接当说明用（`htmlCode` → 模型可能理解成"html 的 code"，一般也行，但语义模糊、字段一多就容易填错字段）。
- **模型真能保证返回 JSON 吗？** 不能 100% 保证。所以生产链路里**根本不依赖结构化输出**——HTML/MULTI_FILE 主链路用流式 + 自己正则解析。结构化输出只用在**别的地方**（见 3.6）。

**这就是这份代码最值得抄的设计取舍**：把"不确定性最高"的那一环（模型输出格式），用**两套独立机制**兜底——
- 同步路径：LangChain4j 结构化输出（框架管格式）；
- 流式路径：正则解析（我自己管格式，且提示词里强制写死了代码块格式）。

### 3.4 门面：为什么要有 AiCodeGeneratorFacade

`generateAndSaveCode` 用的是 **Java 21 switch 表达式**（[#L55-L68](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L55-L68)）：

```java
return switch (codeGenTypeEnum) {
    case HTML -> {
        HtmlCodeResult result = aiCodeGeneratorService.generateHtmlCode(userMessage);
        yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.HTML, appId);
    }
    case MULTI_FILE -> { ... }
    default -> {
        String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
    }
};
```

**门面的价值**：把「调 AI（网络、慢、可能失败）」和「解析 + 落盘（纯本地）」这两件事**编排成一件事**，调用方只说"给我生成并保存"，不用关心中间几步。这正是 **Facade（门面模式）** 的标准用法。

追问：**为什么不把 switch 下沉到 Service 里？** 因为 Service 是 AI 能力层（只负责"生成"，不应该知道"文件目录契约"）；落盘和目录命名是 core 层的职责。**分层边界就是这样划的**：`ai` 包管模型交互，`core` 包管编排/解析/落盘。

**`default -> throw` 是必须的吗？** 是。工程约定就是：**多类型分支的 default 必须显式抛异常**，绝不允许"静默走某条分支"或"返回 null"。因为一旦新增枚举值而忘了补分支，静默 fail 比崩溃更难查。

### 3.5 解析层：策略模式 + 执行器（静态工厂）

`core/parser` 目录很小，但把**策略模式**用得很标准：

| 文件 | 职责 |
|---|---|
| [CodeParser.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/CodeParser.java) | 策略接口 `parseCode(String) → T` |
| [HtmlCodeParser.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/HtmlCodeParser.java) | HTML 单文件策略 |
| [MultiFileCodeParser.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/MultiFileCodeParser.java) | 多文件策略 |
| [CodeParserExecutor.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/CodeParserExecutor.java) | 执行器：按类型选中策略 |

执行器本体极简（[#L13-L32](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/CodeParserExecutor.java#L13-L32)）：

```java
private static final HtmlCodeParser htmlCodeParser = new HtmlCodeParser();
private static final MultiFileCodeParser multiFileCodeParser = new MultiFileCodeParser();

public static Object executeParser(String codeContent, CodeGenTypeEnum codeGenTypeEnum) {
    return switch (codeGenTypeEnum) {
        case HTML -> htmlCodeParser.parseCode(codeContent);
        case MULTI_FILE -> multiFileCodeParser.parseCode(codeContent);
        default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
    };
}
```

**注意它是 `static` 的、parser 是 `static final` 实例**——这不是随便写的，而是刻意的：**解析是「无状态纯计算」**，没有成员变量、不碰数据库、不碰 IoC，所以**根本不需要注入 Spring**。这正是工程约定里那条"无状态纯计算类使用静态常量持有，无需注入 IoC 容器"。

**为什么用正则而不用 JSON 解析？** 因为流式路径下，模型吐的是**带 markdown 代码块的原文**（前面有"好的，我来帮你实现…"，后面有"以上就是完整代码"），必须先"从散文里切出代码块"。正则就是干这个的。

`HtmlCodeParser` 的正则（[#L15](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/HtmlCodeParser.java#L15)）：

```java
Pattern.compile("```html\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE)
```

拆开看每个字符的意图，**这是必须能背出来的**：
- ```` ```html ```` —— 锚定代码块语言标记，区分 html/css/js；
- `\s*\n` —— 语言标记后允许空白再换行（模型有时写 ```` ```html\n ````，有时 ```` ```html  \n ````）；
- `([\s\S]*?)` —— **惰性匹配任意字符（含换行）**，`[\s\S]` 是因为 `.` 默认不跨行；`*?` 惰性是为了**在遇到第一个 ` ``` ` 就停**，否则会把后面所有代码块全部吞掉；
- `Pattern.CASE_INSENSITIVE` —— 模型可能写 ```` ```HTML ````。

**兜底设计**：正则匹配不到时（[#L22-L27](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/HtmlCodeParser.java#L22-L27)），它把**整段回包**当成 HTML（`result.setHtmlCode(codeContent.trim())`）。这是个"宁滥勿缺"的兜底——大不了文件里多几句废话，但**不会因为格式不规范就整个失败**。

**这个兜底有副作用吗？** 有。如果模型回了一大段解释、且里面没有代码块，你保存出来的 `index.html` 就是一堆中文散文（浏览器能渲染，但页面是坏的）。而且不会有任何报错。这是**静默失败**，属于已知坑（见 3.11）。

### 3.6 落盘层：模板方法模式（面试高频）

`core/saver` 是**模板方法模式**的教科书实现：

```
CodeFileSaverTemplate<T>              ← 抽象父类，final 锁死流程骨架
  ├─ HtmlCodeFileSaverTemplate        ← 单文件：只写 index.html
  └─ MultiFileCodeFileSaverTemplate   ← 多文件：写 index.html + style.css + script.js
```

父类的 `final` 模板方法（[CodeFileSaverTemplate.java#L32-L41](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/CodeFileSaverTemplate.java#L32-L41)）：

```java
public final File saveCode(T result, Long appId) {
    validateInput(result);              // 1. 校验（钩子，子类可覆盖）
    String baseDirPath = buildUniqueDir(appId);  // 2. 建目录（固定，含契约）
    saveFiles(result, baseDirPath);     // 3. 写文件（抽象方法，子类必须实现）
    return new File(baseDirPath);       // 4. 返回目录
}
```

**为什么 `saveCode` 必须 `final`？** 因为**流程骨架不能被子类改**。骨架 = "先校验、再建目录、再写文件、再返回目录"，这个顺序是**契约**。如果允许子类覆盖，某个子类忘了 `buildUniqueDir`（自己拼目录名），目录契约立刻被打破，下游（部署、下载、预览）全部找不到文件。

**为什么 `saveFiles` 是 `abstract`？** 因为它才是"真正变化的部分"——单文件写几个文件、多文件写几个文件、Vue 走工具调用。**不变的锁死（final），可变的放开（abstract）**，这就是模板方法的全部精髓。

父类还提供了 `writeToFile` 工具方法（[#L50-L55](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/CodeFileSaverTemplate.java#L50-L55)）：

```java
public final void writeToFile(String dirPath, String filename, String content) {
    if (StrUtil.isNotBlank(content)) {   // ← 空内容静默跳过
        String filePath = dirPath + File.separator + filename;
        FileUtil.writeString(content, filePath, StandardCharsets.UTF_8);
    }
}
```

**`isNotBlank` 判断是第二个关键设计**：多文件模式下，模型可能只给 HTML、不给 CSS/JS（提示词不强制）。这时 `writeToFile` 静默跳过——最终目录里只有 `index.html`，**不报错**。这与工程约定一致："多文件生成模式下仅强制要求 index.html 存在，CSS/JS 缺失时静默跳过不报错"。

**反过来会怎样（如果改为抛异常）？** 模型偶尔不听话、少写一个 CSS 块，整个生成流程直接失败，用户白等一次 AI 调用。**这是"宽松容忍"型设计**，代价是——如果你后面加了"复用目录"逻辑（不清理旧文件），**上次的 style.css 会残留**，页面用的是旧样式（这是真实坑，见 [项目记忆] 中的"目录复用模式下旧文件不会自动清理"）。

### 3.7 目录契约：全链路唯一的那根"绳子"

父类 `buildUniqueDir`（[#L74-L83](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/CodeFileSaverTemplate.java#L74-L83)）：

```java
String uniqueDirName = StrUtil.format("{}_{}", codeType, appId);
String dirPath = FILE_SAVE_ROOT_DIR + File.separator + uniqueDirName;
```

即 **`{codeType}_{appId}`**，根目录来自 [AppConstant.java#L21](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/constant/AppConstant.java#L21) 的 `CODE_OUTPUT_ROOT_DIR`。

**这是全项目最重要的隐式契约**。这套命名被**四处"反拼"**（即从 appId 反推目录名）：

| # | 位置 | 用途 | 拼接方式 |
|---|---|---|---|
| 1 | 生成落盘（Saver） | 写文件 | `codeType + "_" + appId` |
| 2 | 部署 `deployApp` | 找源目录 | `codeGenType + "_" + appId` |
| 3 | 下载 `downloadAppCode` | 找源目录 | `codeGenType + "_" + appId` |
| 4 | Vue 构建 | 找工程目录 | `"vue_project_" + appId` |

**为什么这么说重要？** 因为这四处是**字符串硬编码在两个不同层次的类里**（`core.saver` 和 `service.impl`）。没有常量、没有枚举、没有校验。**改一处不改另一处 → 页面 404、下载失败、部署失败**，而且线上表现是"文件不存在"，不会告诉你"目录名拼错了"。

**复现建议**：自己写的时候，把这个拼接**提成一个公共方法**（比如 `AppConstant.buildCodeDirName(type, appId)`），四处都调它。这是你能对原始代码做的**第一个真实改进**——也是面试里能加分的点。

### 3.8 prompt ↔ 解析器：强绑定契约

看 [codegen-html-system-prompt.txt](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/resources/prompt/codegen-html-system-prompt.txt#L14-L23) 的两段约定：

```text
9. 输出格式: 你的最终输出必须包含 HTML 代码块 ... 格式如下：
```html
... HTML 代码 ...
```

特别注意：在生成代码后，用户可能会提出修改要求...
2. 确保始终最多输出 1 个 HTML 代码块...（而不是要修改的部分代码）。
3. 一定不能输出超过 1 个代码块，否则会导致保存错误！
```

**看出绑定关系了吗？**
- 提示词**强制"最多 1 个 html 代码块"** ↔ 解析器的 `matcher.find()` **只取第一个匹配**；
- 提示词**要求完整页面而非修改片段** ↔ 解析器**直接全量覆盖写文件**（TRUNCATE 语义）；
- 提示词**要求 ```html 标记** ↔ 正则锚定 ```` ```html ````。

**这就是工程约定里的"正则解析器与提示词是强绑定契约，修改任意一方必须同步校验另一方"**。

**反过来想（如果提示词要求输出 2 个代码块）**：解析器只取第一个 → 第二个块被静默丢弃 → 用户以为改了，其实没生效。**没有异常、没有日志**，只是结果不对。这类 bug 极难查。

### 3.9 验收标准

| # | 场景 | 期望 |
|---|---|---|
| 1 | 调 `generateAndSaveCode("生成登录页", HTML, 1L)` | 返回 `File`，路径 = `{user.dir}/tmp/code_output/html_1` |
| 2 | 看该目录 | 有 `index.html`，内容为完整 HTML（无中文旁白） |
| 3 | 调 `..., MULTI_FILE, 2L` | 目录 `multi_file_2`，有 `index.html`；`style.css`/`script.js` 视模型输出而定 |
| 4 | 模型只回 HTML 无 CSS | 不报错，只生成 `index.html` |
| 5 | 传 `VUE_PROJECT` | 抛 `SYSTEM_ERROR`（不支持）——**`default -> throw` 生效** |
| 6 | 传 `null` 类型 | 抛 `PARAMS_ERROR` "生成类型不能为空" |

### 3.10 阶段 3 坑清单

| # | 坑 | 表现 | 处理 |
|---|---|---|---|
| 1 | 正则无匹配时整段兜底 | 模型回解释文字 → `index.html` 变成散文，无报错 | 加"是否含 `<html`/`<body`"校验 |
| 2 | 只取第一个代码块 | 模型违规输出多块 → 后块静默丢弃 | 提示词强约束 + 解析后校验完整性 |
| 3 | 目录名四处硬编码 | 改一处漏一处 → 文件找不到 | 抽公共方法 |
| 4 | `writeToFile` 静默跳过 | 复用目录时旧文件残留 | 生成前清空目录 |
| 5 | 非流式方法实际未被生产调用 | 误以为主链路是同步的 | 认清主链路是 streaming |
| 6 | `AiCodeGeneratorFacade` 有循环依赖风险 | Factory ↔ ChatHistoryService | 用 `@Lazy` / `SpringContextUtil` 延迟取 |

### 3.11 心法 + Checklist

**心法**：阶段 3 只有一句话——**"把 AI 的不确定性，用确定性的流程包起来"**。AI 只负责"内容"，流程负责"格式、目录、落盘、兜底"。你写的所有代码，本质都是**给模型输出加护栏**。

| # | 自问 | 答案 |
|---|---|---|
| 1 | 一句话从哪进、到哪出？ | `generateAndSaveCode` → 代理 → 结构化结果 → Saver → `tmp/code_output/{type}_{appId}` |
| 2 | AI 接口为什么只有声明没有实现？ | `AiServices.builder()` 运行时生成动态代理 |
| 3 | 结构化输出和非流式为什么绑一起？ | 流式拿不到完整 JSON，没法反序列化 |
| 4 | 解析用策略 + 执行器的好处？ | 新增类型只加一个 parser + 一个 case |
| 5 | `final saveCode` 防的是什么？ | 防子类改流程骨架、破坏目录契约 |
| 6 | 目录名契约有几处调用点？ | 4 处（生成/部署/下载/Vue 构建） |
| 7 | 改提示词要动代码吗？ | 不用，`fromResource` 加载 txt |
| 8 | 正则和提示词什么关系？ | 强绑定契约，改一必须验二 |

---

## 阶段 4 讲透：流式 SSE + 预览

> 目标：把阶段 3 的"等 30 秒拿结果"升级成"边生成边显示"，并让生成好的页面**能被浏览器直接打开**。

### 4.1 为什么必须流式

一次复杂页面的代码生成可能要几十秒。同步请求会面临三个问题：
1. **浏览器超时**（很多网关/浏览器默认 30s~60s）；
2. **用户没有反馈**，以为卡死了；
3. **无法中断**，用户想改提示词只能干等。

解法只有一条：**SSE（Server-Sent Events）**——服务端单向持续推送文本事件。这就是工程约定里的"长耗时流式接口统一使用 SSE 协议，避免浏览器超时"。

**为什么不用 WebSocket？** 因为这里的通信是**单向的**（服务端推、客户端只负责看），SSE 基于普通 HTTP、无需握手升级、天然支持断线重连，**够用就不引入复杂度**。

### 4.2 三层包装：TokenStream/Flux → SSE 事件 → 双层 JSON

这是阶段 4 最烧脑的地方。跟着数据流走一遍，就不会乱：

**第 1 层：模型层产出 Flux（或 TokenStream）**
`AiCodeGeneratorServiceFactory#createAiCodeGeneratorService` 按类型建两种服务：
- HTML / MULTI_FILE → `Flux<String>`（[AiCodeGeneratorFacade#L86-L93](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L86-L93)）；
- VUE_PROJECT → `TokenStream`（[#L94-L97](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L94-L97)），因为要携带**工具调用**事件。

**第 2 层：Facade 包装（加解析落盘 / 事件转 JSON）**
- HTML/MULTI_FILE 走 `processCodeStream`——攒字符串，流结束再解析落盘（[#L148-L167](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L148-L167)）；
- VUE 走 `processTokenStream`——把 `TokenStream` 的回调（`onPartialResponse` / `onPartialToolExecutionRequest` / `onToolExecuted` / `onCompleteResponse` / `onError`）翻译成 **JSON 字符串**塞进 `Flux<String>`（[#L112-L138](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L112-L138)）。

这一步是**适配器（Adapter）**思路：**把不同形态的流，统一成 `Flux<String>`**，上层只认这一种类型。

**第 3 层：Controller 包装成 SSE 事件**（[AppController.java#L59-L86](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/AppController.java#L59-L86)）：

```java
@GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60, message = "AI 对话请求过于频繁，请稍后再试")
public Flux<ServerSentEvent<String>> chatToGenCode(@RequestParam Long appId,
                                                   @RequestParam String message,
                                                   HttpServletRequest request) {
    ...
    Flux<String> contentFlux = appService.chatToGenCode(appId, message, loginUser);
    return contentFlux
            .map(chunk -> {
                Map<String, String> wrapper = Map.of("d", chunk);
                String jsonData = JSONUtil.toJsonStr(wrapper);
                return ServerSentEvent.<String>builder().data(jsonData).build();
            })
            .concatWith(Mono.just(
                    ServerSentEvent.<String>builder().event("done").data("").build()
            ));
}
```

**这里有三个必须讲清的细节：**

**① 为什么是 `GET` 而不是 `POST`？**
因为 SSE 的原生实现是 `EventSource`，而浏览器 `EventSource` **只支持 GET + query 参数**。所以提示词走 `@RequestParam String message`。代价是**提示词会出现在 URL 和访问日志里**（长度也受限）。这是**为兼容浏览器 API 做的妥协**，属于已知坑。

**② 为什么要 `Map.of("d", chunk)` 再 `toJsonStr`——两层 JSON？**
因为 `chunk` 本身可能是**任意文本**（HTML 里有换行、有 `data:` 字样、甚至可能包含 `\n\n`）。SSE 协议格式是：

```
data: xxx
<空行>
```

如果直接把原始 HTML 塞进 `data:`，一旦 HTML 里出现换行，**SSE 帧结构就被破坏了**，前端会收到断裂的事件。

所以要把内容**先 JSON 序列化成一行**（`{"d":"..."}`，JSON 转义会把换行变成 `\n` 字面量），再作为 `data:` 的值。**这就是"双层包装"的原因**：外层是 SSE 协议帧，内层是 JSON 载荷。

**③ 为什么要 `concatWith` 一个 `event("done")`？**
因为 HTTP 流的结束**只能靠约定**。`Flux` 的 `onComplete` 在 SSE 里会被翻译成"关闭连接"，但前端此时可能还在处理最后一批数据，无法区分"正常结束"和"连接被切断"。所以**额外发一个语义化事件 `done`**，前端收到它就收尾（关 loading、刷新列表）。

**反过来会怎样？** 不发 `done`——前端得靠 `onerror`/连接关闭来判断，容易把"生成成功"误判成"失败"。

### 4.3 攒批：为什么必须"流完全结束后"才解析落盘

`processCodeStream`（[#L148-L167](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L148-L167)）：

```java
StringBuilder codeBuilder = new StringBuilder();
return codeStream
        .doOnNext(chunk -> codeBuilder.append(chunk))   // ① 边推边攒
        .doOnComplete(() -> {                            // ② 流结束才解析落盘
            try {
                String completeCode = codeBuilder.toString();
                Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
                File saveDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
                log.info("保存成功，目录为：{}", saveDir.getAbsolutePath());
            } catch (Exception e) {
                log.error("保存失败: {}", e.getMessage());   // ③ 吞异常
            }
        });
```

**这里面藏着两个设计决策，一个坑：**

**决策 1：为什么不能"边收边写文件"？**
因为解析需要**完整文本**（正则要匹配成对的 ```` ``` ````）。代码块还没传完就去匹配，正则只会匹配失败或匹配到残缺内容。

**决策 2：为什么推给前端的仍是原始 chunk，而不是攒批后的结果？**
**内存与网络解耦**：前端要的是"实时感"（逐字出现），磁盘要的是"完整正确"。两者需求不同，所以**同一个流分两路消费**——一路 `doOnNext` 只攒不推（给落盘用），一路直接返回给 Controller（给前端用）。

**坑 3：`catch (Exception e)` 吞异常 + 只 log（[#L163-L165](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L163-L165)）**
**这是本阶段最重要的坑**。后果链条：
- 保存失败 → 日志里一条 error；
- `Flux` 仍然正常 `onComplete`；
- Controller 照样发 `done` 事件；
- **前端显示"生成成功"，但磁盘上没有文件**；
- 用户点预览 → 404。

**正确处理**：要么在 doOnComplete 里把异常**转换成 `sink.error`**（让流失败，前端能感知），要么**把 saveDir 结果带进事件里**（发一个 `saved` 事件）。这个项目**两样都没做**。

### 4.4 静态资源预览：StaticResourceController

生成完的页面怎么给用户看？两种做法：
- **A. 让前端拿代码自己渲染**（`iframe + srcdoc`）——简单但 html 里若有 `<script>` 加载外部资源、或相对路径引用，就会失效；
- **B. 后端把生成目录当静态资源暴露**（本项目的做法）。

[StaticResourceController.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/StaticResourceController.java) 用**通配符映射**：

```java
@GetMapping("/{deployKey}/**")
public ResponseEntity<Resource> serveStaticResource(@PathVariable String deployKey,
                                                    HttpServletRequest request) {
```

访问格式：`http://localhost:8123/api/static/{deployKey}[/{fileName}]`。

**四个关键细节：**

**① `deployKey` 在这里扮演什么角色？**
注意名字——它叫 `deployKey`，但根目录用的是 `PREVIEW_ROOT_DIR = AppConstant.CODE_OUTPUT_ROOT_DIR`（**生成目录**，不是部署目录）。**这说明这个接口本意是"预览生成目录"**，靠 `deployKey` 当目录名。而实际目录名契约是 `{codeType}_{appId}`，`deployKey` 却是 6 位随机串（见阶段 6 部署）。

**这是一个真实的语义错位**：前端预览时传进来的 `deployKey`，实际值是 `{codeType}_{appId}`（目录名）。**变量名沿用了部署语境，语义却是生成目录**。属于"可读性坑"，复现时建议改名 `dirName`。

**② 目录访问重定向**（[#L41-L45](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/StaticResourceController.java#L41-L45)）：
```
/static/html_1  →  301  /static/html_1/
```
为什么？因为**相对路径的基准**。访问 `index.html` 时，若 URL 是 `/static/html_1`，浏览器会把 `style.css` 解析成 `/static/style.css`（错）；若 URL 是 `/static/html_1/`，则解析成 `/static/html_1/style.css`（对）。所以**必须补斜杠**。

**③ 默认文件**（[#L47-L49](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/StaticResourceController.java#L47-L49)）：`resourcePath.equals("/") → "/index.html"`。

**④ Content-Type 手写映射**（[#L70-L77](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/StaticResourceController.java#L70-L77)）：因为 `FileSystemResource` 走的是通用返回，Spring 不会自动推断后缀类型，必须自己告诉浏览器"这是 html 还是 js"。
**关键点：HTML/CSS/JS 都带 `charset=UTF-8`**——不带的话，中文页面会乱码。

**安全追问（面试必问）**：`deployKey` 是路径变量，直接拼进 `filePath`。**有路径穿越风险吗？**
`String filePath = PREVIEW_ROOT_DIR + "/" + deployKey + resourcePath;`
理论上 `deployKey = ../../etc` 可以逃逸目录。但实际受两个限制：(1) Spring 的路径变量默认**不会包含 `/`**（`{}` 段不跨斜杠），(2) `**` 部分会被 Spring 归一化。所以**风险不高，但工程上应该显式校验**（比如 `deployKey` 必须是 `[a-zA-Z0-9_]+`）。这是**能主动指出来的加分点**。

### 4.5 验收标准

| # | 场景 | 期望 |
|---|---|---|
| 1 | 浏览器开 `/api/app/chat/gen/code?appId=1&message=xxx` | 看到逐块出现的 `data: {"d":"..."}` |
| 2 | 流末尾 | 收到一条 `event: done` |
| 3 | 生成完成后访问 `/api/static/html_1/` | 返回 301 → 再访问得到页面 |
| 4 | 访问 `/api/static/html_1/`（带斜杠） | 直接返回 `index.html`，`Content-Type: text/html; charset=UTF-8` |
| 5 | 中文页面 | 不乱码 |
| 6 | 1 分钟内第 6 次请求 | 报 `TOO_MANY_REQUEST`"AI 对话请求过于频繁" |
| 7 | 第 7、8 次 | 每次都返回同一个错误（**不用等 1 小时**，见 4.7 坑 3） |

### 4.6 阶段 4 坑清单

| # | 坑 | 表现 | 处理 |
|---|---|---|---|
| 1 | `processCodeStream` 吞异常 | 前端显示成功、磁盘无文件、预览 404 | 转成 `sink.error` 或发 `saved` 事件 |
| 2 | SSE 数据未 JSON 转义 | HTML 里的换行破坏 SSE 帧 | 必须 `Map.of("d",chunk)` + `toJsonStr` |
| 3 | 提示词走 GET query | 日志泄露 + 长度受限 | 可改用 POST + 手动 SSE 或 `@RequestBody` 分片 |
| 4 | `deployKey` 当目录名用 | 命名与语义不符，易误读 | 改名为 `dirName` |
| 5 | 静态资源无 `..` 校验 | 理论路径穿越面 | 正则白名单校验路径变量 |
| 6 | `done` 事件无错误语义 | 失败也发 done | 增加 `event: error` |
| 7 | 无心跳 | 长连接可能被中间层掐断 | 定期发注释帧 `:ping` |

### 4.7 心法 + Checklist

**心法**：阶段 4 的核心是**"一个流，两种消费"**——对用户要**实时**，对磁盘要**完整**；对用户推原始字节，对磁盘攒完整文本。**别想着用一份数据同时满足两个需求。**

| # | 自问 | 答案 |
|---|---|---|
| 1 | 为什么要 SSE 而不是同步？ | 避免浏览器/网关超时，给用户实时反馈 |
| 2 | 为什么是 GET？ | 浏览器 `EventSource` 只支持 GET |
| 3 | 双层 JSON 分别挡什么？ | 外层挡 SSE 帧破坏，内层做载荷转义 |
| 4 | 为什么流结束才落盘？ | 正则需要完整文本；解析与推送解耦 |
| 5 | 预览为什么要有斜杠重定向？ | 保证相对路径解析基准正确 |
| 6 | 为什么手写 Content-Type？ | `FileSystemResource` 不会自动推断；且要 UTF-8 |
| 7 | 流式失败用户能感知吗？ | **不能**（异常被吞 + 仍发 done）——已知缺陷 |

---

## 阶段 5 讲透：对话历史与记忆

> 目标：让 AI"记得"前面聊过什么，并让用户翻得到历史。这一阶段是全项目**最容易"乱"**的地方，因为它是**三层存储 + 两条写入链路 + 两条读取链路**。

### 5.1 先画一张图，把"乱"消掉

```text
┌──────────── 三层存储（不是三个"同样的"缓存）────────────┐

 ① MySQL  chat_history 表        ← 冷存储：全量、永久、可翻页
 ② Redis  RedisChatMemoryStore   ← 热存储：LangChain4j 记忆的后端
 ③ 浏览器 前端内存/组件状态        ← 渲染态：当前会话已经在屏上的内容

┌──────────── 两条写入链路 ────────────┐
 写 1：用户消息   AppServiceImpl#chatToGenCode 第 5 步（调 AI **之前**）
 写 2：AI 消息     StreamHandlerExecutor → Simple/Json handler 的 doOnComplete

┌──────────── 两条读取链路 ────────────┐
 读 1：AI 记忆重建  loadChatHistoryToMemory（实例创建时一次性回填）
 读 2：用户翻历史  ChatHistoryController#listAppChatHistory（游标分页）
```

**先记住一句话**：**MySQL 是"账本"，Redis 是"AI 的短期记忆"，浏览器是"屏幕上的字"**。三者用途不同，所以**不是冗余，而是分工**。

追问：**为什么不用 Redis 一个就够？**
- Redis 会过期、可能丢、不适合做"用户要翻的永久记录"；
- 而且 LangChain4j 的 `MessageWindowChatMemory` 只保留**最近 N 条**（本项目 20），超出就丢——你把历史存在这里，用户翻不到第 21 条之前的。

**反过来会怎样（只存 MySQL 不存 Redis）？** 每次对话都要**把完整历史重新喂给模型**——token 消耗暴涨、响应变慢，且长对话根本塞不进上下文。

### 5.2 表结构（`chat_history`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | 唯一有序 ID |
| `appId` | bigint | 归属应用 |
| `userId` | bigint | 归属用户 |
| `message` | text | 消息内容 |
| `messageType` | varchar | `user` / `ai`（见 [ChatHistoryMessageTypeEnum.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/model/enums/ChatHistoryMessageTypeEnum.java)） |
| `createTime` | datetime | **游标分页依据** |

索引：`idx_appId_createTime`（**这是游标分页能走索引的关键**）。

### 5.3 写入链路 1：用户消息——为什么必须"先落库、再调 AI"

[AppServiceImpl.java#L100-L101](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L100-L101)：

```java
// 5. 在调用 AI 前，先保存用户消息到数据库中
chatHistoryService.addChatMessage(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());
// 6. 设置监控上下文 ...
// 7. 调用 AI 生成代码（流式）
Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId);
```

**为什么顺序不能反？** 三个理由，缺一不可：
1. **时序可追溯**：数据库里 `createTime` 顺序必须真实反映对话顺序。若先调 AI（可能 30 秒）再落库，用户消息的时间戳会**晚于**AI 响应时间，历史排序就乱了；
2. **失败可追溯**：AI 调用失败时，用户"问了什么"必须已留痕，否则你不知道失败的是哪次请求；
3. **记忆一致性**：AI 服务实例在**创建时**会回填历史到 Redis 记忆。这次用户消息如果没提前落库，回填就少一条，AI 会"忘掉"用户刚说的话。

**这是项目记忆里的硬约束**："对话历史写入必须在调用 AI 之前先落库用户消息"。

`addChatMessage`（[ChatHistoryServiceImpl.java#L44-L61](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ChatHistoryServiceImpl.java#L44-L61)）做四件事：参数校验 → **消息类型白名单校验** → 构造实体 → `save`。

**"消息类型白名单"这个校验有用吗？** 非常有用。因为 `messageType` 是**字符串**而不是枚举（为了 DTO 灵活性），字符串就可能被写进 `"USER"`、`"system"`、`"admin"` 之类的脏值。白名单一拦，脏数据进不了库。**这是"用字符串传枚举"的必要补偿**。

### 5.4 写入链路 2：AI 消息——挂在流的 `doOnComplete` 上

[StreamHandlerExecutor.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/handler/StreamHandlerExecutor.java) 按类型选 handler：

```java
return switch (codeGenType) {
    case VUE_PROJECT -> jsonMessageStreamHandler.handle(...);
    case HTML, MULTI_FILE -> new SimpleTextStreamHandler().handle(...);
};
```

**① SimpleTextStreamHandler**（[#L27-L47](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/handler/SimpleTextStreamHandler.java#L27-L47)）：`StringBuilder` 攒全文，`doOnComplete` 落库；`doOnError` 落错误文案。

**② JsonMessageStreamHandler**（[JsonMessageStreamHandler.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/handler/JsonMessageStreamHandler.java)）：它要**把 JSON 事件"重组"成人类可读的文本**再落库。核心是 `handleJsonMessageChunk` 的三分支（[#L73-L114](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/handler/JsonMessageStreamHandler.java#L73-L114)）：
- `AI_RESPONSE` → 取 `data`，**拼接**（这就是 AI 说的话）；
- `TOOL_REQUEST` → 用 `seenToolIds` 去重，**只在同一个工具 ID 第一次出现时**输出 `[选择工具] xxx`；
- `TOOL_EXECUTED` → 渲染成 `[工具调用] writeFile src/App.vue` + 代码块，**追加**到历史。

**为什么要去重工具请求？** 因为流式下同一工具的请求事件会**重复推送**（分片），如果不去重，历史里会出现几十行"选择工具 写入文件"，把真正的对话淹没。**用 `Set<String> seenToolIds` 记录"见过的 ID"**——简单有效。

**这里有一个设计亮点**：它把 VUE 的"工具调用信息"也**持久化进历史文本**了。为什么？因为下次 AI 要接着改代码时，**它需要知道"上次是用工具写了哪些文件"**，否则会重复创建文件、或不知道文件已存在。**历史 = 模型的短期记忆来源**，所以历史里必须有"我干过什么"。

### 5.5 读取链路 1：AI 记忆重建 loadChatHistoryToMemory

[ChatHistoryServiceImpl.java#L93-L125](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ChatHistoryServiceImpl.java#L93-L125)：

```java
QueryWrapper queryWrapper = QueryWrapper.create()
        .eq(ChatHistory::getAppId, appId)
        .orderBy(ChatHistory::getCreateTime, false)   // ① 先按时间"倒序"取
        .limit(1, maxCount);
List<ChatHistory> historyList = this.list(queryWrapper);
historyList = historyList.reversed();               // ② 再反转成正序
chatMemory.clear();                                  // ③ 清空，防重复加载
for (ChatHistory history : historyList) {
    if (USER == type) chatMemory.add(UserMessage.from(history.getMessage()));
    else if (AI == type) chatMemory.add(AiMessage.from(history.getMessage()));
}
```

**三个细节都要讲透：**

**① 为什么先倒序再 limit，而不是直接正序 limit？**
因为要取**最近的** N 条。正序 `limit 20` 拿的是**最早的** 20 条——对于长对话，AI 记住的是"一开始聊了啥"，而**最近的上下文才是最重要的**（用户说"再改一下刚才那个按钮"）。所以：`orderBy desc + limit N` 拿最近 N 条 → `reversed()` 还原成"老→新"的时间正序喂给模型。

**② 为什么喂模型的顺序必须是正序？**
对话记忆是**链式**的：`user: 做一个登录页` → `ai: 好的，这是代码` → `user: 加个验证码`。顺序错了，模型理解就错。**取的时候倒序（为了"最近"），喂的时候正序（为了"因果"）**——这两步顺序相反，是最容易被写错的地方。

**③ 为什么先 `chatMemory.clear()`？**
因为这个 `chatMemory` 实例可能被复用（同一 appId 的 AI 服务实例**只创建一次**，见 5.7），而 `createAiCodeGeneratorService` 里每次创建都会调用本方法。如果不清空，**每次回填都会把历史再追加一遍**，20 条变 40 条、60 条……

**异常处理**（[#L120-L124](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ChatHistoryServiceImpl.java#L120-L124)）：整个方法 `try/catch` 包住，失败只 log 并 `return 0`。
**这是刻意的"降级不阻断"**：历史加载失败不该让整个 AI 对话失败——大不了这次"没有记忆"，但功能还能用。**这是正确取舍**，值得学。

### 5.6 记忆窗口 20 vs 回填 20 —— 必须对齐

[AiCodeGeneratorServiceFactory.java#L93-L100](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/AiCodeGeneratorServiceFactory.java#L93-L100)：

```java
MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
        .id(appId)
        .chatMemoryStore(redisChatMemoryStore)
        .maxMessages(20)                       // ← 窗口大小 20
        .build();
chatHistoryService.loadChatHistoryToMemory(appId, chatMemory, 20);   // ← 回填 20
```

**为什么两个 20 必须一致？** 这对应工程约定"对话记忆窗口大小与回填条数保持一致，避免无效数据加载"。
- 若**回填 30、窗口 20**：多查出来的 10 条立刻被窗口挤掉，**白查数据库**；
- 若**回填 10、窗口 20**：窗口里有 10 个空槽永远填不满，AI 上下文**凭空变短**。

**复现建议**：把 20 提成一个常量（如 `AppConstant.CHAT_MEMORY_MAX_MESSAGES`），两处引用。**同一个语义的值出现在两个地方，就是未来的 bug**。

### 5.7 Caffeine 实例缓存：为什么"一个 appId 一个 AI 服务实例"

[AiCodeGeneratorServiceFactory.java#L52-L81](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/AiCodeGeneratorServiceFactory.java#L52-L81)：

```java
private final Cache<String, AiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofMinutes(30))
        .expireAfterAccess(Duration.ofMinutes(10))
        .build();

public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
    String cacheKey = buildCacheKey(appId, codeGenType);        // "appId_codeGenType"
    return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType));
}
```

**缓存键是 `appId + "_" + codeGenType`**（[#L155-L157](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/AiCodeGeneratorServiceFactory.java#L155-L157)）。对应工程约定"按 appId + 代码生成类型维度缓存 AI 服务实例"。

**`Caffeine.get(key, loader)` 到底干了啥？**（这个概念之前用户专门问过，这里讲清）
它是一个**二选一的懒加载分支**，不是"并行执行"：
- **命中** → 直接返回已有实例，**loader 完全不执行**；
- **未命中** → 调用 loader 创建实例，然后**自动把返回值放进缓存**。

所以**代码里不需要写 `cache.put(...)`**——`get` 帮你存了。

**为什么要按 appId 缓存实例？** 因为**记忆是绑在实例上的**。`AiCodeGeneratorService` 是接口，实际是 `AiServices` 生成的**动态代理**，代理内部持有这个 appId 专属的 `chatMemory`。**同一个 appId 的多次对话必须复用同一个实例**，否则每轮都新建一个空记忆，AI 永远"失忆"。

**一个细节**：并非所有模型 bean 都是多例。`streamingChatModelPrototype` 和 `reasoningStreamingChatModelPrototype` 是 `@Scope("prototype")`（见 [StreamingChatModelConfig.java#L43-L56](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/config/StreamingChatModelConfig.java#L43-L56)），每次取都**新建**一个模型对象——这是**为了并发安全**（流式模型对象有内部状态，共享会串数据）。而 `openAiChatModel`（同步 ChatModel）是单例注入的。

**这是两层不同的"缓存/多例"逻辑，别混**：
- **AI 服务实例**：按 appId 缓存，**要复用**（为了记忆）；
- **底层模型对象**：prototype，**不复用**（为了并发）。

### 5.8 读取链路 2：游标分页（不要用 OFFSET）

[ChatHistoryServiceImpl.java#L72-L91](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ChatHistoryServiceImpl.java#L72-L91) + [ChatHistoryController.java#L46-L54](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/ChatHistoryController.java#L46-L54)：

```java
@GetMapping("/app/{appId}")
public BaseResponse<Page<ChatHistory>> listAppChatHistory(@PathVariable Long appId,
        @RequestParam(defaultValue = "10") int pageSize,
        @RequestParam(required = false) LocalDateTime lastCreateTime, ...)
```

查询条件构造（[#L154-L163](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ChatHistoryServiceImpl.java#L154-L163)）：

```java
if (lastCreateTime != null) {
    queryWrapper.lt("createTime", lastCreateTime);   // ← 游标：只取"比它更早"的
}
```

**为什么是游标分页，不是 `pageNum`？** 对应工程约定"持续增长的流水数据使用游标分页，禁止使用 OFFSET 分页"：
- 对话是**持续插入**的；
- OFFSET 分页在"边翻页边新增"时会**漏记录/重复记录**（想象每翻一页前面又插进来两条，你第二页看到的其实是第 3、4 条的内容）；
- 深分页时 `LIMIT 100000, 10` 还要**扫描前 10 万行**，性能崩。

**游标分页的语义**：`createTime` 是"上一页最后一条的时间"，下一页取 `createTime < 该值` 的记录 → **和插入速度无关，永远不漏不重**。

**但这里有个真实的坑（项目记忆里也记了）**：用**秒级 `createTime` 当游标**，同一秒内的多条记录会被**跳过**。因为条件是**严格小于** `<`——同一秒的记录全部被排除。
**正确做法**：用 `(createTime < X) OR (createTime = X AND id < lastId)`，或者直接用唯一有序的 `id` 做游标。

**权限校验**（[#L78-L83](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ChatHistoryServiceImpl.java#L78-L83)）：非管理员**必须**是应用创建者才能看历史。`pageSize` 限制 1~50。

### 5.9 验收标准

| # | 场景 | 期望 |
|---|---|---|
| 1 | 首次对话（Redis 无记忆） | 正常生成，`chat_history` 新增 user + ai 两条 |
| 2 | 第二次对话提"再加个按钮" | AI 能理解上下文（说明记忆生效） |
| 3 | 重启应用后再对话 | 仍能记得（记忆从 MySQL 回填） |
| 4 | 翻历史 `/chatHistory/app/1` | 返回最近 10 条，按时间**倒序** |
| 5 | 传 `lastCreateTime` | 返回更早的记录，不漏不重 |
| 6 | 非属主访问历史 | `NO_AUTH_ERROR` |
| 7 | AI 调用失败 | 历史里多一条 `AI回复失败: ...` |

### 5.10 阶段 5 坑清单

| # | 坑 | 表现 | 处理 |
|---|---|---|---|
| 1 | 秒级 `createTime` 游标 | 同秒记录被跳过 | 改为 `id` 游标或时间+id 双游标 |
| 2 | 记忆窗口与回填条数不一致 | 白查库 / 上下文变短 | 抽公共常量 |
| 3 | 错误消息落库污染上下文 | AI 回填时读到"AI回复失败" | 新增消息类型，回填时过滤 |
| 4 | 回填未 `clear()` | 记忆条目翻倍 | 每次回填前 clear |
| 5 | 用户消息在 AI 之后落库 | 时序错乱、记忆少一条 | 先落库再调 AI |
| 6 | `messageType` 传字符串无校验 | 脏类型进库 | 枚举白名单校验 |
| 7 | Redis 与 MySQL 过期策略不对齐 | 静默失忆且无法恢复 | 明确各自职责：Redis 有意过期 |

### 5.11 心法 + Checklist

**心法**：阶段 5 的全部门道可以压缩成一句——**"AI 的记忆是"私有的、有限的、会过期的"，用户的账本必须是"共享的、完整的、永久的"**。搞混这两者，就会设计出"用户翻到的历史"和"AI 记得的历史"不一致的系统。

| # | 自问 | 答案 |
|---|---|---|
| 1 | 为什么三层存储不是冗余？ | 账本/短期记忆/渲染态，用途不同 |
| 2 | 用户消息为什么先落库？ | 时序、可追溯、记忆一致性 |
| 3 | AI 消息在哪写的？ | handler 的 `doOnComplete` / `doOnError` |
| 4 | 为什么倒序取、正序喂？ | 取最近的，喂有因果的 |
| 5 | AI 服务实例为什么要缓存？ | 记忆绑在实例上，不复用就失忆 |
| 6 | 两层缓存分别是什么？ | Caffeine 实例缓存（要复用） vs Redis 对话数据 |
| 7 | 游标分页为什么不用 OFFSET？ | 持续插入场景 OFFSET 会漏/重 + 深分页慢 |
| 8 | 历史加载失败为什么吞掉？ | 降级不阻断，没记忆也能用 |

---

## 阶段 6 讲透：功能扩展（一片一片拼上去）

> 目标：核心闭环跑通后，把六个扩展能力逐个挂上去。每个扩展都遵循同一个套路——**新增一个"可变点"，用已有的分派结构接进去**。

### 6.1 扩展一：AI 智能选择生成类型（路由）

**它解决什么？** 用户不该选"HTML / 多文件 / Vue"，那是技术细节。用户只说需求，系统自己判断。

**两个文件**：

| 文件 | 职责 |
|---|---|
| [AiCodeGenTypeRoutingService.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/AiCodeGenTypeRoutingService.java) | 接口：`CodeGenTypeEnum routeCodeGenType(String userPrompt)` |
| [AiCodeGenTypeRoutingServiceFactory.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/AiCodeGenTypeRoutingServiceFactory.java) | 用 `routingChatModelPrototype` 造实例 |

**最妙的一行**——返回值直接是**枚举**：

```java
@SystemMessage(fromResource = "prompt/codegen-routing-system-prompt.txt")
CodeGenTypeEnum routeCodeGenType(String userPrompt);
```

**LangChain4j 对枚举做了特殊结构化输出处理**：它会把枚举的常量列表写进提示词，让模型只在合法值里选。所以**不需要你写"如果模型返回了 'vue' 就手工映射"**。

提示词也很克制（[codegen-routing-system-prompt.txt](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/resources/prompt/codegen-routing-system-prompt.txt)）：三条判断规则 → 三选一。

**接线点在 [AppServiceImpl#createApp](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L130-L133)**：

```java
AiCodeGenTypeRoutingService routingService = aiCodeGenTypeRoutingServiceFactory.createAiCodeGenTypeRoutingService();
CodeGenTypeEnum selectedCodeGenType = routingService.routeCodeGenType(initPrompt);
app.setCodeGenType(selectedCodeGenType.getValue());
```

**为什么用 Factory 而不是 `@Resource` 直接注入？** 看 [RoutingAiModelConfig.java#L36-L38](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/config/RoutingAiModelConfig.java#L36-L38)：`routingChatModelPrototype` 是 `@Scope("prototype")`。要拿到**新实例**，**必须每次通过容器取**（`SpringContextUtil.getBean(...)`），单例注入只能拿到同一个。**工厂存在的唯一理由，就是"每次都要新的"**。

**已知坑**：`createApp` 里**路由失败 = 创建失败**（没有 try/catch，没有兜底类型）。模型抖动一下，用户连应用都建不出来。**合理做法**：`try { route } catch { 默认 HTML }`。

### 6.2 扩展二：多文件生成（MULTI_FILE）

**新增了什么？** 三样东西，都是"顺着已有结构加"：

| 层 | 新增物 | 复用点 |
|---|---|---|
| 模型 | [MultiFileCodeResult.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/model/MultiFileCodeResult.java)（htmlCode/cssCode/jsCode） | 同 `@Description` 套路 |
| 解析 | [MultiFileCodeParser.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/MultiFileCodeParser.java) | 实现同一个 `CodeParser` 接口 |
| 落盘 | [MultiFileCodeFileSaverTemplate.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/MultiFileCodeFileSaverTemplate.java) | 继承同一个模板父类 |

**解析器**用三条独立正则（[#L15-L17](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/MultiFileCodeParser.java#L15-L17)）：

```java
Pattern.compile("```html\\s*\\n([\\s\\S]*?)```", CASE_INSENSITIVE)
Pattern.compile("```css\\s*\\n([\\s\\S]*?)```", CASE_INSENSITIVE)
Pattern.compile("```(?:js|javascript)\\s*\\n([\\s\\S]*?)```", CASE_INSENSITIVE)
```

**注意 JS 那条用了 `(?:js|javascript)`**——**非捕获组**，因为模型可能写 ```` ```js ```` 也可能写 ```` ```javascript ````。这是个很实用的细节。

**落盘**（[MultiFileCodeFileSaverTemplate.java#L21-L29](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/MultiFileCodeFileSaverTemplate.java#L21-L29)）：

```java
writeToFile(baseDirPath, "index.html", result.getHtmlCode());
writeToFile(baseDirPath, "style.css", result.getCssCode());
writeToFile(baseDirPath, "script.js", result.getJsCode());
```

**重要**：文件名 `index.html` / `style.css` / `script.js` 是**硬编码契约**——因为**提示词和预览都不认路径**，只认这三个名字。所以 HTML 里引用必须是 `style.css`（不是 `./css/style.css`）。

**校验的宽松度**（[#L31-L38](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/MultiFileCodeFileSaverTemplate.java#L31-L38)）：只要求 `htmlCode` 非空，CSS/JS 允许为空。这就是阶段 3 讲的"静默跳过"的具体落点。

**验证方式**：`htmlCode` 非空 → 生成；`cssCode` 空 → `writeToFile` 里 `isNotBlank` 为 false → 不写文件、不报错。

### 6.3 扩展三：Vue 工程（工具调用 + 构建）

这是**最复杂的一条分支**，因为它换了三件事：**输出形态、模型类型、落盘方式**。

**① 输出形态换成 `TokenStream`**，因为要携带工具调用事件（[AiCodeGeneratorFacade#L112-L138](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L112-L138)）：

```java
tokenStream
    .onPartialResponse(p -> sink.next(json(new AiResponseMessage(p))))              // 文字
    .onPartialToolExecutionRequest((i, r) -> sink.next(json(new ToolRequestMessage(r))))  // 要调工具了
    .onToolExecuted(t -> sink.next(json(new ToolExecutedMessage(t))))               // 工具调完了
    .onCompleteResponse(r -> {
        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId;
        vueProjectBuilder.buildProject(projectPath);   // 流结束后同步构建
        sink.complete();
    })
    .onError(e -> sink.error(e))
    .start();
```

**四种消息被统一成 JSON**（类型见 [StreamMessageTypeEnum.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/model/message/StreamMessageTypeEnum.java)）：`ai_response` / `tool_request` / `tool_executed`。

**② 工具调用：AI 自己"写文件"**，而不是把代码回给你再解析。

工具集在 [ai/tools](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools)：

| 工具 | 作用 |
|---|---|
| [FileWriteTool.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools/FileWriteTool.java) | writeFile（写文件） |
| FileReadTool / FileModifyTool / FileDeleteTool | 读 / 改 / 删 |
| FileDirReadTool | 列目录 |
| [ExitTool.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools/ExitTool.java) | 主动结束（让 AI 有"我干完了"这个动作） |

`FileWriteTool` 的核心（[#L27-L53](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools/FileWriteTool.java#L27-L53)）：

```java
@Tool("写入文件到指定路径")
public String writeFile(@P("文件的相对路径") String relativeFilePath,
                        @P("要写入文件的内容") String content,
                        @ToolMemoryId Long appId) {
    Path path = Paths.get(relativeFilePath);
    if (!path.isAbsolute()) {
        String projectDirName = "vue_project_" + appId;       // ← 目录契约第 4 处
        path = Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName).resolve(relativeFilePath);
    }
    ...
    return "文件写入成功: " + relativeFilePath;   // ← 故意只回相对路径
}
```

**四个必须讲到的点：**
- **`@Tool` / `@P` / `@ToolMemoryId`**：`@P` 是参数说明（写给模型看）；**`@ToolMemoryId` 是关键**——它让工具方法能自动拿到当前对话的 `appId`（也就是 `generateVueProjectCodeStream(@MemoryId long appId, ...)` 传进来的那个）。**没有它，工具不知道往哪个目录写**。
- **绝对路径 vs 相对路径**：AI 给**相对路径**（`src/App.vue`），工具负责补上 `vue_project_{appId}` 前缀 → **这是"防 AI 乱写系统文件"的第一道护栏**。
- **返回值故意只说相对路径**（[#L52-L53](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools/FileWriteTool.java#L52-L53)）：注释写得很明白——"不能让 AI 把文件绝对路径返回给用户"。**因为返回值会进对话历史、会被模型复述给用户**，绝对路径会**泄露服务器目录结构**。
- **`ToolManager` 的"自动注册"**（[ToolManager.java#L26-L40](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools/ToolManager.java#L26-L40)）：`@Resource private BaseTool[] tools;`——Spring 会把**所有 BaseTool 子类**注入成数组，`@PostConstruct` 时建 `name → 实例` 的 Map。**新增工具只需加一个 `@Component`**，不用改任何注册代码。这是非常优雅的"开闭原则"落地。

**③ 渲染成"看得懂的过程"**：工具调用信息不能原样丢给前端，要靠 `JsonMessageStreamHandler` 转成人类语言（阶段 5 已讲，[#L81-L109](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/handler/JsonMessageStreamHandler.java#L81-L109)）。

**④ 构建：`VueProjectBuilder`**（[VueProjectBuilder.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/builder/VueProjectBuilder.java)）：

```java
public boolean buildProject(String projectPath) {
    // 1. 目录存在？ 2. package.json 存在？
    executeNpmInstall(projectDir);   // npm install，300s 超时
    executeNpmBuild(projectDir);     // npm run build，180s 超时
    // 3. 校验 dist 目录生成
}
```

**四个工程细节：**
- **跨平台命令**（[#L96-L110](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/builder/VueProjectBuilder.java#L96-L110)）：Windows 用 `npm.cmd`，类 Unix 用 `npm`——**否则 Windows 上 exec 会失败**；
- **超时强制终止**（[#L129-L134](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/builder/VueProjectBuilder.java#L129-L134)）：`waitFor(300s)` 超时就 `destroyForcibly()`——防止 npm 挂死把线程占满；
- **构建时机：流结束后同步执行**（[AiCodeGeneratorFacade#L126-L130](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java#L126-L130)），注释写着"同步执行，确保预览时项目已就绪"。**这是个取舍**：用户要多等几分钟，但避免了"预览时 dist 还没好"的竞态；
- **`buildProjectAsync` 是死代码**（[#L22-L31](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/builder/VueProjectBuilder.java#L22-L31)）：全项目无业务调用，只有定义。**这是能主动指出来、面试加分的点**——说明你真的读过全量代码。

**追问：为什么 VUE 用"工具调用"，而 HTML 用"解析代码块"？**
- HTML 是**单文件、纯文本**，模型一次吐完，正则切一刀就行；
- Vue 是**多文件工程 + 需要"改已有文件"能力**（第二轮对话要改 `src/App.vue`），必须让模型能**读、写、改文件**——这不是"解析输出"能解决的，**只能给工具**。

### 6.4 扩展四：一键部署 + 封面截图

**部署 `deployApp`**（[AppServiceImpl#L142-L198](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L142-L198)）十步：

| 步 | 动作 | 要点 |
|---|---|---|
| 1-3 | 校验 → 查应用 → 属主校验 | 沿用阶段 2 的权限套路 |
| 4 | 生成/复用 `deployKey` | 6 位随机串；**没有就生成，有了就复用** |
| 5-6 | 拼源目录 `{codeGenType}_{appId}` 并校验存在 | **目录契约第 2 处** |
| 7 | VUE 特殊处理：先 `buildProject`，把 sourceDir 换成 `dist` | |
| 8 | `FileUtil.copyContent(sourceDir, deployDir, true)` | 复制到 `CODE_DEPLOY_ROOT_DIR/{deployKey}` |
| 9 | 更新 `deployKey` + `deployedTime` | |
| 10 | 拼 URL `deployHost + "/" + deployKey + "/"` | |
| 11 | `generateAppScreenshotAsync(appId, url)` | 异步生成封面 |

**为什么"部署"只是"复制文件"？**
因为本项目把**部署目录当作"可被静态服务托管的根目录"**——`CODE_DEPLOY_ROOT_DIR/{deployKey}` 就是一份可访问的静态站点。**deployKey 存在的意义 = 让 URL 不含 appId，且可随机化/可更换**（避免用户猜别人应用的地址）。
**代价**：改绑 deployKey 后旧地址立刻失效，且**部署目录永久堆积、无清理机制**（已知坑）。

**为什么 VUE 要换成 `dist`？** 因为 `vue_project_{appId}` 是**源码目录**（有 `node_modules`、`src`），不能直接对外；`dist` 才是构建产物。**这也是"部署前必须先构建"的原因**。

**截图 `generateAppScreenshotAsync`**（[#L206-L221](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L206-L221)）：

```java
Thread.startVirtualThread(() -> {
    String screenshotUrl = screenshotService.generateAndUploadScreenshot(appUrl);
    App updateApp = new App();
    updateApp.setId(appId);
    updateApp.setCover(screenshotUrl);
    this.updateById(updateApp);
});
```

**为什么用虚拟线程（JDK 21）？** 截图是**长时间阻塞的 IO + 渲染**任务，虚拟线程正是为它设计的——**不占平台线程池**。对比 `@Async`（要配线程池、要处理拒绝策略）和手工 `new Thread`（贵且不受管），`Thread.startVirtualThread` 最简洁。
**注意这一行本身就在一个 async 方法里**——`deployApp` 调 `generateAppScreenshotAsync` 时，**部署响应会立刻返回**，截图在后台跑。

**为什么"写库"不放在 screenshot 服务里？** 因为 `screenshotService` 只该负责"截图并返回 URL"（纯能力）；**"更新哪个 app 的封面对应哪个字段"是 AppService 的业务职责**。**职责边界清晰**，也让 screenshot 服务可以独立复用（比如给别的业务截图）。

`ScreenshotServiceImpl`（[ScreenshotServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ScreenshotServiceImpl.java)）用 **try/finally 清理本地文件**（[#L32-L43](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ScreenshotServiceImpl.java#L32-L43)）：

```java
try {
    String cosUrl = uploadScreenshotToCos(localScreenshotPath);
    ...
    return cosUrl;
} finally {
    cleanupLocalFile(localScreenshotPath);   // 无论如何都删本地
}
```

**为什么必须 finally？** 否则每次部署都在服务器上留一个 jpg，**长期跑必然把磁盘写满**。这是"临时文件必须 finally 清理"的标准写法。

**注意一个真实的瑕疵**：`generateAppScreenshotAsync` 里**没有 try/catch**。截图失败/上传失败抛出的异常会**吞在虚拟线程里**（虚拟线程的未捕获异常只会打到默认 handler，不会影响主流程，但**你也不知道出了什么事**），应用封面就永远为空。**该记 error 日志 + 标记重试**。

### 6.5 扩展五：下载代码包（ZIP）

入口 [AppController#downloadAppCode](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/AppController.java#L118-L144)：

```java
// 权限校验：只有应用创建者可以下载代码
if (!app.getUserId().equals(loginUser.getId())) { throw ... "无权限下载该应用代码"; }
String sourceDirName = codeGenType + "_" + appId;   // ← 目录契约第 3 处
...
projectDownloadService.downloadProjectAsZip(sourceDirPath, downloadFileName, response);
```

**为什么"管理员也不行"？** 这是**有意的隐私边界**——用户生成的代码视为**私有产物**（可能含敏感业务逻辑），管理员有权**管理应用**（改精选、改名称），但**无权拿走用户的代码**。这条与前几处"管理员可删"的差异，**是设计而非疏漏**，面试里能说清很加分。

**打包过滤**（[ProjectDownloadServiceImpl.java#L26-L46](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/ProjectDownloadServiceImpl.java#L26-L46)）：

```java
IGNORED_NAMES = { node_modules, .git, dist, build, .DS_Store, .env, target, .mvn, .idea, .vscode }
IGNORED_EXTENSIONS = { .log, .tmp, .cache }
```

**为什么要过滤？** `node_modules` 动辄 10 万+ 文件、几百 MB——**打包要几分钟、下载要几个 G**，用户根本不想要。`.env` 必须排除（**可能含密钥**）。
**过滤逻辑**（[#L83-L99](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/ProjectDownloadServiceImpl.java#L83-L99)）：把路径相对化，**逐段**检查是否命中忽略名单——这样**任意层级**的 `node_modules` 都会被排除（不只是根目录）。

**流式打包**（[#L66-L69](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/ProjectDownloadServiceImpl.java#L66-L69)）：

```java
ZipUtil.zip(response.getOutputStream(), StandardCharsets.UTF_8, false, filter, projectDir);
```

**直接写 `response.getOutputStream()`**——**不落中间 zip 文件**，边压边传。避免"先生成一个几百 M 的临时 zip 再删"的磁盘压力。

### 6.6 扩展六：缓存 / 限流 / 监控（三个"横切"能力）

这三个的共同点：**它们不属于任何单个业务，是"切面"**。

**① 缓存**：`/good/list/page/vo` 上的声明式缓存（阶段 2 已讲）。补充本阶段的点是**缓存 key 怎么生成**——[CacheKeyUtils.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/utils/CacheKeyUtils.java)：

```java
public static String generateKey(Object obj) {
    if (obj == null) return DigestUtil.md5Hex("null");
    return DigestUtil.md5Hex(JSONUtil.toJsonStr(obj));    // 对象 → JSON → MD5
}
```

**为什么"JSON 再 MD5"两步？** 查询条件是个对象，Spring 的默认 key 生成对复杂对象**不可控**（可能依赖 `hashCode`/`toString`，也可能超长）。**自己序列化成确定性字符串再哈希** = **同一个查询条件永远得到同一个 key**。
**注意**：`JSONUtil.toJsonStr` 的字段顺序是**稳定的**（按字段定义顺序），所以确定性有保障。但如果 DTO 加了**无顺序保证的集合字段**（如 `Set`），哈希就会不稳定 → 缓存永远不命中。**这是潜在的坑**。

**② 限流**：注解 + 切面（Redisson `RRateLimiter`）。

[RateLimit.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ratelimter/annotation/RateLimit.java) 定义了 `key/rate/rateInterval/limitType/message`；[RateLimitAspect.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ratelimter/aspect/RateLimitAspect.java) 用 `@Before("@annotation(rateLimit)")` 拦截：

```java
String key = generateRateLimitKey(point, rateLimit);
RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
rateLimiter.expire(Duration.ofHours(1));
rateLimiter.trySetRate(RateType.OVERALL, rateLimit.rate(), rateLimit.rateInterval(), RateIntervalUnit.SECONDS);
if (!rateLimiter.tryAcquire(1)) {
    throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, rateLimit.message());
}
```

**用 Redis 做限流而不是本地 Guava RateLimiter 的原因**：本地限流在**多实例部署**时失效（每个实例各限各的，5 QPS × N 实例）。**Redis 集中计数才能全局生效**。这是项目记忆里的硬约束"代码生成接口必须配置用户级限流，防止 LLM 调用额度被刷爆"。

`generateRateLimitKey`（[#L61-L102](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ratelimter/aspect/RateLimitAspect.java#L61-L102)）按 `limiterType` 三选一：API（方法名）/ USER（用户 ID）/ IP。
**USER 类型的降级**：取不到登录用户时**自动降级为 IP 限流**（[#L85-L91](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ratelimter/aspect/RateLimitAspect.java#L85-L91)）——**否则未登录用户就成了无限流通道**。这个降级是**必要的安全兜底**。

`getClientIP`（[#L109-L127](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ratelimter/aspect/RateLimitAspect.java#L109-L127)）按 `X-Forwarded-For → X-Real-IP → remoteAddr` 依次取值，并对多级代理 `split(",")[0]` 取第一个。
**注意其风险**：`X-Forwarded-For` 是**客户端可伪造**的头。**如果服务直接暴露公网、没有可信代理在前面**，攻击者可以伪造成任意 IP → **绕过 IP 限流**。工程上应只在"有可信网关"时才信任这些头。

**这里有一个真实的实现瑕疵（值得写进笔记）**：`trySetRate` 只在 key 不存在时生效。但 key 带了 `expire(1h)`。1 小时后 key 过期 → 重建限流器 → 计数器清零。
**所以"1 分钟 5 次"并不是严格的"1 分钟窗口"**，而是"最多持续 1 小时、1 小时到期后清零"。用户被限流后**如果一直重试，只要不重试超过 1 小时，就永远处于被限流状态**（计数器不会随时间滑出）。这是**固定窗口 + 小时级重建**的语义，与直觉不同，面试可能被追问。

**③ 监控**：LangChain4j 的 `ChatModelListener` 扩展点。

| 文件 | 职责 |
|---|---|
| [AiModelMonitorListener.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/monitor/AiModelMonitorListener.java) | 监听 `onRequest`/`onResponse`/`onError` |
| [AiModelMetricsCollector.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/monitor/AiModelMetricsCollector.java) | 记录指标 |
| [MonitorContext.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/monitor/MonitorContext.java) / [MonitorContextHolder.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/monitor/MonitorContextHolder.java) | `ThreadLocal` 传递 userId/appId |
| [StreamingChatModelConfig.java#L54](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/config/StreamingChatModelConfig.java#L54) | `.listeners(List.of(aiModelMonitorListener))` 挂载 |

**为什么需要 `MonitorContextHolder`（ThreadLocal）？**
因为**模型监听器和业务代码不在同一个调用栈里**——监听器是 LangChain4j 内部回调的，它**无从知道这次调用是哪个用户在给哪个应用生成代码**。所以在业务侧（[AppServiceImpl#L103-L108](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L103-L108)）**先把上下文塞进 ThreadLocal**，监听器回调时再取出来。

```java
MonitorContextHolder.setContext(MonitorContext.builder()
        .userId(loginUser.getId().toString())
        .appId(appId.toString())
        .build());
```

**关键细节：为什么要在 `.doFinally` 里 `clearContext()`？**（[#L113-L116](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L113-L116)）

```java
.doFinally(signalType -> MonitorContextHolder.clearContext());   // 成功/失败/取消都清
```

因为 **Tomcat/WebFlux 的线程是复用的**。不清 → 下一个复用该线程的请求，会读到**上一个人的 userId/appId** → **指标记错人、上下文串数据**（这也是"ThreadLocal 用完必须清理"的经典教训）。
**再说一层**：`onRequest` 里还把 `monitorContext` **存进了 `requestContext.attributes()`**（[AiModelMonitorListener#L34-L37](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/monitor/AiModelMonitorListener.java#L34-L37)），因为**响应回调可能不在同一线程**，ThreadLocal 取不到，必须靠属性传递。**这是"跨线程传递上下文"的正确姿势**。

**指标内容**（[AiModelMonitorListener#L44-L98](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/monitor/AiModelMonitorListener.java#L44-L98)）：请求数（started/success/error）、响应耗时、**token 用量（input/output/total）**。
最后落进 `management`（[application.yml](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/resources/application.yml) 的 actuator 配置）暴露给 Prometheus。

### 6.7 扩展七（可选）：LangGraph4j 工作流

这一块是**独立的、实验性的**，不影响主链路。看懂它在干什么就行。

[CodeGenWorkflow.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/CodeGenWorkflow.java) 用**图**编排代码生成：

```text
START → image_collector → prompt_enhancer → router → code_generator → code_quality_check
                                                                          │
                              ┌───────────────────────────────────────────┤
                              │ 条件边 routeAfterQualityCheck             │
                              ├─ "fail"       → code_generator   （质检不过，重生成）
                              ├─ "build"      → project_builder  （Vue 要构建）
                              └─ "skip_build" → END              （HTML/多文件，结束）
```

**四个节点 / 三个要点：**
- **条件边**（[#L54-L60](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/CodeGenWorkflow.java#L54-L60)）：`addConditionalEdges`，用 `edge_async(this::routeAfterQualityCheck)` 决定下一步——**这就是 LangGraph 的核心能力："让流程自己决定走向"**（普通代码里你得写一堆 if-else）；
- **自环重试**：质检失败回到 `code_generator`——**用状态机表达"重试"**，比循环 + 计数器清晰；
- **状态传递**：`WorkflowContext`（[WorkflowContext.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/state/WorkflowContext.java)）在节点间流转，放在 `MessagesState` 的 `WORKFLOW_CONTEXT_KEY` 里。
- **三种执行方式**（[WorkflowSseController.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/WorkflowSseController.java)）：同步 `executeWorkflow` / `Flux executeWorkflowWithFlux` / `SseEmitter executeWorkflowWithSse`——**同一个图，三种"怎么把过程播出去"的方式**。流式版本用 `Thread.startVirtualThread` 跑图，边跑边 `sink.next(formatSseEvent(...))`。

**要不要复现这块？** 建议**先跳过**。它是"锦上添花"（且 `docs` 里已有分析指出 `CodeGeneratorNode` 里 appId 传参等整合问题）。**先把阶段 0~5 吃透，再回来看图编排**。

### 6.8 阶段 6 坑清单

| # | 坑 | 表现 | 处理 |
|---|---|---|---|
| 1 | `createApp` 路由失败无兜底 | 模型抖动导致建应用失败 | try/catch + 默认 HTML |
| 2 | `buildProjectAsync` 死代码 | 误以为构建是异步的 | 用 `@Async` 或删掉 |
| 3 | 同步构建阻塞流结束 | 用户多等几分钟 | 可改异步 + 构建完成事件 |
| 4 | 部署目录无清理 | 磁盘持续增长 | 加清理策略/定时任务 |
| 5 | 改绑 deployKey 旧址失效 | 用户原有链接打不开 | 保留历史 key |
| 6 | 截图异步无 try/catch | 封面长期为空且无感知 | 记日志 + 落库失败状态 |
| 7 | `X-Forwarded-For` 可伪造 | 绕过 IP 限流 | 仅信任可信代理链 |
| 8 | `trySetRate` + 1h expire | 限流语义非滑动窗口 | 明确文档化 / 改滑动窗口 |
| 9 | `CacheKeyUtils` 遇非确定性集合 | 缓存永不命中 | key 字段需有序/规范化 |
| 10 | 工具返回绝对路径会泄露目录 | 信息泄露 | 只回相对路径（已做） |

### 6.9 心法 + Checklist

**心法**：阶段 6 的每一个扩展，都是**在阶段 1~5 的结构上"挂一个可变点"**——
- 新的生成类型 → 加一个 `CodeGenTypeEnum` 值 + 一个 parser + 一个 saver（**不用改流程**）；
- 新的工具 → 加一个 `@Component extends BaseTool`（**不用改注册**）；
- 新的横切能力 → 加一个**注解 + 切面**（**不用改业务**）。

**判断你的复现是否成功**：**加新功能时，你需不需要改动核心流程代码？** 如果不需要，说明你的分层和分派结构是对的。

| # | 自问 | 答案 |
|---|---|---|
| 1 | 用户为什么不用选生成类型？ | AI 路由用结构化输出直接返回枚举 |
| 2 | 路由为什么用 Factory？ | 模型 bean 是 prototype，必须每次取新实例 |
| 3 | Vue 为什么用工具调用而不是解析？ | 多文件 + 需要改已有文件，必须给读写能力 |
| 4 | `@ToolMemoryId` 干嘛的？ | 把 appId 传进工具，定位写入目录 |
| 5 | 新增工具要改注册代码吗？ | 不用，`BaseTool[]` 自动注入 + `@PostConstruct` |
| 6 | 部署本质是什么？ | 复制目录到静态托管根目录 + 生成随机 deployKey |
| 7 | 截图为什么要 finally 清理？ | 否则临时图片撑爆磁盘 |
| 8 | 限流为什么用 Redis？ | 多实例下本地限流失效 |
| 9 | 监控上下文为什么要 ThreadLocal + attributes？ | 监听器回调跨线程，取不到业务上下文 |
| 10 | `.doFinally` 里 clear 是防什么？ | 线程复用导致上下文串数据 |
| 11 | LangGraph 相对 if-else 的优势？ | 条件边 + 自环重试，流程自决、状态清晰 |

---

## 附录 A：复现顺序总表（照着做就行）

| 阶段 | 你要写的类 | 验收信号 | 可跳过？ |
|---|---|---|---|
| 0 | 启动类、4 套模型 Config、Redis/MyBatis-Flex/SSE 基础 | 应用能起、8123/api 能通 | 否 |
| 1 | User 全链路（Entity/Mapper/Service/Controller/VO） | 能注册、登录、`getLoginUser` 生效 | 否 |
| 2 | App 全链路 + 属主校验 | CRUD 能跑、越权被拦、删除级联 | 否 |
| 3 | `ai` 接口 + Factory + 2 个 parser + 2 个 saver | 一句话 → `tmp/code_output/html_x/index.html` | 否 |
| 4 | SSE 接口 + `StaticResourceController` | 浏览器看到流式输出 + 能预览 | 否 |
| 5 | `chat_history` + handler + 记忆回填 + 游标分页 | 二轮对话"记得"、历史可翻 | 否 |
| 6.1 | 路由服务 + Factory | 不选类型也能建应用 | 是 |
| 6.2 | 多文件 parser + saver | 生成三文件 | 是 |
| 6.3 | `ai/tools` + TokenStream + `VueProjectBuilder` | Vue 工程可构建、可预览 | 是 |
| 6.4 | `deployApp` + `ScreenshotServiceImpl` + COS | 有部署 URL、有封面 | 是 |
| 6.5 | `ProjectDownloadServiceImpl` | 下载 zip、过滤生效 | 是 |
| 6.6 | `@RateLimit` + 监控 Listener | 限流生效、指标可见 | 是 |
| 6.7 | LangGraph4j 工作流 | 图能跑通三种输出 | 是（建议先跳） |

**推荐节奏**：先把 **0→5** 走完（这条线就是"一个应用的一生"），跑通后再按 6.1→6.6 逐个挂。

## 附录 B：把这份代码"抄"成自己的（可改进点清单）

这些是**你完全可以在复现时做对、并拿去面试讲"我做了哪些改进"**的点：

| # | 原始实现 | 改进方案 | 收益 |
|---|---|---|---|
| 1 | 目录名 4 处硬编码 | 抽 `AppConstant.buildCodeDirName(type, appId)` | 消除最危险的隐性契约 |
| 2 | `processCodeStream` 吞保存异常 | 转 `sink.error` 或发 `saved` 事件 | 消除"假成功" |
| 3 | 秒级 `createTime` 游标 | 改 `id` 双游标 | 消除同秒漏记录 |
| 4 | 记忆窗口 20 出现两处 | 抽公共常量 | 防不一致 |
| 5 | 精选缓存无 `@CacheEvict` | 管理端更新时清缓存 | 精选即时生效 |
| 6 | `getAppVOList` N+1 | VO 组装支持传入 User 列表 | 减少 N 次查询 |
| 7 | `get/vo` 无鉴权 | 按优先级/作者过滤敏感字段 | 防 enumerate |
| 8 | `orderBy(sortField)` 直传 | 列名白名单 | 防注入面 |
| 9 | 固定盐 MD5 存密码 | BCrypt / `PasswordEncoder` | 安全性 |
| 10 | `createApp` 路由无兜底 | try/catch + 默认类型 | 提升可用性 |
| 11 | `getUserById` 返回 User | 返回 `UserVO` | 防密码泄露 |
| 12 | 静态资源无路径校验 | 路径正则白名单 | 防穿越 |
| 13 | `buildProjectAsync` 死代码 | 删除或真正接入 | 代码整洁 |
| 14 | 部署目录无清理 | 定时清理/引用计数 | 磁盘可控 |

## 附录 C：面试速答卡（20 问）

| # | 问题 | 一句话答案 |
|---|---|---|
| 1 | 项目分层怎么划的？ | Controller（协议/校验）→ Service（业务/权限）→ core（AI 编排/解析/落盘）→ ai（模型交互） |
| 2 | 持久层用什么？ | **MyBatis-Flex**（不是 MyBatis-Plus），`IService` 提供通用 CRUD |
| 3 | AI 接口为什么不写实现？ | LangChain4j `AiServices` 运行时生成动态代理 |
| 4 | 结构化输出 vs 流式怎么选？ | 要对象用同步 + POJO；要实时用 `Flux<String>` + 自己解析 |
| 5 | 解析为什么用正则？ | 模型输出是"散文 + 代码块"，必须先切块 |
| 6 | 为什么最后才落盘？ | 正则要完整文本；解析与推送解耦 |
| 7 | 模板方法用在哪？ | `CodeFileSaverTemplate`：final 锁流程，abstract 放开写文件 |
| 8 | 策略模式用在哪？ | `CodeParser` + `CodeParserExecutor` |
| 9 | 工厂模式用在哪？ | `AiCodeGeneratorServiceFactory`（带实例缓存）/ 路由服务（prototype 模型） |
| 10 | 门面模式用在哪？ | `AiCodeGeneratorFacade`：生成 + 解析 + 落盘一把梭 |
| 11 | 装饰/适配思想用在哪？ | `processTokenStream` 把 `TokenStream` 适配成 `Flux<String>` |
| 12 | 全项目最硬的契约？ | 目录名 `{codeType}_{appId}`，4 处调用点 |
| 13 | 权限有几种强度？ | 公开 / 登录 / 属主 / 属主或管理员 / 仅属主 / 仅管理员 |
| 14 | 为什么 `userId` 不能来自前端？ | 可伪造归属，整套权限失效 |
| 15 | 三层存储是哪三层？ | MySQL 账本 / Redis AI 记忆 / 浏览器渲染态 |
| 16 | 用户消息为什么先落库？ | 时序、可追溯、记忆一致性 |
| 17 | 记忆窗口与回填为什么必须一致？ | 否则白查库或上下文变短 |
| 18 | 游标分页为什么不用 OFFSET？ | 持续插入 + 深分页，会漏/重且慢 |
| 19 | 限流为什么用 Redis？ | 多实例本地限流失效 |
| 20 | 监控上下文怎么跨线程传？ | `ThreadLocal` 设置 + `attributes` 传递 + `doFinally` 清理 |

---

> **全篇一句话收尾**：
> 这个项目看起来"乱"，是因为你按**功能**在看；一旦按**资源**看，它只有 5 个服务、3 种生成类型、1 条主线（`Controller → Service → Facade → 代理 → 解析 → 落盘 → SSE`）。
> **你要复现的不是"功能清单"，而是这条主线，以及主线上每一个"为什么这么切"的决定。**
