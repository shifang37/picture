-- 机审初筛结果：默认只记录，不直接改变人工审核结论（assist 模式）。
-- 机审关闭时四列保持 NULL，应用行为与接入前一致。
ALTER TABLE picture
    ADD COLUMN machineReviewStatus tinyint NULL COMMENT '机审状态：0-正常 1-违规 2-疑似' AFTER reviewTime,
    ADD COLUMN machineReviewLabel varchar(64) NULL COMMENT '机审命中标签（主标签/子标签）' AFTER machineReviewStatus,
    ADD COLUMN machineReviewScore int NULL COMMENT '机审置信度 0-100' AFTER machineReviewLabel,
    ADD COLUMN machineReviewTime datetime NULL COMMENT '机审时间' AFTER machineReviewScore;
