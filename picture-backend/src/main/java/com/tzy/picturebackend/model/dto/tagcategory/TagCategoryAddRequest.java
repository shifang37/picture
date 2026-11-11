package com.tzy.picturebackend.model.dto.tagcategory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 新增标签/分类请求
 */
@Data
public class TagCategoryAddRequest implements Serializable {

    /**
     * 类型：tag/category
     */
    @NotBlank(message = "类型不能为空")
    @Pattern(regexp = "tag|category", message = "类型只能是 tag 或 category")
    private String type;

    /**
     * 名称
     */
    @NotBlank(message = "名称不能为空")
    @Size(max = 64, message = "名称不能超过 64 个字符")
    private String name;

    private static final long serialVersionUID = 1L;
}
