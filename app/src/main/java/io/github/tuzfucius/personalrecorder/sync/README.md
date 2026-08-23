# 云端同步模块

本目录定义云端同步的独立领域模型和后端适配边界。同步对象是已封存 JSONL 与 manifest 的不可变字节，不上传 Room 数据库。

- `SyncModels.kt`：后端、频率、状态、结果与错误模型。
- `SyncCoordinator.kt`：按归档和后端独立处理状态，临时网络问题指数退避重试，冲突和授权问题不重试。
- `CloudSyncWorker.kt`：使用 WorkManager 网络约束进行尽力而为的周期调度，不承诺精确时间。
- `GitHubDeviceFlow.kt`、`OkHttpGitHubApi.kt`、`GitHubSync.kt`：Device Flow、私有仓库连接、GitHub REST/Git Data API 和批量提交。
- `GoogleDriveSync.kt`、`OkHttpGoogleDriveRestClient.kt`：`drive.file` 授权抽象、按需 token、401 刷新、REST v3 边界和应用自建目录 ID 缓存。

调用方负责将 archive 模块的封存记录转换为 `CloudArchive`，并以 Android Keystore 保护 GitHub token。Google access token 由 AuthorizationClient 按需获取，不写入本地长期凭据。不得把 secret 写入 APK、资源、日志或归档。
