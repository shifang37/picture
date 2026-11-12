-- M2 / F6：图片专辑。
--   1. album 是专辑主体，isDelete 支持逻辑删除；pictureCount 是作者视角的冗余总数（含未过审）；
--   2. album_picture 是纯关系表，不做逻辑删除：移除即物理删除，恢复专辑不影响图片本身；
--   3. 公开页只统计/展示 reviewStatus = 1 的图片，publicCount 在查询时另行计算，避免暴露未过审数量。
CREATE TABLE IF NOT EXISTS album
(
    id             bigint auto_increment comment 'id' primary key,
    userId         bigint       NOT NULL comment '作者 id',
    name           varchar(64)  NOT NULL comment '专辑名称',
    description    varchar(512) NULL comment '简介',
    coverPictureId bigint       NULL comment '封面图片 id，空表示按最新公开图片回退',
    pictureCount   int          NOT NULL DEFAULT 0 comment '图片数量（作者视角，含未过审）',
    isPublic       tinyint      NOT NULL DEFAULT 0 comment '0-私密 1-公开',
    createTime     datetime     DEFAULT CURRENT_TIMESTAMP NOT NULL comment '创建时间',
    editTime       datetime     DEFAULT CURRENT_TIMESTAMP NOT NULL comment '编辑时间',
    updateTime     datetime     DEFAULT CURRENT_TIMESTAMP NOT NULL ON UPDATE CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint      NOT NULL DEFAULT 0 comment '是否删除：0-未删除 1-已删除',
    -- 我的专辑：按作者 + 时间倒序
    KEY idx_album_user (userId, isDelete, createTime),
    -- 公开专辑列表：等值过滤后按时间倒序
    KEY idx_album_public (isPublic, isDelete, createTime)
) comment '图片专辑' collate = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS album_picture
(
    albumId    bigint   NOT NULL comment '专辑 id',
    pictureId  bigint   NOT NULL comment '图片 id',
    createTime datetime DEFAULT CURRENT_TIMESTAMP NOT NULL comment '加入时间',
    -- 幂等：同一张图片在同一专辑里只有一行
    PRIMARY KEY (albumId, pictureId),
    -- 按图片反查所属专辑 / 物理删除图片时的级联清理
    KEY idx_album_picture_picture (pictureId)
) comment '专辑-图片关系' collate = utf8mb4_unicode_ci;
