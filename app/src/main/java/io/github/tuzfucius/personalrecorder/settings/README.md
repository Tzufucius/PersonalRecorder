# 设置模块

设置模块保存 GitHub 自动同步开关、连接状态、账号、仓库名称和同步频率。所有配置使用 DataStore；PAT 不放入此处，由同步实现使用 Android Keystore 保护。

`CloudSyncSettingsStore` 只保存显示和调度所需配置，不负责网络请求或凭证验证。旧 DataStore 中的无效 provider 键会被忽略，不执行破坏性迁移。
