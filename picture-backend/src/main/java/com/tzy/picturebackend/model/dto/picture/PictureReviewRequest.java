package com.tzy.picturebackend.model.dto.picture;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class PictureReviewRequest implements Serializable {

    /**
     * id
     */
    @NotNull(message = "id 不能为空")
    @Positive(message = "id 必须为正数")
    private Long id;

    /**
     * 状态：0-待审核, 1-通过, 2-拒绝
     */
    @NotNull(message = "审核状态不能为空")
    @Min(value = 1, message = "审核状态只能是通过或拒绝")
    @Max(value = 2, message = "审核状态只能是通过或拒绝")
    private Integer reviewStatus;

    /**
     * 审核信息
     */
    @Size(max = 512, message = "审核意见不能超过 512 个字符")
    private String reviewMessage;


    private static final long serialVersionUID = 1L;
}
