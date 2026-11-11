package com.tzy.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量审核结果
 */
@Data
public class PictureReviewBatchResult implements Serializable {

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
