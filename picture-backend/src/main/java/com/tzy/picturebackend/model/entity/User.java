package com.tzy.picturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Date;
import lombok.Data;

/**
 * 用户
 * @TableName user
 */
@TableName(value ="user")
@Data
public class User {
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 账号
     */
    private String userAccount;

    /**
     * 密码
     */
    @JsonIgnore
    private String userPassword;

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
     * 是否必须修改初始密码：true-首次登录需改密
     */
    private Boolean passwordChangeRequired;

    /**
     * 编辑时间
     */
    private Date editTime;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 存储配额（字节），默认 1GB
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
     * 是否删除
     */
    @TableLogic
    private Integer isDelete;
}