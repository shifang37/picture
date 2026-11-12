-- 图片删除改为「先进回收站，保留期结束后再物理删除」：
--   1. deletedAt 记录移入回收站的时间，到期清理任务据此判断是否超过保留期；
--   2. deletedBy 记录执行删除的用户，便于管理员在回收站里追溯；
--   3. updateTime 会因其它更新而变化，不能用来判断删除时间，因此必须单独存一列。
ALTER TABLE picture
    ADD COLUMN deletedAt datetime NULL COMMENT '移入回收站时间' AFTER isDelete,
    ADD COLUMN deletedBy bigint NULL COMMENT '删除操作人 id' AFTER deletedAt,
    -- 回收站列表（isDelete = 1 ORDER BY deletedAt DESC）与到期清理（isDelete = 1 AND deletedAt < ?）共用
    ADD INDEX idx_recycle_list (isDelete, deletedAt);

-- 兼容升级前已经逻辑删除、但没有 deletedAt 的历史数据：按最后一次更新时间作为删除时间，
-- 避免这些行永远不满足清理条件。
UPDATE picture
SET deletedAt = updateTime
WHERE isDelete = 1
  AND deletedAt IS NULL;
