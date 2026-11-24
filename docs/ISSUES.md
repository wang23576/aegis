# Aegis 测试问题记录

> 测试闭环：发现问题 → 记录 → 修复 → 重新编译部署 → 复测 → 关闭。
> 本文件按 AGENT.md §7 规范维护。

## 状态说明
- 待修复 / 修复中 / 已修复待复测 / 已关闭

## 待 Alan 决策（非 bug，产品边界）
- **D-001【中】普通用户可见范围**：当前实现普通用户只能看到 claw（内置）+ 自己创建的（普通用户不能创建，故实际只有 claw）。需求原文"浏览并调用智能体"未明确普通用户是否可用智能体管理员创建的智能体。当前按"仅内置可见"处理，如需放开请指示。

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
