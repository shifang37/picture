-- M1 / F2：站内通知。
--   1. 审核结果、密码重置、角色变更、系统公告共用一张表，用 type 区分；
--   2. dedupeKey + 唯一索引保证「同一图片的同一审核结论只通知一次」，重复投递 / 机审兜底重发都不会刷屏；
--      dedupeKey 允许 NULL，而 MySQL 唯一索引允许多个 NULL，因此密码重置、公告每次都提醒；
--   3. 列表与未读数共用 (userId, isRead, createTime) 索引。
CREATE TABLE IF NOT EXISTS notification
(
    id         bigint auto_increment comment 'id' primary key,
    userId     bigint        NOT NULL comment '接收人 id',
    type       varchar(32)   NOT NULL comment '类型：REVIEW_PASS / REVIEW_REJECT / PASSWORD_RESET / ROLE_CHANGED / SYSTEM',
    title      varchar(128)  NOT NULL comment '标题',
    content    varchar(1024) NULL comment '正文（拒绝原因最长 512，加前缀后留余量）',
    bizType    varchar(32)   NULL comment '业务类型：PICTURE / USER / SYSTEM，点击跳转用',
    bizId      bigint        NULL comment '业务 id',
    dedupeKey  varchar(128)  NULL comment '幂等键，如 REVIEW_PASS:123；NULL 表示不去重',
    isRead     tinyint       NOT NULL DEFAULT 0 comment '是否已读：0-未读 1-已读',
    createTime datetime      DEFAULT CURRENT_TIMESTAMP NOT NULL comment '创建时间',
    UNIQUE KEY uk_user_dedupe (userId, dedupeKey),
    KEY idx_user_read_time (userId, isRead, createTime)
) comment '站内通知' collate = utf8mb4_unicode_ci;
