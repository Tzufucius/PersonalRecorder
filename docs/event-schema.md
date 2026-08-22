# PersonalEvent 字段说明

`PersonalEvent` 是 Android 通知进入 Personal Recorder 后的统一业务模型。数据库使用 `EventEntity` 保存同名字段。

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `id` | String | UUID，事件唯一标识 |
| `timestamp` | Long | 通知产生时间，Unix epoch milliseconds |
| `source` | String | 来源，当前固定为 `notification` |
| `packageName` | String | 发送通知的 Android package |
| `title` | String? | `Notification.EXTRA_TITLE` |
| `content` | String? | 按 `bigText`、`text`、`textLines` 顺序选择的主要正文 |
| `bigText` | String? | `Notification.EXTRA_BIG_TEXT` |
| `textLines` | List<String> | `Notification.EXTRA_TEXT_LINES` 的多行正文 |
| `notificationKey` | String | Android notification key |
| `notificationId` | Int | Android notification id |
| `category` | String? | Android 通知类别 |
| `channelId` | String? | Android 通知渠道 ID |
| `groupKey` | String? | Android 通知分组 key |
| `isOngoing` | Boolean | 是否为持续通知 |
| `isGroupSummary` | Boolean | 是否为通知组摘要 |
| `isClearable` | Boolean | 是否可被用户清除 |
| `createdAt` | Long | 本地保存时间，Unix epoch milliseconds |

同一个 notification key 的后续更新会追加为新事件，不进行聊天语义去重。通知移除回调不会产生新事件。

## 统计口径

- 原始事件：数据库中保存的所有通知事件。
- 有效统计事件：排除自身应用、当前应用筛选之外的包、`isOngoing` 和 `isGroupSummary` 后的事件。
- 逻辑消息：本项目当前不推断聊天消息，也不做语义去重。
