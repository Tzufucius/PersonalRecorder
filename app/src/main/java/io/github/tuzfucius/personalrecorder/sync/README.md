# 云端同步模块

本目录定义 GitHub 私有归档同步的领域模型和窄后端边界。GitHub 私有仓库是长期归档中心和新设备恢复源；同步对象是已封存 JSONL 与 manifest，不上传 Room 数据库。

- `SyncModels.kt`：后端、频率、状态、结果与错误模型。
- `SyncCoordinator.kt`：保留旧后端协调边界，跨时间重试由 WorkManager 负责。
- `ArchiveInventory.kt`：本地/远端清单扫描、七日增量窗口、SHA-256 和原子文件替换。
- `ArchiveReconciler.kt`：按路径分类并按事件 ID 确定性合并 JSONL。
- `ArchiveReconcileService.kt`：Finalize → Discover → Pull → Verify → Merge → Push → Persist 的单实例协调流程。
- `ArchiveImportService.kt`：事务化、按事件 ID 幂等恢复到 Room。
- `CloudSyncWorker.kt`：使用 WorkManager 网络约束进行尽力而为的周期调度，不承诺精确时间。
- `GitHubSync.kt`、`GitHubArchiveClient.kt`：PAT 连接校验、私有仓库保护、Contents API 目录发现、下载和带 SHA 的更新。
- `CloudCredentialStore.kt`、`SecureSecretStore.kt`：仅通过 Android Keystore 保存 GitHub PAT。

调用方负责将 archive 模块的封存记录转换为 `CloudArchive`。不得把 PAT 写入 APK、资源、DataStore、Room、日志或归档。

Reconcile 遵循 Pull Before Push：远端-only 文件下载并校验后才落盘；本地-only 文件上传；同路径不同内容按 JSONL 集合合并；相同事件 ID 内容不一致时写入 `archive_conflicts` 和原始文件，不静默覆盖。409 只允许重新发现并协调一次。
