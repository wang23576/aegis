# Aegis 测试问题记录

> 测试闭环：发现问题 → 记录 → 修复 → 重新编译部署 → 复测 → 关闭。
> 本文件按 AGENT.md §7 规范维护。

## 状态说明
- 待修复 / 修复中 / 已修复待复测 / 已关闭

## 待 Alan 决策（非 bug，产品边界）
- **D-001【中】普通用户可见范围**：当前实现普通用户只能看到 claw（内置）+ 自己创建的（普通用户不能创建，故实际只有 claw）。需求原文"浏览并调用智能体"未明确普通用户是否可用智能体管理员创建的智能体。当前按"仅内置可见"处理，如需放开请指示。

---

### ISSUE-010  list_files 在工作区目录里"看到"不存在的 aegis.jar
- 发现时间：2026-11-24（Alan 实测反馈）
- 现象：`list_files {"path": "/data/workspaces/claw/1"}` 返回 `[FILE] /aegis.jar (54020157 bytes)`，但该目录实际没有此文件（`execute` 跑 `ls -la` / `find` 均证实不存在）；而同轮 `list_files {"path": "/app"}` 返回的是 `../../../app/aegis.jar`，两处大小一致（= /app/aegis.jar 实际大小）
- 定位过程（反编译 agentscope-harness 2.1.0-SNAPSHOT + 容器实机对照实验）：
  1. HarnessAgent 默认文件系统 = `ProjectAwareOverlay`：上层是工作区层（cwd=/data/workspaces/claw，ROOTED 模式，userId 作命名空间，条目按 cwd 相对路径渲染、剥掉 "1/" 前缀 → 工作区条目显示为裸名）；下层是"项目层" `LocalFilesystem(cwd=/app, SANDBOXED 虚拟模式)`
  2. `OverlayFilesystem.ls` 会把上下两层结果按条目路径**字符串去重后合并**；项目层条目经 `toVirtualPath` 渲染为 `"/" + 相对路径`（如 `/aegis.jar`）
  3. **实机对照实验**：`execute` 执行 `touch /app/zzz_probe.txt` 后立即 `list_files` 工作区，`/zzz_probe.txt` 立刻出现在列表中 → 实锤：项目层（/app）的虚拟条目被合并进了工作区目录列表
  4. 两层渲染样式不同（`/aegis.jar` vs `aegis.jar`），字符串去重失效 → /app/aegis.jar 以 `/aegis.jar` 形式"幻影"出现在工作区列表，**工作区里并无第二份 jar**
- 修复：属 harness 内建行为（把项目目录以虚拟路径透出给智能体），不在应用层强行绕过；改为在 AGENTS.md 脚手架增加「工具输出解读约定」：以 `/` 开头的 list_files 条目是 /app 的虚拟映射，判断目录真实内容必须用 `execute` 的 `ls -la` 交叉验证，以真实 shell 输出为准
- 状态：已关闭（按约定缓解；如需彻底隐藏项目层可后续用 `LocalFilesystemSpec.project()` 显式配置）

---

### ISSUE-011  轨迹里 execute 工具"无结果"（result 恒为空）
- 发现时间：2026-11-24（Alan 实测反馈）
- 现象：轨迹详情里 execute 的参数完整，但 result 恒为空字符串；list_files 的结果却能完整记录
- 根因：`ShellExecuteTool` 的结果块带 `withExecutionDetails`，框架 `ToolResultCallback` 按 ContentBlock 类型分流——TextBlock 发 `ToolResultTextDeltaEvent`，**非文本块发 `ToolResultDataDeltaEvent`**；SessionService 只监听了 TextDelta，DataDelta/ToolProgressEvent 全部漏掉
- 修复：SessionService 补抓三类事件——`ToolResultDataDeltaEvent`（提取 TextBlock 文本）、`ToolProgressEvent`（作为兜底缓冲）、`ToolResultEndEvent`（结果仍为空且 progress 有内容时回填）；并增加按 toolCallName 的兜底匹配（toolCallId 不一致时防丢）+ 空结果 WARN 日志便于后续排查；**关键修复**：收获已完成的调用后不再清空 pendingCalls——自动批准的写类工具（PERMISSION_ASKING → ConfirmResult）在下一轮才真正执行，其结果事件到达时调用记录必须仍在，否则全被丢弃
- 状态：已关闭
- 复测结果：execute 执行 `echo HELLO_AEGIS && ls /app`，轨迹 result 完整记录 `Exit code: 0\n\nHELLO_AEGIS\naegis.jar`；混合轮次（execute → list_files）顺序与结果均正确，无重复记录，无空结果 WARN。

---

### ISSUE-012  list_files 访问 .agentscope-runtime 报 "Session journal is reserved for the runtime"
- 发现时间：2026-11-24（Alan 实测反馈）
- 现象：`list_files {"path": "/data/workspaces/claw/1/.agentscope-runtime"}` 返回 Error: Tool execution failed: Session journal is reserved for the runtime
- 根因：agentscope-java 有意为之——`LocalFilesystem.resolvePath` 对路径中含 `.agentscope-runtime` 段的任何访问直接抛 SecurityException（该目录存放会话 journal / checkpoint，属运行时保留数据，禁止智能体读写）
- 结论：**正常防护行为，非 bug，无需修复**；已在 AGENTS.md 脚手架中告知智能体该目录不可访问属正常现象，不要反复尝试
- 状态：已关闭

---

### ISSUE-013  流式输出时手动滚动会被"抢"回底部
- 发现时间：2026-11-24（Alan 实测反馈）
- 现象：回复流式输出过程中，用户向上滚动查看历史，滚动位置会被每个 delta 自动 `scrollIntoView` 拉回底部
- 根因：ChatView 的流式跟随 effect 无条件 `scrollIntoView`，没有判断用户是否已离开底部
- 修复：两层修复——
  1. **布局层（真正的根因）**：`.chat-area` 作为 flex 子项缺 `min-height: 0`，内容超高时整个 body 在滚动而非聊天气泡区；补 `flex: 1 1 0; min-height: 0`（composer 加 `flex-shrink: 0`）
  2. **跟随层**：监听 `.chat-area` scroll 事件，距底部 <80px 视为贴底；非贴底时流式 delta 不再滚动，发送新消息或切换会话时重置为贴底；距底部 >200px 时显示悬浮「↓ 回到底部」按钮（流式内容增长时同步刷新按钮可见性）
- 状态：已关闭
- 复测结果：Playwright 实测——流式期间内容真实溢出（scrollHeight 898 → 1225），手动滚到顶部后 scrollTop 保持 0 不被拉回；悬浮按钮出现且点击后正确回到底部（scrollTop 626 ≈ 贴底）。

---

### ISSUE-008  claw 凭记忆回答目录问题 + 回复偶发英文
- 发现时间：2026-11-24（Alan 实测反馈）
- 现象：问 claw"你能查到哪些目录和文件"，它先从记忆里找答案返回（且记忆含早期错误快照），经用户追问才真实调用工具；回复偶尔整段切换英文
- 根因：claw workspace 的 AGENTS.md 仅一句英文提示词（"You are a helpful assistant..."），无语言约束、无工具使用规范；且 MEMORY.md 已被污染——早期错误条目（aegis.jar 双目录误判）靠 memory_save 反复"追加更正"，错误内容仍在库中被 memory_search 命中
- 修复：
  1. 新增 `WorkspaceScaffold` 统一脚手架：AGENTS.md = 人设 + 语言要求（始终简体中文）+ 工具使用规则（当前实际状态必须真实调工具查询、记忆只用于用户偏好和历史事实、不确定先查、失败如实告知）；DataBootstrap 种子与 AgentController 创建/更新智能体均改用它，PUT 更新时同步重写 AGENTS.md 并 invalidate agent 实例
  2. claw 的 DB sys_prompt 与 workspace AGENTS.md 通过 API 更新为中文版
  3. 清理被污染记忆：MEMORY.md 重置、memory/ 清空（原文件备份为 `MEMORY.md.polluted.bak`）
- 状态：已关闭
- 复测结果：同样提问"你能查到哪些目录和文件"，claw 真实调用 `list_files` ×2（两个根目录），全程简体中文回复实时数据；第二轮问"我刚才让你查了几个目录"正确回答 2 个（上下文记忆正常，未污染）。

---

### ISSUE-009  轨迹页与 DSH 完全不一致（按 DSH 完全重做）
- 发现时间：2026-11-24（Alan 实测反馈）
- 现象：原轨迹页只是"统计卡片 + 角色列表"，与 DSH 的台账式轨迹差距大
- 修复（按 DSH 信息架构 1:1 复刻）：
  1. 后端：`runWithAutoApprove` 捕获工具调用全生命周期（`ToolCallStartEvent` 起、`ToolCallDeltaEvent` 累积参数、`ToolResultTextDeltaEvent` 累积结果、`ToolResultEndEvent` 定状态/耗时），`tool_calls` 字段改存结构化 JSON（name/args/result/startedAt/durationMs/error + durationMs 总耗时）；trajectory 接口返回结构化数据
  2. 前端 DSH 式台账：
     - 32px sticky 工具栏：⏱总时长 / 轮次 / 调用次数 统计 + 折叠轮次开关 + 右侧搜索框（非命中行淡化 24%）
     - fixed 表格：事件列（kind 彩色 chip：用户=蓝、助手=紫、工具=琥珀；轮次竖轨 + "第 N 轮"角标）+ 内容列
     - 工具行等宽字体 `工具名 参数` 单行省略，有结果时 `调用 → 结果` 双栏 grid（错误红色）
     - 轮次折叠：摘要行 `… N 个步骤 · N 个工具调用`，可一键折叠/展开
     - 点击行打开右侧详情面板：概述（工具/耗时/状态）+ 参数/结果 pre 块；助手行显示全文与耗时
- 状态：已关闭
- 复测结果：浏览器实测——2 个轮次标签正确、折叠摘要行内容正确、搜索 list_files 命中 2 行其余淡化、工具行详情面板完整显示参数 JSON 与逐文件结果。截图目检布局整洁无错乱。

---

### ISSUE-006  轨迹「工具调用数」恒为 0（展示不准确）
- 现象：轨迹标签显示"工具调用 0"，但实际工具（如 write_file）已执行
- 根因：`chat()` 只返回回复文本，工具调用信息丢弃；`tool_calls` 字段从未写入，统计逻辑只按"字段非空的行数"计数
- 修复：
  1. `SessionService.chat()` 改返回 `ChatResult(reply, toolCalls)`
  2. 对话核心从 blocking `call().block()` 改为 `streamEvents(msg, ctx)` 事件流（对齐官方 PlanModeAutoExample）——因为 blocking 返回的最终 Msg 不含中间工具调用块；事件流可捕获 `ToolCallStartEvent`（工具名）、`AgentResultEvent`（最终回复）、`RequireUserConfirmEvent`（审批）
  3. Controller 保存工具名到 `tool_calls` 字段；trajectory 按逗号分隔计数
  4. 顺带修复 favicon 404（前端 index.html 加内联 SVG icon）
- 状态：已关闭
- 复测结果：创建文件对话返回 `toolCalls: ['write_file']`，reply"已创建 evtest.txt，内容为 hello"，轨迹 `toolCalls: 1`、turns 1。事件流方案生效。

---

### ISSUE-007  对话过程不流畅：阻塞式返回导致长时间无反馈

- **现象**：前端发送消息后，需等待 15 秒以上才一次性显示完整回复，期间界面无任何反馈，与 DSH 的流式体验差距明显
- **根因**：`POST /chat` 为阻塞式接口，后端用事件流聚合完再返回；前端也等完整响应后才渲染。对照 DSH：其前端用 `assistant/live-chunk` 增量渲染（消息状态 running 时持续追加文本）+ 状态提示 + 滚动跟随
- **修复**：
  - 后端新增 SSE 流式接口 `POST /api/sessions/{id}/chat/stream`：`SessionService.chat()` 增加 `ChatStreamListener`（onDelta/onThinkingDelta/onTool），事件消费改为阻塞迭代（servlet 线程上逐事件转发，SSE 写入安全有序）；事件：`delta`（正文增量）、`thinking`（思考增量）、`tool`（工具调用）、`done`（最终回复+工具清单）、`error`
  - 前端 `api.chatStream()` 用 fetch + ReadableStream 解析 SSE（EventSource 无法带 Authorization 头）；发送后立即显示流式气泡：思考中/调用工具状态行 + 打字光标，正文增量追加，流式期间滚动跟随；错误时保留已收到的部分内容
  - 原阻塞接口保留作为兼容回退
- 状态：已修复，已关闭
- 复测结果：curl 实测 SSE 帧序 `delta`（逐 token）→ `done`（最终回复+toolCalls）；带工具回合 `tool`（write_file）先于正文增量推送，`done.toolCalls=["write_file"]`。浏览器端到端：T+2.5s 即显示"思考中…"状态，正文增量渲染，完成后 Markdown 列表结构正确（同批补齐：claw 回复为 Markdown 格式，引入 `marked` 渲染助手消息与流式气泡，用户消息保持纯文本）。

---

### ISSUE-005  多轮对话卡住：PERMISSION_ASKING 未处理（工具审批协议缺失）
- 现象：第 1 轮对话返回空内容，第 2 轮报 `Agent is paused for human-in-the-loop confirmation... is in ASKING state`
- 根因：`chat()` 调用 `setPermissionMode(ctx, mode)` 启用了权限引擎；ACCEPT_EDITS 档下写工具（memory_save）走到默认 ASK，进入 ASKING 状态。同步 `call().block()` 未处理审批协议，留下 dangling ASKING 状态污染下一轮
- 关键认知：paw 不卡是因为它没配 permissionContext，走 lightweight path 工具直接 ALLOW；Aegis 启用了权限引擎就必须处理审批
- 修复（对齐框架官方 `PlanModeAutoExample`）：`SessionService.chat()` 加自动批准循环 `autoApprove()`——检测 `GenerateReason.PERMISSION_ASKING`，抽取 ASKING 的 `ToolUseBlock`，用原始块构造 `ConfirmResult(true, block)`，通过 `Msg.METADATA_CONFIRM_RESULTS` 二次 `call()` 恢复；12 轮上限防死循环
- 三档语义契合：EXPLORE 下写工具引擎级 DENY（不触发循环，仍被拒绝）；ACCEPT_EDITS/BYPASS 下 ASK 被自动批准（写操作执行）
- 状态：已关闭
- 复测结果：连续两轮对话均 200。第 1 轮"创建 hello.txt 内容 Aegis OK" → 回复"已创建 hello.txt，内容为 Aegis OK"（工具调用被自动批准并执行）；第 2 轮"刚才那个文件名是什么" → 回复"刚才创建的文件名是 hello.txt"（上下文记忆正确）。消息持久化 4 条，无 ASKING 卡死。

---

### ISSUE-004  LLM 对话请求失败：URL 中的 \r + thinking 模型未禁用
- 现象：`POST /api/sessions/{id}/chat` 报模型请求重试耗尽；日志显示 `Invalid URL: https://llm.talkweb.com.cn\r/chat/completions`
- 根因（两层）：
  1. `docker/model.env` 由 Python 文本模式写入，在 Windows 上变成 CRLF 换行；容器用 `sh -c '. /model.env'` source 时，变量值末尾带 `\r`，拼出非法 URL（authority 含非法字符）
  2. 模型创建未照搬 paw 的 `GenerateOptions`——`gpu-qwen3.8-27b` 是 thinking 模型，不通过 chat_template 禁用会导致长链推理、前端一直转圈
- 修复（完全对齐 paw `TalkwebModelConfig`）：
  1. `model.env` 用 LF 换行重写（`newline='\n'`）
  2. `AgentRegistry.buildModel()` 所有环境变量加 `.trim()` 防御 `\r`
  3. 加 `GenerateOptions.additionalBodyParams(chat_template_kwargs.enable_thinking=false)` + `stream(true)`，与 paw 一致
- 模型配置来源：paw `.env`（base=`https://llm.talkweb.com.cn`，model=`gpu-qwen3.8-27b`，apiKey 复用，未打印密钥）
- 状态：已关闭
- 复测结果：真实对话返回 200，回复 "我是 claw 工作区里的 AI 助手，可以帮你读写文件、执行命令、搜索信息和拆解复杂任务。"——claw 人格与 workspace sysPrompt 生效。

---

### ISSUE-003  创建用户返回的 id 恒为 0
- 现象：`POST /api/users` 返回 `id: 0`，但实际用户已入库（登录正常、role 正确）
- 根因：`DriverManagerDataSource` 每次操作新建连接，`SELECT last_insert_rowid()` 在新连接上取不到上次插入的 rowid
- 修复：改用 `INSERT ... RETURNING id`（SQLite 3.35+），一次操作直接返回自增 id
- 状态：已修复待复测
- 复测结果：

---

### ISSUE-002  带 @PathVariable 的接口全部 500：缺少 -parameters 编译参数
- 现象：`/api/sessions/{id}/messages` 等带路径变量的接口返回 500；此前被 AuthFilter 误报成 401
- 根因：Maven 编译未开启 `-parameters`，Spring 无法反射获取 `@PathVariable String id` 的参数名，抛 `IllegalArgumentException`
- 修复：
  1. pom 的 maven-compiler-plugin 增加 `<parameters>true</parameters>`
  2. 顺带修复 AuthFilter 吞掉下游异常的缺陷（把 token 解析与 chain.doFilter 分开 try，业务异常正常抛出以便定位）
- 状态：已修复待复测
- 复测结果：

---

### ISSUE-001  启动时 DataBootstrap 在数据库建表前执行
- 现象：容器启动失败，`no such table: app_user`，`DataBootstrap.seed()` 调 `findByUsername` 时表还不存在
- 复现：首次启动空数据卷
- 预期：先建表再灌种子数据
- 根因：`DbInitializer` 与 `DataBootstrap` 都是 `@PostConstruct`，Spring 不保证二者顺序
- 修复：`DataBootstrap` 构造器注入 `DbInitializer`，强制初始化顺序
- 状态：已关闭
- 复测结果：全新数据卷启动成功，`Started AegisApplication`，admin/claw 种子数据自动创建，无 `no such table` 错误。

---

---

### ISSUE-014  切换会话/标签页后正在运行或刚完成的回复消失（必须刷新才出现）
- 发现时间：2026-11-25（Alan 实测反馈）
- 现象：对话流式中切换到其他会话或管理后台，再返回时流式气泡与已完成回复都不见了；刷新页面后回复才出现
- 根因：`ChatView` 把 messages/stream/busy 全放在组件局部 state；切页导致组件卸载，SSE 回调仍继续跑但 `setState` 落在已卸载实例上被丢弃；返回时只从服务端已落库消息读取，而助手消息要等整轮结束才入库，运行中/未同步完成的内容全部丢失
- 修复：新增 `frontend/src/chatStore.ts` 模块级会话状态（按 sessionId 分区、SSE 回调直接写入 store），`ChatView` 通过 `useSyncExternalStore` 订阅；状态与组件生命周期解耦，切页/切会话/进管理后台期间 SSE 持续更新，返回即恢复当前状态；完成时把 `toolCallsJson` 随 done 事件回传以保留结构化轨迹数据
- 复测：Playwright 实测①运行中切到第 2 个会话再切回：流式气泡 + 思考块仍在（back_streaming=1）；②切到管理后台等待 26 秒完成后返回：助手回复已在、无流式气泡、无需刷新（has_done=true）

---

### ISSUE-015  轨迹视图不是流式的，运行中看不到工具调用
- 发现时间：2026-11-25（Alan 实测反馈）
- 现象：轨迹页只在打开时从 `/trajectory` 拉一次已落库数据；运行中的工具调用、参数、结果都不显示，必须等整轮结束再刷新
- 根因：后端 SSE 只有 `tool`（工具名）细粒度事件缺失；轨迹数据源仅为落库后的 `chat_message.tool_calls`，前端 TrajectoryView 无订阅机制
- 修复：
  - 后端 `ChatStreamListener` 增加 `onToolStart/onToolArgs/onToolResult/onToolEnd`，`SessionService` 在事件流各阶段回调；`SessionController` 转发为 SSE 帧 `tool_start/tool_args/tool_result/tool_end`（带 id、参数增量、结果增量、耗时、错误标记）
  - 前端 `chatStore` 按会话聚合实时工具记录；`TrajectoryView` 订阅 store，在运行中追加"实时行"（运行中工具带脉冲标记、实时参数/结果）；轮次结束后自动重新拉取 `/trajectory` 用持久化数据替换
- 复测：SSE API 实测帧序 `thinking×82 → tool → tool_start → tool_args×6 → tool_result → tool_end → delta×28 → done`；Playwright 实测运行中轨迹实时行出现（live_rows=2、running_tags=1），结束后工具行保留完整结果（tool_rows=1、has_ui_verify=true）

---

### ISSUE-016  模型思考模式被关闭，工具调用来得太突然
- 发现时间：2026-11-25（Alan 实测反馈）
- 现象：每次回复直接"开始执行工具"，没有可见思考过程
- 根因：`AgentRegistry.buildModel()` 为规避 ISSUE-004 的转圈问题显式下发 `chat_template_kwargs.enable_thinking=false`；前端 `onThinking` 回调只切换 phase、丢弃文本
- 修复：
  - `AgentRegistry` 默认开启 thinking（`AEGIS_MODEL_THINKING=false` 可回退关闭），标题生成仍单独禁用以保持_auxiliary 调用短平快
  - 前端 store 累积 thinking 增量并在流式气泡中以「💭 思考过程」折叠块展示；轨迹详情里也保留思考文本
- 复测：容器日志 `Model thinking enabled`；SSE 实测 `thinking` 帧 82 个；Playwright 实测思考块在切页返回后仍可见（thinking_len>0）

---

### ISSUE-017  轨迹行可读性差：Markdown 源码裸露、参数/结果硬截断、状态与时间缺失
- 发现时间：2026-11-25（Alan 反馈"轨迹有问题，可分析"，结合 DSH 源码对照）
- 问题清单与修复：
  1. 助手行直接显示原始 Markdown（`**`、`|---|`、标题标记）→ 行内改为 Markdown 纯文本摘要（去代码块/强调/表格/链接），详情面板提供"渲染/原文"切换（DSH `extractMarkdownPlainText` 思路）
  2. 工具参数截成 160 字符原始 JSON、结果截 120 字符 → 参数改 `key=value` 可读摘要（完整 JSON 在详情面板 pretty 展示），结果合并前 3 行非空内容摘要（保留 `Exit code` + 实际输出），避免只看到退出码
  3. 失败工具只有红色、无状态文案 → 行内增加"失败"标签，运行中保留脉冲"运行中…"，`data-running` 标记便于样式扩展
  4. 详情时间为助手消息入库时间 → 工具行改用自身 `startedAt`，显示毫秒级 `YYYY-MM-DD HH:MM:SS.mmm`（title 属性保留 Unix ms）
  5. 搜索只把不匹配行变暗 → 改为直接过滤（DSH 行为）
  6. 折叠轮次的摘要行不可点 → 点击摘要行展开该轮；同时修复折叠时同轮次其余行仍渲染的 bug（折叠后 14→2 行，展开恢复）
  7. 轮次结束后实时行与落库数据可能短暂重复 → 实时行仅在 `chat.busy || pendingUser || pendingAssistant` 时渲染，完成后立即切回落库行
- 对照来源：`packages/client/ui-trajectory/src/client/trajectory-preview.ts:13-20`、`layout.ts:1060-1086`、`TrajectoryTable.tsx:1030-1046,1147-1166`
- 复测：Playwright 实测助手行 `has_raw_markdown=false`、工具行 `execute command=pwd → Exit code: 0 · /app`、失败标签 4 个、详情时间 `2026-11-25 10:22:58.286`、渲染/原文切换生效、搜索无匹配行数 0、折叠/展开行数正确

---

### ISSUE-018  会话列表无法拿到 Session ID（报问题时无法精确指认会话）
- 发现时间：2026-11-25（Alan 需求）
- 需求：左侧历史会话右侧加 "..." 操作，浮层内提供"复制 Session ID"，便于反馈具体会话
- 参考 DSH：`packages/client/ui-workspace/src/client/session-actions/context-menus.tsx:72-75`（Copy Session ID 恒定可用）、`client/index.ts:296-301`（writeClipboard + toast）、`ui-primitives/src/clipboard.ts:29-61`（clipboard API + execCommand 降级）、`ui-context-menu/src/client/Button.tsx:30-53`（点击 ellipsis、锚点取按钮左下角、aria-haspopup）
- 修复：
  - 会话行右侧 "..." 按钮（hover/focus 显示，aria-haspopup=menu、aria-expanded、data-session-menu-trigger）
  - 点击弹出固定定位浮层（`role=menu` / `role=menuitem`），含"复制 Session ID"；复制完整 `session.id`
  - 剪贴板 `navigator.clipboard.writeText` 优先，`execCommand('copy')` 降级，复制后恢复原焦点；成功/失败 toast（`role=alert`）反馈
  - 外部 pointerdown 与 Escape 关闭（Escape 时焦点回到触发按钮），菜单项 autoFocus；会话行本身仍可点击打开
- 复测：Playwright 实测菜单文案"复制 Session ID"、复制值与 `data-session-id` 完全一致（clip_match=true）、toast"Session ID 已复制"；Escape 关闭正常

---

### ISSUE-019  轨迹不是运行过程的 1:1 还原：多次 LLM 调用被塞进一条助手消息
- 发现时间：2026-11-25（Alan 反馈："思考-调工具-思考-调工具-思考-回复"应对应多条轨迹记录；轨迹是反查核对的唯一依据）
- 对照结论（DSH 源码 + agentscope-java 事件实证）：
  - DSH 的层级是 turn > step，**一次模型调用 = 一个 step**，每步一条 `assistant/message`；工具调用/结果是独立记录；`ui-trajectory/layout.ts:706-801` 按 step 生成 MESSAGE 行、按 tool-call 生成 TOOL 行
  - agentscope-java 有精确的 step 锚点：`ModelCallStartEvent` / `ModelCallEndEvent`（后者携带 ChatUsage 六种 token 计数），无需猜边界
  - DSH 轨迹只有 7 种行（system/user/context/compacted/message/tool/subtool）；其余大量事件是日志/UI 元数据，不单独成行，但 DSH 会把原始事件全部留存供重建
- 修复：
  - 新增 `chat_event`（原始事件审计日志，逐条保存事件类型、replyId、toolCallId、blockId、createdAt、载荷）与 `chat_step`（每次 LLM 调用的 thinking/text/工具/usage/首 token/耗时/状态）两张表；zip 导出新增 `events.jsonl` + `steps.json`
  - `SessionService` 以 `ModelCallStartEvent` 开 step、`ModelCallEndEvent` 收 step；文本/思考增量归所属 step，工具按 toolCallId 回填到发起它的 step；`RequestStopEvent(permission asking)` 标为 `awaiting-approval`，真实中断才标 `interrupted`
  - `/trajectory` 返回 `rows`：user / message(每 step 一行) / tool(每次调用一行) / context(权限、确认、subagent、custom 等非内容事件)，按事件 seq 归位并排序；旧会话仍从 chat_message 兼容投影
  - `/messages` 在每条助手消息内嵌 `steps`；前端 `chatStore` 实时维护 `liveSteps`，`StepBlocks` 将中间步骤折叠为"第 N 步 / 工具次数 / 耗时 / token"，最终无工具步骤作为可见回复；思考逐块折叠、工具独立展示
  - 轨迹详情补充本次调用的完整思考、token 用量、步骤编号、毫秒级时间；聊天区对 REQUEST_STOP / ALL_TOOLS_DENIED / SUBAGENT_EXPOSED 等事件显示提示条
- 复测：
  - API：SSE 实测 `step_start×3 → thinking×601 → tool_start×3 → tool_end×3 → step_end×3 → done`；轨迹 rows = user×1 + message×3 + tool×3 + context×13，原始事件 901 条覆盖 21 种事件类型；zip 含 5 个文件（meta/session.jsonl/steps.json/events.jsonl/trajectory.json）
  - Playwright：聊天区 2 个折叠过程步骤 + 1 个最终气泡、3 条工具行；轨迹 3 个"第 N 步"标签、3 条工具行、13 条事件行，详情可展开完整思考

---

### ISSUE-020  步骤化改造后对话布局挤成竖条 + 轨迹摘要丢失工具名下划线
- 发现时间：2026-11-25（Alan 截图反馈："这不对吧，改好后，对话和轨迹你要测试一下呀"）
- 根因：
  1. `StepBlocks` 返回多个并列元素（若干 `details.step-process` + 最终 `div.bubble`），而父容器 `.msg` 是 `display:flex`，这些元素被当成同一行的 flex 项横向排列，步骤块被挤到约 100px 宽、文字竖排
  2. `plainText()` 为去 Markdown 强调符号无条件删除 `_`，把 `list_files`、`action_observation`、`tool_call_id` 等标识符里的下划线也删掉了，导致轨迹摘要失真
- 修复：
  - 助手消息（含流式）整体包入 `.msg-body`（纵向 flex、min-width:0、gap:6px），步骤块与最终气泡按 DSH 风格上下堆叠、整宽显示；用户气泡保持原样
  - `plainText()` 改为只剥离成对的代码/强调标记（`` `x` ``、`**x**`、`__x__`、单个 `*x*`），保留标识符中的下划线；行内摘要无 Markdown 源码、工具/事件名保持原样
- 复测（Playwright 截图 + DOM 断言）：
  - 运行中：2 个步骤块 + 1 个流式气泡，`.msg-body` 与 `.step-process` 宽度均 776px，无窄竖条（tmp/chat_live.png）
  - 完成后：流式光标归零、最终回答整宽显示（tmp/chat_live_done.png）
  - 轨迹：15 行 = 用户×1 + 助手步骤×3 + 工具×2 + 事件×9；用户行保留 `list_files`、事件行保留 `action_observation`/`tool_call_id`，无反引号等 Markdown 残留（tmp/traj_after.png）
  - 注意：发送按钮在输入框为空时也会禁用，验证"运行中"应看按钮文案"回复中…/发送"，不能只看 disabled

---

### ISSUE-021  运行中前序步骤混入当前回复 + 轨迹思考与回复仍同行 + 框架内部事件干扰
- 发现时间：2026-11-25（Alan 截图反馈：运行中前后步骤同时出现回复内容；轨迹里 action_observation 是什么、思考与回复为何在一条记录）
- 根因：
  1. `chatStore.patchStep(undefined)` 用 `liveSteps[s.stepNo - 1]` 判断目标，导致每个 step 都命中自身，同一段增量被写进所有进行中的 step；旧步骤折叠块里也出现当前回复（完成后重新落库所以正常）
  2. 后端 `/trajectory` 每个 step 只产一条 `message` 行，`thinking` 作为附属字段塞在同一行详情里，不满足"每次 LLM 调用的思考与回复各自成记录"的核对要求
  3. agentscope-core 的 `ActionObserver` 会在工具执行 STARTED/RETURNED 时发 CUSTOM `action_observation`（`action_observation` 常量见 `core/observation/ActionObserver.class`、`ActionObservations.class`），`ModelRequestPreparer` 发 CUSTOM `context_build`；这些是框架内部审计事件，不是助手内容，逐条铺在主轨迹里造成干扰
- 修复：
  - `patchStep` 无步号时只更新当前（最后一个启动的）step；前序步骤保留自己的内容
  - `/trajectory` 每次模型调用拆成独立 `thinking` 行（完整思考）+ `message` 行（完整回复），工具仍独立成行；前端增加 `thinking` 行类型、标签、详情面板与实时行投影
  - CUSTOM `action_observation` / `context_build` 在行 meta 标 internal，主轨迹默认隐藏，工具栏「内部事件 (N)」开关可随时展开；原始事件日志与导出 zip 不受影响
- 复测：
  - Playwright 运行中截取正在输出的气泡与前序步骤：`earlierBodies` 只含该步自己的思考+工具，不含当前回复（tmp/issue021_live_text.png）
  - 轨迹默认 2 思考行 + 1 回复行 + 3 工具行 + 2 关键事件行；点「内部事件」后新增 8 条框架审计行（tmp/issue021_traj_clean.png / issue021_traj_all.png）
  - 详情面板：点思考行显示"思考过程（本次模型调用）"+完整思考；点回复行只显示回复正文，不再重复思考
