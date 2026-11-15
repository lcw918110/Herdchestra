# DEBT

本文件记录 `AGENTS.md` 要求但当前实现尚未做到、或实现与 spec 存在已知偏差的缺口。每条包含：条款位置、缺口描述、影响、建议处理方式。只记缺口，不记已完成项；缺口关闭时删除本条。追赶 spec 的 AI 以本文件为工作队列。

编号不重排、不复用：已关闭条目（如 D-01、D-02、D-03、D-04、D-05、D-06、D-08–D-12、D-13–D-20、D-22、D-23、D-32）从本文件删除；历史状态见 git 历史与 `plans/debt-status-0.0.1.md`。新增条目从 D-38 起连续编号（D-38–D-46 已占用；D-32、D-38 已关闭但编号不复用）。


## D-37 `:conversation` 无上限（其余分区均已关闭）

- **条款**：`AGENTS.md` §5.1（单 Owner 状态迁移）、§6.1（流式阅读）与 §8.4 变更检查表。
- **已关闭的部分**：`:receipts` 的无限增长已在 `752060a` 关闭——窗口满时用 `:COMMAND_RECEIPT_WINDOW_FULL` + `:action :resync-state` 拒绝**新** command-id，重放与 payload 冲突仍走原语义，轮换 epoch 后窗口清空。默认上限 4096，可由 gate 文档的 `:max-receipts` 覆盖。
- **已关闭的部分（二）**：`:runs` 的终态正文重复存储已在 `c6cc6c4` 关闭。每条终态 run 的回答同时存在于 run 的 `:text` 与 `:conversation` 的 assistant 消息里，实测重复占整文档 16.2%（500 轮 155000 字符、1000 轮 310000 字符）。`state/collapse-run-text` 保留最新 `:run-text-window`（默认 64）条终态 run 的正文，更早的只留 `:text-length`。活跃 run 与 actor run 永不折叠：前者靠 `:text` 累积，后者的 `put-assistant` 故意跳过 conversation，run 记录是那份回答的唯一副本。
- **已关闭的部分（三）**：`:calls` 的 request snapshot 冗余已在 `62293e0`、`9348cbb`、`6d06a29` 关闭。每条 model call 冻结整份出站消息窗口，终态后无任何读者读 `[:request :snapshot :messages]`。实测带 32 条 300 字符窗口时 100 轮文档 1 817 818 字符，其中 89% 是这些快照。既有 `hc.compose.owner.compact/compact-state` 早就能做这个外置，但只在 `commit-message!` 一处、由 `:compact-threshold`（生产配 1000000）开关式触发；那个开关本身要 `(count (pr-str state))` 全量序列化一次，且文档会锯齿——55 轮压到 137 296 后 45 轮又长回 954 108。现在改为每次 commit 都压缩（`compact-state` 幂等），并把 store 在 `start!` 处包一层 `store/compacting-store`，让 5 处 commit 路径全部受保护而不只是消息路径。另把 `max-preview-chars` 从 `max-inline-chars` 解耦成独立的 128：preview 在 `compact.clj` 外零读者，之前每条已结算 call 白扛 1 KiB。合计后 500 轮文档从 3 654 616 降到 1 027 816（省 72%）。
- **已关闭的部分（四）**：流式 chunk 的逐条提交已在 `be56d33` 关闭。buffer 放在 Owner 状态（`(:stream-buffer ctx)`，永不进入已提交文档），门槛 192 字符或 240 ms；任一非 chunk 消息（terminal / cancel / tool-call / dispatch-failed）在转移前无条件折叠全部尾巴。实测 **2000 chunk / 20 000 字符 → 111 次提交（降 94.5%）**；取消路径保留 32 字符尾巴，`:text run` 与 `:text message` 一致。折叠永不移除文本，只有 `prune-stream-entry` 依据**已提交状态**（不是候选态）裁掉已被覆盖的前缀，因此 `:VERSION_CONFLICT` 重试会重算出同一段尾巴。发送侧完全不参与，`dispatch.clj` 未因此改动——这正是回避上一轮 flush 竞态的要点。
- **剩余缺口（二）**：只剩 `:conversation` 本身仍随会话线性增长且无上限。`c6cc6c4` 折叠了 run 的重复正文，`62293e0`/`9348cbb`/`6d06a29` 外置了 call 的 request 快照，但每条消息的原文仍必须在 `:conversation` 内联——它不是重复副本，是唯一副本。合成 500 轮会话实测 33%（`:conversation` 占整文档三分之一）。发布侧曾每 tick 全量投影所有可见会话（已由 `83679e7` 关闭：`conversations` 现在按会话收窄，活动会话用 `history-window-size`、其余用 `inactive-history-window-size`，并与 `:published-windows` 取并集；收窄在投影之后、缓存之后执行，所以 id 不重编号、缓存键无需含窗口）。
- **已实测的当前水位**：500 轮会话在四处修复后共 1 027 816 字符，commit 5.48 ms（`2437e3b` 之前是 31.81 ms，因为 `compact-state` 为报 `:saved` 而做了两次全量 `pr-str`；加 `:measure? false` 后消除）。对照未修复的 3 654 616 字符，同会话省 72%、commit 快 5.8 倍。
- **影响**：长会话的 Owner 整态 CAS 越来越慢；长流式回合放大持久层写。真实成本已实测：主力是 `hc.server.storage.migration/normalize` 的两次全量序列化——`migration.clj:180` 的 `(count (pr-str doc))` 约 36%，`migration.clj:146` 的 `(pr-str (canonical-form …))` + SHA-256 约 50%。LMDB `transact!` + `sync!` 只占 2.5%（`:nosync` 与 `:sync` 中位数几乎相同，fsync 不是瓶颈），提交后返回视图约 11%。线性拟合 `commit_ms = 2.99 + 0.0430 × turns`：20 ms 在约 400 轮越过，1 MiB 在约 2031 轮到达（那时约 89 ms）。中文载荷按 UTF-8 字节算 1 MiB 只需约 795 轮。**20 ms 阈值早已越过，且触发它的轮数远早于 1 MiB。**
- **不能直接裁剪的原因**：`:conversation` 是 Scope/Pair-Hint 的权威源，也是 sealed view 的切片来源（`hc.core.scope.runtime/conversation->items`、`hc.compose.owner.state/project-conversation`）。`sealed-scope-view`（`state.clj:1357`）的 docstring 明写 "The Owner conversation is never trimmed"，实现靠 `conversation-call-index` 在任意位置定位 reset beat 再 `subvec`；中间挖洞会让下标语义失效。另有两处需要随机访问任意旧消息：`handlers.clj:828` 的 `mirror-history-message!`（操作员可编辑任意历史消息）与 `state.clj:1362` 的 `conversation-call-index`。完整读者普查见 `plans/D-37-retention-census.md`，其中列出 12 处读者（补上了先前遗漏的 4 处）。
- **chunk 合并已试过，不能这样做**：曾在 `dispatch.clj` 的 `deliver!` 按 1 KiB 阈值合并相邻 chunk，`recovered` 前的一切都正确，但取消路径暴露了真实缺陷——Agent 的 buffer 由发送侧持有，而「Esc 停止」由 Owner 侧最终化。仅靠 `:completed`/`:failed` 前的 flush 无法覆盖取消：`hc.compose.owner.runtime-test` 的 `streaming-cancellation-finalizes-partial-content` 显示，取消时阈值以下的文本从未提交，用户已看到的半句会消失。因此该改动已整体撤销（逐条提交保证不丢字）。正确形态需要发送侧在取消时**同步**flush 并让 Owner 等待该 flush 落库，或把 buffer 移到 Owner 侧（跨消息聚合），两者都改变取消时序契约，必须单独设计并验收。
- **把 buffer 放进 Owner 状态也不是无条件的解**：`runtime.clj:398` 的 `commit-message!` 是一个 `loop`，在 `:VERSION_CONFLICT` 时 `reload!` 后 `recur`（`attempt < 8`，即同一 message 最多重放九次）。若 buffer 在 `apply-message` 内被 drain，则第一次尝试把它折进 candidate、提交撞 `:VERSION_CONFLICT`、`reload!` 把状态还原、buffer 已空；第二次尝试同一 chunk 再来时无内容可折，那段文本从最终回答里永久消失。今天的逐 chunk 追加没有这个洞，因为文本落在 `apply-message` 重算出的**不可变状态值**里。因此 Owner 侧 buffer 必须把 drain 绑定到**提交成功**，而不是绑定到 message 应用：`commit-message!` 读取 buffer、折进 candidate、提交，且只在 `:ok?` 时清空被折的条目。这是 D-37 剩余部分的设计前提，不是可选项。
- **建议**：先把 buffer 放进 Owner 状态（每 attempt 一个 `:pending-chunk` 字段），由 Owner 在每次消息里合并，取消时由 Owner 自己 finalize——这样不存在发送侧与 Owner 的 flush 竞态；再为 `:conversation` 定义显式折叠策略（保留 reset 边界之后的权威消息与密封窗口所需的切片，把更早的已结算消息折叠为摘要并保留可追溯引用），同步更新 Pair-Hint 链与 sealed-view 的验收。
- **实测：前缀归档也会打断探针**。取 200 条、50 个 reset beat 的对话，砍掉前 40 条后，`reset-0` 与 `reset-9` 在保留段里查不到（局部下标为 `-`），而 `reset-10` 起才有局部下标。故前缀归档同样不是「丢掉即可」——被归档段必须保持随机可读（每段一个 blob），否则 `sealed-scope-view` 对旧 beat 的查询会退化为 `:SEALED_VIEW_BOUNDARY_MISSING`。今天不出错只因为 `scope-admission-check` 强制 ask 指向 `(peek beats)`，那是行为保证，存储层无法强制。
- **触发条件**：`:calls` 快照外置、`:runs` 正文折叠、`:runs` 休眠元数据缩身（`9155417`）与 chunk 批量提交（`be56d33`）四部分均已关闭。剩下的是 `:conversation` 本体，500 轮约 313 KB，占整文档 33%。修完这四处后，长会话的其余成本已不再是重复副本——每一条消息原文都是唯一副本。
- **缩身后的新水位**：`9155417` 把终态 run 折叠为 `:run-id :call-id :attempt-id :status :started-at :finished-at :message-id :text :text-length :had-tools?`，走真实 `compact-state` 路径实测 `:runs` 省 33.3% / 48.7% / 52.6%（100 / 400 / 800 轮），整文档省 9.7% / 10.9% / 11.2%。收益随会话变长而上升，因为单条 run 是固定大小，文档其余部分不是。所以 `:conversation` 在文档里的占比只会更高，它是下一个也是最后一个大头。
- **要动它必须先锁死五个前提**：sealed view 回溯上界、归档 blob 的随机可读性、下标连续性方案、Pair-Hint 投影不变的证明、归档触发的可观测性（见 `plans/D-37-retention-census.md` §8）。在那之前，改动 `:conversation` 的存储形态会同时破坏 `:PREFIX_DRIFT` 与 sealed view 两个契约，风险高于收益。

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
