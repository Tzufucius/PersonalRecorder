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

当天两个 segment 都闭合后才生成最终 `manifest.json`，避免半日 manifest 被重复覆盖。manifest 记录固定的 `schemaVersion`、日期、设备时区、每个 segment 的数量和 SHA-256，以及当天总事件数。

```json
{
  "schemaVersion": 1,
  "date": "2026-08-22",
  "timeZone": "Asia/Shanghai",
  "segments": [],
  "totalEventCount": 0
}
```

每个 JSONL 文件生成后计算 SHA-256，并保存到 Room 的 `ArchiveSegmentEntity` 和 manifest。文件一旦封存不自动因晚到事件重新生成；晚到事件仍保留在 Room 中。
