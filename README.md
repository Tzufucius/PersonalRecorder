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
- GitHub 私有仓库与 Google Drive 云端同步配置

Room 仍是应用本地实时数据源；JSONL 是长期归档格式。云端同步只上传半日归档文件，不上传 Room 数据库。

## Cloud Sync

支持 GitHub Private Repository、Google Drive，以及两个 backend 同时同步。

GitHub 使用无需 `client_secret` 的 Device Flow，连接后自动维护私有的
`PersonalRecorder-Archive` 仓库，并将一个同步批次合并为一个 Git commit。
Google Drive 使用 `AuthorizationClient` 的 `drive.file` scope；后台每次按需获取
有效 access token，不把短期 token 作为长期凭据保存。

Archive 固定为：

- `00-12.jsonl`
- `12-24.jsonl`
- 完整日 `manifest.json`

Sync Frequency 支持 Twice Daily、Daily、Weekly。调度由 WorkManager 执行，允许系统延迟但不会跳过已闭合归档。

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
