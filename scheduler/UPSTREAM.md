# db-scheduler 源码来源

- 上游：https://github.com/kagkarlsson/db-scheduler
- 标签：v16.11.0
- 提交：8c3e465d5651f9fcad673ab54863932259b5de64
- 许可证：Apache-2.0，见 LICENSE-db-scheduler；保留各源码文件版权头。
- src/upstream/java 保留上游包名及核心实现，不依赖 db-scheduler 发布制品。
- 未纳入可选 Gson/Jackson 序列化器及 Micrometer 适配，运行数据使用 PAAS JSON。
- 上游 JDBC、java.time 和编码风格仅限第三方源码；本项目适配位于 src/main/java。
