# 云端同步模块

本目录定义 GitHub 私有归档同步的领域模型和窄后端边界。同步对象是已封存 JSONL 与 manifest 的不可变字节，不上传 Room 数据库。

- `SyncModels.kt`：后端、频率、状态、结果与错误模型。
- `SyncCoordinator.kt`：按归档处理状态，跨时间重试由 WorkManager 负责。
- `CloudSyncWorker.kt`：使用 WorkManager 网络约束进行尽力而为的周期调度，不承诺精确时间。
- `GitHubSync.kt`、`GitHubArchiveClient.kt`：PAT 连接校验、私有仓库保护和 Contents API 幂等上传。
- `CloudCredentialStore.kt`、`SecureSecretStore.kt`：仅通过 Android Keystore 保存 GitHub PAT。

调用方负责将 archive 模块的封存记录转换为 `CloudArchive`。不得把 PAT 写入 APK、资源、DataStore、Room、日志或归档。
