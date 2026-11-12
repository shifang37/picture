package com.tzy.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户配额视图：只包含“已用/上限”，不暴露内部字段。
 */
@Data
public class UserQuotaVO implements Serializable {

    /**
     * 已用存储（字节，只统计未删除图片）
     */
    private Long storageUsed;

    /**
     * 存储配额（字节）
     */
    private Long storageQuota;

    /**
     * 已用图片数量（只统计未删除图片）
     */
    private Integer pictureUsed;

    /**
     * 图片数量上限
     */
    private Integer pictureQuota;

    /**
     * 是否不受配额限制（管理员且开启 exempt-admin，或全局关闭配额）
     */
    private Boolean exempt;

    private static final long serialVersionUID = 1L;
}
