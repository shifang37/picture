-- 历史库中曾手工添加过单列 idx_reviewStatus（从未出现在任何建表/迁移脚本里），
-- 它已被 idx_review_list(isDelete, reviewStatus, createTime) 覆盖。
-- 用 information_schema 判断后再删：新库没有这个索引，执行空操作。
SET @idx_count := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'picture'
      AND index_name = 'idx_reviewStatus'
);
SET @drop_sql := IF(@idx_count > 0, 'ALTER TABLE picture DROP INDEX idx_reviewStatus', 'DO 0');
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;
