# Personal Recorder

Personal Recorder 是一个 Android 本地个人事件采集器。

## 当前能力

- `NotificationListenerService` 通知采集
- Room 本地持久化
- 统计页：今日、近 7 日、近 30 日事件分析
- 最近通知查看
- 通知访问权限状态检测
- 应用白名单/黑名单筛选

当前事件和筛选配置只保存在设备本地，数据库与筛选配置不会参与 Android 云备份，也没有网络上传逻辑。

## 计划能力

- Android Share Target
- 日终归档
- Personal Hub 同步
- Hermes 集成

## 使用

1. 使用 Android Studio 运行 Debug 版本。
2. 打开 Personal Recorder，点击“授权通知访问”。
3. 在系统设置中允许 Personal Recorder 访问通知。
4. 返回应用，查看通知访问状态和最近事件。
5. 在记录页右上角打开应用筛选，可选择全部应用、白名单或黑名单。
6. 在统计页切换时间范围，查看小时分布、应用来源和每日趋势。

统计页使用通知事件作为统计单位，并排除进行中通知、组摘要和当前筛选之外的应用；记录页仍保留原始事件。
