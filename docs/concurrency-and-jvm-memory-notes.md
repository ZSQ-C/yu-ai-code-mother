# yu-ai-code-mother 学习笔记 · 并发与 JVM 内存模型专题

> 会话范围：langgraph4j 模块精讲 → 类串联实战 → 状态管理 → static/new 标准 → AI 并发治理 → JVM 内存模型 → 可运行实验
> 项目：`yu-ai-code-mother`（Spring Boot 3.5.4 + JDK 21 + LangChain4j 1.1.0 + MyBatis-Flex + LangGraph4j）
> 配套实验目录：`experiments/`

---

## 目录

- [第一部分 · 我的问题全清单（索引）](#第一部分--我的问题全清单索引)
- [第二部分 · langgraph4j 模块精讲](#第二部分--langgraph4j-模块精讲)
- [第三部分 · 走通一次完整请求（把类串起来）](#第三部分--走通一次完整请求把类串起来)
- [第四部分 · WorkflowContext 与图状态管理](#第四部分--workflowcontext-与图状态管理)
- [第五部分 · static 与 new 的执行标准](#第五部分--static-与-new-的执行标准)
- [第六部分 · AI 并发调用：项目现状 vs 企业级](#第六部分--ai-并发调用项目现状-vs-企业级)
- [第七部分 · JVM 内存模型（本次重点）](#第七部分--jvm-内存模型本次重点)
- [第八部分 · 缓存复用 vs 每次 new](#第八部分--缓存复用-vs-每次-new)
- [第九部分 · 并发核心概念补充](#第九部分--并发核心概念补充)
- [第十部分 · 实验文件与实测数据](#第十部分--实验文件与实测数据)
- [第十一部分 · 项目锚点速查表](#第十一部分--项目锚点速查表)
- [第十二部分 · 心法 & Checklist](#第十二部分--心法--checklist)
- [附录 · 术语表](#附录--术语表)

---

## 第一部分 · 我的问题全清单（索引）

### 1.1 今晚（本次会话）的问题

| # | 我的问题（原话精简） | 对应章节 |
| --- | --- | --- |
| Q1 | `langgraph4j` 这个模块详细讲解，全面细致分块输出 | 第二部分 |
| Q2 | 上面说的有点抽象，小白不友好；通过走通一遍完整实例把类串联起来介绍，精准定位每个类的作用 | 第三部分 |
| Q3 | `WorkflowContext` 内部数据结构是咋样的？它是不是就是全图状态？针对实现以及传输中都是怎么改变状态的 | 第四部分 §1-3 |
| Q4 | 还有什么情况需要静态方法？什么时候需要用 `new`？有执行标准么 | 第五部分 |
| Q5 | 项目中的 AI 并发调用问题是怎么解决的？这种做法相比当前企业落地项目如何 | 第六部分 |
| Q6 | 实例字段是可以创建多次供多线程使用保障线程安全？静态字段单独一份地址且全局无法复制？它们位置都在堆中么？实例和静态字段分别指什么？能包括项目中大部分代码的地址么 | 第七部分 §3 |
| Q7 | 用户聊一次完整流程：创建了哪些线程、数据都保存到哪里（线程/内存视角）；用户信息保存到哪、地址在堆还是栈、从数据库取的数据等等 | 第三部分 §3 + 第七部分 §8 |
| Q8 | 代码存在方法区，方法区是独立于堆栈的另一块地址么？类的实例是指向对应方法区代码么？Map 住在堆里，其它结构也是堆中创建空间保存地址、栈中变量指向该地址么 | 第七部分 §4 |
| Q9 | 缓存制造了共享还是没理解：不缓存每次都会 new 一个？缓存就只有 1 份？每个用户 new 一个自己的？不缓存的话每次打开应用会再 new 一个而不继承上次地址？缓存会让下次打开指向上次地址么 | 第八部分 |
| Q10 | 类加载是指项目初始时所有 class 及其它代码么？类被 new 成实例就在堆中指向方法区对应类代码片段么？为什么不能访问实例成员（`this` 不存在啥意思、`this` 指什么）？是因为方法区不能访问堆中数据么 | 第七部分 §5-6 |
| Q11 | 把上面内容整理成可运行的实验文件 | 第十部分 |

### 1.2 前置会话（同一学习线）的问题

| # | 我的问题 | 对应章节 |
| --- | --- | --- |
| P1 | 怎么理解无状态和有状态？作为小白讲透 | 第九部分 §1 |
| P2 | 两个线程同时碰它会不会互相破坏状态？异步不是两个线程么，为什么两个线程去截图会相互破坏？同步/异步怎么解决？每分钟几十 → 线程池；每分钟几万 → MQ + 写库，怎么理解实现 | 第九部分 §4 |
| P3 | 遇到类似功能如何判断该用异步还是全局唯一就行？如果异步有哪些方法，落地最多的是哪些？静态导致并发不安全详细讲解 | 第九部分 §2、§5 |

---

## 第二部分 · langgraph4j 模块精讲

### 2.1 模块定位（结论先行）

| 判断 | 依据 |
| --- | --- |
| **不参与主链路** | `fail` 自环回 `code_generator`，而 `CodeGeneratorNode` 里 `Long appId = 0L` 是硬编码 |
| **教学/实验性质** | 配置类自嘲注释「实际上很难使用」 |
| **复现时建议最后看** | 它依赖的 `AiCodeGeneratorFacade`、`VueProjectBuilder`、`CosManager` 全是主链路产物 |

**模块共 37 个 Java 文件**，用 glob 实测确认。

`CodeGenWorkflow` 的流程：

```
START → image_collector → prompt_enhancer → router → code_generator → code_quality_check
                                                                          │
                                    ┌─────────┬──────────────┬───────────┘
                              "build"│  "skip_build"│        "fail"│
                                    ▼         ▼                 ▼
                            project_builder  END          code_generator（自环重试）
```

### 2.2 核心概念对照（LangGraph4j）

| 概念 | 类比 | 本项目实例 |
| --- | --- | --- |
| `StateGraph` | 流程图骨架 | `new MessagesStateGraph<String>()` |
| Node | 图上一个工序 | `"code_generator"` |
| Edge | 固定传送带 | `addEdge("router", "code_generator")` |
| ConditionalEdge | 岔路口 + 交警 | `routeAfterQualityCheck` |
| State | 传送带上的托盘 | `MessagesState<String>` |
| `CompiledGraph` | 编译后可执行的图 | `workflow.stream(...)` |

`START` / `END` 是框架内置虚拟节点。

### 2.3 三套图横向对比（面试高频）

| 维度 | 图1 串行 | 图2 并发 | 图3 子图 |
| --- | --- | --- | --- |
| 文件 | `CodeGenWorkflow` | `CodeGenConcurrentWorkflow` | `CodeGenSubgraphWorkflow` |
| 并发在哪 | **节点内部**（`CompletableFuture.allOf`） | **图调度层**（线程池并行节点） | 同图2，粒度是"子图" |
| 并行度控制 | 受 `ForkJoinPool.commonPool` 限制 | `corePool=10 / max=20 / 队列=100` | 同图2 |
| 状态是否隔离 | 不需要（单线程内） | **多分支共享同一 state** | 同图2 |
| 可读性 | 最简单 | 一眼看出并行 | 图最干净，抽象层数多 |
| 复用性 | 差 | 差 | **好** |
| 失败可观测性 | 差（`allOf` 里异常易被吞） | **好**（每分支独立节点/日志） | 好 |

**关键结论**：图1 的并发是"黑盒"（节点内部一次开 4 类任务，异常 catch 完就吞）；图2 把 4 个分支变成 4 个独立节点，**每一步都有 `NodeOutput`、可单独打日志、可单独重试**。

> **图编排相对手写并发的核心收益：把"不可观测的并发"变成"可观测的图"。**

**图2 的并行配置**（`addParallelNodeExecutor("image_plan", pool)`语义：当调度器发现某节点有多个出边时，把这些分支丢进该线程池并发跑）：

```java
ExecutorService pool = ExecutorBuilder.create()
        .setCorePoolSize(10).setMaxPoolSize(20)
        .setWorkQueue(new LinkedBlockingQueue<>(100))
        .setThreadFactory(ThreadFactoryBuilder.create().setNamePrefix("Parallel-Image-Collect").build())
        .build();
RunnableConfig runnableConfig = RunnableConfig.builder()
        .addParallelNodeExecutor("image_plan", pool).build();
```

### 2.4 主干节点逐个拆解

| 节点 | 读 | 干 | 写 | 失败策略 |
| --- | --- | --- | --- | --- |
| `ImageCollectorNode` | `originalPrompt` | 先取计划，再 `CompletableFuture` 并发搜图 | `imageList` | 异常 catch 后吞掉 |
| `PromptEnhancerNode` | `originalPrompt` + `imageList` | **纯字符串拼接，不调 AI** | `enhancedPrompt` | — |
| `RouterNode` | `originalPrompt` | `AiCodeGenTypeRoutingService` 判断类型 | `generationType` | **降级为 `HTML`** |
| `CodeGeneratorNode` | `enhancedPrompt` 或 `qualityResult` | `AiCodeGeneratorFacade.generateAndSaveCodeStream` + `blockLast(10min)` | `generatedCodeDir` | 不 catch |
| `CodeQualityCheckNode` | `generatedCodeDir` | 读盘 → 拼大字符串 → AI 质检 | `qualityResult` | **异常判定为"通过"** |
| `ProjectBuilderNode` | `generatedCodeDir` | `VueProjectBuilder.buildProject` | `buildResultDir` | 抛异常又立刻吞掉 |

**`PromptEnhancerNode` 的心法示例**：追加的是「## 可用素材资源 + 图片 URL 列表」——**确定性任务不要交给 LLM**。它同时支持 `imageList`（结构化列表）和 `imageListStr`（字符串），但只有 `imageList` 非空才走列表分支，而全模块没有任何地方给 `imageListStr` 赋值 → **遗留字段**。

**`CodeGeneratorNode` 的重试回灌**：`buildUserMessage` 在"质检失败"时用**错误信息顶替**原提示词（不是追加）——避免长提示词稀释错误权重。

### 2.5 质检重试回路（最值钱的设计）

```java
.addConditionalEdges("code_quality_check",
        edge_async(this::routeAfterQualityCheck),
        Map.of(
                "build", "project_builder",   // 质检通过且需要构建
                "skip_build", END,            // 质检通过但跳过构建
                "fail", "code_generator"      // 质检失败，重新生成
        ))
```

| 返回值 | 目标 | 触发条件 | 含义 |
| --- | --- | --- | --- |
| `fail` | `code_generator` | `isValid` 为 false 或 null | 带错误重生成 |
| `build` | `project_builder` | `generationType == VUE_PROJECT` | Vue 需 `npm run build` |
| `skip_build` | `END` | `HTML` / `MULTI_FILE` | 不需构建 |

**核心结论**：`"fail" → "code_generator"` 是一条**自环**，等价于「重试框架」——**重试不需要 retry 框架，一条边就够了**。

**⚠️ 路由指挥员是普通私有方法，不是 Node**：它返回"路牌名称"（`"fail"`/`"build"`/`"skip_build"`），由 `Map.of(...)` 翻译成真实的下一个节点。它**不写状态**——只管指路，不管运货。分派逻辑与业务逻辑分离。

### 2.6 并发节点与聚合（图2/图3 专属）

**`ImageCollectionPlan` 用 `record` 定义 4 种 task**：

```java
public record ImageSearchTask(String query) {}
public record IllustrationTask(String query) {}
public record DiagramTask(String mermaidCode, String description) {}
public record LogoTask(String description) {}
```

> record 是不可变值对象 → **并发场景下天然线程安全**。

**4 个 Collector 的真相**：节点叫 `Collector`、注释写「并发收集」，但**节点内部是 `for` 串行循环**。真正的并发在**节点之间**（4 个节点被线程池并行调度）。

**⚠️ 这是图级并发的固有粒度上限**：只能并行到"节点"这一层，节点内部的多个 task 还得自己叠 `CompletableFuture`（如图1 那样）。**图1 与图2 是"互补的两种并发粒度"，不是替代关系。**

**`ImageAggregatorNode` 存在的意义**：把 4 个并行分支的"信箱"（`contentImages`/`illustrations`/`diagrams`/`logos`）合并成统一 `imageList`，让下游 `PromptEnhancerNode` **不需要知道并发结构** —— 这叫**并发屏蔽**。

**为什么 4 个分支要各写各的信箱？** 若都往同一个 `imageList` 里 `addAll`，就是 4 个线程抢同一个 `ArrayList` → 数据丢失。**各写各的信箱、最后单线程合并 = 经典的"分片-合并（fork-join）"套路。**

而 `ImageAggregatorNode` **天然安全**，因为 4 条入边都是它，图的调度器保证 4 个 Collector 全部完成后它才执行 —— **join 语义由拓扑自动实现**。

### 2.7 工具层（4 个 `@Tool`）

| 工具 | 数据源 | 输入 | 输出 |
| --- | --- | --- | --- |
| `ImageSearchTool` | Pexels API | `query` | 12 张 `medium` 图 |
| `UndrawIllustrationTool` | undraw 站点 JSON 接口 | `query` | 12 张 SVG |
| `MermaidDiagramTool` | 本地 `mmdc` CLI + COS | `mermaidCode`, `description` | 1 张 SVG 的 COS URL |
| `LogoGeneratorTool` | 阿里云通义万相 | `description` | 1 张 512×512 图 |

**三个细节**：

1. `@Tool` 的字符串是**工具描述**，`@P` 的字符串是**参数描述** —— 这两个直接决定模型能否正确调用。**写提示词和写工具描述是同一种技能。**
2. **undraw 的 URL 里硬编码了 Next.js 的 buildId**（`mMWmJSt23qpgo8cLTD_pB`）—— 站点重构后接口直接 404，插画功能**静默失效**。对比 Pexels 用官方稳定 API 就没这个问题。
3. `MermaidDiagramTool` 是唯一"落盘 + 上传"的工具：`mmdc` 转 SVG → 上传 COS → 删除临时文件，并用 `RandomUtil.randomString(5)` 避免并发文件名冲突。

### 2.8 AI 服务与 Factory

| 接口 | 方法 | 返回类型 | 是否挂 tools |
| --- | --- | --- | --- |
| `ImageCollectionPlanService` | `planImageCollection` | `ImageCollectionPlan`（结构化输出） | 否 |
| `ImageCollectionService` | `collectImages` | `String` | 是（4 个全挂） |
| `CodeQualityCheckService` | `checkCodeQuality` | `QualityResult`（结构化输出） | 否 |

**接口没有实现类，是 `AiServices.builder(...)` 在运行时动态生成代理**，把方法调用翻译成一次 LLM 请求。

关键点：

- `@SystemMessage(fromResource = "prompt/xxx.txt")` → **提示词外置成 `.txt`**：改动频率远高于代码，外置后改提示词不用重新编译。
- 返回 POJO 的接口（`ImageCollectionPlan` / `QualityResult`）会自动让模型输出 JSON 再反序列化 —— **结构化输出**，比手写 JSON 解析可靠得多。
- **`ImageCollectionService.collectImages()` 全模块没有任何地方调用** → 死代码，容易误导读者以为走的是 Agent 路线。

### 2.9 入口控制器与三种输出方式

| 方式 | 方法 | 返回 | 阻塞？ | 适合 |
| --- | --- | --- | --- | --- |
| 同步 | `executeWorkflow` | `WorkflowContext` | 全程阻塞 HTTP 线程 | 调试/脚本 |
| Flux | `executeWorkflowWithFlux` | `Flux<String>` | 非阻塞（WebFlux 语义） | 响应式背压 |
| SseEmitter | `executeWorkflowWithSse` | `SseEmitter` | 虚拟线程上跑图 | **浏览器实时进度** |

**Flux 版三个要点**：

1. **`Thread.startVirtualThread` 跑图** —— 图的 `stream` 是阻塞迭代器，必须搬到别的线程，否则阻塞调用方。
2. `sink.next(...)` 是线程安全的（`FluxSink` 内部有同步）。
3. **异常处理完整**：`log.error` + `sink.next(workflow_error)` + `sink.error(e)` —— **先发错误事件再 error**，让前端能收到具体错误信息。

SSE 格式：`"event: " + eventType + "\ndata: " + json + "\n\n"`（**两个换行结尾**）。

**Flux 版与 SSE 版逻辑几乎逐行相同 → 重复代码**，理想做法是抽一个 `Consumer<String>` 参数复用。

### 2.10 langgraph4j 坑清单（均有源码依据）

| # | 坑 | 影响 |
| --- | --- | --- |
| 1 | `appId` 硬编码 `0L` | 所有生成结果落在 `xxx_0/`，多用户互相覆盖 |
| 2 | 三套图路由逻辑不一致（图1 判 `HTML`/`MULTI_FILE`，图2/3 只判 `VUE_PROJECT`） | 同一业务判断两套写法，新增类型行为分裂 |
| 3 | 质检 `fail → code_generator` 无次数上限（未配 `recursionLimit`） | LLM 反复不过 → 无限循环烧 token |
| 4 | `image_plan` 失败后仍无条件触发 4 分支 | 静默产出无图网站，用户无感知 |
| 5 | `CompletableFuture.supplyAsync` 无超时（用默认 `ForkJoinPool`） | 外部 API 卡死 → `join()` 永久阻塞 |
| 6 | `ProjectBuilderNode` 抛异常又立刻吞掉 | `buildResultDir` 语义混乱（`dist` 还是源码目录？） |
| 7 | 并发 4 分支共写 `currentStep` | 进度显示"最后一步"是随机的 |
| 8 | `ImageCollectionService` 从未被调用 | 死代码 |
| 9 | `imageListStr` 只读不写 | 遗留字段 |

---

## 第三部分 · 走通一次完整请求（把类串起来）

### 3.1 主线请求

```
POST /api/workflow/execute?prompt=创建一个鱼皮的个人博客网站
```

`WorkflowSseController` 只做一件事：把 `prompt` 交给 `CodeGenWorkflow`。

### 3.2 全景时序图

```
浏览器
  │  POST /workflow/execute?prompt=...
  ▼
[0] WorkflowSseController      门卫：转交 prompt
  ▼
[1] CodeGenWorkflow            工头：焊流水线（addNode / addEdge / compile）
  ▼
[2] WorkflowContext 上托盘      装 originalPrompt + currentStep="初始化"
  ▼
    workflow.stream(...)  ← 传送带启动
  ▼
[3] image_collector    → 托盘上有 imageList
[4] prompt_enhancer    → 托盘上有 enhancedPrompt
[5] router             → 托盘上有 generationType
[6] code_generator     → 托盘上有 generatedCodeDir
[7] code_quality_check → 托盘上有 qualityResult
  ▼
[8] 岔路口（条件边）
   ├─"fail"       → 回 [6]（带错误重生成）
   ├─"skip_build" → END
   └─"build"      → [9] project_builder → END
  ▼
返回 WorkflowContext（转 JSON 塞回 HTTP 响应）
```

### 3.3 后台名单：类 → 工位

| 类 | 岗位 | 一句话职责 |
| --- | --- | --- |
| `WorkflowSseController` | 门卫 | 收 HTTP 请求，转交 |
| `CodeGenWorkflow` | **工头** | 画图纸、编译图纸、启动传送带 |
| `WorkflowContext` | **托盘** | 携带全部数据在工位间传递 |
| `MessagesState<String>` | 传送带 | 托盘的外壳容器 |
| `ImageCollectorNode` | 图片总工位 | 一次搞定所有图片 |
| `ImageCollectionPlanService` | 图片规划员 | AI 决定"要收集哪几种图" |
| `ImageCollectionPlan` | 规划书 | 规划员产出的 task 清单 |
| `ImageSearchTool` 等 4 个 | 4 个搜图工人 | 各自去外部拿图 |
| `ImageResource` | 一张图 | 图片的统一格式（**解耦的关键**） |
| `PromptEnhancerNode` | 提示词加工工位 | 把图 URL 拼进提示词 |
| `RouterNode` | 分流决策工位 | AI 决定生成哪种类型 |
| `CodeGeneratorNode` | 代码生成工位 | 调 AI 生成代码并落盘 |
| `CodeQualityCheckNode` | 质检工位 | 读文件交给 AI 评估 |
| `CodeQualityCheckService` | 质检员 | 产出质检报告 |
| `QualityResult` | 质检报告 | 通过与否 + 错误 + 建议 |
| `ProjectBuilderNode` | 构建工位 | Vue 项目跑 npm build |

### 3.4 每个类只回答四个问题（示例）

**第 3 站 `ImageCollectorNode`**：

| 四问 | 答案 |
| --- | --- |
| 谁调我 | 传送带从 `START` 开过来 |
| 我读什么 | `context.getOriginalPrompt()` |
| 我写什么 | `imageList`（20+ 张 `ImageResource`）、`currentStep="图片收集"` |
| 没我会怎样 | 生成的网站一张图都没有，纯文字页面 |

**它内部两步**：

- **3-A 先问规划员**：`planService.planImageCollection(originalPrompt)` → **AI 现场生成规划书**（"先让 LLM 出计划，再由确定性代码执行计划" = Planning 模式）
- **3-B 按规划书派人搜图**：4 类 task 各自 `CompletableFuture.supplyAsync` 并发调用对应 Tool

**为什么必须统一成 `ImageResource`？** 下一站（提示词加工）不认识 Pexels、也不认识通义万相，它只想看到"一张图 = 分类 + 描述 + URL"。**统一格式 = 解耦**；否则下游要为每种图源写一个 if，每加图源就要改下游（这叫"污染"）。

### 3.5 数据变形记（同一份东西在各站的样子）

| 站 | 核心数据 | 类型 |
| --- | --- | --- |
| 0 | `"创建一个鱼皮的个人博客网站"` | `String` |
| 2 | `WorkflowContext{originalPrompt, currentStep="初始化"}` | `WorkflowContext` |
| 3-A | `ImageCollectionPlan{contentImageTasks=[...]}` | `ImageCollectionPlan`（**AI 产出**） |
| 3-B | 20+ 张图 | `List<ImageResource>` |
| 4 | `"原始提示词\n\n## 可用素材资源\n- 内容图片：..."` | `String` |
| 5 | `CodeGenTypeEnum.MULTI_FILE` | 枚举（**AI 产出**） |
| 6 | `e:\...\tmp\code_output\multi_file_0` + 磁盘文件 | `String` + 磁盘 |
| 7 | `QualityResult{isValid=false, errors=[...]}` | `QualityResult`（**AI 产出**） |
| 8 | `"fail"` / `"build"` / `"skip_build"` | `String`（路牌） |
| 9 | `e:\...\tmp\code_output\vue_project_0\dist` | `String` |

**规律**：

```
字符串 → 结构化对象 → 又变字符串 → 枚举 → 磁盘文件 → 结构化报告 → 路牌字符串
```

**托盘 `WorkflowContext` 就是承载所有形态变化的容器** —— 这就是它字段这么多、每种中间产物都要留一个字段的原因。

### 3.6 ⭐ 一次完整聊天的内存追踪（线程 / 内存视角）

| 时刻 | 发生了什么 | 数据 | 住在哪 | 几份 |
| --- | --- | --- | --- | --- |
| 1 | Tomcat 从线程池借出**工作线程 T-1** | `Thread` 对象（含 `threadLocals` 字段） | **堆**（！） | 每请求一个 |
| 2 | 解析请求 | `HttpServletRequest` | **堆** | 每请求一份 |
| 3 | 登录校验：从 **HttpSession** 取 `User` | Session 对象 | **堆**（容器管理） | 每会话一份 |
| 4 | **再查一次库**：`this.getById(userId)` | 新的 `User` 对象 | **堆** | 每请求一份 |
| 5 | 方法里存引用 `User loginUser` | 局部变量 | **栈**（存地址） | 每请求一份 |
| 6 | 拿 AI 服务（Caffeine 命中） | `AiCodeGeneratorService` | **堆** | 每 `appId` 一份 |
| 7 | 该服务持有的记忆 | `MessageWindowChatMemory` 里的 `List<ChatMessage>` | **堆** | 每 `appId` 一份 |
| 8 | 落库用户消息 | `ChatHistory` 实体 → MySQL | 对象在**堆**，数据落**磁盘** | 每消息一份 |
| 9 | 记忆快照 | `RedisChatMemoryStore` 写 Redis | Redis **服务端内存** | 每 appId 一份 |
| 10 | 调 LLM | LangChain4j 内部 OkHttp 连接 | **堆** | 共享连接池 |
| 11 | SSE 流返回 | `Flux` / `ServerSentEvent` | **堆** | 每请求一份 |
| 12 | 部署后异步截图 | `Thread.startVirtualThread` | 新建**虚拟线程**（栈在堆上） | 每次部署一个 |
| 13 | 截图操作共享浏览器 | `webDriver`（**静态字段**） | **方法区**（全局一份） | ❌ 全员共享 |

**可直接回答"用户信息保存在哪"**：

- 会话层：`HttpSession` 里的 `User`（Session 对象在堆）
- 每请求：`this.getById(userId)` **又查一次库**，产生新的 `User` 对象（堆）
- 栈里只存了指向它的**地址**

> **⚠️ 顺带发现的一个小反模式**：登录用户信息**每次请求都多查一次库**。

**线程是怎么来的**：Tomcat 有**线程池（默认 `maxThreads=200`）**，从池里借出工作线程；**Spring MVC 是 1 请求 : 1 线程**；Controller 返回后线程**归还池**（不销毁）。

### 3.7 关于 `CodeGeneratorNode` 落盘路径

`AppConstant.CODE_OUTPUT_ROOT_DIR = System.getProperty("user.dir") + "/tmp/code_output"`，所以：

```
e:\AI\GitHub\yu-ai-code-mother\tmp\code_output/multi_file_0
                                                    └──┬──┘ └┬┘
                                          generationType.value()  appId=0L（硬编码）
```

---

## 第四部分 · WorkflowContext 与图状态管理

### 4.1 结论先行

| 疑问 | 结论 |
| --- | --- |
| 它是不是"全图状态"？ | **严格说不是**。图的真正状态是 `MessagesState` 内部那个 `Map`；`WorkflowContext` 只是这个 Map 里的**一个 value** |
| 那它算什么？ | **业务意义上的全图状态**。一个对象装下了全流程所有字段 |
| 传输中状态怎么变？ | **不是创建新快照，而是同一个对象在 6 个工位间被反复原地修改** |

### 4.2 五层结构（从外到内）

```
CompiledGraph（可执行流水线）
  └─ 每走完一个工位 → 产出一个 NodeOutput
        └─ NodeOutput<MessagesState<String>>
              └─ MessagesState<String> extends AgentState     ← 框架的类
                    └─ private final Map<String, Object> data   ★ 真正的"状态容器"
                          ├─ "messages"         → List（框架预留，本项目没用）
                          └─ "workflowContext"  → WorkflowContext 对象  ★ 业务数据
```

| 层 | 类 | 负责什么 | 谁造的 |
| --- | --- | --- | --- |
| 状态容器 | `AgentState` / `MessagesState` | 提供一个 `Map`，**规定 key 的更新策略** | 框架 |
| 业务数据包 | `WorkflowContext` | **装业务字段**，提供读写静态方法 | 作者 |
| 流水线 | `CompiledGraph` | 决定**哪一步之后走哪一步** | 作者的 `createWorkflow()` |

### 4.3 框架内部：`AgentState` 就一个 Map（反编译验证）

```java
public class org.bsc.langgraph4j.state.AgentState {
    public static final Object MARK_FOR_RESET;      // 特殊标记：重置该 key
    public static final Object MARK_FOR_REMOVAL;    // 特殊标记：删除该 key

    private final Map<String, Object> data;         // ★ 状态本体就是这一个 Map

    public AgentState(Map<String, Object>);
    public final Map<String, Object> data();        // 就是 state.data()
    public final <T> Optional<T> value(String);
}
```

```java
public class org.bsc.langgraph4j.prebuilt.MessagesState<T> extends AgentState {
    public static final Map<String, Channel<?>> SCHEMA;   // ★ key → 更新策略
    public List<T> messages();
}
```

**`SCHEMA` 里到底注册了什么？** 反编译静态初始化块：

```java
public static final Map<String, Channel<?>> SCHEMA =
        Map.of("messages", Channels.appender(ArrayList::new));   // 只有这一个 key！
```

> **⚠️ 关键**：我们用的 `"workflowContext"` **没有注册任何 Channel**。

### 4.4 框架怎么合并节点返回的状态（反编译验证）

```java
public static Map<String,Object> updateState(Map<String,Object> state,
                                             Map<String,Object> partialState,
                                             Map<String,Channel<?>> schema) {
    Objects.requireNonNull(state, "state cannot be null");
    if (partialState == null || partialState.isEmpty()) {
        return state;                                  // ① 空 Map = 什么都不改
    }
    Map<String,Object> merged = updatePartialStateFromSchema(state, partialState, schema);
    return Stream.concat(
               state.entrySet().stream(),              // 老状态的所有 key
               merged.entrySet().stream()              // 新算出来的 key
           ).collect(toMapRemovingItemMarkedForRemoval());
}
```

per-key 的规则（反编译 lambda）：

```java
(entry) -> {
    Channel<?> channel = schema.get(entry.getKey());   // 去 SCHEMA 查这个 key 有没有策略
    if (channel != null) {
        Object newValue = channel.update(key, currentState.get(key), entry.getValue());
        return entryOf(key, newValue);
    }
    return entry;   // ← channel 为 null：原样返回，即"新值直接覆盖旧值"
}
```

**所以每个 key 的更新规则是二分法**：

| key 在 SCHEMA 里？ | 更新方式 | 本项目哪个 key |
| --- | --- | --- |
| 有（如 `"messages"`） | 走 `Channel.update` → reducer 合并（appender = 追加到 List 尾） | `"messages"` |
| 没有（如 `"workflowContext"`） | **直接覆盖** | `"workflowContext"` |

**额外能力**：`AgentState.MARK_FOR_REMOVAL` 可以让节点返回值删除某个 key（本项目未使用）。

### 4.5 读写契约与"原地修改"真相

```java
public static WorkflowContext getContext(MessagesState<String> state) {
    return (WorkflowContext) state.data().get(WORKFLOW_CONTEXT_KEY);   // 取出的是引用
}
public static Map<String, Object> saveContext(WorkflowContext context) {
    return Map.of(WORKFLOW_CONTEXT_KEY, context);                      // 交回的还是同一个引用
}
```

节点里的固定三连：

```java
WorkflowContext context = WorkflowContext.getContext(state);  // 1. 取出引用
context.setImageList(collectedImages);                        // 2. 直接改这个对象
return WorkflowContext.saveContext(context);                  // 3. 同一个引用挂回 Map
```

**追踪（看引用，不看值）**：

```
【❌ 很多人以为的：每步一份新快照，像 Git commit】
   A1{初始化} → A2{+imageList} → A3{+enhancedPrompt} → A4{+type} ...

【✅ 实际发生的：一份可变对象，被反复就地修改】
   A{初始化} → A{初始化+imageList} → A{...+enhancedPrompt} → A{...+type} ...
   （同一个 A 在不同时刻的样子）
```

**收益与代价**：

| 维度 | 说明 |
| --- | --- |
| 收益 | 实现简单；不写 `copy()`；字段多也不用到处传参 |
| 代价 1 | **不可回溯**：拿不到"第 3 步结束时"的状态 → 只能靠日志（所以 `CodeGenWorkflow` 每步都 `log.info("当前步骤上下文: {}", ...)`） |
| 代价 2 | **并发分支共写字段会互相覆盖**：图2 的 4 个 Collector 都 `setCurrentStep(...)` → "最后写的赢"，结果不确定 |
| 代价 3 | 与 LangGraph 设计理念不一致：框架鼓励"节点返回增量 Map，由 Channel/reducer 合并"（不可变状态），本项目用可变对象**绕过**了 |

**为什么作者要"绕过"**：`WorkflowContext` 有 15 个字段，若每个节点都返回增量 Map（`Map.of("imageList", list, "currentStep", "图片收集")`），要手写大量 key 字符串，容易拼错且重构困难。**用工程便利性换掉了框架的可回溯性。**

### 4.6 `implements Serializable` 是为"快照"预留的

```java
public class WorkflowContext implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
```

配套 `@Data @Builder @NoArgsConstructor @AllArgsConstructor` —— 这套组合是**给序列化/反序列化框架用的**。同包 `ImageResource`、`ImageCollectionPlan` 的 record 也都 `implements Serializable`。

> 这说明作者考虑过「把状态存起来」= LangGraph4j 的 **checkpoint（检查点）**机制：每步序列化落库，支持断点续跑、时间旅行、人工介入。
> **但本项目没有配置 checkpointer**（`stream` 调用没传 saver）→ **预留了但没启用**。

### 4.7 可自验证的两个实验

```java
// 实验 1：证明"是同一个对象"
log.info("context hashCode = {}", System.identityHashCode(context));
// ImageCollectorNode 开头和 PromptEnhancerNode 开头各加一行 → 输出会是同一个数字

// 实验 2：证明"覆盖语义"
return Map.of("messages", List.of("a"), WorkflowContext.WORKFLOW_CONTEXT_KEY, context);
// messages 会累积成 [..., "a"]；workflowContext 只保留最后一个
```

---

## 第五部分 · static 与 new 的执行标准

### 5.1 本项目真实写法全摊开

| 代码 | 写法 | 为什么 |
| --- | --- | --- |
| `new CodeGenWorkflow()` | **new** | 每次请求要一个独立的执行主体；不是 Spring Bean |
| `ImageCollectorNode.create()` | **静态工厂** | 节点本质是**一个函数**（lambda），不是有状态对象 |
| `WorkflowContext.builder()...build()` | **静态工厂 + Builder** | 15 个字段，构造器参数列表会长到不可读 |
| `WorkflowContext.getContext(state)` | **静态工具方法** | 无状态纯计算 |
| `SpringContextUtil.getBean(X.class)` | **静态服务定位器** | 拿不到容器引用时取 Bean（**有代价**） |
| `Map.of(...)` / `List.of(...)` | **静态工厂** | 不可变集合，语义直达 |
| `node_async(...)` / `edge_async(...)` | **静态工具** | 把同步函数包装成异步 |
| `Thread.startVirtualThread(...)` | **静态方法** | JDK 入口 |
| `new MessagesStateGraph<String>()` | **new** | 需持有内部状态且链式 `addNode/addEdge` |
| `new SseEmitter(...)` / `new BusinessException(...)` / `new StringBuilder()` | **new** | 独立实例 / 有可变状态 |

### 5.2 判断标准：决策树

```
               我需要的这个东西……
                        │
        ┌───────────────┴───────────────┐
   是不是"一段行为"？              是不是"一个东西"？
   （要当参数传递）                  （要持有状态/被引用）
        │                               │
   静态方法返回函数               它需要被 Spring 管理吗？
   （Node.create()）                    │
                                ┌──────┴──────┐
                              需要           不需要
                                │             │
                       SpringContextUtil  构造复杂吗？
                          .getBean()           │
                                        ┌──────┴──────┐
                                   复杂（参数多）    简单
                                        │             │
                                  静态工厂              new
                                  + Builder      （或静态工厂起名）
                                        │
                          ┌─────────────┴─────────────┐
                       无状态纯计算                 需要缓存/单例
                          │                           │
                       静态工具方法                静态方法 + 内部
                       （getContext/saveContext）  持有实例
```

### 5.3 五条硬标准（背下来）

| # | 标准 | 用静态 | 用 new |
| --- | --- | --- | --- |
| 1 | **有没有可变状态？** | 没有（纯函数） | 有（`ExecutorBuilder`、`StringBuilder`、`SseEmitter`） |
| 2 | **有没有生命周期？** | 无（随调随用） | 有（创建/使用/销毁） |
| 3 | **要不要被当"参数"传？** | 要 → 静态工厂返回函数 | 不要 |
| 4 | **构造复不复杂？** | 复杂 → 静态工厂 + Builder | 简单（1~3 个参数） |
| 5 | **要不要 IoC 能力？** | **千万别自己 new**，用容器 | 容器管不到的场景才 new |

**第 5 条单独说** —— 自己 `new` 一个被 Spring 管理的类，会丢掉三样东西：

```
new XxxService()
   ✗ 依赖注入（内部 @Resource 字段全是 null）
   ✗ AOP（@Transactional / @Cacheable / 日志切面全部失效）
   ✗ 多例/单例语义（Spring 配的 @Scope 无效）
```

### 5.4 本项目一个"被迫妥协"：为什么必须用 `SpringContextUtil`

```
① 节点必须是"静态方法 + lambda"
       ↓ 为什么？
② 因为框架要的是 AsyncNodeAction（一个函数），不是对象
       ↓ 那这个 lambda 从哪拿依赖？
③ lambda 没有构造器、没经过 Spring 容器 → 无法依赖注入
       ↓ 只能
④ 用静态服务定位器 SpringContextUtil.getBean(...) 在 lambda 内部现取
```

**所以 `SpringContextUtil` 不是偷懒，是被架构逼出来的妥协。** 它是第三种选择 —— **服务定位器（Service Locator）模式**，问题明确：

| 问题 | 说明 |
| --- | --- |
| 隐藏依赖 | 看方法签名看不出它需要 `ImageSearchTool`，要读实现体才知道 |
| 难测试 | 单测无法注入 Mock，只能启动整个 Spring 上下文 |
| 运行时才发现 | 拼错 Bean 名、Bean 不存在，编译期不报错 |

**更好方案**：把节点改成 Spring Bean，用 `@Resource` 注入依赖，`create()` 作为实例方法被容器调用。代价是节点从"函数"变成"对象"。

### 5.5 什么时候**不该**用静态方法

| 反模式 | 后果 | 正确做法 |
| --- | --- | --- |
| 静态方法里访问可变静态字段 | **并发不安全** | 实例 + 容器单例，或 `AtomicInteger` / `ConcurrentHashMap` |
| 静态方法当"万能工具类" | 无法替换、无法 Mock | 按职责拆类，用组合 |
| 静态方法里 `getBean` 拿依赖 | 隐藏依赖、难测试 | 依赖注入 |
| 静态常量当"可变配置" | 改不了（除非重启） | `@ConfigurationProperties` |

### 5.6 静态工厂优于构造器的四条

| 优势 | 本项目实例 |
| --- | --- |
| **有名字** | `WorkflowContext.builder()` 一看就知道在造 Builder |
| **可以返回子类型** | `AiServices.builder(...).build()` 返回**运行时生成的代理**（接口没法 new） |
| **可以复用/缓存** | `Map.of()` 空集合返回单例 |
| **可重载同名不同语义** | `Channels.appender(...)` vs `appenderWithDuplicate(...)` |

**命名惯例**：

| 名字 | 含义 | 例 |
| --- | --- | --- |
| `create()` | 每次新建 | `ImageCollectorNode.create()`、`ExecutorBuilder.create()` |
| `of(...)` | 聚合/转换创建（参数少） | `Map.of()`、`List.of()` |
| `from(...)` | 从另一个类型转换 | 各类 `getEnumByValue` |
| `builder()` | 返回 Builder | `WorkflowContext.builder()`、`AiServices.builder()` |
| `getInstance()` | 单例 | （本项目未使用） |

### 5.7 一分钟口诀

> **有状态就 `new`；纯计算就 `static`；当函数传就 `static` 造 lambda；参数多就 `static` + `builder`；归容器管就 `getBean`。**

---

## 第六部分 · AI 并发调用：项目现状 vs 企业级

### 6.1 结论先行

项目对「AI 并发」的处理是**分层的、局部的、够用型**的，做了 **3 件实事**，另有 **3 个真实隐患未解决**。

| 层 | 并发问题 | 项目怎么做的 | 评价 |
| --- | --- | --- | --- |
| ① **模型实例** | 同一个 `StreamingChatModel` 被多线程复用 | `@Scope("prototype")` 多例 | ✅ 做了，代价是实例膨胀 |
| ② **AI 服务 + 对话记忆** | 实例创建要回填历史，不能每次新建 | Caffeine 按 `appId + 类型` 缓存 | ⚠️ **同 appId 并发仍不安全** |
| ③ **配额保护** | LLM 额度被刷爆 | Redisson 分布式限流（用户级 5 次/60s） | ✅ 做了，但只在一处 |
| ④ **线程模型** | 长耗时请求占线程 | 主链路 Reactor；辅助用虚拟线程 | ⚠️ 没有业务线程池隔离 |
| ⑤ **下游资源** | 浏览器/本地目录竞争 | **没做任何处理** | ❌ **真 bug：静态单例无锁** |
| ⑥ **异步异常** | 异步任务静默失败 | **没做** | ❌ 虚拟线程里无 `try-catch` |

### 6.2 "AI 并发"要拆成五层看

```
用户A ─┐
       ├─► ① 模型实例层：共用一个 ChatModel 对象？
用户B ─┘
              ▼
       ② AI 服务层：共用一个 AiCodeGeneratorService / 对话记忆？
              ▼
       ③ 入口层：HTTP 线程从哪来？阻塞还是非阻塞？
              ▼
       ④ 下游层：WebDriver / 本地文件目录 / COS 会不会撞车？
              ▼
       ⑤ 配额层：LLM 调用总量有没有闸门？
```

### 6.3 层① 模型实例：prototype 多例

```java
@Bean
@Scope("prototype")                                   // 每次 getBean 都 new 一个
public StreamingChatModel streamingChatModelPrototype() {
    return OpenAiStreamingChatModel.builder()
            .apiKey(apiKey).baseUrl(baseUrl).modelName(modelName)
            .maxTokens(maxTokens).temperature(temperature)
            .listeners(List.of(aiModelMonitorListener))   // token 用量监控
            .build();
}
```

消费端显式按 Bean 名取：

```java
// 使用多例模式的 StreamingChatModel 解决并发问题
SpringContextUtil.getBean("reasoningStreamingChatModelPrototype", StreamingChatModel.class);
```

**⚠️ 一个不对称**：同一工厂里非流式的 `chatModel` 是**单例**（`@Resource(name = "openAiChatModel")`，无 prototype）。

| 模型 | 作用域 |
| --- | --- |
| `OpenAiChatModel`（非流式） | **单例**（LangChain4j starter 自动装配） |
| `streamingChatModelPrototype` | **prototype** |
| `reasoningStreamingChatModelPrototype` | **prototype** |
| `routingChatModelPrototype` | **prototype** |

**评价**：这是**防御性设计**。代价明确：每创建一个 AI 服务就新建一个模型实例 + 一套新 HTTP 客户端资源，且无复用池。

### 6.4 层② AI 服务 + 对话记忆（**真风险所在**）

```java
private final Cache<String, AiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofMinutes(30))
        .expireAfterAccess(Duration.ofMinutes(10))
        .build();

public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
    String cacheKey = appId + "_" + codeGenType.getValue();
    return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenType));
}
```

**为什么要缓存（关键）**：创建一次很贵 —— 要查库回填 20 条历史。

```java
MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
        .id(appId).chatMemoryStore(redisChatMemoryStore).maxMessages(20).build();
chatHistoryService.loadChatHistoryToMemory(appId, chatMemory, 20);   // 查库回填历史
```

**真正的并发问题：同 appId 并发**

```
用户A 的两个标签页同时向 appId=100 发消息
        ▼
两个请求拿到【同一个服务实例】
        ▼
共享【同一个 MessageWindowChatMemory】
        ▼
⚠️ 消息按到达顺序交错写入 → 上下文被污染
   （A1 → B1 → A2 → B2，AI 看到混在一起的两轮对话）
```

**项目对此没有任何处理**：没有锁、没有按 requestId 分组记忆、没有版本号。缓存是**按 appId 共享**的语义 —— 作者认为「同一个应用属于同一个用户，并发消息是少数情况」。

**共享链**：

```
Caffeine 缓存（堆里的 Map）
    key = "100_html"
    value = 服务代理对象（堆）
               │ 内部字段持有引用
               ▼
        MessageWindowChatMemory（堆）
               │ 内部字段持有引用
               ▼
        List<ChatMessage>（堆）  ← ★ 真正被并发写的那个东西
```

### 6.5 层③ 线程模型

| 场景 | 实现 | 阻塞吗 |
| --- | --- | --- |
| 主链路 SSE 对话生成 | `Flux<ServerSentEvent<String>>` | **非阻塞**（Reactor 事件循环） |
| 实验模块工作流 | `Thread.startVirtualThread(...)` + `SseEmitter` | 虚拟线程阻塞，代价低 |
| 部署后异步截图 | `Thread.startVirtualThread(...)` | 虚拟线程 |

**虚拟线程很适合本项目**：请求都是"等 IO"（等 LLM、等浏览器、等 COS），CPU 几乎不占。

**但缺两件企业必备的东西**：

| 缺什么 | 后果 |
| --- | --- |
| **业务线程池隔离（Bulkhead 舱壁）** | AI 生成把线程吃光时，登录、查应用列表等**无关接口也一起变慢** |
| **有界并发上限** | 虚拟线程不是无限免费 —— 1000 个并发 LLM 请求会打挂下游 API |

### 6.6 层④ 下游资源竞争：**真 bug**

```java
private static final WebDriver webDriver;      // ★ 全局唯一
static {
    webDriver = initChromeDriver(1600, 900);  // 类加载时初始化
}
```

而 `saveWebPageScreenshot` 是**静态方法，直接操作这个共享 driver**：

```java
webDriver.get(webUrl);                       // ① 导航
waitForPageLoad(webDriver);                  // ② 等加载
byte[] screenshotBytes = ((TakesScreenshot) webDriver).getScreenshotAs(OutputType.BYTES);  // ③ 截图
```

**并发时序灾难**：

```
线程1: webDriver.get("应用A的URL")  ─┐
线程2:        webDriver.get("应用B的URL")  ← 把浏览器导航到 B
线程1: waitForPageLoad... (等的是 B 的页面)
线程2: 截图 → 存成"应用B"的图         ✅ 正常
线程1: 截图 → 存的却是"应用B"的图     ❌ 串页！A 的封面变成了 B
```

**这就是「共享可变状态的并发不安全」** —— `WebDriver` 的"当前导航到哪个页面"就是它的状态。

**并且**：`@PreDestroy` 加在一个**非 Spring Bean** 的类上（`WebScreenshotUtils` 只有 `@Slf4j`，没有 `@Component`）：

```java
@PreDestroy
public void destroy() { webDriver.quit(); }
```

→ 它不在容器里 → **`@PreDestroy` 根本不会被回调** → **Chrome 进程泄漏**。

**此外**：静态块启动 Chrome，触发点在"第一次有人要截图"时 → **第一个用户的部署请求会额外多等几秒**（冷启动副作用）。

### 6.7 层⑤ 配额保护：限流

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

三个维度可选：`API` / `USER` / `IP`。**实际只用在一个地方**：

```java
@GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60,
           message = "AI 对话请求过于频繁，请稍后再试")
public Flux<ServerSentEvent<String>> chatToGenCode(...)
```

| 点 | 说明 |
| --- | --- |
| ✅ 分布式 | Redisson 而非本地 Guava → 多实例部署也准（**企业必需**） |
| ✅ 用户维度 | 按登录用户 ID 限流；未登录降级为按 IP |
| ⚠️ 幂等误用 | `trySetRate` **只在第一次返回 true**，之后每次调用都是空操作 → 多余但无害，容易误解为"动态参数" |
| ❌ 覆盖不全 | `WorkflowSseController`（实验模块）**没有任何限流** |

### 6.8 层⑥ 异步异常：**真 bug**

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

**整个 lambda 没有 `try-catch`**：

| 结果 | 说明 |
| --- | --- |
| 异常被**虚拟线程的默认未捕获处理器**接走 | 只打印到标准错误，**不进日志系统** |
| 没有重试 | 截图失败就永久没有封面 |
| 没有指标 | 无法统计"封面生成失败率" |
| 用户无感知 | 部署成功了，封面一直空着，**没人知道为什么** |

**而讽刺的是**：`return appDeployUrl` 在异步任务**启动之前**就返回了 → 调用方连"任务是否起来"都感知不到。

### 6.9 企业级对比（12 维度）

| 维度 | 本项目（教学级） | 企业生产级典型做法 | 差距 |
| --- | --- | --- | --- |
| **模型实例管理** | `prototype` 每服务一份，无池化 | 模型客户端**单例 + HTTP 连接池**，线程安全由官方 SDK 保证 | 中 |
| **会话隔离** | 按 `appId` 共享记忆 | 按 `sessionId / requestId` 隔离；**同会话串行、不同会话并行** | **大** |
| **并发控制** | 无 | **舱壁隔离（Bulkhead）** + 信号量限并发 | **大** |
| **超时与重试** | 主链路无 | 超时 + 指数退避重试 + **熔断** + 降级 | **大** |
| **限流** | 单维度（用户 5/60s），仅 1 处 | 多维度（租户/用户/IP/接口/全局）+ 令牌桶 + **token 预算** | 中 |
| **线程模型** | Reactor（主）+ 虚拟线程（辅助） | 业务线程池**隔离**（AI 池 / 普通接口池分开）+ MQ 削峰 | 中 |
| **可观测性** | 日志 + `AiModelMonitorListener` 统计 token | **指标（token/latency/失败率/并发数）+ traceId 全链路 + 告警** | 中 |
| **降级兜底** | 无 | **多模型路由**（OpenAI 挂 → 切国产模型）+ 缓存兜底 | **大** |
| **幂等与去重** | 无 | 幂等键 + 请求去重 | 中 |
| **成本控制** | 记忆窗口限 20 条 | Prompt 缓存、**语义缓存**、token 预算与熔断 | 中 |
| **并发安全的下游** | WebDriver 静态单例无锁（**串页 bug**） | 资源池化 + 每请求租借/归还；或独立无头渲染服务 | **大** |
| **异步可靠性** | 虚拟线程裸用，无异常捕获 | 统一异步框架 + 异常兜底 + 重试 + 死信告警；关键任务走 MQ | **大** |

**一句话总结差距**：

> **本项目是「按请求隔离」级别，企业项目需要「按资源分层治理」级别。**
>
> ```
> 本项目 = 我把每个用户/应用的实例分开，剩下的靠"同时用的人少"赌运气
> 企业级 = 不仅要隔离，还要限并发上限、限总配额、超时熔断、降级兜底、全链路可观测
> ```

### 6.10 企业里最常见的三种"AI 并发"治理手段

**① 舱壁隔离（Bulkhead）**

```
Tomcat/Netty 线程池
   ├── 普通接口池（查询、登录）        ← AI 挂了也不受影响
   └── AI 生成池（最多 50 并发）       ← 满了就快速失败，而不是排队堆积
```

**② 信号量硬上限**

```java
Semaphore aiConcurrency = new Semaphore(50);
if (!aiConcurrency.tryAcquire(200, TimeUnit.MILLISECONDS)) {
    throw new BusinessException(TOO_MANY_REQUEST, "AI 服务繁忙，请稍后重试");
}
```

**③ 熔断 + 降级**

```
连续 20 次 LLM 调用失败 → 熔断打开 30s（不再打下游，直接返回兜底）
         ↓ 下游恢复
      半开试探 → 恢复正常
```

（工具：Resilience4j / Sentinel；本项目**完全没有**。）

### 6.11 生产化改造最小清单（按投入产出比排序）

| # | 改什么 | 怎么改 | 收益 |
| --- | --- | --- | --- |
| 1 | **截图串页** | 加 `ReentrantLock` 串行化；或 Driver 池 / 无头渲染服务 | 修掉用户可见 bug |
| 2 | **异步静默失败** | 包 `try-catch`，记日志 + 指标 + 有限重试 | 消除无感知的功能缺失 |
| 3 | **同 appId 并发记忆污染** | 加 Redisson 分布式锁串行；或记忆键换成 `appId:sessionId` | 消灭上下文污染 |
| 4 | **AI 调用并发上限** | 加 `Semaphore` 或 Resilience4j Bulkhead | 防止雪崩 |
| 5 | 模型实例池化 | 去掉 `prototype`，改单例 + 连接池 | 省资源 |
| 6 | 熔断降级 | Resilience4j CircuitBreaker + 备用模型 | 下游挂掉不影响主流程 |
| 7 | 多维度限流 | 其他入口也加 `@RateLimit`；增加全局配额维度 | 保护额度 |

---

## 第七部分 · JVM 内存模型（本次重点）

### 7.1 ⭐ 核心误解的破除

> 我的原话：「一个线程处理数据的时候，数据不应该都是在这个线程内部的么……每个线程内部就是隔离的状态么」
>
> **❌ 这句话不成立。**

```
线程内部（栈）只放【引用】（一串地址）          堆里放【对象本体】
       ┌───────────────┐
       │  线程A 的栈    │
       │  s = 0x1234  ──┼──────────┐
       └───────────────┘          ▼
                          ┌──────────────────┐
       ┌───────────────┐  │  堆              │
       │  线程B 的栈    │  │  0x1234: [对象]  │  ← 对象只有一份
       │  s = 0x1234  ──┼──┘  内部字段 x=1   │
       └───────────────┘  └──────────────────┘
```

**两个必须记住的定义**：

> - **线程私有的边界** = 栈帧里的**局部变量**（含参数、引用本身）
> - **共享的边界** = 堆里的**对象字段** + 类级别的**静态字段**
>
> 只要两个线程的局部变量里装的是同一个地址 → 它们就在共享那个堆对象。

### 7.2 ⭐ 三区职责总表

| 区域 | 私有/共享 | 里面放什么 | 本项目例子 |
| --- | --- | --- | --- |
| **栈**（每线程一条） | **线程私有** | 局部变量、方法参数、**引用（地址）** | `AppController` 里的 `loginUser`、`prompt`、`this` |
| **堆**（所有线程共享） | **共享** | **对象实例**、**实例字段**、数组、String | `User` 对象、`AiCodeGeneratorService`、`chatMemory` 里的 `List`、`MonitorContext`、`Thread` 对象 |
| **方法区 / 元空间** | **共享** | 类元信息、方法字节码、**静态字段**、运行时常量池 | `AppConstant` 常量、`WebScreenshotUtils.webDriver` |

### 7.3 实例字段 vs 静态字段

**先纠正用词**：

> ❌ 「实例字段**可以创建多次**」→ 字段不能"创建"，能创建的是【对象】。**字段只是对象内部的"格子"。**

```java
class User {
    private Long id;        // 实例字段：格子，不是对象
    private String name;    // 实例字段
    static int COUNT = 0;   // 静态字段
}

User u1 = new User();   // ★ 这一步才叫"创建"；堆上出现第 1 个 User 对象，带 2 个格子
User u2 = new User();   // ★ 堆上出现第 2 个 User 对象，带【自己独立】的 2 个格子
```

| | **实例字段** | **静态字段** |
| --- | --- | --- |
| 定义 | 不带 `static` 的成员变量 | 带 `static` 的成员变量 |
| 属于谁 | 属于**对象** | 属于**类** |
| 何时存在 | `new` 之后才有 | **类加载时**就有 |
| 几份 | **每个对象一份** | **全局一份** |
| 物理位置 | 对象内部 → 对象在堆 → **在堆** | 概念上属**方法区**（JDK 8+ 实现为元空间）；HotSpot 实际是**挂在 `Class` 对象上**（`Class` 对象本身在堆） |
| 怎么访问 | `u1.id`（通过对象） | `User.COUNT`（通过类） |

**⭐ 纠正核心推论**：

> ❌ 错：「实例字段可以创建多次**供多线程使用保障线程安全**」
>
> ```
> ✅ 正确的说法：实例字段安不安全，看这个对象【被几个线程共享】
>
>    每个线程独占一个对象  → 安全（每个线程改的是自己的格子）
>    多个线程共享一个对象  → 不安全（大家改同一个格子）
> ```

| 场景 | 对象份数 | 安全吗 |
| --- | --- | --- |
| `@Scope("prototype")` 的 `StreamingChatModel` | 每个调用者一份 | ✅ 安全 |
| 单例的 `AiCodeGeneratorService`（同 appId 共一份，内部有 `chatMemory`） | 1 份 | ❌ **不安全** |

**所以"搞多例"不是让字段变安全，是让每个线程拿到不同对象。**

**"能包括项目中大部分代码的地址么？"** → **不能，必须加上第三类**：

```
项目里的数据只有三种下场：
  ① 局部变量 + 引用  → 住栈（线程私有）
  ② 实例字段        → 住堆（对象内）
  ③ 静态字段        → 类级别（全局一份）
```

**特别注意 ①**：栈里存的**不是对象，是对象的地址**。

### 7.4 方法区：位置与"实例指向代码"的关系

**方法区是不是独立于堆栈的另一块地址？**

| 版本 | 实现 | 物理位置 |
| --- | --- | --- |
| JDK 7 及以前 | 永久代 PermGen | **在 JVM 堆内**（不是独立的） |
| **JDK 8+**（本项目 JDK 21） | 元空间 Metaspace | **在本地内存（native memory），在 Java 堆之外** |

> **逻辑上**：独立的三区（栈 / 堆 / 方法区），任何 JDK 都成立
> **物理上**：JDK 8+ 元空间**确实在 Java 堆之外**（默认不受 `-Xmx` 限制）

**实例怎么"找到"方法区的代码**：

```
堆                          方法区 / 元空间
┌──────────────────┐        ┌──────────────────────────┐
│ User 对象 0xA100 │        │ User 类元数据（Klass）    │
│ ┌──────────────┐ │        │  ├ 字段表                │
│ │ 对象头        │ │        │  ├ 虚方法表 vtable ──────┼──► 方法字节码
│ │  klass ptr ───┼─┼───────►│  └ 静态字段 COUNT        │
│ └──────────────┘ │        └──────────────────────────┘
│  实例字段 id/name│
└──────────────────┘
```

| 我的说法 | 精确表述 |
| --- | --- |
| 「类的实例指向对应的方法区代码」 | 对象头里有个 `klass` 指针，指向方法区里的**类元数据**；类元数据里再有虚方法表指向**方法字节码**。是**间接**的 |

**✅ 确认我的另一句理解**：

> 「本质是个 Map，住在堆里；其它这种结构也是差不多都是在**堆中创建了一个空间保存地址**，然后**栈中执行到这里用变量指向这个地址**」
>
> **完全正确。** 这就是全部真相：
> ```
> new → 堆里划一块空间（对象 + 它的实例字段）
> 栈里的局部变量 → 存的只是那块空间的地址（引用）
> "变量指向对象" = 栈里的地址 指向 堆里的空间
> ```

### 7.5 类加载：不是"启动时全加载"

> ❌ 「项目初始时所有的 class 类以及其它代码」→ 不是这样。
>
> **JVM 是【按需、延迟加载】的**，触发时机只有三个（JVM 规范叫"首次主动使用"）：

| 触发时机 | 本项目例子 |
| --- | --- |
| ① **首次 `new`** 某个类 | 第一个用户发消息 → 才加载 `AppController` / `AiCodeGeneratorFacade` |
| ② 首次访问该类的**静态字段** | 第一次调截图 → 才触发 `WebScreenshotUtils` 的静态块 → **才启动 Chrome** |
| ③ 首次调用该类的**静态方法** | 第一次调 `WorkflowContext.getContext()` |

**⚠️ 这里有个真实的性能陷阱**：`WebScreenshotUtils` 用静态块初始化 `webDriver`（启动整个 Chrome），触发点在"第一次有人要截图"时 → **第一个用户的部署请求会额外多等几秒**（冷启动）。

**记住**：`new` → 类元数据（方法区）+ 对象（堆）；**新对象不会"复制"代码，只是对象头留个指针指过去。**

### 7.6 `this` 的本质：隐藏的第一个参数

> ❌ 我的错误推论：「为什么不能访问实例成员，**是因为方法区不能访问堆中数据么**」
>
> **完全不是。** 方法区/代码**完全可以访问堆**（我们写的方法里到处都是 `new` 对象、改对象字段）。**真正原因是"没有钥匙"，不是"门锁着"。**

```java
class User {
    private Long id;
    void setId(Long id) { this.id = id; }   // 实例方法
}
```

编译之后，**实例方法其实等价于**：

```java
// 编译器偷偷做了这件事：把"当前对象"作为【第一个隐藏参数】塞进来
void setId(User this, Long id) { this.id = id; }
//          ↑↑↑↑ 这就是 this 的真身
```

| 概念 | 含义 |
| --- | --- |
| `this` | 实例方法的**隐式第一个参数**，指向"调用这个方法的那个对象" |
| 它的值住在哪 | 它是**局部变量** → 住**栈**（存放的是堆里对象的地址） |
| 谁传进来的 | 调用方 `u1.setId(1L)` 时，JVM 自动把 `u1` 作为 `this` 传入 |

| | 实例方法 | 静态方法 |
| --- | --- | --- |
| 调用方式 | `u1.setId(1L)` —— **有对象** | `User.setCount(1)` —— **没有对象** |
| 隐藏参数 `this` | ✅ 有，值是 `u1` | ❌ **没有** |
| 能访问实例字段 `id` 吗 | ✅ 通过 `this.id` | ❌ 拿不到 `this`，**不知道是哪个对象的 id** |

**所以：不是"方法区访问不了堆"，而是"静态方法不知道该访问堆里的哪一个对象"。**

> **比方**：
> - 实例方法 = 你手里拿着某间房的钥匙（`this`），能进去改东西
> - 静态方法 = 你站在楼道里，知道"有一种叫房的东西"，但**你不知道是哪一间**

### 7.7 ⭐ 反直觉的关键点：`ThreadLocal` 的数据也在堆里

```java
private static final ThreadLocal<MonitorContext> CONTEXT_HOLDER = new ThreadLocal<>();
```

**存储路径**：

```
栈（线程 T-1）                堆
  Thread 引用 ────────────────► Thread 对象（T-1）
                                 └ threadLocals = ThreadLocalMap
                                        └ "monitor" → MonitorContext 对象
```

> **所以："线程私有"只是**逻辑上私有** —— 物理上 ThreadLocal 的值存在 `Thread` 对象的一个 Map 字段里，而 `Thread` 对象住在堆。**
>
> 这解释了一件事：为什么"线程私有数据"能被另一个线程通过反射读到 / 造成内存泄漏 —— 因为它就在堆里，谁拿到 `Thread` 引用谁就能访问。

### 7.8 完整一次聊天的三区内存图

```
┌─────────────────────────── JVM ───────────────────────────────┐
│ ┌─ 线程 T-1 的栈（私有）─────────────┐                          │
│ │ AppController.chatToGenCode() 栈帧 │                          │
│ │   request  = 0xC001 ───────────────┼──► ┐                    │
│ │   loginUser= 0xA100 ───────────────┼──► │ ┌── 堆（共享）───┐ │
│ │   prompt   = "做个个人博客" → 0xA200┼──► │ │ 0xC001 HttpReq │ │
│ │                                     │    │ │ 0xA100 User    │ │
│ │  （方法结束后栈帧销毁）              │    │ │ 0xA200 String  │ │
│ └─────────────────────────────────────┘    │ │ 0xA300 HttpSess│ │
│                                            │ │  └ attr: 0xA100│ │
│ ┌─ 线程 T-2 的栈（私有）─────────────┐      │ │ 0xA400 AiCodeG │ │
│ │ （另一个用户的请求）                │      │ │  Service(代理) │ │
│ └─────────────────────────────────────┘    │ │  └ chatMemory ─┼─┐│
│                                            │ │ 0xA500 ChatMem │◄┘│
│ ┌─ 线程 T-3 的栈（ThreadLocal）───────┐     │ │  └ List[0xA600]│ │
│ │ Thread 对象（注意！Thread 也在堆里）─┼───► │ 0xA600 List    │ │
│ │   threadLocals = ThreadLocalMap     │     │ │   [msg1,msg2]  │ │
│ │      └ "monitor" → MonitorContext ──┼───► │ 0xA700 Monitor │ │
│ └─────────────────────────────────────┘     │ └────────────────┘ │
│ ┌─ 方法区 / 元空间（共享）───────────────────────────┐           │
│ │  UserService 类元信息（含方法字节码）              │           │
│ │  AppConstant 常量：CODE_OUTPUT_ROOT_DIR → 0xA800   │           │
│ │  WebScreenshotUtils.webDriver（静态）→ 0xA900 ─────┼──► 0xA900 WebDriver │
│ │  String 常量池："做个个人博客" 字面量                │           │
│ └────────────────────────────────────────────────────┘           │
└────────────────────────────────────────────────────────────────┘
```

---

## 第八部分 · 缓存复用 vs 每次 new

### 8.1 ⭐ 结论：我的理解是对的

| 我的说法 | 判定 |
| --- | --- |
| 「不缓存每次创建都会 new 一个对象出来」 | ✅ 对 |
| 「缓存就只有 1 份」 | ✅ 对（**同一 appId 一份**） |
| 「每个用户 new 一个对象，另一个用户 new 另一个」 | ✅ 对 |
| 「不缓存的话，每个用户每次打开应用**不会**继承上次自己 new 出来的地址，会**再 new 一个**」 | ✅ **完全正确** |
| 「缓存就会让下次打开应用**指向上次的地址**」 | ✅ **完全正确** |

### 8.2 时间线对比

```
【不缓存】appId=100 的第 1 次请求
    createAiCodeGeneratorService(100)
      → new 服务对象 [地址 0xA400]  ← 新建
      → new chatMemory  [地址 0xA500]  ← 新建
      → 查库回填 20 条历史  ← ★ 慢，每次都要做

【不缓存】appId=100 的第 2 次请求
    createAiCodeGeneratorService(100)
      → new 服务对象 [地址 0xB400]  ← 又是新的！不继承 0xA400
      → new chatMemory  [地址 0xB500]  ← 又是新的
      → 再查库回填 20 条历史  ← ★ 又慢一次，而且【第 1 次的对话记忆没了】
```

```
【有缓存】appId=100 的第 1 次请求
    cache.get("100_html", k -> create...)
      → 未命中 → new 服务 [0xA400] + new chatMemory [0xA500]
      → 查库回填 → 存入缓存 Map { "100_html" → 0xA400 }

【有缓存】appId=100 的第 2 次请求
    cache.get("100_html", k -> create...)
      → 命中！直接返回 0xA400  ← ★★ 复用上次那个地址
      → 不 new，不查库  ← ★ 快
      → 而且能看到上次的对话记忆  ← ★ 功能必需（多轮对话）✅
```

### 8.3 "缓存制造了共享"到底什么意思

```
风险出现的位置：不是"不同用户之间"，而是【同一个 appId 的两个请求同时进行】

appId=100 的第 1 次请求（线程 T-1）  ─┐
appId=100 的第 2 次请求（线程 T-2）  ─┴─► 都拿到 0xA400
                                            ▼
                                    0xA500 里的 List<ChatMessage>
                                    两个线程同时 add → 顺序交错 ❌
```

**总结三句话**：

1. **缓存按 `appId` 复用对象** → 让"同一个应用的多轮对话"能连起来（**功能需求，必须缓存**）
2. **不缓存的代价不是"不隔离"，而是"每次查库 + 记忆丢失"** → 这才是加缓存的真实动机
3. **代价是**：同一 appId 并发时，两个线程共享了堆里同一个 List → 上下文污染（**项目没处理**）

> **我的直觉「每个用户一份对象」是对的** —— 只是"用户"这个维度在项目里是 **appId**（一个用户可以有多个应用，每个应用各自一份记忆）。

---

## 第九部分 · 并发核心概念补充

### 9.1 有状态 vs 无状态

| | 有状态（Stateful） | 无状态（Stateless） |
| --- | --- | --- |
| 定义 | 结果**依赖历史调用记录** | 结果**只由入参决定** |
| 例子 | `WebDriver`、`MessageWindowChatMemory`、带计数的对象 | `Math.abs`、`WorkflowContext.getContext`、`StringUtil` |
| 能否全局共享 | ❌ 共享则状态被覆盖 | ✅ 可安全全局共享 |
| 本项目 | 静态单例 `webDriver`（危险） | `WorkflowContext.getContext/saveContext`（安全） |

**判定口诀**：**结果是否只由入参决定？是 → 无状态，可共享；否 → 有状态，必须隔离。**

**为什么本项目 AI Service 是单例而主链路是 prototype？**

| 对比 | 主链路 AI Service | langgraph4j 的 AI Service |
| --- | --- | --- |
| 模型作用域 | `prototype`（每次新实例） | 单例（`openAiChatModel`） |
| 原因 | **持有多轮对话记忆**（有状态），共享会串话 | 都是**无状态单次调用**，单例安全 |

### 9.2 静态导致并发不安全（详细）

**核心**：`WebDriver` 的"当前导航到哪个页面"就是它的状态 → 多线程共享 = 互相覆盖。

**这类问题的通用识别方法**（三步）：

```
① 这个东西被几个线程访问？（是静态/单例/缓存 → 多线程）
② 它内部有没有可变状态？（字段会不会被 set）
③ 有没有保护？（锁 / 原子类 / 不可变 / 每线程一份）
三个都命中 → 并发 bug
```

**解决方案四种**：

| 方案 | 做法 | 适用 |
| --- | --- | --- |
| 每线程/每请求一份 | 不复用，每次 new | 创建成本低 |
| 加锁串行化 | `ReentrantLock` 包住临界区 | 低频、资源昂贵（**本项目截图的最佳解**） |
| 用无状态替代 | 把状态外移到参数 | 可行时最优 |
| 资源池化 | 固定 N 个，借还 | 高频、资源昂贵 |

### 9.3 竞态的本质与四种修法

**本质**：`count++` 是**读-改-写三步**，中间可能被别的线程插一脚。

**修法**：把"读-改-写"变成**不可被打断的一个整体**。

| 方案 | 原理 | 适用 |
| --- | --- | --- |
| `synchronized` | 互斥（同一时刻只有一个线程能进） | 简单；**JDK 21 配虚拟线程会"钉住"** |
| `ReentrantLock` | 同上，但可中断/可超时/公平锁 | 需保护一段代码块；**虚拟线程场景优先** |
| `AtomicLong` / `LongAdder` | **无锁 CAS**（硬件指令保证原子） | 简单计数/累加，高并发通常最快 |
| `ConcurrentHashMap` | 分段/CAS 并发容器 | 共享 Map 读写 |

### 9.4 异步 vs 串行；线程池 vs MQ 削峰（前置会话）

**什么时候需要异步？判断顺序**：

```
① 这件事是否必须"立刻"返回结果给用户？
   → 是  → 同步（如查询接口）
   → 否  → 走异步（如"部署后生成封面"）

② 这个功能是否"全局只能有一个在跑"？
   → 是  → 全局唯一 + 锁（如共享 WebDriver 截图）
   → 否  → 可以并发

③ 并发量有多大？
   → 每分钟几十   → 线程池 / 虚拟线程即可
   → 每分钟几万   → MQ 削峰 + 批量写库
```

**"每分钟几十 → 线程池；每分钟几万 → MQ + 写库"怎么理解**：

| | 线程池 / 虚拟线程 | MQ + 写库 |
| --- | --- | --- |
| 并发在哪 | **进程内**（JVM 线程） | **跨进程**（消息中间件） |
| 能否扛住突发 | ❌ 突发会把线程/DB 打挂 | ✅ 消息先入队，按消费能力慢慢处理 |
| 数据会不会丢 | ❌ JVM 挂了任务就没了 | ✅ 消息持久化，可重投 |
| 复杂度 | 低 | 高（要处理重复消费、顺序、死信） |
| 适用 | 低并发、可容忍丢失 | 高并发、不可丢失、需要削峰填谷 |

**异步的四种落地方式（按使用频率）**：

| 方式 | 说明 | 本项目 |
| --- | --- | --- |
| `Thread.startVirtualThread` | 最轻，适合一次性 IO 任务 | ✅ 用于截图、跑图 |
| 线程池（`ExecutorService`） | 可控制并发数、可复用 | langgraph4j 图2 用过 |
| `CompletableFuture` | 编排多个异步任务、组合结果 | ✅ `ImageCollectorNode` 用过 |
| MQ（RocketMQ/Kafka/RabbitMQ） | 跨进程、削峰、可靠投递 | ❌ 未使用 |

**⚠️ 异步的铁律**：**异步任务必须显式捕获异常并记录日志，禁止静默失败。**（本项目 `generateAppScreenshotAsync` 违反此条 → 见 6.8）

### 9.5 虚拟线程

**名称**：正式名 **Virtual Thread（虚拟线程）**，JEP 444，**JDK 21 正式发布**。叫"虚拟"是因为它**不直接对应操作系统线程**。

```
传统平台线程：1 个 Java 线程 = 1 个 OS 内核线程
虚拟线程（M:N）：N 个虚拟线程 复用 M 个 OS 线程

    虚拟线程1 ─┐
    虚拟线程2 ─┼─► 载体线程(Carrier)1 ──► OS线程 ──► CPU
    虚拟线程3 ─┤
    虚拟线程4 ─┴─► 载体线程(Carrier)2 ──► OS线程 ──► CPU
```

**载体线程（Carrier Thread）** = 背后真正干活的平台线程，来自 `ForkJoinPool`（默认 `CPU 核数` 个）。

**为什么"轻"（三个技术原因）**：

| 原因 | 平台线程 | 虚拟线程 |
| --- | --- | --- |
| **栈的位置** | OS 分配的内核栈，约 **1 MB** | **堆上的一组对象**（`StackChunk`），初始几百字节，**按需伸缩** |
| **线程数上限** | 几千个就吃满内存 | **百万级** |
| **谁来调度** | **操作系统内核**（切换陷入内核态，贵） | **JVM 自己调度**（切的是 Java 对象引用，便宜） |

**核心机制**：

```
虚拟线程遇到阻塞 IO（等 LLM、等 HTTP、等数据库）
        ▼
JVM 把「虚拟线程的栈」从载体线程"卸载"到堆里保存
        ▼
载体线程被【让出去】，立刻去跑别的虚拟线程  ← ★
        ▼
IO 回来了 → JVM 把栈"重新挂载"到一个空闲载体线程上继续跑
```

> **虚拟线程的价值不是"跑得更快"，而是"等 IO 的时候不占 OS 线程"。**

**"零成本"的真实含义** = **改造代码的成本为零**：

| 方案 | 要不要改代码 | 代价 |
| --- | --- | --- |
| 传统异步（Reactor / `CompletableFuture`） | **要**：改写成回调链、`flatMap`、事件流 | 可读性差、调试难（本项目主链路 `Flux` 到处飞） |
| 虚拟线程 | **不要**：照旧写阻塞代码 | 零改造成本，拿到近似异步的吞吐 |

**JDK 21 以上有没有更好的？**

| 特性 | 状态 | 说明 |
| --- | --- | --- |
| 虚拟线程 | JDK 21 正式 | 上面讲的 |
| **结构化并发 `StructuredTaskScope`** | JDK 21 起**预览** | 配套 API：把一组子任务当作一个整体管理（一起成功/取消），解决"虚拟线程满天飞、没人管" |
| **JEP 491** | **JDK 24** | 修掉 pin 问题 |

**⚠️ JDK 21 已知瑕疵（Pin 钉住）**：

```
虚拟线程在 synchronized 代码块里阻塞
    → 【无法被卸载】
    → 载体线程被钉住（pin），不能去干别的
    → 虚拟线程的优势在这段代码里【失效】
```

修法：JDK 21 里把 `synchronized` 换成 `ReentrantLock`；或升级到 JDK 24+。

**虚拟线程 vs 多线程**：

| 维度 | 传统多线程 | 虚拟线程 |
| --- | --- | --- |
| 本质 | 还是多线程 | 还是多线程（**同一套 `Thread` API**） |
| 数量 | 几百~几千 | 几十万~百万 |
| 适合 | CPU 密集、或并发量小的 IO 密集 | **高并发的阻塞式 IO** |
| 创建成本 | 高（内核调用 + 1MB 栈） | 低（堆对象 + 几百字节） |
| 能否解决并发安全 | ❌ 不能 | ❌ **一样不能** |

> **虚拟线程完全不能解决共享问题。它换的只是"线程贵不贵"，没有换"对象是否共享"。**
> 本项目用虚拟线程是因为"等 LLM 很慢"，**不是为了并发安全**。

### 9.6 分布式 vs 多线程

**项目里已有的分布式组件（容易被忽略）**：

| 组件 | 位置 | 为什么算"分布式" |
| --- | --- | --- |
| `RedissonClient` | `RedissonConfig` | 连的是**外部 Redis**，不是本机内存 |
| `RRateLimiter` | `RateLimitAspect` | 令牌桶**存在 Redis**，多台服务器共用一个桶 |
| `RedisChatMemoryStore` | `RedisChatMemoryStoreConfig` | 对话记忆**存 Redis**，换台机器还能读到 |
| `CosManager` | 对象存储 | 文件不存本地，存云端 |

**为什么需要分布式**：

```
【单机】所有线程在同一个 JVM 里 → 共享同一块堆内存
    ┌─────── JVM（1 台机器）───────┐
    │  堆：限流计数器 count = 3     │   ← 所有线程看得见、改得对
    └──────────────────────────────┘

【多机】同一个服务跑 3 个实例
    ┌── JVM-1 ──┐   ┌── JVM-2 ──┐   ┌── JVM-3 ──┐
    │ count=5   │   │ count=5   │   │ count=5   │
    └───────────┘   └───────────┘   └───────────┘
         ↑ 三个堆互相【看不见】↑
    结果：你以为限流 5 次/分钟，实际被放了 15 次
```

**解决方式：把共享状态外移到 Redis**

```
         ┌── JVM-1 ──┐   ┌── JVM-2 ──┐   ┌── JVM-3 ──┐
         └─────┬─────┘   └─────┬─────┘   └─────┬─────┘
               └───────────────┼───────────────┘
                    ┌──────────────────────┐
                    │   Redis（外部共享）    │
                    │   令牌桶: 5 个令牌     │  ← 唯一权威的那一份
                    └──────────────────────┘
```

**关键**：Redis 的命令是**原子的**（单线程处理命令），所以多实例同时 `tryAcquire` 也不会算错。

**多线程 vs 分布式对比**：

| 维度 | 多线程（进程内） | 分布式（跨进程） |
| --- | --- | --- |
| 并发范围 | 同一个 JVM 内的线程 | 多台机器 / 多个容器实例 |
| 共享介质 | **堆内存** | **外部中间件**（Redis / MySQL / MQ / ZooKeeper） |
| 同步工具 | `synchronized`、`ReentrantLock`、`Semaphore`、`ConcurrentHashMap` | `RLock`、`RRateLimiter`、`INCR`、`SETNX`、Lua 脚本 |
| 速度 | 纳秒级（内存） | 毫秒级（一次网络往返） |
| 复杂度 | 低 | 高（网络抖动、超时、锁续期、宕机） |
| 解决什么 | 同机并发 | **多机并发**（多线程解决不了的部分） |

> **核心结论：分布式不是替代多线程，是"多线程够不着的那部分"的补充。**
> - 单实例部署 → 用 `ReentrantLock` 就够，**没必要引入 Redis 锁**（徒增延迟）
> - 多实例部署 → 进程内锁失效，必须换成 Redis 锁

**分布式锁原理版（本项目未使用，仅作认知）**：

```java
RLock lock = redissonClient.getLock("lock:app:100:gen");
boolean locked = lock.tryLock(3, 30, TimeUnit.SECONDS);   // 最多等3s，持锁30s自动释放
if (!locked) { throw new BusinessException(TOO_MANY_REQUEST, "该应用正在生成中"); }
try {
    // 生成代码（同一 appId 串行执行）
} finally {
    lock.unlock();
}
```

原理只有两步：

1. **加锁** = Redis 里 `SET lock:xxx 唯一值 NX PX 30000`（NX = 不存在才写 → 抢占成功）
2. **解锁** = 用 Lua 脚本「比对唯一值 + 删除」，保证不会误删别人的锁

> **记住一句话：分布式同步 = 把"内存里的一个标记位"换成"Redis 里的一个 key"。**

### 9.7 作为学习者，企业级要学到什么程度

> **现阶段"理解概念 + 能识别坑"就够，不需要手写生产级实现。**

| 层级 | 内容 | 建议 |
| --- | --- | --- |
| 🔴 **必须真懂** | 栈/堆、引用 vs 对象、静态 vs 实例、有状态 vs 无状态、什么算线程安全 | 配实验，彻底搞透 |
| 🟠 **必须会用** | `synchronized`、`ReentrantLock`、`ConcurrentHashMap`、线程池、`CompletableFuture`、`volatile` | 能写出可跑的代码 |
| 🟡 **会用一种分布式组件** | Redis（`RLock` / `RRateLimiter` / 缓存） | 项目需要时现学 |
| 🟢 **知道名词 + 会用现成注解即可** | Resilience4j 熔断、Sentinel、Bulkhead、K8s 扩缩容 | 面试能讲清"是什么、为什么" |
| ⚪ **可以先跳过** | ZooKeeper、Seata 分布式事务、Service Mesh | 等工作需要 |

**分阶段路线**：

| 阶段 | 目标 | 产出 |
| --- | --- | --- |
| ① JVM 内存模型 | 能画出栈/堆图 | 手画一遍 + 跑 `experiments/` |
| ② Java 并发基础 | 写得出线程不安全的例子，也修得掉 | 用 5 种方式修同一个计数器 |
| ③ 回到本项目对照 | 指着每个坑说"这是哪一层的共享导致的" | 独立找出 `WebDriver`、`chatMemory` 两处 |
| ④ 分布式入门 | 用 Redis 实现一个分布式计数器/锁 | 跑通一次 |

---

## 第十部分 · 实验文件与实测数据

### 10.1 文件清单

目录：`experiments/`（在 `src/` 之外，**不参与项目构建**）

| 文件 | 验证内容 | **实测结果** |
| --- | --- | --- |
| `Exp1_MemoryLayout.java` | 栈/堆/方法区、实例字段 vs 静态字段 | 两对象地址不同（`796667727` vs `1794717576`）；静态字段三处读到同一个 `999` |
| `Exp2_RaceCondition.java` | 两线程共享堆对象 → 竞态 | 期望 `2000000`，实际 `1895145`，**丢了 104855** |
| `Exp3_StaticVsParam.java` | 无状态静态方法 vs 静态字段 | 纯函数 `1000001000000` **完全正确**；静态字段丢了 **972663** |
| `Exp4_VirtualThreads.java` | 虚拟线程 vs 平台线程（同为等 IO） | 虚拟线程 **352ms**，200 线程池 **7830ms** → **22.2 倍** |
| `Exp5_FixRace.java` | 修竞态四种方案 | 裸 `++` 丢 52186；`synchronized` / `ReentrantLock` / `AtomicLong` **全部正确** |

### 10.2 运行方式

```powershell
java e:\AI\GitHub\yu-ai-code-mother\experiments\Exp1_MemoryLayout.java
```

若提示找不到 `java`：

```powershell
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

**无需编译**（JDK 11+ 单文件源码启动）。

### 10.3 建议的跑法顺序

```
Exp1  → 建立"栈存地址、堆存对象、静态字段全局一份"的直觉
Exp2  → 亲眼看到共享的代价
Exp3  → 搞清"静态方法不背锅，静态字段才背锅"
Exp5  → 看清"怎么修"（接上项目里给 webDriver 加锁的改法）
Exp4  → 明白虚拟线程到底省了什么（建议最后跑，最耗时）
```

### 10.4 三个最值得记住的数字

1. **Exp2：`2000000` → `1895145`** —— 这就是"共享堆对象"的代价。
2. **Exp3①：`1000001000000` 精确相等** —— 同样叫"静态方法"，只碰入参的那个一次都没错。这正是 `WorkflowContext.getContext(state)` 安全、而 `WebScreenshotUtils` 不安全的全部原因。
3. **Exp4：`352ms` vs `7830ms`** —— 同样的工作量、同样的等待时间，只因为线程数不同就差了 22 倍。

### 10.5 注意

- 竞态是**概率事件**。Exp2 / Exp3 / Exp5 若某次刚好没丢数，**多跑几次**必然能看到。
- `Exp4` 里的 `TASK_COUNT` 改成 `100_000` 会更震撼（固定线程池版本会跑很久，可 Ctrl+C 中断）。
- 控制台若出现 `?` 乱码，是 Windows 代码页问题；本套文件已改用纯文本标记（`[正确]` / `[错误]` / `【注意】`）。

---

## 第十一部分 · 项目锚点速查表

| 知识点 | 项目位置 |
| --- | --- |
| 图编排 / 条件边 / 重试自环 | `langgraph4j/CodeGenWorkflow.java` |
| 并发图 / 并行节点执行器 | `langgraph4j/CodeGenConcurrentWorkflow.java` |
| 子图复用 | `langgraph4j/CodeGenSubgraphWorkflow.java` |
| 状态容器 / 原地修改 | `langgraph4j/state/WorkflowContext.java` |
| 节点 = 静态工厂返回 lambda | `langgraph4j/node/*.java`（`create()`） |
| 静态服务定位器反模式 | `utils/SpringContextUtil.java` |
| 模型多例（prototype） | `config/StreamingChatModelConfig.java` |
| AI 服务缓存（按 appId） | `ai/AiCodeGeneratorServiceFactory.java` |
| 对话记忆 + Redis 存储 | `config/RedisChatMemoryStoreConfig.java` |
| 分布式限流 | `ratelimter/aspect/RateLimitAspect.java` + `config/RedissonConfig.java` |
| **静态单例 WebDriver（真 bug）** | `utils/WebScreenshotUtils.java` |
| **异步静默失败（真 bug）** | `service/impl/AppServiceImpl.java`（`generateAppScreenshotAsync`） |
| ThreadLocal（值在堆里的 Thread 对象上） | `monitor/MonitorContextHolder.java` |
| 登录用户来源（Session + 每请求查库） | `service/impl/UserServiceImpl.java`（`getLoginUser`） |
| 落盘根目录 | `constant/AppConstant.java`（`CODE_OUTPUT_ROOT_DIR`） |
| 生成类型枚举 | `model/enums/CodeGenTypeEnum.java` |
| 硬编码 `appId = 0L` | `langgraph4j/node/CodeGeneratorNode.java` |
| 工具层（`@Tool` / `@P`） | `langgraph4j/tools/*.java` |
| AI 声明式接口 + 外置提示词 | `langgraph4j/ai/*.java` |

---

## 第十二部分 · 心法 & Checklist

### 12.1 心法（十条）

1. **看任何并发问题，先问两句：「堆上有几个对象？」「几个线程的引用指向了同一个？」** 想通这两句，`synchronized`、`prototype`、缓存、分布式锁全都只是答案的不同形式。
2. **"线程隔离"是个陷阱说法。** 线程隔离的只有**栈**；对象从来不属于任何线程，它属于**堆**。
3. **静态方法不背锅，静态字段才背锅。** 判断安全性，看你碰的是"入参"还是"全局字段"。
4. **图编排的价值不是"少写代码"，而是"让流程可观测、可复用、可回环"。** 重试不需要 retry 框架，一条边就够了。
5. **确定性的活别交给 LLM**（如 `PromptEnhancerNode` 的字符串拼接）；**非确定性的活别硬写规则**（如类型路由优先用 AI）。
6. **缓存一定会引入共享状态。** 加缓存时同时要问一句"共享了会不会串"。
7. **企业级和教学级的核心差别不是"用没用线程池"，而是"有没有为失败设计"** —— 限并发、超时、熔断、降级、可观测，这些全是"失败时才起作用"的东西。
8. **异步的铁律**：必须显式捕获异常并记录日志，禁止静默失败。
9. **虚拟线程只是"线程便宜"，不解决并发安全。** 它换的是成本，不是共享。
10. **内存模型这种东西，画图 + 跑实验比读文档有效 10 倍。**

### 12.2 Checklist

**A. 图编排（langgraph4j）**

- [ ] 能画出三套图的 ASCII 结构（串行 / 并发 / 子图）
- [ ] 能说清 `WorkflowContext` 每个字段被谁写、被谁读
- [ ] 能解释 `fail → code_generator` 这条自环为什么等价于"重试框架"
- [ ] 能在图上指出"汇聚点 `image_aggregator` 为什么天然安全"
- [ ] 知道"图级并发只能并行到节点这一层"这个粒度上限
- [ ] 能说出至少 3 条坑并指到源码

**B. 状态管理**

- [ ] 能画出 `CompiledGraph → NodeOutput → MessagesState → AgentState.data(Map) → WorkflowContext` 五层
- [ ] 能说出 `MessagesState.SCHEMA` 里只有 `"messages"` 一个 key，且是 appender 累积语义
- [ ] 能解释 `"workflowContext"` 没有 Channel → **覆盖语义**
- [ ] 能说清"同一个 `WorkflowContext` 实例从 START 走到 END"及不可回溯问题
- [ ] 能说出 `implements Serializable` 是为 checkpoint 预留

**C. static 与 new**

- [ ] 能对任意一段代码用五条标准判断该 `static` 还是 `new`
- [ ] 能解释为什么 `ImageCollectorNode.create()` 内部必须用 `SpringContextUtil.getBean`
- [ ] 能说出"自己 new 一个被 Spring 管理的类"会丢哪三样东西

**D. JVM 内存模型**

- [ ] 能一句话说清"线程私有的边界"和"共享的边界"
- [ ] 能说出实例字段/静态字段分别住哪、几份
- [ ] 能说清 `ThreadLocal` 的值其实住在堆里的 `Thread` 对象上
- [ ] 能画出一次请求中 `prompt`（栈）→ `User`（堆）→ `chatMemory` 的 List（堆）→ `webDriver`（方法区）各自位置
- [ ] 能说清 JDK 8+ 元空间在 Java 堆之外（本地内存）
- [ ] 能说清对象头 `klass` 指针是**间接**指向方法区
- [ ] 能纠正"静态方法不能访问实例成员是因为方法区访问不了堆" → 真正原因是**没有 `this`**
- [ ] 能说出类加载是延迟的，并指出 `WebScreenshotUtils` 静态块会在"第一次截图"时冷启动 Chrome

**E. 并发与分布式**

- [ ] 能解释为什么单例模型会被多线程共享，而 prototype 不会
- [ ] 能说出 Caffeine 缓存为什么会引入共享（而不是"多线程造成了共享"）
- [ ] 能说清虚拟线程"轻"的三个技术原因，以及 JDK 21 的 pin 缺陷
- [ ] 能解释多机部署时本地限流为什么失效，以及 Redis 为什么能解决
- [ ] 能说出竞态的四种修法与各自适用场景

### 12.3 面试追问预演

| 追问 | 答题骨架 |
| --- | --- |
| 为什么用图编排而不是 if-else / 状态机？ | 可观测（`NodeOutput`）+ 可视化（MERMAID）+ 回环重试（条件边）+ 复用（子图）；代价是学习成本和框架耦合 |
| 图里的并发安全吗？ | 分支**只写不重叠字段**才安全；共写字段（`currentStep`）就是不确定行为；汇聚点由拓扑保证 join 语义 |
| 质检失败无限重试怎么办？ | 现在没有上限（可指出源码），应配 `recursionLimit` 或加 `retryCount` 字段 |
| 这些 AI Service 为什么是单例？ | 无状态（入参决定输出），对比主链路持记忆的 Service 用 prototype |
| 多个用户同时用 AI，你怎么保证不串？ | 分五层：① 模型实例 prototype / 或官方线程安全单例；② **会话记忆按 sessionId 隔离**，同会话串行；③ 舱壁隔离 + 并发上限；④ 下游资源池化加锁；⑤ 分布式限流保额度 |
| 为什么 AI Service 要缓存而不是每次新建？ | 它持有对话记忆，创建要查库回填历史；不缓存会让每次请求多一次 DB + Redis 往返，且丢失多轮上下文 |
| 限流为什么用 Redisson 而不是本地 Guava？ | 多实例部署时本地计数器各算各的，实际并发是 N 倍；`RRateLimiter` 是**全局共享桶**，多副本也准 |
| 虚拟线程能解决并发问题吗？ | ❌ 不能。它只降低"线程成本"，共享的堆对象照样互相覆盖；并发安全要靠隔离/锁/原子类/不可变 |
| 静态方法为什么不能访问实例成员？ | 实例方法编译后等价于 `method(this, 其它参数...)`，`this` 是隐式的第一个参数；静态方法没有这个参数，所以拿不到对象 |

---

## 附录 · 术语表

| 术语 | 含义 |
| --- | --- |
| **栈（Stack）** | 线程私有区域，存放局部变量、方法参数、引用 |
| **堆（Heap）** | 所有线程共享，存放对象实例、实例字段、数组、String |
| **方法区 / 元空间（Metaspace）** | 所有线程共享，存放类元信息、字节码、静态字段、常量池；JDK 8+ 在本地内存 |
| **引用（Reference）** | 栈里存放的"对象地址"，不是对象本体 |
| **实例字段** | 不带 `static` 的成员变量，每个对象一份 |
| **静态字段** | 带 `static` 的成员变量，类级别全局一份 |
| **类加载** | JVM 首次主动使用某类时，加载其字节码并生成类元数据 + `Class` 对象（**延迟加载**） |
| **`klass` 指针** | 对象头里的类型指针，指向方法区中的类元数据 |
| **`this`** | 实例方法的隐式第一个参数，指向调用该方法的对象 |
| **`ThreadLocal`** | 线程隔离变量；其值存在 `Thread` 对象的 `threadLocals` 字段里（`Thread` 在堆） |
| **载体线程（Carrier）** | 虚拟线程背后真正执行的平台线程，来自 `ForkJoinPool` |
| **Pin（钉住）** | 虚拟线程在 `synchronized` 块中阻塞时无法卸载，导致载体线程被占用（JDK 24 已修） |
| **竞态（Race Condition）** | 多线程对共享可变状态做非原子操作导致结果不确定 |
| **CAS** | Compare-And-Swap，硬件级原子指令，无锁并发的基石 |
| **舱壁隔离（Bulkhead）** | 为不同业务分配独立资源池，防止互相拖垮 |
| **熔断（Circuit Breaker）** | 下游连续失败后快速失败一段时间，避免雪崩 |
| **分布式锁** | 用外部中间件（Redis 等）实现跨进程互斥 |
| **checkpoint（检查点）** | LangGraph 的状态持久化机制，支持断点续跑 / 时间旅行 |
| **Bulkhead/舱壁、Sentinel、Resilience4j** | 主流容错治理组件 |

---

> 笔记完。配套实验在 `experiments/`，跑一遍比读三遍有效。
