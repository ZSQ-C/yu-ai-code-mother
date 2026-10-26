# L7+ · 后续模块补充（按功能 · 不包含前端）

> 本文是 [L0-L6-file-checklist.md](file:///e:/AI/GitHub/yu-ai-code-mother/docs/L0-L6-file-checklist.md) 的**续集**，格式完全一致：`一句话目标 → 文件清单表 → 重点理解 → 默想卡 → 给肉讲解 → 调用链 → 坑 → 验收`。
> 覆盖 L0~L6 没深入讲的 4 个后端功能模块（不含前端 visualEditor）：

| 编号 | 模块 | 一句话 | 对应 L6 里的遗留 |
|---|---|---|---|
| **L7** | AI 工具调用体系 | 模型怎么"自己写文件" | L6.7 只一句 `ai/tools/*（8个）` 带过 |
| **L8** | 代码解析与落盘（模板方法 + 策略） | 生成文本 → 结构化 → 落盘的全套抽象 | L3/L6.1 分散提到，没讲这套设计模式 |
| **L9** | 生成核心编排 Facade + Vue 流式协议 | 同步/流式/工具三合一的门面 | L4 只讲了 Flux，没讲 TokenStream |
| **L10** | 可观测性 | 记录 LLM 耗时/Token/错误的完整链路 | L6.6 只点了 ThreadLocal 一个坑 |

---

# L7 · AI 工具调用体系（模型怎么自己写文件）

> 一句话目标：搞清楚**大模型是如何把"生成一个 Vue 工程"变成"调用 8 个 Java 工具方法去写文件"的**，以及这套工具声明/注册/分发/防穿越是怎么搭起来的。

## 文件清单表

| 文件 | 职责 | 盯住 |
|---|---|---|
| [BaseTool.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools/BaseTool.java) | 工具抽象基类 | 4 个方法谁继承谁重写 |
| [ToolManager.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools/ToolManager.java) | 工具注册 + 分发 | `@PostConstruct` 收工具 |
| [FileWriteTool.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools/FileWriteTool.java) | 写文件 | `@Tool` + `@P` + `@ToolMemoryId` |
| [FileReadTool.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools/FileReadTool.java) | 读文件 | 相对路径解析 |
| [FileModifyTool.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools/FileModifyTool.java) | 改文件 | replace 含 old 校验 |
| [FileDirReadTool.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools/FileDirReadTool.java) | 列目录 | 忽略 node_modules |
| [FileDeleteTool.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools/FileDeleteTool.java) | 删文件 | 重要文件保护 |
| [ExitTool.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools/ExitTool.java) | 结束工具 | 防死循环 |

## ★ 本阶段重点理解

1. **`@Tool` + `@P` 是"给模型看的接口文档"**：LangChain4j 会在运行时把带 `@Tool` 的方法、每个 `@P` 的参数描述，自动生成一份 **JSON Schema** 发给模型。模型据此知道"有个 `write_file` 工具，参数有 `relativeFilePath` 和 `content`"。**你写的是 Java 方法，模型看到的是函数签名清单**。

2. **`@ToolMemoryId` 是"工具也要认人"**：工具方法要写文件，得知道写到哪个 appId 的目录。`@ToolMemoryId Long appId` 让框架把记忆上下文里的 ID 注入工具方法——**工具不是无状态的，它也要定位到"这次生成属于谁"**。

3. **相对路径强制解析到项目目录 = 防路径穿越**：工具不会直接信模型给的路径，而是 `resolveProjectFile(appId, relativeFilePath)` 把它拼到 `vue_project_{appId}` 下。这样模型哪怕恶意传 `../../etc/passwd` 也写不出去。

4. **工具方法返回"错误字符串"而不是"抛异常"**：`try-catch` 后 `return "写入文件失败: xxx"`。因为**抛异常会让整个 Agent 链崩掉**；返回错误文本则让模型"看到"自己的错误，下一轮换个方式重试。这是 Agent 工程里"把失败当消息"的核心手法。

5. **`ExitTool` 和 `maxSequentialToolsInvocations(20)` 是同一件事的双保险**：模型可能陷入"不停调工具"的循环，ExitTool 给模型一个"我干完了"的出口，20 次上限是兜底强制断环。

## 🧠 默想卡

- **默写顺序**：`BaseTool(抽象) → 6 个具体工具 → ToolManager(注册分发) → Factory 里 .tools(toolManager.getAllTools())`
- **三步自问**：① `@Tool` 注解到底给谁看、被谁消费？② `@ToolMemoryId` 解决什么问题？③ 为什么工具不抛异常而返回错误字符串？
- **高频写错点**：工具方法忘了 `@ToolMemoryId` 导致拼不出项目目录；相对路径没做 `resolve` 校验写出越权文件；工具直接抛异常导致 Agent 链路崩。

## 具体代码示例（给"肉"）

**BaseTool 的 4 个方法分工**（[BaseTool.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools/BaseTool.java)）：

```java
public abstract class BaseTool {
    public abstract String getToolName();            // 工具名（给模型看的标识）
    public abstract String getDisplayName();         // 展示名（给前端/历史看）
    // 折扣展示工具执行结果（存对话历史时，把太长的结果压缩）
    public abstract String generateToolRequestResponse(Object... objects);
    public abstract String generateToolExecutedResult(String result);
}
```

**FileWriteTool 的核心方法**（节选，注意 `@Tool`/`@P`/`@ToolMemoryId` 三件套）：

```java
@Component
public class FileWriteTool extends BaseTool {

    @Tool("写入一个文件，若存在则覆盖。只接受相对路径")
    public String writeFile(
            @P("要写入的文件相对路径，例如 src/main.js") String relativeFilePath,
            @P("要写入的文件内容") String content,
            @ToolMemoryId Long appId) {
        // 1. 相对路径解析到 vue_project_{appId} 下，防路径穿越
        Path projectPath = getProjectPath(appId);
        Path filePath = projectPath.resolve(relativeFilePath).normalize();
        if (!filePath.startsWith(projectPath)) {
            return "非法路径：" + relativeFilePath;   // 不抛异常，把错误告诉模型
        }
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content);
        } catch (Exception e) {
            return "写入文件失败：" + e.getMessage(); // 错误返回给模型，让它重试
        }
        return "文件写入成功：" + relativeFilePath;   // 只回相对路径，不暴露绝对路径
    }
}
```

**ToolManager 注册所有工具**（[ToolManager.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/tools/ToolManager.java)）：

```java
@Component
public class ToolManager {
    @Resource
    private List<BaseTool> tools;          // Spring 自动注入所有 BaseTool 子类

    private final Map<String, BaseTool> toolMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        for (BaseTool tool : tools) {
            toolMap.put(tool.getToolName(), tool);   // 按名建索引
        }
    }

    public BaseTool getTool(String toolName) { return toolMap.get(toolName); }
    public List<BaseTool> getAllTools() { return new ArrayList<>(tools); }
}
```

> 为什么用 `List<BaseTool>` 注入：**新增工具零改动**——你只要 `@Component` 再写一个 `BaseTool` 子类，Spring 会自动把它塞进 list，ToolManager 的 `@PostConstruct` 自动注册。开闭原则的最小演示。

## 调用链

```text
模型输出 "要调用 write_file(relativeFilePath=..., content=...)"
   → AiServices 找到工具名 → ToolManager.getTool("write_file")
   → 调 FileWriteTool.writeFile(...)，@ToolMemoryId 注入 appId
   → 解析到 vue_project_{appId}/.../ 写盘
   → 返回 "文件写入成功"（作为 tool 结果回给模型，供它下一轮决策）
```

## 坑

| 坑 | 后果 |
|---|---|
| 工具方法直接 `throw` 异常 | 整个 Agent 链终止，模型收不到"我错了"的信号 |
| 不校验路径 `startsWith(projectPath)` | 目录穿越漏洞，模型可写任意文件 |
| 返回绝对路径 | 把服务器文件系统结构泄露给前端/历史 |
| 忘了 `@ToolMemoryId` | 多 appId 工具共享目录，互相覆盖 |

## 验收

写一个测试：注入 `ToolManager`，`getAllTools()` 返回 8 个工具；调 `FileWriteTool.writeFile("a/b.txt", "hello", 1L)` 后，`vue_project_1/a/b.txt` 文件存在且内容为 `hello`；传 `../../evil.txt` 返回"非法路径"且不落盘。

---

# L8 · 代码解析与落盘（模板方法 + 策略分派）

> 一句话目标：搞清楚**"模型吐出来的文本 → 结构化 POJO → 按类型落盘"这套流程，是怎么用「模板方法 + 策略模式 + switch 分派」三件套组织成可扩展体系的**。

## 文件清单表

| 文件 | 职责 | 盯住 |
|---|---|---|
| [parser/CodeParser.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/CodeParser.java) | 解析策略接口 | 泛型 `<T>` |
| [parser/HtmlCodeParser.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/HtmlCodeParser.java) + [parser/MultiFileCodeParser.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/MultiFileCodeParser.java) | 两个策略实现 | 正则 |
| [parser/CodeParserExecutor.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/parser/CodeParserExecutor.java) | 解析分派器 | `switch` + default 抛异常 |
| [saver/CodeFileSaverTemplate.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/CodeFileSaverTemplate.java) | 保存模板方法骨架 | `final` 锁流程 |
| [saver/HtmlCodeFileSaverTemplate.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/HtmlCodeFileSaverTemplate.java) + [saver/MultiFileCodeFileSaverTemplate.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/MultiFileCodeFileSaverTemplate.java) | 两个具体保存器 | 只写差异 |
| [saver/CodeFileSaverExecutor.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/CodeFileSaverExecutor.java) | 保存分派器 | `switch` |
| [CodeParser.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/CodeParser.java) + [CodeFileSaver.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/CodeFileSaver.java)（`core/` 根下，`@Deprecated`） | 旧版工具类 | 对比新旧目录契约 |

## ★ 本阶段重点理解

1. **模板方法：`final` 锁死流程骨架，`abstract` 交出可变部分**。`CodeFileSaverTemplate.saveCode()` 是 `final` 的，把"校验 → 建目录 → 写文件 → 返回路径"这 4 步**锁死**，任何子类改不了顺序；`validateInput / saveFiles / getCodeType` 是 `abstract`，交给子类各自实现。**好处：流程一致性由基类保证，子类只关心差异，不会有人手滑改乱流程。**

2. **策略模式：`CodeParser<T>` 接口 + 两个实现**。HTML 解析成 `HtmlCodeResult`，多文件解析成 `MultiFileCodeResult`，泛型 `<T>` 让"同一种解析动作"能输出"不同类型的对象"。

3. **switch 分派：`CodeParserExecutor` 用静态常量持有 parser（无状态纯计算，不需要 IoC）**。`case HTML -> htmlCodeParser; case MULTI_FILE -> multiFileCodeParser; default -> 抛异常`。**default 必须显式抛异常**——因为以后新增一个生成类型（比如 `MINI_PROGRAM`），如果不补这个 switch，静默 fall through 会导致"用了错误的解析器却不报错"，比报错更难查。

4. **旧版 `@Deprecated` 是"目录契约演变"的活化石**：根下 `core/CodeParser.java / CodeFileSaver.java` 是旧版，用**雪花 ID 拼唯一目录名**（每次生成都新建一个目录）；新版用 `{type}_{appId}` 固定目录（同 appId 复用）。**这是"从不可定位 → 可定位"的演进**，看懂这个对比就懂为什么要定目录契约。

5. **解析器正则与提示词是强绑定契约**：`MultiFileCodeParser` 用正则从 `javascript`/`html`/`css` 代码块抠内容，这意味着**系统提示词里必须让模型用这几个围栏名**。改提示词的围栏名却没改正则，解析就会静默失败——这是 memory 里记死的坑。

## 🧠 默想卡

- **默写顺序**：`CodeParser<T>(接口) → 两个 Parser → CodeParserExecutor(switch) → CodeFileSaverTemplate(final模板) → 两个 Saver → CodeFileSaverExecutor(switch)`
- **三步自问**：① 模板方法里 `final` 为什么要锁 `saveCode`？② 分派器 `default` 为什么必须抛异常？③ `{type}_{appId}` 相比雪花 id 目录，好在哪？
- **高频写错点**：给初始化器里 `switch` 漏某个 case；正则围栏和提示词对不上；把"解析"和"落盘"混在一个类里。

## 具体代码示例（给"肉"）

**模板方法骨架**（[CodeFileSaverTemplate.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/CodeFileSaverTemplate.java)，节选）：

```java
public abstract class CodeFileSaverTemplate<T> {

    // 流程锁死，子类改不了顺序
    public final String saveCode(T codeResult, long appId) {
        validateInput(codeResult);                 // 1 校验（可被 override）
        String dir = buildUniqueDir(appId);        // 2 建目录（{type}_{appId}）
        saveFiles(codeResult, dir);                // 3 写文件（抽象，子类实现）
        return dir;                                // 4 返回目录
    }

    protected void validateInput(T codeResult) {}  // 钩子，可选覆盖
    protected abstract void saveFiles(T codeResult, String dir);
    protected abstract String getCodeType();       // 供 buildUniqueDir 拼目录
}
```

**分派器（策略模式 + switch）**（[CodeFileSaverExecutor.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/saver/CodeFileSaverExecutor.java)，节选）：

```java
public class CodeFileSaverExecutor {
    // 无状态纯计算，静态常量持有，不需要 Spring
    private static final Map<CodeGenTypeEnum, CodeFileSaverTemplate<?>> SAVER_MAP =
            Map.of(
                CodeGenTypeEnum.HTML, new HtmlCodeFileSaverTemplate(),
                CodeGenTypeEnum.MULTI_FILE, new MultiFileCodeFileSaverTemplate());

    public static String executeSaver(CodeGenTypeEnum codeGenType, Object codeResult, long appId) {
        CodeFileSaverTemplate<?> saver = SAVER_MAP.get(codeGenType);
        if (saver == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的生成类型: " + codeGenType);
        }
        return saver.saveCode(codeResult, appId);
    }
}
```

> 为什么 `Map.of` + get + null 判断，而不是 `switch`：**两者皆可**，这里是"静态 Map"版策略分派；L3 的 Factory 用的是 `switch` 版。本质都是"按类型选实现"，一个走查表、一个走分支，查表更易扩展。

## 调用链

```text
AiCodeGeneratorFacade
   → CodeParserExecutor.executeParser(type, 模型文本)   // 策略分派 → 得到 POJO
   → CodeFileSaverExecutor.executeSaver(type, POJO, appId)  // 模板方法 → 落盘
   → 返回 {type}_{appId} 目录
```

## 坑

| 坑 | 后果 |
|---|---|
| `saveCode` 不是 `final` | 子类覆盖后改乱 4 步顺序 |
| `default` 不抛异常 | 新增类型静默走错解析器，难排查 |
| 正则围栏与提示词不一致 | 解析出空内容，静默生成残缺文件 |
| 目录复用时旧文件不清 | 多文件模式下旧版本残留（memory 记死） |

## 验收

写测试：`CodeParserExecutor` 解析一段带 ````html```` 围栏的模型输出，拿到非空 `HtmlCodeResult`；`CodeFileSaverExecutor.executeSaver(HTML, result, 1L)` 后 `html_1/index.html` 存在；传一个不存在的类型抛 `BusinessException`。

---

# L9 · 生成核心编排 Facade + Vue 流式消息协议

> 一句话目标：搞清楚**同步生成、Flux 流式、TokenStream 工具流式这三条路，是怎么被 `AiCodeGeneratorFacade` 统一成"一个入口、一套落盘"的**，以及 Vue 工程那种"模型一边写文件一边推消息"的 JSON 流协议长什么样。

## 文件清单表

| 文件 | 职责 | 盯住 |
|---|---|---|
| [core/AiCodeGeneratorFacade.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java) | 门面编排 | `processTokenStream` |
| [ai/model/message/StreamMessage.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/model/message/StreamMessage.java) | 流消息基类 | `type` 字段 |
| [ai/model/message/StreamMessageTypeEnum.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/model/message/StreamMessageTypeEnum.java) | 消息类型枚举 | 三种 |
| [ai/model/message/AiResponseMessage.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/model/message/AiResponseMessage.java) + [ToolRequestMessage.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/model/message/ToolRequestMessage.java) + [ToolExecutedMessage.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/model/message/ToolExecutedMessage.java) | 三种具体消息 | 各自字段 |
| [core/builder/VueProjectBuilder.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/builder/VueProjectBuilder.java) | Vue 构建 | 超时 + dist 校验 |

## ★ 本阶段重点理解

1. **Facade 是"门面"，对 Service 屏蔽底层复杂度**。上层 `AppService` 只需要调 `facade.generateAndSaveCode(...)`，不用知道后面是"工厂建 AiServices → 解析 → 保存 → 构建"这么多步。**Facade 组合了 factory/parser/saver/builder 四个组件，把多组件协作收敛成一个接口。**

2. **Flux 流式：`doOnNext` 收集 + `doOnComplete` 解析落盘**。为什么不在 `doOnNext` 里逐段落盘？因为**只有流完全结束后，代码才是完整的**——边收边写会写出半截文件。`doOnNext` 只负责把 token 拼进 `StringBuilder`，`doOnComplete` 才把完整文本交给解析器落盘。

3. **TokenStream → Flux 的桥接：`Flux.create(sink)`**。Vue 的 `generateVueProjectCodeStream` 返回的是 `TokenStream`（回调风格），但下游要 `Flux<String>`（Reactor 风格）给 SSE 用。`Flux.create` 把"回调事件"翻译成"Reactor 信号"：每次 `onPartialResponse/onPartialToolExecutionRequest` 就 `sink.next(消息)`，`onCompleteResponse` 时 `sink.complete()`。**这是把推模式回调适配成响应式流的经典桥接。**

4. **为什么有 `ToolExecutedMessage` 且要存 `result`**：Vue 生成时，模型"调 write_file 工具 → 得到成功结果 → 再决定下一步"。**这个"工具执行结果"必须回传给模型**（LangChain4j 内部会拼进下轮消息），同时也要推给前端让用户看到"模型正在写哪个文件"。所以消息协议里专门有 `ToolExecutedMessage(id, name, arguments, result)`。

5. **Vue 构建要"超时 + 强制杀 + 产物存在性校验"**：`npm install` 给 300s、`npm run build` 给 180s，超时 `destroyForcibly()` 强杀；构建完还要校验 `dist/` 目录真的生成了。**因为"进程退出码 0"不代表产物可用**——必须显式验证构建产物存在性（memory 里"构建返回值被静默丢弃导致预览 404"的教训）。

## 🧠 默想卡

- **默写顺序**：`Facade(双入口) → processCodeStream(Flux) → processTokenStream(TokenStream→Flux) → VueProjectBuilder(build)`
- **三步自问**：① 为什么 `doOnComplete` 才落盘，而不是 `doOnNext`？② `Flux.create(sink)` 桥接了什么？③ 三种消息类型各代表什么时刻？
- **高频写错点**：在 `doOnNext` 里就落盘写成半截文件；忘了 `ToolExecutedMessage` 存 result，模型下轮失忆。

## 具体代码示例（给"肉"）

**TokenStream → Flux 桥接**（[AiCodeGeneratorFacade.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/AiCodeGeneratorFacade.java)，节选）：

```java
private Flux<String> processTokenStream(TokenStream tokenStream, long appId, CodeGenTypeEnum codeGenType) {
    return Flux.create(sink -> {
        AtomicReference<String> dirRef = new AtomicReference<>();
        tokenStream
            .onPartialResponse(part ->                        // AI 吐字：推一句话
                sink.next(wrapMessage(AI_RESPONSE, part)))
            .onPartialToolExecutionRequest(req ->             // 要调工具：推"准备写文件"
                sink.next(wrapMessage(TOOL_REQUEST, req)))
            .onToolExecuted(res ->                            // 工具执行完：推"写好了"
                sink.next(wrapMessage(TOOL_EXECUTED, res)))
            .onCompleteResponse(resp -> {                     // 全部结束：解析+落盘+构建
                String dir = saveCode(...);
                VueProjectBuilder.buildProjectAsync(dir);     // 异步 npm build
                dirRef.set(dir);
                sink.complete();
            })
            .onError(sink::error)
            .start();                                         // 触发开始
    });
}
```

**三种消息的 `type` 字段**（[StreamMessageTypeEnum.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/model/message/StreamMessageTypeEnum.java)）：

```java
public enum StreamMessageTypeEnum {
    AI_RESPONSE("ai_response"),     // 模型正常吐字（代码/文本）
    TOOL_REQUEST("tool_request"),   // 模型要调用某个工具
    TOOL_EXECUTED("tool_executed"); // 某个工具执行完成
}
```

**VueProjectBuilder 的构建校验**（[VueProjectBuilder.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/core/builder/VueProjectBuilder.java)，关键片段）：

```java
// 超时强杀 + 产物存在性校验
boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
if (!finished) { process.destroyForcibly(); return false; }
...
File distDir = new File(projectDir, "dist");
if (!distDir.exists() || !distDir.isDirectory()) {
    log.error("构建完成但 dist 目录未生成"); return false;   // 退出码 0 也不代表成功
}
```

## 调用链

```text
AppService → AiCodeGeneratorFacade
   ├─ 同步/HTML、MULTI_FILE → Flux<String>（doOnNext 拼 + doOnComplete 落盘）
   └─ VUE → TokenStream → Flux.create 桥接 → 逐事件推 SSE
                         └─ onCompleteResponse → 解析落盘 → VueProjectBuilder 异步 build
```

## 坑

| 坑 | 后果 |
|---|---|
| `doOnNext` 就落盘 | 写出半截文件 |
| 退出码 0 就认为构建成功 | 没验证 `dist/`，预览 404 |
| `onCompleteResponse` 里同步 build | 阻塞 SSE 完成回调 |
| 忘了 `sink.complete()` | 前端永远等不到流结束 |

## 验收

写测试：给一段 `TokenStream` 的 mock，验证 `processTokenStream` 依次 emit `ai_response→tool_request→tool_executed→complete`；最终目录下 `vue_project_{appId}` 存在且含源码文件。

---

# L10 · 可观测性（监听器 + 监控指标 + 上下文传递）

> 一句话目标：搞清楚**LLM 调用这种"请求和响应不在同一线程、又有耗时和 Token 要记"的场景，是怎么用 `ChatModelListener` + `ThreadLocal` + Micrometer 完整观测的**。

## 文件清单表

| 文件 | 职责 | 盯住 |
|---|---|---|
| [MonitorContext.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/monitor/MonitorContext.java) | 监控上下文（userId/appId） | `Serializable` |
| [MonitorContextHolder.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/monitor/MonitorContextHolder.java) | ThreadLocal 持有上下文 | set/get/clear |
| [AiModelMonitorListener.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/monitor/AiModelMonitorListener.java) | 请求/响应/错误监听 | `attributes` 跨线程 |
| [AiModelMetricsCollector.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/monitor/AiModelMetricsCollector.java) | Micrometer 指标落库 | `computeIfAbsent` 缓存 |

## ★ 本阶段重点理解

1. **`ChatModelListener` 是 LangChain4j 给 LLM 调用埋的"切面"**：`onRequest`（发请求前）/ `onResponse`（收到响应后）/ `onError`（出错后）三个钩子，框架在每次模型调用前后自动回调。**等价于你在"调大模型"这个动作上写了个 `@Around`，但不用碰业务代码。**

2. **ThreadLocal + `attributes` 双通道传上下文**：`MonitorContextHolder` 用 `ThreadLocal` 存当前请求的用户/appId；但**模型"请求"和"响应"可能不在同一个线程**（尤其异步/流式），所以 `onRequest` 里把 `monitorContext` 和 `startTime` 塞进 `requestContext.attributes()`，`onResponse` 再从 `attributes` 取回来。**ThreadLocal 管"本线程"，attributes 管"跨线程"**。

3. **Micrometer 是"指标的天平"**：`Counter`（计数：请求次数、token 量、错误次数）+ `Timer`（耗时）。Metric 名 + tag（`user_id`/`app_id`/`model_name`/`status`）构成一条时间序列，最后被 `/actuator/prometheus` 暴露给 Prometheus 抓取。**这是"业务可观测"的标准姿势，不是打日志。**

4. **`computeIfAbsent` 缓存指标是为了"同一名字只注册一次"**：Micrometer 里**同名+同 tag 的 Counter 重复 register 会抛异常**，所以必须缓存已注册的指标。`ConcurrentMap` + `computeIfAbsent` 保证并发安全和"只建一次"。

## 🧠 默想卡

- **默写顺序**：`MonitorContext(载体) → MonitorContextHolder(ThreadLocal) → AiModelMonitorListener(三钩子) → AiModelMetricsCollector(指标)`
- **三步自问**：① 为什么 `onResponse` 不从 ThreadLocal 读，而是从 `attributes` 读？② 为什么指标要 `computeIfAbsent` 缓存？③ 三种指标（次数/token/耗时）各用什么 Micrometer 类型？
- **高频写错点**：响应线程不同却去 ThreadLocal 里拿上下文（拿到的可能是别人的/null）；同名指标重复 register 抛异常；`onError` 里没判空就 `.getUserId()` 导致 NPE。

## 具体代码示例（给"肉"）

**监听器的三钩子**（[AiModelMonitorListener.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/monitor/AiModelMonitorListener.java)，节选）：

```java
@Override
public void onRequest(ChatModelRequestContext requestContext) {
    requestContext.attributes().put("request_start_time", Instant.now()); // 死活开始时间
    MonitorContext ctx = MonitorContextHolder.getContext();               // 本线程上下文
    requestContext.attributes().put("monitor_context", ctx);              // 塞进 attributes 供跨线程取
    aiModelMetricsCollector.recordRequest(ctx.getUserId(), ctx.getAppId(), modelName, "started");
}

@Override
public void onResponse(ChatModelResponseContext responseContext) {
    Map<Object,Object> attributes = responseContext.attributes();
    MonitorContext ctx = (MonitorContext) attributes.get("monitor_context"); // 跨线程取回
    aiModelMetricsCollector.recordRequest(ctx.getUserId(), ctx.getAppId(), modelName, "success");
    recordResponseTime(attributes, ...);      // Instant.now() - startTime
    recordTokenUsage(responseContext, ...);   // input/output/total
}
```

**指标收集器的缓存注册**（[AiModelMetricsCollector.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/monitor/AiModelMetricsCollector.java)，节选）：

```java
public void recordRequest(String userId, String appId, String modelName, String status) {
    String key = String.format("%s_%s_%s_%s", userId, appId, modelName, status);
    Counter counter = requestCountersCache.computeIfAbsent(key, k ->
        Counter.builder("ai_model_requests_total")
            .tag("user_id", userId).tag("app_id", appId)
            .tag("model_name", modelName).tag("status", status)
            .register(meterRegistry));         // 同名同 tag 只注册一次
    counter.increment();
}
```

## 调用链

```text
AppService 设置 MonitorContextHolder.setContext(用户/appId)
   → 调 LLM → LangChain4j 触发 AiModelMonitorListener
   ├─ onRequest：记开始时间 + 塞 attributes + 记 "started"
   ├─ onResponse：取回上下文 → 记 "success" + 响应耗时 + Token
   └─ onError：记 "error" + 错误信息 + 耗时
   → 指标经 /actuator/prometheus 暴露 → Prometheus 采集
```

## 坑

| 坑 | 后果 |
|---|---|
| 响应线程里从 ThreadLocal 拿上下文 | 拿到别人的上下文或 null，指标错乱 |
| 同名 Counter 重复 `register` | 抛 `IllegalArgumentException`（metric 已存在） |
| 用 `errorMessage` 做 tag | 高基数标签爆炸，无穷多个时间序列 |
| `onError` 不看 `attributes` 而从 ThreadLocal 读 | 异步场景 NPE |

## 验收

写测试：mock 一个 `ChatModelListener` 的 request/response context，走一遍 `onRequest → onResponse`，断言 `ai_model_requests_total` 的 `status=success` 计数 +1，且 `Timer` 记录了 >0 的耗时。

---

## 附：这 4 个模块串起来 = 一条完整链路

```text
用户发需求
   ▼
AppService ── MonitorContextHolder.setContext(userId, appId)   ← L10 观测起点
   ▼
AiCodeGeneratorFacade（统一入口）                              ← L9 编排
   ├─ 路由分型（HTML/MULTI_FILE/VUE）                           ← L6.5
   ├─ AiServices 动态代理调模型的生成/流式                        ← L3/L4
   │     ├─ 模型要写文件 → 调 ai/tools/*（L7 工具体系）
   │     └─ 每步 LLM 调用 → AiModelMonitorListener（L10 观测）
   ├─ CodeParserExecutor 解析（L8 策略分派）
   └─ CodeFileSaverExecutor 落盘（L8 模板方法）
       └─ Vue 工程再 VueProjectBuilder 异步 build（L9）
```

这一条链路把 L7~L10 四个"散装模块"重新焊回主流程，反过来也说明它们不是孤立存在的——**工具（L7）服务于生成，解析/落盘（L8）接管生成结果，Facade（L9）负责调度，可观测性（L10）全程埋点。**