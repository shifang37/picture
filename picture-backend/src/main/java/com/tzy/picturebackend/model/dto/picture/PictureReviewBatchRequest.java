package com.tzy.picturebackend.model.dto.picture;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量审核请求
 */
@Data
public class PictureReviewBatchRequest implements Serializable {

    /**
     * 待审核图片 id 列表
     */
    @NotEmpty(message = "请选择要审核的图片")
    @Size(max = 100, message = "单次最多审核 100 张图片")
    private List<Long> ids;

    /**
     * 审核结果：1-通过 2-拒绝
     */
    @NotNull(message = "审核状态不能为空")
    @Min(value = 1, message = "审核状态只能是通过或拒绝")
    @Max(value = 2, message = "审核状态只能是通过或拒绝")
    private Integer reviewStatus;

    /**
     * 审核意见
     */
    @Size(max = 512, message = "审核意见不能超过 512 个字符")
    private String reviewMessage;

    private static final long serialVersionUID = 1L;
}
