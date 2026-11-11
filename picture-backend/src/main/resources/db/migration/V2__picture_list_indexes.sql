-- 列表查询联合索引：三个列表都按 createTime 倒序分页，原有索引没有覆盖排序字段。
-- 首页 / 审核页：WHERE isDelete = 0 AND reviewStatus = ? ORDER BY createTime DESC
-- 我的图片：   WHERE isDelete = 0 AND userId = ?       ORDER BY createTime DESC
-- 两个索引都让「等值过滤 + 按 createTime 排序」走同一棵 B-tree，避免 filesort。
ALTER TABLE picture
    ADD INDEX idx_review_list (isDelete, reviewStatus, createTime),
    ADD INDEX idx_user_list (userId, isDelete, createTime);
