package com.tzy.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量编辑 / 批量删除结果。
 * <p>
 * 字段与 {@link PictureReviewBatchResult} 保持一致（successCount / failCount / errors），
 * 但不复用同一个 DTO，避免编辑、删除接口被审核语义绑定。
 */
@Data
public class PictureBatchResult implements Serializable {

    /**
     * 成功数量
     */
    private int successCount;

    /**
     * 失败数量
     */
    private int failCount;

    /**
     * 失败原因
     */
    private List<String> errors = new ArrayList<>();

    private static final long serialVersionUID = 1L;
}
