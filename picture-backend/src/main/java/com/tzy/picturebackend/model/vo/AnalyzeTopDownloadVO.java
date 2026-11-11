package com.tzy.picturebackend.model.vo;

import lombok.Data;

/**
 * 管理端看板：下载次数排行
 */
@Data
public class AnalyzeTopDownloadVO {

    /**
     * 图片 id
     */
    private Long id;

    /**
     * 图片名称
     */
    private String name;

    /**
     * 图片地址
     */
    private String url;

    /**
     * 下载次数
     */
    private Long downloadCount;
}
