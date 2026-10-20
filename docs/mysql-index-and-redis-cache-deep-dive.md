# MySQL 索引 与 Redis 缓存 · 专项深挖

> 定位：这是 [backend-knowledge-supplement.md](./backend-knowledge-supplement.md) 第 C 章、第 D 章的**展开版**。
> 补充手册给的是"知道有这回事"，这份给的是"被追问三层还能答上来"。
>
> 阅读方式：**每节都是「原理 → 图解 → 本项目锚点 → 面试追问 → 答案」**。
> 建议一天只啃一节，啃完去原项目里把对应 SQL / 代码找出来看一眼。

---

## 目录

```
第一部分 · MySQL 索引
  1. 为什么需要索引：从一次磁盘 IO 算起
  2. InnoDB 的存储层级：表空间 → 页 → 行
  3. B+ 树到底长什么样
  4. 聚簇索引 vs 二级索引
  5. 一次查询的完整旅程（含回表）
  6. 覆盖索引与索引下推
  7. 联合索引与最左前缀（图解）
  8. 索引失效的 10 种场景
  9. EXPLAIN 逐字段精读
  10. 索引设计方法论
  11. 分页优化的三种写法
  12. 本项目索引实战：chat_history 怎么建
  13. 索引的代价：页分裂与写放大

第二部分 · Redis 缓存
  14. Redis 为什么快（以及"单线程"的真相）
  15. 五种数据结构的底层实现
  16. 过期删除与内存淘汰
  17. 缓存穿透（含布隆过滤器数学）
  18. 缓存击穿
  19. 缓存雪崩
  20. 缓存一致性：为什么"先更库再删缓存"
  21. 四种缓存模式
  22. 分布式锁：从手写到 Redisson
  23. RedLock 的世纪争议
  24. 缓存三大工程的落地清单
  25. 本项目 Redis 全景图
  26. 大 key 与热 key

第三部分 · 交叉
  27. 多级缓存：Caffeine + Redis 怎么配合
  28. 两章合并的 30 道连环追问
```

---

# 第一部分 · MySQL 索引

---

## 1. 为什么需要索引：从一次磁盘 IO 算起

**先算一笔账**：

| 操作 | 耗时（量级） |
|---|---|
| CPU 执行一条指令 | 0.3 ns |
| 访问 L1 缓存 | 1 ns |
| 访问内存 | 100 ns |
| **SSD 随机读 4KB** | **~100 μs（10 万 ns）** |
| **机械硬盘随机读** | **~10 ms（1000 万 ns）** |

**结论**：一次磁盘随机读 ≈ 10 万次内存访问。

所以数据库优化的核心从来不是"算法快不快"，而是**"能不能少读几次磁盘"**。

**全表扫描的代价**：
一张 1000 万行、每行 1KB 的表 ≈ 10 GB。全表扫 = 读 10 GB ≈ 62 万个 16KB 页。
即使 SSD 也要 60 秒以上。

**索引的本质**：**用额外的有序结构，把"扫描 N 页"变成"读 3~4 页"**。

---

## 2. InnoDB 的存储层级：表空间 → 页 → 行

```
表空间（.ibd 文件）
  └── 段（Segment）：叶子段 / 非叶子段 / 回滚段
        └── 区（Extent）：1 区 = 64 个页 = 1 MB
              └── 页（Page）：16 KB  ← ★ 磁盘 IO 的最小单位
                    └── 行（Row）
```

**为什么是 16KB**：
- 太小 → IO 次数多
- 太大 → 一次读进来用不上，浪费带宽
- 16KB 是经验值，可通过 `innodb_page_size` 调整（4K/8K/16K/32K/64K）

**★ 关键认知**：**InnoDB 每次从磁盘读的是"页"，不是"行"**。
所以"查 1 行"和"查这个页里相邻的 100 行"，磁盘成本是一样的。

**一页里有什么**（简化）：

```
┌──────────────────────────────────────┐
│ File Header（38B）：页号、上一页、下一页 │
├──────────────────────────────────────┤
│ Page Header（56B）：记录数、堆顶指针     │
├──────────────────────────────────────┤
│ Infimum + Supremum（虚拟行，边界）     │
├──────────────────────────────────────┤
│ 用户记录（按主键有序，单链表）           │
│  ...                                  │
├──────────────────────────────────────┤
│ Page Directory（页目录，稀疏槽位）      │
├──────────────────────────────────────┤
│ File Trailer（8B）：校验和              │
└──────────────────────────────────────┘
```

**页目录是"页内二分查找"的关键**：它把页内的行分组，每组 4~8 行，记录每组的最大主键。查找时先在目录里二分定位到组，再在组内遍历。

---

## 3. B+ 树到底长什么样

### 3.1 结构图

```
                         ┌─────────────────────┐
                         │  根页（非叶子）        │
                         │  [10 | 30 | 50]      │
                         └───┬────┬────┬────┬───┘
                    ┌────────┘    │    │    └────────┐
                    ▼             ▼    ▼             ▼
              ┌──────────┐  ┌──────────┐ ┌──────────┐ ┌──────────┐
              │[1|5|8]   │  │[12|20]   │ │[35|45]   │ │[55|80]   │  ← 非叶子层
              └──┬──┬──┬─┘  └──┬───┬───┘ └──┬───┬───┘ └──┬───┬───┘
                 ▼  ▼  ▼       ▼   ▼        ▼   ▼        ▼   ▼
              叶子页（存整行数据或主键，且用双向链表串起来）
              ┌────┐ ↔ ┌────┐ ↔ ┌────┐ ↔ ┌────┐ ↔ ...
              │1,5,8│   │12,20│  │35,45│  │55,80│
              └────┘   └────┘   └────┘   └────┘
```

### 3.2 三个必须记住的特征

| 特征 | 含义 | 带来的能力 |
|---|---|---|
| **非叶子只存 key + 指针** | 不存数据行 | 扇出大（一页能放上千个指针），树矮 |
| **所有数据在叶子** | 查询路径长度恒定 | 任何 key 的查询都是 O(树高) |
| **叶子之间有双向链表** | 范围查询沿链表走 | `BETWEEN` / `ORDER BY` / `>` 高效 |

### 3.3 树高估算（面试常问）

假设：
- 一行 1 KB → 叶子页存 16 行
- 非叶子项：主键 8B（bigint）+ 页号 6B = 14B → 16KB / 14B ≈ **1170 个指针/页**

```
2 层：1170 × 16          ≈ 1.9 万行
3 层：1170 × 1170 × 16   ≈ 2190 万行
4 层：1170³ × 16         ≈ 256 亿行
```

**这就是"千万级表 3 次 IO 就能定位"的来源。**

而二叉树要 log₂(1000万) ≈ 24 层 → 24 次 IO。

### 3.4 为什么不用 B 树

| | B 树 | B+ 树 |
|---|---|---|
| 数据存放 | 非叶子也存数据 | 只有叶子存 |
| 扇出 | 小（因为存了数据） | 大 |
| 范围查询 | 需要中序遍历回溯 | 叶子链表直接扫 |
| 查询稳定性 | 不同 key 层数不同 | 所有 key 都到叶子，层数一致 |

---

## 4. 聚簇索引 vs 二级索引

### 4.1 两棵树

```sql
CREATE TABLE app (
    id BIGINT PRIMARY KEY,
    appName VARCHAR(256),
    userId BIGINT,
    KEY idx_userId (userId)
);
```

**聚簇索引（clustered index）** = 主键索引，叶子存**整行**：

```
叶子页：[id=1, appName='博客', userId=10] → [id=2, ...] → ...
```

**二级索引（secondary index）** = `idx_userId`，叶子存**主键值**：

```
叶子页：[userId=10, id=1] → [userId=10, id=5] → [userId=11, id=3] → ...
```

### 4.2 ★ 关键结论

| 问题 | 答案 |
|---|---|
| 表数据存在哪 | **只在聚簇索引的叶子节点** |
| 一张表有几个聚簇索引 | **1 个**（就是主键） |
| 没有主键怎么办 | InnoDB 用第一个非空唯一索引；都没有就生成 6 字节隐藏 `ROW_ID` |
| 二级索引查询完就结束了吗 | ❌ 拿到主键后还要回聚簇索引查整行（**回表**） |
| 为什么建议用自增主键 | 顺序插入不引发页分裂；且二级索引叶子更小 |

### 4.3 主键选择的权衡（本项目正好踩在中间）

| 主键类型 | 优点 | 缺点 |
|---|---|---|
| 自增 BIGINT | 顺序插入、页填充率高、索引小 | 可枚举（暴露业务量）、分库分表冲突 |
| UUID | 分布式唯一 | **完全随机 → 严重页分裂**、索引大（36B） |
| **雪花 ID** | 分布式唯一、大致有序、不可枚举 | 19 位（JS 精度）、写入仍有局部热点 |

**本项目**：[App.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/model/entity/App.java#L37-L38) 用雪花：

```java
@Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
private Long id;
```

**面试追问**：雪花 ID 是"有序"的吗？
**答**：**趋势有序**，不是严格有序。同一毫秒内的 ID 递增，但不同机器生成的 ID 会交错。相比 UUID 好很多，相比自增仍有页分裂，但分摊到不同区段，实际影响可接受。

---

## 5. 一次查询的完整旅程（含回表）

```sql
SELECT appName FROM app WHERE userId = 10;
```

```
① 解析 SQL，选索引 idx_userId
② 从 idx_userId 的根页开始（页号来自数据字典，通常在内存）
   → 二分：userId=10 落在哪个子页
③ 读子页（可能还在 buffer pool，miss 则磁盘 IO）
   → 继续二分
④ 到达叶子页，找到 [userId=10, id=1]、[userId=10, id=5]
⑤ ★ 回表：拿 id=1 / id=5 去聚簇索引再查一次
   → 又是 3 层 B+ 树
⑥ 从聚簇索引叶子取出整行，投影出 appName
⑦ 返回
```

**IO 次数**：约 3（二级索引）+ 3×N（回表，N = 匹配行数）。

**这就是"回表"的代价**：匹配的行越多，回表次数越多。

---

## 6. 覆盖索引与索引下推

### 6.1 覆盖索引（Covering Index）

**定义**：查询需要的列**全部**在索引里 → 不需要回表。

```sql
-- 索引 idx_userId(userId)，叶子存 (userId, id)
SELECT id FROM app WHERE userId = 10;          -- ✅ 覆盖（id 在二级索引叶子里）
SELECT userId FROM app WHERE userId = 10;      -- ✅ 覆盖
SELECT appName FROM app WHERE userId = 10;     -- ❌ 要回表（appName 不在索引）
```

**EXPLAIN 标志**：`Extra: Using index`

**如何利用**：

```sql
-- 索引 (appId, createTime, messageType)
SELECT id, createTime FROM chat_history
WHERE appId = 1 ORDER BY createTime DESC;
-- ↑ 三列都在索引里 → 不回表
```

**★ 覆盖索引是"用空间换时间"最划算的手段**：多加一列到索引，可能省掉所有回表。

### 6.2 索引下推（ICP, Index Condition Pushdown）

MySQL 5.6+。**在存储引擎层用索引里的列先过滤**，减少回表次数。

```sql
-- 索引 idx_name_age(name, age)
SELECT * FROM user WHERE name LIKE '张%' AND age = 25;
```

**没有 ICP**：
1. 用 `name LIKE '张%'` 在索引里找到 1000 条
2. **回表 1000 次**取 age
3. Server 层再过滤 age=25 → 剩 10 条

**有 ICP**：
1. 用 `name` 找到 1000 条
2. **在索引里直接用 age 过滤**（age 就在索引里）→ 剩 10 条
3. **只回表 10 次**

**EXPLAIN 标志**：`Extra: Using index condition`

**本质**：把"Server 层的过滤"下推到"存储引擎层"，减少无效回表。

---

## 7. 联合索引与最左前缀（图解）

### 7.1 联合索引怎么排序

`INDEX idx_abc (a, b, c)` 的排序规则是：

**先按 a 排序；a 相同的按 b 排序；a、b 都相同的按 c 排序。**

```
(a=1,b=1,c=1)
(a=1,b=1,c=5)
(a=1,b=2,c=1)   ← a 相同，b 有序
(a=1,b=2,c=3)
(a=2,b=1,c=2)   ← a 变了，b 重新从 1 开始
(a=2,b=1,c=9)
(a=3,b=1,c=1)
```

**★ 所以 (a, c) 是无序的**（在 a 相同的分组内，c 的顺序取决于 b）。

### 7.2 最左前缀法则

| 查询条件 | 能否用索引 | 用到哪些列 |
|---|---|---|
| `a=1` | ✅ | a |
| `a=1 AND b=2` | ✅ | a, b |
| `a=1 AND b=2 AND c=3` | ✅ | a, b, c |
| `a=1 AND c=3` | ⚠️ | 只用到 a（c 无法用于定位，但可用于 ICP 过滤） |
| `b=2` | ❌ | 完全用不上 |
| `b=2 AND c=3` | ❌ | 完全用不上 |
| `a=1 AND b>2 AND c=3` | ⚠️ | a、b 用于范围；c 只能 ICP 过滤 |

**★ 关键理解**：**范围查询之后，后面的列无法用于"定位"，但可以用于"过滤"（ICP）**。

### 7.3 最左前缀的一个常见误解

```sql
-- 索引 (a, b)
WHERE b = 2 AND a = 1     -- ✅ 优化器会自动重排，等价于 a=1 AND b=2
WHERE a = 1 ORDER BY b    -- ✅ 索引本身就是 (a,b) 有序，无需额外排序
WHERE b = 2 ORDER BY a    -- ❌ 用不上（b 不是最左）
```

**优化器会重排 WHERE 条件的顺序，但不会重排 ORDER BY。**

### 7.4 联合索引列顺序怎么定

三条原则：

1. **等值查询列在前，范围查询列在后**（否则范围后面的列失效）
2. **区分度高的列在前**（能更快缩小范围）
3. **要覆盖查询的列尽量都放进去**

```sql
-- 场景：WHERE appId = ? AND createTime < ? ORDER BY createTime DESC
-- 正确顺序：等值在前，范围在后
INDEX idx (appId, createTime)      -- ✅
INDEX idx (createTime, appId)      -- ❌ createTime 是范围，appId 用不上
```

---

## 8. 索引失效的 10 种场景

| # | 场景 | 例子 | 原因 |
|---|---|---|---|
| 1 | 函数包裹列 | `WHERE YEAR(createTime) = 2026` | 索引存的是原值，函数后无索引 |
| 2 | 表达式运算 | `WHERE id + 1 = 10` | 同上 |
| 3 | 隐式类型转换 | `WHERE userId = '123'`（userId 是 bigint） | 字符串转数字再比，等价于函数 |
| 4 | 前导模糊 | `LIKE '%abc'` | B+ 树只能从左边匹配 |
| 5 | OR 连接非索引列 | `WHERE a=1 OR b=2`（b 无索引） | 另一侧要全表扫，优化器直接放弃 |
| 6 | 联合索引跳过最左列 | `WHERE b=2`（索引是 a,b） | 索引按 a 排序，b 无序 |
| 7 | 范围后面的列 | `WHERE a>1 AND b=2`（索引 a,b） | b 只能 ICP |
| 8 | `!=` / `NOT IN` / `NOT LIKE` | `WHERE status != 1` | 结果集通常太大，优化器选全扫 |
| 9 | `IS NULL`（列定义为 NOT NULL 时不会） | `WHERE name IS NULL` | 视统计信息而定 |
| 10 | **区分度太低** | `WHERE isDelete = 0`（99% 是 0） | 走索引反而更慢（回表多） |

**★ 第 10 条最容易被忽略**：
如果一列 99% 的值都是 0，查 `= 0` 时优化器会直接全表扫，因为"回表 99 万次"比"顺序扫 10 万页"更贵。

**验证方法**：`EXPLAIN` 里看 `type` 是不是 `ALL`，`possible_keys` 有但不代表一定用。

---

## 9. EXPLAIN 逐字段精读

```sql
EXPLAIN SELECT * FROM chat_history
WHERE appId = 1 AND createTime < '2026-10-20 10:00:00'
ORDER BY createTime DESC
LIMIT 20;
```

| 列 | 含义 | 关注点 |
|---|---|---|
| `id` | 查询序号 | 大的先执行 |
| `select_type` | SIMPLE / PRIMARY / SUBQUERY / DERIVED / UNION | 有 DERIVED 说明有子查询物化 |
| `table` | 表名 | |
| `partitions` | 命中分区 | 分区表才看 |
| `type` | **访问类型（最重要）** | 见下方等级表 |
| `possible_keys` | 可能用的索引 | 有值不代表一定用 |
| `key` | **实际用的索引** | 为 NULL = 没用索引 |
| `key_len` | 用了索引的多少字节 | 判断用了联合索引的哪几列 |
| `ref` | 与索引比较的列/常量 | |
| `rows` | 估算扫描行数 | 越小越好 |
| `filtered` | 过滤后剩余百分比 | 越低说明过滤性越好 |
| `Extra` | **额外信息（第二重要）** | 见下方 |

### 9.1 `type` 等级（从好到坏）

| type | 含义 | 例子 |
|---|---|---|
| `system` | 表只有一行 | 系统表 |
| `const` | 主键/唯一索引等值 | `WHERE id = 1` |
| `eq_ref` | 联表时被驱动表用主键 | `JOIN ... ON a.id = b.id` |
| `ref` | 非唯一索引等值 | `WHERE userId = 10` |
| `range` | 索引范围扫描 | `WHERE id > 10`、`BETWEEN`、`IN` |
| `index` | **全索引扫描** | 遍历整个索引树（比 ALL 好一点，因为索引小） |
| `ALL` | **全表扫描** | 最差 |

**目标：至少 `range`，常见业务查询应该到 `ref`。**

### 9.2 `Extra` 常见值

| 值 | 含义 | 好坏 |
|---|---|---|
| `Using index` | **覆盖索引，不回表** | ⭐ 最好 |
| `Using index condition` | 索引下推（ICP） | ✅ 好 |
| `Using where` | Server 层过滤 | ⚠️ 一般 |
| `Using filesort` | **额外排序**（内存或磁盘） | ❌ 要优化 |
| `Using temporary` | **用了临时表** | ❌ 要优化 |
| `Using join buffer` | 联表没走索引 | ❌ 要优化 |
| `Using MRR` | 多范围读，回表前排序 | ✅ 好 |

**`Using filesort` 是最常见的性能杀手**：
`ORDER BY` 的列顺序和索引顺序不一致时，MySQL 要把结果读出来再排序。数据量大就落磁盘。

### 9.3 `key_len` 计算（面试常考）

| 类型 | 字节数 |
|---|---|
| `tinyint` | 1 |
| `int` | 4 |
| `bigint` | 8 |
| `datetime` | 5（MySQL 5.6+） |
| `timestamp` | 4 |
| `char(n)` utf8mb4 | n × 4 |
| `varchar(n)` utf8mb4 | n × 4 **+ 2**（变长长度） |
| 允许 NULL | **+1** |

**用法**：索引 `(appId BIGINT NOT NULL, createTime DATETIME NULL)`：
- 只用到 appId：`key_len = 8`
- 用到 appId + createTime：`key_len = 8 + 5 + 1 = 14`

看到 `key_len` 就能反推"联合索引用到了第几列"。

---

## 10. 索引设计方法论

### 10.1 五步法

```
① 找出所有高频查询（慢查询日志 / 业务梳理）
② 对每条查询，列出 WHERE / ORDER BY / JOIN ON 的列
③ 按"等值在前、范围在后、排序列跟上"组合
④ 评估区分度，区分度 < 0.1 的列单独建索引意义不大
⑤ 用 EXPLAIN 验证，确保 type ≥ range 且无 filesort
```

### 10.2 区分度

```sql
SELECT COUNT(DISTINCT col) / COUNT(*) AS selectivity FROM t;
```

| 区分度 | 评价 |
|---|---|
| > 0.5 | 很好 |
| 0.1 ~ 0.5 | 可用 |
| < 0.1 | 单独建索引意义不大，考虑组合 |

**反例**：`isDelete`、`status`（只有 3 个值）、`gender` —— 单独建索引几乎不会走。

### 10.3 前缀索引

```sql
-- 长字符串列，只索引前 10 个字符
ALTER TABLE user ADD INDEX idx_email (email(10));
```

**代价**：
- ❌ 不能用于覆盖索引
- ❌ 不能用于 `ORDER BY`
- ⚠️ 需要回表验证完整值

### 10.4 三个"不要"

| 不要 | 原因 |
|---|---|
| 不要给每个列都建索引 | 写放大 + 优化器选择困难 |
| 不要建重复/冗余索引 | `(a)` 和 `(a,b)` 中 `(a)` 是冗余的 |
| 不要用 UUID 做聚簇索引 | 页分裂严重 |

---

## 11. 分页优化的三种写法

### 11.1 问题：深分页

```sql
SELECT * FROM chat_history ORDER BY id LIMIT 1000000, 20;
```

MySQL 要**扫描 1000020 行，丢弃前 100 万行**。

### 11.2 方案一：延迟关联（Deferred Join）

```sql
-- 先在索引上分页拿到 id（覆盖索引，不回表）
SELECT t.* FROM chat_history t
INNER JOIN (
    SELECT id FROM chat_history ORDER BY id LIMIT 1000000, 20
) x ON t.id = x.id;
```

**原理**：子查询只查 `id`（覆盖索引，扫描快），再拿 20 个 id 回表。

**效果**：深分页从 O(offset) 降到 O(log n + 20)。

### 11.3 方案二：游标分页（本项目用的）

```sql
SELECT * FROM chat_history
WHERE appId = ? AND createTime < ?
ORDER BY createTime DESC
LIMIT 20;
```

**优点**：无 offset，永远走索引定位。
**缺点**：不能跳页（只能上一页/下一页）。

### 11.4 方案三：业务限制

```java
ThrowUtils.throwIf(pageNum > 100, ErrorCode.PARAMS_ERROR, "最多翻到第 100 页");
```

**最土但最有效**。大多数业务不需要翻到 1 万页。

### 11.5 三者对比

| 方案 | 深分页性能 | 能否跳页 | 实现复杂度 |
|---|---|---|---|
| 原生 OFFSET | ❌ O(n) | ✅ | 0 |
| 延迟关联 | ✅ O(log n) | ✅ | 低 |
| 游标分页 | ✅ O(log n) | ❌ | 低 |
| 业务限制 | ✅ | 部分 | 最低 |

---

## 12. 本项目索引实战：chat_history 怎么建

### 12.1 表结构与查询

```sql
CREATE TABLE chat_history (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    appId       BIGINT NOT NULL,
    userId      BIGINT NOT NULL,
    message     TEXT NOT NULL,
    messageType VARCHAR(32) NOT NULL,
    createTime  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updateTime  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    isDelete    TINYINT DEFAULT 0
);
```

**查询 1（游标分页）**：
```sql
SELECT * FROM chat_history
WHERE appId = ? AND createTime < ?
ORDER BY createTime DESC
LIMIT 20;
```

**查询 2（记忆回填）**：
```sql
SELECT * FROM chat_history
WHERE appId = ?
ORDER BY createTime DESC
LIMIT 20;
```

**查询 3（管理端按用户查）**：
```sql
SELECT * FROM chat_history WHERE userId = ? ORDER BY createTime DESC;
```

### 12.2 索引设计

```sql
-- ★ 主索引：支撑查询 1 和查询 2
-- 等值列 appId 在前，范围/排序列 createTime 在后
CREATE INDEX idx_appId_createTime ON chat_history (appId, createTime);

-- 次要索引：支撑查询 3
CREATE INDEX idx_userId ON chat_history (userId);
```

**为什么不是 `(createTime, appId)`**：
`appId` 是等值，`createTime` 是范围和排序。反过来 `createTime` 是范围列，后面的 `appId` 用不上。

### 12.3 EXPLAIN 验证

```sql
EXPLAIN SELECT * FROM chat_history
WHERE appId = 1 AND createTime < '2026-10-20 10:00:00'
ORDER BY createTime DESC LIMIT 20;
```

**期望看到**：

```
type: range
key: idx_appId_createTime
key_len: 14          ← 8(appId) + 5(createTime) + 1(NULL)
Extra: Using index condition
```

**★ 注意 `Extra` 里没有 `Using filesort`** —— 因为索引本身就是 `(appId, createTime)` 有序，范围内天然按 createTime 排序。

### 12.4 本项目已知缺陷的索引视角

[CannHistoryServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/ChatHistoryServiceImpl.java#L153-L156)：

```java
if (lastCreateTime != null) {
    queryWrapper.lt("createTime", lastCreateTime);
}
```

**问题**：`createTime` 是秒级精度。同一秒内写入 3 条消息，游标传回来是 `10:00:05`，`lt` 会跳过这 3 条。

**修复（复合游标）**：

```sql
WHERE appId = ?
  AND (createTime < ? OR (createTime = ? AND id < ?))
ORDER BY createTime DESC, id DESC
LIMIT 20;
```

**对应索引要改成**：

```sql
CREATE INDEX idx_appId_createTime_id ON chat_history (appId, createTime, id);
```

**为什么这样能走索引**：
`appId` 等值 → `createTime` 范围 → `id` 范围。虽然 `id` 在范围后，但排序是 `(createTime DESC, id DESC)`，索引顺序 `(createTime ASC, id ASC)` 可以反向扫描，**不需要 filesort**。

**★ 这是一个"索引设计和业务逻辑强绑定"的典型案例** —— 改游标逻辑必须同步改索引，否则性能下降。

---

## 13. 索引的代价：页分裂与写放大

### 13.1 页分裂

```
插入前：[1,2,3,4,5,6,7,8]（页已满）
插入 4.5：需要把页劈成两半，并更新父节点指针
```

**随机主键的后果**：
- 每个新 ID 都可能落在中间页 → 频繁分裂
- 页填充率降到 50% → 同样数据占 2 倍空间
- 索引树变高 → IO 增加

**自增主键**：永远插在最后一页 → 几乎不分裂（但会有"最后一页热点"）。

### 13.2 写放大

**每建一个索引，一次 INSERT 就要写 N+1 次**（1 次数据 + N 次索引）。

| 索引数 | 一次 INSERT 的 B+ 树维护次数 |
|---|---|
| 0（只有主键） | 1 |
| 3 个二级索引 | 4 |
| 6 个二级索引 | 7 |

**所以"索引不是越多越好"不是空话** —— 每个索引都在收"写"的税。

### 13.3 索引维护成本表

| 操作 | 成本 |
|---|---|
| INSERT | 每个索引都要插入 + 可能分裂 |
| UPDATE 索引列 | 相当于 DELETE + INSERT |
| UPDATE 非索引列 | 索引不动 |
| DELETE | 标记删除，索引项延迟清理 |

**★ 实用建议**：
- 高频更新的列（如 `updateTime`）**不要**放进索引（除非真的需要）
- 本项目的 `updateTime` 没建索引，是对的

---

# 第二部分 · Redis 缓存

---

## 14. Redis 为什么快（以及"单线程"的真相）

### 14.1 五个原因

| 原因 | 说明 |
|---|---|
| **纯内存操作** | 内存访问 100ns，磁盘 100μs |
| **单线程无锁** | 无竞争、无上下文切换、无锁开销 |
| **IO 多路复用** | epoll，一个线程处理万个连接 |
| **高效数据结构** | SDS、跳表、listpack 都是为场景特化 |
| **RESP 协议简单** | 解析成本低 |

### 14.2 ★ "单线程"的真相

| 版本 | 网络 IO | 命令执行 | 持久化 |
|---|---|---|---|
| < 6.0 | 单线程 | 单线程 | 子进程/子线程 |
| ≥ 6.0 | **多线程（可配）** | **单线程** | 子进程/子线程 |

**为什么命令执行必须单线程**：
Redis 的命令很多是"读-改-写"（`INCR`、`LPUSH`），单线程天然原子，不需要加锁。

**为什么单线程还这么快**：
瓶颈在**网络和内存**，不在 CPU。单线程 10 万 QPS 已经够用，多线程反而带来锁竞争。

**为什么 6.0 要加多线程 IO**：
现代服务器网卡到 10Gbps 时，**网络读写成了瓶颈**。多线程只处理"从 socket 读数据 / 往 socket 写数据"，命令执行仍在主线程串行。

### 14.3 面试追问：Redis 6 多线程后还需要分布式锁吗？
**答**：需要。多线程只是 IO 线程，命令仍串行。但"多个客户端同时 `GET` 再 `SET`"这个组合操作依然不是原子的，必须用 `SETNX`/Lua/分布式锁。

---

## 15. 五种数据结构的底层实现

### 15.1 String → SDS

```c
struct sdshdr {
    int len;      // 已用长度
    int alloc;    // 分配长度
    char flags;   // 类型
    char buf[];   // 数据
};
```

**相比 C 字符串的优势**：
- O(1) 取长度（C 要遍历）
- 二进制安全（可以存 `\0`）
- 预分配 + 惰性释放（减少 realloc）

**本项目用途**：Session、限流 token、缓存序列化对象。

### 15.2 List → quicklist

```
quicklist
  ├── listpack（压缩存储，小）
  ├── listpack
  └── listpack
   ↑ 节点之间用双向指针连接
```

**演进**：`ziplist + linkedlist`（3.2 前）→ `quicklist`（3.2+）→ `listpack`（7.0 替换 ziplist）

**为什么不用普通链表**：每个节点 2 个指针（16B）+ 内存分配器开销，太浪费。

### 15.3 Hash → listpack / hashtable

```
小数据（field 少、值小）→ listpack（连续内存，省空间）
大数据                → hashtable（哈希表，O(1)）
```

**转换条件**（默认）：
- field 数 > 128，或
- 单个 field/value > 64 字节

### 15.4 Set → intset / listpack / hashtable

| 条件 | 编码 |
|---|---|
| 全是整数且元素少 | `intset`（有序数组，二分查找） |
| 非整数但元素少 | `listpack` |
| 元素多 | `hashtable` |

### 15.5 ZSet → listpack / skiplist + dict

```
小数据 → listpack（按 score 有序的连续数组）
大数据 → skiplist（跳表）+ dict（member → score 的哈希）
```

**为什么用跳表不用红黑树**：
- 实现简单（红黑树插入删除要维护颜色）
- **范围查询友好**（`ZRANGEBYSCORE` 沿底层链表走）
- 并发场景好做无锁（虽然 Redis 单线程用不上）

**跳表结构**：

```
level 3:  1 ────────────── 5 ─────────── 9
level 2:  1 ────── 3 ────── 5 ────── 7 ── 9
level 1:  1 ─ 2 ─ 3 ─ 4 ─ 5 ─ 6 ─ 7 ─ 8 ─ 9
```

查找 7：从最高层开始，`1 → 5 → 7`，3 步。平均 O(log n)。

### 15.6 编码转换（面试常问）

**为什么 Redis 要这么多编码**：**小对象用紧凑结构省内存**。一个 2 元素的 Hash 如果用 hashtable，光哈希表结构就几十字节，而 listpack 只要十几字节。

**查询编码**：`OBJECT ENCODING key`

---

## 16. 过期删除与内存淘汰

### 16.1 过期删除：惰性 + 定期

| 策略 | 说明 | 问题 |
|---|---|---|
| **惰性删除** | 访问时才检查是否过期 | 不访问的 key 永远不删 → 内存泄漏 |
| **定期删除** | 每秒 10 次，每次随机抽查 20 个，过期比例 > 25% 就继续 | 抽样，不保证及时 |

**组合效果**：大多数过期 key 会被清掉，但**不是全部**。所以内存可能虚高。

**★ 面试追问**：为什么不用"定时删除"（每个 key 一个定时器）？
**答**：CPU 开销爆炸。100 万个 key = 100 万个定时任务，Redis 主线程扛不住。

### 16.2 八种淘汰策略

```conf
maxmemory 4gb
maxmemory-policy allkeys-lru
```

| 策略 | 范围 | 算法 |
|---|---|---|
| `noeviction`（默认） | — | 内存满时写操作报错 |
| `allkeys-lru` | 所有 key | 最近最少使用 |
| `allkeys-lfu` | 所有 key | 最不经常使用（4.0+） |
| `allkeys-random` | 所有 key | 随机 |
| `volatile-lru` | 设了 TTL 的 | LRU |
| `volatile-lfu` | 设了 TTL 的 | LFU |
| `volatile-random` | 设了 TTL 的 | 随机 |
| `volatile-ttl` | 设了 TTL 的 | 最快过期的先淘汰 |

**选型建议**：

| 场景 | 选 |
|---|---|
| 纯缓存 | `allkeys-lru` |
| 缓存 + 持久数据混用 | `volatile-lru`（只淘汰有 TTL 的） |
| 有明显热点 | `allkeys-lfu` |

**★ 注意 `noeviction` 是默认值**：很多线上事故就是"内存满了，所有写操作全部报错"。**生产必须显式配置**。

### 16.3 LRU vs LFU

| | LRU | LFU |
|---|---|---|
| 依据 | 最后一次访问时间 | 访问频率（带衰减） |
| 问题 | 偶发批量扫描会污染缓存 | 老热点难以淘汰（有衰减机制缓解） |
| 适合 | 一般缓存 | 有明显冷热分层的 |

**Redis 的 LRU 是"近似 LRU"**：采样 5 个 key（`maxmemory-samples`），淘汰最旧的。不是全量维护链表 —— 为了省内存。

---

## 17. 缓存穿透（含布隆过滤器数学）

### 17.1 现象

```
请求 id = -1（不存在）→ 缓存 miss → 查 DB（也没有）→ 不写缓存 → 下次再来
```

**攻击场景**：恶意用户用随机 id 大量请求 → 每次都打到 DB → DB 挂。

### 17.2 方案一：缓存空值

```java
Object cache = redis.get(key);
if (cache == null) {
    Object db = db.query(key);
    if (db == null) {
        redis.set(key, NULL_MARK, 60, SECONDS);   // ⭐ 缓存空值，TTL 短
    } else {
        redis.set(key, db, 30, MINUTES);
    }
}
```

**优点**：实现简单。
**缺点**：① 大量随机 key 会占满 Redis；② 数据后来有了，缓存还是空（TTL 内不一致）。

### 17.3 方案二：布隆过滤器

**原理**：一个位数组 + k 个哈希函数。

```
初始：  [0 0 0 0 0 0 0 0 0 0 0 0]（m=12 bit）
插入 x：h1(x)=2, h2(x)=5, h3(x)=9
        [0 0 1 0 0 1 0 0 0 1 0 0]

查询 y：算 h1(y),h2(y),h3(y)
        如果有一个位置是 0 → y 一定不存在 ✅
        如果全是 1      → y 可能存在（可能误判）⚠️
```

**★ 核心特性**：
- **说不存在 → 一定不存在**（无假阴性）
- **说存在 → 可能不存在**（有假阳性）

**误判率公式**：

```
p ≈ (1 - e^(-kn/m))^k

n = 元素数量
m = 位数组长度
k = 哈希函数个数
```

**工程取值**：

| n（元素数） | m（位数组） | k | 误判率 |
|---|---|---|---|
| 100 万 | 958 万 bit ≈ 1.2 MB | 7 | 1% |
| 1000 万 | 1.2 亿 bit ≈ 14 MB | 7 | 1% |

**★ 记住这个量级**：100 万元素 1% 误判 ≈ **1.2 MB 内存**。非常便宜。

**缺点**：
- ❌ 不支持删除（删了会影响其他元素）
- ❌ 需要提前把所有 key 灌进去（新增数据要同步）
- ✅ 变体：Counting Bloom Filter（支持删除）、布谷鸟过滤器

**Redis 实现**：RedisBloom 模块（`BF.ADD` / `BF.EXISTS`）。

### 17.4 方案三：参数校验

```java
ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 错误");
```

**最土但最有效**。`id = -1` 这种请求在入口就被拦了。

**★ 本项目**：[AppServiceImpl.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/service/impl/AppServiceImpl.java#L85) 有这行校验。**这一行就挡住了一部分穿透**。

### 17.5 三种方案对比

| 方案 | 内存成本 | 实现复杂度 | 适用 |
|---|---|---|---|
| 缓存空值 | 中（取决于攻击量） | 低 | 中小规模 |
| 布隆过滤器 | 低（1.2MB/百万） | 中 | 大规模、key 集合可枚举 |
| 参数校验 | 0 | 最低 | 必须做 |

---

## 18. 缓存击穿

### 18.1 现象

**一个热点 key 过期瞬间**，大量并发请求同时 miss → 同时查 DB → DB 压力瞬间飙升。

```
t=0: 10000 个请求同时发现 key 不存在
t=1: 10000 个请求同时查 DB（DB 只有 100 连接，9999 个排队）
```

### 18.2 方案一：互斥锁（Mutex）

```java
Object cache = redis.get(key);
if (cache != null) return cache;

// 只有一个线程能拿到锁
String lockKey = "lock:" + key;
if (redis.setIfAbsent(lockKey, "1", 10, SECONDS)) {
    try {
        Object db = db.query(key);          // 只有 1 个线程查库
        redis.set(key, db, 30, MINUTES);
        return db;
    } finally {
        redis.delete(lockKey);
    }
} else {
    Thread.sleep(50);                       // 其他线程等一会儿重试
    return getWithCache(key);
}
```

**优点**：强一致，DB 只被查一次。
**缺点**：① 其他线程要等待（体验差）；② 有死锁风险（用 TTL 兜底）。

### 18.3 方案二：逻辑过期（不删 key）

```
缓存里存 {data: 实际数据, expireTime: 逻辑过期时间}
```

```
读：拿到数据 → 判断 expireTime
  未过期 → 直接返回
  已过期 → 启一个异步线程重建缓存，自己先返回旧数据
```

**优点**：**永不阻塞用户**，一直有数据返回。
**缺点**：会短暂返回旧数据（**最终一致**）。

**★ 本项目更适合哪个**：
"精选应用列表"这类**可以接受短暂旧数据**的场景，用逻辑过期更好（不阻塞用户）。而"用户余额"这种必须强一致的，用互斥锁。

### 18.4 方案三：永不过期 + 后台刷新

```
缓存永不过期，由一个定时任务每 N 分钟主动刷新。
```

**优点**：彻底没有"过期瞬间"。
**缺点**：占用内存；刷新失败会一直是旧数据。

### 18.5 三种方案对比

| 方案 | 一致性 | 用户阻塞 | 实现复杂度 |
|---|---|---|---|
| 互斥锁 | 强 | ✅ 会等 | 中 |
| 逻辑过期 | 最终 | ❌ 不等 | 中 |
| 永不过期 | 最终 | ❌ 不等 | 低 |

---

## 19. 缓存雪崩

### 19.1 现象

**大量 key 同时过期** 或 **Redis 整体宕机** → 所有请求打到 DB → DB 挂 → 服务挂。

**和击穿的区别**：
- 击穿 = **1 个**热点 key
- 雪崩 = **一片** key / **整个 Redis**

### 19.2 方案一：随机 TTL

```java
// ❌ 所有缓存都在同一时刻过期
redis.set(key, value, 30, MINUTES);

// ✅ 加随机抖动
long ttl = 30 * 60 + RandomUtil.randomLong(0, 300);   // 30min ~ 35min
redis.set(key, value, ttl, SECONDS);
```

**★ 这是最有效、成本最低的一招。** 一行代码解决问题。

### 19.3 方案二：多级缓存

```
请求 → 本地缓存（Caffeine，毫秒级）
         ↓ miss
       Redis（毫秒~几毫秒）
         ↓ miss
       MySQL
```

**Redis 挂了，本地缓存还能扛一阵**。这就是"多级缓存"的核心价值（见第 27 节）。

### 19.4 方案三：熔断降级

```
Redis 不可用 → 直接查 DB（但要限流）→ 或者返回默认值
```

**关键**：**必须有降级开关**。不能"Redis 挂了整个服务跟着挂"。

**本项目缺口**：`RedisChatMemoryStore` / Session / 限流全都强依赖 Redis。**Redis 挂 = 服务不可用**。这是"重依赖"而不是"轻依赖"。

### 19.5 方案四：Redis 高可用

| 模式 | 说明 | 切换时间 |
|---|---|---|
| 主从复制 | 手动切换 | 分钟级 |
| **哨兵（Sentinel）** | 自动故障转移 | 秒级 |
| **Cluster** | 分片 + 故障转移 | 秒级 |

**Cluster 分片**：`CRC16(key) % 16384` → 16384 个槽分给 N 个节点。

**★ 为什么是 16384 个槽**（面试冷知识）：
心跳包要携带槽位图，16384 bit = 2KB。如果 65536 个槽，心跳包就是 8KB，太浪费带宽。

### 19.6 雪崩的三层防御

```
第一层：随机 TTL（防同时过期）
第二层：多级缓存（防 Redis 挂）
第三层：熔断降级（防 DB 被打挂）
```

---

## 20. 缓存一致性：为什么"先更库再删缓存"

### 20.1 四种更新顺序

| 顺序 | 问题 |
|---|---|
| ① 先删缓存，再更库 | **并发读会把旧值回填**（严重） |
| ② 先更库，再删缓存 | 有极小的不一致窗口（推荐） |
| ③ 先更缓存，再更库 | 缓存写成功、库写失败 → 不一致 |
| ④ 先更库，再更缓存 | 并发写时更新顺序可能错乱 |

### 20.2 为什么①有问题

```
时间线：
T1: 写线程删除缓存
T2: 读线程 miss → 查 DB（读到旧值）
T3: 写线程更新 DB（新值）
T4: 读线程把旧值写回缓存   ← ★ 旧值复活了
```

**缓存里现在是旧值，DB 里是新值，且不会再自动纠正。**

### 20.3 为什么②也有极小问题

```
T1: 读线程 miss → 查 DB（读到旧值）
T2: 写线程更新 DB（新值）
T3: 写线程删除缓存
T4: 读线程把旧值写回缓存   ← 还是旧值
```

**概率很低**（要求读请求的"查库"和"回填"跨越整个写操作），但存在。

### 20.4 三种补救

| 方案 | 说明 | 延迟 |
|---|---|---|
| **延迟双删** | 删缓存 → 更库 → sleep N ms → 再删一次 | 有延迟（N 要大于读请求耗时） |
| **订阅 binlog** | Canal 监听 DB 变更，异步删缓存 | 毫秒级 |
| **强一致场景不用缓存** | 直接读库 + 加锁 | 无 |

**延迟双删代码**：

```java
redis.delete(key);
db.update(entity);
Thread.sleep(500);       // 大于"读请求：查库+回填"的耗时
redis.delete(key);
```

**★ 为什么 sleep 500ms 而不是固定值**：取决于业务，一般取"读请求 P99 耗时 × 2"。**用 sleep 阻塞线程很糟糕**，生产应改成延时队列/定时任务。

### 20.5 缓存一致性的正确心态

**★ 缓存和数据库不可能做到强一致。**
能做的只有：
1. 缩小不一致窗口（延迟双删 / binlog）
2. 给缓存设 TTL（最终兜底）
3. 关键业务不走缓存

**面试如果被问"怎么保证强一致"，正确答法是"做不到，只能尽量缩小窗口 + TTL 兜底"。**

---

## 21. 四种缓存模式

### 21.1 Cache Aside（旁路缓存）

```
读：cache.get → miss → db.query → cache.set
写：db.update → cache.delete
```

**本项目用的就是这个**（`@Cacheable` + `@CacheEvict`）。

**优点**：简单、可控。
**缺点**：需要自己处理一致性。

### 21.2 Read Through（读穿透）

```
读：cache.get → 缓存层自己去查 DB 并回填
```

**调用方不感知 DB**。适合缓存中间件（如 Guava LoadingCache）。

**Caffeine 的 `cache.get(key, k -> load(k))` 就是 Read Through**：

```java
serviceCache.get(cacheKey, k -> createAiCodeGeneratorService(appId, type));
```

### 21.3 Write Through（写穿透）

```
写：写缓存 → 缓存层同步写 DB
```

**优点**：缓存和 DB 由同一层保证一致。
**缺点**：写性能差（同步写两次）。

### 21.4 Write Behind（写回）

```
写：只写缓存 → 异步批量刷 DB
```

**优点**：写极快。
**缺点**：宕机丢数据。

**本项目哪里适合**：对话历史的 `createTime` 之类统计字段可以 Write Behind，但消息本身不能丢，必须同步写。

### 21.5 模式对比

| 模式 | 读性能 | 写性能 | 一致性 | 复杂度 |
|---|---|---|---|---|
| Cache Aside | 高 | 高 | 最终 | 低 |
| Read Through | 高 | — | 最终 | 中 |
| Write Through | 高 | 低 | 强 | 中 |
| Write Behind | 高 | 极高 | 弱 | 高 |

---

## 22. 分布式锁：从手写到 Redisson

### 22.1 手写版本的四次演进

```java
// 版本 1：最原始（有问题）
if (redis.setIfAbsent(key, "1")) {
    doBusiness();
    redis.delete(key);
}
// ❌ 宕机 → 死锁

// 版本 2：加过期时间
if (redis.setIfAbsent(key, "1", 30, SECONDS)) {
    doBusiness();
    redis.delete(key);
}
// ❌ 业务超过 30s → 锁失效，别人进来；且会误删别人的锁

// 版本 3：value 存 UUID
String uuid = UUID.randomUUID().toString();
if (redis.setIfAbsent(key, uuid, 30, SECONDS)) {
    doBusiness();
    if (uuid.equals(redis.get(key))) redis.delete(key);
}
// ❌ get + delete 不是原子的，中间可能过期并被别人获取

// 版本 4：Lua 原子删除
if (uuid.equals(redis.get(key))) {
    redis.execute(luaScript, key, uuid);   // if get==uuid then del
}
// ✅ 基本可用，但仍需解决"业务超时"和"主从丢锁"
```

### 22.2 五个经典问题

| 问题 | 后果 | 解法 |
|---|---|---|
| 无过期时间 | 宕机死锁 | 加 TTL |
| 误删别人的锁 | 并发进入 | value 存 UUID + Lua 删除 |
| 业务超时锁失效 | 并发进入 | **看门狗续期** |
| 主从切换丢锁 | 并发进入 | RedLock / fencing token |
| 不可重入 | 自己锁自己死锁 | 计数 + ThreadLocal |

### 22.3 Redisson 怎么解决的

```java
RLock lock = redissonClient.getLock("lock:order:123");
lock.lock();               // 不传 leaseTime → 启用看门狗
try {
    doBusiness();
} finally {
    lock.unlock();
}
```

**看门狗（Watchdog）机制**：

```
lock() 不指定 leaseTime
  → 默认 TTL 30s
  → 后台定时任务每 10s（TTL/3）检查：线程还活着吗？
      → 活着 → 续期到 30s
      → 死了 → 不再续期，30s 后自动释放
```

**★ 关键细节**：
- `lock.lock(10, SECONDS)` **不会**启用看门狗（明确指定了 leaseTime）
- 看门狗续期是**后台线程**做的，所以 `unlock()` 必须由**同一个线程**调用
- 如果服务崩溃，看门狗线程也死了 → 最多 30s 后锁自动释放

**Redisson 的可重入实现**：

```
value 存 {UUID}:{线程ID}
加锁：如果已存在且是自己的 → count++
解锁：count-- → 减到 0 才真正删除
```

### 22.4 本项目锚点

[RedissonConfig.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ratelimter/config/RedissonConfig.java) 配置 Redisson 客户端。
[RateLimitAspect.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ratelimter/aspect/RateLimitAspect.java#L44-L51) 用的是 `RRateLimiter`（令牌桶），**不是分布式锁**。

**★ 注意区分**：
| 组件 | 用途 | 底层 |
|---|---|---|
| `RLock` | 互斥（分布式锁） | Hash + Lua + 看门狗 |
| `RRateLimiter` | 限流（令牌桶） | Hash/Lua，`tryAcquire` 扣令牌 |
| `RSemaphore` | 信号量 | |
| `RCountDownLatch` | 计数器 | |

**限流器不是锁**：它允许 N 个并发通过，锁只允许 1 个。

---

## 23. RedLock 的世纪争议

### 23.1 背景

**单节点 Redis 锁的问题**：主从异步复制，锁还没同步到从节点，主挂了 → 从变主 → 锁丢了 → 两个客户端同时持锁。

**RedLock 方案**：向 5 个**独立** Redis 节点加锁，**超过半数（3 个）成功**才算拿到锁。

```
客户端 → Node1 ✅
       → Node2 ✅
       → Node3 ✅   → 3/5，加锁成功
       → Node4 ❌
       → Node5 ❌
```

### 23.2 争议双方

**Martin Kleppmann（反对）** 的核心论点：

1. **GC 停顿 / 网络延迟**会让客户端在"拿到锁"和"执行操作"之间停顿
2. 停顿期间锁过期，另一个客户端拿到锁
3. 两个客户端同时操作 → 数据损坏
4. **RedLock 无法解决这个问题**，因为分布式系统里"时间"不可靠

**他的解决方案**：**fencing token（栅栏令牌）**

```
每次获取锁，返回一个递增的 token
存储层拒绝比当前 token 小的写请求

客户端A 拿 token=33 → GC 停顿
客户端B 拿 token=34 → 写入成功（存储层记录 34）
客户端A 恢复 → 尝试写入 token=33 → ❌ 被拒绝
```

**antirez（Redis 作者，支持）** 的回应：
- 如果不是为了"效率"而是为了"正确性"，用 RedLock 确实有风险
- 但绝大多数场景只需要"效率"（避免重复计算），不是"正确性"（防止数据损坏）

### 23.3 工程结论

| 场景 | 建议 |
|---|---|
| 防止重复计算（如缓存重建、定时任务） | Redis 单节点锁 ✅ 够用 |
| 防止数据损坏（如转账、库存扣减） | **不要只靠 Redis 锁**，用 DB 唯一约束/乐观锁/fencing token |

**★ 一句话**：**分布式锁不能替代数据库约束。**

---

## 24. 缓存三大工程的落地清单

### 24.1 上线前必须检查

- [ ] 所有 key **都有 TTL**（除了明确要永久的）
- [ ] TTL **带随机抖动**
- [ ] 明确 `maxmemory-policy`（不能是默认 `noeviction`）
- [ ] 有 **key 命名规范**（前缀 + 冒号）
- [ ] 没有 `KEYS *`（用 `SCAN`）
- [ ] 大 key 已排查（`redis-cli --bigkeys`）
- [ ] 缓存对象都实现了 `Serializable` 或配置了 JSON 序列化
- [ ] 有**缓存击穿的兜底**（互斥锁 or 逻辑过期）
- [ ] 有**Redis 挂了的降级方案**
- [ ] **热点 key** 有识别机制（监控 QPS）

### 24.2 上线后必须监控

| 指标 | 阈值 |
|---|---|
| 命中率 | < 80% 要关注 |
| 内存使用率 | > 80% 要扩容 |
| 慢查询 | `slowlog` > 10ms |
| 连接数 | 接近 `maxclients` 要告警 |
| 淘汰数 | `evicted_keys` 突增说明内存不够 |

---

## 25. 本项目 Redis 全景图

### 25.1 五处用途

```
┌─────────────────────────────────────────────────────┐
│                    Redis                            │
├─────────────────────────────────────────────────────┤
│ 1. Session 共享                                      │
│    spring:session:sessions:{id}                     │
│    → 多实例登录态一致                                 │
├─────────────────────────────────────────────────────┤
│ 2. 对话记忆持久化                                     │
│    RedisChatMemoryStore                             │
│    → AI 服务实例被淘汰后能恢复记忆                      │
├─────────────────────────────────────────────────────┤
│ 3. 分布式限流                                         │
│    RRateLimiter（令牌桶）                             │
│    rate_limit:user:{userId}                         │
│    → 用户级 5 次/分钟                                 │
├─────────────────────────────────────────────────────┤
│ 4. 业务缓存（@Cacheable）                             │
│    good_app_page（5 分钟）                            │
│    → 精选应用列表                                     │
├─────────────────────────────────────────────────────┤
│ 5. 应用截图/部署元数据（间接）                          │
└─────────────────────────────────────────────────────┘
```

### 25.2 四个配置问题

| # | 位置 | 问题 | 后果 |
|---|---|---|---|
| 1 | [RedisCacheManagerConfig.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/config/RedisCacheManagerConfig.java#L34-L42) | value 序列化器被注释 | 走 JDK 序列化，Redis 里不可读，对象必须 Serializable |
| 2 | 同上 [L36](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/config/RedisCacheManagerConfig.java#L36) | `disableCachingNullValues()` | 放弃了"缓存空值防穿透" |
| 3 | [AppController.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/controller/AppController.java#L266-L272) | `@Cacheable` 无 `@CacheEvict` | 改了 priority 后 5 分钟内不生效 |
| 4 | 全局 | 无降级 | Redis 挂 = 登录、记忆、限流全挂 |

### 25.3 一个真实的隐患：缓存键与记忆 id 不一致

[AiCodeGeneratorServiceFactory.java](file:///e:/AI/GitHub/yu-ai-code-mother/src/main/java/com/yupi/yuaicodemother/ai/AiCodeGeneratorServiceFactory.java#L155-L157)：

```java
String cacheKey = appId + "_" + codeGenType.getValue();   // 缓存键带类型
// ...
chatMemory.id(appId)                                      // 记忆 id 只带 appId
```

**结果**：`html_123` 和 `multi_file_123` 是两个不同的 service 实例，各自持有独立的 `MessageWindowChatMemory` 对象，但**都指向 Redis 里同一个 `chat:memory:123`**。

```
实例 A（HTML）   窗口 [m1, m2, m3]  ──┐
                                       ├──→ Redis: chat:memory:123（同一份）
实例 B（MULTI）  窗口 [m3, m4, m5]  ──┘
```

**后果**：
- 两边写 Redis 会互相覆盖
- 实例 A 的窗口和 Redis 里的内容可能不一致
- 用户切换类型后，记忆是"混合"的

**修复**：`chatMemory.id(appId + "_" + type)`。

---

## 26. 大 key 与热 key

### 26.1 大 key

**定义**：

| 类型 | 阈值 |
|---|---|
| String | > 10 KB |
| Hash / List / Set / ZSet | 元素 > 5000 |

**危害**：
- **网络阻塞**：一次读 1MB，10 万 QPS 就是 100GB/s
- **删除阻塞**：`DEL` 大 key 会阻塞主线程（用 `UNLINK` 异步删）
- **迁移阻塞**：Cluster 扩缩容时大 key 迁移慢

**排查**：

```bash
redis-cli --bigkeys
redis-cli --memkeys
```

**治理**：拆分（大 Hash 拆成多个小 Hash）、压缩、改用其他存储。

### 26.2 热 key

**定义**：单个 key 的 QPS 远超其他 key。

**危害**：所有请求打到同一个 Cluster 节点 → 该节点 CPU 打满，其他节点闲着。

**排查**：

```bash
redis-cli --hotkeys          # 需要 maxmemory-policy 是 lfu
MONITOR                       # 生产慎用，会严重影响性能
```

**治理**：

| 方案 | 说明 |
|---|---|
| 本地缓存 | 热点数据放 JVM（Caffeine），不查 Redis |
| key 拆分 | `hotkey:1` ~ `hotkey:10`，随机选一个 |
| 读写分离 | 多个从节点分担读 |
| 限流 | 热点 key 做特殊限流 |

**★ 本项目的潜在热 key**：
`good_app_page` 是"精选应用列表"，所有用户都会访问 → **典型的全局热 key**。
一旦 QPS 高，这一个 key 会打满一个节点。

**改进**：加本地 Caffeine 缓存（见第 27 节）。

---

# 第三部分 · 交叉

---

## 27. 多级缓存：Caffeine + Redis 怎么配合

### 27.1 三层架构

```
请求
  │
  ├─→ L1：Caffeine（JVM 堆内，纳秒级，无网络）
  │      命中率 80~95%
  │
  ├─→ L2：Redis（网络，0.5~2ms）
  │      命中率 90%+（对剩余请求）
  │
  └─→ L3：MySQL（10~100ms）
```

### 27.2 本项目里两处缓存的定位完全不同

| 缓存 | 位置 | 缓存什么 | 目的 |
|---|---|---|---|
| Caffeine `serviceCache` | **JVM 堆** | AI 服务实例（含 ChatMemory 对象） | 避免每请求重建代理 + 回填历史 |
| Redis `good_app_page` | **Redis** | 精选应用列表数据 | 避免每请求查库 |

**★ 这是关键区分**：
- Caffeine 缓存的**不是数据，是"昂贵对象"**
- Redis 缓存的**是查询结果**

### 27.3 两种缓存的失效策略差异

| | Caffeine `serviceCache` | Redis `good_app_page` |
|---|---|---|
| 过期 | `expireAfterWrite(30min)` + `expireAfterAccess(10min)` | `entryTtl(5min)` |
| 容量 | `maximumSize(1000)` | 无限制 |
| 一致性 | **本机**，多实例不一致 | 全局一致 |
| 失效方式 | LRU 淘汰 | TTL / 主动 evict |

### 27.4 多级缓存的四个经典问题

| 问题 | 说明 | 解法 |
|---|---|---|
| **各层不一致** | L1 改了，L2 没改 | 广播失效（Pub/Sub / MQ） |
| **L1 多实例不一致** | 实例 A 更新了，实例 B 还是旧的 | 同上 |
| **L1 脏数据** | 写库后只删了 L2 | 写时同时删 L1+L2，或 L1 TTL 设短 |
| **穿透** | 两层都 miss | 布隆过滤器 |

**★ 本项目的 Caffeine 就存在"多实例不一致"问题**：
两个实例各自缓存一份 `AiCodeGeneratorService`，各自的内存窗口内容不同，但共享同一个 Redis memory id。
用户请求打到实例 A 和实例 B，看到的"记忆窗口"可能不一样。

**这在"单实例部署"下不是问题，在多实例部署下是真问题。**

### 27.5 什么数据适合放 L1

| 特征 | 适合 L1 | 原因 |
|---|---|---|
| **变更极少** | ✅ | 一致性风险低 |
| **读多写少** | ✅ | 命中率高 |
| **允许短暂不一致** | ✅ | |
| **体积小** | ✅ | JVM 内存有限 |
| **用户私有数据** | ❌ | 多实例/多用户容易串 |

**★ 本项目的 `serviceCache` 缓存了"用户会话相关对象"（ChatMemory），这是有风险的** —— 因为它不是"全局共享的只读数据"，而是"每个 appId 独立的状态"。

---

## 28. 两章合并的 30 道连环追问

| # | 追问 | 答案要点 |
|---|---|---|
| 1 | 为什么 B+ 树 3 层能存 2000 万行 | 非叶子存 1170 指针，叶子存 16 行，1170×1170×16 |
| 2 | 聚簇索引叶子存什么 | 整行数据 |
| 3 | 二级索引叶子存什么 | 主键值 |
| 4 | 回表是什么 | 二级索引拿到主键，再回聚簇索引查整行 |
| 5 | 怎么避免回表 | 覆盖索引（查询列全在索引里） |
| 6 | 覆盖索引的标志 | `Extra: Using index` |
| 7 | 索引下推的作用 | 在存储引擎层用索引列过滤，减少回表 |
| 8 | 索引下推的标志 | `Extra: Using index condition` |
| 9 | 最左前缀为什么 | 联合索引先按第一列排序，第一列无序则后面无序 |
| 10 | 范围查询后面的列还能用吗 | 不能定位，但能 ICP 过滤 |
| 11 | `WHERE a=1 AND c=3`（索引 a,b,c）能用几列 | 只能用 a |
| 12 | 为什么 `userId = '123'` 会失效 | 隐式类型转换等价于函数运算 |
| 13 | 区分度怎么算 | `count(distinct col)/count(*)`，< 0.1 意义不大 |
| 14 | 自增主键为什么比 UUID 好 | 顺序插入少页分裂，索引更小 |
| 15 | 页分裂的代价 | 页填充率下降、树变高、IO 增加 |
| 16 | 深分页怎么优化 | 延迟关联 / 游标分页 / 业务限制 |
| 17 | `Using filesort` 什么意思 | 索引无法满足 ORDER BY，需要额外排序 |
| 18 | Redis 单线程为什么快 | 纯内存、无锁、IO 多路复用、数据结构高效 |
| 19 | Redis 6 多线程改了什么 | 只有网络 IO 多线程，命令执行仍单线程 |
| 20 | 过期 key 怎么删 | 惰性删除 + 定期抽样删除 |
| 21 | 默认淘汰策略是什么 | `noeviction`（内存满则写报错）—— 生产必须改 |
| 22 | 缓存穿透怎么办 | 参数校验 / 缓存空值 / 布隆过滤器 |
| 23 | 布隆过滤器会误判吗 | 说"不存在"一定对，说"存在"可能错 |
| 24 | 布隆过滤器 100 万元素占多少内存 | 1% 误判约 1.2 MB |
| 25 | 缓存击穿和雪崩的区别 | 1 个热点 key vs 一片 key / 整个 Redis |
| 26 | 雪崩最便宜的解法 | 随机 TTL 抖动 |
| 27 | 为什么先更库再删缓存 | 反过来会有旧值回填窗口 |
| 28 | 延迟双删的 sleep 多长 | 大于读请求"查库+回填"的耗时 |
| 29 | Redisson 看门狗怎么工作 | 默认 30s TTL，每 10s 续期，指定 leaseTime 则不续 |
| 30 | RedLock 能保证强一致吗 | 不能，GC 停顿会导致锁失效，需要 fencing token |

---

# 附 · 两章的"考试型"自查

## MySQL 自查（能答出 8 条算过关）

- [ ] 能画出 B+ 树，并说出叶子/非叶子的区别
- [ ] 能算出 3 层 B+ 树大约存多少行
- [ ] 能解释"为什么表数据只在聚簇索引里"
- [ ] 能说出覆盖索引和索引下推的区别
- [ ] 能给一条 SQL 设计正确的联合索引顺序
- [ ] 能说出 5 种以上索引失效场景
- [ ] 能读懂 EXPLAIN 的 type / key_len / Extra
- [ ] 能写出深分页的两种优化
- [ ] 能说出页分裂的成因和后果
- [ ] 能给本项目的 `chat_history` 设计复合游标索引

## Redis 自查（能答出 7 条算过关）

- [ ] 能解释"单线程"的准确含义
- [ ] 能说出五种数据结构的底层编码
- [ ] 能说出过期删除的两种策略及其局限
- [ ] 能区分穿透/击穿/雪崩，并各说两个解法
- [ ] 能解释"先更库再删缓存"的原因
- [ ] 能写出 Redisson 看门狗的机制
- [ ] 能说出 RedLock 的争议和 fencing token
- [ ] 能说出大 key / 热 key 的排查与治理
- [ ] 能画出多级缓存架构，并说出三个一致性问题
- [ ] 能说出本项目五处 Redis 用途，以及四个配置问题

---

# 附 · 建议的啃读节奏（每天 40 分钟）

| 天 | 内容 | 产出 |
|---|---|---|
| 1 | 第 1~3 节（IO 成本、存储层级、B+ 树） | 手画一遍 B+ 树 |
| 2 | 第 4~6 节（聚簇/二级/覆盖/ICP） | 说清"回表"是什么 |
| 3 | 第 7~8 节（联合索引/失效） | 给 5 条 SQL 设计索引 |
| 4 | 第 9 节（EXPLAIN） | 用 EXPLAIN 分析本项目 3 条 SQL |
| 5 | 第 10~13 节（设计方法/分页/实战） | 给 chat_history 写出完整 DDL |
| 6 | 第 14~16 节（Redis 原理/结构/淘汰） | 背下 8 种淘汰策略 |
| 7 | 第 17~19 节（三大问题） | 各画一张时间线图 |
| 8 | 第 20~21 节（一致性/模式） | 能讲清延迟双删 |
| 9 | 第 22~23 节（分布式锁/RedLock） | 手写一遍 Lua 删锁 |
| 10 | 第 24~26 节（清单/项目/大key） | 对照本项目找出 4 个问题 |
| 11 | 第 27~28 节（多级缓存/30 问） | 自测 30 问 |

**★ 最关键的一条**：**每读完一节，打开原项目对应的 SQL / 代码看一眼**。
知识点不落地就永远是"背过"，落地一次才是"会"。
