-- M1 / F1 + F4：收藏与点赞的数据模型，同时为热度排序提供索引。
--   1. picture 上增加冗余计数列：列表展示与排序直接读它，避免每次 COUNT 关系表；
--   2. picture_interaction 是权威关系数据，同一用户对同一图片的同一类型只允许一条；
--   3. 计数列只由关系表的增删驱动（同事务），并提供管理端重算入口修正漂移。
ALTER TABLE picture
    ADD COLUMN favoriteCount bigint NOT NULL DEFAULT 0 COMMENT '收藏数' AFTER downloadCount,
    ADD COLUMN likeCount     bigint NOT NULL DEFAULT 0 COMMENT '点赞数' AFTER favoriteCount,
    -- 公开列表按收藏/点赞/下载排序：等值过滤（isDelete + reviewStatus）后直接读有序索引，避免 filesort
    ADD INDEX idx_review_favorite (isDelete, reviewStatus, favoriteCount),
    ADD INDEX idx_review_like (isDelete, reviewStatus, likeCount),
    ADD INDEX idx_review_download (isDelete, reviewStatus, downloadCount);

CREATE TABLE IF NOT EXISTS picture_interaction
(
    id         bigint auto_increment comment 'id' primary key,
    pictureId  bigint      NOT NULL comment '图片 id',
    userId     bigint      NOT NULL comment '用户 id',
    type       varchar(16) NOT NULL comment '互动类型：favorite-收藏 like-点赞',
    createTime datetime DEFAULT CURRENT_TIMESTAMP NOT NULL comment '创建时间',
    -- 幂等兜底：并发重复点击只有一行能插入成功，计数不会被重复累加
    UNIQUE KEY uk_picture_user_type (pictureId, userId, type),
    -- 「我的收藏 / 我的点赞」：按用户 + 类型 + 时间倒序
    KEY idx_user_type_time (userId, type, createTime),
    -- 统计与物理删除时的级联清理
    KEY idx_picture_type (pictureId, type)
) comment '图片互动（收藏/点赞）' collate = utf8mb4_unicode_ci;
