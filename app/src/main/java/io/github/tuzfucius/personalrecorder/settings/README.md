# 设置模块

设置模块保存应用筛选和云端同步偏好。普通开关、连接状态、GitHub 用户名与同步频率使用 DataStore；GitHub Device Flow 返回的 access token 不放入此处，由同步实现使用 Android Keystore 保护。Google Drive access token 不持久化，由 AuthorizationClient 按需获取。

`CloudSyncSettingsStore` 只保存 GitHub/Google Drive 的开关、连接状态、GitHub 用户名和同步频率，不负责上传或授权流程。
