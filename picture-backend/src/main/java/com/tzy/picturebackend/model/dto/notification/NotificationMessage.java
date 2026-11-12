package com.tzy.picturebackend.model.dto.notification;

import com.tzy.picturebackend.model.enums.NotificationTypeEnum;
import lombok.Data;

import java.io.Serializable;

/**
 * 一条待写入的站内通知。
 * <p>
 * 业务代码只构造这个消息并发布 Spring 事件，真正的落库由监听器在事务提交后完成，
 * 因此「通知写失败」不会反过来影响审核、重置密码等主流程。
 */
@Data
public class NotificationMessage implements Serializable {

    /**
     * 通知类型
     */
    private NotificationTypeEnum type;

    /**
     * 接收人 id
     */
    private Long userId;

    /**
     * 标题；为空时使用类型的默认标题
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
     * 业务 id
     */
    private Long bizId;

    /**
     * 幂等键；为空表示不去重
     */
    private String dedupeKey;

    private static final long serialVersionUID = 1L;

    /**
     * 审核结果通知：dedupeKey 按「结论 + 图片」生成，重复投递与机审兜底重发都不会产生第二条
     */
    public static NotificationMessage review(NotificationTypeEnum type, Long userId, Long pictureId,
                                             String pictureName, String reviewMessage) {
        NotificationMessage message = new NotificationMessage();
        message.setType(type);
        message.setUserId(userId);
        message.setBizType(BIZ_TYPE_PICTURE);
        message.setBizId(pictureId);
        message.setDedupeKey(type.getValue() + ":" + pictureId);
        String name = pictureName == null || pictureName.isBlank() ? "未命名图片" : pictureName;
        if (type == NotificationTypeEnum.REVIEW_REJECT) {
            String reason = reviewMessage == null || reviewMessage.isBlank()
                    ? "未填写审核意见，如有疑问请联系管理员"
                    : reviewMessage;
            message.setTitle(type.getDefaultTitle());
            message.setContent("你的图片《" + name + "》未通过审核：" + reason);
        } else {
            message.setTitle(type.getDefaultTitle());
            message.setContent("你的图片《" + name + "》已通过审核，现在可以在首页被浏览了");
        }
        return message;
    }

    /**
     * 账号安全通知（密码重置、角色变更）；每次事件都应提醒，不做去重
     */
    public static NotificationMessage account(NotificationTypeEnum type, Long userId, String content) {
        NotificationMessage message = new NotificationMessage();
        message.setType(type);
        message.setUserId(userId);
        message.setBizType(BIZ_TYPE_USER);
        message.setBizId(userId);
        message.setTitle(type.getDefaultTitle());
        message.setContent(content);
        return message;
    }

    /**
     * 系统公告；按用户各写一行，dedupeKey 为空表示每次发送都要提醒
     */
    public static NotificationMessage system(Long userId, String title, String content) {
        NotificationMessage message = new NotificationMessage();
        message.setType(NotificationTypeEnum.SYSTEM);
        message.setUserId(userId);
        message.setBizType(BIZ_TYPE_SYSTEM);
        message.setTitle(title);
        message.setContent(content);
        return message;
    }

    public static final String BIZ_TYPE_PICTURE = "PICTURE";

    public static final String BIZ_TYPE_USER = "USER";

    public static final String BIZ_TYPE_SYSTEM = "SYSTEM";
}
