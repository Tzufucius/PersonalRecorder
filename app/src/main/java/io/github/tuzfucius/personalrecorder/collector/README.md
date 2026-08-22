# collector

本目录负责通知监听、通知字段解析和采集过滤。采集服务只负责把解析后的 `PersonalEvent` 异步交给 Room，不包含网络上传逻辑。
