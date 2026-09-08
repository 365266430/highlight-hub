# 适配器开发（游戏识别）指南

## 核心原则（不可妥协）

1. **通用与游戏特定分离**：上传、任务、编辑、渲染只实现一次；游戏识别规则只存在于
   适配器版本（`adapter_versions.config_json`）与用户校准的 ROI 模板中。
2. **版本不可变**：已发布的适配器版本不原地修改，新调整创建新版本；
   分析任务绑定具体 `adapter_version_id`，结果可解释、可追溯。
3. **状态诚实**：DRAFT / EXPERIMENTAL / VERIFIED / DISABLED。
   只有在真实授权录像 + 人工标注评测通过后才能标 VERIFIED。
   当前唯一内置适配器 `generic-ocr` 为 **EXPERIMENTAL**——未经真实游戏验证。
4. **不匹配时退回手动**：适配器无法处理的输入必须明确失败
   （`ADAPTER_INPUT_UNSUPPORTED` / `NO_ROIS_CONFIGURED`），不得悄悄给出可信结果。

## 通用 OCR 适配器（当前实现）

### ROI 模板字段
```json
{
  "id": "score",
  "x": 0.55, "y": 0.02, "w": 0.44, "h": 0.30,   // 相对坐标 0~1
  "mode": "number",                              // number | text
  "eventType": "SCORE_CHANGE",                   // 触发的事件类型
  "pattern": "",                                 // text 模式的正则（可选）
  "multiFrameConfirm": 2,                        // 多帧确认阈值
  "dedupWindowMs": 5000                          // 事件去重时间窗
}
```
全局参数：`sampleIntervalMs`（采样间隔）、`preprocess`（{scale, grayscale}）。

### 时序处理
- 采样使用容器时间戳（顺序读取帧时的 `CAP_PROP_POS_MSEC`），
  不用 frameIndex/guessedFps（可变帧率下会漂移）。
- 变化确认：新值需连续出现 `multiFrameConfirm` 次才生成事件；OCR 短暂抖动被过滤。
- 去重：同 ROI 同类型事件在 `dedupWindowMs` 内合并。
- 数字模式校验数字串变化；归属未知时**不推断**是当前玩家完成的击败——
  ELIMINATION_NOTICE 只表示“画面中观察到击败提示”。
- confidence 是识别置信信息，没有校准前不解释为准确概率，更不是精彩程度。

### 证据
每个确认事件保存裁剪帧截图（`evidence/{analysisRunId}/n.jpg`）作为 EVIDENCE 资产，
用户可逐条查看证据后决定采纳与否。

## 新增一款游戏适配器的步骤（待真实条件具备后执行）

1. 明确游戏名、界面布局、有权使用的录像样本与人工标注（没有这些，停止）。
2. 在样帧上框选 ROI，标定相对坐标；确定每个 ROI 的 mode/事件类型/确认/去重参数。
3. 创建新的 adapter_version（config_json 携带模板），状态 DRAFT → EXPERIMENTAL。
4. 用真实录像评估：事件 Precision / Recall、时间定位误差、候选覆盖率、
   每小时无用候选数。数字必须实测并记录于评估报告。
5. 指标达标后才允许置 VERIFIED；不同分辨率/宽高比/HUD/语言仍需分别校准。

## 诚实的边界（当前状态）

- 未指定第一款真实游戏：所有真实游戏适配**待验证**。
- 合成测试（OpenCV 计数器夹具）只验证管线机制，不作为识别准确率证据。
- 不做：全游戏通用识别、操作水平评分、自动判断搞笑内容、读取进程内存、自动操作。
