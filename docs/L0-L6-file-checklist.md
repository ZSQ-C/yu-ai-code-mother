# L0 ~ L6 文件清单与重点理解点（上班碎片时间用）

> 这份不是"继续讲原理"，而是一张**开工清单**：
> 每个阶段要建哪些文件、每个文件负责什么、看它的时候要盯哪几个点、哪些必须想明白。
>
> 配套：
> - 原理讲解 → [yu-ai-code-mother-rebuild-guide.md](./yu-ai-code-mother-rebuild-guide.md)
> - 手写方法与节奏 → [handson-coding-practice-plan.md](./handson-coding-practice-plan.md)
> - 知识点横向补充（Spring / HTTP / MyBatis / Redis / 安全 / 工程化） → [backend-knowledge-supplement.md](./backend-knowledge-supplement.md)
> - MySQL 索引与 Redis 缓存专项深挖 → [mysql-index-and-redis-cache-deep-dive.md](./mysql-index-and-redis-cache-deep-dive.md)
>
> **使用建议**：上班时间只做「默想」（看每阶段的 🧠 默想卡，不看完整实现），晚上只做「写」。不要混着来。

---

## 〇、先降一档难度：别一上来就"闭卷"

闭卷写代码是第三档能力，你现在只需要走到第一档：

| 档位 | 标准 | 怎么练 | 建议时机 |
|---|---|---|---|
| ① 认得出 | 看到这个文件，能说出"它负责什么、被谁调用" | 对着清单读源码 | **现在这一档** |
| ② 说得出 | 关掉代码，能口述它的执行步骤和输入输出 | 对着清单讲给自己听 | 读完一个阶段后 |
| ③ 写得出 | 不看源码能从空文件写出来 | 延迟 24 小时重写 | 三个阶段之后 |

**"想清楚为什么这么写"比"背下来怎么写"重要得多**，因为面试问的是前者，而且前者才能迁移到别的项目。

---

## ★ 每阶段都配了「默想卡」：上班看这个，晚上照着写

从 L0 开始，每个阶段后面都有一张 **🧠 默想卡**，固定 6 块内容：

| 块 | 作用 | 怎么用 |
|---|---|---|
| **① 默写顺序** | 先写谁、后写谁、为什么 | 通勤时在脑子里过一遍顺序，能说出来就说明依赖关系懂了 |
| **② 骨架回忆** | 只给字段 / 方法签名 / 类注解，不给方法体 | 闭眼想"我新建这个类，第一行敲什么" |
| **③ 三步自问** | 写之前必须能答上来的问题 | 答不上来就翻源码，别硬写 |
| **④ 高频写错点** | 新手最常踩的 5 个坑 | 写完后拿这个清单自查一遍 |
| **⑤ 可扩展知识点** | 这个文件背后的通用知识 | 想深入时点链接 |
| **⑥ 验收一句话** | 写完怎么证明它对了 | 没跑通不算完成 |

**★ 核心用法**：上班时**不要看完整实现**，只看「默想卡」。
在脑子里模拟"我现在打开 IDE，新建这个文件，第一个词打什么"，卡住了记下来，晚上重点看那一处。

> 为什么不给完整代码：给了你就会"抄"，抄完大脑没有任何痕迹。
> 给骨架不给肉，你晚上要自己长肉，这才是有效练习。

---

## 一、看任何一个文件，固定问四句话

1. **它解决什么问题？**（没有它，谁会报错 / 谁会变慢？）
2. **谁调用它，它调用谁？**（在链路里的位置）
3. **它的输入和输出是什么？**（方法签名就是契约）
4. **如果删掉它，系统会怎样？**（这一问最能区分"必要设计"和"过度设计"）

清单里每个文件后面的「盯住」列，就是按这四问提炼的。

---

## 二、总览：每阶段文件数与优先级

| 阶段 | 文件数 | 核心产出 | 优先级 | 建议投入 |
|---|---|---|---|---|
| L0 骨架 | 12 | 能启动 + 统一响应 | 必做 | 低 |
| L1 用户 | 12 | 注册登录 + 登录态 + 权限切面 | 必做 | 中 |
| L2 应用 | 12 | CRUD + 属主校验 | 必做 | 中 |
| L3 AI 同步 | 13 | 一句话 → 磁盘文件 | **最高** | 高 |
| L4 流式 | 6 | Flux/SSE + 预览 | 高 | 高 |
| L5 历史记忆 | 9 | 二轮对话记得 | 高 | 高 |
| L6 扩展 | 40+ | 部署/下载/限流/监控/Vue/图 | 选做 | 按需 |

**L3 是整个项目的分水岭**。L3 走通 = 你已经"拥有"这个项目；L0~L2 只是普通 CRUD。

---

# L0 · 骨架与外壳（12 个文件）

> 一句话目标：一个空工程能起来，且所有异常都以同一种 JSON 返回。

| # | 文件 | 职责 | 盯住 / 想清楚 |
|---|---|---|---|
| 1 | `pom.xml` | 依赖清单 | 为什么 Java 21？为什么这阶段**不**引 MySQL/Redis？ |
| 2 | `application.yml` | 端口 8123、`context-path: /api`、profile | `context-path` 会影响哪些地方？ |
| 3 | `MyCodeMotherApplication.java` | 启动类 | `@SpringBootApplication` 到底合了哪三个注解？ |
| 4 | `common/BaseResponse.java` | 统一响应体 code/data/message | 为什么三个构造器？为什么实现 `Serializable`？ |
| 5 | `common/ResultUtils.java` | 构造响应的静态方法 | 和直接 `new` 相比，抽出来的价值是什么？ |
| 6 | `exception/ErrorCode.java` | 错误码枚举 | 为什么 `0` 表示成功而不是 `200`？ |
| 7 | `exception/BusinessException.java` | 业务异常 | **为什么继承 `RuntimeException` 而不是 `Exception`？** |
| 8 | `exception/ThrowUtils.java` | `throwIf(条件, 错误码)` | 它替代了什么样的 if 嵌套？ |
| 9 | `exception/GlobalExceptionHandler.java` | `@RestControllerAdvice` 兜底 | 为什么未知异常**不能**把 message 透给前端？ |
| 10 | `config/JsonConfig.java` | Long → String | **不配会怎样？**（JS 的 Number 只有 53 位精度） |
| 11 | `config/CorsConfig.java` | 跨域 | 为什么 `allowedOriginPatterns` 不能换成 `allowedOrigins("*")`？ |
| 12 | `controller/HealthController.java` | 健康检查 | 验收锚点：`GET /api/health/` |

### ★ 本阶段重点理解（只有 3 个）

1. **统一响应体的收益与代价**
   收益：前端一套解析逻辑。
   代价：HTTP 状态码永远是 200，网关/监控/告警按状态码区分就失效了。**这是一个主动取舍，面试常问。**
2. **`@RestControllerAdvice` 的定位**
   它不是"捕获所有异常"，而是"把异常翻译成协议"。业务异常原样返回 code，未知异常必须脱敏。
3. **`context-path` 是全局前缀**
   Controller 里写 `/user`，实际路径是 `/api/user`。所有 curl 命令都要带 `/api`，这是新手最常踩的坑。

### 验收

```powershell
curl http://localhost:8123/api/health/
# {"code":0,"data":"ok","message":"ok"}
```

### 🧠 L0 默想卡

**① 默写顺序（依赖倒序 → 被依赖的先写）**

```
ErrorCode  ──┐
             ├──► BaseResponse ──► ResultUtils
BusinessException ──► ThrowUtils ──► GlobalExceptionHandler
JsonConfig / CorsConfig（独立，随时写）
HealthController（依赖 ResultUtils）
启动类（最后，因为要扫描到上面所有 @Component）
```

**为什么是这个顺序**：如果先写 `ResultUtils`，你会发现它 `new BaseResponse(...)`，而 `BaseResponse` 又需要 `ErrorCode`。
**写代码的顺序 = 依赖的拓扑排序**，这个思维在 L3 更明显。

**② 骨架回忆（只看形状，不看实现）**

```java
// ErrorCode
public enum ErrorCode {
    SUCCESS(0, "ok"), PARAMS_ERROR(40000, "...");
    private final int code;
    private final String message;
    ErrorCode(int code, String message) { ... }
    public int getCode() { ... }
    public String getMessage() { ... }
}

// BaseResponse<T>
@Data
public class BaseResponse<T> implements Serializable {
    private int code;
    private T data;
    private String message;
    public BaseResponse(int code, T data, String message) { ... }
    public BaseResponse(int code, T data) { ... }
    public BaseResponse(ErrorCode errorCode) { ... }
}

// ResultUtils
public class ResultUtils {
    private ResultUtils() {}                          // ★ 私有构造
    public static <T> BaseResponse<T> success(T data) { ... }
    public static <T> BaseResponse<T> error(ErrorCode ec) { ... }
    public static <T> BaseResponse<T> error(int code, String msg) { ... }
}

// BusinessException
@Getter
public class BusinessException extends RuntimeException {
    private final int code;                            // ★ final
    public BusinessException(int code, String message) {
        super(message);                                // ★ 传给父类
        this.code = code;
    }
    public BusinessException(ErrorCode ec) { ... }
    public BusinessException(ErrorCode ec, String msg) { ... }
}

// ThrowUtils
public class ThrowUtils {
    private ThrowUtils() {}
    public static void throwIf(boolean condition, RuntimeException e) { ... }
    public static void throwIf(boolean condition, ErrorCode ec) { ... }
    public static void throwIf(boolean condition, ErrorCode ec, String msg) { ... }
}
```

**③ 三步自问（答不上就别写）**

1. `ErrorCode` 为什么用 `0` 表示成功，而不是 `200`？
   → 业务码和 HTTP 码是两套体系。HTTP 层面永远 200，业务码自己定义，`0` 是 C 语言传统（0 = 无错误）。
2. `BusinessException` 的 `code` 为什么要 `final`？
   → 异常一旦创建，错误码不应该再被改。`final` 让它在编译期锁死，也让它天然线程安全。
3. `GlobalExceptionHandler` 里 `@ExceptionHandler(RuntimeException.class)` 会不会把 `BusinessException` 也捕获走？
   → **不会**。Spring 会选**最精确匹配**的 handler。`BusinessException` 有专门的方法，优先命中它。

**④ 高频写错点（写完逐条自查）**

| # | 错法 | 后果 |
|---|---|---|
| 1 | `@ControllerAdvice` 写成 `@Controller` | 返回的是 HTML 视图名，前端拿到一堆乱码 |
| 2 | `ThrowUtils.throwIf` 条件写反 | 正常情况抛异常、异常情况放行，最阴的 bug |
| 3 | CORS 用 `allowedOrigins("*")` + `allowCredentials(true)` | **启动直接报错**（Spring 明确禁止） |
| 4 | `BaseResponse` 三个构造器写重了（`(int,T)` 和 `(int,T,String)` 参数冲突） | 编译报错，或调用歧义 |
| 5 | 忘了 `context-path: /api` | 所有验收 curl 都 404，你以为代码错了 |

**⑤ 可扩展知识点**

| 想知道什么 | 看哪里 |
|---|---|
| 为什么不直接抛受检异常 | [backend-knowledge-supplement.md](./backend-knowledge-supplement.md) **H1 异常设计** |
| HTTP 状态码语义与"永远 200"的代价 | 同上 **B4** |
| `@RestControllerAdvice` 底层是谁在调 | 同上 **B1 请求路径**、**B3 HttpMessageConverter** |
| `Serializable` 到底有什么用 | 同上 **D9 序列化**（Redis 缓存也要求它） |

**⑥ 验收一句话**：`curl /api/health/` 返回 `{"code":0,...}`，且随便造一个参数错误能返回 `{"code":40000,...}` 而不是 500 页面。

---

## L0 · 具体知识点与代码示例（给"肉"）

> 默想卡给的是骨架，这一节给的是**完整代码 + 逐行注释 + 为什么这么写**。
> **用法**：上班仍然只看默想卡；**晚上写代码时对照这一节**，写完再回来看注释，检查自己漏了哪个理由。

### 0.1 先搞清楚一次请求经过了谁（这决定了后面每个类的位置）

```
curl http://localhost:8123/api/health/
        │
        ▼
① Tomcat           监听 8123，把 HTTP 报文解析成 HttpServletRequest
        │
        ▼
② DispatcherServlet  Spring MVC 的前端控制器（唯一入口）
        │
        ▼
③ HandlerMapping    拿 URI 去匹配  @RequestMapping("/health") + @GetMapping("/")
        │
        ▼
④ HandlerAdapter    反射调用 healthCheck()，顺便把返回值接住
        │
        ▼
⑤ HttpMessageConverter（MappingJackson2HttpMessageConverter）
                    看到 @ResponseBody → 不找视图，直接把对象交给 Jackson
        │
        ▼
⑥ Jackson           反射读 getter → 拼 JSON
        │
        ▼
HTTP/1.1 200 + {"code":0,"data":"ok","message":"ok"}
```

三个必须记住的结论：

| 结论 | 原因 |
|---|---|
| `@RestController` = `@Controller` + `@ResponseBody` | 有了 `@ResponseBody`，返回值才走 ⑤ 而不是视图解析器（否则会去找 `health.html`） |
| HTTP 状态码为什么永远是 200 | 方法正常返回、没抛异常、没加 `@ResponseStatus` → Spring 默认 200 |
| 异常时谁接手 | ④ 抛异常 → DispatcherServlet 进入 `HandlerExceptionResolver` 链 → `ExceptionHandlerExceptionResolver` 去找 `@ExceptionHandler` |

**这一节的第 0 个知识点**：`context-path: /api` 是**在 ③ 之前**由 Tomcat/Servlet 层剥掉的。
所以 Controller 里写 `/health`，浏览器必须访问 `/api/health`，而 `request.getRequestURI()` 拿到的仍然是 `/api/health`（含前缀）。L4 判断 SSE 路径时依赖这一点。

---

### 0.2 工程骨架：pom.xml 与 application.yml

**为什么 L0 只引 4 个依赖**：依赖越少，启动越快、报错面越小。每加一个依赖，出问题时就多一个怀疑对象。
MySQL / Redis 放到 L1、L2 再加，**加到哪一步就在哪一步排查**。

```xml
<!-- pom.xml：L0 最小依赖 -->
<properties>
    <java.version>21</java.version>
</properties>

<dependencies>
    <!-- Web：内嵌 Tomcat + Spring MVC + Jackson + 参数校验的基础 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- AOP：L1 的 @AuthCheck 切面要用，L0 放着不影响启动 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-aop</artifactId>
    </dependency>

    <!-- 省掉 getter/setter/构造器，编译期生成，不进 jar -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <version>1.18.36</version>
        <optional>true</optional>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

**为什么 Java 21**（三个都不是"新"，而是这个项目真的用到了）：

1. **虚拟线程**（L4）：`Thread.ofVirtual()` 处理 SSE，一个请求占一个线程也不怕。
2. **switch 表达式 + 模式匹配**（L3/L4）：`AiCodeGeneratorFacade` 用 `switch (codeGenType)` 分派，`default` 必须抛异常，这是编译期强制的穷尽检查。
3. **LTS**：Spring Boot 3.x 最低 17，21 是当前主流 LTS。

`lombok` 必须同时配 `annotationProcessorPaths`（原项目 [pom.xml](file:///e:/AI/GitHub/yu-ai-code-mother/pom.xml#L166-L180) 里那段），否则 IDEA 里能跑、`mvn package` 报"找不到 getter"。

```yaml
# application.yml：L0 只需要这三段
spring:
  application:
    name: my-code-mother

server:
  port: 8123              # 为什么不是 8080？避免和你本机其它项目抢端口
  servlet:
    context-path: /api    # ★ 全局前缀，所有 URL 都要带 /api
```

**为什么不用 `application-local.yml`**：原项目用它放 DB 密码 / API Key（[application.yml](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/resources/application.yml#L23-L24) 里 `profiles.active: local`）。
L0 没有密钥，写 profile 只会多一个"文件没加载对"的坑。**等到 L2 接 MySQL 时再拆 profile。**

---

### 0.3 `ErrorCode`：枚举不是"常量集合"

**知识点：枚举编译后就是一个 `final class`，每个枚举项是它的 `public static final` 实例。**
所以 `ErrorCode.SUCCESS` 是一个**对象**，天然线程安全（静态 final），也天然单例。

**错误码分区间设计**（不是随便写的数字，是照着 HTTP 语义映射的）：

| 区间 | 含义 | 本项目取值 | 对应 HTTP |
|---|---|---|---|
| `0` | 成功 | `SUCCESS(0)` | 200 |
| `4xxxx` | 客户端错误（你传错了） | `40000` 参数、`40100` 未登录、`40101` 无权限、`40300` 禁止访问、`40400` 不存在、`42900` 限流 | 400 / 401 / 403 / 404 / 429 |
| `5xxxx` | 服务端错误（我写错了） | `50000` 系统异常、`50001` 操作失败 | 500 |

```java
@Getter
public enum ErrorCode {

    SUCCESS(0, "ok"),
    PARAMS_ERROR(40000, "请求参数错误"),
    NOT_LOGIN_ERROR(40100, "未登录"),
    NO_AUTH_ERROR(40101, "无权限"),
    TOO_MANY_REQUEST(42900, "请求过于频繁"),
    NOT_FOUND_ERROR(40400, "请求数据不存在"),
    FORBIDDEN_ERROR(40300, "禁止访问"),
    SYSTEM_ERROR(50000, "系统内部异常"),
    OPERATION_ERROR(50001, "操作失败");

    private final int code;       // final：枚举项是共享实例，字段必须不可变
    private final String message;

    ErrorCode(int code, String message) {   // 构造器默认 private，写 public 编译报错
        this.code = code;
        this.message = message;
    }
    // getCode() / getMessage() 由 @Getter 生成
}
```

**易错点**：

- 枚举构造器**只能**是 `private`（不写也行），写 `public` 直接编译错误。
- 不要在枚举里放 `setter` 或非 final 字段——所有地方共享同一个实例，改一个等于改全局。
- `message` 是**给用户看的兜底文案**，不是日志文案。想带动态信息（"用户 123 不存在"）用 `BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户 123 不存在")`，不要拼进枚举。

---

### 0.4 `BaseResponse<T>`：泛型 + 三个构造器

**知识点：`T` 只在编译期存在，运行期被擦除。**
所以 `BaseResponse<User>` 和 `BaseResponse<App>` 在 JVM 里是**同一个类**，Jackson 序列化时也不知道 `T` 是什么——它只按实际对象的 getter 输出。这就是为什么 controller 返回 `BaseResponse<UserVO>` 能正确输出 `UserVO` 的字段。

```java
@Data
public class BaseResponse<T> implements Serializable {

    private int code;        // 业务码，不是 HTTP 码
    private T data;          // 成功时的载荷
    private String message;  // 提示文案

    // ① 全参：需要自定义 message 时用
    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    // ② 两参：借用 ①，message 给空串（构造器链式调用，减少重复）
    public BaseResponse(int code, T data) {
        this(code, data, "");
    }

    // ③ 只传错误码：直接从枚举里取 code 和 message，避免漏写
    public BaseResponse(ErrorCode errorCode) {
        this(errorCode.getCode(), null, errorCode.getMessage());
    }
}
```

**三个点必须想清楚**：

1. **为什么是三个构造器而不是一个**：调用方信息量不同。`success` 有三个都不知道（走 ①②），`error` 只给枚举（走 ③），需要精确文案时给 `(code, msg)`（内部走 ①）。
2. **`implements Serializable` 在 L0 其实没被用到**。它是给 L1/L2 的 Redis 缓存、L4 的 SSE 对象序列化预留的。**面试可以答"为跨进程传输/缓存做准备"**，但别答"响应返回给前端需要序列化"——Jackson 转 JSON 用的是 getter，跟 `Serializable` 无关。
3. **真实瑕疵**：这个类**没有无参构造器**。
   - 序列化（对象 → JSON）不需要无参构造器，所以返回给前端没问题。
   - 反序列化（JSON → 对象）需要无参构造器或 `@JsonCreator`。如果你以后拿它接 Redis 缓存 / Feign 响应，会抛 `InvalidDefinitionException: Cannot construct instance of BaseResponse`。
   - 修法：显式加 `@NoArgsConstructor`。注意 `@Data` 自带的构造器生成规则是「**只要你手写了任意构造器，它就不再生成**」，所以不标注解永远不会有无参构造器。

---

### 0.5 `ResultUtils`：为什么值得抽出来

**知识点：工具类用 `static` 方法 + 私有构造器，是为了"不需要实例就能用，且不允许被实例化"。**

```java
public class ResultUtils {

    private ResultUtils() {}   // ★ 最佳实践：禁止 new。原项目没写，你写的时候补上

    /** 成功：code 固定 0 */
    public static <T> BaseResponse<T> success(T data) {
        // 注意：静态方法上的 <T> 叫"泛型方法"，与类上的 <T> 无关
        return new BaseResponse<>(0, data, "ok");
    }

    /** 失败：直接用枚举 */
    public static BaseResponse<?> error(ErrorCode errorCode) {
        return new BaseResponse<>(errorCode);
    }

    /** 失败：自定义文案 */
    public static BaseResponse<?> error(ErrorCode errorCode, String message) {
        return new BaseResponse<>(errorCode.getCode(), null, message);
    }

    /** 失败：裸 code（极少用，一般是第三方错误码透传） */
    public static BaseResponse<?> error(int code, String message) {
        return new BaseResponse<>(code, null, message);
    }
}
```

**为什么 `error` 返回 `BaseResponse<?>` 而不是 `<T>`**：
失败时 `data` 是 `null`，没有具体类型可言；用通配符避免调用方写出 `BaseResponse<String> r = ResultUtils.error(...)` 这种"假装有 String 数据"的代码。

**为什么抽这个类**：`new BaseResponse<>(0, data, "ok")` 这行在项目里会写几百次。
抽出来以后，**"成功码是 0"这个知识只存在于一个地方**，哪天改成 200 只改一行。这是"消除重复知识"而不是"消除重复代码"——后者才是抽工具类的正当理由。

---

### 0.6 `BusinessException`：为什么必须继承 `RuntimeException`

```java
@Getter
public class BusinessException extends RuntimeException {

    private final int code;   // ★ final：异常一旦创建，错误码不许再改

    public BusinessException(int code, String message) {
        super(message);       // ★ 交给父类保存，getMessage() 才能拿到
        this.code = code;
    }

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);       // ★ 用自定义文案覆盖枚举的默认文案
        this.code = errorCode.getCode();
    }
}
```

**继承 `RuntimeException` 而不是 `Exception`，四个硬理由**：

| # | 理由 | 具体后果 |
|---|---|---|
| 1 | **不污染方法签名** | 受检异常要 `throws` 一路声明到 Controller；接口方法一改，所有实现和调用方都要改 |
| 2 | **能穿过 lambda / Stream** | 受检异常在 `forEach`、`map` 里无法抛出，只能包一层 try-catch |
| 3 | **`@Transactional` 默认只对 `RuntimeException` 回滚** | 抛受检异常，事务**会提交**——这是最经典的线上事故 |
| 4 | **和兜底 handler 对齐** | `GlobalExceptionHandler` 的兜底是 `RuntimeException`，受检异常会漏到 Spring 默认的 500 页面 |

**`super(message)` 不能省**：`getMessage()` 是 `Throwable` 的方法，读的是父类内部字段，不是你的字段。
省了它，日志里只剩 `com.xxx.BusinessException` 一行，**看不到任何原因**。

**`@Getter` 只生成 `getCode()`**：`getMessage()` 来自父类，不要自己再定义一个 `message` 字段——会遮蔽父类的，日志打印时又是空的。

---

### 0.7 `ThrowUtils`：把 if 嵌套压成卫语句

先看**不抽**的样子（真实项目里到处都是这种）：

```java
// ❌ 三层嵌套：正常逻辑被埋在缩进里
if (app != null) {
    if (app.getUserId().equals(loginUser.getId())) {
        if (app.getStatus() == 1) {
            doSomething(app);
        } else {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用状态异常");
        }
    } else {
        throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限");
    }
} else {
    throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
}
```

再看"卫语句"版本，以及把它抽成工具类之后的版本：

```java
// ✅ 卫语句：先挡掉所有异常，最后是正常路径
ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()), ErrorCode.NO_AUTH_ERROR);
ThrowUtils.throwIf(app.getStatus() != 1, ErrorCode.OPERATION_ERROR, "应用状态异常");
doSomething(app);
```

```java
public class ThrowUtils {

    private ThrowUtils() {}   // 同 ResultUtils，原项目没写，建议补

    /** ① 兜底重载：能抛任意运行时异常（比如已经 new 好的） */
    public static void throwIf(boolean condition, RuntimeException runtimeException) {
        if (condition) {
            throw runtimeException;
        }
    }

    /** ② 只给错误码，文案用枚举默认值 */
    public static void throwIf(boolean condition, ErrorCode errorCode) {
        throwIf(condition, new BusinessException(errorCode));
    }

    /** ③ 错误码 + 自定义文案 */
    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        throwIf(condition, new BusinessException(errorCode, message));
    }
}
```

**注意三个细节**：

1. **方法名是 `throwIf`，语义是"条件成立才抛"**。写反了不会编译报错，只会"正常情况抛异常、异常情况放行"——这种 bug 极难发现。**每写一处，默念一遍"条件成立 → 抛"**。
2. **`throwIf(cond, null)` 会有重载歧义**（`RuntimeException` 和 `ErrorCode` 都能匹配），必须强转：`throwIf(cond, (ErrorCode) null)`。所以别传 null。
3. ① 重载用的是**已经 new 好的异常对象**——`throwIf(true, new BusinessException(...))` 会把同一个对象抛出去。异常对象带堆栈，**不能跨请求复用**（复用会污染堆栈），所以这个重载只适合"只抛一次"的场景，别缓存异常实例。

---

### 0.8 `GlobalExceptionHandler`：它不是"捕获"，是"翻译协议"

**核心知识点：Spring 用 `ExceptionHandlerExceptionResolver` 处理 Controller 抛出的异常。匹配规则不是"第一个匹配"，而是"继承距离最近"。**

所以：

- `BusinessException` 同时满足 `BusinessException.class` 和 `RuntimeException.class` 两个 handler；
- `ExceptionDepthComparator` 算出它到 `BusinessException` 的距离是 0，到 `RuntimeException` 的距离是 1；
- **选距离 0 的**。这就是"精确匹配优先"的真实机制。

```java
@Hidden                    // 让 knife4j / springdoc 不要在接口文档里展示这个"假 Controller"
@RestControllerAdvice      // = @ControllerAdvice + @ResponseBody
@Slf4j
public class GlobalExceptionHandler {

    /** 业务异常：code 原样返回，属于"预期内"的失败 */
    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        // ★ 真实瑕疵：预期内的业务异常用 error 级别 + 打全堆栈，日志噪音很大
        // 更合适：log.warn("业务异常 code={}, msg={}", e.getCode(), e.getMessage());
        log.error("BusinessException", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /** 未知异常：必须脱敏 */
    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);   // 完整堆栈只给运维看
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");  // ★ 前端只看到这句话
    }
}
```

**为什么未知异常不能把 message 透给前端**：
`e.getMessage()` 里经常带 SQL、文件路径、内网 IP、甚至密钥片段（比如 `Connection refused: 10.0.3.7:6379`）。**这是信息泄露，不是"用户体验问题"。**

**为什么还要一个 `RuntimeException` 兜底**：
`BusinessException` 只覆盖"我主动抛的"。空指针、数组越界、SQL 异常都是 `RuntimeException` 的子类，没有兜底就会走到 Spring 默认的 `/error` 页面，返回一段 HTML 或另一套 JSON 结构，前端解析直接崩。

**L0 阶段不要写 SSE 分支**：原项目的 [GlobalExceptionHandler.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/exception/GlobalExceptionHandler.java#L53-L94) 里有 `handleSseError`，那是 L4 才需要的东西。现在写了你也验证不了，只会增加 L0 的失败面。

---

### 0.9 `JsonConfig`：Long → String（不配必踩）

**知识点：JavaScript 的 `Number` 是 IEEE 754 双精度，安全整数上限是 `2^53 - 1 = 9007199254740991`（16 位）。**

```
9007199254740993  →  JS 解析后变成 9007199254740992   （末位被"吃掉"）
```

本项目的 ID 是**雪花算法**生成的 19 位 Long（[App.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/model/entity/App.java#L37-L38)），必然溢出。
后果不是"前端显示错"，而是**前端拿这个 ID 去查详情/删除，查的是另一个 ID**。

```java
@JsonComponent   // ★ 注意：它自带 @Component 语义，所以这个类会被扫描成 Bean
public class JsonConfig {

    @Bean
    public ObjectMapper jacksonObjectMapper(Jackson2ObjectMapperBuilder builder) {
        // 用 Spring Boot 给的 builder，而不是 new ObjectMapper()
        // 原因：builder 里已经预置了 JavaTimeModule 等默认配置，自己 new 会丢掉
        ObjectMapper objectMapper = builder.createXmlMapper(false).build();

        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);   // 包装类型 Long
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);    // 基本类型 long
        objectMapper.registerModule(module);

        return objectMapper;
    }
}
```

**两个必须知道的点**：

1. **为什么要注册两个**：`Long.class` 管 `Long id`，`Long.TYPE` 管 `long id`（基本类型反射出来是 `long.class`）。少一个，另一半漏网。
2. **`@JsonComponent` 的副作用**：`@JsonComponent` 是 `@Component` 的派生注解，所以这个类**不是** `@Configuration`。在非 `@Configuration` 类里写 `@Bean`，Spring 走的是 **lite 模式**——Bean 会注册，但 `@Bean` 方法之间互相调用不会被代理，也不会走 CGLIB。这里只有一个 `@Bean` 方法，所以没事；如果你以后加第二个 `@Bean` 并在里面调用第一个，**会 new 出两个实例**。

**代价（面试常问）**：全局把 Long 变成字符串，`count`、`total` 这类数字也会变字符串，前端做算术要先 `Number()`。
所以企业里更精细的做法是 **只给 ID 字段加 `@JsonSerialize(using = ToStringSerializer.class)`**，而不是全局改。

---

### 0.10 `CorsConfig`：`allowedOrigins("*")` + Cookie = 启动直接炸

**知识点：跨域不是"服务端拒绝"，而是浏览器拦截响应。**
服务端能做的是返回 `Access-Control-Allow-Origin` 等响应头，告诉浏览器"这个来源我允许"。

带 Cookie 的跨域还多两条规则：

1. 响应头必须是**具体来源**，不能是 `*`（因为 `*` 和 `Access-Control-Allow-Credentials: true` 互斥）；
2. 前端 `fetch` 必须写 `credentials: 'include'`。

```java
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")              // 对哪些路径生效
                .allowCredentials(true)         // 允许带 Cookie（L1 的 JSESSIONID 要用）
                // ★ 必须用 patterns：它允许"回显具体来源"，而不是写死 *
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*");
    }
}
```

**为什么不能换成 `allowedOrigins("*")`**：
Spring 在 `CorsConfiguration.validateAllowCredentials()` 里直接抛异常，大意是：
`When allowCredentials is true, allowedOrigins cannot contain the special value "*" ... consider using "allowedOriginPatterns" instead.`
**表现是启动失败，不是跨域失败**——新手会以为是依赖冲突。

**`OPTIONS` 为什么必须放行**：带自定义头（如 `Content-Type: application/json`）的请求，浏览器会先发一个 `OPTIONS` 预检。不放行 → 预检 403 → 真正的 POST 根本没发出去。

**生产环境**：`allowedOriginPatterns("*")` 等于允许任何网站带着用户 Cookie 调你的接口（CSRF 风险），上线必须换成具体域名。

---

### 0.11 `HealthController`：验收锚点 + 尾斜杠坑

```java
@RestController
@RequestMapping("/health")
public class HealthController {

    // ★ 同时写 "" 和 "/"，避免不同 Spring 版本尾斜杠匹配策略不同导致 404
    @GetMapping({"", "/"})
    public BaseResponse<String> healthCheck() {
        return ResultUtils.success("ok");
    }
}
```

**知识点：为什么要有健康检查接口**：
K8s / Nginx / 负载均衡都靠它判断"这个实例能不能接流量"。它必须**不依赖任何外部系统**（不查库、不查 Redis），否则数据库抖动会导致所有实例被摘除，引发雪崩。

**尾斜杠坑**：`@GetMapping("/")` 在部分版本下只能匹配 `/api/health/`，访问 `/api/health` 会 404。
写 `{"", "/"}` 两个映射就没这个歧义。**这是 L0 最没技术含量但最容易卡住人的坑**——遇到 404，先确认 URL 和斜杠，再怀疑代码。

---

### 0.12 启动类：`@SpringBootApplication` 到底合了什么

```java
@SpringBootApplication
public class MyCodeMotherApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyCodeMotherApplication.class, args);
    }
}
```

它 = 三个注解：

| 注解 | 作用 |
|---|---|
| `@SpringBootConfiguration` | 本质是 `@Configuration`，标记这是配置类（**只能有一个**） |
| `@EnableAutoConfiguration` | 触发自动装配：读 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，按 `@ConditionalOnClass` / `@ConditionalOnMissingBean` 决定装不装 |
| `@ComponentScan` | 扫描**当前类所在包及其子包**，把 `@Component` / `@Service` / `@RestController` / `@Configuration` 注册成 Bean |

**两个推论**：

1. **启动类必须放在最外层包**（`com.yupi.yuaicodemother`），否则 `controller`、`service` 扫不到，表现为"接口全部 404，但启动成功"。
2. **`@ConditionalOnMissingBean` 给了你覆盖自动配置的机会**：你自己声明了 `ObjectMapper`，Spring Boot 默认的那个就不创建了。这就是 0.9 能生效的原因。

**L0 的启动类不需要这些**（等用到再加）：

```java
// L2 才需要：扫描 Mapper 接口
@MapperScan("com.yupi.yuaicodemother.mapper")
// L2/L5 才需要：开启 @Cacheable / @CacheEvict
@EnableCaching
// 原项目还有一个 exclude，是为了排除没配置的 Redis 向量存储自动装配
// @SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
```

---

### 0.13 验收：6 条命令，缺一不算过

临时在 `HealthController` 里加两个"故意出错"的接口（验完删掉）：

```java
@GetMapping("/boom")
public BaseResponse<String> boom() {
    ThrowUtils.throwIf(true, ErrorCode.PARAMS_ERROR);   // 验业务异常
    return ResultUtils.success("不会执行到这里");
}

@GetMapping("/crash")
public BaseResponse<String> crash() {
    throw new RuntimeException("数据库连接失败: 10.0.3.7:3306");  // 验未知异常脱敏
}
```

```powershell
# 1. 健康检查：必须 200 + code=0
curl.exe http://localhost:8123/api/health/
# {"code":0,"data":"ok","message":"ok"}

# 2. 不带 /api：必须 404（证明 context-path 生效）
curl.exe http://localhost:8123/health/

# 3. 请求不存在的方法：404（说明不是你代码的问题，是路由没匹配上）
curl.exe http://localhost:8123/api/health/nothing

# 4. 业务异常：code=40000，HTTP 仍是 200
curl.exe http://localhost:8123/api/health/boom
# {"code":40000,"data":null,"message":"请求参数错误"}

# 5. 未知异常：code=50000，message 必须被替换成"系统错误"
curl.exe http://localhost:8123/api/health/crash
# {"code":50000,"data":null,"message":"系统错误"}   ← 不应该出现 10.0.3.7

# 6. 跨域预检：必须返回 Access-Control-Allow-* 头
curl.exe -i -X OPTIONS http://localhost:8123/api/health/ `
  -H "Origin: http://localhost:3000" `
  -H "Access-Control-Request-Method: POST"
```

**第 5 条是这一阶段最有价值的验证**：如果你在那里看到了 `10.0.3.7`，说明你的脱敏根本没生效——这是面试可以直接讲的真实案例。

---

### 0.14 现象 → 原因对照表（L0 排错用）

| 现象 | 最可能的原因 |
|---|---|
| 启动报 `Failed to configure a DataSource` | 引了 mybatis/mysql 依赖但没配 `spring.datasource`（L0 不该引） |
| 启动报 `Port 8123 was already in use` | 端口被占；`netstat -ano \| findstr 8123` 找 PID |
| 启动成功但所有接口 404 | 启动类不在最外层包 / 忘了 `/api` / 尾斜杠 |
| 返回一大段 HTML 错误页 | `@RestControllerAdvice` 写成了 `@ControllerAdvice`（少了 `@ResponseBody`） |
| 启动失败，日志有 `allowedOrigins cannot contain "*"` | CORS 用了 `allowedOrigins("*")` + `allowCredentials(true)` |
| 业务异常被当成 500 处理 | handler 参数写成了 `Exception` 但没匹配上，或异常没继承 `RuntimeException` |
| 前端 ID 末位不对 | 没配 `Long → String` |
| 日志里 `BusinessException` 没有 message | 构造器里漏了 `super(message)` |
| `mvn package` 报找不到 getter | Lombok 没配 `annotationProcessorPaths` |
| 中文变成问号 | 响应没设 UTF-8（Boot 默认 UTF-8，通常是终端编码问题） |

---

### 0.15 这一阶段的知识点，对应到通用技能

| L0 里做的事 | 背后的通用知识 | 面试怎么问 |
|---|---|---|
| `@SpringBootApplication` | 自动装配原理、条件注解、Bean 覆盖顺序 | "Spring Boot 是怎么做到引入 starter 就能用的？" |
| `@RestController` + `@ResponseBody` | DispatcherServlet 九大组件、HttpMessageConverter | "一个请求从进 Tomcat 到返回 JSON 经历了什么？" |
| `@RestControllerAdvice` | `HandlerExceptionResolver`、异常匹配距离 | "全局异常处理是怎么找到对应方法的？" |
| 统一响应体 | API 契约设计、HTTP 语义与业务码的取舍 | "为什么不用 HTTP 状态码表达业务错误？" |
| `BusinessException` | 受检 / 非受检异常、`@Transactional` 回滚规则 | "为什么自定义异常一般继承 RuntimeException？" |
| `JsonConfig` | IEEE 754、雪花 ID、全局序列化器的代价 | "前端传来的 ID 精度丢失怎么解决？" |
| `CorsConfig` | 同源策略、预检请求、CSRF | "跨域是谁拦的？带 Cookie 有什么限制？" |
| `Serializable` | Java 序列化、`serialVersionUID` | "为什么实体类要 implements Serializable？" |

---


# L1 · 用户体系（12 个文件）

> 一句话目标：能注册、能登录，之后任何接口都能拿到"当前登录用户"。

| # | 文件 | 职责 | 盯住 / 想清楚 |
|---|---|---|---|
| 1 | `model/entity/User.java` | 用户表映射 | `@Table`、逻辑删除字段 `isDelete` 怎么配 |
| 2 | `mapper/UserMapper.java` | 数据访问 | 为什么是空接口？`BaseMapper` 提供了什么 |
| 3 | `model/enums/UserRoleEnum.java` | 角色枚举 | 为什么要 `getEnumByValue` 而不是 `valueOf` |
| 4 | `model/dto/user/UserRegisterRequest.java` | 注册入参 | DTO 为什么不直接复用 Entity（防越权赋值） |
| 5 | `model/dto/user/UserLoginRequest.java` | 登录入参 | |
| 6 | `model/vo/LoginUserVO.java` | 脱敏出参 | **为什么不能直接返回 User？** |
| 7 | `model/vo/UserVO.java` | 用户信息出参 | 与 `LoginUserVO` 的边界 |
| 8 | `constant/UserConstant.java` | `USER_LOGIN_STATE` | 常量类为什么私有构造 |
| 9 | `service/UserService.java` | 接口 | 接口先行的好处 |
| 10 | `service/impl/UserServiceImpl.java` | 注册 / 登录 / `getLoginUser` / 登出 | **注册四步、登录五步；加盐摘要** |
| 11 | `controller/UserController.java` | `/register` `/login` `/get/login` `/logout` | 为什么登录要传 `HttpServletRequest` |
| 12 | `aop/AuthInterceptor.java` + `annotation/AuthCheck.java` | 角色权限切面 | `@Around("@annotation(authCheck)")` 怎么拿到注解 |

> 额外改配置：`pom.xml` 加 `spring-session-data-redis`、`application.yml` 加 `spring.session.store-type: redis`。

### ★ 本阶段重点理解（5 个）

1. **登录态存在哪**
   `request.getSession().setAttribute(USER_LOGIN_STATE, user)` → Session 存 Redis，浏览器只拿一个 `JSESSIONID` Cookie。
   **追问**：为什么不用 JWT？→ Session 能主动失效（改密码/封号立即生效），JWT 做撤销很麻烦。
2. **`getLoginUser` 是全项目调用最多的方法**
   原项目每次都 `getById` 再查一次库。你要想清楚：这一步是为了"数据新鲜"还是"历史包袱"？能不能只查必要字段 / 加本地缓存？
3. **密码不能裸 MD5**
   必须加盐。想清楚：固定盐有什么用、为什么企业用 BCrypt（每次盐不同）。
4. **脱敏的边界**
   `password`、`salt` 永远不能出接口。哪怕前端不看，也会被抓包。
5. **AOP 切面的价值**
   权限校验如果在每个 Controller 里写 if，一定会漏。注解 + 切面 = 声明式，漏不掉。

### 验收

```powershell
curl -c cookie.txt -X POST http://localhost:8123/api/user/register `
  -H "Content-Type: application/json" `
  -d '{\"userAccount\":\"test1\",\"userPassword\":\"12345678\",\"checkPassword\":\"12345678\"}'

curl -c cookie.txt -X POST http://localhost:8123/api/user/login `
  -H "Content-Type: application/json" `
  -d '{\"userAccount\":\"test1\",\"userPassword\":\"12345678\"}'

curl -b cookie.txt http://localhost:8123/api/user/get/login
# data.userAccount = test1，且没有 password 字段
```

### 🧠 L1 默想卡

**① 默写顺序**

```
UserConstant ──► UserRoleEnum ──► User（Entity）──► UserMapper
                                        │
                    ┌───────────────────┼───────────────────┐
                    ▼                   ▼                   ▼
              DTO(注册/登录)         VO(登录/普通)      UserService 接口
                                                              │
                                                              ▼
                                                    UserServiceImpl
                                                              │
                                                              ▼
                                                     UserController
                                                              │
                              AuthCheck(注解) ──► AuthInterceptor ──► WebMvcConfig 注册
```

**为什么 DTO/VO 必须早于 Service**：Service 的方法签名要引用它们。**签名先定，实现后填** —— 这是接口先行思维。

**② 骨架回忆**

```java
// User（Entity）—— 注意逻辑删除字段和表名
@Table("user")
public class User {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private String userAccount;
    private String userPassword;
    private String salt;              // ★ 加盐
    private String userName;
    private String userAvatar;
    private String userRole;          // user / admin
    @Column(isLogicDelete = true)
    private Integer isDelete;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

// 注册 DTO 与登录 DTO 分开 —— 字段不同
public class UserRegisterRequest {
    private String userAccount;
    private String userPassword;
    private String checkPassword;     // ★ 只有注册有
}

// 登录 VO —— 出参必须脱敏
@Data
public class LoginUserVO {
    private Long id;
    private String userAccount;
    private String userName;
    private String userAvatar;
    private String userRole;
    // ★ 没有 password，没有 salt
}

// Service 接口：先定签名
public interface UserService extends IService<User> {
    long userRegister(String userAccount, String userPassword, String checkPassword);
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);
    User getLoginUser(HttpServletRequest request);
    boolean userLogout(HttpServletRequest request);
}
```

**③ 三步自问**

1. **注册流程有几步？**
   → 4 步：参数校验（含两次密码一致）→ 账号不重复 + 长度 → **加盐摘要** → 插入。
2. **登录流程有几步？**
   → 5 步：参数校验 → 按账号查库 → **用同一个盐再算一次摘要比对** → 转成 `LoginUserVO` → 写 Session。
3. **为什么要把"用户不存在"和"密码错误"返回同一句提示？**
   → 防**账号枚举**。如果提示不同，攻击者就能试出哪些账号存在。

**④ 高频写错点**

| # | 错法 | 后果 |
|---|---|---|
| 1 | 摘要只算一次 MD5、不加盐 | 彩虹表可秒破 |
| 2 | Session key 前后用了两个不同的字符串 | 登录成功但取不到用户，最难查 |
| 3 | 拦截器里 `throw new RuntimeException` | 前端拿到 500 页面而不是统一 JSON |
| 4 | `BeanUtil.copyProperties(user, vo)` 连 password 一起拷 | 密码泄露（**必须用 VO 白名单字段**） |
| 5 | 拦截器只写了 `addInterceptor` 忘了 `addPathPatterns("/**")` | 等于没生效 |
| 6 | Session 里存了完整 `User`（含密码） | 每个请求都带着密码在 Redis 里躺着 |

**⑤ 可扩展知识点**

| 想知道什么 | 看哪里 |
|---|---|
| Session 为什么能共享、和 JWT 怎么选 | [backend-knowledge-supplement.md](./backend-knowledge-supplement.md) **D2**、**G2** |
| 密码存储演进（为什么企业用 BCrypt） | 同上 **G1** |
| Spring AOP 为什么能拿到注解、自调用为什么失效 | 同上 **A5** |
| Filter / Interceptor / AOP 该用哪个 | 同上 **B2** |
| 用户不存在时的枚举风险 | 同上 **G7 敏感信息** |

**⑥ 验收一句话**：注册 → 登录 → 带 cookie 调 `/get/login` 能拿到用户，且响应里**绝对没有** `password` / `salt` 字段。

---

# L2 · App CRUD（12 个文件）

> 一句话目标：用户只能操作自己的应用，越权必被拦。

| # | 文件 | 职责 | 盯住 / 想清楚 |
|---|---|---|---|
| 1 | `model/entity/App.java` | 应用表映射 | `codeGenType` / `deployKey` / `priority` 各自什么时候写 |
| 2 | `mapper/AppMapper.java` | 数据访问 | |
| 3 | `model/enums/CodeGenTypeEnum.java` | `HTML` / `MULTI_FILE` / `VUE_PROJECT` | 为什么用枚举而不是字符串常量 |
| 4 | `model/dto/app/AppAddRequest.java` | 建应用入参 | 为什么只有 `initPrompt` |
| 5 | `model/dto/app/AppUpdateRequest.java` | 改名入参 | 为什么**只允许**改名字 |
| 6 | `model/dto/app/AppQueryRequest.java` | 查询条件 | 继承 `PageRequest` |
| 7 | `common/PageRequest.java` | 分页基类 | `pageNum/pageSize` 上限校验 |
| 8 | `common/DeleteRequest.java` | 通用删除入参 | |
| 9 | `model/vo/AppVO.java` | 应用出参 + 作者信息 | 为什么要 VO 而不是 Entity |
| 10 | `service/AppService.java` | 业务接口 | `getQueryWrapper` 为什么放在 Service |
| 11 | `service/impl/AppServiceImpl.java` | 建 / 查 / 改 / 删 + 属主校验 | **属主校验四处写法为什么不一样** |
| 12 | `controller/AppController.java` | `/add` `/update` `/delete` `/get/vo` `/my/list/page/vo` | Controller 应该有多薄 |

### ★ 本阶段重点理解（5 个）

1. **属主校验是"每个按 id 操作的接口"的责任**
   不是只在列表接口过滤 userId 就安全了。`update` / `delete` / `deploy` / `download` 都必须单独校验。
   **追问**：为什么删除允许管理员、修改不允许？→ 管理动作（删违规）和用户动作（改内容）风险不同。
2. **`getQueryWrapper` 的两个坑**
   ① `orderBy(sortField)` 直接拼用户输入 → 注入面，要白名单。
   ② `eq("appId", null)` 会生成 `appId = null`，条件必须 `if != null` 才拼。
3. **逻辑删除**
   `isDelete` 字段 + MyBatis-Flex 的 `@Column(isLogicDelete = true)`，删除变成 update，查询自动过滤。
4. **VO 与 N+1**
   列表要展示作者名 → 先批量查 user 再拼，**不要在循环里查库**。
5. **分页上限**
   `pageSize > 20` 直接拒绝。不限制的话，一个 `pageSize=100000` 就能把服务打挂。

### 验收

```powershell
# 用户 A 建应用
curl -b cookieA.txt -X POST http://localhost:8123/api/app/add `
  -H "Content-Type: application/json" -d '{\"initPrompt\":\"做一个个人博客首页\"}'

# 用户 B 改 A 的应用 → 期望 code=40101
curl -b cookieB.txt -X POST http://localhost:8123/api/app/update `
  -H "Content-Type: application/json" -d '{\"id\":1,\"appName\":\"hacked\"}'
```

### 🧠 L2 默想卡

**① 默写顺序**

```
CodeGenTypeEnum ──► App（Entity）──► AppMapper
                                        │
             PageRequest / DeleteRequest ─┤
             DTO(add/update/query) ───────┤
             AppVO ───────────────────────┘
                                        │
                                        ▼
                              AppService（接口）
                                        │
                                        ▼
                              AppServiceImpl
                                  ├─ getQueryWrapper
                                  ├─ addApp
                                  ├─ getAppVOById
                                  ├─ updateApp
                                  ├─ deleteApp
                                  └─ listAppVOByPage
                                        │
                                        ▼
                                 AppController
```

**② 骨架回忆**

```java
// 枚举：为什么用枚举不用字符串常量
@Getter
public enum CodeGenTypeEnum {
    HTML("html", "原生 HTML"),
    MULTI_FILE("multi_file", "多文件"),
    VUE_PROJECT("vue_project", "Vue 工程");
    private final String value;
    private final String text;
    // ★ 必须有 getEnumByValue，因为 valueOf 只能按常量名找
    public static CodeGenTypeEnum getEnumByValue(String value) { ... }
}

// VO：Entity 不直接出接口
@Data
public class AppVO {
    private Long id;
    private String appName;
    private String cover;
    private String initPrompt;
    private String codeGenType;
    private String deployKey;
    private Integer priority;
    private LocalDateTime createTime;
    // ★ 关联字段
    private UserVO user;
}

// Service 接口：五个方法
public interface AppService extends IService<App> {
    Long createApp(AppAddRequest req, User loginUser);
    AppVO getAppVOById(Long id);
    void updateApp(AppUpdateRequest req, User loginUser);
    void deleteApp(Long id, User loginUser);
    Page<AppVO> listAppVOByPage(AppQueryRequest req, User loginUser);
    QueryWrapper getQueryWrapper(AppQueryRequest req);
}
```

**③ 三步自问**

1. **属主校验应该写在 Controller 还是 Service？**
   → **Service**。因为"改应用"这个动作可能被 HTTP 入口、定时任务、MQ 消费者调用。校验写在 Controller，换个入口就漏。
2. **`getQueryWrapper` 里有哪两类条件必须判空？**
   → ① 所有 `eq` / `like` 的字段值；② 排序字段的白名单校验。前者防 `= null` 查不到，后者防注入。
3. **为什么 `add` 只接收 `initPrompt`？**
   → 其余字段（`userId`、`codeGenType`、`createTime`）都由服务端决定。**用户能传的字段越少，越权面越小**（防"自己指定 userId"）。

**④ 高频写错点**

| # | 错法 | 后果 |
|---|---|---|
| 1 | 属主校验只写 `update`，漏了 `delete` / `deploy` | 水平越权（IDOR） |
| 2 | `queryWrapper.eq("appId", appId)` 不判空 | SQL 变成 `appId = null`，永远查不到 |
| 3 | `orderBy(sortField, true)` 直传用户输入 | 排序字段注入 |
| 4 | 逻辑删除字段没加 `@Column(isLogicDelete = true)` | 删了还能查出来 |
| 5 | 列表组装作者信息写在 `for` 循环里 | N+1 查询，20 条列表 21 次 SQL |
| 6 | 分页不设 `pageSize` 上限 | 一个 `pageSize=100000` 打挂服务 |
| 7 | 返回 `Page<App>` 而不是 `Page<AppVO>` | 敏感字段/冗余字段全暴露 |

**⑤ 可扩展知识点**

| 想知道什么 | 看哪里 |
|---|---|
| `QueryWrapper` 的两个陷阱、逻辑删除的三个副作用 | [backend-knowledge-supplement.md](./backend-knowledge-supplement.md) **C2~C4** |
| N+1 怎么识别和修复 | 同上 **C10** |
| 水平越权 vs 垂直越权 | 同上 **G3** |
| 排序字段注入为什么 ORM 防不住 | 同上 **G4** |
| 分页上限与业务限制 | [mysql-index-and-redis-cache-deep-dive.md](./mysql-index-and-redis-cache-deep-dive.md) **11.4** |

**⑥ 验收一句话**：用户 B 改用户 A 的应用，返回 `40101` 而不是"修改成功"。

---

# L3 · AI 最小闭环（13 个文件）★ 最重要的一关

> 一句话目标：给一句话，磁盘上真的出现一个能打开的 `index.html`。

**先画这张图再动手**：

```
Controller → AppService → AiCodeGeneratorFacade → Factory → AiCodeGeneratorService(接口)
                                                                    │ LangChain4j 生成代理
                                                                    ▼
                                                          返回 markdown 字符串
                                                                    │
                                              CodeParserExecutor → HtmlCodeParser(正则)
                                                                    ▼
                                                         HtmlCodeResult(对象)
                                                                    │
                                              CodeFileSaverExecutor → Template(模板方法)
                                                                    ▼
                                                       tmp/code_output/html_{appId}/index.html
```

| # | 文件 | 职责 | 盯住 / 想清楚 |
|---|---|---|---|
| 1 | `ai/model/HtmlCodeResult.java` | 结构化输出载体 | **`@Description` 是给模型的契约**，改字段名必须同步改它 |
| 2 | `ai/AiCodeGeneratorService.java` | AI 服务**接口** | **没有实现类，是谁实现了它？** |
| 3 | `resources/prompt/codegen-html-system-prompt.txt` | 系统提示词 | 提示词要求的输出格式 = 解析器的正则 |
| 4 | `core/parser/CodeParser.java` | 解析接口 `T parseCode(String)` | 泛型接口的价值 |
| 5 | `core/parser/HtmlCodeParser.java` | 正则提取 ` ```html ` 块 | 匹配失败时的兜底策略 |
| 6 | `core/parser/CodeParserExecutor.java` | 按类型分派 | `switch` + 静态单例 |
| 7 | `core/saver/CodeFileSaverTemplate.java` | **模板方法** `final saveCode()` | 为什么骨架 `final`、变体 `abstract` |
| 8 | `core/saver/HtmlCodeFileSaverTemplate.java` | 只写 `saveFiles` | 子类薄到什么程度 |
| 9 | `core/saver/CodeFileSaverExecutor.java` | 按类型分派 | 和 Parser 执行器同一个套路 |
| 10 | `ai/AiCodeGeneratorServiceFactory.java` | 造 AI 服务实例 | 第一轮先不缓存，直接 build |
| 11 | `core/AiCodeGeneratorFacade.java` | 门面：生成 → 解析 → 落盘 | 为什么要有门面层 |
| 12 | `constant/AppConstant.java` | 落盘根目录 + 目录名方法 | **目录契约 `{codeType}_{appId}`** |
| 13 | `AppServiceImpl` 加一个同步方法 + Controller 临时接口 | 串起来 | 先把链路打通，L4 再换流式 |

### ★ 本阶段重点理解（6 个，全是面试高频）

1. **AI Service 是"只有接口"的**
   `AiServices.builder(XxxService.class).build()` 在**运行时生成动态代理**。方法签名 = 调用方式，`@SystemMessage` = 提示词，`@UserMessage` = 入参模板。
   **所以它不能被 `@Resource` 注入**，必须走 Factory。
2. **结构化输出的契约是三重绑定的**
   `HtmlCodeResult` 的字段 + `@Description` + 提示词里的输出格式 + 解析器正则。
   **改任意一个，另外三个都可能失效。**
3. **模板方法为什么必须是 `final`**
   骨架一旦能被覆盖，"先校验再落盘"这个顺序就可能被某个人写反。`final` 是防呆，不是防别人，是防未来的自己。
4. **目录契约是整个项目的"绳子"**
   生成、预览、部署、下载、截图五处都靠 `{codeType}_{appId}` 反推目录。原项目硬编码了 4 处，你在 `AppConstant` 里抽一个方法就赢了。
5. **"流完全结束后才解析落盘"是通用原则**
   L3 是同步的还看不出来，到 L4 就明白了：边收边解析 = 拿半截 HTML 去正则 = 必错。
6. **门面层（Facade）的职责**
   它不写业务规则，只负责"编排"：拿服务 → 生成 → 解析 → 落盘。这类"顺序固定、步骤多"的地方，用门面比在 Service 里堆代码清晰。

### 验收

```powershell
curl -b cookie.txt "http://localhost:8123/api/app/gen?appId=1&message=做一个简单的个人主页"

dir tmp\code_output\html_1
# index.html 存在、能双击打开、内容里没有 html 代码围栏
```

### 🧠 L3 默想卡（★ 全项目最重要，值得多看两遍）

**① 默写顺序（严格按依赖拓扑）**

```
HtmlCodeResult ──┐
                 ├──► AiCodeGeneratorService（接口）
                 │
CodeParser<T> ──► HtmlCodeParser ──► CodeParserExecutor
                 │
CodeFileSaverTemplate ──► HtmlCodeFileSaverTemplate ──► CodeFileSaverExecutor
                 │
AppConstant ─────┤
                 ▼
      AiCodeGeneratorServiceFactory ──► AiCodeGeneratorFacade
                                              │
                                              ▼
                                    AppServiceImpl + Controller
```

**为什么 `HtmlCodeResult` 必须第一个写**：
它被 Parser（返回值）、Saver（入参）、AI Service（返回值）三方引用。**它是整条链路的"货币"**，货币面额没定，其他都没法写。

**为什么 `Template` 必须早于 `Executor`**：Executor 里要 `new HtmlCodeFileSaverTemplate()`，依赖方向决定顺序。

**② 骨架回忆（只背形状）**

```java
// 1. 结构化输出载体 —— 字段名就是给模型的契约
public class HtmlCodeResult {
    @Description("完整的 HTML 代码，包含所有样式和脚本")
    private String htmlCode;
}

// 2. AI 服务接口 —— 注意：没有实现类！
public interface AiCodeGeneratorService {
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    HtmlCodeResult generateHtmlCode(@UserMessage String userMessage);
}

// 3. 解析器泛型接口
public interface CodeParser<T> {
    T parseCode(String codeContent);
}

// 4. 模板方法 —— 骨架 final，变体 abstract
public abstract class CodeFileSaverTemplate<T> {
    public final File saveCode(T result, Long appId) {
        validateInput(result);
        String baseDirPath = buildUniqueDir(appId);
        saveFiles(result, baseDirPath);
        return new File(baseDirPath);
    }
    protected abstract void saveFiles(T result, String baseDirPath);
    protected abstract String getCodeType();
}

// 5. 执行器 —— 静态单例 + switch
public class CodeFileSaverExecutor {
    private static final HtmlCodeFileSaverTemplate HTML_SAVER = new HtmlCodeFileSaverTemplate();
    public static File executeSaver(Object result, CodeGenTypeEnum type, Long appId) {
        return switch (type) {
            case HTML -> HTML_SAVER.saveCode((HtmlCodeResult) result, appId);
            default -> throw new BusinessException(...);   // ★ 必须显式抛
        };
    }
}

// 6. 门面 —— 只编排，不写业务规则
public File generateAndSaveCode(String msg, CodeGenTypeEnum type, Long appId) {
    // ① 拿服务  ② 生成  ③ 解析  ④ 落盘
}
```

**③ 三步自问（这一关的核心）**

1. **AI 接口没有实现类，为什么还能调用？**
   → `AiServices.builder(XxxService.class).build()` 用**运行时动态代理**生成实现。方法注解 = 提示词，返回类型 = 反序列化目标。
   **推论**：它不能被 `@Resource` 注入（容器里没有这个 Bean），必须走 Factory。
2. **改 `HtmlCodeResult` 的字段名会发生什么？**
   → 四重契约一起崩：`@Description` 没改 → 模型不知道输出什么；提示词没改 → 模型给的格式不对；解析器没改 → 正则匹配不到。**这是本项目最脆弱的地方。**
3. **`saveCode` 为什么必须是 `final`？**
   → 骨架是"校验 → 建目录 → 写文件 → 返回"。任何一步顺序被改，都可能"先写后校验"或"目录建了文件没写"。`final` 锁死顺序，子类只能填 `saveFiles` 和 `getCodeType`。

**④ 高频写错点**

| # | 错法 | 后果 |
|---|---|---|
| 1 | 正则没转义三反引号，或没处理 `\r\n` | 永远匹配不到，落盘失败 |
| 2 | 写文件用平台默认编码 | 中文乱码（Windows 默认 GBK） |
| 3 | 目录用 `UUID` / 雪花 ID | 预览、部署、下载全都找不到 |
| 4 | `Executor` 的 `switch` 漏 `default` | 新增类型时静默返回 null |
| 5 | `Factory` 里 `@Resource` 注入 `AiCodeGeneratorService` | **启动直接失败**（无实现类） |
| 6 | 解析失败返回 `null` 后直接落盘 | NPE，且错误信息不可读 |
| 7 | 正则贪婪匹配（`.*` 而不是 `.*?`） | 把后面的内容也吃进来 |
| 8 | 门面里塞了权限校验/写库 | 职责混乱，换入口就漏 |

**⑤ 可扩展知识点（这一关最值得深挖）**

| 想知道什么 | 看哪里 |
|---|---|
| 动态代理到底做了什么（7 步） | [backend-knowledge-supplement.md](./backend-knowledge-supplement.md) **F1** |
| 结构化输出的 JSON Schema 机制 | 同上 **F3** |
| 模板方法的通用价值 | 同上 **A3**（单例/多例）也是同一类"骨架 vs 变体"思维 |
| 为什么无状态类可以安全用静态单例 | [concurrency-and-jvm-memory-notes.md](./concurrency-and-jvm-memory-notes.md) 第八、九部分 |
| 目录契约为什么能串起五条链路 | 本文 **三、第 8 条** |
| 提示词工程的 6 个技巧 | 同上 **F6** |

**⑥ 验收一句话**：给一句话，`tmp/code_output/html_{appId}/index.html` 出现在磁盘上，双击能打开，内容里**没有** markdown 围栏。

---

# L4 · 流式输出 + 预览（6 个文件）

> 一句话目标：浏览器能看到代码一个字一个字蹦出来，生成完能直接预览。

| # | 文件 | 职责 | 盯住 / 想清楚 |
|---|---|---|---|
| 1 | `config/StreamingChatModelConfig.java` | 流式模型 Bean | **为什么 `@Scope("prototype")`** |
| 2 | `ai/AiCodeGeneratorService.java` | 加 `Flux<String> generateHtmlCodeStream()` | 流式方法和同步方法的关系 |
| 3 | `core/AiCodeGeneratorFacade.java` | 加 `generateAndSaveCodeStream` | **流结束才 parse+save** |
| 4 | `core/handler/StreamHandlerExecutor.java` | 按类型选处理器 | 为什么 VUE 和别人不一样 |
| 5 | `core/handler/SimpleTextStreamHandler.java` | 攒 + 落库 | `doOnNext` 攒、`doOnComplete` 写库 |
| 6 | `controller/AppController.java` + `StaticResourceController.java` | SSE 接口 + 静态资源 | 为什么 SSE 必须 GET；为什么要补 `done` 事件 |

### ★ 本阶段重点理解（5 个）

1. **`prototype` 的原因**
   `StreamingChatModel` 内部持有当前流的可变状态。单例共享 → 两个用户的 token 串在一起。这不是"性能优化"，是**正确性问题**。
2. **三层数据形态的转换**
   LangChain4j `TokenStream` → Reactor `Flux<String>` → SSE `ServerSentEvent`。
   每转一层都会丢信息，所以原项目 VUE 类型直接处理 `TokenStream`（要保留工具调用事件）。
3. **为什么 SSE 用 GET**
   浏览器原生 `EventSource` 只支持 GET。想 POST 就得前端 `fetch` + `ReadableStream` 自己解析。
4. **为什么要显式发 `done` 事件**
   SSE 正常关闭时前端收不到任何回调。必须服务端说一句"完了"，前端才知道去加载预览。
5. **静态资源接口必须做路径校验**
   `/{deployKey}/../..` 这种请求如果不拦，等于把整个磁盘暴露出去。

### 验收

```powershell
curl -N -b cookie.txt "http://localhost:8123/api/app/chat/gen/code?appId=1&message=做一个计时器"
# 逐段输出，最后有 event: done

# 浏览器打开
http://localhost:8123/api/static/1/index.html
```

### 🧠 L4 默想卡

**① 默写顺序**

```
StreamingChatModelConfig（Bean 先就位）
        │
        ▼
Service 接口加流式方法 ──► Factory 里把 streamingModel 传进 builder
        │
        ▼
Facade 加 generateAndSaveCodeStream
        │
        ▼
StreamHandlerExecutor ──► SimpleTextStreamHandler
        │
        ▼
Controller SSE 接口 + StaticResourceController
```

**② 骨架回忆**

```java
// 模型 Bean —— 注意 prototype
@Bean
@Scope("prototype")
public StreamingChatModel streamingChatModel() { ... }

// 接口方法：返回 Flux
Flux<String> generateHtmlCodeStream(String userMessage);

// 门面：攒完再解析落盘
public Flux<String> generateAndSaveCodeStream(String msg, CodeGenTypeEnum type, Long appId) {
    Flux<String> stream = ...;
    StringBuilder sb = new StringBuilder();
    return stream
            .doOnNext(sb::append)          // ★ 只攒，不解析
            .doOnComplete(() -> {          // ★ 流结束才解析+落盘
                HtmlCodeResult r = parse(sb.toString());
                save(r, appId);
            });
}

// 控制器：SSE
@GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> chat(...) {
    return flux.map(chunk -> ServerSentEvent.builder(...).data(...).build())
               .concatWith(Mono.just(ServerSentEvent.builder().event("done").build()));
}
```

**③ 三步自问**

1. **`StreamingChatModel` 为什么必须 `prototype`？**
   → 它内部持有"当前流的状态"。单例共享 = 两个用户的 token 交错。**这不是性能问题，是正确性问题。**
2. **为什么不能在 `doOnNext` 里解析？**
   → AI 是逐 token 返回的，`doOnNext` 拿到的永远是**半截字符串**。正则要求闭合的代码围栏，半截必然匹配失败。
3. **SSE 结束时为什么要补 `done` 事件？**
   → 连接正常关闭时前端**收不到任何回调**（`onerror` 也不触发）。必须服务端主动说"完了"，前端才知道去加载预览。

**④ 高频写错点**

| # | 错法 | 后果 |
|---|---|---|
| 1 | 控制器返回 `String` 而不是 `Flux` | 变成一次性返回，看起来"流式失效" |
| 2 | 忘了 `produces = TEXT_EVENT_STREAM_VALUE` | 浏览器不按 SSE 解析 |
| 3 | 在 `doOnNext` 里解析 | 半截数据解析失败 |
| 4 | 忘了 `done` 事件 | 前端永远等不到结束，预览不加载 |
| 5 | 静态资源接口不校验路径 | 路径穿越，整个磁盘暴露 |
| 6 | 模型 Bean 写成单例 | 多用户 token 串流 |
| 7 | 前端 `EventSource` 不 `close()` | 自动重连，重复调用 LLM 扣钱 |

**⑤ 可扩展知识点**

| 想知道什么 | 看哪里 |
|---|---|
| SSE 报文格式、`EventSource` 三个限制 | [backend-knowledge-supplement.md](./backend-knowledge-supplement.md) **B7** |
| 流式被 Nginx 缓冲的 6 层排查 | 同上 **B8** |
| 冷流/热流：前端重连会重复扣费 | 同上 **E4** |
| `doFinally` vs `doOnComplete` 的区别 | 同上 **E5** |
| `SseEmitter` 生命周期三个坑 | 同上 **E6** |
| 虚拟线程为什么适合长连接 | 同上 **E7** |

**⑥ 验收一句话**：`curl -N` 能看到逐段输出、最后有 `event: done`，浏览器打开静态地址能看到页面。

---

# L5 · 对话历史与记忆（9 个文件）

> 一句话目标：同一个应用聊第二轮时，模型"记得"第一轮。

| # | 文件 | 职责 | 盯住 / 想清楚 |
|---|---|---|---|
| 1 | `model/entity/ChatHistory.java` | 消息表映射 | 为什么存 `messageType` |
| 2 | `model/enums/ChatHistoryMessageTypeEnum.java` | `user` / `ai` | 为什么要区分类型 |
| 3 | `mapper/ChatHistoryMapper.java` | 数据访问 | |
| 4 | `model/dto/chathistory/ChatHistoryQueryRequest.java` | 游标查询入参 | `lastCreateTime` 从哪来 |
| 5 | `service/ChatHistoryService.java` | 接口 | |
| 6 | `service/impl/ChatHistoryServiceImpl.java` | 写消息 / 回填记忆 / 游标分页 | **三件事的顺序** |
| 7 | `config/RedisChatMemoryStoreConfig.java` | 记忆持久化 | 为什么记忆要存 Redis |
| 8 | `ai/AiCodeGeneratorServiceFactory.java`（改造） | Caffeine 缓存 + 创建时回填 | **缓存键为什么带类型** |
| 9 | `controller/ChatHistoryController.java` | 历史查询 | 查询也要校验属主 |

### ★ 本阶段重点理解（6 个）

1. **用户消息必须"先落库、再调 AI"**
   反了的话，AI 调用失败 → 用户消息丢失 → 对话断层、无法追溯。
2. **AI 消息挂在"流完成"回调**
   `doOnNext` 每 token 写一次库 = 几万次写入。必须攒完整条再写一次。
3. **记忆的三层状态**

   | 层 | 位置 | 生命周期 | 作用 |
   |---|---|---|---|
   | `chat_history` 表 | MySQL | 永久 | 事实来源，用户可查 |
   | `MessageWindowChatMemory` | JVM 堆 | 缓存实例存活期 | 给模型看最近 N 条 |
   | `RedisChatMemoryStore` | Redis | 按配置 | 实例被淘汰后能恢复 |

4. **窗口大小 = 回填条数**
   `maxMessages(20)` 必须和 `loadChatHistoryToMemory(..., 20)` 一致。不一致就是"白查一半"或"永远只记得 10 条"。
5. **Caffeine 缓存是性能优化，不是功能必需**
   第一轮可以先每请求 new 一个，功能是对的。想清楚"缓存省掉的到底是什么开销"再改成缓存。
6. **游标分页的两个理由**
   ① 深翻页 OFFSET 要扫并丢弃前面所有行；② 头部持续插入时 OFFSET 会重复读/漏读。
   **坑**：`createTime` 是秒级，同秒多条会被 `lt` 跳过 → 需要 `(createTime, id)` 复合游标。

### 验收

```powershell
# 第一轮：立一个事实
curl -N -b cookie.txt ".../chat/gen/code?appId=1&message=做一个页面，标题叫「小明的博客」"
# 第二轮：不提标题
curl -N -b cookie.txt ".../chat/gen/code?appId=1&message=把标题改成红色"
# 期望：输出里标题仍是「小明的博客」
```

### 🧠 L5 默想卡

**① 默写顺序**

```
ChatHistoryMessageTypeEnum ──► ChatHistory ──► ChatHistoryMapper
                                                    │
                                ChatHistoryQueryRequest
                                                    │
                                                    ▼
                                        ChatHistoryService（接口）
                                                    │
                    ┌───────────────────────────────┤
                    ▼                               ▼
        addChatMessage（写库）            loadChatHistoryToMemory（回填）
                    │                               │
                    └───────────────┬───────────────┘
                                    ▼
                        ChatHistoryServiceImpl
                                    │
                    RedisChatMemoryStoreConfig ──► Factory 改造
                                    │
                                    ▼
                          ChatHistoryController + AppController 改造
```

**② 骨架回忆**

```java
// 消息类型：必须区分 user / ai
@Getter
public enum ChatHistoryMessageTypeEnum {
    USER("user"), AI("ai");
    private final String value;
}

// 实体：注意 messageType 和 createTime
@Table("chat_history")
public class ChatHistory {
    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long appId;
    private Long userId;
    private String message;
    private String messageType;
    private LocalDateTime createTime;
    @Column(isLogicDelete = true)
    private Integer isDelete;
}

// Service 三个核心方法
public interface ChatHistoryService extends IService<ChatHistory> {
    boolean addChatMessage(Long appId, String message, String messageType, Long userId);
    boolean loadChatHistoryToMemory(Long appId, ChatMemory memory, int maxCount);
    Page<ChatHistory> listAppChatHistoryByPage(Long appId, int pageSize, LocalDateTime lastCreateTime, User loginUser);
}

// 记忆构建：注意 id 和 maxMessages
MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
        .id(appId)                       // ★ 和缓存键要能对应
        .chatMemoryStore(redisStore)
        .maxMessages(20)                 // ★ 必须等于回填条数
        .build();
chatHistoryService.loadChatHistoryToMemory(appId, memory, 20);
```

**③ 三步自问**

1. **为什么用户消息必须"先落库、再调 AI"？**
   → 反过来的话，AI 调用失败 → 用户消息没保存 → 对话断层，用户刷新后看不到自己说过的话。
2. **AI 消息应该在哪写、写几次？**
   → 在**流完成回调**里写，**整条只写一次**。如果在 `doOnNext` 里写，一条 5000 token 的回答就是 5000 次 insert。
3. **回填条数为什么必须等于 `maxMessages`？**
   → 回填 20 条但窗口只留 10 条 = 白查 10 条；回填 10 条但窗口 20 条 = 永远只记得一半。**两个数字是同一个语义。**

**④ 高频写错点**

| # | 错法 | 后果 |
|---|---|---|
| 1 | AI 消息在 `doOnNext` 里写库 | 几万次 INSERT，DB 直接跪 |
| 2 | 回填条数和 `maxMessages` 不一致 | 白查库或记忆残缺 |
| 3 | 游标只用 `createTime`（秒级） | 同秒多条被 `lt` 整体跳过 |
| 4 | 回填时把错误消息/系统消息也加载 | 污染 AI 上下文，模型开始"胡言乱语" |
| 5 | 缓存键带 `type`，但 `memory.id` 不带 | 两种类型共享一份 Redis 记忆，互相覆盖 |
| 6 | 历史查询接口不校验属主 | 能看别人的对话记录 |
| 7 | `@Cacheable` 没配 `@CacheEvict` | 更新后缓存不失效 |

**⑤ 可扩展知识点**

| 想知道什么 | 看哪里 |
|---|---|
| 游标分页的完整索引设计与 EXPLAIN 验证 | [mysql-index-and-redis-cache-deep-dive.md](./mysql-index-and-redis-cache-deep-dive.md) **12** |
| 复合游标 `(createTime, id)` 怎么改 | 同上 **12.4** |
| 记忆三层状态（DB / JVM / Redis） | [backend-knowledge-supplement.md](./backend-knowledge-supplement.md) **F2** |
| Session 存 Redis 的机制 | 同上 **D2** |
| Caffeine 是"缓存对象"不是"缓存数据" | 同上 **D3**、[深挖文档](./mysql-index-and-redis-cache-deep-dive.md) **27.2** |
| 缓存一致性（改历史后要不要清缓存） | 深挖文档 **20** |

**⑥ 验收一句话**：第一轮立了个标题，第二轮不提标题，模型仍然沿用第一轮的标题。

---

# L6 · 扩展（按需挂，每个独立验收）

> 原则：**一次只挂一个**，挂完验收再挂下一个。同时挂三个 = 出问题不知道是谁的。

## 6.1 多文件生成（3 个文件）★ 推荐先做

| 文件 | 职责 | 盯住 |
|---|---|---|
| `ai/model/MultiFileCodeResult.java` | html/css/js 三个字段 | 结构化输出三字段 |
| `core/parser/MultiFileCodeParser.java` | 三条正则 | 强绑定提示词 |
| `core/saver/MultiFileCodeFileSaverTemplate.java` | 写三个文件 | **只强制要求 index.html**，css/js 可空 |

> ★ 理解点：**这是模板方法的"扩展性考试"**——加一种类型，只新增 3 个文件，`Executor` 和 `Facade` 只加一个 case，调用方零改动。

## 6.2 一键部署 + 封面（5 个文件）

| 文件 | 职责 | 盯住 |
|---|---|---|
| `AppServiceImpl.deployApp` | 生成 deployKey + 复制目录 + 更新库 | 部署本质 = 复制目录 |
| `service/ScreenshotService.java` + `impl` | 截图并上传 | 失败不能影响部署 |
| `utils/WebScreenshotUtils.java` | Selenium 截图 | **已知两个 bug**：静态 WebDriver 无锁、`@PreDestroy` 不生效 |
| `manager/CosManager.java` | 对象存储 | 本地可用 tmp 替代 |
| `config/CosClientConfig.java` | COS 客户端 | |

> ★ 理解点：**异步任务的异常必须自己接住**。`Thread.startVirtualThread(() -> {...})` 里不写 try-catch，异常会直接消失，用户以为成功了。

## 6.3 代码下载（2 个文件）

`service/ProjectDownloadService.java` + `service/ProjectDownloadServiceImpl.java`
> ★ 盯住：ZIP 流式写、`Content-Disposition` 文件名编码、必须过滤 `node_modules`/`dist`。

## 6.4 分布式限流（4 个文件）★ 推荐做

`ratelimter/annotation/RateLimit.java`、`enums/RateLimitType.java`、`aspect/RateLimitAspect.java`、`config/RedissonConfig.java`
> ★ 理解点：**本地限流在多实例下等于没限**；`trySetRate` 要幂等；限流器 key 必须设过期时间，否则 Redis 无限增长。

## 6.5 生成类型路由（4 个文件）

`ai/AiCodeGenTypeRoutingService.java`、`AiCodeGenTypeRoutingServiceFactory.java`、`config/RoutingAiModelConfig.java`、`resources/prompt/codegen-routing-system-prompt.txt`
> ★ 理解点：这是一个"低成本模型做分类"的典型用法——用 qwen-turbo 这种便宜模型，`max-tokens: 100` 就够。

## 6.6 缓存 / 监控（6 个文件）

`config/RedisCacheManagerConfig.java`、`utils/CacheKeyUtils.java`、`monitor/MonitorContextHolder.java`、`MonitorContext.java`、`AiModelMetricsCollector.java`、`AiModelMonitorListener.java`
> ★ 理解点：① `@Cacheable` 的 key 用 SpEL + `condition` 限定只缓存前 10 页；② 监控上下文用 `ThreadLocal`，但**监听器回调可能跨线程**，所以还要往 `RequestAttributes` 里塞一份。

## 6.7 Vue 工程（17 个文件，第二轮再做）

`ai/tools/*`（8 个）、`ai/model/message/*`（5 个）、`core/handler/JsonMessageStreamHandler.java`、`core/builder/VueProjectBuilder.java`、`config/ReasoningStreamingChatModelConfig.java`
> ★ 理解点：① `@Tool` + `@P` 让模型能读写文件；② `TokenStream` 要保留工具调用事件，所以不能降级成纯文本 Flux；③ 工具名幻觉用 `hallucinatedToolNameStrategy` 兜住；④ 构建 npm 要限时 + 检查产物存在性。

## 6.8 LangGraph4j（建议最后或跳过）

`langgraph4j/*` 整套
> ★ 结论：不参与主链路，是技术试验田。学它的价值是"图编排"这个通用思路（条件边 + 自环重试 + 子图复用），不是复现代码。

### 🧠 L6 默想卡（每个子模块只有 3 个核心问题）

L6 不建议按顺序全做，按"这个子模块在训练什么能力"来挑。

| 子模块 | 写之前先默想这 3 个问题 | 训练的能力 |
|---|---|---|
| **6.1 多文件生成** | ① 加一种类型要动几个文件？② 为什么只强制要求 `index.html`？③ CSS 缺失时该报错还是跳过？ | **模板方法的扩展性**（这是 L3 的考试） |
| **6.2 部署 + 封面** | ① 部署本质是什么操作？② 截图失败该不该影响部署？③ 虚拟线程里的异常谁来接？ | **异步任务的失败隔离** |
| **6.3 代码下载** | ① ZIP 几万文件怎么不 OOM？② 文件名含中文怎么写响应头？③ 为什么要过滤 `node_modules`？ | **流式 IO 与响应头编码** |
| **6.4 分布式限流** | ① 本地计数器在多实例下为什么等于没限？② `trySetRate` 为什么要幂等？③ 限流 key 不设过期会怎样？ | **共享状态外置** |
| **6.5 类型路由** | ① 为什么分类用便宜模型 + `max-tokens: 100`？② 分类失败怎么兜底？③ 分类准确率怎么验证？ | **按任务难度分配模型** |
| **6.6 缓存 + 监控** | ① 缓存 key 为什么用 SpEL？② `condition` 限定前 10 页的作用？③ 监听器跨线程为什么 ThreadLocal 会丢？ | **缓存治理与可观测性** |
| **6.7 Vue 工程** | ① 为什么不能让模型直接写文件？② 工具名幻觉怎么兜？③ 构建产物不存在为什么必须显式报错？ | **工具调用与执行验证** |
| **6.8 LangGraph4j** | ① 条件边怎么实现"重试"？② 子图当节点用解决了什么？③ `recursionLimit` 不设会怎样？ | **图编排思维**（不要求复现） |

**★ L6 的统一默想方式**：
不要问"这个功能怎么写"，问 **"如果我不做这个，系统会在什么场景下出问题"**。
答不上来说明它是"可有可无的功能"；答得上说明它是"必要设计"。这就是四问法里的第 4 问。

**L6 的验收统一标准**：每个子模块单独跑通、单独验收，**一次只挂一个**。
挂三个一起出问题，你无法判断是谁的错 —— 这是排障成本，不是能力问题。

---

# 三、全场 12 个"必须想明白"的点（按重要性排序）

这 12 个点想通了，整个项目就通了；其余的都可以查。

| # | 要点 | 一句话 | 对应阶段 |
|---|---|---|---|
| 1 | 分层与依赖方向 | Controller → Service → core/ai，禁止反向依赖 | 全程 |
| 2 | 统一响应 + 全局异常 | 异常翻译成协议，未知异常必须脱敏 | L0 |
| 3 | 登录态 = Session + Redis | 能主动失效是它相对 JWT 的核心优势 | L1 |
| 4 | 属主校验 | 每个"按资源 id 操作"的接口都要单独校验 | L2 |
| 5 | AI Service 是动态代理 | 只有接口，实现由 `AiServices` 运行时生成 | L3 |
| 6 | 结构化输出四重绑定 | 字段 + `@Description` + 提示词 + 解析器 | L3 |
| 7 | 模板方法 | 骨架 `final` 防改坏，变体 `abstract` 允许扩展 | L3 |
| 8 | 目录契约 | `{codeType}_{appId}` 是串联五条链路的绳子 | L3 |
| 9 | 流式三段转换 | TokenStream → Flux → SSE，别跨层用 | L4 |
| 10 | 流结束才落盘 | 边收边解析必然错 | L4 |
| 11 | 记忆三层 + 窗口对齐 | DB / 堆 / Redis 各管一段；窗口 = 回填条数 | L5 |
| 12 | 游标分页 | 持续增长 + 头部插入的场景禁用 OFFSET | L5 |

---

# 四、读代码时的"三色标记法"（上班时间用这个）

准备三种标记，边读边标：

| 颜色 | 标什么 | 例 |
|---|---|---|
| 🔴 红 | **看不懂、必须回头查**的 | `@Scope("prototype")` 为什么必须有 |
| 🟡 黄 | **看懂了但说不出为什么**的 | 为什么 `saveCode` 是 final |
| 🟢 绿 | **能讲给别人听**的 | `throwIf` 替代 if 嵌套 |

规则：**黄的必须当天转成绿的或红的**，否则就是"以为自己懂了"。
黄的转绿的方法：合上代码，用一句自己的话讲出来（讲不通就是还没懂）。

---

# 五、建议的上班节奏（碎片时间版）

### 5.1 单次默想的标准动作（10 分钟一轮）

```
第 1 步（2 分钟）：只看「默写顺序」，在纸上默画一遍箭头
        ↓ 画不出来 = 依赖关系没懂，记下来
第 2 步（3 分钟）：只看「骨架回忆」，闭眼想"我新建这个类，第一行敲什么"
        ↓ 想不出第一个词 = 类注解没记住
第 3 步（3 分钟）：看「三步自问」，不翻代码，试着回答
        ↓ 答不上来 = 原理没懂，这就是晚上要重点看的
第 4 步（2 分钟）：扫一眼「高频写错点」，问自己"我大概率会踩哪几个"
        ↓ 圈出 1~2 个，晚上写完自查
```

**关键**：全程**不打开 IDE、不看完整源码**。这是"默想"，不是"阅读"。
看源码是验证，不是练习。

### 5.2 时间预算表

| 场景 | 做什么 | 产出 |
|---|---|---|
| 5 分钟 | 只看一个阶段的「默写顺序」图 | 能在纸上画出依赖箭头 |
| 10 分钟 | 走完上面 4 步（针对 1 个阶段） | 圈出 1~2 个"我答不上来"的问题 |
| 20 分钟 | 走完 4 步 + 翻源码验证卡住的那一处 | 红/黄/绿三色标记 |
| 30 分钟 | 把一个完整链路（Facade→Parser→Saver）在纸上画出数据流 | 一张手绘图 |
| 下班后 | 只写当天标记为 🟡/🔴 的部分 | 写不出来的就是下次的重点 |

**不要试图上班时间写代码**。碎片时间只适合"读 + 想 + 默"，写需要连续环境。

### 5.3 每天一个闭环（推荐）

```
上班空闲：默想卡 1 轮（10 分钟）  →  记录「卡住的问题」
              ↓
下班回家：只写这一轮卡住的部分（30~60 分钟）
              ↓
写完后：对着「高频写错点」自查一遍
              ↓
第二天上班：复述昨天写的类（不看代码），检验是否真的会了
```

**★ 为什么是这个闭环**：
上班时大脑没有 IDE、没有自动补全，被迫做"结构性思考"；
回家后有环境，正好把结构填成代码。
**这两种状态互补，不要互相替代。**

### 5.4 一周安排参考（空闲多的情况）

| 日 | 上班默想 | 晚上动手 |
|---|---|---|
| 周一 | L0 全部 12 个文件的骨架 | L0 的 5 个核心类 |
| 周二 | L1 注册/登录两条流程 | L1 的 Service + Controller |
| 周三 | L1 拦截器 + L2 的 CRUD 顺序 | L1 拦截器 + L2 Entity/DTO/VO |
| 周四 | L2 属主校验四处差异 | L2 ServiceImpl 五个方法 |
| 周五 | **L3 全链路默想（重点）** | L3 的模型 + Parser |
| 周六 | L3 的 Template + Facade | L3 剩下的 + 跑通验收 |
| 周日 | 回看 L3 默想卡，闭卷重写一个类 | 休息或补 L4 |

**★ 周五为什么只默想不动手**：L3 是全项目最难的一关，先让它在脑子里"发酵"一天，晚上写会顺很多。**不要一上来就写 L3。**

### 5.5 默想卡的三种"卡住"信号

| 信号 | 说明 | 晚上怎么补 |
|---|---|---|
| 画不出依赖箭头 | 不知道谁先写 | 先看 import 语句，import 就是依赖方向 |
| 想不出类注解 | 不知道这个类在框架里的角色 | 看类头上那几行注解，逐个查作用 |
| 答不上三步自问 | 原理没通 | 看本文对应阶段的「重点理解」+ 补充手册 |

**★ 卡住不是失败，卡住是"今晚的作业清单"。**
最怕的是"感觉都看懂了" —— 那说明你只是在阅读，没有在默想。

---

# 六、什么时候可以开始闭卷

满足下面任意两条，就可以试：

- [ ] 能不看代码，口述 L3 从 Controller 到磁盘文件的完整数据流
- [ ] 能说出模板方法的三个角色（`final` 骨架 / `abstract` 变体 / 子类实现）
- [ ] 能解释"为什么不能把 `StreamingChatModel` 做成单例"
- [ ] 能画出记忆的三层结构图，并说出每层的作用
- [ ] 能说出属主校验必须在哪四个接口里做

**闭卷第一题别选 L3**（最难），从 `BaseResponse` + `ErrorCode` + `ThrowUtils` 这三个开始，5 分钟能写完，先建立"我能写出来"的信心。

### 6.1 闭卷的五个等级（逐级升，别跳）

| 等级 | 题目 | 允许看什么 | 时间 |
|---|---|---|---|
| 1 | 默写 `ErrorCode` + `BaseResponse` + `ThrowUtils` | 什么都不看 | 5 分钟 |
| 2 | 默写一个 Entity + Mapper + 一个 Service 方法 | 可看字段清单 | 15 分钟 |
| 3 | 默写 `CodeFileSaverTemplate` + 一个子类 | 可看骨架回忆 | 15 分钟 |
| 4 | 从空目录写 L0 全部 | 什么都不看 | 40 分钟 |
| 5 | 从空目录写 L3 全链路 | 什么都不看 | 90 分钟 |

**★ 能到等级 3 就足够面试讲清楚了**，等级 5 是"真的复现一遍"的目标，不必强求一次到位。

### 6.2 闭卷失败后的正确反应

```
写不出来
  ↓
不要立刻翻源码
  ↓
先写下"我卡在第几步、卡在哪个词"
  ↓
再看源码，只看卡住的那一处（不要整篇重读）
  ↓
合上源码，把那一处重写一遍
  ↓
隔 24 小时，再闭卷一次同一题
```

**★ 只重写"卡住的那一处"，不要整篇重来。**
整篇重来会让你把时间花在已经会的部分，而卡住的地方永远没练到。

### 6.3 什么时候算"这个阶段通过了"

| 阶段 | 通过标准 |
|---|---|
| L0 | 闭卷能写出统一响应 + 全局异常，且能讲出"为什么 HTTP 永远 200"的代价 |
| L1 | 闭卷能说出注册 4 步、登录 5 步，并解释 Session 与 JWT 的取舍 |
| L2 | 闭卷能说出属主校验必须放 Service 的理由，并列出 4 个必校验接口 |
| L3 | 闭卷能默写 `saveCode` 骨架，并说清"AI 接口为什么没有实现类" |
| L4 | 闭卷能解释 `prototype` 的原因，以及"为什么流结束才落盘" |
| L5 | 闭卷能画出记忆三层图，并说出窗口与回填条数的关系 |
| L6 | 每挂一个子模块，能说清"不做它会在什么场景出问题" |

**★ 通过标准全部是"能解释为什么"，不是"能背出代码"。**
因为面试官考的是判断力，不是记忆力；工作中真正有用的也是判断力。
