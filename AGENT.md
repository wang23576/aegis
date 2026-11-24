# Aegis · AGENT.md

> Aegis —— 企业智能体治理平台
> 本文件是本项目唯一的 AI 协作与工程规范，指导开发、编译、部署、测试、修复、复测的完整闭环。
> 修改本文件前先与维护者（Alan）确认。详细需求与决策见 [docs/需求设计分析.md](docs/需求设计分析.md)。

---

## 0. 状态与原则

- 阶段：**MVP 已完成并验证通过**（本地 + Docker 部署 + 浏览器端到端全通）· 最后更新 2026-11-24
- 已完成：三角色鉴权、智能体管理 + claw 内置、会话对话（事件流 + 自动批准）、三档权限、轨迹、管理后台；6 个 ISSUE 全部关闭（见 ISSUES.md）
- **总原则：快速走通。** MVP 优先，企业级增强留后续迭代。
- **自主闭环：** 开发→编译→本地验证→Docker 部署→真实网页测试→记录问题→修复→复测，循环至无 AI 可解问题。中途不因技术决策打断 Alan；仅当触及「需决策清单」（§9）才暂停。
- 忠实表述：不虚构进度、测试数据、已实现模块；未做就是未做。

---

## 1. 需求基线（已确认，勿反复澄清）

- **三角色单值**：超管 `SUPER_ADMIN` / 智能体管理员 `AGENT_ADMIN` / 普通用户 `USER`。
- **权限 = 角色能力 + 数据归属**：`agent.owner_id == 当前用户 id`；超管豁免归属检查。
- **认证**：仅超管创建用户；账号 + 密码登录；JWT（7 天）。无自助注册。
- **本期不做**：审计日志、强制沙箱、多租户、企业级 DB、插件/自动化任务/Channels、自助添加智能体、多角色、移动端。
- **权限档位三档**：仅查看=`EXPLORE`、工作区修改=`ACCEPT_EDITS`、完全权限=`BYPASS`（按会话存储与回显）。
- **默认智能体**：id=`claw`，owner=超管 admin，`builtin=true`，对所有用户可见可用，仅超管可改；workspace 从 scaffold 拷贝，启用 paw 默认 16 个内置工具。
- **工具**：直接用 agentscope 框架内置工具（read/write/edit/list/grep/glob、execute、web_fetch/search、memory 系列、thread 系列）；不同 agent 用各自 `tools.json` 的 allow/deny 选择；内置工具小标志由平台维护名单实现（框架无该元数据）。
- **隔离**：`isolationScope=USER`；会话状态/日志/权限档位按 (userId,sessionId) 隔离；MEMORY/outputs 按 userId 跨会话共享；skills/AGENTS.md 全局共享。shell 保留，越界改写共享区为已知优化点，测试阶段做攻击测试（§7.4）。

---

## 2. 技术栈与构建链（关键约束）

| 层 | 选型 | 备注 |
| --- | --- | --- |
| 后端 | Spring Boot + agentscope-harness（2.1.0-SNAPSHOT）+ SQLite | 新建 clean 工程，不改 paw 样例 |
| 前端 | React 18 + TypeScript + Vite 6 | 参考 DSH 风格与组件 |
| 数据库 | SQLite | 与 paw 一致 |
| 构建 | **全程 Docker** | 本机仅 JDK8，无法编译 agentscope（需 Java17） |

**构建链（必须遵守）**：
- agentscope 的 SNAPSHOT 不在公共 Maven 仓库，本地 m2 也没有。**Aegis 干净工程依赖它们，必须在 Docker 构建阶段先 install agentscope 模块，再 build Aegis。**
- 复用 paw 已验证的多阶段构建思路（`agentscope-java/agentscope-examples/agents/agentscope-paw/Dockerfile`）：前端 node 构建 → 后端 maven 构建（含 agentscope install）→ temurin17-jre 运行。
- Maven 源用阿里云镜像（`docker/maven-settings.xml`），避免中央仓库超时。
- 前端 npm 用 npmmirror registry。
- 只装必要模块（core / harness / 模型扩展 / sqlite），不装全部 channel 扩展，加快构建。

**模型接入**：从环境变量读（`AEGIS_MODEL_BASE_URL` / `AEGIS_MODEL_API_KEY` / `AEGIS_MODEL_NAME`），`.env.example` 给模板，真实值不入库不提交。参考 paw 的 OpenAI 兼容扩展。

---

## 3. 项目地图

```
aegis/
├── AGENT.md                # 本文件
├── README.md               # 项目门面
├── docs/
│   ├── 需求设计分析.md      # 需求、决策、风险（§1 基线来源）
│   └── ISSUES.md           # 测试问题记录与修复状态（§7 闭环核心）
├── backend/                # Spring Boot clean 工程
│   ├── pom.xml
│   └── src/main/...
├── frontend/               # React18+TS+Vite6
│   ├── package.json
│   └── src/...
├── docker-compose.yml      # 一键部署（根目录）
├── docker/                 # Dockerfile / maven-settings / model.env(.example)
└── scripts/                # rebuild.sh / prepare-m2.sh 等辅助脚本
```

---

## 4. 开发规范

**通用**
- 每次改动保持 diff 最小；新增依赖说明理由。
- 凭据不提交；用环境变量 + `.env.example`。
- 代码与文档同步：新增能力更新 README、AGENT.md、ISSUES.md。
- 提交前 `git status` / `git diff` 自检；未经 Alan 授权不 commit / push。

**后端**
- Java 17；包结构按域划分：`auth` / `user` / `agent` / `session` / `tool` / `permission` / `common`。
- 分层：Controller → Service → Repository；DTO 与 Entity 分离。
- 数据归属校验做成统一拦截器/AOP，避免每个接口重复写。
- 与 agentscope 交互集中在 `SessionService`，通过 `RuntimeContext` 传 userId/sessionId。

**前端**
- 两栏布局：左栏 Aegis 标题 + 新会话 + workspace 分组会话历史；右栏对话区 + 轨迹标签。
- 无插件/自动化任务/Channels；新会话选智能体（仅已有）；输入框无 + 号；权限档位 + 模型选择置右下。
- 对话/轨迹渲染参考 `deepseek-harness/apps/web`，不照搬业务逻辑。
- 切换会话按会话读取权限档位回显，不继承上一会话。

---

## 5. 编译与本地验证

```bash
# Docker 构建（本机无 JDK17，全部走 Docker）
docker compose -f docker/docker-compose.yml build

# 后端单元测试（容器内）
docker compose -f docker/docker-compose.yml run --rm backend mvn test

# 前端类型检查（容器内）
docker compose -f docker/docker-compose.yml run --rm frontend npm run build
```

本地验证顺序：后端容器健康 → 前端构建产物生成 → API 冒烟（登录、列出智能体、建会话）。
验证通过才进入 Docker 部署与网页测试。

---

## 6. Docker 部署

```bash
docker compose -f docker/docker-compose.yml up -d
docker compose -f docker/docker-compose.yml ps       # 状态
docker compose -f docker/docker-compose.yml logs -f   # 日志排障
```
- 后端绑定 127.0.0.1 的宿主端口（如 8081），前端静态资源由后端托管或独立端口。
- 数据卷持久化 SQLite 与 workspace。
- 端口冲突时改用其他宿主端口，不改容器内端口。

---

## 7. 测试与修复闭环（核心工作流）

### 7.1 测试层次
1. **API 冒烟**：curl 调登录、智能体 CRUD、会话创建、对话。
2. **真实网页测试**：浏览器打开首页，走完整用户流程（超管登录→建用户→建智能体→普通用户登录→选智能体开会话→对话→看轨迹）。
3. **攻击测试**（§1 隔离）：验证 shell 越界改写 skills/AGENTS.md、跨用户目录访问、多用户 outputs 干扰、权限档位回显。

### 7.2 问题记录（ISSUES.md）
每发现一个问题，立即记录，格式：
```
### ISSUE-001  简短标题
- 现象：具体表现 / 截图 / 日志
- 复现：操作步骤
- 预期 / 实际：
- 根因：
- 修复：
- 状态：待修复 / 修复中 / 已修复待复测 / 已关闭
- 复测结果：
```

### 7.3 闭环规则
- 发现问题 → 记录 → 修复 → 重新编译 → 重新部署 → 复测 → 更新状态。
- 循环直到：所有「AI 可解」问题状态为「已关闭」，无新增阻塞。
- 每个问题的修复都要实际验证，不允许只改代码不测。

### 7.4 攻击测试清单（学习 agentscope，非上线阻断）
- shell `cd ..` / 绝对路径改写 `<workspace>/skills/`、`AGENTS.md`
- 文件工具绝对路径访问他人 namespace
- 多用户同 agent 会话 outputs / MEMORY 是否干扰
- 切换 session 权限档位回显

---

## 8. 完成标准（本任务）

- [ ] AGENT.md / 需求文档 / README 同步
- [ ] 后端 + 前端工程骨架可编译
- [ ] 核心链路端到端跑通（登录→智能体→会话→对话→轨迹）
- [ ] claw 默认智能体由 admin 创建并初始化 workspace
- [ ] 三角色鉴权 + 数据归属生效
- [ ] 三档权限按会话下发与回显
- [ ] Docker 部署成功，网页真实测试通过
- [ ] ISSUES.md 中所有 AI 可解问题已关闭

---

## 9. 需决策清单（仅这些才暂停问 Alan）

- 模型服务的 base-url / api-key / model-name（若环境无可用的 OpenAI 兼容端点）
- 任何触及数据合规、对外暴露端口、生产凭据的事项
- 超出已确认基线的产品行为变更

其余技术问题 AI 自行决策并实施，不中断。

---

## 10. 沟通规范

- 结论先行；交付时列改动文件、验证方式与结果、遗留缺口。
- 未解决问题标注高/中/低风险。
- 偏好简洁列表 + wiki 风格 Markdown；流程用「图版流程 / 文字版流程」。
