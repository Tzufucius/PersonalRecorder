# data

包含 PersonalEvent 业务模型、Room 实体、DAO、数据库迁移和统计投影。通知正文只存储在本地数据库。

数据库版本 4 新增 `archive_conflicts`，用于保留同一归档路径中无法静默合并的事件冲突。归档恢复使用 `insertEventsIgnore` 和事务，不会因重复 event ID 产生重复记录，也不使用 destructive migration。
