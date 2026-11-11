-- 已有数据库升级脚本 v3：标签/分类从只读聚合升级为可管理字典
-- 新装数据库直接执行 create_table.sql 即可。
-- 本脚本可重复执行：建表使用 IF NOT EXISTS，初始化使用 INSERT IGNORE。

CREATE TABLE IF NOT EXISTS tag_category
(
    id         bigint auto_increment COMMENT 'id' PRIMARY KEY,
    type       varchar(16)                          NOT NULL COMMENT '类型：tag/category',
    name       varchar(64)                          NOT NULL COMMENT '名称',
    createTime datetime DEFAULT CURRENT_TIMESTAMP NOT NULL COMMENT '创建时间',
    UNIQUE KEY uk_type_name (type, name)
) COMMENT '标签/分类字典' COLLATE = utf8mb4_unicode_ci;

-- 从现有图片初始化分类
INSERT IGNORE INTO tag_category(type, name)
SELECT 'category', TRIM(category)
FROM picture
WHERE isDelete = 0
  AND category IS NOT NULL
  AND TRIM(category) <> ''
  AND CHAR_LENGTH(TRIM(category)) <= 64
GROUP BY TRIM(category);

-- 从现有图片初始化标签（tags 为 JSON 数组）
INSERT IGNORE INTO tag_category(type, name)
SELECT 'tag', TRIM(jt.tag)
FROM picture,
     JSON_TABLE(picture.tags, '$[*]' COLUMNS (tag VARCHAR(64) PATH '$')) jt
WHERE picture.isDelete = 0
  AND picture.tags IS NOT NULL
  AND JSON_VALID(picture.tags)
  AND jt.tag IS NOT NULL
  AND TRIM(jt.tag) <> ''
GROUP BY TRIM(jt.tag);
