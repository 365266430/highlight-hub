你是一名负责完整交付的高级 Java 全栈工程师。请实际开发下面定义的项目，不要只给设计方案。

项目名称：开黑剪辑台 HighlightHub
项目定位：面向普通游戏玩家的录像事件定位、高光候选筛选与轻量剪辑平台。

请把这份任务书当作产品需求、技术约束和验收标准。
先检查工作目录与现有代码，再按阶段实现。
不要随意扩大范围，不要用假数据冒充已完成能力。

==================================================
一、产品目标与核心原则
==================================================

用户录制了一段较长的游戏视频，希望快速找到值得保留的片段，稍作编辑后导出分享。

核心流程：

上传录像
→ 生成预览
→ 自动或手动标记事件
→ 生成候选片段
→ 用户确认与编辑
→ 保存剪辑工程
→ 异步渲染
→ 下载成片

重要定义：

1. “事件识别”是判断画面中发生了什么。
   例如击败提示出现、比分变化、回合结束。

2. “高光候选”是根据事件与规则推荐一个时间片段。
   不代表系统理解了操作水平。

3. “最终高光”由用户确认。
   不宣称系统能可靠识别所有精彩、搞笑或高水平操作。

4. 不同游戏需要独立适配。
   通用上传、任务、编辑、渲染只实现一次。
   游戏识别逻辑与规则不得散落在通用业务代码中。

5. 第一版不训练大模型。
   优先采用指定区域 OCR、图像模板、时序规则和人工修正。

6. Spring Boot 是业务权威服务。
   Python 只负责媒体探测、分析和渲染等专项执行。
   Python 不直接修改业务数据库。

7. 所有游戏都能使用手动标记与剪辑。
   只有验证通过的适配器才能显示“支持自动识别”。

==================================================
二、第一版范围与暂缓事项
==================================================

第一版必须完成：

- 用户登录。
- 视频分片上传与续传。
- 视频探测、预览、缩略图。
- 手动事件标记。
- 通用 OCR 区域识别适配器。
- 可配置的候选生成规则。
- 时间轴与片段编辑。
- 剪辑工程版本。
- 横屏成片导出。
- 任务进度、失败重试、取消。
- 私有素材权限。
- 自动化测试与真实运行文档。

第二阶段完成：

- 一款明确指定游戏的真实适配与评测。
- 更多事件类型。
- 消息队列和 Outbox。
- 异步批量渲染。
- 竖屏固定裁剪、补边。
- 固定区域打码。
- 分享链接。
- 资源配额与清理。

暂缓：
- 所有游戏通用识别。
- 实时直播剪辑。
- 任意游戏操作水平评分。
- 自动判断所有搞笑内容。
- 多机位自动同步。
- 智能人物追踪运镜。
- 商业音乐库。
- 读取游戏进程内存。
- 游戏自动操作。
- 全局快捷键录制助手。
- 移动端原生 App。
- 大型微服务拆分。

用户尚未指定第一款游戏：
不要因此停止通用功能开发。
先实现适配器接口、模板编辑器与真实 OCR 链路。
使用合成测试视频验证链路时，必须清楚标记“合成测试”。
不能因此宣称已支持某款真实游戏。
真实游戏适配必须等待明确的游戏名称、界面布局和有权使用的录像样本。
不具备这些条件时，把该项标为待验证，不虚构完成。

==================================================
三、技术栈与项目结构
==================================================

建议采用：

后端：
- Java 21
- Spring Boot 3.x 兼容稳定版本
- Maven
- Spring Security
- MyBatis-Plus
- MySQL 8
- Flyway
- Redis
- RabbitMQ：可靠事件阶段加入
- OpenAPI
- Spring Boot Actuator

前端：
- Vue 3
- TypeScript
- Vite
- Pinia
- Vue Router
- Element Plus
- 原生 HTMLVideoElement 作为首版播放器基础

Python：
- FastAPI
- FFmpeg / FFprobe
- OpenCV
- 一种实际验证可用的 OCR 实现
- pytest

测试：
- JUnit 5
- Spring Boot Test
- Testcontainers，用于必要的数据库和消息集成测试

部署：
- Docker Compose
- 本地文件存储作为开发默认实现
- 通过 StorageService 保留 S3 兼容对象存储实现

版本要求：
检查运行环境和依赖兼容性。
固定具体版本，不用无界 latest。
不要为追求最新版本引入无关迁移。
README 写清实际使用版本。

目录：

highlight-hub/
  backend/
  frontend/
  media-worker/
  deploy/
  docs/
  test-fixtures/
  README.md

backend 按业务组织：

auth
user
upload
media
adapter
analysis
highlight
project
render
task
storage
share
outbox
admin
common

采用模块化单体。
禁止一开始创建十几个微服务。
不要为了结构美观创建大量空接口。

==================================================
四、端到端示例
==================================================

用户上传一段 40 分钟录像。

系统：
1. 创建上传会话。
2. 接收分片。
3. 核验并合并。
4. 使用 FFprobe 获取视频信息。
5. 生成浏览器可播放预览和缩略图。
6. 用户选择“手动模式”或已验证适配器。
7. 自动分析生成事件时间轴。
8. 根据规则生成候选片段。
9. 用户选中三个片段，调整边界和字幕。
10. 保存剪辑工程版本。
11. 提交渲染。
12. 渲染成功，下载 MP4。

断网、重复点击、Worker 崩溃、重复回调都不能导致：
- 素材归属串号。
- 无限重复渲染。
- 成功结果被旧回调覆盖。
- 取消任务重新显示成功。
- 临时文件无上限堆积。

==================================================
五、用户、认证与资源归属
==================================================

角色：
USER
ADMIN

普通用户只访问自己的：
- 原始录像。
- 上传会话。
- 分析任务。
- 事件。
- 剪辑工程。
- 渲染结果。

管理员：
- 管理适配器模板。
- 查看不含私密原文的运行状态。
- 重试失败清理任务。
- 配置配额。
- 不在普通后台默认浏览所有用户录像。

所有资源接口必须做归属校验。
不能只在列表页过滤，而让按 ID 访问绕过权限。

认证建议：
使用 Spring Security + HttpOnly 会话 Cookie。
同源部署或通过前端开发代理访问 API。
Cookie 会话采用 CSRF 防护。
生产启用 Secure Cookie。

禁止：
- 明文密码。
- 写死生产管理员密码。
- 前端传 userId 就决定归属。
- 在日志里记录令牌或私密分享密钥。

==================================================
六、视频上传模块
==================================================

采用上传会话 + 分片。

上传会话包含：
- uploadId
- userId
- originalFilename，仅作展示
- declaredSize
- chunkSize
- expectedChunkCount
- status
- expiresAt
- reservedBytes
- createdAt
- updatedAt

分片包含：
- uploadId
- chunkIndex
- actualSize
- checksum
- storageKey
- createdAt

唯一约束：
uploadId + chunkIndex。

流程：
1. 创建会话时检查单文件上限和用户配额。
2. 服务端决定合法分片大小和总数。
3. 查询会话返回已接收分片序号。
4. 前端只补传缺失分片。
5. 同一分片重复上传，相同内容返回已接收。
6. 同一分片序号出现不同内容，返回冲突。
7. 完成请求验证数量、顺序、总大小和校验信息。
8. 服务端流式合并，不能把整段视频读入内存。
9. 服务端计算最终内容哈希。
10. 合并成功后创建媒体记录并提交探测任务。

要求：
- 不相信客户端声称的哈希和大小。
- 限制分片数量、单片大小和请求大小。
- filename 不参与真实路径拼接。
- 所有存储路径由服务端生成。
- complete 重复调用不能产生多个媒体资源。
- 合并过程有状态和超时恢复方案。
- 配额预留需要并发保护，避免多会话绕过配额。
- 失败、取消、过期后释放相应预留。
- 上传临时分片按 TTL 清理。

第一版只做同一用户范围的内容去重。
不通过“这个文件已经存在”泄露其他用户是否上传过某视频。

==================================================
七、媒体探测、预览与缩略图
==================================================

FFprobe 输出至少解析：
- durationMs
- width
- height
- videoCodec
- audioCodec
- frameRate
- variableFrameRate 标识或说明
- fileSize
- rotation
- audioStreamCount

统一时间基准：
使用视频展示时间对应的毫秒。
分析与剪辑都使用同一时间轴。
不能在可变帧率视频中简单用 frameIndex / guessedFps 替代时间戳。

输入不合法时：
返回明确失败原因。
不能把任意文件仅凭扩展名当视频。

预览：
- 生成适合浏览器播放的代理视频。
- 分析与最终渲染保留原始素材关联。
- 原始视频与预览时间轴须有明确映射，首版保持等速和同起点。
- 支持 HTTP Range，或者使用能支持 Range 的授权存储地址。
- 首版不强制实现 HLS。

缩略图：
- 按固定时间间隔生成。
- 可以生成雪碧图及索引。
- 记录生成参数与版本。
- 调整剪辑时不重复生成同样的缩略图。

媒体状态：
UPLOADING
PROBING
READY
FAILED
DELETING
DELETED

分析和渲染状态分别存在任务里。
不要用一个 media.status 表达所有流程，造成相互覆盖。

==================================================
八、游戏适配器设计
==================================================

每个适配器有：
- adapterId
- adapterVersion
- gameKey
- displayName
- supportedLayouts
- supportedResolutions 或缩放条件
- supportedLanguages
- supportedEventTypes
- templateVersion
- status：DRAFT / EXPERIMENTAL / VERIFIED / DISABLED

原始版本发布后不可原地修改。
新调整创建新版本。
分析任务绑定具体版本，保证结果可解释。

Python 侧建议接口：

probe(mediaInfo, sampleFrames, config)
  判断输入是否适配，返回理由和置信说明。

analyze(inputVideo, config, progressCallback, cancelToken)
  产生结构化事件与证据。

Java 负责：
- 适配器版本管理。
- 用户选择。
- 参数校验。
- 分析任务与结果入库。
- 兼容性失败提示。

通用适配器不得写死某款游戏名称。
游戏特定规则放入独立包或模板。

首版 OCR 区域模板支持：
- 在视频截图上框选 ROI。
- ROI 使用相对坐标 0~1。
- 指定预处理方式。
- 指定采样频率。
- 指定文字模式或数字变化模式。
- 指定多帧确认阈值。
- 指定事件去重时间窗。
- 指定支持的界面条件。

相对坐标缩放不代表自动支持所有 UI 布局。
不同宽高比、HUD 设置和语言仍需校准。

不匹配时：
提示重新校准或使用手动模式。
不能悄悄用错误模板继续给出可信结果。

==================================================
九、事件模型与识别
==================================================

事件字段：
- id
- analysisRunId
- mediaId
- type
- startMs
- endMs
- actor，可为空
- target，可为空
- confidence，可为空
- source：AUTO / MANUAL
- status：ACTIVE / REJECTED
- evidenceAssetId，可为空
- attributes，受控 JSON
- adapterVersion
- createdAt

事件类型先提供：
- ELIMINATION_NOTICE
- SCORE_CHANGE
- ROUND_START
- ROUND_END
- MANUAL_MARKER

注意：
ELIMINATION_NOTICE 只表示画面中观察到击败提示。
不知道归属时，不能自动推断为当前玩家完成击败。
“连续击败”规则如果需要本人事件，必须验证 actor 或游戏适配器确实能识别归属。
否则只能标记“击败提示密集片段”。

confidence 是事件识别信息。
没有校准时，不对外解释成准确概率。
不把它当成精彩程度。

时序处理：
- 多帧一致性。
- 短暂 OCR 抖动过滤。
- 同一公告在多帧出现时合并。
- 时间窗去重。
- 数字变化合法性检查。
- 低可信结果标记待确认。

用户可：
- 添加手动事件。
- 修改时间。
- 修改事件类型。
- 拒绝自动事件。
- 查看证据截图。
- 不得直接覆盖原始自动分析记录而丢失来源。

事件修订要保留必要版本或操作记录。
用户修正可用于离线评估，不自动宣称模型已经在线学习。

==================================================
十、高光候选规则引擎
==================================================

规则和游戏适配器分离。
同一事件集合可以运行不同规则。

规则参数示例：
- eventType
- windowMs
- minimumCount
- paddingBeforeMs
- paddingAfterMs
- mergeGapMs
- maxSegmentDurationMs
- actorConstraint，可选

例子：
20 秒内至少出现 3 次满足条件的事件；
候选开始向前扩展 12 秒；
候选结束向后扩展 8 秒。

这些阈值是默认示意值，不当作通用最佳参数。
在配置和文档中说明可调。

候选字段：
- id
- analysisRunId
- ruleRunId
- ruleVersion
- startMs
- endMs
- score，可选，明确是排序分
- reasonCode
- reasonText
- eventIds
- status：PENDING / ACCEPTED / REJECTED

reasonText 示例：
“18 秒内检测到 3 次击败提示。”

不能输出没有证据的：
“你完成了顶级操作。”
“这是一次逆风翻盘。”

时间处理：
- 开始不能小于 0。
- 结束不能超过视频时长。
- 开始必须小于结束。
- 重叠候选按规则合并。
- 不能因为连续合并变成几分钟的失控长片。
- 达到最大长度后按明确策略拆分或停止合并。
- 保留源事件关联。

生成器可用 Java 实现，突出后端规则能力。
Python 只负责事件提取也可以。

修改候选规则不应重新执行视频 OCR。
重新生成候选创建新 ruleRun。
旧剪辑工程不被自动改动。

==================================================
十一、剪辑工程与轻量编辑
==================================================

首版一个工程只使用一段源录像。
多个片段可以来自同一源录像的不同时段。
跨多个源文件后续扩展。

功能：
- 从候选创建工程。
- 添加任意手动片段。
- 调整起止时间。
- 片段排序。
- 删除、复制片段。
- 简单字幕。
- 源音频音量。
- 横屏导出。
- 保存草稿与历史版本。

不做完整专业编辑器。

工程：
- projectId
- ownerId
- mediaId
- name
- latestRevision
- status
- createdAt
- updatedAt

工程版本：
- projectId
- revision
- schemaVersion
- editDecisionJson
- createdAt

示例 EDL：

{
  "schemaVersion": 1,
  "sourceMediaId": "media-001",
  "segments": [
    {
      "id": "segment-1",
      "sourceInMs": 125000,
      "sourceOutMs": 153000,
      "caption": "这波配合成功了",
      "sourceVolume": 1.0
    }
  ],
  "output": {
    "aspectMode": "SOURCE",
    "width": 1920,
    "height": 1080,
    "fps": 30
  }
}

时间规范：
片段采用 [sourceInMs, sourceOutMs)。
字幕首版覆盖对应片段，避免同时引入复杂字幕时间轴。
如果扩展字幕时间，必须明确是源视频时间还是片段局部时间。

保存时提交 expectedRevision。
并发冲突返回 409。
成功生成新版本。
渲染绑定不可变的 projectRevision，不读取执行时的 latestRevision。

后端校验：
- 素材归属。
- 时间边界。
- 总片段数。
- 总导出时长。
- 输出分辨率上限。
- 字幕长度。
- 音量范围。
- 支持的枚举。
- 禁止客户端传任意 FFmpeg 参数。

==================================================
十二、渲染与媒体安全
==================================================

渲染输入：
- projectId
- projectRevision
- renderPresetVersion

由 Java 根据权威 EDL 生成受控任务。
Python 渲染器将 EDL 转换为命令参数。

要求：
- 使用 subprocess 参数数组，shell=False。
- 不拼接可执行 shell 字符串。
- 用户文件名不作为真实路径。
- 不接受用户指定任意本地输入路径。
- 不允许 Worker 根据用户文本访问任意网络 URL。
- Worker 只读取分配给该任务的素材与目录。
- 使用非 root 用户运行。
- 限制 CPU、内存、运行时间和临时磁盘。
- 每任务独立临时目录。

字幕：
- 正确处理特殊字符和换行。
- 使用受控字幕文件或安全参数构造。
- 使用项目内可分发且许可明确的字体。
- 不把文本直接拼进任意滤镜表达式。

输出：
- 首版 MP4。
- 明确视频、音频编码与浏览器兼容性。
- 验证实际 FFmpeg 构建能力。
- 在文档列出依赖和许可信息。

精确裁剪：
默认解码后重编码，避免把关键帧快速截取冒充帧级精度。
确认时间戳归零、音画同步、无音轨输入的处理。
不假设所有源文件编码、帧率和旋转方向一致。

进度：
使用 FFmpeg 可解析进度输出。
结合预计输出时长计算近似百分比。
不能使用定时器假装真实进度。
最终文件完成并通过探测校验后才能标记成功。

结果验证：
- 文件存在且非空。
- 可以探测。
- 时长在合理容差内。
- 包含预期视频流。
- 有音源时按配置处理音轨。
- 生成结果校验值。

第二阶段：
增加固定裁剪、补边、静态区域打码。
明确裁剪坐标属于源画面。
不宣称会自动追踪所有人物。

==================================================
十三、任务系统
==================================================

任务类型：
PROBE
PREVIEW
THUMBNAIL
ANALYZE
GENERATE_CANDIDATES
RENDER
CLEANUP

状态：
QUEUED
RUNNING
CANCEL_REQUESTED
CANCELLED
SUCCEEDED
FAILED

任务字段：
- id
- ownerId
- type
- inputRef
- inputVersion
- status
- attempt
- maxAttempts
- attemptToken
- leaseUntil
- progress
- phase
- errorCode
- errorMessage
- outputRef
- cancelRequestedAt
- createdAt
- startedAt
- finishedAt

Java 为任务状态权威。

首版：
使用数据库任务队列 + Worker 主动领取。
Python 通过内部 HTTP 接口领取任务、续租、更新进度、提交结果。
不要求首版依赖 RabbitMQ。

领取：
- 原子领取。
- 发放唯一 attemptToken。
- 有限租约。
- Worker 定期心跳。
- 同一 attemptToken 才能更新对应执行尝试。
- 旧 Worker 回调不得覆盖新尝试。

重试：
- 明确区分可重试基础设施失败和不可重试输入错误。
- 设置最大次数和退避。
- 首版按失败阶段重跑，不声称 FFmpeg 可以任意断点续算。
- 已成功阶段结果保留，避免重跑整个链路。

取消：
- QUEUED 可以直接取消。
- RUNNING 先变成 CANCEL_REQUESTED。
- Worker 检测取消并停止整个子进程组。
- 确认停止后变为 CANCELLED。
- 迟到成功回调不能把已取消任务改成成功。
- 临时结果进入清理。
- 状态竞态使用数据库条件更新解决。

完成：
状态和结果关联要原子提交。
结果文件先写任务尝试专属路径。
只有当前有效尝试成功后才能成为正式产物。
过期尝试输出只能清理，不能公开。

资源控制：
- 全局分析并发数。
- 全局渲染并发数。
- 每用户待处理任务上限。
- 单任务时长、输入大小、输出大小上限。
- 开发默认保守，例如渲染并发 1，允许配置。
- 不为了展示分布式而过早扩容。

==================================================
十四、幂等与重复计算
==================================================

上传完成、创建分析、提交渲染支持幂等。

客户端可提交 Idempotency-Key。
服务端绑定：
userId + operation + key + requestHash。

同 key 同请求：
返回原资源。

同 key 不同请求：
返回 409。

分析结果复用键至少考虑：
- 原素材内容哈希
- adapterVersion
- templateVersion
- 分析参数哈希
- 算法版本

渲染结果复用键至少考虑：
- 原素材内容哈希
- 不可变 EDL
- renderPresetVersion
- rendererVersion

不能只按文件名去重。
不能把修改后的字幕映射到旧成片。
不能跨用户泄露私有结果。

==================================================
十五、存储、下载与删除
==================================================

定义 StorageService：
- put
- open/readRange
- stat
- delete
- createAuthorizedDownload，按实现选择

第一版默认本地存储。
物理路径只保留在服务端，不直接返回给前端。
浏览器通过鉴权接口或短期授权地址访问。

资源类型：
ORIGINAL
PREVIEW
THUMBNAIL
EVIDENCE
RENDER_OUTPUT
TEMPORARY

资产字段：
- id
- ownerId
- mediaId，可为空
- type
- storageKey
- size
- checksum
- status
- createdAt
- expiresAt，可为空

私有录像默认不公开。

第二阶段分享：
- 随机高熵分享令牌。
- 过期时间。
- 撤销。
- 只分享选定成片，不自动共享原始录像。
- 数据库存令牌摘要。
- 不在日志记录完整令牌。

删除：
- 先确认权限与依赖。
- 正在使用的素材不能无提示强删。
- 可以提供“取消任务并删除”的显式操作。
- 逻辑删除后安排清理任务。
- 删除对象失败可重试。
- 成功清理后再修正相应空间统计。
- 不用任意客户端路径执行递归删除。
- 定期处理过期分片、失败任务临时目录和失效产物。

==================================================
十六、消息、进度推送与 Outbox
==================================================

第一版：
REST 轮询任务状态。
先确保正确性，不强制上 WebSocket。

第二阶段：
SSE 推送任务进度与完成事件。
如果使用 WebSocket，必须说明相对 SSE 的必要性。
重连后以 REST 当前状态为准。
事件不能发给其他用户。

RabbitMQ 与 Outbox：
用于任务完成后的通知、后续阶段触发和清理等可靠事件。
避免数据库更新成功但事件丢失。

同一业务事务：
更新状态 + 插入 outbox。

事件至少包含：
- eventId
- eventType
- aggregateId
- aggregateVersion
- occurredAt
- payload，不包含不必要的文件路径或密钥

消费者：
- 按 eventId + consumerName 幂等。
- 有限重试。
- 失败队列或失败记录。
- 旧版本事件不能覆盖新状态。
- 明确消息确认时机。

不要同时保留两套互相竞争的任务状态权威。
数据库仍是任务状态来源。
RabbitMQ 不是唯一的任务真相。

==================================================
十七、数据库表
==================================================

至少包括：

users
upload_sessions
upload_chunks
media
media_assets
adapter_definitions
adapter_versions
analysis_runs
video_events
event_revisions
highlight_rule_versions
highlight_runs
highlight_candidates
editing_projects
editing_project_revisions
render_jobs
tasks
task_attempts
idempotency_records
share_links
outbox_events
consumed_events

可以合并明显冗余表，但需要说明原因。
不要为了凑表数制造不必要的结构。

要求：
- Flyway 迁移。
- 数据库唯一约束保证关键幂等。
- 任务领取扫描、用户素材列表、事件时间轴有合适索引。
- 使用 UTC 存储业务时间。
- 视频位置统一用毫秒。
- JSON 只用于可变参数，不把所有关系塞入 JSON。
- 不直接返回 ORM 实体。

==================================================
十八、API 设计
==================================================

统一错误：
code
message
requestId
details，仅包含安全的字段错误。

状态码：
400 参数错误
401 未登录
403 无权限
404 不存在
409 状态或版本冲突
413 文件过大
429 配额或频率限制
503 服务暂不可用

至少实现：

认证：
POST /api/auth/register
POST /api/auth/login
POST /api/auth/logout
GET /api/me

上传：
POST /api/uploads
GET /api/uploads/{id}
PUT /api/uploads/{id}/chunks/{index}
POST /api/uploads/{id}/complete
DELETE /api/uploads/{id}

素材：
GET /api/media
GET /api/media/{id}
GET /api/media/{id}/preview
GET /api/media/{id}/thumbnails
DELETE /api/media/{id}

适配器：
GET /api/adapters
GET /api/adapters/{id}/versions

分析：
POST /api/media/{id}/analyses
GET /api/analyses/{id}
GET /api/analyses/{id}/events

手动事件：
POST /api/media/{id}/events
PATCH /api/events/{id}
POST /api/events/{id}/reject

候选：
POST /api/analyses/{id}/highlight-runs
GET /api/highlight-runs/{id}/candidates
PATCH /api/highlight-candidates/{id}

工程：
POST /api/projects
GET /api/projects
GET /api/projects/{id}
PUT /api/projects/{id}
GET /api/projects/{id}/revisions
GET /api/projects/{id}/revisions/{revision}

渲染：
POST /api/projects/{id}/renders
GET /api/renders/{id}
GET /api/renders/{id}/download

任务：
GET /api/tasks
GET /api/tasks/{id}
POST /api/tasks/{id}/cancel
POST /api/tasks/{id}/retry

分享：
POST /api/renders/{id}/shares
DELETE /api/shares/{id}

内部 Worker：
POST /internal/tasks/claim
POST /internal/tasks/{id}/heartbeat
POST /internal/tasks/{id}/progress
POST /internal/tasks/{id}/complete
POST /internal/tasks/{id}/fail
GET /internal/tasks/{id}/cancellation

内部接口必须有独立服务鉴权。
attemptToken 不是服务鉴权的替代品。
上传进度与处理进度分开显示。

==================================================
十九、前端页面与体验
==================================================

页面：

1. 登录注册。
2. 工作台：最近素材、处理中任务、最近工程。
3. 上传页：分片进度、续传、取消。
4. 素材详情：预览、媒体信息、分析选择。
5. 适配器校准：截图框选 ROI、测试识别结果。
6. 事件时间轴：事件类型筛选、点击跳转、证据预览。
7. 高光候选列表：原因、保留、排除、修改边界。
8. 剪辑编辑页：播放器、片段列表、起止输入、字幕、排序。
9. 任务中心：阶段、真实进度、失败原因、取消重试。
10. 成片页：预览、下载、第二阶段分享。
11. 管理页：适配器、配额、任务统计。

交互要求：
- 适合桌面操作，移动端能查看和上传。
- 视觉上像轻量创作工具，不像纯后台表格。
- 所有关键数据来自真实 API。
- 加载、空数据、失败、取消状态完整。
- 未支持游戏明确显示“手动模式”。
- 实验性适配器有明显标记。
- 不能显示假的成功率或活跃用户数量。
- 用户调整边界时提供数字输入，不能只依赖拖拽。
- 预览可以近似播放，最终精确裁剪以渲染结果为准。
- 页面刷新后能恢复任务和工程状态。

==================================================
二十、测试要求
==================================================

重点测试真实风险，不只是返回 200。

上传：
1. 重复分片。
2. 分片内容冲突。
3. 缺片完成。
4. 超大请求。
5. 断点续传。
6. complete 并发调用。
7. 用户访问他人上传会话。
8. 路径穿越文件名。
9. 配额并发。
10. 过期会话清理。

任务：
1. 两个 Worker 争抢同一任务。
2. 心跳超时。
3. 旧 attemptToken 回调。
4. 重复成功回调。
5. 成功与取消竞态。
6. Worker 崩溃后重试。
7. 重试耗尽。
8. 重复消息。
9. 乱序消息。
10. 成功阶段复用。

事件与候选：
1. 同提示多帧去重。
2. OCR 短暂抖动。
3. 无归属提示不得当本人击败。
4. 候选边界裁到视频范围。
5. 重叠合并。
6. 合并不超过最大时长策略。
7. 手工拒绝后规则结果正确处理。
8. 相同输入与规则产生可重复结果。
9. 模板版本变更不覆盖历史结果。

工程与渲染：
1. expectedRevision 冲突。
2. 渲染绑定指定版本。
3. 非法时间范围。
4. 无音轨视频。
5. 有旋转信息视频。
6. 可变帧率样本或明确限制说明。
7. 字幕包含特殊字符。
8. 输出时长正确。
9. 取消后进程与临时产物清理。
10. 用户不能读取他人成片。
11. 私有存储路径不泄露。
12. 下载 Range 行为。

媒体测试数据：
可以用 FFmpeg 生成短合成视频，包含计时、颜色和提示文本。
必须标为测试夹具。
用它验证时间裁剪、OCR 链路和任务恢复。
真实游戏准确率必须用真实授权录像与人工标注评估。
不能把合成测试指标冒充真实游戏指标。

==================================================
二十一、性能与观测
==================================================

记录：
- 上传吞吐与失败次数。
- 任务排队时间。
- 各阶段执行时间。
- 视频处理实时率：处理耗时 / 源视频时长。
- Worker 心跳状态。
- CPU、内存、临时磁盘用量。
- 重试次数。
- 渲染成功率。
- 候选数量。
- 用户保留/拒绝候选比例。

算法评估：
- 事件 Precision / Recall。
- 时间定位误差。
- 高光候选覆盖率。
- 每小时无用候选数量。
- 人工找到并导出片段所需时间。

所有数字都必须实际测量。
没有真实样本时，明确“尚未评测”。
不得编造百万用户、QPS 或提升百分比。

日志：
包含 requestId、taskId、attempt。
不写登录令牌、完整分享链接和用户敏感内容。

==================================================
二十二、部署与交付
==================================================

必须交付：

- 完整 Java 后端。
- Vue 前端。
- Python Worker。
- Flyway 迁移。
- .env.example，无真实密钥。
- Docker Compose。
- OpenAPI 文档。
- 自动化测试。
- 合成测试视频生成脚本。
- README。
- docs/architecture.md。
- docs/adapter-development.md。
- docs/task-state-machine.md。
- docs/storage-and-cleanup.md。
- docs/test-report.md。
- docs/demo-script.md。
- docs/implementation-status.md。

开发部署：
- MySQL
- Redis
- backend
- frontend
- media-worker
- RabbitMQ 可选 profile
- 对象存储可选 profile

本地模式要明确：
Java 与 Python 怎样共享受控媒体目录。
容器内外路径怎样映射。
不要把宿主机绝对路径直接传给无法访问它的容器。
存储引用使用逻辑 key，通过服务端配置解析。

默认开发配额：
采用保守可调值，并在 README 写清。
不要让首次运行直接接受无限时长、无限大小视频。

==================================================
二十三、实施顺序
==================================================

阶段 0：检查与初始化
- 检查目录、已有代码、Java/Python/Node/Docker/FFmpeg。
- 固定兼容版本。
- 建立目录和状态文档。
- 简短说明后立即写代码。

阶段 1：不依赖算法的完整闭环
- 认证。
- 分片上传。
- 探测与预览。
- 手动事件。
- 工程编辑与版本。
- 数据库任务队列。
- Python 渲染。
- 成片下载。
- 权限与核心故障测试。

验收：
上传短视频，手动选两段，添加字幕，导出可播放文件。
断网能续传。
重复提交不重复创建。
其他用户不能读取。
修改工程不会改变已提交渲染的版本。

阶段 2：自动定位
- 适配器接口。
- ROI 配置。
- 真实 OCR。
- 时序事件去重。
- Java 候选规则引擎。
- 候选编辑。
- 合成夹具测试。
- 真实游戏样本到位后适配第一款游戏。

验收：
真实输出事件时间轴及候选原因。
不支持的输入正确退回手动模式。
未完成真实游戏验证时不得标 VERIFIED。

阶段 3：工程增强
- 任务租约、恢复、取消完整验证。
- Outbox + RabbitMQ。
- SSE。
- 配额和存储清理。
- 分享。
- 固定裁剪、补边、打码。
- 性能与故障报告。

阶段 4：可选扩展
- 第二款游戏适配。
- 规则比较。
- 多源素材工程。
- 多视角时间对齐。
只有前三阶段可靠后才开始。

==================================================
二十四、执行纪律
==================================================

每阶段：
1. 实现真实代码。
2. 执行对应测试。
3. 修复失败。
4. 更新状态文档。
5. 简短报告证据。
6. 无阻塞时继续。

不要频繁询问命名、颜色、目录等低风险选择。
根据任务书做合理决定并记录。

如果 Docker、网络或依赖安装受阻：
明确错误与影响。
继续不受影响的实现。
不要声称未执行的测试通过。
不要删测试来制造成功。
不要把真实处理改成随机返回成功。

上下文不足前：
更新 implementation-status.md：
- 已完成。
- 当前任务。
- 尚未实现。
- 阻塞。
- 已执行测试。
- 下一条具体操作。

恢复后先读状态文档，不重建项目。

简历归属：
文档明确哪些来自 FFmpeg、OCR、OpenCV 等组件。
自己的贡献聚焦上传协议、任务系统、适配器设计、候选规则、工程版本和验证。
不得把调用库的基础能力写成自研编码器或自研识别模型。

现在从阶段 0 开始，并优先完成阶段 1 的真实纵向闭环。