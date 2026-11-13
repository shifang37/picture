-- 文本审核结果（图片名称、简介）：默认只记录，不直接改变人工审核结论（assist 模式）。
-- 文审关闭时四列保持 NULL，应用行为与接入前一致。
ALTER TABLE picture
    ADD COLUMN textReviewStatus tinyint NULL COMMENT '文审状态：0-正常 1-违规 2-疑似' AFTER machineReviewTime,
    ADD COLUMN textReviewLabel varchar(64) NULL COMMENT '文审命中标签（主标签/子标签）' AFTER textReviewStatus,
    ADD COLUMN textReviewScore int NULL COMMENT '文审置信度 0-100' AFTER textReviewLabel,
    ADD COLUMN textReviewTime datetime NULL COMMENT '文审时间' AFTER textReviewScore;
