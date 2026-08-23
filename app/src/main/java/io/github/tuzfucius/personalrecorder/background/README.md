# 后台运行模块

本目录只负责后台状态可观测性和轻量健康检查，不负责替代 `NotificationListenerService`，也不创建永久前台服务。

- `BackgroundRuntimeStateStore.kt`：保存最近监听、采集、归档、同步、健康检查和错误摘要。
- `BackgroundHealthWorker.kt`：每 6 小时及事件触发检查权限、监听连接、待同步数量和冲突，并在必要时请求一次 rebind。
- `StatusNotificationManager.kt`：用户主动开启后展示脱敏运行状态，操作入口转发到应用和 WorkManager。
- `BackgroundSettingsStore.kt`：保存状态通知开关，默认关闭。

Android/OEM 不保证普通应用永久存活；诊断页只使用标准系统设置入口，不自动修改自启动或最近任务锁定策略。
