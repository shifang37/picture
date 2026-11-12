package com.tzy.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class UserVO implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 账号
     */
    private String userAccount;

    /**
     * 用户昵称
     */
    private String userName;

    /**
     * 用户头像
     */
    private String userAvatar;

    /**
     * 用户简介
     */
    private String userProfile;

    /**
     * 用户角色：user/admin
     */
    private String userRole;

    /**
     * 存储配额（字节）
     */
    private Long storageQuota;

    /**
     * 已用存储（字节，只统计未删除图片）
     */
    private Long storageUsed;

    /**
     * 图片数量上限
     */
    private Integer pictureQuota;

    /**
     * 已用图片数量（只统计未删除图片）
     */
    private Integer pictureUsed;

    /**
     * 创建时间
     */
    private Date createTime;

    private static final long serialVersionUID = 1L;
}

