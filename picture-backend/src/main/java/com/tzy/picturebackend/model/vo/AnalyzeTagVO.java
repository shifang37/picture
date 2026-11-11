package com.tzy.picturebackend.model.vo;

import lombok.Data;

/**
 * 管理端看板：标签使用次数
 */
@Data
public class AnalyzeTagVO {

    /**
     * 标签名称
     */
    private String tag;

    /**
     * 使用该标签的图片数量
     */
    private Long count;
}
