package com.tzy.picturebackend.model.vo;

import lombok.Data;

/**
 * 管理端看板：按天上传数量
 */
@Data
public class AnalyzeUploadTrendVO {

    /**
     * 日期，格式 yyyy-MM-dd
     */
    private String date;

    /**
     * 当天上传数量
     */
    private Long count;
}
