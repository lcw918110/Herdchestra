# DEBT

本文件记录 `AGENTS.md` 要求但当前实现尚未做到、或实现与 spec 存在已知偏差的缺口。每条包含：条款位置、缺口描述、影响、建议处理方式。只记缺口，不记已完成项；缺口关闭时删除本条。追赶 spec 的 AI 以本文件为工作队列。

编号不重排、不复用：已关闭条目（如 D-01、D-02、D-03、D-04、D-05、D-06、D-08–D-12、D-13–D-20、D-22、D-23、D-32）从本文件删除；历史状态见 git 历史与 `plans/debt-status-0.0.1.md`。新增条目从 D-38 起连续编号（D-38–D-46 已占用；D-32、D-38 已关闭但编号不复用）。

## D-37 `:conversation` / `:calls` / `:runs` 无保留策略，流式 chunk 逐条提交

- **条款**：`AGENTS.md` §5.1（单 Owner 状态迁移）、§6.1（流式阅读）与 §8.4 变更检查表。
- **已关闭的部分**：`:receipts` 的无限增长已在 `752060a` 关闭——窗口满时用 `:COMMAND_RECEIPT_WINDOW_FULL` + `:action :resync-state` 拒绝**新** command-id，重放与 payload 冲突仍走原语义，轮换 epoch 后窗口清空。默认上限 4096，可由 gate 文档的 `:max-receipts` 覆盖。
- **剩余缺口（一）**：每个流式 chunk 仍逐条提交。`src/hc/compose/owner/dispatch.clj:301-312` 的 `deliver!` 对每个 provider 事件直接 `post!`，`state.clj:1002-1012` 的 `append-run-text` 每次追加都把整段文本重新 `str` 拼接并重写 assistant 消息。实测 2000 个 chunk 得到 14.9 KB 文本、33.9 KB 文档，写放大与 chunk 数成正比。
- **剩余缺口（二）**：`:conversation`、`:calls`、`:runs` 随会话增长且无上限。实测 200 轮会话的 `:conversation` 已占 125 KB，整个 Owner 文档 128 KB。`src/hc/server/app/snapshot.clj:725-735` 的 window 只限制**发布**，不限制 Owner 文档本体。
- **影响**：长会话的 Owner 整态 CAS 越来越慢，最终逼近存储文档大小与提交成本边界；长流式回合放大持久层写。
- **不能直接裁剪的原因**：`:conversation` 是 Scope/Pair-Hint 的权威源，也是 sealed view 的切片来源（`hc.core.scope.runtime/conversation->items`、`hc.compose.owner.state/project-conversation`）。裁剪会改变出站提示词组合与前缀链，必须与 `:PREFIX_DRIFT` 语义、`reset-beats` 边界和接力屏障一起设计；`:calls` 中未结/未确认的记录不可丢弃。
- **chunk 合并已试过，不能这样做**：曾在 `dispatch.clj` 的 `deliver!` 按 1 KiB 阈值合并相邻 chunk，`recovered` 前的一切都正确，但取消路径暴露了真实缺陷——Agent 的 buffer 由发送侧持有，而「Esc 停止」由 Owner 侧最终化。仅靠 `:completed`/`:failed` 前的 flush 无法覆盖取消：`hc.compose.owner.runtime-test` 的 `streaming-cancellation-finalizes-partial-content` 显示，取消时阈值以下的文本从未提交，用户已看到的半句会消失。因此该改动已整体撤销（逐条提交保证不丢字）。正确形态需要发送侧在取消时**同步**flush 并让 Owner 等待该 flush 落库，或把 buffer 移到 Owner 侧（跨消息聚合），两者都改变取消时序契约，必须单独设计并验收。
- **建议**：先把 buffer 放进 Owner 状态（每 attempt 一个 `:pending-chunk` 字段），由 Owner 在每次消息里合并，取消时由 Owner 自己 finalize——这样不存在发送侧与 Owner 的 flush 竞态；再为 `:conversation` 定义显式折叠策略（保留 reset 边界之后的权威消息与密封窗口所需的切片，把更早的已结算消息折叠为摘要并保留可追溯引用），同步更新 Pair-Hint 链与 sealed-view 的验收。
- **触发条件**：单会话 Owner 文档超过 1 MiB，或持久层 CAS 提交耗时超过 20 ms 时优先处理缺口（二）；出现流式回合卡顿时优先处理缺口（一）。
