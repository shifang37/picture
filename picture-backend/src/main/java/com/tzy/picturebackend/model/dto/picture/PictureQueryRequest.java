package com.tzy.picturebackend.model.dto.picture;

import com.tzy.picturebackend.common.PageRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
public class PictureQueryRequest extends PageRequest implements Serializable {

    /**
     * id
     */
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
    @Size(max = 20, message = "标签最多 20 个")
    private List<@Size(max = 32, message = "单个标签不能超过 32 个字符") String> tags;

    /**
     * 文件体积
     */
    private Long picSize;

    /**
     * 图片宽度
     */
    private Integer picWidth;

    /**
     * 图片高度
     */
    private Integer picHeight;

    /**
     * 图片比例
     */
    private Double picScale;

    /**
     * 图片格式
     */
    @Size(max = 32, message = "图片格式不能超过 32 个字符")
    private String picFormat;

    /**
     * 搜索词（同时搜名称、简介等）
     */
    @Size(max = 256, message = "搜索词不能超过 256 个字符")
    private String searchText;

    /**
     * 用户 id
     */
    private Long userId;


    /**
     * 状态：0-待审核; 1-通过; 2-拒绝
     */
    @Min(value = 0, message = "审核状态只能是 0、1 或 2")
    @Max(value = 2, message = "审核状态只能是 0、1 或 2")
    private Integer reviewStatus;

    /**
     * 审核信息
     */
    @Size(max = 512, message = "审核意见不能超过 512 个字符")
    private String reviewMessage;

    /**
     * 审核人 id
     */
    private Long reviewerId;


    private static final long serialVersionUID = 1L;
}
