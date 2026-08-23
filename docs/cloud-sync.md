# 云端同步

云端只接收本地半日 JSONL 和最终 manifest，不接收 Room 数据库。两个 backend 使用同一份 immutable archive，并分别记录每个归档文件的同步状态。

## 调度与错误

`CloudSyncWorker` 使用 WorkManager 和 `NetworkType.CONNECTED`。周期任务按设置频率尽力执行，手动任务使用唯一 work `cloud_archive_sync_now`，重复点击不会创建并行任务。

网络、服务不可用和限流错误由 WorkManager 使用指数退避重试；认证、权限、冲突、无效归档和未配置错误只记录失败，不由 Worker 无限重试。Runner 在进程内使用 Mutex，避免周期任务和手动任务同时处理同一批文件。

## GitHub

GitHub 使用 OAuth App Device Flow：

1. APP 请求 `/login/device/code`，取得验证码和服务器给出的 polling interval。
2. 用户在 `https://github.com/login/device` 输入验证码。
3. APP 以不低于 interval 的频率轮询 `/login/oauth/access_token`。

`slow_down` 按 GitHub 规则增加轮询间隔；Device Flow 不需要 `client_secret`，因此不会把 secret 放入 APK。GitHub 官方主要将 Device Flow 定位于无头或受限浏览器场景，这是当前 Android public client 不引入认证服务器的明确工程取舍。

连接成功后，APP 使用 `GET /user` 识别账号，检查或创建私有的 `PersonalRecorder-Archive` 仓库。仓库必须属于当前账号、保持 private 且具有 push 权限；发现 public 仓库时硬阻止同步。

OAuth App 使用 `repo` scope。该权限大于 Personal Recorder 实际所需的单仓库写入权限，后续可迁移到 GitHub App 以收窄权限，本轮不迁移。

GitHub access token 仅通过 Android Keystore 保护的 `SecureSecretStore` 保存。一次 batch 会通过 Git Data API 创建 blob、tree、commit 并更新 branch ref，最多产生一个逻辑 commit。远端同路径同内容视为幂等成功，同路径不同内容视为冲突，不静默覆盖。

## Google Drive

Google Drive 使用 `AuthorizationClient` 和 `drive.file` scope。前台连接仍通过 Activity Result；后台 Worker 使用 `Context` 创建 AuthorizationClient 并尝试静默获取当前有效 token。

access token 不长期持久化。Drive 请求返回 401 时清除 Google token cache，并且只重新获取 token、重试当前请求一次；再次失败即记录认证错误。若 `authorize()` 返回需要 PendingIntent 的 resolution，Worker 不弹 UI，而是将连接状态置为需要重新授权，等待用户回到设置页操作。

断开连接时调用 Google 官方 `revokeAccess`，清理本地 token cache、连接状态和启用状态，但保留本地归档与同步历史。

## 归档发现

同步前只读取一次事件时间范围和已有 segment ID。`ArchivePlanner` 在内存中计算缺失的闭合 segment，只对缺失日期查询 events；因此可以恢复历史 hole，同时避免每天重复查询已有归档。
