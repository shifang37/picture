-- Flyway 基线 V1：与接入 Flyway 之前的建表脚本（upgrade_v3 之后的完整结构）保持一致。
-- 空库首次启动时执行本文件；已有表但没有 Flyway 历史表的旧库会被 baseline 到版本 1，跳过本文件。
-- 注意：迁移一旦执行过就不能再修改内容（校验和会变化），后续变更请新增 V2、V3……

-- 用户表
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    userName     varchar(256)                           null comment '用户昵称',
    userAvatar   varchar(1024)                          null comment '用户头像',
    userProfile  varchar(512)                           null comment '用户简介',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin',
    editTime     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName)
    ) comment '用户' collate = utf8mb4_unicode_ci;


-- 图片表
create table if not exists picture
(
    id           bigint auto_increment comment 'id' primary key,
    url          varchar(512)                       not null comment '图片 url',
    name         varchar(128)                       not null comment '图片名称',
    introduction varchar(512)                       null comment '简介',
    category     varchar(64)                        null comment '分类',
    tags         varchar(512)                      null comment '标签（JSON 数组）',
    picSize      bigint                             null comment '图片体积',
    downloadCount bigint   default 0                not null comment '下载次数',
    picWidth     int                                null comment '图片宽度',
    picHeight    int                                null comment '图片高度',
    picScale     double                             null comment '图片宽高比例',
    picFormat    varchar(32)                        null comment '图片格式',
    userId       bigint                             not null comment '创建用户 id',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    editTime     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    reviewStatus int      default 0                 not null comment '审核状态：0-待审核 1-通过 2-拒绝',
    reviewMessage varchar(512)                      null comment '审核信息',
    reviewerId   bigint                             null comment '审核人 id',
    reviewTime   datetime                           null comment '审核时间',
    INDEX idx_name (name),                 -- 提升基于图片名称的查询性能
    INDEX idx_introduction (introduction), -- 用于模糊搜索图片简介
    INDEX idx_category (category),         -- 提升基于分类的查询性能
    INDEX idx_tags (tags),                 -- 提升基于标签的查询性能
    INDEX idx_userId (userId)              -- 提升基于用户 ID 的查询性能
    ) comment '图片' collate = utf8mb4_unicode_ci;


-- 图片审核记录表
create table if not exists picture_review_log
(
    id            bigint auto_increment comment 'id' primary key,
    pictureId     bigint                             not null comment '图片 id',
    reviewerId    bigint                             not null comment '审核人 id',
    reviewStatus  int                                not null comment '审核结果：1-通过 2-拒绝',
    reviewMessage varchar(512)                       null comment '审核意见',
    createTime    datetime default CURRENT_TIMESTAMP not null comment '审核时间',
    INDEX idx_pictureId (pictureId),
    INDEX idx_reviewerId (reviewerId)
    ) comment '图片审核记录' collate = utf8mb4_unicode_ci;


-- 标签/分类字典表（由管理员维护，上传页与首页从这里读取选项）
create table if not exists tag_category
(
    id         bigint auto_increment comment 'id' primary key,
    type       varchar(16)                          not null comment '类型：tag/category',
    name       varchar(64)                          not null comment '名称',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    unique key uk_type_name (type, name)
    ) comment '标签/分类字典' collate = utf8mb4_unicode_ci;
