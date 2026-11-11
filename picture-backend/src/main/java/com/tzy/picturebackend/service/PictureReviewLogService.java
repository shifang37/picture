package com.tzy.picturebackend.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tzy.picturebackend.model.entity.PictureReviewLog;
import com.tzy.picturebackend.model.vo.PictureReviewLogVO;

import java.util.List;

/**
 * 图片审核记录服务
 */
public interface PictureReviewLogService extends IService<PictureReviewLog> {

    /**
     * 系统机审写入审核历史时使用的审核人 id。
     * <p>
     * picture_review_log.reviewerId 非空，用 0 表示这条记录不是真人审核。
     */
    long SYSTEM_REVIEWER_ID = 0L;

    /**
     * 记录一次审核，失败只记录日志，不影响审核结果
     */
    void record(Long pictureId, Long reviewerId, Integer reviewStatus, String reviewMessage);

    /**
     * 查询某张图片的审核历史，按时间倒序
     */
    List<PictureReviewLog> listByPictureId(Long pictureId);

    /**
     * 查询某张图片的审核历史，并补全审核人昵称/账号，按时间倒序
     *
     * @param pictureId 图片 id
     * @return 含审核人信息的审核历史
     */
    List<PictureReviewLogVO> listVOByPictureId(Long pictureId);
}
