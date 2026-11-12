package com.tzy.picturebackend.model.dto.interaction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 收藏 / 点赞请求。
 * <p>
 * {@code type} 只接受 {@code favorite} 与 {@code like}，由 service 转成枚举校验；
 * 不在这里用 {@code @Pattern} 约束，是为了让非法类型返回统一的「类型不合法」业务码。
 */
@Data
public class InteractionRequest implements Serializable {

    /**
     * 图片 id
     */
    @NotNull(message = "图片 id 不能为空")
    @Positive(message = "图片 id 必须为正数")
    private Long pictureId;

    /**
     * 互动类型：favorite / like
     */
    @NotBlank(message = "互动类型不能为空")
    private String type;

    private static final long serialVersionUID = 1L;
}
