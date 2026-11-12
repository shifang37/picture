-- M4 / F10：用户配额与文件哈希（去重）。
--   1. 配额列放在 user 上：默认 1GB / 1000 张，管理员可通过 /api/user/quota/update 调整；
--   2. storageUsed / pictureUsed 只统计未删除图片（isDelete = 0），软删除立即释放额度、
--      恢复时重新占用；回收站保留期内 COS 仍占用存储，因此配额不等于 COS 实际用量；
--   3. picture.fileHash 存文件 SHA-256（上传时对最终存储内容计算），用于同一用户的秒传；
--      历史数据为 NULL，NULL 永远不会命中 fileHash = ?，无需强制回填即可工作。
ALTER TABLE user
    ADD COLUMN storageQuota bigint NOT NULL DEFAULT 1073741824 COMMENT '存储配额（字节），默认 1GB' AFTER isDelete,
    ADD COLUMN storageUsed  bigint NOT NULL DEFAULT 0 COMMENT '已用存储（字节，仅统计未删除图片）' AFTER storageQuota,
    ADD COLUMN pictureQuota int    NOT NULL DEFAULT 1000 COMMENT '图片数量上限' AFTER storageUsed,
    ADD COLUMN pictureUsed  int    NOT NULL DEFAULT 0 COMMENT '已用图片数量（仅统计未删除图片）' AFTER pictureQuota;

ALTER TABLE picture
    ADD COLUMN fileHash char(64) NULL COMMENT '文件 SHA-256（上传时计算，用于同用户秒传）' AFTER picFormat,
    -- 秒传查询：同一用户 + 同一哈希 + 未删除
    ADD INDEX idx_user_hash (userId, fileHash);

-- 回填历史用量，避免老用户显示 0 已用、被误判为还有全额空间。
-- 数据量大时可按 id 分段执行，发布完成后也可以调用 POST /api/user/quota/recalc 重算。
UPDATE user u
SET storageUsed = (SELECT COALESCE(SUM(p.picSize), 0)
                   FROM picture p
                   WHERE p.userId = u.id
                     AND p.isDelete = 0),
    pictureUsed = (SELECT COUNT(*)
                   FROM picture p
                   WHERE p.userId = u.id
                     AND p.isDelete = 0);
