-- AI 建议单独存表，不修改用户填写的 picture.name / introduction / category / tags。
-- status：0 处理中、1 成功、2 失败；pictureId 唯一，保证同一张图片只有一条建议。
CREATE TABLE IF NOT EXISTS picture_ai_suggestion
(
    id           bigint auto_increment comment 'id' primary key,
    pictureId    bigint                             not null comment '图片 id',
    name         varchar(128)                       null comment '建议名称',
    introduction varchar(512)                       null comment '建议简介',
    category     varchar(64)                        null comment '建议分类',
    tags         varchar(512)                       null comment '建议标签（JSON 数组）',
    model        varchar(64)                        null comment '模型标识',
    status       tinyint  default 0                 not null comment '状态：0-处理中 1-成功 2-失败',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    UNIQUE KEY uk_pictureId (pictureId)
) comment '图片 AI 建议' collate = utf8mb4_unicode_ci;
