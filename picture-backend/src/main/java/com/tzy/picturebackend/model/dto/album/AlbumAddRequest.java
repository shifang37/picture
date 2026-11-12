package com.tzy.picturebackend.model.dto.album;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建专辑请求
 */
@Data
public class AlbumAddRequest implements Serializable {

    /**
     * 专辑名称
     */
    @NotBlank(message = "专辑名称不能为空")
    @Size(max = 64, message = "专辑名称不能超过 64 个字符")
    private String name;

    /**
     * 简介
     */
    @Size(max = 512, message = "专辑简介不能超过 512 个字符")
    private String description;

    /**
     * 是否公开：true-公开 false/null-私密
     */
    private Boolean isPublic;

    private static final long serialVersionUID = 1L;
}
