# 测试报告（实测）

最后更新：2026-09-08。所有数字来自本机实际执行，未通过未执行的测试一律不声称。

## 执行环境

- Windows 10 (x64)，Java 17.0.12，Maven 3.9.12，MySQL 8.0.44（本地服务）
- Python 3.12.10 venv，FFmpeg/FFprobe 9.0.1（gyan full build，winget 安装）
- Node 22.22.2，Vite 6

## 1. 后端集成测试（`backend mvn test`，真实 MySQL 测试库）

**56 个用例全部通过，0 失败 0 错误。**

| 套件 | 数量 | 覆盖 |
| --- | --- | --- |
| AuthIntegrationTest | 6 | 注册/登录/登出/me、错误密码 401、重复用户名 409、非法用户名 400 |
| UploadIntegrationTest | 11 | 完整分片上传+合并、complete 幂等（并发不产生双媒体）、重复分片内容冲突 409、缺片 complete 409、断点续传（只补缺失分片）、跨用户访问 404、路径穿越文件名净化、配额原子预留（6 并发 1 成功）、越界分片 400、取消释放配额与分片、未认证 401 |
| TaskQueueIntegrationTest | 7 | 双 Worker SKIP LOCKED 领取互斥、旧 attemptToken 回调 409、取消后迟到成功不复活（记录 CANCELLED）、QUEUED 即时取消、租约过期清扫重排、重试耗尽 FAILED、内部接口无 token 403 |
| ProjectRenderIntegrationTest | 7 | 版本链不可变+expectedRevision 409、非法时间范围/音量 400、跨用户工程 404、渲染绑定不可变版本、Idempotency-Key 回放同一渲染、物理路径不泄露、跨用户下载 404/409 |
| AnalysisHighlightIntegrationTest | 3 | ANALYZE 结果落库（AUTO 事件、不虚构 actor）、候选生成 108000–142000ms 精确断言、候选接受、规则参数变更产生新 run 不覆盖旧候选、不支持输入 fail→run FAILED、跨用户分析 404 |
| HighlightRuleEngineTest | 8 | 纯引擎：证据 reason、窗口计数、边界裁剪、合并上限 30s、同输入同输出、类型过滤、actor 约束 |
| AdminIntegrationTest | 4 | 普通用户访问管理面 403、实测统计字段、配额调整（低于已用自动钳制/负值 400）、适配器生命周期（VERIFIED 无凭据 400、凭据通过、非法迁移 409） |
| AdminIntegrationTest（补充） | 1 | Worker 心跳 ping（内部鉴权接口）→ 管理统计显示在线；渲染实时率实测（5s 执行 / 4s 成片 = 1.25） |
| AnalysisReuseIntegrationTest | 1 | §14 复用键：同 media+适配器版本+配置哈希+算法版本 → 复用已完成的 run（不重复排队）；配置不同 → 新 run |
| EdlValidatorTest | 7 | CROP/SOURCE 模式、未知模式拒绝、打码区域越界/负值拒绝、JSON 往返保留 masks |
| ShareIntegrationTest | 1 | 令牌创建/匿名下载 200/撤销后 410/假令牌 404 |

## 2. Worker 测试（`media-worker pytest`）

**15 个用例全部通过。** 夹具全部为合成素材（OpenCV 计数器 / testsrc2），文件名与注释均标注 SYNTHETIC。

- 探测：时长/分辨率/编码/音轨数解析；无音轨正确报告；非视频文件明确失败
- 渲染：双段 trim+concat 帧级精度（期望 4.5s ±0.6s 实测通过）、中文字幕（UTF-8 文本文件注入，
  不进滤镜表达式）、无音源 `-an`、音量过滤
- 取消：cancel check 触发后杀进程树并抛 CancellationRequested
- OCR 分析器（RapidOCR，真实 OCR 链路）：计数器增量 5–8 事件、事件时间有序、
  证据截图落盘、置信度>0.5、去重窗口抑制、无 ROI 明确失败、即时取消

## 3. 端到端闭环（真实后端 + 真实 Worker + 真实 FFmpeg + MySQL）

**阶段1 `run_e2e.py`：31/31 通过。**
注册登录 → 分片上传（模拟断线只补缺失分片）→ 合并 → 探测 READY（时长/分辨率正确）→
Worker 生成预览+缩略图 → 预览 Range 206 → 跨用户 404 → 手动事件创建/修改/拒绝 →
工程版本链（R1 不可变、stale 409）→ 渲染绑定 R1 → 成片 H.264 时长 4.5s±0.5（帧级裁剪实测）→
下载/Range/跨用户 → 无物理路径泄露。

**阶段2 `run_e2e_phase2.py`：13/13 通过。**
合成计数器夹具 → 自动分析（真实 RapidOCR）→ SCORE_CHANGE 事件（4–8 个，AUTO+证据）→
适配器注册表显示 EXPERIMENTAL 且 **verified=false**（不虚构 VERIFIED）→
候选规则引擎（理由可追溯、边界 0–8s 内）→ 候选接受。

### 管理面 `run_e2e_admin.py`：10/10 通过（含 DISABLED→EXPERIMENTAL 自恢复，可重复执行）
环境变量注入初始管理员（不硬编码、不入日志）→ 普通用户 403 → 实测统计 → 配额调整并立即对用户生效 → VERIFIED 无真实游戏评测凭据被拒绝 → DISABLED 生效。

### 阶段3 `run_e2e_phase3.py`：9/9 通过
SSE 会话鉴权连接 → 上传触发 PROBE 的终态事件经 SSE 推送 → 预览进度百分比实时推送 →
CROP+打码 EDL 通过校验、越界打码 400 → 渲染成功 → 输出 400×400 铺满、
打码区域（源坐标 0.25–0.5 经裁剪映射）实测纯黑。

## 4. 前端 GUI 冒烟（浏览器实测）

- 构建通过（vite build）
- 登录页渲染 → UI 注册 guitest1 → 自动登录跳转工作台（显示真实配额 0/2GiB）
- 素材库/上传/任务中心/剪辑工程页全部渲染真实 API 数据

## 5. 诚实说明（未做/受限）

- **未执行 Docker Compose 全链路**：开发机无 Docker（`docker` 命令不存在）。
  Compose/Dockerfile 已交付但未实测容器启动；Testcontainers 集成测试同理，
  当前用本地 MySQL 真库替代，CI 环境应切回 Testcontainers。
- **RabbitMQ 未运行**：Outbox 表 + 同事务写入 + 进程内 Publisher 已实现；
  MQ 中继以可选 profile 留接口，未实测。
- **真实游戏识别准确率**：未评测（无授权样本）。所有识别相关指标只对合成夹具成立。
- 前端仅做了页面级 GUI 冒烟（注册/登录/各页渲染），未做全交互自动化脚本。
