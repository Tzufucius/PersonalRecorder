# 设置模块

设置模块保存应用筛选和云端同步偏好。普通开关与同步频率使用 DataStore；OAuth token、PKCE verifier 等敏感数据不放入此处，必须由云端实现使用 Android Keystore 保护。

`CloudSyncSettingsStore` 只保存 GitHub/Google Drive 开关和同步频率，不负责上传或授权流程。
