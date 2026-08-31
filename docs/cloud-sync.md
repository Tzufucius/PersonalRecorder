# 云端同步

云端只接收本地半日 JSONL 和最终 manifest，不接收 Room 数据库。两个 backend 使用同一份 immutable archive，并分别记录每个归档文件的同步状态。

## 调度与错误

`CloudSyncWorker` 使用 WorkManager 和 `NetworkType.CONNECTED`。普通云同步仍按用户选择的频率尽力执行，手动任务使用唯一 work `cloud_archive_sync_now`。每日完整归档由独立的 `DailyArchiveFinalizeWorker` 负责，使用无网络约束的 OneTimeWorkRequest 按本地时间 `00:30` 执行，先在离线状态完成 Room 到 JSONL/manifest 的归档并登记 pending，成功后再触发普通云同步。每日任务使用按目标日期命名的独立 work 和 `REPLACE`，失败也会安排下一次；Application 启动、GitHub 连接成功和健康检查都会确保任务存在，并对历史缺口 enqueue 唯一 catch-up work；这些流程不依赖打开 UI 或修改同步频率。

网络、服务不可用和限流错误由 WorkManager 使用指数退避重试；认证、权限、冲突、无效归档和未配置错误只记录失败，不由 Worker 无限重试。网络失败时本地 JSONL 和 manifest 保留，Room 同步状态保持 pending，下一次运行只补传。Runner 在进程内使用 Mutex，避免每日任务、周期任务和手动任务同时处理同一批文件。

每日 Worker 的 `Result.success()` 只表示本地最终归档成功，不代表 GitHub 已上传；GitHub 认证失败不会阻止下一次每日任务。生成 manifest 时始终创建对应的 GitHub manifest 同步状态，历史日期的 pending 状态也会进入 reconcile scope，不受七日增量窗口限制。

## GitHub

GitHub 使用 OAuth App Device Flow：

1. APP 请求 `/login/device/code`，取得验证码和服务器给出的 polling interval。
2. 用户在 `https://github.com/login/device` 输入验证码。
3. APP 以不低于 interval 的频率轮询 `/login/oauth/access_token`。

`slow_down` 按 GitHub 规则增加轮询间隔；Device Flow 不需要 `client_secret`，因此不会把 secret 放入 APK。GitHub 官方主要将 Device Flow 定位于无头或受限浏览器场景，这是当前 Android public client 不引入认证服务器的明确工程取舍。

连接成功后，APP 使用 `GET /user` 识别账号，检查或创建私有的 `PersonalRecorder-Archive` 仓库。仓库必须属于当前账号、保持 private 且具有 push 权限；发现 public 仓库时硬阻止同步。

OAuth App 使用 `repo` scope。该权限大于 Personal Recorder 实际所需的单仓库写入权限，后续可迁移到 GitHub App 以收窄权限，本轮不迁移。

GitHub access token 仅通过 Android Keystore 保护的 `SecureSecretStore` 保存。完整日期使用 Contents API 按 `00-12.jsonl`、`12-24.jsonl`、`manifest.json` 顺序逐个上传；manifest 只有在两个分片已经远端存在且内容一致后才发布，因此它是远端完成标记。Contents API 的三个 PUT 不是单一原子 commit，期间可能短暂只看到分片，但不会先看到 manifest。远端同路径同内容视为幂等成功，同路径不同内容按现有 reconcile 规则处理。

## Google Drive

Google Drive 使用 `AuthorizationClient` 和 `drive.file` scope。前台连接仍通过 Activity Result；后台 Worker 使用 `Context` 创建 AuthorizationClient 并尝试静默获取当前有效 token。

access token 不长期持久化。Drive 请求返回 401 时清除 Google token cache，并且只重新获取 token、重试当前请求一次；再次失败即记录认证错误。若 `authorize()` 返回需要 PendingIntent 的 resolution，Worker 不弹 UI，而是将连接状态置为需要重新授权，等待用户回到设置页操作。

断开连接时调用 Google 官方 `revokeAccess`，清理本地 token cache、连接状态和启用状态，但保留本地归档与同步历史。

## 归档发现

同步前由 `ArchivePlanner` 和 `ArchiveService` 统一扫描事件覆盖范围、闭合日期、segment 元数据和 manifest 完整性。每日 finalize 会补齐所有已闭合但缺少分片或 manifest 的日期；普通同步仍可生成当前已闭合的半日分片。manifest 只有在两个 JSONL 的行数和 SHA-256 均匹配时才作为 `COMPLETE`，因此 Daily Review 可以把 manifest 作为唯一 completeness contract。
