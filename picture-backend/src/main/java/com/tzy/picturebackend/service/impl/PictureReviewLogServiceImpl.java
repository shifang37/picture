package com.tzy.picturebackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tzy.picturebackend.mapper.PictureReviewLogMapper;
import com.tzy.picturebackend.model.entity.PictureReviewLog;
import com.tzy.picturebackend.service.PictureReviewLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 图片审核记录服务实现
 */
@Service
@Slf4j
public class PictureReviewLogServiceImpl
        extends ServiceImpl<PictureReviewLogMapper, PictureReviewLog>
        implements PictureReviewLogService {

    @Override
    public void record(Long pictureId, Long reviewerId, Integer reviewStatus, String reviewMessage) {
        try {
            PictureReviewLog reviewLog = new PictureReviewLog();
            reviewLog.setPictureId(pictureId);
            reviewLog.setReviewerId(reviewerId);
            reviewLog.setReviewStatus(reviewStatus);
            reviewLog.setReviewMessage(reviewMessage);
            reviewLog.setCreateTime(new Date());
            boolean result = this.save(reviewLog);
            if (!result) {
                log.warn("保存审核记录失败, pictureId = {}", pictureId);
            }
        } catch (Exception e) {
            // 审核结果已经落库，历史记录失败不能反过来让审核接口报错
            log.error("保存审核记录异常, pictureId = {}", pictureId, e);
        }
    }

    @Override
    public List<PictureReviewLog> listByPictureId(Long pictureId) {
        return this.list(new QueryWrapper<PictureReviewLog>()
                .eq("pictureId", pictureId)
                .orderByDesc("createTime")
                .orderByDesc("id"));
    }
}
