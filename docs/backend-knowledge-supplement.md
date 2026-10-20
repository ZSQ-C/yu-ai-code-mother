# 后端知识点补充手册（项目锚定版）

> 定位：这份是 [L0~L6 文件清单](./L0-L6-file-checklist.md) 的「横向补充」。
> 清单解决「这个文件干什么」，这份解决「它背后依赖的那套知识是什么」。
>
> 已有文档分工：
> - 原理 + 复现路线 → [yu-ai-code-mother-rebuild-guide.md](./yu-ai-code-mother-rebuild-guide.md)
> - JVM / 并发 / 内存模型 → [concurrency-and-jvm-memory-notes.md](./concurrency-and-jvm-memory-notes.md)
> - 手写节奏 → [handson-coding-practice-plan.md](./handson-coding-practice-plan.md)
>
> 阅读方式：**每个知识点都是「概念 → 本项目哪段代码用了它 → 面试怎么问 → 一句话答案」四段式**。
> 上班碎片时间读，一次读 3~5 个知识点，读完立刻能在原项目里找到对应代码。

---

## 目录

| 章 | 主题 | 知识点数 |
|---|---|---|
| A | Spring 核心机制 | 10 |
| B | Spring MVC 与 HTTP | 9 |
| C | 数据访问与 MySQL | 12 |
| D | Redis 与缓存 | 9 |
| E | 响应式与流式传输 | 8 |
| F | LangChain4j 与 AI 应用 | 10 |
| G | 安全 | 9 |
| H | 工程化与稳定性 | 10 |
| I | 本项目 20 个 bug / 瑕疵（真实存在） | 20 |
| J | 面试速答卡 | — |

---

# A · Spring 核心机制

## A1. IoC 和 DI 到底解决了什么

**概念**
IoC（控制反转）= 对象的创建权从"你自己 new"交给容器。
DI（依赖注入）= 容器把依赖塞给你，而不是你去容器里拿。

**对比**

| | 自己 new | 容器管理 |
|---|---|---|
| 谁决定实现类 | 写代码的人 | 配置/注解 |
| 换实现要改几处 | 每个 new 的地方 | 一处 |
| 单例复用 | 自己保证 | 容器保证 |
| 生命周期回调 | 手动 | `@PostConstruct` / `@PreDestroy` |
| 测试替身 | 难 | 注入 mock 即可 |

**本项目锚点**
[AppServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L61-L80) 里有 7 个 `@Resource`。它从来没有 `new` 过任何一个依赖。

**面试问法**：为什么不用静态方法调 Service？
**答**：静态方法无法被代理（AOP 失效）、无法注入、无法替换实现、无法做事务。

---

## A2. Bean 生命周期（只记 6 个关键节点）

```
实例化（构造器）
  ↓
属性填充（@Autowired / @Resource 注入）
  ↓
BeanNameAware / BeanFactoryAware / ApplicationContextAware
  ↓
BeanPostProcessor#postProcessBeforeInitialization     ← AOP 代理在这一步前后生成
  ↓
@PostConstruct → InitializingBean#afterPropertiesSet → init-method
  ↓
BeanPostProcessor#postProcessAfterInitialization      ← ★ 代理对象在这里被包装
  ↓
使用中
  ↓
@PreDestroy → DisposableBean#destroy → destroy-method
```

**本项目锚点（一个真实的反例）**
[WebScreenshotUtils.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/utils/WebScreenshotUtils.java#L42-L45) 上加了 `@PreDestroy`：

```java
@PreDestroy
public void destroy() { webDriver.quit(); }
```

但这个类只有 `@Slf4j`，**没有 `@Component`**，不是 Spring Bean。
所以 `@PreDestroy` 永远不会被调用 → Chrome 驱动进程不会被回收 → 每次重启泄漏一个 Chrome。

**这就是"知道生命周期"的实际价值**：`@PreDestroy` 只对容器管理的 Bean 生效，不是"Java 的注解就都会生效"。

**面试问法**：`@PostConstruct` 和 `InitializingBean` 有什么区别？
**答**：前者是 JSR-250 标准，后者是 Spring 接口；执行顺序是 `@PostConstruct` 先。用前者可以不和 Spring 耦合。

---

## A3. 单例 vs 多例（`@Scope`）

| | singleton（默认） | prototype |
|---|---|---|
| 实例数 | 容器内 1 个 | 每次 `getBean` 一个新的 |
| 何时销毁 | 容器关闭 | 容器不管，GC 回收 |
| 线程安全 | **看你自己** | 天然隔离实例状态 |
| 适合 | 无状态 Service | 有状态的连接/流/会话对象 |

**本项目锚点**
[StreamingChatModelConfig.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/config/StreamingChatModelConfig.java) 三个 `StreamingChatModel` 都是 `@Scope("prototype")`。

**为什么**：流式模型内部持有"当前流"的可变状态，单例共享会让两个用户的 token 交错。

**注意一个坑**：prototype Bean 注入到 singleton Bean 里时，只在**注入那一刻**创建一次，之后不会每次换新。想每次拿新的必须用 `ObjectProvider` 或 `SpringContextUtil.getBean()` 动态取。

本项目正是这么做的 —— [AiCodeGeneratorServiceFactory.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/AiCodeGeneratorServiceFactory.java#L105-L124) 用 `SpringContextUtil.getBean("streamingChatModelPrototype", ...)` 手动取。**这不是"多此一举"，是 prototype 语义决定的**。

---

## A4. 循环依赖与三级缓存（面试必问）

```
A 依赖 B，B 依赖 A
```

Spring 用三级缓存解决：

| 缓存 | 名字 | 存什么 |
|---|---|---|
| 一级 | `singletonObjects` | 完全初始化好的 Bean |
| 二级 | `earlySingletonObjects` | 实例化但未填充属性的 Bean（**可能是代理**） |
| 三级 | `singletonFactories` | 产生早期引用的工厂（`ObjectFactory`） |

为什么需要第三级？因为 A 可能被 AOP 代理，暴露的应该是**代理对象**而不是原始对象。第三级的存在就是为了"延迟决定要不要生成代理"。

**能解决的前提**：setter/字段注入（属性填充在实例化之后）。
**不能解决**：构造器注入（A 构造器要 B，B 构造器要 A，死结）。

**Spring Boot 2.6+** 默认禁止循环依赖，需要 `spring.main.allow-circular-references=true` 才允许。

**面试问法**：为什么要三级缓存，两级不行吗？
**答**：两级的话，早期引用只能暴露原始对象，无法在"是否需要代理"这一点上做延迟决策，会破坏 AOP。

---

## A5. AOP 原理：JDK 动态代理 vs CGLIB

| | JDK Proxy | CGLIB |
|---|---|---|
| 要求 | 目标类必须**实现接口** | 类不能是 `final`，方法不能 `final`/`private` |
| 原理 | 运行时生成实现同一接口的类 | 运行时生成**子类**并重写方法 |
| Spring Boot 2.x+ 默认 | 否 | **是**（`proxyTargetClass=true`） |

**本项目锚点**
- [AuthInterceptor.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/aop/AuthInterceptor.java#L34-L58)：`@Around("@annotation(authCheck)")`
- [RateLimitAspect.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ratelimter/aspect/RateLimitAspect.java#L40-L52)：`@Before("@annotation(rateLimit)")`

两者都通过 `@annotation(...)` 拿到**注解实例本身**，所以能读注解上的属性（`rate`、`rateInterval`）。

**★ AOP 失效的 5 种情况（面试高频）**

| 情况 | 原因 |
|---|---|
| 同类内部方法调用 | `this.method()` 不走代理 |
| `private` / `final` / `static` 方法 | 代理无法重写 |
| 对象不是容器管理的 | 没有代理对象 |
| 自调用且用了 `@Transactional` | 同上，事务注解在代理层生效 |
| 切点表达式写错 | 根本没织入 |

**本项目里就有一个"疑似":**

[StreamHandlerExecutor.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/handler/StreamHandlerExecutor.java#L37-L42) 里 `new SimpleTextStreamHandler()`。这个类没有 Spring 注解，天然不需要代理，所以没问题。但如果以后给它加了 `@Transactional`，就会静默失效。

**面试问法**：为什么 `@Transactional` 加在同类内部调用上不生效？
**答**：事务是代理对象在方法入口处开启的。`this.method()` 绕过了代理，直接调原始对象，所以没有任何事务逻辑。

---

## A6. `@Transactional` 失效的 7 种场景

1. **同类内部调用**（最常见）
2. **方法非 public**（Spring 6 之前直接不生效）
3. **异常被自己 catch 了**（默认只回滚 `RuntimeException` 和 `Error`）
4. **异常类型不在 `rollbackFor` 里**（受检异常默认不回滚）
5. **数据库引擎不支持事务**（MyISAM）
6. **多线程**：事务绑定在 ThreadLocal，子线程不在同一事务
7. **传播行为设置不当**（`REQUIRES_NEW` / `NOT_SUPPORTED` 等）

**一句话记法**：**代理没走到、异常没抛出、连接没同一条** → 事务一定失效。

---

## A7. 自动配置原理（Spring Boot 3）

```
@SpringBootApplication
  = @SpringBootConfiguration
  + @ComponentScan
  + @EnableAutoConfiguration
        ↓
读 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
        ↓
按 @ConditionalOnClass / @ConditionalOnMissingBean 等条件筛选
        ↓
注册 BeanDefinition
```

**关键理解**：自动配置的本质是"**条件化的默认值**"。
你自己声明了 `CacheManager` Bean，自动配置的 `RedisCacheManager` 就不生效 —— 这就是 [RedisCacheManagerConfig.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/config/RedisCacheManagerConfig.java#L27-L50) 能覆盖默认行为的原因（`@ConditionalOnMissingBean`）。

**面试问法**：`@ConditionalOnMissingBean` 有什么风险？
**答**：顺序敏感。如果用户 Bean 定义得晚，可能两个都注册，或者判断时机不对。Spring Boot 用 `AutoConfigureAfter` / `AutoConfigureOrder` 来保证顺序。

---

## A8. 配置优先级（由高到低）

```
命令行参数
  > SPRING_APPLICATION_JSON
  > ServletConfig / ServletContext 参数
  > JNDI
  > Java System properties
  > 操作系统环境变量
  > application-{profile}.yml（jar 外部）
  > application-{profile}.yml（jar 内部）
  > application.yml（外部）
  > application.yml（内部）
  > @PropertySource
  > 默认值
```

**本项目锚点**：`spring.profiles.active: local` → 优先加载 `application-local.yml`。原项目 `.gitignore` 忽略了 `application-local.yml`，就是靠这个机制把密钥挡在仓库外。

---

## A9. 常用注解辨析

| 注解 | 语义 | 本项目用例 |
|---|---|---|
| `@Component` | 通用组件 | |
| `@Service` | 业务层（本质同 Component） | `AppServiceImpl` |
| `@Repository` | 数据层（能翻译异常） | |
| `@Configuration` | 配置类（CGLIB 增强，`@Bean` 方法单例） | `JsonConfig` |
| `@Resource` | 按**名称**注入（JSR-250） | 项目全用这个 |
| `@Autowired` | 按**类型**注入（Spring） | |
| `@Qualifier` | 配合 Autowired 指定名称 | |
| `@Value` | 注入配置值 | `deployHost` |

**为什么项目统一用 `@Resource`**：按名称注入，同名多个实现时不用额外加 `@Qualifier`，且是 JDK 标准。

---

## A10. 静态工具类 vs Spring Bean（判断标准）

| 条件 | 用静态工具类 | 用 Bean |
|---|---|---|
| 需要注入依赖 | ❌ | ✅ |
| 需要 AOP（事务/限流/日志） | ❌ | ✅ |
| 需要生命周期回调 | ❌（`@PreDestroy` 不生效） | ✅ |
| 无状态纯计算 | ✅ 更简洁 | 也可以 |
| 跨层随处调用 | ✅ 方便 | 需要注入链 |

**本项目三种形态并存，正好当案例**：

| 类 | 形态 | 判断依据 |
|---|---|---|
| `ThrowUtils` | 纯静态 | 无状态、无依赖、纯判断 |
| `CodeFileSaverExecutor` | 静态 + `static final` 单例 | 无状态，省容器开销 |
| `WebScreenshotUtils` | **静态但持有静态 WebDriver** | ❌ 反面教材：有状态还做静态 |

**一句话**：**静态方法不背锅，静态字段才背锅**。

---

# B · Spring MVC 与 HTTP

## B1. 一次请求的完整路径

```
浏览器
  → Tomcat（连接、线程池）
  → Filter 链（编码、CORS、安全）
  → DispatcherServlet
      → HandlerMapping（找方法）
      → HandlerAdapter（调方法）
      → 参数解析（HttpMessageConverter）
      → 拦截器 preHandle
      → Controller 方法
      → 返回值处理（HttpMessageConverter）
      → 拦截器 postHandle / afterCompletion
  → 异常 → HandlerExceptionResolver → @RestControllerAdvice
  → 响应
```

**本项目锚点**：`CorsConfig` 是 Filter 层，`AuthInterceptor` 是 AOP 层，`GlobalExceptionHandler` 是 `HandlerExceptionResolver` 层。三层的位置不同，职责也不同。

---

## B2. Filter vs Interceptor vs AOP

| | Filter | Interceptor | AOP |
|---|---|---|---|
| 归属 | Servlet 规范 | Spring MVC | Spring |
| 能拿到 | request/response | + handler 方法信息 | 方法参数、注解、返回值 |
| 执行位置 | DispatcherServlet 之前 | DispatcherServlet 之内 | 方法调用处 |
| 能否改请求体 | 能（wrapper） | 不能 | 不能 |
| 典型用途 | 编码、CORS、XSS 过滤 | 登录校验、日志 | 事务、限流、缓存 |

**选择建议**：
- 和 HTTP 协议相关的（编码、跨域）→ Filter
- 和"哪个 Controller 方法"相关的 → Interceptor
- 和"方法本身"相关的（事务、限流）→ AOP

---

## B3. `HttpMessageConverter`

Controller 方法入参 `@RequestBody` 和返回值，都靠它序列化。

| 场景 | 转换器 |
|---|---|
| JSON | `MappingJackson2HttpMessageConverter` |
| 表单 | `FormHttpMessageConverter` |
| 字符串 | `StringHttpMessageConverter` |
| 文件 | `ResourceHttpMessageConverter` |
| SSE | `ServerSentEventHttpMessageConverter` |

**本项目锚点**：[JsonConfig.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/config/JsonConfig.java) 修改的是 Jackson 的序列化器，影响的就是 `MappingJackson2HttpMessageConverter`。

**坑**：只改 Jackson 不会影响 Redis 的序列化 —— 那是 `RedisCacheConfiguration` 的事（见 D9）。

---

## B4. HTTP 状态码语义

| 码 | 含义 | 什么时候用 |
|---|---|---|
| 200 | 成功 | 一切正常 |
| 201 | 已创建 | POST 新建资源 |
| 204 | 无内容 | DELETE 成功 |
| 301 / 302 | 永久/临时重定向 | |
| 304 | 未修改 | 协商缓存 |
| 400 | 参数错误 | 客户端给的数据不合法 |
| 401 | 未认证 | 没登录 / token 无效 |
| 403 | 无权限 | 登录了但没这个权限 |
| 404 | 资源不存在 | |
| 405 | 方法不允许 | GET 接口用 POST 调 |
| 409 | 冲突 | 重复创建、状态机不允许 |
| 429 | 限流 | |
| 500 | 服务端错误 | |
| 502 / 504 | 网关错误 / 超时 | |

**本项目选择**：HTTP 永远 200，业务码放 `BaseResponse.code`。
**代价**：网关/APM 按状态码做告警、限流、路由时全部失效。
**收益**：前端只解析一种结构。

**面试问法**：这样设计好不好？
**答**：对内网/自研前端可以；对开放 API 和需要网关治理的场景不推荐。**关键是要能说出代价，而不是说"这样更好"**。

---

## B5. 幂等性（面试高频）

**定义**：同一个请求执行 N 次，结果和执行 1 次相同。

| 方法 | 幂等 | 安全 |
|---|---|---|
| GET | ✅ | ✅ |
| PUT | ✅ | ❌ |
| DELETE | ✅ | ❌ |
| POST | ❌ | ❌ |

**三种实现**：

1. **唯一键**：数据库唯一索引，重复插入报错 → 捕获后返回成功
2. **状态机**：只允许 `待支付 → 已支付`，重复回调直接忽略
3. **Token**：客户端先申请一次性 token，提交时校验并删除

**本项目哪里需要**：
- `deployApp` 重复调用 → 每次都复制目录，虽然结果一样但浪费 IO（天然幂等但低效）
- `createApp` 重复提交 → **不幂等**，会产生两个应用（真实风险：用户手抖连点）

---

## B6. Content-Type 与 Charset

| 值 | 用途 |
|---|---|
| `application/json` | JSON 请求/响应 |
| `application/x-www-form-urlencoded` | 表单 |
| `multipart/form-data` | 文件上传 |
| `text/event-stream` | **SSE** |
| `application/octet-stream` | 二进制下载 |
| `text/html; charset=utf-8` | 静态页面 |

**注意**：中文乱码 90% 是没指定 charset，或者写文件时用了平台默认编码。
本项目 [CodeFileSaverTemplate.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/CodeFileSaverTemplate.java#L50-L55) 显式指定 `StandardCharsets.UTF_8`，是对的。

---

## B7. SSE 协议细节

**报文格式**（注意两个换行结尾）：

```
event: step_completed
data: {"stepNumber":1,"currentStep":"分析需求"}

```

| 字段 | 作用 |
|---|---|
| `event` | 事件名，前端 `addEventListener` 监听 |
| `data` | 数据（可多行，每行以 `data:` 开头） |
| `id` | 断线重连时的 `Last-Event-ID` |
| `retry` | 重连间隔（毫秒） |

**浏览器侧**：

```javascript
const es = new EventSource("/api/app/chat/gen/code?appId=1&message=xxx");
es.addEventListener("step_completed", e => console.log(e.data));
es.addEventListener("done", () => es.close());
es.onerror = () => es.close();
```

**三个必须知道的限制**：
1. `EventSource` **只支持 GET**（不能带 body）
2. 默认**自动重连**（需要手动 `close()` 才停）
3. **单向**：服务端 → 客户端，客户端想发消息只能另开接口

---

## B8. 为什么流式会被"一次性返回"

排查顺序（从近到远）：

```
1. 服务端没 flush          → 检查返回值是不是 Flux/SseEmitter，而不是 String
2. Nginx 缓冲             → proxy_buffering off; (或 X-Accel-Buffering: no 响应头)
3. Tomcat 缓冲             → 一般没问题，但要确认没被包装成 ResponseEntity<byte[]>
4. 网关/云 LB 缓冲          → 需要关掉响应缓冲
5. curl 自己缓冲            → 用 curl -N
6. 前端没逐块处理           → fetch 的 response.body 要 getReader() 逐块读
```

**本项目锚点**：[AppController.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/AppController.java#L59-L86) 返回 `Flux<ServerSentEvent<String>>`，`produces = TEXT_EVENT_STREAM_VALUE`。

---

## B9. 静态资源与路径穿越

**攻击**：

```
GET /api/static/abc/../../../../etc/passwd
GET /api/static/abc/%2e%2e%2f%2e%2e%2fapplication.yml
```

**防御三层**：

```java
// 1. 规范化后再比较
File file = new File(root, path).getCanonicalFile();
String rootPath = new File(root).getCanonicalPath();
if (!file.getPath().startsWith(rootPath + File.separator)) {
    throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "非法路径");
}
// 2. 后缀白名单
// 3. 只允许 GET，禁止目录列表
```

**本项目锚点**：[StaticResourceController.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/StaticResourceController.java)。

---

# C · 数据访问与 MySQL

## C1. MyBatis-Flex 的定位

| | MyBatis | MyBatis-Plus | MyBatis-Flex |
|---|---|---|---|
| SQL 控制 | 手写 XML | 注解 + Wrapper | 注解 + Wrapper |
| 无 SQL 也能跑 | ❌ | ✅ | ✅ |
| 关联查询 | 手写 | 弱 | 支持 `Join` |
| APT 生成 | 无 | 无 | **有**（编译期生成 TableDef） |
| 性能 | 原生 | 中等 | 官方宣称更优 |

**本项目锚点**：[App.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/model/entity/App.java#L28-L38) 用 `@Table` / `@Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)`。

---

## C2. `BaseMapper` 给了什么

```java
insert(entity)
insertOrUpdate(entity)
insertBatch(List)
deleteById(id)
deleteByQuery(QueryWrapper)
update(entity)
updateByQuery(entity, QueryWrapper)
selectOneById(id)
selectOneByQuery(QueryWrapper)
selectListByQuery(QueryWrapper)
selectCountByQuery(QueryWrapper)
selectPageByQuery(Page, QueryWrapper)
```

**所以 Mapper 接口可以是空的**。你不需要写一行 SQL 就能完成 CRUD。

---

## C3. `QueryWrapper` 的两个陷阱

```java
QueryWrapper.create()
    .eq("appId", appId)        // appId 为 null 时生成 appId = null
    .orderBy(sortField, true)  // sortField 来自前端 → 注入面
```

**陷阱 1：null 条件**
必须 `if (x != null) wrapper.eq(...)`，否则 `WHERE appId = null` 永远查不到（SQL 里 `null = null` 是 `unknown`，不是 true）。

**陷阱 2：排序字段注入**
`orderBy("id; drop table app")` 之类。用**白名单**：

```java
private static final Set<String> ALLOWED_SORT = Set.of("id", "createTime", "priority", "editTime");
ThrowUtils.throwIf(!ALLOWED_SORT.contains(sortField), ErrorCode.PARAMS_ERROR, "非法排序字段");
```

**本项目锚点**：[ChatHistoryServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ChatHistoryServiceImpl.java#L158-L163) 这里 `orderBy(sortField, ...)` 是直传的 —— **这是一个真实的注入面**。

---

## C4. 逻辑删除

**做法**：加 `isDelete` 字段，删除变 `UPDATE ... SET isDelete = 1`，查询自动加 `WHERE isDelete = 0`。

**本项目锚点**：[App.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/model/entity/App.java#L107-L108)

```java
@Column(value = "isDelete", isLogicDelete = true)
private Integer isDelete;
```

**三个注意点**：

| 问题 | 说明 |
|---|---|
| 唯一索引冲突 | `userAccount` 唯一索引 + 逻辑删除 → 删了还占着账号，无法重新注册。解法：唯一索引加 `isDelete` 字段，或删除时改账号名 |
| 数据膨胀 | 表会越来越大，需要归档策略 |
| 关联查询 | 手写 SQL 不会自动加条件，必须自己写 |

---

## C5. 分页原理

**物理分页**（MySQL）：`LIMIT offset, size` —— 数据库只返回需要的行。
**逻辑分页**：查全量再在内存切 —— 数据量大直接 OOM。

**`OFFSET` 的性能问题**：

```sql
-- 第 10000 页
SELECT * FROM chat_history ORDER BY createTime DESC LIMIT 20 OFFSET 199980;
```

MySQL 需要**扫描并丢弃前 199980 行**。数据量大时是 O(n)。

**游标分页**：

```sql
SELECT * FROM chat_history
WHERE appId = ? AND createTime < ?
ORDER BY createTime DESC LIMIT 20;
```

配合索引 `(appId, createTime)` → 直接定位，O(log n)。

**本项目锚点**：[ChatHistoryServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ChatHistoryServiceImpl.java#L84-L99) + [L153-L163](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ChatHistoryServiceImpl.java#L153-L163)。

**★ 已知缺陷**：`createTime` 是秒级精度，同一秒的多条记录会被 `lt` 整体跳过。
正确写法是复合游标：

```sql
WHERE (createTime < ?) OR (createTime = ? AND id < ?)
```

---

## C6. 索引原理（B+ 树）

```
              [ 10 | 30 ]                 ← 非叶子节点只存 key + 指针
             /     |      \
      [1|5|8]  [12|20]  [35|50]           ← 叶子节点存 key + 行指针/整行
        ↓        ↓        ↓
      数据页   数据页   数据页              ← 叶子之间用链表串起来（支持范围查询）
```

**为什么用 B+ 树而不是 B 树 / 哈希 / 红黑树**：

| 结构 | 范围查询 | 磁盘 IO | 说明 |
|---|---|---|---|
| B+ 树 | ✅ 链表 | 低（矮胖，3~4 层存千万级） | ✅ |
| B 树 | ❌ 要中序回溯 | 中 | 非叶子也存数据，扇出小 |
| 哈希 | ❌ | 1 次 | 只支持等值 |
| 红黑树 | ✅ | 高（树高 log n） | 内存结构 |

**一次查询的 IO 次数** ≈ 树高。千万级数据 3 层 ≈ 3 次 IO。

---

## C7. 回表、覆盖索引、最左前缀

**回表**：二级索引查到主键后，再回聚簇索引查整行 → 2 次查找。

```sql
-- 有索引 idx_appId_createTime(appId, createTime)
SELECT message FROM chat_history WHERE appId = 1 ORDER BY createTime DESC;
-- appId/createTime 在索引里，message 不在 → 需要回表
```

**覆盖索引**：查询的列全在索引里，不用回表。

```sql
SELECT id, appId, createTime FROM chat_history WHERE appId = 1;
-- 全部命中 → Using index
```

**最左前缀**：联合索引 `(a, b, c)` 能被 `a`、`a+b`、`a+b+c` 使用，不能被 `b`、`b+c` 使用。

```sql
-- 索引 (appId, createTime)
WHERE appId = 1 AND createTime < '...'  -- ✅ 两个都用上
WHERE createTime < '...'                 -- ❌ 用不上（跳过最左列）
```

**本项目对应**：游标分页的 `WHERE appId = ? AND createTime < ?` 必须建 `(appId, createTime)` 联合索引，否则每次都是全表扫。**这就是清单里强调那个索引的原因**。

---

## C8. 索引失效的 7 个场景

1. **函数/运算**：`WHERE YEAR(createTime) = 2026`
2. **隐式类型转换**：`WHERE userId = '123'`（userId 是 bigint）
3. **前导模糊**：`LIKE '%abc'`
4. **OR 连接非索引列**：`WHERE a = 1 OR b = 2`（b 无索引）
5. **联合索引跳过最左列**
6. **`!=` / `NOT IN`**（通常失效，取决于优化器）
7. **区分度太低**：`isDelete` 只有 0/1，优化器可能不走索引

**本项目锚点**：`LIKE` 查询 `message` 字段 —— 如果加了索引，`like("message", message)` 是 `%xxx%` 形式，索引无效。原项目没给 message 建索引，是对的。

---

## C9. 雪花 ID vs 自增 ID

| | 自增 ID | 雪花 ID |
|---|---|---|
| 有序 | ✅ | 大致有序 |
| 可枚举 | ✅（安全风险） | ❌（更安全） |
| 分库分表 | ❌ 冲突 | ✅ |
| 长度 | 短 | 19 位（JS 精度问题） |
| 信息泄露 | 暴露业务量 | 不暴露 |

**雪花结构**（64 bit）：

```
1 位符号(0) | 41 位时间戳(ms) | 10 位机器ID | 12 位序列号
```

41 位 ≈ 69 年；12 位序列 ≈ 单机每毫秒 4096 个。

**本项目**：`App.id` 用雪花（[App.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/model/entity/App.java#L37-L38)）。
**这就是必须配 `JsonConfig` 把 Long 转 String 的原因**：19 位超过 JS `Number.MAX_SAFE_INTEGER`（2^53-1 ≈ 9007199254740991）。

---

## C10. N+1 查询

```java
// ❌ N+1：列表 20 条 → 21 次查询
for (App app : appList) {
    User user = userService.getById(app.getUserId());
    app.setUser(user);
}

// ✅ 2 次查询
Set<Long> userIds = appList.stream().map(App::getUserId).collect(toSet());
Map<Long, User> userMap = userService.listByIds(userIds).stream()
        .collect(toMap(User::getId, u -> u));
```

**识别方法**：看到"在循环里调用数据库"就是 N+1。

**本项目锚点**：[AppServiceImpl.getAppVOList](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java)（在阶段 2 的文档里提过）。

---

## C11. 事务隔离级别与 MVCC

| 级别 | 脏读 | 不可重复读 | 幻读 |
|---|---|---|---|
| READ UNCOMMITTED | ✅ | ✅ | ✅ |
| READ COMMITTED | ❌ | ✅ | ✅ |
| REPEATABLE READ（MySQL 默认） | ❌ | ❌ | ✅* |
| SERIALIZABLE | ❌ | ❌ | ❌ |

\* MySQL 用 MVCC + 间隙锁（Next-Key Lock）在 RR 下基本解决了幻读。

**MVCC 两个隐藏列**：
- `DB_TRX_ID`：最后修改该行的事务 ID
- `DB_ROLL_PTR`：指向 undo log 的版本链

**Read View** 决定"这个版本对我可见吗"。

**一句话**：**RC 每次查询新建 Read View，RR 只在第一次查询时建**。这就是 RR 能"可重复读"的原因。

---

## C12. 数据库连接池

**HikariCP 关键参数**：

| 参数 | 含义 | 建议 |
|---|---|---|
| `maximumPoolSize` | 最大连接 | CPU 核数 × 2~4 |
| `minimumIdle` | 最小空闲 | 和 maximum 一致（避免抖动） |
| `connectionTimeout` | 拿连接超时 | 3s（默认 30s 太长） |
| `idleTimeout` | 空闲回收 | 10min |
| `maxLifetime` | 连接寿命 | 比数据库 `wait_timeout` 小 |

**为什么不要设太大**：连接是数据库的稀缺资源。100 个并发 = 100 个数据库线程，数据库上下文切换成本会超过收益。

**本项目**：pom 里显式引入了 `HikariCP 4.0.3`（其实 Spring Boot 默认已带，版本由 BOM 管理，显式指定反而可能覆盖）。

---

# D · Redis 与缓存

## D1. 五种基本数据结构与用途

| 结构 | 命令 | 典型用途 | 本项目 |
|---|---|---|---|
| String | `SET/GET/INCR` | 缓存、计数器、Session | Session、限流令牌 |
| Hash | `HSET/HGETALL` | 对象字段局部更新 | — |
| List | `LPUSH/RPOP` | 队列、最新 N 条 | — |
| Set | `SADD/SINTER` | 去重、共同好友 | — |
| ZSet | `ZADD/ZRANGE` | 排行榜、延时队列 | — |

**扩展类型**：Bitmap（签到）、HyperLogLog（UV 估算）、GEO（附近的人）、Stream（消息队列）。

---

## D2. Session 共享原理

```
浏览器 ──Cookie: JSESSIONID=abc──► 服务实例 A
                                      │
                               Spring Session
                                      │
                                      ▼
                              Redis: spring:session:sessions:abc
                                      ▲
                                      │
浏览器 ──Cookie: JSESSIONID=abc──► 服务实例 B
```

**为什么需要**：多实例部署时，请求可能打到任意一台。本地 Session 会导致"刚登录又掉线"。

**关键配置**：

```yaml
spring:
  session:
    store-type: redis
    timeout: 2592000        # 30 天
server:
  servlet:
    session:
      cookie:
        max-age: 2592000
```

**本项目**：`application.yml` 里配了这两项。

**追问**：Session 存 Redis 有什么问题？
**答**：① 每次请求多一次 Redis 访问（可用本地缓存缓解）；② Redis 挂了所有人掉线；③ 大对象不要放 Session。

---

## D3. 缓存穿透 / 击穿 / 雪崩

| 问题 | 现象 | 解法 |
|---|---|---|
| **穿透** | 查不存在的数据，每次都打到 DB | 布隆过滤器 / 缓存空值（短 TTL） |
| **击穿** | 热点 key 过期瞬间，大量请求打 DB | 互斥锁重建 / 逻辑过期不删 / 永不过期+异步刷新 |
| **雪崩** | 大量 key 同时过期 / Redis 宕机 | 随机 TTL / 多级缓存 / 熔断降级 / 高可用 |

**记忆法**：
- 穿透 = **数据不存在**
- 击穿 = **一个热点 key 没了**
- 雪崩 = **一片 key 同时没了**

**本项目锚点**：[RedisCacheManagerConfig.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/config/RedisCacheManagerConfig.java#L34-L49)

```java
.entryTtl(Duration.ofMinutes(30))
.disableCachingNullValues()      // ★ 禁用 null 缓存 → 放弃了"防穿透"这一招
.withCacheConfiguration("good_app_page", defaultConfig.entryTtl(Duration.ofMinutes(5)))
```

**注意**：`disableCachingNullValues()` 意味着查不到时**不会缓存空值**，所以"查不存在的 id"会反复穿透到 DB。这是一个**真实的设计取舍**（缓存空值会增加内存和"脏空值"风险）。

---

## D4. 缓存更新策略

| 策略 | 一致性 | 复杂度 | 说明 |
|---|---|---|---|
| Cache Aside | 最终 | 低 | 读：miss 查库回填；写：**先更库再删缓存** |
| Read/Write Through | 较好 | 中 | 缓存层代理读写 |
| Write Behind | 弱 | 高 | 异步批量写，可能丢数据 |

**为什么是"删缓存"而不是"更新缓存"**：
① 并发写时更新顺序可能错乱；② 很多缓存值需要复杂计算，更新成本高；③ 懒加载更省。

**为什么"先更库再删缓存"**：
反过来的话（先删缓存再更库），删完到更库之间有一个窗口，其他请求会把**旧值**回填。

**极端情况仍不一致**：读请求在写请求之前查库（拿到旧值），在删缓存之后才回填 → 旧值复活。
解法：延时双删 / Canal 订阅 binlog。

**本项目缺口**：`good_app_page` 缓存**没有 `@CacheEvict`**。管理端改了 priority，精选列表 5 分钟内不更新。这是真实瑕疵。

---

## D5. 分布式锁（Redisson）

**手写 Redis 锁的 4 个坑**：

```java
// ❌ 这个写法有 4 个问题
Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, "1");
if (ok) {
    doBusiness();
    redisTemplate.delete(key);
}
```

| 坑 | 后果 | 解法 |
|---|---|---|
| 没有过期时间 | 宕机 → 死锁 | `setIfAbsent(key, val, 30s)` |
| 删错别人的锁 | 误删 | value 存 UUID，删除前比对（Lua 原子） |
| 业务超时锁先失效 | 并发进入 | 看门狗续期 |
| 主从切换丢锁 | 两个客户端同时持锁 | RedLock（有争议）/  fencing token |

**Redisson 都解决了**：`RLock` 内置 UUID + Lua 删除 + 看门狗（默认 30s 续期）。

**本项目锚点**：[RedissonConfig.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ratelimter/config/RedissonConfig.java)。

---

## D6. 限流算法

| 算法 | 特点 | 突发流量 | 实现 |
|---|---|---|---|
| 固定窗口计数 | 简单，有临界问题 | 允许 2 倍 | `INCR` + `EXPIRE` |
| 滑动窗口 | 平滑，需存时间序列 | 较平滑 | ZSet 存时间戳 |
| 漏桶 | 恒定速率，不允许突发 | ❌ 削平 | 队列 + 定时消费 |
| **令牌桶** | 允许一定突发 | ✅ | Redisson `RRateLimiter` |

**临界问题示例**（固定窗口）：限制 100 次/分钟。用户在 00:59 发 100 次，01:00 又发 100 次 → 2 秒内 200 次。

**本项目锚点**：[RateLimitAspect.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ratelimter/aspect/RateLimitAspect.java#L44-L51)

```java
RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
rateLimiter.expire(Duration.ofHours(1));
rateLimiter.trySetRate(RateType.OVERALL, rate, rateInterval, RateIntervalUnit.SECONDS);
if (!rateLimiter.tryAcquire(1)) throw new BusinessException(TOO_MANY_REQUEST, msg);
```

**三个细节**：
1. `expire`：防止每个用户一个 key 无限增长
2. `trySetRate`：幂等，只在第一次设置
3. `RateType.OVERALL` vs `PER_CLIENT`：前者所有客户端共享配额，后者每客户端独立

---

## D7. Key 设计规范

```
{业务}:{对象}:{id}                 → app:detail:123
{业务}:{对象}:{id}:{字段}           → app:stat:123:view
{业务}:{维度}:{值}                  → rate_limit:user:456
```

**四条原则**：
1. **可读**：看到 key 知道是什么
2. **有层次**：用 `:` 分隔，方便 `SCAN` 前缀匹配
3. **控制长度**：太长浪费内存
4. **必须设 TTL**（除非明确要永久）

**禁止**：`KEYS *`（阻塞 Redis），用 `SCAN`。

---

## D8. Redis 持久化

| | RDB | AOF |
|---|---|---|
| 原理 | 定时快照 | 追加写命令 |
| 恢复速度 | 快 | 慢 |
| 数据安全 | 可能丢几分钟 | 可配 `everysec`（丢 1 秒） |
| 文件大小 | 小（压缩） | 大 |
| 对性能影响 | fork 时阻塞 | 写盘有开销 |

**生产建议**：RDB + AOF 混合（`aof-use-rdb-preamble yes`）。

**本项目用途**：Session、对话记忆、限流 —— **都属于可重建数据**，丢了影响体验但不致命。如果 Redis 只做缓存，可以关持久化省性能。

---

## D9. 序列化（一个真实的坑）

[RedisCacheManagerConfig.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/config/RedisCacheManagerConfig.java#L34-L42) 里 value 序列化器**被注释掉了**：

```java
RedisCacheConfiguration.defaultCacheConfig()
    .entryTtl(Duration.ofMinutes(30))
    .disableCachingNullValues()
    .serializeKeysWith(... StringRedisSerializer ...)
//  .serializeValuesWith(... GenericJackson2JsonRedisSerializer ...)   ← 被注释
```

**结果**：value 走默认的 `JdkSerializationRedisSerializer`。

**两个后果**：
1. 被缓存的对象**必须实现 `Serializable`**（`Page`、`AppVO` 都要）
2. Redis 里存的是**二进制**，无法用 `redis-cli get` 看懂，也无法跨语言（比如 Python 服务）读

**注释里的原话**也说明了原因：`需要给序列化增加默认类型配置，否则无法反序列化` —— 这是 Jackson 多态序列化的老问题。

---

# E · 响应式与流式传输

## E1. Flux / Mono 是什么

| | 含义 | 元素数 |
|---|---|---|
| `Mono<T>` | 0 或 1 个元素 | ≤ 1 |
| `Flux<T>` | 0 到 N 个元素 | 0 ~ ∞ |

**核心区别：不是"异步的 List"，而是"事件流"**。

```
Flux:  onSubscribe → onNext(a) → onNext(b) → onNext(c) → onComplete
                   ↘ onError（任意时刻可替代后面的所有事件）
```

---

## E2. 背压（Backpressure）

**问题**：生产者速度快于消费者，内存会爆。

**Reactor 的策略**：

| 策略 | 行为 |
|---|---|
| `onBackpressureBuffer` | 缓存（默认无界 → 危险） |
| `onBackpressureDrop` | 丢弃新元素 |
| `onBackpressureLatest` | 只保留最新一个 |
| `limitRate(n)` | 请求 n 个再请求 |

**SSE 场景**：浏览器消费速度通常跟得上（人眼看的），所以项目里没显式处理背压。但如果是"服务端 → 服务端"的流水线就必须考虑。

---

## E3. 阻塞 vs 非阻塞的边界

**关键认知**：Reactor 本身不创造线程。**只有 `publishOn` / `subscribeOn` 才切换线程**。

```java
flux.map(x -> blockingCall(x))     // ❌ 阻塞了事件循环线程
    .subscribeOn(Schedulers.boundedElastic())  // ✅ 放到弹性线程池
```

**本项目锚点**：`SseEmitter` 版本用的是 `Thread.startVirtualThread`（[CodeGenWorkflow.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/CodeGenWorkflow.java#L169-L214)），是**命令式阻塞式**写法；`Flux` 版本是响应式。两种都能做流式，但语义不同。

---

## E4. 冷流 vs 热流

| | 冷流 | 热流 |
|---|---|---|
| 订阅前 | 不产生数据 | 一直在产生 |
| 多次订阅 | 每次重新执行 | 共享同一份 |
| 例 | HTTP 请求、数据库查询 | 消息总线、鼠标事件 |

**`Flux` 默认是冷的**：每个订阅者都会触发一次完整的 AI 调用。如果前端重连，**会重复调用 LLM 扣两次钱** —— 这是一个需要注意的点。

---

## E5. `doOnNext` / `doOnComplete` / `doFinally`

| 回调 | 时机 | 能否改变数据 |
|---|---|---|
| `doOnNext` | 每个元素 | ❌（副作用专用） |
| `doOnComplete` | 正常结束 | ❌ |
| `doOnError` | 异常 | ❌ |
| `doFinally` | 任何结束（完成/错误/取消） | ❌ |
| `map` / `flatMap` | 转换 | ✅ |

**本项目锚点**：
- [AppServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L112-L116)：`doFinally` 清理 `ThreadLocal`
- `SimpleTextStreamHandler`：`doOnNext` 攒字符串、`doOnComplete` 写库

**★ 为什么清理要放 `doFinally` 而不是 `doOnComplete`**：
流被取消或出错时 `doOnComplete` 不会触发，但 `doFinally` 一定触发。**不清理 ThreadLocal → 线程复用时会串数据**。

---

## E6. `SseEmitter` 生命周期

```
new SseEmitter(timeout)
  → 返回给 Spring MVC（请求线程立刻释放）
  → 后台线程 send() 多次
  → complete() / completeWithError() / timeout
  → onCompletion / onTimeout / onError 回调
```

**三个坑**：

| 坑 | 后果 | 解法 |
|---|---|---|
| 不设 timeout | 默认 30s（取决于容器），长任务被掐 | `new SseEmitter(30 * 60 * 1000L)` |
| 客户端断开还继续 send | `IOException` 刷日志 | 捕获后 `complete()` |
| 不注册 `onCompletion` | 后台任务继续跑，浪费资源 | 注册回调取消任务 |

**本项目锚点**：[CodeGenWorkflow.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/CodeGenWorkflow.java#L169-L214) 设了 30 分钟，且 catch 了异常，但**没有注册 `onCompletion`** → 用户关掉页面后，后台仍在跑完整条工作流（会继续消耗 LLM 额度）。

---

## E7. 虚拟线程与流式

**为什么流式接口特别适合虚拟线程**：

```
平台线程：1000 个 SSE 连接 = 1000 个 OS 线程 ≈ 1GB 栈内存 + 大量上下文切换
虚拟线程：1000 个 SSE 连接 = 1000 个对象 ≈ 几 MB
```

因为 SSE 是"**长时间等待 + 偶尔写一点**"的 IO 密集场景，正是虚拟线程的最佳场景。

**本项目三处都在用**：
1. `Thread.startVirtualThread` 跑图（[CodeGenWorkflow.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/CodeGenWorkflow.java#L171)）
2. `Thread.startVirtualThread` 生成截图（[AppServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L209)）
3. `Executors.newVirtualThreadPerTaskExecutor()`

**注意 JDK 21 的 pin 问题**（JEP 491 在 JDK 24 修复）：`synchronized` 块里做阻塞 IO 会钉住载体线程。

---

## E8. 为什么"边收边解析"一定错

```
AI 流式输出：
data: "```ht"
data: "ml\n<!DOCTYPE"
data: " html>..."
```

如果在 `onNext` 里就正则匹配 ` ```html ... ``` `，**永远匹配不到闭合的 ```**。

**正确做法**：

```java
StringBuilder buffer = new StringBuilder();
flux.doOnNext(buffer::append)          // 只攒
    .doOnComplete(() -> {
        Object result = parser.parse(buffer.toString());  // 流结束才解析
        saver.save(result, appId);                        // 才落盘
    })
```

**这条原则通用到所有流式解析场景**：JSON 流、日志流、CSV 流都一样。

---

# F · LangChain4j 与 AI 应用

## F1. `AiServices` 的动态代理原理

```java
AiCodeGeneratorService service = AiServices.builder(AiCodeGeneratorService.class)
        .chatModel(chatModel)
        .streamingChatModel(streamingChatModel)
        .chatMemory(chatMemory)
        .tools(...)
        .build();
```

**这个接口没有实现类，谁实现的？**
LangChain4j 在运行时用 `Proxy.newProxyInstance` 生成实现。当你调用 `generateHtmlCode(msg)` 时，代理内部做的事是：

```
1. 读接口方法上的 @SystemMessage / @UserMessage / @V 注解
2. 从 ChatMemory 取历史
3. 拼成 ChatRequest
4. 调 ChatModel
5. 按返回类型反序列化（String / POJO / Result<T>）
6. 写回 ChatMemory
7. 返回
```

**所以**：

| 你的写法 | 实际含义 |
|---|---|
| 接口方法 | 一次对话的调用契约 |
| `@SystemMessage` | 系统提示词 |
| `@UserMessage` | 用户消息模板 |
| 返回 POJO | 结构化输出 |
| 返回 `Flux<String>` | 流式 |
| 返回 `TokenStream` | 流式 + 工具调用事件 |

**本项目锚点**：[AiCodeGeneratorService.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/AiCodeGeneratorService.java)。

---

## F2. ChatMemory 三层结构

```
ChatMemory（接口）
  └── MessageWindowChatMemory（实现：滑动窗口）
        ├── maxMessages = 20
        ├── ChatMemoryStore（持久化接口）
        │     └── RedisChatMemoryStore（本项目）
        └── id（区分不同会话）
```

**`ChatMemoryProvider` vs `chatMemory`**：

| 写法 | 语义 |
|---|---|
| `.chatMemory(memory)` | 所有调用共享一个 memory |
| `.chatMemoryProvider(id -> memory)` | 按 `@MemoryId` 参数路由到不同 memory |

**本项目两种都用了**：
- HTML/MULTI_FILE → `.chatMemory(chatMemory)`（因为服务实例已经按 appId 隔离了）
- VUE_PROJECT → `.chatMemoryProvider(memoryId -> chatMemory)`（用了 `@MemoryId`）

---

## F3. 结构化输出（Structured Output）

**原理**：LangChain4j 把 POJO 的字段和 `@Description` **转成 JSON Schema**，作为 `response_format` 传给模型（如果模型支持），然后在返回时反序列化。

```java
public class HtmlCodeResult {
    @Description("完整的 HTML 代码，包含所有样式和脚本")
    private String htmlCode;
}
```

**三重契约**（本项目最核心的设计点）：

```
POJO 字段名 + @Description
        ↕ 必须一致
提示词里的输出格式要求（```html ... ```）
        ↕ 必须一致
解析器的正则（```html\s*\n([\s\S]*?)```）
```

**改任意一个，另外两个都可能失效。** 这就是为什么"提示词和解析器是强绑定契约"。

---

## F4. Function Calling / Tool 调用流程

```
1. 用户消息 + 工具定义（name/description/parameters schema）→ 模型
2. 模型返回：我要调 file_write(path=..., content=...)
3. 框架执行你这个 @Tool 方法
4. 结果作为 ToolExecutionResultMessage 回传模型
5. 模型继续推理（可能再调工具）→ 直到不再调用工具
6. 输出最终答案
```

**本项目锚点**：[ai/tools/](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools) 下 8 个工具类，`@Tool` 描述 + `@P` 参数描述。

**两个常见问题**：

| 问题 | 解法 |
|---|---|
| 模型编造不存在的工具名 | `hallucinatedToolNameStrategy` 返回错误信息让模型重试 |
| 无限循环调工具 | `maxSequentialToolsInvocations(20)` 限制次数 |

**本项目两个都配了**（[AiCodeGeneratorServiceFactory.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/AiCodeGeneratorServiceFactory.java#L112-L116)）。

---

## F5. Guardrail（护轨）

| 类型 | 时机 | 用途 |
|---|---|---|
| 输入护轨 | 调模型前 | 敏感词、Prompt 注入检测 |
| 输出护轨 | 得到结果后 | 幻觉检测、格式校验、重试 |

**本项目锚点**：
- [PromptSafetyInputGuardrail.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/guardrail/PromptSafetyInputGuardrail.java)
- [RetryOutputGuardrail.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/guardrail/RetryOutputGuardrail.java) —— **但在 Factory 里被注释掉了**

**为什么输出护轨不能用**：

```java
// .outputGuardrails(new RetryOutputGuardrail())  // 为了流式输出，这里不使用
```

**原因**：输出护轨需要"拿到完整结果再判断"，而流式是"边产生边发"。两者语义冲突。这是流式带来的**能力损失**，值得记住。

---

## F6. Prompt 工程的 6 个实用技巧

| 技巧 | 说明 | 本项目 |
|---|---|---|
| 角色设定 | "你是一位资深前端工程师" | system prompt 里有 |
| 输出格式约束 | 明确要求 markdown 代码块 | ✅ 关键 |
| 分隔符 | 用 `"""` 或 XML 标签包裹用户输入 | 防注入 |
| Few-shot | 给 1~2 个示例 | 复杂格式时用 |
| 分步推理 | "先分析需求，再输出代码" | 提升质量 |
| 负面约束 | "不要输出解释文字" | 简化解析 |

**★ 提示词是把双刃剑**：越严格，模型越可能"为了满足格式而编造内容"。

---

## F7. Token 与成本

**计算**：中文 1 字 ≈ 1~2 token，英文 1 词 ≈ 1.3 token。

**成本三要素**：输入 token × 单价 + 输出 token × 单价 + 缓存命中折扣。

**优化手段**：

| 手段 | 效果 |
|---|---|
| 缩短 system prompt | 每次调用都省 |
| 减少历史条数 | 20 → 10，省一半 |
| 用便宜模型做分类 | qwen-turbo vs deepseek-chat |
| Prompt 缓存（部分厂商支持） | system prompt 命中打折 |
| 流式输出早停 | 用户中断即停 |

**本项目**：
- 路由用 `qwen-turbo` + `max-tokens: 100`（[application.yml](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/resources/application.yml#L73-L80)）
- 生成用 `deepseek-chat` + `max-tokens: 8192`
- 推理用 `deepseek-reasoner` + `max-tokens: 32768`

**这就是"按任务难度分配模型"的标准做法**。

---

## F8. RAG 与本项目的关系

本项目**没有用 RAG**，但你在简历里写过 RAG，面试可能横向问。

| | RAG | 本项目（Code Generation） |
|---|---|---|
| 核心问题 | 知识不在模型参数里 | 从自然语言生成代码 |
| 输入 | 问题 + 检索到的文档片段 | 提示词 |
| 输出 | 自然语言答案 | 结构化代码 |
| 关键指标 | 召回率、准确率 | 可运行率、编译通过率 |
| 失败模式 | 检索不到 / 幻觉 | 格式错 / 代码跑不起来 |

**可以讲的连接点**：代码生成也需要"知识注入"（比如把项目的技术栈、组件库文档作为 system prompt 的一部分），本质是 RAG 的变体。

---

## F9. 模型幻觉的三个层次

| 层次 | 表现 | 本项目对策 |
|---|---|---|
| 格式幻觉 | 不按 ```html 输出 | 结构化输出 + 解析器兜底 |
| 工具幻觉 | 调用不存在的工具 | `hallucinatedToolNameStrategy` |
| 内容幻觉 | 编造不存在的 API | 无法根治，只能靠执行验证（Vue 构建） |

**★ 关键认知**：**能"执行"的系统才有真相**。
本项目 Vue 类型会真的跑 `npm build`，构建失败就说明代码有问题 —— 这是从"模型自述"到"客观验证"的跨越。

---

## F10. 三套模型的配置方式

```yaml
langchain4j:
  open-ai:
    chat-model:                   # 同步
      base-url: https://api.deepseek.com
      model-name: deepseek-chat
    streaming-chat-model:         # 流式
      model-name: deepseek-chat
    reasoning-streaming-chat-model:  # 推理
      model-name: deepseek-reasoner
      temperature: 0.1
    routing-chat-model:           # 分类（换成阿里百炼）
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      model-name: qwen-turbo
      max-tokens: 100
```

**为什么可以用两个厂商**：因为它们都兼容 **OpenAI 协议**（`/v1/chat/completions`）。LangChain4j 的 `open-ai` starter 其实是一个通用 OpenAI 兼容客户端。

**这就是"协议比实现重要"的典型案例**。

---

# G · 安全

## G1. 密码存储的演进

```
明文 → MD5 → MD5+盐 → BCrypt / Argon2 / scrypt
```

| 方式 | 问题 |
|---|---|
| 明文 | 泄露即灾难 |
| MD5 | 彩虹表可查；算力已可暴力破解 |
| MD5+固定盐 | 盐泄露后同样可批量破解 |
| MD5+随机盐（存库） | 好一些，但仍太快（GPU 每秒百亿次） |
| **BCrypt** | 自带随机盐 + **可调计算成本**，天然抗暴力 |

**BCrypt 的特点**：同一个密码每次 hash 结果不同（盐随机），验证时从 hash 里取出盐再算。

**本项目**：用固定盐 + MD5/摘要（阶段 1 讲过）。**这是可以在面试里主动说的改进点**。

---

## G2. Session vs JWT vs OAuth2

| | Session | JWT | OAuth2 |
|---|---|---|---|
| 状态 | 服务端存 | 客户端存 | 授权框架（可配 JWT） |
| 主动失效 | ✅ 删 session | ❌ 需要黑名单 | 取决于实现 |
| 水平扩展 | 需共享存储 | 天然 | |
| 体积 | Cookie 小 | Token 大 | |
| 适合 | 单体/管理后台 | 开放 API / 微服务 | 第三方授权 |

**关键权衡**：**JWT 的无状态是优点也是缺点** —— 服务端不知道"谁被踢下线了"。

**本项目的选择**：Session + Redis（Spring Session）。**理由**：需要"封号立即生效"。

---

## G3. 水平越权 vs 垂直越权

| 类型 | 定义 | 例子 | 防御 |
|---|---|---|---|
| 水平越权 | 同角色用户互相访问 | 用户 A 查用户 B 的应用 | 每个接口校验 `resource.userId == loginUser.id` |
| 垂直越权 | 低权限访问高权限功能 | 普通用户调管理员接口 | 角色校验（`@AuthCheck`） |

**★ 最常见的漏洞**：只在列表接口过滤了 userId，但 `getById` / `update` / `delete` 没校验。
攻击者只要改一下 id 就能拿到别人的数据 —— 这叫 **IDOR**（Insecure Direct Object Reference）。

**本项目**：`getAppVOById` 在 [AppController.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/AppController.java#L224-L232) **没有做属主校验**（只有 `get/vo` 这一个接口）。这是一个真实的越权点。

---

## G4. SQL 注入

**原理**：用户输入被拼进 SQL 字符串。

```java
// ❌ 危险
"SELECT * FROM user WHERE name = '" + name + "'"
// 输入 ' OR '1'='1 → 查到所有用户
```

**防御**：

| 手段 | 说明 |
|---|---|
| 预编译（PreparedStatement） | `?` 占位，参数与 SQL 分离 |
| ORM 参数绑定 | MyBatis 的 `#{}` 而非 `${}` |
| 白名单 | 排序字段、表名这类**不能参数化**的地方必须白名单 |

**★ 关键认知**：ORM 不是万能的。`orderBy(sortField)` 这种"结构拼接"没法用 `?`，只能白名单。

**本项目**：[ChatHistoryServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ChatHistoryServiceImpl.java#L158-L163) 的 `orderBy(sortField, ...)` 是直传 —— 注入面。

---

## G5. 路径穿越

见 B9。核心是 **canonical path 比对**。

**额外注意**：
- ZIP 解压也要防（Zip Slip：压缩包里的 `../../evil.sh`）
- 上传文件名不能直接用用户提供的名字

---

## G6. XSS / CSRF

**XSS**（跨站脚本）：恶意脚本被存进页面执行。

| 类型 | 场景 |
|---|---|
| 存储型 | 评论、昵称里存 `<script>` |
| 反射型 | URL 参数回显 |
| DOM 型 | 前端 `innerHTML` |

**防御**：输出转义、CSP 头、`HttpOnly` Cookie。

**本项目注意点**：生成的应用是用户自己的 HTML，如果直接托管在同域下，**生成页里的 JS 能访问主站的 Cookie**（除非 Cookie 是 `HttpOnly` + 部署到独立域名）。这是一个真实的安全设计点。

**CSRF**（跨站请求伪造）：诱导用户在自己已登录的站点上发起请求。

**防御**：`SameSite=Lax/Strict`、CSRF Token、校验 `Origin`/`Referer`。

**本项目**：Session Cookie + 跨域 `allowCredentials(true)` → **需要关注 CSRF**。Spring Security 默认开启 CSRF 防护，但项目没用 Security。

---

## G7. 敏感信息脱敏

**三层**：

| 层 | 手段 |
|---|---|
| 数据库 | 密码哈希、手机号加密存储（可选） |
| 接口 | VO 剔除敏感字段 |
| 日志 | 手机号/身份证/密钥打码 |

**★ 最容易漏的是日志**：`log.info("用户登录：{}", user)` 会把密码打进日志文件。

**本项目**：[UserController.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/UserController.java#L112-L115) 的 `getUserById` 返回的是 `User` 实体（**包含密码字段**）。这是真实问题。

---

## G8. 接口防刷的四个层次

| 层次 | 手段 | 强度 |
|---|---|---|
| 1 | 前端限流（按钮禁用） | 最弱（可绕过） |
| 2 | 网关/Nginx 限流 | 中 |
| 3 | 应用层用户级限流 | 中 |
| 4 | 业务校验（验证码、频率、额度） | 最强 |

**★ AI 项目必须有第 4 层**：一次调用可能几毛钱，被刷就是真金白银。
本项目有第 3 层（`@RateLimit` 用户级 5 次/分钟），**没有第 4 层**（没有"每日额度"概念）。

---

## G9. 权限模型（RBAC / ABAC）

| 模型 | 说明 | 本项目 |
|---|---|---|
| ACL | 每个资源一个访问列表 | |
| RBAC | 角色 → 权限，用户 → 角色 | ✅ `user` / `admin` |
| ABAC | 基于属性（时间、IP、部门） | |
| ReBAC | 基于关系（Google Zanzibar） | |

**本项目的 RBAC 是最简版**：只有两个角色，权限判断直接写在切面里。
**企业级做法**：`用户 → 角色 → 权限点`，权限点存在数据库，切面查库判断。

---

# H · 工程化与稳定性

## H1. 异常设计

**受检 vs 非受检**：

| | 受检（`Exception`） | 非受检（`RuntimeException`） |
|---|---|---|
| 编译器强制处理 | ✅ | ❌ |
| 适合 | 可恢复的外部故障（IO） | 编程错误、业务规则 |
| 缺点 | 污染方法签名 | 容易被吞 |

**业务异常必须是 `RuntimeException`**：
① 不污染签名；② 能被全局处理器统一捕获；③ 能被事务回滚。

**★ 异常分层设计**：

```
BusinessException（可预期，有错误码，返回给用户）
  ├── ParamException
  ├── AuthException
  └── NotFoundException

SystemException（不可预期，记日志，返回"系统繁忙"）
```

---

## H2. 日志规范

**级别**：

| 级别 | 用途 |
|---|---|
| ERROR | 需要人介入（DB 挂了、第三方不可用） |
| WARN | 可自愈但异常（限流触发、重试成功） |
| INFO | 关键业务节点（下单、登录） |
| DEBUG | 排查用（入参出参、SQL） |
| TRACE | 极细粒度（一般不开） |

**三个必须**：

1. **链路 ID（traceId）**：MDC 存，跨线程要传递
2. **脱敏**：手机号、密码、token
3. **不打大对象**：`log.info("resp: {}", JSON.toJSONString(hugeResponse))` 会拖垮日志

**本项目缺点**：没有 traceId。AI 调用链路长，出问题很难定位。这是可改进点。

---

## H3. 超时设计（三层超时）

```
客户端超时（前端 fetch / axios）
  > 网关超时（Nginx proxy_read_timeout）
    > 应用超时（AI 调用 timeout）
      > 数据库/Redis 超时
```

**原则**：**外层必须大于内层**，否则外层先超时，内层还在跑，资源浪费。

**本项目**：
- AI 模型：`max-retries: 3`，没有显式 timeout（依赖默认）
- 工作流：`blockLast(Duration.ofMinutes(10))`
- SSE：`new SseEmitter(30 * 60 * 1000L)`
- Selenium：`pageLoadTimeout(30s)`

**★ 注意 10 分钟 vs 30 分钟**：工作流超时 10 分钟，但 SSE 连接保持 30 分钟 → 工作流已经失败，连接还挂着。逻辑上没问题，但用户体验不好。

---

## H4. 重试的正确姿势

**能重试的前提**：**幂等**。

| 场景 | 能重试吗 |
|---|---|
| 查询 | ✅ |
| 幂等写（PUT） | ✅ |
| 非幂等写（POST 创建） | ❌（会产生重复数据） |
| LLM 调用 | ⚠️（能重试但会产生重复计费） |
| 支付 | ❌ |

**退避策略**：

```
固定间隔       → 简单，但会打爆
指数退避       → 1s, 2s, 4s, 8s
指数退避 + 抖动 → 1s±0.3, 2s±0.6（防惊群）
```

**重试三次仍失败 → 必须熔断**，否则会一直打一个已经挂掉的服务。

---

## H5. 熔断 / 降级 / 隔离

| 模式 | 作用 | 类比 |
|---|---|---|
| **熔断** | 连续失败后直接拒绝，不再调用 | 跳闸 |
| **降级** | 返回兜底结果 | 备用方案 |
| **隔离** | 不同业务用不同线程池/连接池 | 船舱隔板 |
| **限流** | 控制入口速率 | 闸门 |

**状态机**：

```
CLOSED（正常）
  → 失败率超阈值
OPEN（直接拒绝）
  → 等待窗口结束
HALF_OPEN（放几个请求试探）
  → 成功 → CLOSED
  → 失败 → OPEN
```

**★ AI 场景的降级**：模型不可用 → 返回"服务繁忙，请稍后重试"；或者切备用模型（DeepSeek → Qwen）。
本项目**没有降级**：模型挂了整个功能不可用。

---

## H6. 异步的四种实现

| 方式 | 适用 | 缺点 |
|---|---|---|
| `Thread` / 虚拟线程 | 简单的一次性任务 | 无管理、无监控 |
| 线程池 | 有并发控制需求 | 参数难调、队列会积压 |
| `@Async` | Spring 集成 | 默认线程池容易打爆 |
| MQ | 削峰、可靠、可重试 | 架构复杂 |

**判断标准**：

```
任务耗时 > 请求可接受时间？
  否 → 同步
  是 → 异步
      ↓
  需要保证不丢吗？
    否 → 线程池 / 虚拟线程
    是 → MQ + 落库
```

**本项目**：`deployApp` 后的截图用虚拟线程（可丢，失败只影响封面）。**这个判断是对的**（截图失败不该导致部署失败）。

---

## H7. 并发控制的四个层次

| 层次 | 手段 |
|---|---|
| 数据库 | 唯一索引、行锁、乐观锁（version）、悲观锁（`FOR UPDATE`） |
| 应用 | `synchronized`、`ReentrantLock`、`Atomic`、`ConcurrentHashMap` |
| 分布式 | Redis 分布式锁、ZooKeeper、数据库锁表 |
| 消息 | 串行消费、分区有序 |

**★ 优先级**：能用数据库约束解决的，不要用应用锁（多实例下失效）。

**典型场景**：防止重复下单 → 唯一索引 `(userId, orderNo)` 比 `synchronized` 可靠一万倍。

---

## H8. 幂等设计的三种实现

见 B5。

**补充：AI 场景的幂等**
同一个 prompt 重复调用会产生不同结果（temperature > 0），**本质不幂等**。所以要靠：

- 前端按钮防抖
- 服务端 `requestId` 去重（Redis SETNX）
- 用户额度限制

---

## H9. 监控三件套

| | Metrics（指标） | Logging（日志） | Tracing（链路） |
|---|---|---|---|
| 回答 | 系统健康吗 | 发生了什么 | 慢在哪一环 |
| 数据 | 数字聚合 | 文本 | 调用链 |
| 工具 | Prometheus | ELK / Loki | SkyWalking / Zipkin |
| 采样 | 全量 | 全量/抽样 | 抽样 |

**本项目**：有 Metrics（Micrometer + Prometheus + `AiModelMetricsCollector`），**没有 Tracing**。

**四个黄金指标**：延迟、流量、错误率、饱和度。

---

## H10. 部署相关

| 概念 | 说明 |
|---|---|
| 蓝绿部署 | 两套环境，切流量 |
| 滚动部署 | 逐个替换实例 |
| 灰度/金丝雀 | 按比例/按用户放量 |
| 回滚 | 保留上一个版本 |

**★ 本项目的"部署"其实是"发布静态文件"**：把 `tmp/code_output/{type}_{appId}` 复制到 `tmp/code_deploy/{deployKey}`。这不是 K8s 部署，是**内容发布**。

**两个必须解决的问题（本项目都没做）**：
1. 部署目录无限增长（无清理策略）
2. 同名 deployKey 冲突（6 位随机字符串，碰撞概率约 1/2^31，虽然低但没校验唯一）

---

# I · 本项目 20 个真实 bug / 瑕疵清单

> 全部有代码依据，可直接用于面试"你觉得这个项目有什么问题"。

| # | 位置 | 问题 | 后果 | 修复方向 |
|---|---|---|---|---|
| 1 | [WebScreenshotUtils.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/utils/WebScreenshotUtils.java#L30-L37) | 静态单例 WebDriver，无锁 | 并发截图互相串页 | `ReentrantLock` 串行化，或 ThreadLocal 独立 driver |
| 2 | [WebScreenshotUtils.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/utils/WebScreenshotUtils.java#L42-L45) | `@PreDestroy` 加在非 Bean 上 | 永远不回调 → Chrome 进程泄漏 | 加 `@Component`，或注册 `Runtime.addShutdownHook` |
| 3 | [AppServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L207-L220) | 虚拟线程 lambda 无 try-catch | 截图失败静默消失 | 包 try-catch + 日志 + 有限重试 |
| 4 | [ChatHistoryServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ChatHistoryServiceImpl.java#L153-L156) | 秒级 `createTime` 单游标 | 同秒记录被跳过 | 复合游标 `(createTime, id)` |
| 5 | [ChatHistoryServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ChatHistoryServiceImpl.java#L158-L159) | `orderBy(sortField)` 直传 | 排序字段注入面 | 白名单 |
| 6 | [CodeFileSaverTemplate.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/CodeFileSaverTemplate.java#L50-L55) | 空白内容静默跳过 | 旧文件残留被引用 | 抛异常或清理目录 |
| 7 | [AppController.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/AppController.java#L224-L232) | `get/vo` 无属主校验 | 水平越权 IDOR | 加校验（按优先级/作者脱敏） |
| 8 | [UserController.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/UserController.java#L112-L115) | 返回 `User` 实体 | 密码字段泄露 | 返回 `UserVO` |
| 9 | [AiCodeGeneratorServiceFactory.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/AiCodeGeneratorServiceFactory.java#L155-L157) | 缓存键带类型，memory id 只带 appId | 两种类型共享一份 Redis 记忆 | memory id 也带类型 |
| 10 | [AppController.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/AppController.java#L266-L272) | `@Cacheable` 无 `@CacheEvict` | 精选列表最长 5 分钟不更新 | 管理端更新时清缓存 |
| 11 | [RedisCacheManagerConfig.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/config/RedisCacheManagerConfig.java#L34-L42) | value 序列化器被注释 | 只能 JDK 序列化，Redis 里不可读 | 改用 JSON + 类型信息 |
| 12 | [RedisCacheManagerConfig.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/config/RedisCacheManagerConfig.java#L36) | `disableCachingNullValues` | 失去防穿透手段 | 视业务决定是否缓存空值 |
| 13 | [AiModelMonitorListener.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/monitor/AiModelMonitorListener.java#L63-L66) | `onError` 用 ThreadLocal 取上下文 | 跨线程时拿到 null → NPE | 和 `onResponse` 一样从 `attributes` 取 |
| 14 | [AiModelMonitorListener.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/monitor/AiModelMonitorListener.java#L34-L36) | `getContext()` 无空判断 | 未设上下文时 NPE | 加 null 兜底 |
| 15 | [CodeGenWorkflow.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/CodeGenWorkflow.java#L169-L214) | 未注册 `onCompletion` | 用户关页后仍跑完整流程 | 注册回调取消任务 |
| 16 | [CodeGeneratorNode.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/langgraph4j/node/CodeGeneratorNode.java) | `Long appId = 0L` 硬编码 | 落盘目录永远是 `html_0` | 从上下文取 appId |
| 17 | [AppServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L154-L158) | `deployKey` 无唯一性校验 | 极小概率冲突 | 生成后查库 + 重试 |
| 18 | [AppServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L142-L198) | 部署目录无清理 | 磁盘无限增长 | 定时清理 / 引用计数 |
| 19 | [AppServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L133-L138) | `createApp` 路由调用无兜底 | 分类模型失败则建应用失败 | try-catch + 默认 HTML |
| 20 | [application.yml](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/resources/application.yml#L12-L14) | 明文 root/123456 | 配置泄露 | 环境变量 / 配置中心 |

**★ 用这 20 条准备的面试话术**：

> "我读过这个项目的源码，发现它有几处可以改进的地方。比如截图工具类用静态 WebDriver 做单例，多用户并发截图时会串页；而且它上面的 `@PreDestroy` 因为类不是 Spring Bean 根本不会生效，会导致 Chrome 进程泄漏。如果要改成生产可用，我会用锁串行化或者每线程独立 driver，并把销毁逻辑注册成 Bean 的生命周期回调。"

**这种回答的价值**：证明你真的读懂了，而不是背了架构图。

---

# J · 面试速答卡（补充 30 问）

| # | 问题 | 一句话答案 |
|---|---|---|
| 1 | IoC 解决了什么 | 把对象创建权交给容器，实现解耦和可替换 |
| 2 | 单例 Bean 线程安全吗 | 不保证，取决于有没有可变实例字段 |
| 3 | prototype 注入到 singleton 会怎样 | 只在注入时创建一次，不会每次换新 |
| 4 | 循环依赖怎么解决 | 三级缓存 + 提前暴露引用；构造器注入解决不了 |
| 5 | AOP 用的什么代理 | Spring Boot 2.x+ 默认 CGLIB（子类） |
| 6 | `@Transactional` 失效场景 | 自调用、非 public、异常被吞、非 RuntimeException |
| 7 | Filter / Interceptor / AOP 区别 | Servlet 层 / MVC 层 / 方法层 |
| 8 | 为什么统一响应体有代价 | HTTP 状态码语义丢失，网关治理失效 |
| 9 | 幂等怎么实现 | 唯一索引 / 状态机 / 一次性 Token |
| 10 | `EventSource` 为什么只能 GET | W3C 规范限制，且不支持自定义请求头 |
| 11 | SSE 为什么要补 done 事件 | 正常关闭时前端收不到任何回调 |
| 12 | 流式被缓冲怎么办 | 关 Nginx `proxy_buffering`，curl 加 `-N` |
| 13 | 为什么用游标分页 | 数据持续增长，OFFSET 慢且会漂移 |
| 14 | 联合索引什么时候失效 | 跳过最左列、函数运算、隐式类型转换 |
| 15 | 回表和覆盖索引 | 二级索引查主键再回聚簇索引；列全在索引里就不回表 |
| 16 | 雪花 ID 为什么转 String | 19 位超过 JS 最大安全整数 2^53-1 |
| 17 | N+1 怎么解决 | 批量查 + Map 组装 |
| 18 | 缓存穿透/击穿/雪崩 | 数据不存在 / 单热点过期 / 一片同时过期 |
| 19 | 为什么先更库再删缓存 | 反过来会有旧值回填窗口 |
| 20 | Redis 分布式锁的坑 | 无过期、误删、业务超时、主从丢锁 |
| 21 | 令牌桶和漏桶区别 | 令牌桶允许突发，漏桶恒定速率 |
| 22 | Flux 和 Mono 区别 | 0~N vs 0~1 |
| 23 | 背压是什么 | 消费者告诉生产者"慢点"，防内存溢出 |
| 24 | 为什么流式不能边收边解析 | 半截数据格式不完整，必然解析失败 |
| 25 | AiServices 怎么实现的 | 运行时动态代理，注解即提示词 |
| 26 | 结构化输出靠什么 | 字段 + `@Description` 转 JSON Schema |
| 27 | 工具调用怎么防幻觉 | `hallucinatedToolNameStrategy` + 调用次数上限 |
| 28 | 为什么输出护轨不能用于流式 | 护轨要完整结果，流式是边产边发 |
| 29 | BCrypt 比 MD5 强在哪 | 自带随机盐 + 可调计算成本 |
| 30 | 水平越权和垂直越权 | 同角色互访 / 低权限越级 |

---

# 附：知识地图（按依赖关系）

```
                     ┌──────────────┐
                     │  Java 基础    │
                     │ 集合/泛型/IO  │
                     └──────┬───────┘
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
       ┌──────────┐  ┌──────────┐  ┌──────────┐
       │  JVM     │  │ 并发编程  │  │ 网络/HTTP │
       │ 内存/GC   │  │线程/锁/池 │  │ TCP/SSE  │
       └────┬─────┘  └────┬─────┘  └────┬─────┘
            └─────────────┼─────────────┘
                          ▼
                  ┌───────────────┐
                  │ Spring 全家桶  │
                  │ IoC/AOP/MVC   │
                  └───────┬───────┘
                          │
        ┌─────────────────┼─────────────────┐
        ▼                 ▼                 ▼
  ┌──────────┐     ┌──────────┐     ┌──────────┐
  │ MySQL    │     │ Redis    │     │ 工程化    │
  │ 索引/事务 │     │ 缓存/锁   │     │ 日志/监控 │
  └────┬─────┘     └────┬─────┘     └────┬─────┘
       └────────────────┼────────────────┘
                        ▼
                ┌───────────────┐
                │ 响应式 / 流式  │
                │ Flux/SSE      │
                └───────┬───────┘
                        ▼
                ┌───────────────┐
                │ AI 应用层      │
                │ LangChain4j   │
                │ 提示词/工具/RAG│
                └───────────────┘
```

**给你的建议**：你现在在「AI 应用层」，但如果底层（MySQL 索引、Redis、Spring AOP）不牢，面试会被追问到答不上来。
**优先补的顺序**：Spring AOP/事务 → MySQL 索引 → Redis 缓存 → 然后再看流式。
