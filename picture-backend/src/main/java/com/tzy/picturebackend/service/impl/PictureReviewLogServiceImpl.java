package com.tzy.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tzy.picturebackend.mapper.PictureReviewLogMapper;
import com.tzy.picturebackend.model.entity.PictureReviewLog;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.PictureReviewLogVO;
import com.tzy.picturebackend.service.PictureReviewLogService;
import com.tzy.picturebackend.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 图片审核记录服务实现
 */
@Service
@Slf4j
public class PictureReviewLogServiceImpl
        extends ServiceImpl<PictureReviewLogMapper, PictureReviewLog>
        implements PictureReviewLogService {

    /**
     * 审核人已被删除时的展示回退
     */
    private static final String DELETED_REVIEWER_PREFIX = "用户#";

    /**
     * 系统机审的展示名称
     */
    private static final String SYSTEM_REVIEWER_NAME = "系统机审";

    /**
     * 系统机审的展示账号
     */
    private static final String SYSTEM_REVIEWER_ACCOUNT = "system";

    @Resource
    private UserService userService;

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

    @Override
    public List<PictureReviewLogVO> listVOByPictureId(Long pictureId) {
        List<PictureReviewLog> reviewLogs = listByPictureId(pictureId);
        if (CollUtil.isEmpty(reviewLogs)) {
            return new ArrayList<>();
        }
        // 批量补全审核人，避免逐条查库；0 是系统机审，不对应真实用户
        Set<Long> reviewerIds = reviewLogs.stream()
                .map(PictureReviewLog::getReviewerId)
                .filter(Objects::nonNull)
                .filter(reviewerId -> reviewerId > 0)
                .collect(Collectors.toSet());
        Map<Long, User> reviewerMap = reviewerIds.isEmpty()
                ? Map.of()
                : userService.listByIds(reviewerIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity(), (first, second) -> first));
        List<PictureReviewLogVO> result = new ArrayList<>(reviewLogs.size());
        for (PictureReviewLog reviewLog : reviewLogs) {
            result.add(toVO(reviewLog, reviewerMap.get(reviewLog.getReviewerId())));
        }
        return result;
    }

    /**
     * 实体转 VO：审核人优先展示昵称，其次账号；用户已删除时回退为「用户#id」
     */
    private PictureReviewLogVO toVO(PictureReviewLog reviewLog, User reviewer) {
        PictureReviewLogVO vo = new PictureReviewLogVO();
        vo.setId(reviewLog.getId());
        vo.setPictureId(reviewLog.getPictureId());
        vo.setReviewerId(reviewLog.getReviewerId());
        vo.setReviewStatus(reviewLog.getReviewStatus());
        vo.setReviewMessage(reviewLog.getReviewMessage());
        vo.setCreateTime(reviewLog.getCreateTime());
        if (Objects.equals(reviewLog.getReviewerId(), PictureReviewLogService.SYSTEM_REVIEWER_ID)) {
            vo.setReviewerName(SYSTEM_REVIEWER_NAME);
            vo.setReviewerAccount(SYSTEM_REVIEWER_ACCOUNT);
        } else if (reviewer != null) {
            vo.setReviewerAccount(reviewer.getUserAccount());
            vo.setReviewerName(StrUtil.isNotBlank(reviewer.getUserName())
                    ? reviewer.getUserName()
                    : reviewer.getUserAccount());
        } else if (reviewLog.getReviewerId() != null) {
            vo.setReviewerName(DELETED_REVIEWER_PREFIX + reviewLog.getReviewerId());
        }
        return vo;
    }
}
