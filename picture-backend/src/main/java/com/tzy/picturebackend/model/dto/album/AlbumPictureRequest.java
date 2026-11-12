package com.tzy.picturebackend.model.dto.album;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 专辑加图 / 移图请求。
 * <p>
 * 两个接口的请求结构一致，共用同一个 DTO；单次最多 100 张，重复图片自动跳过（幂等）。
 */
@Data
public class AlbumPictureRequest implements Serializable {

    /**
     * 专辑 id
     */
    @NotNull(message = "专辑 id 不能为空")
    @Positive(message = "专辑 id 必须为正数")
    private Long albumId;

    /**
     * 图片 id 列表
     */
    @NotEmpty(message = "请选择要操作的图片")
    @Size(max = 100, message = "单次最多操作 100 张图片")
    private List<Long> pictureIds;

    private static final long serialVersionUID = 1L;
}
