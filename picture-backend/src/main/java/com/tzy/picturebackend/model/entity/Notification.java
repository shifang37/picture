package com.tzy.picturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 站内通知。
 * <p>
 * 通知是「事件快照」，不引用其它表做外键：图片被物理删除后，通知仍然可以展示
 * （点击时由前端提示内容已不可访问）。删除通知是物理删除，同一个 dedupeKey 之后仍可再次插入。
 */
@TableName("notification")
@Data
public class Notification implements Serializable {

    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 接收人 id
     */
    private Long userId;

    /**
     * 类型：见 {@link com.tzy.picturebackend.model.enums.NotificationTypeEnum}
     */
    private String type;

    /**
     * 标题
     */
    private String title;

    /**
     * 正文
     */
    private String content;

    /**
     * 业务类型：PICTURE / USER / SYSTEM
     */
    private String bizType;

    /**
     * 业务 id（图片 id、用户 id），点击后跳转用
     */
    private Long bizId;

    /**
     * 幂等键；NULL 表示不去重
     */
    private String dedupeKey;

    /**
     * 是否已读：0-未读 1-已读
     */
    private Integer isRead;

    /**
     * 创建时间
     */
    private Date createTime;

    private static final long serialVersionUID = 1L;
}
