# 实施状态（恢复会话先读本文档，不重建项目）

更新时间：2026-09-08

## 已完成

### 阶段 0
- 环境检查：Java 17.0.12（任务书建议 21，本机无 21，Spring Boot 3.3 兼容并记录）、
  Maven 3.9.12、Python 3.12.10、Node 22、MySQL 8.0.44 本地服务、Git 2.53。
- FFmpeg/FFprobe 9.0.1 经 winget 安装完成。Docker 不可用（见“阻塞”）。
- 目录结构建立，`PROJECT_SPEC.md` 为权威任务书，git 仓库初始化。

### 阶段 1（不依赖算法的完整闭环）——已验收
- 认证：注册/登录/登出/me，BCrypt，HttpOnly 会话 Cookie + SameSite=Lax，
  Spring Security 6 SPA CSRF 模式（/api/csrf + X-XSRF-TOKEN）。
- 分片上传：会话/分片/续传/内容冲突/配额原子预留/流式合并/服务端哈希/
  同用户内容去重/complete 幂等与并发安全/合并崩溃恢复/TTL 清理。
- 探测与预览：FFprobe 解析（duration/分辨率/编码/帧率/VFR 标识/旋转/音轨数），
  预览代理 720p H.264 faststart，固定间隔雪碧图缩略图（含索引与版本），
  预览/原始/成片均支持 HTTP Range。
- 手动事件：创建/修改/拒绝，event_revisions 保留操作记录。
- 剪辑工程：乐观并发 expectedRevision → 409，不可变版本链（渲染绑定版本），
  EDL 服务端白名单校验（时间边界/总时长/分辨率/音量/字幕长度/枚举）。
- 任务系统：DB 队列 + SKIP LOCKED 原子领取 + attemptToken + 租约心跳 + 取消竞态 +
  退避重试 + 清扫器；所有关键竞态有集成测试覆盖。
- Python Worker：PROBE/PREVIEW/THUMBNAIL/RENDER/CLEANUP 执行器，
  ffmpeg 参数数组 shell=False，字幕文本文件注入，真实进度解析，取消杀进程树，
  输出探测校验（时长容差/流存在/非空/大小上限）。
- 渲染下载：鉴权下载 + Range；成片页播放。
- 测试：后端 31→43 用例、Worker 12 用例、E2E 阶段1 31 项全部通过。

### 阶段 2（自动定位）——已验收（合成测试）
- 适配器注册表：定义/版本（不可变版本、DRAFT/EXPERIMENTAL/VERIFIED/DISABLED），
  内置 generic-ocr v1 为 **EXPERIMENTAL**（诚实：未经真实游戏验证，verified=false）。
- 用户校准 ROI（相对坐标 0~1、number/text 模式、多帧确认、去重窗、采样间隔）。
- 真实 OCR 链路：RapidOCR + OpenCV 顺序帧采样（容器时间戳，非 frameIndex/fps），
  多帧确认过滤抖动，数字变化合法性，去重合并，证据截图落盘。
- Java 候选规则引擎（纯函数）：窗口计数/填充/合并 gap/最大时长拆分/边界裁剪/actor 约束，
  reason 全部可追溯（“N 次事件出现在窗口内”），不输出无证据话术。
- 候选编辑：ACCEPTED/REJECTED/PENDING；规则重跑产生新 ruleRun，旧候选与工程不受影响。
- E2E 阶段2：13/13 通过（合成夹具上的真实 OCR → 事件 → 候选 → 接受）。

### 阶段 3（部分）
- 分享链接：高熵令牌（库存哈希）、过期、撤销、公开下载仅成片。
- SSE 任务事件推送：`GET /api/tasks/stream`（会话鉴权、仅推给所有者、心跳保活），
  终态与实时进度事件；前端任务中心以 SSE 为主、REST 轮询兜底（重连即回 REST 权威）。
- 竖屏/方形导出基础：EDL output.aspectMode=CROP（中心裁剪铺满）+ 源画面相对坐标
  固定打码（≤8 块，渲染在裁剪之前、始终跟踪原始画面）；渲染器版本 v2。
  E2E 实测：400×400 铺满输出 + 打码区域纯黑。
- Outbox：任务终态同事务插入 outbox_events + 进程内 Publisher（幂等消费表就绪）。
- 存储清理：上传会话 TTL 清扫、媒体软删 + CLEANUP 任务 + 配额释放、Worker 临时目录清理。

### 前端
- Vue3 + TS + Pinia + Router + Element Plus 完整骨架与页面：
  登录注册/工作台/上传（分片+续传+取消）/素材库/素材详情（预览+媒体信息+分析配置+实验性警示）/
  事件时间轴（筛选/跳转/证据/手动事件/候选）/剪辑工程列表与编辑器（片段增删改排、
  数字输入边界、字幕、音量、保存版本、提交渲染）/任务中心（阶段/真实进度/失败原因/取消重试）/
  成片页（播放/下载）。
- 浏览器 GUI 冒烟：注册登录跳转、五个页面真实 API 数据渲染通过。

### 交付物
- `.env.example`（无真实密钥）、`deploy/docker-compose.yml`（含可选 mq profile）、
  三个组件 Dockerfile（后端/Worker 非 root）、OpenAPI（springdoc /swagger-ui）、
  合成夹具生成脚本、完整 README 与 docs/*（8 篇）。
- git 提交 5 个里程碑（详见 git log）。

## 当前任务
- 已完成最终多轮回归（50 后端 + 15 Worker + 31/13/9 三套 E2E）并推送远端。
- 管理后台（第十九节页面11）：`/api/admin/stats|users|adapters` +
  前端 `/admin` 页（仅 ADMIN 可见），含实测统计（排队耗时/重试数/渲染成功率/候选决策比/存储占用）、
  配额调整（低于已用自动钳制）、适配器生命周期管理（VERIFIED 必须附真实游戏评测凭据）。
- 分析结果复用键（第十四节）：同 media 内容 + 适配器版本 + 合并配置哈希 + 算法版本
  的已完成 run 直接复用，不重复执行 OCR；配置不同才创建新 run。
- 初始管理员账号通过 `HIGHLIGHT_HUB_ADMIN_INITIAL_PASSWORD` 环境变量注入
  （仅启动时无管理员才创建，密码不硬编码不入日志）。
- 已修复：集成测试基类补充 `@ActiveProfiles("test")`——此前测试套件误指向开发库
  `highlight_hub` 并在其上 TRUNCATE（测试数据隔离缺陷）。修复后验证：
  测试写入 `highlight_hub_test`，开发库数据保持独立；50 个后端用例重跑全绿。
- 后续：有 Docker 的环境实测 Compose；真实游戏样本到位后适配第一款游戏。

## 尚未实现（诚实清单）
- 事件修订的离线评估管道（用户修正已留痕，未接评估任务）。
- 全局分析/渲染并发的精细化配置面板（配置项已存在，管理后台展示统计）。
- 多源素材工程、多视角对齐、规则比较（阶段4，未开始）。

## 阻塞
- 开发机无 Docker：`docker` 命令不存在。影响：
  1) docker-compose 未实测容器启动（文件已交付）；
  2) Testcontainers 未用于集成测试（已用本地 MySQL 8 真库 + 独立 test schema 替代，
     迁移与 SQL 全部真实执行）。
  其余功能不受影响。
- git 推送到 https://github.com/365266430 需要凭据；远端已配置 origin，
  推送若因凭据失败将如实记录。

## 已执行测试（汇总见 docs/test-report.md）
- backend `mvn test`：55/55 通过（真实 MySQL，独立测试库）
- media-worker `pytest`：15/15 通过（合成夹具 + 真实 FFmpeg/OCR）
- `scripts/run_e2e.py`：31/31 通过（两轮）
- `scripts/run_e2e_phase2.py`：13/13 通过（两轮）
- `scripts/run_e2e_phase3.py`：9/9 通过（SSE + CROP/打码）
- `scripts/run_e2e_admin.py`：9/9 通过（管理面 + 访问控制）
- 前端 GUI 冒烟：通过

## 下一条具体操作
1. 全量回归（backend + worker + 两个 E2E 脚本）确认绿。
2. `git push origin main`（若凭据缺失则记录阻塞并保留本地提交）。
3. 有 Docker 的环境中：`cd deploy && docker compose up --build` 实测并回填文档。
