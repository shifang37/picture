-- 标签模型从「picture.tags JSON + LIKE '%"tag"%'」改为关系表 + JOIN：
--   1. 原查询对 tags 做前置通配符 LIKE，无法使用索引；idx_tags 已在 V3 删除；
--   2. 原 DTO 允许 20 个标签 × 单个 32 字符，JSON 总长度上限却是 512，合法请求会被数据库拒绝；
--   3. 标签上限统一为 PictureTagConstant（单个 32 字符、最多 20 个），picture.tags 仅保留为展示/搜索引擎使用的冗余 JSON。
CREATE TABLE IF NOT EXISTS picture_tag
(
    pictureId bigint       NOT NULL COMMENT '图片 id',
    tag       varchar(32)  NOT NULL COMMENT '标签',
    PRIMARY KEY (pictureId, tag),
    -- JOIN / IN 按标签查图片走这个索引，(tag, pictureId) 同时覆盖筛选与回表
    INDEX idx_tag_picture (tag, pictureId)
) comment '图片标签关系' collate = utf8mb4_unicode_ci;

-- 兼容手动重建过 idx_tags 的旧库：TEXT 不能直接作为索引列，先按需删除该索引
SET @drop_idx_tags = (
    SELECT IF(COUNT(*) > 0,
              'ALTER TABLE picture DROP INDEX idx_tags',
              'SET @idx_tags_drop_noop = 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'picture'
      AND index_name = 'idx_tags'
);
PREPARE drop_idx_tags_stmt FROM @drop_idx_tags;
EXECUTE drop_idx_tags_stmt;
DEALLOCATE PREPARE drop_idx_tags_stmt;

-- 冗余 JSON 不再承担 512 字符的总长度限制；权威标签关系在 picture_tag
ALTER TABLE picture
    MODIFY COLUMN tags TEXT NULL COMMENT '标签（JSON 数组，冗余展示；查询以 picture_tag 为准）';

-- 回填历史数据：只导入非空、长度不超限的标签，非法 JSON 用 JSON_ARRAY() 兜底
INSERT IGNORE INTO picture_tag (pictureId, tag)
SELECT p.id, TRIM(jt.tag)
FROM picture p
         JOIN JSON_TABLE(
        IF(JSON_VALID(p.tags), p.tags, JSON_ARRAY()),
        '$[*]' COLUMNS (tag VARCHAR(64) PATH '$')
              ) jt
WHERE p.isDelete = 0
  AND p.tags IS NOT NULL
  AND TRIM(jt.tag) <> ''
  AND CHAR_LENGTH(TRIM(jt.tag)) <= 32
GROUP BY p.id, TRIM(jt.tag);
