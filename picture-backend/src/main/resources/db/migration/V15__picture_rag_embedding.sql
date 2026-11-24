-- 图片向量化状态：只是派生索引的去重缓存与失败记录，不是事实源；
-- 删表后重新调用 POST /api/rag/reindex 即可恢复，回滚 RAG 时不需要删表。
-- status：0-待处理 1-成功 2-失败；文本与图片两侧独立，可只重算其中一侧。
CREATE TABLE IF NOT EXISTS picture_embedding
(
    pictureId    bigint                             not null comment '图片 id' primary key,
    textHash     char(64)                           null comment '语料 sha256，未变化则跳过重算',
    textModel    varchar(64)                        null comment '文本向量模型',
    textVersion  varchar(32)                        null comment '语料/向量版本，变化即需重建',
    textStatus   tinyint  default 0                 not null comment '文本向量状态：0-待处理 1-成功 2-失败',
    imageHash    char(64)                           null comment '图片内容 hash（秒传 hash 或 url）',
    imageModel   varchar(64)                        null comment '图片向量模型',
    imageVersion varchar(32)                        null comment '图片向量版本',
    imageStatus  tinyint  default 0                 not null comment '图片向量状态：0-待处理 1-成功 2-失败',
    attemptCount int      default 0                 not null comment '连续失败次数',
    lastError    varchar(512)                       null comment '最近一次失败原因',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    KEY idx_status_update (textStatus, imageStatus, updateTime)
) comment '图片向量化状态（派生索引，可整表重建）' collate = utf8mb4_unicode_ci;
