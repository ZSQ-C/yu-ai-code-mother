# 手写复现训练计划（L0 ~ L6）

> 配套文档：[yu-ai-code-mother-rebuild-guide.md](./yu-ai-code-mother-rebuild-guide.md) 解决的是「读懂」，这一份解决的是「写出来」。
> 区别就一句话：**读的时候按模块读，写的时候必须按「能跑起来的纵切」写。**

---

## 〇、三条铁律（先看这个，比看任何文件清单都重要）

### 铁律 1：不要从第一个包按字母顺序写到最后一个包

按包写的结果一定是：写了 30 个类，项目还是启动不了，然后你失去反馈、失去耐心、开始复制粘贴。

正确的切法是**纵切（vertical slice）**：每一关都要求「从 HTTP 请求进来 → 处理 → 数据落盘 → 返回」整条链路都通，哪怕这条链路上只有 3 个类。

```
错误切法（横切）             正确切法（纵切）
┌─────────────┐            ┌──────┐
│ 所有 Entity │            │ 用户  │ ← 整个竖条，从 Controller 到 DB
├─────────────┤            └──────┘
│ 所有 Mapper │            ┌──────┐
├─────────────┤            │ 应用  │ ← 整个竖条
│ 所有 Service│            └──────┘
└─────────────┘            ┌──────┐
                           │ AI生成│ ← 整个竖条
                           └──────┘
```

### 铁律 2：三遍法（同一份代码写三遍，不是看三遍）

| 遍数 | 做什么 | 允许看源码吗 | 目的 |
|---|---|---|---|
| 第 1 遍 | **只看接口签名和表结构，自己写实现** | 只看方法签名，不看方法体 | 逼出「设计判断力」 |
| 第 2 遍 | **对照源码 diff**，找差异并解释为什么 | 全看 | 发现自己的盲区 |
| 第 3 遍 | **改造**：换一种实现 / 加一个需求 / 修一个坑 | 参考着看 | 真正内化，变成你的 |

只做第 1 遍 = 眼高手低；只做第 2 遍 = 抄写员；三遍都做才是「会」。

### 铁律 3：每一关必须有「可执行的验收命令」

不是「我觉得我写完了」，而是「这条 curl 能返回预期结果」。

写代码前先把验收命令写在便签上，写完就敲。**没有验收标准的关卡，不要开始。**

---

## 一、开工前的准备（半天，但省后面很多天）

### 1.1 新建一个独立仓库，在原仓库里不要动手

```powershell
mkdir e:\AI\GitHub\my-code-mother
cd e:\AI\GitHub\my-code-mother
git init
```

理由有三：
- 原仓库是**参照物**，你随时要 `git diff` 去对比，改脏了就没法对比了
- 你的复现历史（每关一次 commit）本身就是学习成果，面试时可以讲「我分 7 次提交逐步搭起来」
- 出错了可以 `git checkout .` 重来，心理负担低

### 1.2 先把三份「契约」写死，再写任何 Java 代码

这是本项目最值钱的一点：**它的复杂度不在业务，而在契约**。契约写错，后面全歪。

**契约 A：表结构**（先建表，别急着写 Entity）

```sql
create database if not exists my_code_mother default charset utf8mb4;

-- 用户
create table user (
    id           bigint auto_increment primary key,
    userAccount  varchar(256) not null,
    userPassword varchar(512) not null,
    userName     varchar(256),
    userAvatar   varchar(1024),
    userProfile  varchar(512),
    userRole     varchar(256) default 'user',
    createTime   datetime default current_timestamp,
    updateTime   datetime default current_timestamp on update current_timestamp,
    isDelete     tinyint default 0,
    unique key uk_userAccount (userAccount)
);

-- 应用
create table app (
    id           bigint auto_increment primary key,
    appName      varchar(256) not null,
    cover        varchar(512),
    initPrompt   varchar(4096),
    codeGenType  varchar(64),
    deployKey    varchar(64),
    deployedTime datetime,
    priority     int default 0,
    userId       bigint not null,
    createTime   datetime default current_timestamp,
    updateTime   datetime default current_timestamp on update current_timestamp,
    editTime     datetime default current_timestamp,
    isDelete     tinyint default 0,
    key idx_userId (userId)
);

-- 对话历史
create table chat_history (
    id          bigint auto_increment primary key,
    appId       bigint not null,
    userId      bigint not null,
    message     text not null,
    messageType varchar(32) not null,
    createTime  datetime default current_timestamp,
    updateTime  datetime default current_timestamp on update current_timestamp,
    isDelete    tinyint default 0,
    key idx_appId_createTime (appId, createTime)
);
```

> 最后一个索引 `idx_appId_createTime` 是游标分页的命门，别省。

**契约 B：目录契约**

```
tmp/code_output/{codeType}_{appId}/     ← 生成目录（预览、下载读这里）
tmp/code_deploy/{deployKey}/            ← 部署目录（用户访问读这里）
```

这两个字符串会在 **AI 落盘、预览、部署、下载、截图** 五处被拼出来。第一轮就在 `AppConstant` 里放一个方法生成它，别学原项目硬编码 4 处（这是原项目的真实瑕疵）。

**契约 C：接口契约表**（写在一个 md 里，边写边勾）

| 方法 | 路径 | 入参 | 出参 | 鉴权 |
|---|---|---|---|---|
| POST | `/api/user/register` | account/password/checkPassword | Long | 无 |
| POST | `/api/user/login` | account/password | LoginUserVO | 无 |
| GET | `/api/user/get/login` | - | LoginUserVO | 登录 |
| POST | `/api/app/add` | initPrompt | Long | 登录 |
| GET | `/api/app/chat/gen/code` | appId/message | SSE | 登录+属主 |
| POST | `/api/app/deploy` | appId | String(url) | 登录+属主 |
| GET | `/api/chatHistory/app/{appId}` | appId/pageSize/lastCreateTime | Page | 登录+属主 |

### 1.3 环境清单

| 依赖 | 要求 | 备注 |
|---|---|---|
| JDK | **21** | 项目用了虚拟线程、record、switch 表达式 |
| Maven | 3.8+ | |
| MySQL | 8.x | 库名随便，改 yml 即可 |
| Redis | 任意 | 存 session + 对话记忆 + 限流 |
| 大模型 API | OpenAI 兼容协议 | 用 DeepSeek / 阿里百炼都行，改 `base-url` |

配置分开存放：`application.yml` 里放结构，密钥放 `application-local.yml`（原项目 `.gitignore` 里已经忽略了它，照做）。

---

## 二、七关总表（这就是你的路线图）

| 关 | 名称 | 你要写的核心类 | 验收信号 | 能否跳过 |
|---|---|---|---|---|
| **L0** | 骨架与外壳 | 启动类 + 统一响应 + 全局异常 + 配置 | `GET /api/health/` 返回统一 JSON | 否 |
| **L1** | 用户体系 | User 全链路 + Session + 权限切面 | 注册→登录→带 cookie 取到当前用户 | 否 |
| **L2** | App CRUD | App 全链路 + VO + 属主校验 | 越权访问返回无权限 | 否 |
| **L3** | AI 最小闭环（同步） | AI 接口 + 解析器 + 落盘器 + 门面 | 一句话 → 磁盘出现 index.html | 否 |
| **L4** | 流式 + 预览 | StreamingConfig + Flux/SSE + 静态资源 | 浏览器看到逐字输出 + 能打开生成页 | 否 |
| **L5** | 历史与记忆 | chat_history + 回填 + 缓存 + 游标分页 | 第二轮对话"记得"第一轮 | 否 |
| **L6** | 扩展 | 路由/多文件/部署/下载/限流/监控/Vue/图 | 一个个挂，每个独立验收 | 是 |

**L0~L5 是「一个应用的一生」主线，必须走完；L6 是插件，按需挂。**

依赖关系（不能乱序）：

```
L0 ──► L1 ──► L2 ──► L3 ──► L4 ──► L5 ──► L6
                   ▲                    │
                   └──── 没有 L1 的登录用户，L2 的属主校验写不出来
                         没有 L2 的 appId，L3 的落盘目录没法命名
```

---

## 三、逐关详解

### L0 · 骨架与外壳

**目标**：一个空 Spring Boot 能起来，所有异常都以同一种 JSON 形状返回。

**写什么（按这个顺序）**

| 顺序 | 文件 | 只写这些 |
|---|---|---|
| 1 | `pom.xml` | web / aop / validation / hutool / lombok（**DB、Redis 留到 L1 再加**，见下方说明） |
| 2 | `application.yml` | 端口 8123、context-path `/api`、profiles（datasource、redis 在 L1 补） |
| 3 | `YuAiCodeMotherApplication.java` | `@SpringBootApplication`（`@MapperScan` 在 L1 加） |
| 4 | `common/BaseResponse.java` | code / data / message + 三个构造器 |
| 5 | `common/ResultUtils.java` | `success()` / `success(T)` |
| 6 | `exception/ErrorCode.java` | 枚举：PARAMS_ERROR 40000、NO_AUTH_ERROR 40100、NOT_FOUND_ERROR 40400、SYSTEM_ERROR 50000、TOO_MANY_REQUEST 42900 |
| 7 | `exception/BusinessException.java` | 继承 RuntimeException，持 code + message |
| 8 | `exception/ThrowUtils.java` | `throwIf(boolean, ErrorCode[, msg])` |
| 9 | `exception/GlobalExceptionHandler.java` | `@RestControllerAdvice`，兜 BusinessException / RuntimeException |
| 10 | `config/JsonConfig.java` | Long → String（**不写这个，前端拿到的 id 精度会丢**） |
| 11 | `config/CorsConfig.java` | 跨域 + `allowCredentials(true)` |
| 12 | `controller/HealthController.java` | 一个 GET 返回 `ResultUtils.success("ok")` |

**本关唯一决策点**：错误码是「一套 HTTP 状态码 + 业务码」，还是「HTTP 永远 200 + 业务码」？

原项目选后者（`BaseResponse.code` 承载全部语义）。好处是前端只解析一种结构；坏处是监控、网关按 HTTP 状态码做限流/告警时失效。**你得自己做一个选择并写下来**，面试常问。

**验收**

```powershell
curl http://localhost:8123/api/health/
# 期望：{"code":0,"data":"ok","message":""}

# 再验异常处理：随便访问一个不存在的路径 / 传个错参数
```

**卡点**

- 启动报「找不到 Mapper」→ 忘了 `@MapperScan("com.xxx.mapper")`
- 访问 404 → 路径少写了 `/api`（context-path 是全局前缀）
- 中文乱码 → yml 默认 UTF-8，检查文件编码

---

### L1 · 用户体系

**目标**：能注册、能登录、之后任何接口都能拿到「当前登录用户」。

**写什么**

| 顺序 | 文件 | 关键点 |
|---|---|---|
| 1 | `model/entity/User.java` | `@Table` + 逻辑删除字段 `isDelete` |
| 2 | `mapper/UserMapper.java` | `extends BaseMapper<User>`，空接口 |
| 3 | `model/enums/UserRoleEnum.java` | `user` / `admin` + `getEnumByValue` |
| 4 | `model/dto/user/UserRegisterRequest.java` | account / password / checkPassword |
| 5 | `model/dto/user/UserLoginRequest.java` | account / password |
| 6 | `model/vo/LoginUserVO.java` | 脱敏：**不能有 password** |
| 7 | `constant/UserConstant.java` | `USER_LOGIN_STATE = "user_login"` |
| 8 | `service/UserService.java` | 接口：register / login / getLoginUser / logout |
| 9 | `service/impl/UserServiceImpl.java` | 见下方「四步 / 五步」 |
| 10 | `controller/UserController.java` | `/register` `/login` `/get/login` `/logout` |
| 11 | `annotation/AuthCheck.java` | `mustRole()` |
| 12 | `aop/AuthInterceptor.java` | `@Around("@annotation(authCheck)")` |

**注册四步**：参数校验 → 查重（account 唯一）→ 加盐摘要 → 入库。

**登录五步**：查用户 → 摘要比对 → 转脱敏 VO → 写 session → 返回。

```java
// 关键：session 存的是「登录态」，不是整个 User 对象也行，但存对象更快
request.getSession().setAttribute(USER_LOGIN_STATE, user);
```

**`getLoginUser` 是全项目调用最多的方法**，它的写法决定了整个项目的性能基线：

```java
Object attr = request.getSession().getAttribute(USER_LOGIN_STATE);
if (attr == null) throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
User user = (User) attr;
// 原项目这里又查了一次库；第一轮你可以先直接返回 session 里的对象
return this.getById(user.getId());
```

**本关唯一决策点**：`getLoginUser` 要不要每次查库？

- 查库：能感知"用户被禁用/改角色"，但有 N 倍数据库压力
- 不查：快，但 session 里的数据会"活在旧世界"

第一轮建议**先不查库**，等 L2 把功能跑通后，再回来加一层本地缓存 / 只查必要字段——这样你能亲身感受到这个权衡，而不是背结论。

**验收**

```powershell
curl -c cookie.txt -X POST http://localhost:8123/api/user/register `
  -H "Content-Type: application/json" `
  -d '{\"userAccount\":\"test1\",\"userPassword\":\"12345678\",\"checkPassword\":\"12345678\"}'

curl -c cookie.txt -X POST http://localhost:8123/api/user/login `
  -H "Content-Type: application/json" `
  -d '{\"userAccount\":\"test1\",\"userPassword\":\"12345678\"}'

curl -b cookie.txt http://localhost:8123/api/user/get/login
# 期望：data.userAccount = test1，且没有 password 字段
```

**卡点**

- 登录成功但 `get/login` 拿不到 → 检查 `spring.session.store-type: redis` 是否配置、cookie 有没有带上
- 密码字段泄露 → `BeanUtil.copyProperties` 到 VO 时把 password 也复制了，用 `@JsonIgnore` 或手动赋值
- `@AuthCheck` 不生效 → 忘了 `@EnableAspectJAutoProxy`（Spring Boot 默认开了，但切面类必须 `@Component`）

---

### L2 · App CRUD

**目标**：登录用户能增删改查自己的应用，且**越权一定被拦**。

**写什么**

| 顺序 | 文件 | 关键点 |
|---|---|---|
| 1 | `model/entity/App.java` | 字段见契约 A；`initPrompt` 要 `@Column(length=4096)` |
| 2 | `mapper/AppMapper.java` | 空接口 |
| 3 | `model/enums/CodeGenTypeEnum.java` | `HTML` / `MULTI_FILE` / `VUE_PROJECT`，带 `value` + `getEnumByValue` |
| 4 | `model/dto/app/AppAddRequest.java` | 只要 `initPrompt` |
| 5 | `model/dto/app/AppUpdateRequest.java` | `id` + `appName` |
| 6 | `model/dto/app/AppQueryRequest.java` | `extends PageRequest`，加 userId / appName / codeGenType |
| 7 | `model/vo/AppVO.java` | App 字段 + `user: UserVO` |
| 8 | `service/AppService.java` | 接口：createApp / getAppVO / getAppVOList / getQueryWrapper |
| 9 | `service/impl/AppServiceImpl.java` | `extends ServiceImpl<AppMapper, App>` |
| 10 | `common/PageRequest.java` | pageNum / pageSize / sortField / sortOrder |
| 11 | `controller/AppController.java` | add / update / delete / get/vo / my/list/page/vo |

**`createApp` 三个动作**：校验 prompt → 填 `userId` + `appName`（prompt 前 12 位）→ 入库返回 id。

> 原项目在中间插了一步「AI 选生成类型」。**第一轮先写死 `HTML`**，等 L6 再补路由。这是典型的"先把纵切打通，再加分支"。

**属主校验是这一关的灵魂**，四处都要写，且写法不能一样：

| 位置 | 校验逻辑 | 为什么不一样 |
|---|---|---|
| `updateApp` | 只有作者能改 | 改内容的风险最高 |
| `deleteApp` | 作者 **或管理员** | 管理需要删违规应用 |
| `deployApp` | 只有作者能部署 | 部署=公开，必须严 |
| `download/{appId}` | 只有作者能下载 | 源码泄露风险 |

**统一写法**（第一轮别抄原项目的散装 if）：

```java
private App getAppWithAuth(Long appId, User loginUser, boolean allowAdmin) {
    ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
    App app = this.getById(appId);
    ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
    boolean isCreator = app.getUserId().equals(loginUser.getId());
    boolean isAdmin = allowAdmin && UserRoleEnum.ADMIN.getValue().equals(loginUser.getUserRole());
    ThrowUtils.throwIf(!isCreator && !isAdmin, ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
    return app;
}
```

**`getQueryWrapper` 的两个坑**（原项目也有）：

1. `orderBy(sortField)` 直接拼用户传入的字段名 → 有注入面，**加列名白名单**
2. 各条件都要 `if (xxx != null)` 才拼，否则 `eq("appId", null)` 会生成 `appId = null` 查不到数据

**验收**

```powershell
# 1. 用户 A 建应用
curl -b cookieA.txt -X POST http://localhost:8123/api/app/add `
  -H "Content-Type: application/json" -d '{\"initPrompt\":\"做一个个人博客首页\"}'

# 2. 用户 B 尝试改 A 的应用，必须失败
curl -b cookieB.txt -X POST http://localhost:8123/api/app/update `
  -H "Content-Type: application/json" -d '{\"id\":1,\"appName\":\"hacked\"}'
# 期望：code = 40100

# 3. 分页
curl -b cookieA.txt -X POST http://localhost:8123/api/app/my/list/page/vo `
  -H "Content-Type: application/json" -d '{\"pageNum\":1,\"pageSize\":10}'
```

**卡点**

- 分页查不到数据 → `isDelete` 逻辑删除字段没配 `@Column(isLogicDelete = true)`
- `AppVO` 里 user 为 null → 忘了 `getAppVOList` 批量查 user 再 set
- 创建时间不自动填 → 用的是 `datetime default current_timestamp`，但 MyBatis-Flex 插入时传了 null 会覆盖，用 `@Column(onInsertValue = "now()")`

---

### L3 · AI 最小闭环（同步版，全项目最关键的一关）

**目标**：给一句话，磁盘上真的出现一个能打开的 `index.html`。

**这是唯一一关需要「先画图再写代码」的**，因为跨了 4 层：

```
Controller → Facade → Factory → AI 接口(动态代理)
                                  ↓ 返回字符串
                               Parser(正则) → Result 对象
                                  ↓
                               Saver(模板方法) → 磁盘文件
```

**写什么（顺序不能变，否则编译不过）**

| 顺序 | 文件 | 作用 | 只写这些 |
|---|---|---|---|
| 1 | `ai/model/HtmlCodeResult.java` | 结构化输出载体 | `htmlCode` + `@Description`（**契约**） |
| 2 | `ai/AiCodeGeneratorService.java` | 声明式 AI 服务**接口** | `@SystemMessage(fromResource)` + `HtmlCodeResult generateHtmlCode(String)` |
| 3 | `resources/prompt/codegen-html-system-prompt.txt` | 提示词 | 必须明确要求输出 ```html 代码块 |
| 4 | `core/parser/CodeParser.java` | 解析接口 | `T parseCode(String)` |
| 5 | `core/parser/HtmlCodeParser.java` | 正则提取 | 一个 `Pattern` + `matcher.find()` |
| 6 | `core/parser/CodeParserExecutor.java` | 分派 | `switch` + 静态单例 |
| 7 | `core/saver/CodeFileSaverTemplate.java` | **模板方法** | `final saveCode()` + `abstract saveFiles()` |
| 8 | `core/saver/HtmlCodeFileSaverTemplate.java` | 具体落盘 | 三行：`writeToFile(dir, "index.html", code)` |
| 9 | `core/saver/CodeFileSaverExecutor.java` | 分派 | `switch` + 静态单例 |
| 10 | `ai/AiCodeGeneratorServiceFactory.java` | 造 AI 服务 | 第一轮直接 `AiServices.builder(...).build()`，**先不缓存** |
| 11 | `core/AiCodeGeneratorFacade.java` | 门面 | 拿服务 → 生成 → 解析 → 落盘 → 返回 File |
| 12 | 在 `AppServiceImpl` 加一个方法 | 串起来 | 校验 → 查 app → 调 facade |

**三个必须自己做的判断**（身就是面试题）：

1. **为什么要有 `AiCodeGeneratorService` 这个空接口？**
   因为 LangChain4j 的 `AiServices.builder(XxxService.class)` 会在**运行时生成代理实现**。你只写接口，实现是框架"变"出来的。所以这个接口里的方法签名 = 调用契约，注解 = 提示词。

2. **`HtmlCodeResult` 上的 `@Description` 是干嘛的？**
   它是告诉模型"这个字段应该放什么"的**结构化输出契约**。改字段名不改 `@Description`，模型输出就会跑偏。同样，**提示词要求输出 ` ```html `，解析器就必须按 ` ```html ` 提取**——这两者强绑定，改一个必须改另一个。

3. **为什么落盘要用模板方法？**
   因为"建目录 → 写文件 → 返回"这个骨架**永远不变**，变的只是"写哪些文件"。骨架用 `final` 锁死，子类只能实现 `saveFiles` 和 `getCodeType`。好处是以后加 MULTI_FILE / VUE 类型时，**目录契约和写入规则不会被某个人写歪**。

**目录契约这一关必须做对**

```java
String dirName = codeType.getValue() + "_" + appId;   // html_123
String dirPath = ROOT + File.separator + dirName;
```

写成方法放 `AppConstant`，后面 L4/L6 全部复用。原项目这里是硬编码 4 处，**你在第一轮就改对，这是你面试可以讲的改进**。

**验收**

```powershell
# 调接口（先临时开一个同步接口，L4 再改成流式）
curl -b cookie.txt "http://localhost:8123/api/app/gen?appId=1&message=做一个简单的个人主页"

# 看磁盘
dir tmp\code_output\html_1
# 期望：index.html 存在，双击能用浏览器打开并且有内容

# 内容校验
# 文件里不能有 ```html 这种 markdown 标记 —— 说明解析器真的把围栏剥掉了
```

**卡点（这一关一定会踩）**

| 现象 | 原因 | 解法 |
|---|---|---|
| `index.html` 里带着 ` ```html ` | 正则没匹配上，走了兜底"整段当 HTML" | 打印原始返回，检查是不是模型没按格式输出 |
| 文件是空的 | `StrUtil.isBlank` 判断后静默跳过 | 在 `writeToFile` 里对空白内容**抛异常**而不是跳过（原项目这里是静默跳过，是真坑） |
| 报"没有这个 bean" | AI 接口是 LangChain4j 生成的，不能 `@Resource` 注入 | 必须通过 Factory 拿 |
| 401 / 超时 | key 没配、base-url 写错 | 打开 `log-requests: true` 看真实请求 |

---

### L4 · 流式输出 + 预览

**目标**：浏览器里能看到代码一个字一个字蹦出来；生成完能直接打开预览。

**写什么**

| 顺序 | 文件 | 关键点 |
|---|---|---|
| 1 | `config/StreamingChatModelConfig.java` | `@Bean` + **`@Scope("prototype")`** |
| 2 | `ai/AiCodeGeneratorService.java` | 加 `Flux<String> generateHtmlCodeStream(String)` |
| 3 | `core/AiCodeGeneratorFacade.java` | 加 `generateAndSaveCodeStream` |
| 4 | `core/handler/SimpleTextStreamHandler.java` | `.doOnNext` 攒 + `.doOnComplete` 落库 |
| 5 | `controller/AppController.java` | `produces = TEXT_EVENT_STREAM_VALUE`，返回 `Flux<ServerSentEvent<String>>` |
| 6 | `controller/StaticResourceController.java` | `GET /static/{deployKey}/**` 或 `/{appId}/**` 读文件 |

**这一关的四个核心认知**：

1. **为什么 `StreamingChatModel` 要 `prototype`？**
   流式模型内部有"当前流"的可变状态，单例共享会让两个用户的 token 串在一起。同理 AI 服务实例也得一 appId 一份。

2. **为什么必须"流完全结束后"才解析和落盘？**
   边收边解析 = 拿半截 HTML 去正则 = 必然错。原项目的方法是：流里只攒字符串，`doOnComplete` 里再 parse+save。**这是流式处理的通用原则，记住**。

3. **SSE 为什么要 GET？**
   浏览器原生 `EventSource` 只支持 GET。如果你想用 POST，就得前端用 `fetch` + `ReadableStream` 自己解析。

4. **为什么要补一个 `done` 事件？**
   SSE 连接正常关闭时，前端 `onmessage` 不会收到任何信号。必须服务端显式发 `event: done`，前端才知道"生成完了，可以去加载预览了"。

**预览接口的本质**（别把它想复杂）：

```
用户请求 /api/static/xxx/index.html
   → 拼出 tmp/code_output/html_1/index.html
   → 检查路径是否越界（必须做！）
   → 用 Resource 返回，Content-Type 按后缀给
```

**验收**

```powershell
curl -N -b cookie.txt "http://localhost:8123/api/app/chat/gen/code?appId=1&message=做一个计时器"
# -N 关掉缓冲，应该看到内容一段一段出现，最后有 event: done
```

浏览器打开 `http://localhost:8123/api/static/1/index.html` 能看到页面。

**卡点**

- 全都一次性返回 → Nginx/代理缓冲，本地先确认 curl `-N` 是流式的
- 前端收不到 → 少写了 `produces = MediaType.TEXT_EVENT_STREAM_VALUE`
- 第二个用户一进来就串流 → 你用了单例 `StreamingChatModel`
- 预览 404 → 目录名拼错（又是那个契约）

---

### L5 · 对话历史与记忆

**目标**：同一个应用聊第二轮时，模型"记得"第一轮。

**写什么**

| 顺序 | 文件 | 关键点 |
|---|---|---|
| 1 | `model/entity/ChatHistory.java` | appId / userId / message / messageType |
| 2 | `model/enums/ChatHistoryMessageTypeEnum.java` | `user` / `ai` |
| 3 | `mapper/ChatHistoryMapper.java` | 空接口 |
| 4 | `service/ChatHistoryService.java` | addChatMessage / loadChatHistoryToMemory / listAppChatHistoryByPage |
| 5 | `service/impl/ChatHistoryServiceImpl.java` | 见下 |
| 6 | `config/RedisChatMemoryStoreConfig.java` | `RedisChatMemoryStore` bean |
| 7 | `ai/AiCodeGeneratorServiceFactory.java` | 加 Caffeine 缓存 + 记忆回填 |
| 8 | `core/handler/JsonMessageStreamHandler` / 简化版 | AI 消息落库 |
| 9 | `controller/ChatHistoryController.java` | 游标分页查询 |

**三条铁律（这三条错一条，功能就是坏的）**

1. **用户消息必须在调 AI 之前落库**
   顺序反了 → 如果 AI 调用失败，用户消息就丢了，对话断层且无法追溯。

2. **AI 消息挂在流的"完成"回调上，不要在 `onNext` 里落库**
   `onNext` 每收一个 token 就落库一次 = 几万次写库。正确是攒完整条，在 `doOnComplete` 写一次。

3. **记忆窗口大小必须等于回填条数**
   `maxMessages(20)` + `loadChatHistoryToMemory(appId, memory, 20)`。如果窗口 10 回填 20，白查一半；如果窗口 20 回填 10，模型永远"只记得最近 10 条"，你会误以为是模型问题。

**Caffeine 缓存这一层是「性能优化」，不是「功能必需」**

第一轮你可以先 `new` 一个 service 每请求一次，功能是对的，只是慢。等你想明白下面这句再改成缓存：

> 记忆的**内容**存在 Redis（`RedisChatMemoryStore`），记忆的**窗口对象**在 JVM 堆里。缓存的作用是让"窗口对象"复用，避免每轮都重新查 20 条历史。

**三层状态别混淆**（这是面试高频追问）：

| 层 | 存在哪 | 生命周期 | 作用 |
|---|---|---|---|
| DB `chat_history` | MySQL | 永久 | 事实来源，用户可查 |
| `MessageWindowChatMemory` | 堆（+ Redis 持久化） | 缓存实例存活期 | 给模型看最近 20 条 |
| Redis `RedisChatMemoryStore` | Redis | 30 分钟/按配置 | 实例被淘汰后能恢复记忆 |

**验收**

```powershell
# 第一轮：告诉它一个事实
curl -N -b cookie.txt ".../chat/gen/code?appId=1&message=做一个页面，标题叫「小明的博客」"
# 第二轮：不提标题，只问它
curl -N -b cookie.txt ".../chat/gen/code?appId=1&message=把标题改成红色"
# 期望：生成的 HTML 里标题仍是「小明的博客」
```

```powershell
# 历史查询（游标）
curl -b cookie.txt "http://localhost:8123/api/chatHistory/app/1?pageSize=10"
```

**卡点**

- 第二轮"失忆" → ① 记忆对象每请求 new 新的 ② 回填没调 ③ Redis store 的 `id` 不一致
- 历史里有错误消息 → 别把错误信息当初 `ai` 类型落库，否则污染下一次的上下文
- 翻页重复/漏记录 → `createTime` 是秒级精度，同秒多条会被 `lt` 跳过。改成 `(createTime, id)` 复合游标

---

### L6 · 扩展（插件式，一个个挂，每个独立验收）

**挂载顺序**（按"投入产出比"排，前三个最值得做）：

| 顺序 | 扩展 | 你要写的类 | 验收 | 建议 |
|---|---|---|---|---|
| 6.1 | **多文件生成** | `MultiFileCodeResult` + `MultiFileCodeParser` + `MultiFileCodeFileSaverTemplate` | 目录里有 html/css/js 三个文件 | 必做，练模板方法的扩展性 |
| 6.2 | **一键部署** | `deployApp` + `deployKey` + 静态托管目录 | 返回 URL，浏览器能打开 | 必做，理解"部署=复制目录" |
| 6.3 | **代码下载** | `ProjectDownloadService` + ZIP | 下载的 zip 能解压运行 | 必做，练流式 IO |
| 6.4 | **限流** | `@RateLimit` + `RateLimitAspect` + Redisson | 1 分钟第 6 次返回 429 | 必做，练 AOP + 分布式 |
| 6.5 | **类型路由** | `AiCodeGenTypeRoutingService` + Factory | 建应用时不传类型也能选对 | 选做，练结构化分类 |
| 6.6 | **封面截图** | `ScreenshotService` + `WebScreenshotUtils` | 部署后 cover 有 URL | 选做，**注意它有两个真 bug** |
| 6.7 | **监控** | `MonitorContextHolder` + `AiModelMonitorListener` | Prometheus 有指标 | 选做 |
| 6.8 | **Vue 工具调用** | `ai/tools/*` + `TokenStream` + `VueProjectBuilder` | Vue 工程可构建 | 第二轮再做 |
| 6.9 | **LangGraph4j** | 整套 `langgraph4j/*` | 三种输出都能跑 | **建议放最后或跳过** |

> 6.9 是作者的"技术试验田"，不参与主链路。你把时间投在这里，性价比最低。

---

## 四、「写而不抄」的五个具体手法

### 手法 1：断点法 —— 从外往里写，不从里往外写

```java
// 第一步：先写 Controller 的方法签名，方法体 throws UnsupportedOperationException
@PostMapping("/add")
public BaseResponse<Long> addApp(@RequestBody AppAddRequest req, HttpServletRequest request) {
    throw new UnsupportedOperationException("还没写");
}
```

先让**入口存在**，用 curl 打通协议层，再往里填。好处：你永远有一条"能跑的骨架"，不会陷入"写了半天起不来"。

### 手法 2：遮挡法 —— 只看签名，不看实现

打开原项目的 `AppServiceImpl.java`，用编辑器折叠所有方法体，只留：

```java
public Flux<String> chatToGenCode(Long appId, String message, User loginUser)
public Long createApp(AppAddRequest appAddRequest, User loginUser)
public String deployApp(Long appId, User loginUser)
```

看懂"它要什么、给什么"，然后**关掉文件自己写**。写完再展开对比。

### 手法 3：差异法 —— 用 git diff 给自己判卷

```powershell
# 把你的实现和原项目同名文件对比
git diff --no-index e:\AI\GitHub\my-code-mother\src\main\java\...\AppServiceImpl.java `
                e:\AI\GitHub\yu-ai-code-mother\src\main\java\...\AppServiceImpl.java
```

对比时只问三件事：**我多的/少的是什么？为什么？谁更好？**

### 手法 4：延迟 24 小时重写

一关写完，第二天**不看代码**重新写一遍同一个类。写得出来 = 真会；写不出来 = 昨天只是在抄。

### 手法 5：故意破坏

每关结束时，故意改坏一个点，看会发生什么：

- 把 `saveCode` 的 `final` 去掉 → 你能改坏什么？
- 把记忆窗口改成 5，回填仍是 20 → 观察模型"失忆"
- 把 `StreamingChatModel` 改成单例 → 开两个窗口同时生成，看串流

**"见过 bug 长什么样"比"背过最佳实践"值钱一个量级。**

---

## 五、必须手写的文件优先级（别平均用力）

### P0：必须能闭卷写出来（约 25 个）

| 分类 | 文件 |
|---|---|
| 外壳 | `BaseResponse` `ResultUtils` `ErrorCode` `BusinessException` `ThrowUtils` `GlobalExceptionHandler` |
| 用户 | `User` `UserServiceImpl`（register/login/getLoginUser） `UserController` `AuthInterceptor` |
| 应用 | `App` `AppServiceImpl`（createApp/属主校验/getQueryWrapper） `AppController` |
| AI | `HtmlCodeResult` `AiCodeGeneratorService` `AiCodeGeneratorServiceFactory` `AiCodeGeneratorFacade` |
| 设计模式 | `CodeFileSaverTemplate` `HtmlCodeFileSaverTemplate` `CodeFileSaverExecutor` `HtmlCodeParser` `CodeParserExecutor` |
| 流 | `SimpleTextStreamHandler`（doOnNext 攒 + doOnComplete 落库） |
| 历史 | `ChatHistoryServiceImpl`（add / loadChatHistoryToMemory / 游标分页） |

### P1：能看懂 + 能改，不必默写

`VueProjectBuilder`、`JsonMessageStreamHandler`、`CosManager`、`WebScreenshotUtils`、`RateLimitAspect`、`MonitorContextHolder`、`AiModelMonitorListener`

### P2：直接跳过，用到再查

`MyBatisCodeGenerator`（代码生成器）、`langgraph4j/demo/*`、`LangGraphStudioSampleConfig`

---

## 六、第一轮必须跳过的清单（附原因）

| 跳过项 | 原因 | 什么时候回来 |
|---|---|---|
| `langgraph4j` 全部 | 不参与主链路，是实验性模块，概念密度高但业务收益低 | 主线全通后当"图编排"学习材料 |
| Vue 工具调用 | 需要理解 TokenStream + Function Calling + npm 构建，三个新概念叠加 | 第二轮 |
| Selenium 截图 | 有已知 bug（静态 WebDriver 无锁），且依赖 Chrome 环境 | 第二轮 |
| COS 对象存储 | 需要云账号；本地可用 `tmp/` 目录替代 | 有时间再补 |
| 监控 / Actuator | 属于可观测性，不影响功能闭环 | 最后 |
| DashScope 生图 | langgraph4j 模块专用 | 一起跳过 |

**跳过不是偷懒，是保护反馈回路。** 每多引入一个外部依赖，就多一个"可能不是我代码的问题"。

---

## 七、验收命令模板（复制改参数直接用）

```powershell
# 基础
$BASE = "http://localhost:8123/api"

# 注册
curl -c a.txt -X POST "$BASE/user/register" -H "Content-Type: application/json" -d '{\"userAccount\":\"a\",\"userPassword\":\"12345678\",\"checkPassword\":\"12345678\"}'

# 登录
curl -c a.txt -X POST "$BASE/user/login" -H "Content-Type: application/json" -d '{\"userAccount\":\"a\",\"userPassword\":\"12345678\"}'

# 当前用户
curl -b a.txt "$BASE/user/get/login"

# 建应用
curl -b a.txt -X POST "$BASE/app/add" -H "Content-Type: application/json" -d '{\"initPrompt\":\"做一个个人主页\"}'

# 流式生成（-N 关键）
curl -N -b a.txt "$BASE/app/chat/gen/code?appId=1&message=加一个深色模式按钮"

# 历史（游标）
curl -b a.txt "$BASE/chatHistory/app/1?pageSize=10"
```

**每条命令都要能说出「期望的 code 是多少、为什么」**，否则就只是机械敲。

---

## 八、卡住时的诊断顺序（照这个顺序查，别乱试）

```
1. 报错栈最上面那行是什么异常？          → 90% 的问题这里就有答案
2. 异常是我的代码抛的，还是框架抛的？      → 框架抛的去看配置/注解
3. 断点或日志打在「入参」和「出参」各一次  → 确定是"没进来"还是"算错了"
4. SQL 打出来了吗？                      → 开 mybatis 日志，看真实 SQL
5. 外部调用打出来了吗？                  → 开 langchain4j log-requests
6. 以上都正常 → 是你的假设错了，重读一遍需求
```

**禁止行为**：连续 3 次以上"改一行、重启、看结果"而不加日志。那不是调试，是抽奖。

---

## 九、每关对应的面试口径（写完立刻记下来）

| 关 | 面试可能问 | 你要能答 |
|---|---|---|
| L0 | 为什么要统一响应体？ | 前端只解析一种结构；但要说明 HTTP 状态码语义丢失的代价 |
| L1 | session 和 JWT 怎么选？ | session 可主动失效、实现简单；JWT 无状态但撤销难 |
| L2 | 越权怎么防？ | 每个"以资源 id 为入参"的接口都要校验属主，不能只在列表接口过滤 |
| L3 | 为什么用模板方法？ | 骨架 final 防改坏，变体 abstract 允许扩展；新增类型零改动调用方 |
| L3 | AI 返回格式怎么保证？ | 结构化输出（`@Description` 契约）+ 提示词 + 解析器三者强绑定 |
| L4 | 为什么流式？ | LLM 首字节慢，同步会触发各层读超时；流式提升体感并尽早失败 |
| L4 | 为什么 `prototype`？ | 流式模型持有可变流状态，共享会串 |
| L5 | 记忆窗口怎么定？ | 与回填条数一致；窗口太大 token 成本高，太小会失忆 |
| L5 | 为什么游标分页？ | 数据持续增长且头部插入，OFFSET 会漂移且深翻页慢 |
| L6 | 限流为什么用 Redis？ | 多实例下本地计数各算各的，等于没限 |

---

## 十、如果你只有碎片时间：最小可行路径

不能一次坐 4 小时，就按这个"最小闭环"走，每一步都是独立可交付的：

| 步 | 只做这一件事 | 完成标志 |
|---|---|---|
| 1 | 空工程 + `BaseResponse` + `HealthController` | `/api/health/` 通 |
| 2 | `User` + 注册登录 + `getLoginUser` | cookie 能取到用户 |
| 3 | `App` 表 + add + get/vo | 能建能查 |
| 4 | `HtmlCodeResult` + AI 接口 + 打印返回 | 控制台能看到模型输出 |
| 5 | 加正则解析 + 落盘 | 磁盘出现 index.html |
| 6 | 加 Flux + SSE | curl -N 看到流 |
| 7 | 加静态资源接口 | 浏览器能打开生成的页 |
| 8 | 加 chat_history 记录 | 数据库有两条记录 |
| 9 | 加记忆回填 | 第二轮记得第一轮 |

**第 5 步是整个项目的心跳**——走到这里，你就已经"拥有"这个项目了，后面都是加分项。

---

## 附：每关的 commit 规范（方便你回看自己的成长）

```
L0: feat(skeleton): 统一响应体 + 全局异常 + 健康检查
L1: feat(user): 注册登录 + Session 登录态 + 权限切面
L2: feat(app): App CRUD + 属主校验
L3: feat(ai): AI 同步生成闭环（接口 + 解析 + 落盘）
L4: feat(stream): Flux/SSE 流式输出 + 静态资源预览
L5: feat(memory): 对话历史落库 + 记忆回填 + 游标分页
L6.x: feat(xxx): ...
```

写完一关 commit 一次。**7 次提交的仓库，比 1 次提交的仓库，在面试时能讲的东西多 7 倍。**
