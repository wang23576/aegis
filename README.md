# Aegis

> 企业智能体治理平台 —— 面向组织的多角色智能体平台，管理员集中治理智能体，用户按角色使用。

## 状态

**MVP 已跑通**（2026-11-24）：三角色鉴权、智能体管理、会话对话、三档权限、轨迹视图均已完成并通过浏览器端到端测试。设计决策见 [docs/需求设计分析.md](docs/需求设计分析.md)，测试与修复记录见 [docs/ISSUES.md](docs/ISSUES.md)。

## 核心能力

- **三种角色**：超级管理员（建用户/授权/管全部智能体）、智能体管理员（建智能体、管自己创建的）、普通用户（使用智能体、管自己的会话）。
- **默认智能体 claw**：系统内置，归属超管 admin，对所有用户可见；workspace 含 AGENTS.md + example skill。
- **内置工具**：文件读写、execute、web_fetch/search、memory 等 15 个，按智能体通过 `tools.json` 配置，UI 带"内置"标志。
- **对话**：DSH 风格两栏布局，选择智能体 → 对话 → 轨迹；右下角三档权限（仅可查看 / 工作区修改 / 完全权限）+ 模型选择。
- **文件隔离**：会话状态/日志按 (userId, sessionId) 隔离；记忆/产物按 userId 隔离（同用户跨会话共享）。USER 隔离粒度，不上 Docker 沙箱。

## 技术栈

- **后端**：Spring Boot 4.0.4 + Java 17 + agentscope-java 2.1.0（harness）+ SQLite + JWT。
- **前端**：React 18 + TypeScript + Vite 6（参考 DSH 样式）。
- **部署**：单个 fat jar 内含前端静态资源；Docker 多阶段构建。

## 快速开始

### 方式一：Docker Compose（推荐）

```bash
# 1. 准备模型与联网搜索配置（参考 docker/model.env.example 填写；该文件含密钥，已被 git 忽略）
cp docker/model.env.example docker/model.env
# web_search 与 paw 一致：优先 AnySearch（ANYSEARCH_API_KEY + ANYSEARCH_BASE_URL），
# 未配置 AnySearch 时回退 Tavily（TAVILY_API_KEY）。密钥从 paw 的 .env 复制即可。

# 2. 构建并启动
docker compose up -d --build

# 3. 打开 http://localhost:8081 ，用 admin / admin123 登录
```

### 方式二：本地开发

后端需 Java 17；agentscope SNAPSHOT 依赖需先执行 `scripts/prepare-m2.sh` 准备本地 Maven 仓库，再：

```bash
cd backend && mvn -DskipTests clean package
java -jar target/aegis-backend-1.0.0.jar
```

前端：`cd frontend && npm install && npm run dev`。详见 [AGENT.md](AGENT.md)。

## 目录

```
aegis/
├── AGENT.md                 # AI 协作与开发部署测试规范
├── README.md                # 本文件
├── docker-compose.yml       # 一键部署
├── backend/                 # Spring Boot 后端
├── frontend/                # React 前端
├── docker/                  # Dockerfile / Maven 配置 / 模型环境变量模板
├── docs/
│   ├── 需求设计分析.md       # 设计决策（Q1-Q15）
│   └── ISSUES.md            # 测试问题与修复记录
└── scripts/                 # 构建/重编译辅助脚本
```

## 协作方式

在本仓库工作的 AI 编码助手与维护者，请先阅读并遵守 [AGENT.md](AGENT.md)。
