# Personal Recorder

[English](README.md) | [简体中文](README.zh-CN.md)

Personal Recorder 是一款本地优先的 Android 通知记录应用。应用在设备上采集通知事件，使用 Room 保存实时数据，并可写入长期 JSONL 归档。用户可以选择 GitHub 私有仓库作为远端归档和恢复源；Room 数据库不会被上传。

## 功能

- 使用通知监听服务采集通知，并过滤 ongoing 和群组摘要通知。
- 使用 Room 本地保存实时记录，支持快速浏览。
- 按应用、日期、小时，以及允许/排除列表筛选。
- 支持今天、近 7 天和近 30 天统计。
- 固定 24 小时应用堆叠图，展示每个小时的应用来源。
- 以半日为单位写入 JSONL，形成长期、可迁移的归档。
- 可选 GitHub 私有仓库同步，支持恢复、冲突处理和进度展示。
- 提供通知权限、电池限制和厂商后台限制诊断。
- 提供英文和简体中文资源，日期和数量格式遵循系统语言环境。

## 功能截图

以下截图来自当前 Debug APK 和已连接设备。提交前已在本地对通知正文以及账号和仓库标识进行了打码。

| 记录 | 统计 | 设置 |
| --- | --- | --- |
| ![记录](docs/images/records.png) | ![统计](docs/images/statistics.png) | ![设置](docs/images/settings.png) |

## 数据与同步

Room 是设备上的实时数据源。归档器按半日导出 JSONL 文件，每日 manifest 描述可用的归档分片。GitHub 同步是可选功能，使用私有仓库作为归档和恢复端点。恢复流程会在解决冲突前保留本地数据，不会改变归档格式和通知采集规则。

## 隐私与安全

通知正文和元数据默认保留在设备上，除非用户配置了归档远端。请使用 GitHub 私有仓库并妥善保护访问令牌，分享前检查归档内容。当前应用不会对归档文件提供端到端加密，因此仓库权限仍然十分重要。

## 限制

- Android 通知访问权限和厂商电池策略会影响采集可靠性。
- 后台任务受 Android 和设备厂商调度限制影响。
- GitHub 同步需要配置私有仓库并具备网络连接。
- 当前版本没有应用内语言切换器，界面语言由 Android 系统区域设置决定。

## 构建与运行

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

将生成的 `app/build/outputs/apk/debug/app-debug.apk` 安装到 Android 设备，授予通知访问权限；只有需要远端同步时才配置归档设置。

## 验证情况

本次修改已执行以上命令。JVM 测试、Debug 构建和 21 个连接设备测试均在已连接的华为 ADY-AL00 上成功完成。已通过 ADB 安装 Debug APK 并打开记录、统计、设置三个主页面，在应用进程日志中未观察到 Personal Recorder 的 `FATAL EXCEPTION`、`NoSuchMethodError` 或 Vico 崩溃。

## 路线图

- 改进归档检查和冲突审阅流程。
- 增加针对 OEM 后台限制的诊断信息。
- 持续扩展本地化覆盖和无障碍验证。
