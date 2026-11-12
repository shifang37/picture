package com.tzy.picturebackend.model.vo;

import com.tzy.picturebackend.model.entity.Notification;
import com.tzy.picturebackend.model.enums.NotificationTypeEnum;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.Date;

/**
 * 站内通知（用户视角）
 */
@Data
public class NotificationVO implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 类型：REVIEW_PASS / REVIEW_REJECT / PASSWORD_RESET / ROLE_CHANGED / SYSTEM
     */
    private String type;

    /**
     * 类型中文名，前端直接展示，避免在前端维护一份枚举映射
     */
    private String typeText;

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
     * 业务 id：bizType = PICTURE 时为图片 id
     */
    private Long bizId;

    /**
     * 是否已读
     */
    private Integer isRead;

    /**
     * 创建时间
     */
    private Date createTime;

    private static final long serialVersionUID = 1L;

    /**
     * 实体转 VO；未知类型时 typeText 回退为原始 type，便于排查新增类型未注册枚举
     */
    public static NotificationVO objToVo(Notification notification) {
        if (notification == null) {
            return null;
        }
        NotificationVO notificationVO = new NotificationVO();
        BeanUtils.copyProperties(notification, notificationVO);
        NotificationTypeEnum typeEnum = NotificationTypeEnum.getEnumByValue(notification.getType());
        notificationVO.setTypeText(typeEnum == null ? notification.getType() : typeEnum.getDefaultTitle());
        return notificationVO;
    }
}
