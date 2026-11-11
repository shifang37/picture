-- 已有数据库升级脚本（新装数据库直接执行 create_table.sql 即可）
-- 注意：downloadCount 字段只会新增一次，重复执行会报 duplicate column，可忽略

ALTER TABLE picture
    ADD COLUMN downloadCount bigint DEFAULT 0 NOT NULL COMMENT '下载次数' AFTER picSize;

CREATE TABLE IF NOT EXISTS picture_review_log
(
    id            bigint auto_increment COMMENT 'id' PRIMARY KEY,
    pictureId     bigint                             NOT NULL COMMENT '图片 id',
    reviewerId    bigint                             NOT NULL COMMENT '审核人 id',
    reviewStatus  int                                NOT NULL COMMENT '审核结果：1-通过 2-拒绝',
    reviewMessage varchar(512)                       NULL COMMENT '审核意见',
    createTime    datetime DEFAULT CURRENT_TIMESTAMP NOT NULL COMMENT '审核时间',
    INDEX idx_pictureId (pictureId),
    INDEX idx_reviewerId (reviewerId)
) COMMENT '图片审核记录' COLLATE = utf8mb4_unicode_ci;
