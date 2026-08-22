# Personal Recorder

Personal Recorder 是一个 Android 本地个人事件采集器。

## 当前能力

- `NotificationListenerService` 通知采集
- Room 本地持久化
- 今日事件统计
- 最近通知查看
- 通知访问权限状态检测

当前数据只保存在设备本地，数据库不会参与 Android 云备份，也没有网络上传逻辑。

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
