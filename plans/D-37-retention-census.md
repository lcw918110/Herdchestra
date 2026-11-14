# D-37 保留策略普查

本文只列约束面，不给最终设计。所有行号基于 `138abf2`。

## 一句话结论

`:conversation` 不能裁剪，因为 `sealed-scope-view` 的文书契约写明「The Owner conversation is never trimmed」，且它按 `reset-beats` 从任意位置回溯切片。真正可折叠的是**已结算的 `:calls`** 与**发布侧的 `:conversation` 投影**，不是 Owner 文档本体。

## 一、`:conversation` 读者普查

| # | 位置 | 所在函数 | 需要什么 | 涉 Prefix 链 | 涉 sealed view | 只给尾部+归档引用是否可行 |
|---|---|---|---|---|---|---|
| 1 | `guards.clj:1750` | 出站快照组装 | 传给 `conversation-tail` | 是 | 否 | **可行**，下游只 `take-last 32` |
| 2 | `guards.clj:1874` | `conversation-tail` | `project-conversation` 全量入参 | 是（结果进提示词） | 否 | **可行**，但须保证投影结果不变 |
| 3 | `guards.clj:1880` | `conversation-tail` 回退分支 | `conversation->items` 全量 | 是 | 否 | **可行**，同 #2 |
| 4 | `handlers.clj:828` | `mirror-history-message!` 的 reduce | **按 message-id 随机访问任意旧消息** | 否 | 否 | **不可行**：操作员可编辑任意历史消息，须能按 id 命中归档段 |
| 5 | `handlers.clj:2627` | 最终文本比对 | `tool-call-summary-text` 取本轮工具调用的名称 | 否 | 否 | **可行**，只涉及当前 run |
| 6 | `state.clj:960` | `put-assistant` 的线性扫描 | 按 `message-id` 定位当前助手消息 | 否 | 否 | **可行**，当前消息必在尾部 |
| 7 | `state.clj:1370` | `project-conversation` | **全量顺序** + `reset-beats` 切片 | 是 | 是（核心） | **不可行**（见二） |
| 8 | `state.clj:1362` | `conversation-call-index` | **全量线性扫描**找 call-id 下标 | 否 | 是 | **不可行**，须能定位任意 reset beat |
| 9 | `snapshot.clj:858` | 发布投影 | 全量 → `conversation-view` | 否 | 否 | **可窗口化**（见三） |
| 10 | `snapshot.clj:863` | 同上（无 cache 分支） | 同 #9 | 否 | 否 | 同 #9 |
| 11 | `system.clj:3621` | 组装 messages 快照 | 全量 | 否 | 否 | **可窗口化** |
| 12 | `system.clj:5935,6173` | 诊断计数 | **只要 `count`** | 否 | 否 | **可行**，用计数替代 |

**新增（handoff 未列）**：`state.clj:1362` 的 `conversation-call-index`、`system.clj:3621`、`system.clj:5935`、`system.clj:6173`。

## 二、`sealed-scope-view` 的硬约束（决定性）

`state.clj:1357-1363` 的 docstring 原文：

> The Owner conversation is never trimmed, so the view is a pure slice:
> from the target beat's reset `:tool-call` entry up to (not including) the
> next settled reset beat's `:tool-call` entry, or the conversation tail
> when no later beat exists.

实现依赖两件事：

1. `conversation-call-index` 能在**任意位置**找到 reset beat 的 `:tool-call` 条目；
2. `subvec` 能取 `[start end)` 任意区间。

因此 `:conversation` 一旦在**中间**挖洞，`conversation-call-index` 的下标语义与 `subvec` 都会失效。

**但实践边界比文书更窄**：`serve-ask-consult`（`guards.clj:2695+`）是 `sealed-scope-view` 的**唯一**调用点，其 `:target-call-id` 来自 `(:target-call-id ask)`，而 ask 由 `scope-admission-check`（`state.clj:1196+`）在每次 reset 前针对 **`(peek beats)` 最新 beat** 强制要求。加上 `mark-scope-ask-stale` 会让非最新 beat 的 ask 永久失效，**实际只会回溯到最新 beat 及其后一段**。

这给出安全下界，但它是**行为推论而非契约**：若将来允许对更旧 beat 提问，归档就会失效。必须显式锁死这个前提。

## 三、`:message/order` 的客户端不变量

`src/hc/client/sync/AGENTS.md:230`：

> 显式 `:message/order` 必须恰好列出 `:message/by-id` 的全部键一次。

`timeline.cljc:58` 用 `:message/order` 决定渲染顺序。所以发布侧**不能**只截断 `:message/order` 而不截断 `:message/by-id`；两者必须同步，否则客户端渲染/校验失败。

`history-window-size`（`snapshot.clj:809`）只作用于 `:history`，**不作用于 `:conversation` 投影**。这是缺口（二）在发布侧的直接体现。

## 四、`:calls` / `:runs` 保留分类

| 分类 | 判据 | 可归档？ | 依据 |
|---|---|---|---|
| `:outcome-unknown` | 外部效果未知 | **否** | `recover-state` 保留并进未结清单（`state.clj:1788-1794`） |
| `:dispatching` / `:running` | 重启后无进程句柄 | **否** | 同上，转为 `:outcome-unknown` |
| `:retry-wait` | 有待发重试 | **否** | `recover-state` 重新武装定时器 |
| `:ready` | 待领取 | **否** | 调度器仍需领取 |
| `:succeeded` + 终态 | 已结算 | **可以** | 无读者要求内容，仅需计数与审计 |
| `:failed` + 终态 | 已结算 | **可以** | 同上 |
| `:cancelled` + 终态 | 已结算 | **可以** | 同上 |
| `:runs` 中已终态 run | 文本已入 conversation | **可以** | 见下条注意 |

**注意**：`runs` 的 `:text` 会被 `append-run-text` 反复重写（`state.clj:1002-1012`），而最终文本已复制进 conversation 的 assistant 消息。所以终态 run 的 `:text` 是**重复存储**——这是可观的冗余，且删除它是安全的（只要该 run 已 `:completed`）。

`unresolved-calls`（`snapshot.clj`）合并 `:calls` 与 `:worker-calls`，只关心未结项，与归档不冲突。

## 五、不可回避的硬阻塞

1. **`conversation-call-index` 需要全量**。任何中间挖洞都会让 sealed view 的下标失效。唯一安全做法是把归档段**保留下标连续性**（例如整体前缀归档，而非中间挖洞）。
2. **操作员可编辑任意历史消息**（`mirror-history-message!`）。前缀归档后，编辑归档段内的消息必须能先回填该段。这要求归档是**可随机读取**的 blob，不是仅追加的摘要。
3. **Pair-Hint 前缀链**。`conversation-tail` 只取 32 条，但 `:PREFIX_DRIFT` 校验基于渲染消息序列。只要**投影结果**不变，归档不影响前缀链——这是关键：约束在投影，不在存储。
4. **存储硬上限 8,000,000 字符**。超限报 `:OWNER_STATE_TOO_LARGE`，明确要求折叠到 blob 引用。这是最终护栏，不是保留策略。

## 六、可安全归档的候选（按收益排序）

| 候选 | 预估收益 | 风险 | 前置条件 |
|---|---|---|---|
| 终态 `:runs` 的 `:text` | 高（与 conversation 重复存储） | 低 | 须确认无读者依赖终态 run 的 `:text` |
| 已结算 `:calls` 的内容字段 | 中 | 低 | 保留 id/状态/用量，仅为审计 |
| `:conversation` **前缀**归档 | 最高 | 高 | 须保证下标连续、可随机回填、投影不变 |
| 发布侧 `:conversation` 投影窗口化 | 中（内存非磁盘） | 中 | 须与 `:message/by-id` 同步 |

## 七、测量事实

见 `DEBT.md` D-37 条目与 `plans/` 内的 benchmark 记录：

- 500 轮（1000 条消息）会话，文档 258,138 字符，`pr-str` 单次 4.00 ms。
- 按此比例约 2000 轮达 1 MiB，约 31000 轮达 8 MB 硬上限。

## 八、给设计者的前提清单

若要做 `:conversation` 前缀归档，必须先锁死：

1. sealed view 的回溯上界（是否允许对任意旧 beat 提问）。
2. 归档 blob 的随机可读性（供 `mirror-history-message!` 与 `conversation-call-index`）。
3. 下标连续性方案（前缀归档 + 偏移量，或 archived-count + 逻辑下标换算）。
4. Pair-Hint 投影不变的证明方式。
5. 归档触发的可观测性（诊断事件）。
