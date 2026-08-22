# collector

负责通知监听服务、字段解析和采集筛选。采集回调只负责快速派发 IO 协程，Room 写入和 DataStore 配置读取不阻塞系统通知回调。
