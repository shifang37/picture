package com.tzy.picturebackend.model.dto.picture;

import com.tzy.picturebackend.constant.PictureTagConstant;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量编辑图片请求（分类、标签、简介）。
 * <p>
 * 字段为空表示「不修改」；{@code tags} 传空数组表示清空标签，与单张编辑的语义保持一致。
 * 不提供批量重命名：命名规则不统一，批量覆盖容易误操作。
 */
@Data
public class PictureBatchEditRequest implements Serializable {

    /**
     * 待编辑图片 id 列表
     */
    @NotEmpty(message = "请选择要编辑的图片")
    @Size(max = 100, message = "单次最多编辑 100 张图片")
    private List<Long> ids;

    /**
     * 分类；null 表示不修改
     */
    @Size(max = 64, message = "分类不能超过 64 个字符")
    private String category;

    /**
     * 标签；null 表示不修改，空数组表示清空
     */
    @Size(max = PictureTagConstant.TAG_MAX_COUNT,
            message = "标签最多 " + PictureTagConstant.TAG_MAX_COUNT + " 个")
    private List<@Size(max = PictureTagConstant.TAG_MAX_LENGTH,
            message = "单个标签不能超过 " + PictureTagConstant.TAG_MAX_LENGTH + " 个字符") String> tags;

    /**
     * 简介；null 表示不修改
     */
    @Size(max = 512, message = "简介不能超过 512 个字符")
    private String introduction;

    private static final long serialVersionUID = 1L;
}
