# 云端同步模块

本目录定义云端同步的独立领域模型和后端适配边界。同步对象是已封存 JSONL 与 manifest 的不可变字节，不上传 Room 数据库。

- `SyncModels.kt`：后端、频率、状态、结果与错误模型。
- `SyncCoordinator.kt`：按归档和后端独立处理状态，临时网络问题指数退避重试，冲突和授权问题不重试。
- `CloudSyncWorker.kt`：使用 WorkManager 网络约束进行尽力而为的周期调度，不承诺精确时间。
- `GitHubSync.kt`：PKCE/deep-link 协调、安全 token exchange 占位、私有仓库防护和 Git Data API 边界。
- `GoogleDriveSync.kt`：`drive.file` 授权抽象、REST v3 边界和应用自建目录 ID 缓存。

调用方负责将 archive 模块的封存记录转换为 `CloudArchive`，并以 Android Keystore 保护 token、刷新 token 与持久化 PKCE verifier。不得把 secret 写入 APK、资源、日志或归档。
