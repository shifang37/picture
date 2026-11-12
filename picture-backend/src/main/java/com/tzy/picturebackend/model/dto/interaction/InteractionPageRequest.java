package com.tzy.picturebackend.model.dto.interaction;

import com.tzy.picturebackend.common.PageRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 「我的收藏 / 我的点赞」分页请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class InteractionPageRequest extends PageRequest implements Serializable {

    /**
     * 互动类型：favorite / like
     */
    @NotBlank(message = "互动类型不能为空")
    private String type;

    private static final long serialVersionUID = 1L;
}
