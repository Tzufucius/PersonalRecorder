# 归档模块

本目录负责将 Room 原始通知事件按设备时区切分为两个半日 JSONL 文件，并在完整日闭合后生成 manifest。文件写入 `filesDir/archive`，每个封存 segment 计算 SHA-256；Room 中只保存归档元数据，不把 SQLite 文件作为云端同步载荷。

分段类型使用 `ArchiveSegmentType.FIRST_HALF` 和 `ArchiveSegmentType.SECOND_HALF`，文件名固定为 `00-12.jsonl` 与 `12-24.jsonl`。

完整日 manifest 在具备设备实例 ID 时使用 schema 2，并通过可选 `sourceDeviceIds`、`lastWriterDeviceId` 标记归档来源；旧 schema 1 manifest 仍可读取。
