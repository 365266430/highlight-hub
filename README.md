# 开黑剪辑台 HighlightHub

面向普通游戏玩家的录像事件定位、高光候选筛选与轻量剪辑平台。

核心流程：上传录像 → 生成预览 → 手动标记或自动分析（实验性 OCR）→ 生成候选片段 → 用户确认与编辑 → 保存剪辑工程版本 → 异步渲染 → 下载成片。

> 诚实声明：所有游戏都可以使用手动标记与剪辑。自动识别目前只提供**实验性**的通用
> OCR 区域模板（状态 EXPERIMENTAL，未经任何真实游戏验证，不标 VERIFIED）。
> 系统只基于可见事件生成带证据的候选，不评判操作水平，不虚构成功率。

## 技术栈与实际使用版本

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Java | 17.0.12 LTS | 本机可用版本（任务书建议 21；Spring Boot 3.3 兼容 17，未引入无关迁移） |
| Spring Boot | 3.3.5 | Web / Security / Validation / Actuator |
| MyBatis-Plus | 3.5.7 | 数据访问（模块化单体） |
| MySQL | 8.0.44 | 本机运行实例；Docker 镜像 mysql:8.0 |
| Flyway | (Boot 管理) | V1 全量建表，UTF-8 + UTC |
| Python | 3.12.10 | media-worker |
| FastAPI / uvicorn | 0.115.6 / 0.34.0 | Worker 健康检查与内部进程 |
| FFmpeg / FFprobe | 9.0.1 (gyan full) | 探测 / 预览 / 缩略图 / 渲染（Worker 独占调用） |
| OpenCV | 4.x (headless) | 帧采样与预处理 |
| RapidOCR (onnxruntime) | 1.3/1.4 | OCR 实现（离线模型，pip 可装） |
| Vue / TS / Vite | 3.5 / 5.7 / 6.0 | 前端，Element Plus 2.9 |
| 无 Docker 环境的说明 | — | 开发机未安装 Docker：集成测试直连本地 MySQL 8（独立 `highlight_hub_test` 库），Testcontainers 留待有 Docker 的 CI（见 docs/test-report.md） |

## 目录结构

```
highlight-hub/
  backend/          # Spring Boot 业务权威服务（模块化单体）
  frontend/         # Vue3 + TS + Element Plus
  media-worker/     # Python Worker：探测/预览/缩略图/渲染/OCR 分析/清理
  deploy/           # docker-compose.yml
  docs/             # 架构、适配器开发、任务状态机、存储清理、测试报告、演示脚本、实施状态
  test-fixtures/    # 合成测试夹具生成脚本（明确标注合成测试）
  scripts/          # E2E 验证脚本（run_e2e.py / run_e2e_phase2.py）
```

## 快速开始（本地开发，无 Docker）

1. **数据库**：本地 MySQL 8，执行：
   ```sql
   CREATE DATABASE highlight_hub CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
   CREATE USER 'highlight'@'127.0.0.1' IDENTIFIED BY 'highlight_dev_2026';
   GRANT ALL PRIVILEGES ON highlight_hub.* TO 'highlight'@'127.0.0.1';
   ```
   Flyway 在后端启动时自动建表。测试使用独立的 `highlight_hub_test` 库（同账号授权）。

2. **FFmpeg**：安装 FFmpeg/FFprobe 后设置 `FFMPEG_PATH` / `FFPROBE_PATH`（默认在 PATH）。

3. **后端**（默认端口 8080）：
   ```bash
   cd backend && mvn spring-boot:run
   ```
   存储 root 默认 `./data`（gitignored）。所有素材经 `StorageService` 逻辑 key 寻址，
   物理路径永不返回给前端。

4. **Worker**：
   ```bash
   cd media-worker
   python -m venv .venv && .venv/Scripts/pip install -r requirements.txt
   STORAGE_ROOT=../data JAVA_BASE_URL=http://127.0.0.1:8080 \
   FFMPEG_PATH=/path/to/ffmpeg FFPROBE_PATH=/path/to/ffprobe \
   ./.venv/Scripts/python.exe -m app
   ```
   Worker 通过 `/internal/tasks/claim|heartbeat|progress|complete|fail` 拉取任务
   （服务级鉴权 `X-Worker-Token`；`attemptToken` 是每次尝试的执行凭证，不是服务鉴权替代品）。

5. **前端**：
   ```bash
   cd frontend && npm install && npm run dev
   ```
   开发代理把 `/api` 转发到 18080（可在 `vite.config.ts` 调整为 8080）。
   会话 Cookie HttpOnly + SameSite=Lax；CSRF 走 `GET /api/csrf` + `X-XSRF-TOKEN` 头。

6. **合成测试夹具**（明确标注合成测试）：
   ```bash
   python test-fixtures/scripts/generate_fixtures.py
   ```

## 快速开始（Docker Compose）

```bash
cd deploy
cp ../.env.example .env   # 修改所有 change-me
docker compose up --build
# 可选消息队列 profile：
docker compose --profile mq up
```

Java 与 Worker 通过命名卷 `hub-data` 共享受控媒体目录（容器内固定 `/data`，
逻辑 key 由服务端配置解析，不向容器传递宿主机绝对路径）。

## 默认开发配额（保守可调）

- 单文件上限：2 GiB（`UPLOAD_MAX_FILE_BYTES`）
- 每用户存储配额：2 GiB（`DEFAULT_USER_QUOTA_BYTES`，会话创建时原子预留）
- 分片大小：服务端协商 1–16 MiB，最多 4096 片
- 渲染输出上限：2 GiB；任务默认重试 3 次；开发默认渲染并发 1

## 测试与验证

- 后端集成测试（真实 MySQL）：`cd backend && mvn test`（60 个用例）
- Worker 单元/夹具测试：`cd media-worker && pytest`（15 个用例，合成夹具）
- 端到端闭环：`scripts/run_e2e.py`（31 项）、`run_e2e_phase2.py`（13 项）、`run_e2e_phase3.py`（SSE+裁剪打码，9 项）、`run_e2e_admin.py`（管理面，10 项）、`run_e2e_games.py`（游戏档案，13 项）
- 详见 [docs/test-report.md](docs/test-report.md)

## 文档索引

- [docs/architecture.md](docs/architecture.md) — 架构与组件职责
- [docs/adapter-development.md](docs/adapter-development.md) — 适配器/ROI 模板开发与诚实性约束
- [docs/game-genres.md](docs/game-genres.md) — 游戏类型普查与识别策略映射
- [docs/task-state-machine.md](docs/task-state-machine.md) — 任务状态机、租约、幂等与竞态处理
- [docs/storage-and-cleanup.md](docs/storage-and-cleanup.md) — 存储、下载鉴权与清理
- [docs/test-report.md](docs/test-report.md) — 实际执行的测试与结果
- [docs/demo-script.md](docs/demo-script.md) — 端到端演示脚本
- [docs/implementation-status.md](docs/implementation-status.md) — 已完成/未完成/阻塞（恢复会话先读）
