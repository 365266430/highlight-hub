# 存储、下载与清理

## StorageService 抽象

```java
put(key, in) / open(key) / openRange(key, start, len)
exists(key) / size(key) / delete(key) / resolveLocalPath(key)
```

- 默认实现为本地磁盘（`highlight-hub.storage.root`，默认 `./data`）。
- 物理路径只存在服务端；浏览器一律通过鉴权接口访问，API 响应永不包含物理路径。
- key 为服务端生成的逻辑路径：`original/{mediaId}/source`、`preview/{mediaId}/preview.mp4`、
  `thumbnail/{mediaId}/sprite.jpg`、`evidence/{analysisRunId}/n.jpg`、
  `render/{renderJobId}/output.mp4`、`tmp/upload/{uploadId}/chunk-N`。
- key 解析时拒绝 `..`、绝对路径、反斜杠穿越（防路径穿越删除）。
- 本地实现支持范围读取（HTTP Range / 预览播放）。
- S3 兼容对象存储：实现同一接口的 `put/open/openRange/stat/delete` 即可替换，
  分片上传与合并流程不变（key 语义一致）。

## 资产模型（media_assets）

type：ORIGINAL / PREVIEW / THUMBNAIL / EVIDENCE / RENDER_OUTPUT / TEMPORARY。
owner_id 全量归属；media_id 可空（渲染输出独立成资产）。私有素材默认不公开。

## 下载与播放

- 预览/原始/成片均为鉴权 Range 接口：`GET /api/media/{id}/preview|original`、
  `GET /api/renders/{id}/download`；Range 请求返回 206 + Content-Range（E2E 覆盖）。
- 唯一的公开端点是分享下载 `GET /api/shares/{token}/download`：
  - 令牌为 64 字符高熵随机串，库里只存 SHA-256 哈希；
  - 过期时间（默认 72h，可设 1h–30d）；owner 可撤销（revoked_at）；
  - 只暴露选定成片文件，原始录像绝不通过分享可达；
  - 撤销/过期后返回 410；日志不记录完整令牌（记录 share id 与访问方地址哈希）。

## 删除与清理

- 媒体删除：`DELETE /api/media/{id}` → 状态机 DELETING（软删）→ 入队 CLEANUP 任务 →
  Worker 删除物理文件（original/preview/thumbnail 目录）→ Java 同事务把媒体置
  DELETED、资产置 DELETED、按 file_size 释放用户配额。
- 上传会话：过期（TTL 默认 48h）由清扫器清理分片文件与行、释放预留配额；
  取消会话立即同样处理；COMPLETING 状态超时 10 分钟重置回 UPLOADING 允许重试合并。
- Worker 临时目录：每任务独立 `tmp/worker/{taskId}`，任务结束即清；
  清扫任务会移除超过 1 天的残留目录。
- 删除失败可重试：CLEANUP 任务按重试策略重跑，全部成功后才修正空间统计。
- 上传临时分片按 TTL 清理；配额预留与释放全部原子 SQL，多会话并发不绕过配额。
