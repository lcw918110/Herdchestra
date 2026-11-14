# DEBT

本文件记录 `AGENTS.md` 要求但当前实现尚未做到、或实现与 spec 存在已知偏差的缺口。每条包含：条款位置、缺口描述、影响、建议处理方式。只记缺口，不记已完成项；缺口关闭时删除本条。追赶 spec 的 AI 以本文件为工作队列。

编号不重排、不复用：已关闭条目（如 D-01、D-02、D-03、D-04、D-05、D-06、D-08–D-12、D-13–D-20、D-22、D-23、D-32）从本文件删除；历史状态见 git 历史与 `plans/debt-status-0.0.1.md`。新增条目从 D-38 起连续编号（D-38–D-46 已占用；D-32、D-38 已关闭但编号不复用）。

## D-33 单会话 64 MiB artifact 累计上限在捕获阶段未知剩余预算

- **条款**：`AGENTS.md` §7.2（artifact 64 MiB 上限）与附录 §8.6 产物提交流程。
- **缺口**：`src/hc/server/artifacts/registry.clj:505-555` 的 `ensure-session-capacity!` 只在登记时检查会话累计字节；`src/hc/server/artifacts/integration.clj:115-120` 把 `:max-bytes` 与 `:session-limit-bytes` 都设为同一个上限；`src/hc/server/shell/output.clj:21-23,63-80` 的捕获器只接收单 artifact 的 `:artifact-limit`，不知道本会话已用掉多少。捕获仍可能写出一份必然在登记时被 `:ARTIFACT_SESSION_LIMIT` 拒绝的文件。
- **影响**：用户付出等待后拿不到产物；临时文件与诊断更嘈杂，且截断/拒绝发生得过晚。
- **建议**：把会话剩余预算传入捕获器（或由捕获器先查询现有字节），在写入前截断/拒绝并给明确理由；登记仍保留最终权威检查。

## D-37 Owner chunk 未合并，`:calls`/`:conversation`/`:receipts` 无按条数保留策略

- **条款**：`AGENTS.md` §5.1（单 Owner 状态迁移）、§6.1（流式阅读）与 §8.4 变更检查表。
- **缺口**：`src/hc/compose/owner/AGENTS.md:1016-1026` 仍记录两项缺口：每个流式 chunk 逐次提交（未在 `deliver!` 前合并相邻 chunk，`src/hc/compose/owner/handlers.clj:511-515`、`state.clj:874-878` 仍是单 chunk 追加）；`:calls`、`:conversation`、`:receipts` 随会话增长，没有按条数/世代的保留或折叠策略。新增的 `src/hc/compose/owner/compact.clj` 与 `runtime.clj:369-380` 只在大状态超过阈值时压缩长字符串/回执载荷，不限制条目数量，也不改变 chunk 提交粒度。
- **影响**：长流式回合放大持久层写放大；长会话的 Owner 整态 CAS 会越来越慢，最终逼近文档大小/提交成本边界。
- **建议**：在 dispatch 的末端按时间窗或字节阈值合并相邻 chunk，一次提交一个合并块；为 `:calls`/`:conversation`/`:receipts` 定义显式保留/折叠策略（保留权威回执与未结调用，折叠已结算的细粒度记录），并保留现有按大小的 payload 压缩作为独立层。

## D-47 devops worker 内部的 shell 动作不进 Owner 调用账本

- **条款**：`AGENTS.md` §5.3（未结责任与 `:outcome-unknown` 恢复窗口）、§4.4（`.call` 会话边界）。
- **位置**：`src/hc/server/app/devops.clj:121-126` 与 `src/hc/server/app/system.clj:2381-2395` 的 `call` helper 同步执行 shell；`src/hc/compose/owner/state.clj:1631-1660` 的 `recover-state` 只把 Owner 账本里 `:dispatching`/`:running` 的 call 标为 `:outcome-unknown`；`src/hc/server/app/snapshot.clj:308-327` 只发布这些 call。
- **缺口**：Plan dedicated worker 通过 `call` 工具启动的 shell 会话不写入 Owner 的 `:calls`。JVM 被 SIGKILL 时，worker 的 shell 确实在途，但重启后没有任何记录可标 `:outcome-unknown`，「结果待确认」面板不会出现。
- **影响**：worker 侧的外部副作用在硬崩溃后失去可核对入口，与 §5.3「可能产生外部副作用的 Shell 结果未知时优先核对」不一致。E2E 10 因此只能靠 Plan 顶层 `clj-plan` 的 call 记录来间接覆盖。
- **建议**：让 devops worker 的 `call` 也登记为 Owner 可恢复的动作（或让 worker 会话把在途 shell 事实汇报给父 Owner），使 `recover-state` 能覆盖它；同时补一条针对该路径的验收。
- **优先级**：中；当前 E2E 10 已用顶层 call 覆盖同类语义，但 worker 路径本身仍是盲区。

## D-50 `#wb-aux-close` 与其他右上角控件缺少遮挡防护

- **条款**：`AGENTS.md` §7.4-1（关键操作必须在各视口可达）。
- **位置**：`src/hc/client/workbench/views/shell.cljs:419`（`#wb-aux-close`）；`resources/public/css/workbench.css:937-947`（toast-stack）；`:1015`、`:998`（抽屉 z-index）。
- **缺口**：右上角是 topbar、通知栈与抽屉关闭按钮的公共落点，三者此前同处或相近层级，任一浮层出现即可遮住另一个控件。当前修复把通知栈移出顶部栏并把抽屉提到通知之上，层级不再冲突，但没有任何测试或结构约束防止将来再次引入同层浮层。
- **影响**：历史上已发生两次（`#wb-aux-toggle` 与 `#wb-aux-close` 各一次）。表现是「按钮看得见但点不动」，且 Playwright 会重试到整个测试预算耗尽，真正原因不出现在报错里。
- **建议**：给右上角定义一个明确的层级序列（抽屉 > 通知 > topbar）并写成 CSS 变量注释；E2E 用 `clickUncovered`（已在 `e2e-19` 内）覆盖所有右上角控件；新增浮层时必须说明它在序列中的位置。
- **优先级**：低；已有测试侧防护。
