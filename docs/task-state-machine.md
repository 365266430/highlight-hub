# 任务状态机

## 状态

```
QUEUED ──claim──► RUNNING ──complete──► SUCCEEDED
   │                 │ │
   │                 │ └─fail(retryable, attempt<max)──► QUEUED (next_run_at 退避)
   │                 │
   │                 ├─fail(不可重试 或 重试耗尽)──► FAILED
   │                 ├─cancelRequest──► CANCEL_REQUESTED ──worker确认/清扫──► CANCELLED
   │                 └─lease过期──► attempt超时: QUEUED(退避) 或 FAILED(LEASE_LOST)
   │
   └─cancel──► CANCELLED
```

任务类型：PROBE / PREVIEW / THUMBNAIL / ANALYZE / GENERATE_CANDIDATES / RENDER / CLEANUP。

## 领取（原子）

`POST /internal/tasks/claim`（X-Worker-Token 服务鉴权）：

1. `SELECT ... FOR UPDATE SKIP LOCKED` 取候选（status=QUEUED AND next_run_at<=now）；
2. 事务内逐条条件 UPDATE：`WHERE id=? AND status='QUEUED'` → RUNNING，
   attempt+1，生成 `attemptToken`，写 `lease_until`，插 `task_attempts` 行。

两个 Worker 并发领取绝不可能拿到同一任务（行锁 + 条件更新双保险，
TaskQueueIntegrationTest.twoWorkersNeverClaimTheSameTask 覆盖）。

## 心跳与租约

- Worker 每 lease/3 心跳续租；心跳/进度都按 `attempt_token + status=RUNNING` 条件更新。
- 更新 0 行 = 当前 attempt 已失效（被清扫或取消）→ Worker 立即中止执行。
- 清扫器（`highlight-hub.task.lease-sweep-interval-seconds`）把 lease 过期的 RUNNING
  任务按重试策略回收：未耗尽 → QUEUED 退避重排；耗尽 → FAILED(LEASE_LOST)。
  CANCEL_REQUESTED 中 Worker 已死的一致性由清扫器确认成 CANCELLED。

## 回调校验（旧 Worker 隔离）

complete / fail / progress 必须携带当前 `attemptToken`：
- token 不匹配 → 409（旧 Worker 的迟到回调被拒绝，不能覆盖新尝试）；
- 重复成功回调：第一次后状态已非 RUNNING → 409；
- 成功与取消竞态：`markSucceeded` 与 `requestCancelRunning` 都以 status=RUNNING 为条件，
  数据库级恰好一个胜出；取消请求后到达的成功回报按 CANCELLED 记录（不复活任务）；
- Worker 崩溃：租约过期清扫接管；重试按阶段重跑，已成功阶段（探测/预览/缩略图产物）
  保留复用，不声称 ffmpeg 断点续算。

## 结果提交的原子性

`POST /internal/tasks/{id}/complete` 在**同一个事务**里：
1. 条件更新任务状态 → SUCCEEDED；
2. task_attempts 行收尾；
3. 发布 Spring 事件（同一事务内监听）：媒体探测信息落库 / 渲染结果落库 / 事件落库；
4. outbox_events 插入（同事务）。

监听器抛错 → 整体回滚 → Worker 收到失败可重试 complete（产物 key 确定性，重试安全）。

## 取消语义（用户视角）

- QUEUED：直接 CANCELLED（幂等：终态重复取消返回当前状态）。
- RUNNING：RUNNING → CANCEL_REQUESTED；Worker 在进度轮询点检测到后杀掉整个子进程组
  （Windows taskkill /T，POSIX killpg），回报后进入 CANCELLED。
- 迟到成功不能把已取消任务改成成功（TaskQueueIntegrationTest.
  successAfterCancelRequestCannotResurrectTheTask 覆盖）。

## 重试

- 可重试（基础设施类：FFMPEG_CRASH、WORKER_CRASH、RENDER_INVALID_OUTPUT…）与
  不可重试（输入类：INVALID_INPUT、INVALID_MEDIA、NO_ROIS_CONFIGURED、
  RENDER_OUTPUT_TOO_LARGE）在 Worker 回报时显式区分。
- 退避：`min(15s * attempt, 300s)`；最大次数默认 3；用户可对 FAILED 任务手动 retry
  （回到 QUEUED，attempt 归零）。
