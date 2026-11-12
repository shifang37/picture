package com.tzy.picturebackend.model.dto.album;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 更新专辑请求：字段为空表示不修改。
 */
@Data
public class AlbumUpdateRequest implements Serializable {

    /**
     * 专辑 id
     */
    @NotNull(message = "专辑 id 不能为空")
    @Positive(message = "专辑 id 必须为正数")
    private Long id;

    /**
     * 专辑名称；null 表示不修改
     */
    @Size(max = 64, message = "专辑名称不能超过 64 个字符")
    private String name;

    /**
     * 简介；null 表示不修改
     */
    @Size(max = 512, message = "专辑简介不能超过 512 个字符")
    private String description;

    /**
     * 是否公开：1-公开 0-私密；null 表示不修改
     */
    @Min(value = 0, message = "公开状态只能是 0 或 1")
    @Max(value = 1, message = "公开状态只能是 0 或 1")
    private Integer isPublic;

    /**
     * 封面图片 id；null 表示不修改，必须已经在专辑内
     */
    @Positive(message = "封面图片 id 必须为正数")
    private Long coverPictureId;

    private static final long serialVersionUID = 1L;
}
