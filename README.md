# Personal Recorder

Personal Recorder 是一个 Android 本地个人事件采集器。

## 当前能力

- `NotificationListenerService` 通知采集
- Room 本地持久化
- 统计页：今日、近 7 日、近 30 日事件分析
- 最近通知查看
- 通知访问权限状态检测
- 应用白名单/黑名单筛选
- 半日 JSONL 本地归档
- GitHub 私有仓库云端同步

Room 仍是应用本地实时数据源；JSONL 是长期归档格式。GitHub 私有仓库是长期归档中心和新设备恢复源，云端同步只处理半日归档文件与完整日 manifest，不上传 Room 数据库。

## GitHub 私有归档

PersonalRecorder 当前只使用 GitHub 私有仓库存储长期归档，不上传 Room 数据库。

1. 创建 GitHub fine-grained Personal Access Token。
2. 将目标私有仓库授予 `Contents: Read and write` 与 `Metadata: Read-only` 权限。
3. 在 PersonalRecorder 的“设置”中粘贴 Token，并填写仓库名称。
4. 默认仓库名称为 `PersonalRecorder-Archive`；仓库不存在时应用会尝试创建私有仓库。
5. 应用会验证当前账号、仓库 owner、`private=true` 和写权限后才保存连接状态。
6. PAT 只使用 Android Keystore 加密保存，不写入 DataStore、Room 或日志。
7. 半日 JSONL 和完整日 `manifest.json` 通过 GitHub Contents API 进行双向 Reconcile：先发现和拉取远端，再合并本地差异，最后上传本地-only 文件并更新 manifest。

Archive 固定为：

- `00-12.jsonl`
- `12-24.jsonl`
- 完整日 `manifest.json`

Sync Frequency 支持 Twice Daily、Daily、Weekly。调度由 WorkManager 执行，允许系统延迟但不会跳过已闭合归档。

Room 是本地实时数据源，JSONL 是长期归档，GitHub Private Repository 是当前唯一远端持久化存储。更换设备时连接同一仓库后，在设置页选择“从 GitHub 恢复历史”，应用会原子下载、SHA-256 校验、恢复 archive metadata，并将历史事件幂等导入 Room。

同一归档路径内容不同会按事件 ID 合并；相同 ID 内容不同则保留本地和远端原始文件并显示冲突，不静默覆盖。

## 后台可靠性

应用核心仍是 `NotificationListenerService + Room + WorkManager`。设置页的后台运行诊断显示监听、GitHub、待上传、待下载、冲突和最近健康检查；可选状态通知默认关闭，只显示脱敏运行信息。通知访问、电池优化和应用后台设置均需用户在系统页面确认。Android/OEM 不保证普通应用永久存活，Personal Recorder 不使用 AccessibilityService 或永久 `dataSync` 前台服务保活。

## 计划能力

- Android Share Target
- Hermes 集成

## 使用

1. 使用 Android Studio 运行 Debug 版本。
2. 打开 Personal Recorder，点击“授权通知访问”。
3. 在系统设置中允许 Personal Recorder 访问通知。
4. 返回应用，查看通知访问状态和最近事件。
5. 在记录页右上角打开应用筛选，可选择全部应用、白名单或黑名单。
6. 在统计页切换时间范围，查看小时分布、应用来源和每日趋势。

统计页使用通知事件作为统计单位，并排除进行中通知、组摘要和当前筛选之外的应用；记录页仍保留原始事件。
