# 后台运行模块

本目录只负责后台状态可观测性和轻量健康检查，不负责替代 `NotificationListenerService`，也不创建永久前台服务。

- `BackgroundRuntimeStateStore.kt`：保存 UNKNOWN/CONNECTED/DISCONNECTED 监听状态、进程身份、回调时间、rebind 冷却、采集、归档、同步、健康检查和错误摘要。
- `BackgroundHealthWorker.kt`：每 6 小时及事件触发检查权限、监听连接、待同步数量和冲突，并在必要时请求一次 rebind。
- `StatusNotificationManager.kt`：用户主动开启后展示脱敏运行状态，操作入口转发到应用和 WorkManager。
- `BackgroundSettingsStore.kt`：保存状态通知开关，默认关闭。
- `BackgroundDiagnostics.kt`：读取电池优化豁免和厂商后台限制提示，仅使用公开系统 API。
- `RecentTaskController.kt`：通过当前 `AppTask.setExcludeFromRecents` 应用用户选择，提供 `Result` 失败边界和 Fake 实现用于测试。

`PersonalRecorderApplication` 负责进程级健康检查调度和最近任务策略同步；Activity 创建后会再次应用策略，避免应用首次启动时尚未存在 `AppTask`。

Android/OEM 不保证普通应用永久存活；诊断页只使用标准系统设置入口，不自动修改自启动或最近任务锁定策略。
