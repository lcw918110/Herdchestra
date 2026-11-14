# DEBT

本文件记录 `AGENTS.md` 要求但当前实现尚未做到、或实现与 spec 存在已知偏差的缺口。每条包含：条款位置、缺口描述、影响、建议处理方式。只记缺口，不记已完成项；缺口关闭时删除本条。追赶 spec 的 AI 以本文件为工作队列。

编号不重排、不复用：已关闭条目（如 D-01、D-02、D-03、D-04、D-05、D-06、D-08–D-12、D-13–D-20、D-22、D-23、D-32）从本文件删除；历史状态见 git 历史与 `plans/debt-status-0.0.1.md`。新增条目从 D-38 起连续编号（D-38–D-46 已占用；D-32、D-38 已关闭但编号不复用）。


## D-37 `:conversation` / `:calls` / `:runs` 无保留策略，流式 chunk 逐条提交

- **条款**：`AGENTS.md` §5.1（单 Owner 状态迁移）、§6.1（流式阅读）与 §8.4 变更检查表。
- **已关闭的部分**：`:receipts` 的无限增长已在 `752060a` 关闭——窗口满时用 `:COMMAND_RECEIPT_WINDOW_FULL` + `:action :resync-state` 拒绝**新** command-id，重放与 payload 冲突仍走原语义，轮换 epoch 后窗口清空。默认上限 4096，可由 gate 文档的 `:max-receipts` 覆盖。
- **已关闭的部分（二）**：`:runs` 的终态正文重复存储已在 `c6cc6c4` 关闭。每条终态 run 的回答同时存在于 run 的 `:text` 与 `:conversation` 的 assistant 消息里，实测重复占整文档 16.2%（500 轮 155000 字符、1000 轮 310000 字符）。`state/collapse-run-text` 保留最新 `:run-text-window`（默认 64）条终态 run 的正文，更早的只留 `:text-length`。活跃 run 与 actor run 永不折叠：前者靠 `:text` 累积，后者的 `put-assistant` 故意跳过 conversation，run 记录是那份回答的唯一副本。
- **已关闭的部分（三）**：`:calls` 的 request snapshot 冗余已在 `62293e0`、`9348cbb`、`6d06a29` 关闭。每条 model call 冻结整份出站消息窗口，终态后无任何读者读 `[:request :snapshot :messages]`。实测带 32 条 300 字符窗口时 100 轮文档 1 817 818 字符，其中 89% 是这些快照。既有 `hc.compose.owner.compact/compact-state` 早就能做这个外置，但只在 `commit-message!` 一处、由 `:compact-threshold`（生产配 1000000）开关式触发；那个开关本身要 `(count (pr-str state))` 全量序列化一次，且文档会锯齿——55 轮压到 137 296 后 45 轮又长回 954 108。现在改为每次 commit 都压缩（`compact-state` 幂等），并把 store 在 `start!` 处包一层 `store/compacting-store`，让 5 处 commit 路径全部受保护而不只是消息路径。另把 `max-preview-chars` 从 `max-inline-chars` 解耦成独立的 128：preview 在 `compact.clj` 外零读者，之前每条已结算 call 白扛 1 KiB。合计后 500 轮文档从 3 654 616 降到 1 027 816（省 72%）。
- **剩余缺口（一）**：每个流式 chunk 仍逐条提交。`src/hc/compose/owner/dispatch.clj:301-312` 的 `deliver!` 对每个 provider 事件直接 `post!`，`state.clj:1002-1012` 的 `append-run-text` 每次追加都把整段文本重新 `str` 拼接并重写 assistant 消息。实测 2000 个 chunk 得到 14.9 KB 文本、33.9 KB 文档，写放大与 chunk 数成正比。
- **剩余缺口（二）**：只剩 `:conversation` 本身仍随会话线性增长且无上限。`c6cc6c4` 折叠了 run 的重复正文，`62293e0`/`9348cbb`/`6d06a29` 外置了 call 的 request 快照，但每条消息的原文仍必须在 `:conversation` 内联——它不是重复副本，是唯一副本。合成 500 轮会话实测 33%（`:conversation` 占整文档三分之一）。发布侧的 `snapshot.clj:858-880` 每 tick 全量投影所有可见会话，见 D-50；那条路径只受 `:published-windows` 约束，不受 `history-window-size` 约束。
- **已实测的当前水位**：500 轮会话在四处修复后共 1 027 816 字符，commit 5.48 ms（`2437e3b` 之前是 31.81 ms，因为 `compact-state` 为报 `:saved` 而做了两次全量 `pr-str`；加 `:measure? false` 后消除）。对照未修复的 3 654 616 字符，同会话省 72%、commit 快 5.8 倍。
- **影响**：长会话的 Owner 整态 CAS 越来越慢；长流式回合放大持久层写。真实成本已实测：主力是 `hc.server.storage.migration/normalize` 的两次全量序列化——`migration.clj:180` 的 `(count (pr-str doc))` 约 36%，`migration.clj:146` 的 `(pr-str (canonical-form …))` + SHA-256 约 50%。LMDB `transact!` + `sync!` 只占 2.5%（`:nosync` 与 `:sync` 中位数几乎相同，fsync 不是瓶颈），提交后返回视图约 11%。线性拟合 `commit_ms = 2.99 + 0.0430 × turns`：20 ms 在约 400 轮越过，1 MiB 在约 2031 轮到达（那时约 89 ms）。中文载荷按 UTF-8 字节算 1 MiB 只需约 795 轮。**20 ms 阈值早已越过，且触发它的轮数远早于 1 MiB。**
- **不能直接裁剪的原因**：`:conversation` 是 Scope/Pair-Hint 的权威源，也是 sealed view 的切片来源（`hc.core.scope.runtime/conversation->items`、`hc.compose.owner.state/project-conversation`）。`sealed-scope-view`（`state.clj:1357`）的 docstring 明写 "The Owner conversation is never trimmed"，实现靠 `conversation-call-index` 在任意位置定位 reset beat 再 `subvec`；中间挖洞会让下标语义失效。另有两处需要随机访问任意旧消息：`handlers.clj:828` 的 `mirror-history-message!`（操作员可编辑任意历史消息）与 `state.clj:1362` 的 `conversation-call-index`。完整读者普查见 `plans/D-37-retention-census.md`，其中列出 12 处读者（补上了先前遗漏的 4 处）。
- **chunk 合并已试过，不能这样做**：曾在 `dispatch.clj` 的 `deliver!` 按 1 KiB 阈值合并相邻 chunk，`recovered` 前的一切都正确，但取消路径暴露了真实缺陷——Agent 的 buffer 由发送侧持有，而「Esc 停止」由 Owner 侧最终化。仅靠 `:completed`/`:failed` 前的 flush 无法覆盖取消：`hc.compose.owner.runtime-test` 的 `streaming-cancellation-finalizes-partial-content` 显示，取消时阈值以下的文本从未提交，用户已看到的半句会消失。因此该改动已整体撤销（逐条提交保证不丢字）。正确形态需要发送侧在取消时**同步**flush 并让 Owner 等待该 flush 落库，或把 buffer 移到 Owner 侧（跨消息聚合），两者都改变取消时序契约，必须单独设计并验收。
- **把 buffer 放进 Owner 状态也不是无条件的解**：`runtime.clj:398` 的 `commit-message!` 是一个 `loop`，在 `:VERSION_CONFLICT` 时 `reload!` 后 `recur`（`attempt < 8`，即同一 message 最多重放九次）。若 buffer 在 `apply-message` 内被 drain，则第一次尝试把它折进 candidate、提交撞 `:VERSION_CONFLICT`、`reload!` 把状态还原、buffer 已空；第二次尝试同一 chunk 再来时无内容可折，那段文本从最终回答里永久消失。今天的逐 chunk 追加没有这个洞，因为文本落在 `apply-message` 重算出的**不可变状态值**里。因此 Owner 侧 buffer 必须把 drain 绑定到**提交成功**，而不是绑定到 message 应用：`commit-message!` 读取 buffer、折进 candidate、提交，且只在 `:ok?` 时清空被折的条目。这是 D-37 剩余部分的设计前提，不是可选项。
- **建议**：先把 buffer 放进 Owner 状态（每 attempt 一个 `:pending-chunk` 字段），由 Owner 在每次消息里合并，取消时由 Owner 自己 finalize——这样不存在发送侧与 Owner 的 flush 竞态；再为 `:conversation` 定义显式折叠策略（保留 reset 边界之后的权威消息与密封窗口所需的切片，把更早的已结算消息折叠为摘要并保留可追溯引用），同步更新 Pair-Hint 链与 sealed-view 的验收。
- **实测：前缀归档也会打断探针**。取 200 条、50 个 reset beat 的对话，砍掉前 40 条后，`reset-0` 与 `reset-9` 在保留段里查不到（局部下标为 `-`），而 `reset-10` 起才有局部下标。故前缀归档同样不是「丢掉即可」——被归档段必须保持随机可读（每段一个 blob），否则 `sealed-scope-view` 对旧 beat 的查询会退化为 `:SEALED_VIEW_BOUNDARY_MISSING`。今天不出错只因为 `scope-admission-check` 强制 ask 指向 `(peek beats)`，那是行为保证，存储层无法强制。
- **触发条件**：已满足并已处理 `:calls` 与 `:runs` 两部分。剩余 `:conversation` 本体在 500 轮约 313 KB；要让它真正有界，必须先锁死普查报告 §8 的五条前提（sealed view 回溯上界、归档 blob 的随机可读性、下标连续性方案、Pair-Hint 投影不变的证明、归档触发的可观测性）。在那之前，改动 `:conversation` 的存储形态会同时破坏 `:PREFIX_DRIFT` 与 sealed view 两个契约，风险高于收益。

## D-48 存储层 `normalize` 把同一文档序列化三次

- **条款**：`AGENTS.md` §7.1（当前态持久化与故障恢复窗口）与 §8.4 变更检查表。
- **缺口**：`src/hc/server/storage/migration.clj` 的 `normalize` 在每次提交时对同一份文档做三次全量序列化：
  1. `migration.clj:180` 的 `(count (pr-str doc))`，只为检查是否超过 `:max-doc-chars`；
  2. `migration.clj:146` 的 `digest` 里 `(pr-str (canonical-form (dissoc m :at :digest)))`；
  3. `canonical-form`（`migration.clj:123-135`）自己还用 `(pr-str k)` 作 `sorted-map-by` 的比较器，对每个 key 再序列化一次。
- **实测**：77 496 字符的文档，`normalize` 单次 8.11 ms，其中 `digest` 4.40 ms（54%）、`pr-str count` 1.12 ms。真实 Datalevin 提交 400 轮（344 502 字符）耗时 76.99 ms——即使文档体积已被 D-37 的三处修复压到原来的 28%，提交仍远超 20 ms。
- **影响**：这是长会话提交成本的最大单项来源，高于 LMDB 写入（2.5%）与 fsync。D-37 修完体积后，它成为唯一的主导项。
- **不建议的做法**：把 `:max-doc-chars` 检查删掉——它是 `:OWNER_STATE_TOO_LARGE` 唯一来源，删掉会让超限文档静默写入而非明确拒绝（`test/hc/server/storage/owner_state_size_test.clj` 专门锁这条）。把 `digest` 改成弱校验——digest 是迁移幂等与冲突判据，削弱它等于削弱存储合同。
- **已排除的做法（二）：两处序列化的对象不同，无法合并**。`migration.clj:180` 的 `(count (pr-str doc))` 序列化的是 `put` 里的**单个条目文档**；`migration.clj:146` 的 digest 序列化的是**整个 normalized migration map**（含 `put`/`delete`/`obligations`/`blob-refs`/`meta`）。后者是前者的超集但字节不同，位置也无法互推，因此没有一次序列化能同时服务两者。D-48 的可优化空间到此为止。
- **建议方向**：若将来确实要压这条路径，唯一可行方向是**消除 digest**（例如用文档版本号 + 修改计数替代内容哈希），但那是削弱幂等判据，属于协议变更而非优化，必须单独立项。
- **已排除的做法**：不能把 `canonical-form` 的比较器换成 `compare`。实测 `(sort compare [:b "a" :a "b" :c "B"])` 直接抛 `ClassCastException`——`compare` 无法比较 `Keyword` 与 `String`。`pr-str` 比较器不是冗余，它是唯一能对混合 key 类型给出全序的办法；Owner 状态的 key 实测只有 `String` 与 `Keyword` 两种，正好落在 `compare` 办不到的区间。而且它的排序结果（String 在前、Keyword 在后）已经是 digest 的一部分，换掉会改变所有已存数据的 digest，破坏幂等比对。**这一项不是缺陷，是正确设计。** 因此 D-48 的可优化空间只剩「大小检查与 digest 复用同一次序列化」，上限约 14%（大小检查占 1.12/8.11 ms）。
- **触发条件**：任何会话进入 300 轮以上，或单次提交观测到超过 50 ms。

## D-49 终态 run 记录保留整份固定元数据，比 `:conversation` 更占文档

- **条款**：`AGENTS.md` §5.1（单 Owner 状态迁移）、§7.1（当前态持久化）与 §8.4 变更检查表。
- **缺口**：`c6ccc6c` 只折叠了终态 run 的 `:text`，整个 run 记录仍完整保留。实测 400 轮会话：`:runs` 占整文档 **50%**（205 304 / 413 172 字符），`:conversation` 占 33%，`:calls` 占 17%。单个终态 run 记录 491 字符，其中 `:text` 只占 32、`:message-id` 32，其余约 460 字符是固定元数据：`:role :lane :tenure-id :agent-id :status :nudge? :nudge-id :call-id :attempt-id :started-at :finished-at :late-events :tool-call-ids :context :context-attempt-id :error :cancel-reason :search-status :search-degraded? :search-failed?`。
- **已完成的读者普查**（静态阅读，未运行）：Owner 层所有扫描 `:runs` 的循环都先判 `:status ∈ {:started :streaming}`，因此终态记录在这些路径里只做一次关键字比较——`conversation-stable?`（`state.clj:767`）、`active-run-for-call`（`state.clj:920`）、`finish-runs-for-call`（`state.clj:1159`）。发布侧的 `snapshot.clj:989` 读终态 run 的唯一目的是把 `:streaming?` 纠正为 false，按 `:message-id` 匹配，只需要「哪些 message-id 已终态」这一集合，不需要整条记录。`compact.clj:214` 与 `compact.clj:300` 对 `:runs` 逐条走 `compact-value`/`restore-value` 外置，不施加结构折叠。
- **运行时毒丸探针（比静态搜索更强的证据）**：把终态 run 的每个字段替换成一个「一旦被读取就抛标记异常」的对象，再对含该 run 的状态跑 9 个 Owner 探针（`conversation-stable?`、`active-run-for-call`、`run-for-attempt`、`board-summary`、`collapse-run-text`、`project-conversation`、`live-tool-count`、`compact-state`）。结果：23 个字段里只有 **4 个**被任何探针触碰——`:call-id`、`:status`、`:text`、`:message-id`；`:finished-at` 被 `collapse-run-text` 的排序读到但被 `(or (:finished-at run) 0)` 吞掉毒丸。其余 18 个字段（`:run-id :attempt-id :role :lane :tenure-id :agent-id :nudge? :nudge-id :context :context-attempt-id :started-at :cancel-reason :error :late-events :tool-call-ids :search-status :search-degraded? :search-failed?`）在这些路径上零读取。探针只覆盖它实际调用的函数，不能当作全局证明；但配合上面的静态普查，缩身的安全边界已经清楚得多。**探针的已知盲区**：`guards.clj` 的 `set-model-turn` 是 private，无法直接探；静态阅读确认它的 `tools?` 谓词会扫 `(vals (:runs state))` 并按 `(= (:call-id call) (:call-id run))` 配对、再问 `(seq (:tool-call-ids run))`——对**任何** run，含终态。这印证了下面第 1 条的结论：终态 run 必须保留 `:tool-call-ids` 的非空性，但不需要保留 id 内容。
- **影响**：这是修完 `:calls` 与 run `:text` 之后**剩余的最大单项**，比 `:conversation` 本体更大。按线性增长估算，`:runs` 每轮约 513 字符，800 轮即 411 KB；它比 `:conversation` 更早把文档推向 8 000 000 字符的 `:OWNER_STATE_TOO_LARGE` 硬上限。
- **不建议的做法**：不能删掉整条终态 run 记录。它是 `:message-id` → run 身份的唯一映射，`mirror-history-message!` 与发布侧的 streaming 纠正都依赖它；`recover-state`（`state.clj:1867`、`1892`、`1930`）在重启时按 `:status` 分流，也必须能看到这些记录。不能把 `:context` 无脑折叠——`start-run`（`state.clj:883`）把它写进 run，需先确认终态后有无读者。
- **两个候选字段的判定**：
  - `:tool-call-ids` 有 3 处读者。`guards.clj:455` 只问 `(seq (:tool-call-ids run))`——一个**非空性**判断，且它的扫描**没有** `:status` 过滤，所以确实会看终态 run，但只需要「是否非空」这一位。`handlers.clj:2088` 用 ids 的**内容**去反查工具名，但只在构造当前轮 turn 汇总时用。`handlers.clj:2121` 是写入方。因此终态 run 只需保留一个「有工具证据」的布尔，内容可外置。
  - `:context` 在 `state.clj:894` 由 `start-run` 从 call 复制进 run；全部 20 处 `:context` 命中都在讨论 agent 私有上下文（另一概念）或 call 的 context，没有一处读**run** 的 `:context`。判定：终态后无活读者，可外置。
- **实施位置**：`compact.clj` 的 `compact-value` 对 `:context` 既不外置（不在 `large-keys`）也不递归（不在 `recursive-keys`），所以原样保留；缩身必须是一个**独立步骤**，与 `collapse-run-text` 并列，由 `compact-state` 或 store 装饰器在每次提交时调用。不要试图通过往 `large-keys` 里加字段来实现——那些字段单条都很小，`max-inline-chars`（1024）永远达不到阈值，加了也不生效。
- **实测收益**：按「保留 `:run-id :call-id :attempt-id :status :finished-at :started-at :message-id :text :text-length`，并把 `:tool-call-ids` 压成一个 `:had-tools?` 布尔」模拟缩身，在 100/200/400/800 轮上稳定省下 `:runs` 区的 **60%**，即整文档的 **18%**。800 轮从 1 498 032 降到 1 227 052 字符（省 270 980）。已经过 `collapse-run-text` 的长会话同样省 60%（225 361 → 89 981）。这是 D-37 系列里单位收益最高的一刀，且不需要碰 `:conversation` 的任何契约。
- **建议方向**：把终态 run 折叠为「身份 + 终态 + 时间 + 计数 + 是否有工具证据」的最小集，把 `:context` 与 `:tool-call-ids` 明细外置到 blob 区（与 `:calls` 的 `:request :snapshot` 同一机制）。保守估计可去掉 460 字符/条 中的大部分。**必须先跑完整读者普查**：本条目只做了静态阅读，`test/` 下对 `:runs` 的直接断言（`compact_test.clj:40`、`run_text_window_test.clj:41`、`attempt_id_normalization_test.clj:48`、`handlers_test.clj:1285`）会受影响，须逐个判定是锁行为还是锁字段。
- **触发条件**：任何会话进入 300 轮以上；或单次提交观测到文档超过 400 KB。

## D-50 发布侧每 tick 全量投影所有会话的 `:conversation`

- **条款**：`AGENTS.md` §7.3（瘦客户端状态边界与 `STATE_SNAPSHOT`）与 §8.5 性能目标。
- **缺口**：`snapshot/baseline`（`src/hc/server/app/snapshot.clj:789` 起）里存在**两条**投影路径，一条有窗口、一条没有，而它们最后被 `merge-with` 合并：
  - `histories`（约 900-932 行）：走 `history-window-ids` + `:messages-window`，正确受 `history-window-size` 与 `inactive-history-window-size` 约束。
  - `conversations`（约 858-880 行）：把每个可见会话的**完整** `:conversation` 经 `conversation-view` 全量投影，**不受任何窗口约束**。
  - 最终 `base-by-id` 用 `(merge-with ...)` 把 `(:message/by-id messages)`（来自 `conversations`）与 `history-by-id` 合并。全量的 conversation 因此覆盖了窗口化的 history——窗口实际失效。
  `test/hc/server/app/lazy_owner_test.clj:203` 的 `baseline-publishes-the-active-window-and-no-inactive-entities` 之所以通过，是因为它传 `:owner-snapshots {}`，`visible-owners` 为空，`conversations` 自然为空。一旦有 owner snapshot，全量路径就接管。**测试没有覆盖有 owner snapshot 的情形。**`inactive-history-window-size` 的 docstring 明写「Default to 0, so a large session list publishes no entities for sessions nobody opened」，但该意图只对 `:session-records` 生效。
- **影响**：一个 500 轮会话在每次快照里都下发完整历史，即使客户端只打开一个会话；用户没打开的会话也全量下发。这与 §8.5 的「打开列表按需渲染」直接冲突。
- **窗口化的已知陷阱**：`conversation-view` 经 `normalize-message` → `message-id` 计算每条消息的 id，最后一级回退是 `(str "message-" index)`，而 `index` 是**投影后向量里的位置**。一旦丢掉前缀，没有显式 id 的消息会整体改变 id；客户端看到的是身份全换而非增量追加，且 `:published-windows` 记录的旧 id 集合再也匹配不上，窗口逻辑会静默退化成「发布全部」。真实条目都带稳定 `:message-id`（`put-assistant` 写入 `"assistant-<call-id>-<attempt-id>"`），但该回退路径必须被证明不可达，或被改成保留原始下标。
- **两条路径的数据源不同，不能简单删掉其中一条**：`conversations` 源自 `(:conversation state)`（活跃 Owner 会话的**在途**对话），`histories` 源自 `:session-records`（**已持久化**的会话记录）。前者携带尚未落盘的流式 assistant 消息，正是客户端在模型说话时要渲染的那条。删掉 `conversations` 会让流式回答从快照里消失。所以只能给它**加窗口**，不能移除。
- **窗口必须在投影之前**：`conversation-view` 只在 `snapshot.clj:854`（带缓存分支）与 `:860`（无缓存分支）被调用，两处都走 `normalize-message` 的 `(str "message-" index)` 末级回退。因此要先把 `:conversation` 向量按 id 集合收窄**再**传入，不能对 `conversation-view` 的输出收窄——后者会重新生成 id。
- **窗口还必须与 `histories` 一致**：`history-window-ids` 已能从 `published-windows` + `window-size` 算出某会话的 id 集合。直接复用它的结果过滤该会话的 conversation 条目，否则 merge 看到同一会话的两个不同尾巴，并集仍然错。
- **缓存键要带上窗口**：`:854` 的缓存以 `(:version owner)` 为键，不含窗口。若在缓存调用前收窄，必须把窗口并进键，否则第一个发布者的窗口会永久生效。`history-window-key` 就是为这个存在，照抄即可。
- **建议方向**：把两个窗口参数同样应用到 `conversations` 路径，并同步过滤 `:message/order`（客户端硬不变量：`:message/order` 必须恰好列出 `:message/by-id` 的全部键各一次，见 `src/hc/client/sync/AGENTS.md:230`）。窗口化必须保留原始下标，不能重排 `:conversation` 顺序。
- **触发条件**：任何会话超过 `history-window-size`，或可见会话数超过 10。
