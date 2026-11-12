package com.tzy.picturebackend.model.dto.picture;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量删除图片请求：删除即移入回收站，保留期内可由管理员恢复。
 */
@Data
public class PictureBatchDeleteRequest implements Serializable {

    /**
     * 待删除图片 id 列表
     */
    @NotEmpty(message = "请选择要删除的图片")
    @Size(max = 100, message = "单次最多删除 100 张图片")
    private List<Long> ids;

    private static final long serialVersionUID = 1L;
}
