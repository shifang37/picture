package com.tzy.picturebackend.model.dto.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理员发送系统公告
 */
@Data
public class NotificationBroadcastRequest implements Serializable {

    /**
     * 公告标题
     */
    @NotBlank(message = "标题不能为空")
    @Size(max = 128, message = "标题不能超过 128 个字符")
    private String title;

    /**
     * 公告正文
     */
    @NotBlank(message = "正文不能为空")
    @Size(max = 1024, message = "正文不能超过 1024 个字符")
    private String content;

    private static final long serialVersionUID = 1L;
}
