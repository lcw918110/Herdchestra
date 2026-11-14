# DEBT

本文件记录 `AGENTS.md` 要求但当前实现尚未做到、或实现与 spec 存在已知偏差的缺口。每条包含：条款位置、缺口描述、影响、建议处理方式。只记缺口，不记已完成项；缺口关闭时删除本条。追赶 spec 的 AI 以本文件为工作队列。

编号不重排、不复用：已关闭条目（如 D-01、D-02、D-03、D-04、D-05、D-06、D-08–D-12、D-13–D-20、D-22、D-23、D-32）从本文件删除；历史状态见 git 历史与 `plans/debt-status-0.0.1.md`。新增条目从 D-38 起连续编号（D-38–D-46 已占用；D-32、D-38 已关闭但编号不复用）。

## D-31 MSYS2/UCRT64 子环境绑定未实现

- **条款**：`AGENTS.md` §7.2（环境供应：Pixi 与 MSYS2 的职责分工）。
- **缺口**：`src/hc/server/shell/process.clj:49-55` 的 Windows 分支只把字符串命令交给 `cmd.exe /c`，没有绑定 MSYS2/UCRT64 子环境，也没有把项目声明的 Unix 工具链注入 `PATH`、`MSYSTEM`、`CHERE_INVOKING` 等变量。`src/hc/server/artifacts/AGENTS.md:294-295` 的 Windows 项仍标 `NOT RUN`。
- **影响**：Windows 上的 holder/Plan dedicated worker 无法按 spec 使用项目的 bash 工具链；路径分隔符、权限与信号语义与 Unix 不一致，跨平台验收缺少可重复环境。
- **建议**：显式探测 MSYS2/UCRT64 的 `bash.exe`，按会话构造环境（`MSYSTEM=UCRT64`、工作区路径转换），保留 `cmd.exe /c` 作为无 bash 时的诚实回退；补 Windows 冒烟并把运行范围写入证据。

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

## D-41 `/healthz` 的 `session-count` 仍为 O(N) 索引扫描

- **条款**：`RUNNING.md`「健康检查」（`GET /healthz`）与 `AGENTS.md` §8.5 启动健康要求。
- **缺口**：`src/hc/server/app/system.clj:5566-5580` 与 `5789-5801` 的 `:session-count` 调用 `session-ports/list-session-ids`，返回包含全部会话 ID 的索引文档后取 `count`；这不是常量时间计数。已有测量为 1000 会话约 1.6 ms。
- **影响**：当前成本可接受，但 `/healthz` 随会话数线性增长；轮询、启动探针与大库会放大读取与内存分配。
- **建议**：在 `sessions` 分区维护与索引更新同事务的 committed session count，健康端点读取该计数；若继续接受 O(N)，在 `HACK.md` 记录预算、测量口径与超过阈值时的降级为 `:unknown`。
- **证据**：`src/hc/server/app/system.clj:5566-5580,5789-5801`；`src/hc/server/app/session_persistence.clj:443-445` 返回完整 `:ids`；已有测量为 1000 会话约 1.6 ms。

## D-42 CLJS runner 的失败退出码缺少回归守卫

- **条款**：`src/AGENTS.md` §5（测试必须真实失败）与 `AGENTS.md` §8.4（验证矩阵）。
- **已修复部分**：`test/js/run_*.cjs` 曾出现 cljs.test 打印失败但进程仍 `exit 0` 的假绿；现 7 个 runner 捕获 `console` 输出里的 `N failures, M errors` 摘要，并在 `beforeExit` 强制把 `process.exitCode` 置 1，同时保留显式设置/解析 `process.exitCode`。合成探针已验证旧 runner exit 0、新 runner exit 1。
- **剩余缺口**：没有一个**故意失败**的持久化回归测试证明 `scripts/test-cljs-gate.sh` 会在失败时返回非零；合成探针目前只是一次性证据。
- **影响**：未来重构 runner、替换编译入口或改变 `beforeExit` 时序时，退出码传播可能再次退化为假绿，而正常全绿门禁无法发现。
- **建议**：把合成探针固化为仅在 CI/显式开关下启用的必失败 fixture（或注入一个 failing test namespace），断言 Node/harness 返回非零、日志包含失败摘要，且门禁将其计入 failed；正常门禁不运行该 fixture。
- **证据**：上述 runner 的 `console` 摘要捕获与 `beforeExit` 处理；`scripts/test-cljs-gate.sh` 按 `timeout ... bash "$script"` 的退出码统计 PASS/FAIL；`HACK.md` §17.21 的合成探针；仓库仍无持久化负向退出码测试。

## D-43 0.0.2 tag 曾发布 0.0.1 版本号（已用 fixup 修正，保留为流程记录）

- **条款**：发布流程（版本 manifest、tag 与源码树一致性）。
- **事故**：`v0.0.2` 原树的四个 manifest 版本号仍全是 `0.0.1`，推送后成为「自称 0.0.1 的 0.0.2」。涉及 `src/hc/version.cljc`、`pixi.toml`、`package.json`、`package-lock.json`。
- **修正**：现 `v0.0.2` 指向 fixup 提交 `721c1b2`（annotated tag object `cb87d76`），其父为 `1623d4e`（原 0.0.2 source release，freeze tree `956136d0`、bundle `fd5e1f90`）。`git diff --name-status v0.0.2^{commit}^ v0.0.2^{commit}` 恰好 4 行、全部 `M`；`4 files changed, 5 insertions(+), 5 deletions(-)`；`package-lock.json` 第 13 行起逐字节相同（58302 B = 58302 B），依赖树零改动。
- **远端**：`refs/heads/master = 721c1b2…`（原 `379b021`）；`refs/tags/v0.0.1^{} = 40d01c8…`；`refs/tags/v0.0.2^{} = 721c1b2…`；无其它 `v0.0.*` 标签。本地保底分支 `release-0.0.2-source`（指向 `1623d4e`）未推送。
- **状态**：已修正并推送；本条是已关闭事故的保留记录，不进入待办队列。临时 index 的复现命令骨架见 `HACK.md` §17.20。
- **建议**：发布前用 `scripts/release-gate.sh` 的六处版本断言；tag 推送前核对 `v0.0.x^{commit}` 的四个 manifest 与源码/freeze tree；修正 tag 时用 `GIT_INDEX_FILE` 临时 index，禁止 `reset --hard` 或重建源码提交。
- **证据**：`HACK.md` §17.20；`plans/0.0.3-release-notes.md` 「0.0.2 标签修正」。

## D-46 E2E 21 的证据哈希口径与标准公式不可直接比较

- **条款**：E2E 证据必须使用统一、可比较的 `sourceTreeSha256` 口径。
- **缺口/现象**：E2E 21 使用自定义 `sha256(git HEAD tree + spec bytes)` 证据哈希，不等同于标准 `find src test` 公式。E2E 17 曾自带只 walk `src` + `test/e2e` 的私有实现（记录 `74c18e18…`），已在测试侧加固时移除；E2E 11/17 现在使用标准树。
- **影响**：跨 spec/门禁比较源树哈希会误报；E2E 21 的哈希不能直接与标准 tree 哈希对齐。
- **建议**：所有 spec 必须从 `test/e2e/support/evidence.mjs` 导入 `sourceTreeSha256`，禁止本地重写；确需自定义口径时必须在证据中显式标记，并在门禁比较时排除。
- **状态**：已知限制，不视为失败；已在 `HACK.md` §17.29 记录。

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
