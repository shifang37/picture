package com.tzy.picturebackend.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tzy.picturebackend.model.entity.PictureReviewLog;

import java.util.List;

/**
 * 图片审核记录服务
 */
public interface PictureReviewLogService extends IService<PictureReviewLog> {

    /**
     * 记录一次审核，失败只记录日志，不影响审核结果
     */
    void record(Long pictureId, Long reviewerId, Integer reviewStatus, String reviewMessage);

    /**
     * 查询某张图片的审核历史，按时间倒序
     */
    List<PictureReviewLog> listByPictureId(Long pictureId);
}
