# legacy 升级脚本（仅历史库使用）

这里的脚本用于把**接入 Flyway 之前**创建、结构尚未升级到 v3 的数据库补齐到基线：

1. `upgrade_v2.sql`：为 `picture` 增加 `downloadCount` 字段，创建 `picture_review_log` 表；
2. `upgrade_v3.sql`：创建 `tag_category` 字典表，并从现有图片初始化分类/标签。

使用方式：

- 只适用于没有 `flyway_schema_history` 表、且结构落后于基线的旧库；
- 按 v2 → v3 的顺序各执行一次，然后启动后端；
- 启动后 Flyway 会把该库标记为基线版本 1，之后的表结构变更全部由
  `picture-backend/src/main/resources/db/migration` 下的迁移脚本自动完成，**不要再手工执行 SQL**。

新装数据库不需要这些脚本：启动后端时 Flyway 会执行 `V1__baseline.sql` 建表。
