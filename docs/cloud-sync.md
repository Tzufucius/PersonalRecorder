# 云端同步

云端只接收本地半日 JSONL 和最终 manifest，不接收 Room 数据库。`CloudSyncBackend` 抽象使 GitHub 与 Google Drive 使用同一份 immutable archive。

## 调度

`CloudSyncWorker` 使用 WorkManager 和 `NetworkType.CONNECTED`，不承诺精确分钟执行。每次运行都会计算所有已经闭合、尚未成功同步的 segment：

- `TWICE_DAILY`：尽可能每 12 小时处理一次
- `DAILY`：每天批量处理积压
- `WEEKLY`：每周批量处理过去一周积压

调度频率不会改变 `00-12`、`12-24` 文件结构。网络错误可重试；认证、权限和远端冲突不会无限重试。

## GitHub

默认私有仓库为 `PersonalRecorder-Archive`，路径为 `archive/YYYY/MM/YYYY-MM-DD/...`。上传前必须确认仓库属于当前用户、为 private 且可写；public 仓库直接阻止同步。一次 Worker 批量上传尽量通过 Git Data API 生成一个逻辑 commit，避免一个文件一个 commit。

授权前端使用随机 `state`、PKCE 和 `personalrecorder://oauth/github` 回调，仅申请 `repo` scope。`repo` 是 OAuth App 的宽权限，覆盖私有仓库读写及相关协作资源，明显大于归档上传所需的最小权限；后续可迁移到 GitHub App，收窄为仓库级 `Contents` 写权限。GitHub 当前授权码交换接口仍要求 `client_secret`，原生 APK 不安全地保存该值，因此本版本只提供安全 OAuth 占位和 token-exchange 接口；未配置可信交换服务时显示“未配置交换服务”，不会伪造已连接状态。

非敏感 GitHub client ID 可通过未提交的 Gradle 属性 `githubClientId` 注入 Debug BuildConfig；不要注入 client secret。完成浏览器授权后仍需要可信服务交换授权码。

## Google Drive

使用 Google Identity `AuthorizationClient` 和 `drive.file` scope，通过 Drive REST API v3 上传。APP 只管理自己创建的 `PersonalRecorder/archive` 目录树，并缓存各级 folder ID；不扫描或接管用户手工创建的同名目录。

上传文件元数据包含本地 SHA-256。远端已有同路径且 SHA 不同的文件时，记录 `FAILED` 和 `Remote Conflict`，不静默覆盖。

## 设置与凭证

开关、频率和普通状态使用 DataStore。访问 token、refresh token、PKCE verifier 等敏感值使用 Android Keystore 保护，不写入日志、BuildConfig 明文、资源文件或归档。

真机使用前需要：

1. 在 GitHub Developer settings 创建 OAuth App，配置 client ID 和回调地址，并准备可信 token-exchange 服务。
2. 在 Google Cloud Console 启用 Drive API、配置 OAuth consent screen、添加 `drive.file` scope，并分别登记 Debug/Release SHA-1 的 Android OAuth client。
