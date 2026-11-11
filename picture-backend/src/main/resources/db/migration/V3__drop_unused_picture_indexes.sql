-- 删除对实际查询无效或已被覆盖的索引（删除前已在造数库用 EXPLAIN ANALYZE 确认）：
-- idx_introduction / idx_tags 只服务前置通配符 LIKE '%x%'，B-tree 用不上，搜索仍走全表扫描；
-- idx_userId 被 idx_user_list(userId, isDelete, createTime) 的最左前缀覆盖。
ALTER TABLE picture
    DROP INDEX idx_introduction,
    DROP INDEX idx_tags,
    DROP INDEX idx_userId;
