# 半日归档格式

Personal Recorder 将 Room 中的原始事件导出为长期归档，不使用 Room SQLite 文件作为云端数据源。

## 目录

```text
archive/YYYY/MM/YYYY-MM-DD/
├── 00-12.jsonl
├── 12-24.jsonl
└── manifest.json
```

两个半日区间使用设备本地时区解释：

- `00:00 <= timestamp < 12:00`
- `12:00 <= timestamp < 次日 00:00`

`timestamp` 仍是 Unix epoch milliseconds；归档计算使用 `ZoneId.systemDefault()`，不能使用 UTC 取模。

## JSONL

每行是一个独立 JSON object，`schemaVersion` 固定为 `1`。归档 DTO 与 Room 的 `EventEntity` 分离，但 v1 保留当前 `PersonalEvent` 的业务字段与通知元数据。事件按 `timestamp ASC` 输出，空半日也生成空文件。

归档不应用统计页的进行中、组摘要或应用筛选条件，保存的是原始事件历史。

## Manifest 与完整性

当天两个 segment 都闭合且通过完整性校验后才生成最终 `manifest.json`。`manifest.json` 是自然日归档的唯一完成标记，记录固定的 `schemaVersion`、日期、设备时区、每个 segment 的数量和 SHA-256、当天总事件数，以及可选的 `completedAt`。

```json
{
  "schemaVersion": 1,
  "date": "2026-08-22",
  "timeZone": "Asia/Shanghai",
  "segments": [
    { "fileName": "00-12.jsonl", "eventCount": 0, "sha256": "<64-hex>" },
    { "fileName": "12-24.jsonl", "eventCount": 0, "sha256": "<64-hex>" }
  ],
  "totalEventCount": 0,
  "completedAt": "2026-08-23T00:37:12+08:00"
}
```

完整性合同如下：

- 两个 JSONL 都存在且 manifest 存在、校验通过：`COMPLETE`
- 只有一个 JSONL，或两个 JSONL 存在但没有 manifest：`INCOMPLETE`
- manifest 引用文件缺失、数量不匹配、SHA 缺失或 SHA 不匹配：`INVALID`

本地完整性与远端同步是两个独立维度：`Local COMPLETE` 表示两个 segment 和有效 manifest 已在设备上完成；`Remote PENDING` 表示本地 COMPLETE 但 GitHub manifest 尚未同步；`Remote SYNCED` 表示本地 COMPLETE 且 GitHub manifest 已成功发布。远端 pending 不属于本地 archive gap。

最终 finalize 在 manifest 生成前允许基于 Room 重建尚未冻结的分片，以吸收迟到事件；manifest 成功同步后，历史 JSONL 和 manifest 保持 immutable。旧 schema 1/2 manifest 仍可解析，但缺少可验证 SHA 时不能判定为 `COMPLETE`。
