package com.tzy.picturebackend.model.dto.picture;

import com.tzy.picturebackend.constant.PictureTagConstant;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class PictureUpdateRequest implements Serializable {

    /**
     * id
     */
    @NotNull(message = "id 不能为空")
    @Positive(message = "id 必须为正数")
    private Long id;

    /**
     * 图片名称
     */
    @Size(max = 128, message = "图片名称不能超过 128 个字符")
    private String name;

    /**
     * 简介
     */
    @Size(max = 512, message = "简介不能超过 512 个字符")
    private String introduction;

    /**
     * 分类
     */
    @Size(max = 64, message = "分类不能超过 64 个字符")
    private String category;

    /**
     * 标签
     */
    @Size(max = PictureTagConstant.TAG_MAX_COUNT,
            message = "标签最多 " + PictureTagConstant.TAG_MAX_COUNT + " 个")
    private List<@Size(max = PictureTagConstant.TAG_MAX_LENGTH,
            message = "单个标签不能超过 " + PictureTagConstant.TAG_MAX_LENGTH + " 个字符") String> tags;

    private static final long serialVersionUID = 1L;
}

