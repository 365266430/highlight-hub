# 架构

## 组件职责（Spring Boot 为业务权威）

```
┌──────────┐   /api (会话Cookie+CSRF)   ┌─────────────────┐
│  Vue3 前端 │ ◄──────────────────────► │  Spring Boot 后端  │◄──┐
└──────────┘                            │  - 认证/归属校验    │   │ 同一事务:
                                        │  - 上传会话/合并    │   │ 状态更新+
                                        │  - 工程/版本/渲染   │   │ outbox插入
                                        │  - 任务状态机(权威)  │   │
                                        └───┬─────────▲─────┘   │
                                /internal/* │         │ 心跳/进度 │
                                X-Worker-Token ▼        │ 完成/失败 │
                                        ┌─────────────────┐    │
                                        │  Python Worker   │────┘
                                        │  FFprobe/FFmpeg  │
                                        │  OpenCV/RapidOCR │
                                        └────────┬────────┘
                                                 │ 逻辑key读写
                                        ┌────────▼────────┐
                                        │ 共享受控存储 /data │
                                        └─────────────────┘
```

- **Worker 不直接写业务数据库**。它领取任务、执行、回报结构化结果；
  由 Java 在“任务完成事务”里把结果落到业务表（media、video_events、render_jobs…）。
- **任务状态权威是数据库**。Worker 与任何消息组件都不是任务真相来源。
- **文件名不参与真实路径拼接**：存储路径全部由服务端生成的逻辑 key
  （如 `original/{mediaId}/source`、`render/{renderJobId}/output.mp4`）解析。

## 模块化单体（backend 包结构）

auth / user / upload / media / adapter / analysis / highlight / project /
render / task / storage / share / outbox / common。没有为结构美观创建空接口；
模块间通过服务与 Spring 事件（任务终态）协作。

## 关键机制

### 任务系统
- 数据库任务队列 + Worker 主动领取。领取使用
  `SELECT ... FOR UPDATE SKIP LOCKED` + 条件 UPDATE（双保险）。
- 每次 attempt 发放唯一 `attemptToken`；心跳/进度/完成都按 token + 状态条件更新，
  旧 Worker 的迟到回调必然 409。
- 取消：QUEUED 直接 CANCELLED；RUNNING → CANCEL_REQUESTED → Worker 查询取消位、
  杀掉子进程树后回报；迟到成功回报按已取消记录，不复活任务。
- 详见 [task-state-machine.md](task-state-machine.md)。

### 上传协议
- 会话 + 分片：创建会话时按声明大小原子预留配额（`UPDATE ... WHERE used+bytes<=quota`），
  不可绕过；分片按 (uploadId, chunkIndex) 唯一；同内容重复上传返回已接收，
  同序号不同内容返回 409；complete 校验数量/顺序/总大小后流式合并（不整段入内存），
  服务端计算最终哈希；同用户内容去重（不泄露他人上传）；complete 幂等。
- 合并崩溃恢复：COMPLETING 状态超时由清扫器重置回 UPLOADING。

### 渲染
- Java 校验 EDL（白名单字段/边界/时长/分辨率/音量/字幕长度）后按不可变
  projectRevision 生成受控任务；Worker 把 EDL 转为 ffmpeg 参数数组（shell=False）。
- 字幕写入临时文本文件 + cwd 相对引用（文本不进滤镜表达式；Windows 盘符不进滤镜图）。
- 帧级精度：解码后 trim + concat 再编码，不用关键帧快速截取冒充精度。
- 进度：解析 ffmpeg `-progress pipe:1` 的 out_time_ms 结合预计输出时长计算百分比，
  轮询点是取消检查点。输出先经探测校验（时长容差/流存在/非空）才标记成功。

### 幂等
- 上传完成 / 创建分析 / 提交渲染支持 `Idempotency-Key`
  （userId + operation + key + requestHash；同 key 同请求回放，异请求 409）。
- 分析结果复用键：内容哈希 + adapterVersion + 模板版本 + 参数哈希 + 算法版本（设计约定，
  参数哈希落在 analysis_runs.params_hash）。
- 渲染复用键：内容哈希 + 不可变 EDL + presetVersion + rendererVersion。

### Outbox（阶段3）
任务终态与 outbox_events 插入同事务提交，事件绝不丢失；进程内 Publisher
限量清扫（publish_attempts 上限），RabbitMQ 中继以可选 profile 接入同一行存储。
消费者幂等：eventId + consumerName（consumed_events 表）。

## 安全边界

- 会话：HttpOnly Cookie + SameSite=Lax（生产 Secure），Spring Security 6 SPA CSRF 模式。
- 内部接口：`X-Worker-Token` 常量时间比较；未带 token 一律 403。
- 渲染安全：非 shell 参数数组；用户文本仅经字幕文件注入；Worker 只按任务分配的
  逻辑 key 访问存储；容器内非 root 运行；输出键服务端生成并二次校验。
- 隐私：素材归属在服务端逐请求校验（按 ID 访问返回 404 不泄露存在性）；
  分享只暴露选定成片、库存令牌哈希、日志不记完整令牌。
