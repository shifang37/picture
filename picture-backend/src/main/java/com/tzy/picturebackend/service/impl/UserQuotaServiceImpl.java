package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.config.QuotaProperties;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.mapper.UserMapper;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.UserRoleEnum;
import com.tzy.picturebackend.model.vo.UserQuotaVO;
import com.tzy.picturebackend.service.UserQuotaService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 配额实现：所有已用量的增减都通过 {@link UserMapper#reserveQuota} / {@link UserMapper#adjustQuota}
 * 的原子 SQL 完成，不在 Java 侧做“先查再改”，避免并发上传把用量加超。
 */
@Service
@Slf4j
public class UserQuotaServiceImpl implements UserQuotaService {

    /**
     * 配额拒绝类型：存储不足
     */
    static final String REJECT_STORAGE = "storage";

    /**
     * 配额拒绝类型：数量到达上限
     */
    static final String REJECT_COUNT = "count";

    @Resource
    private UserMapper userMapper;

    @Resource
    private QuotaProperties quotaProperties;

    @Resource
    private PictureMetrics pictureMetrics;

    @Override
    public UserQuotaVO getQuota(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户 id 不能为空");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        return toVO(user);
    }

    @Override
    public boolean reserve(User user, long deltaBytes, int deltaPictures) {
        if (user == null || user.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (isExempt(user)) {
            return true;
        }
        return doReserve(user, deltaBytes, deltaPictures);
    }

    @Override
    public boolean reserve(Long userId, long deltaBytes, int deltaPictures) {
        if (userId == null || userId <= 0) {
            return false;
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            log.warn("配额预留失败：用户不存在, userId = {}", userId);
            return false;
        }
        if (isExempt(user)) {
            return true;
        }
        return doReserve(user, deltaBytes, deltaPictures);
    }

    /**
     * 真正执行原子预留；只有真正需要“增加占用”时才区分拒绝原因
     */
    private boolean doReserve(User user, long deltaBytes, int deltaPictures) {
        int updated = userMapper.reserveQuota(user.getId(), deltaBytes, deltaPictures);
        if (updated > 0) {
            return true;
        }
        // 纯释放（delta 都为负）不应该失败；失败说明并发把行删了，直接放行避免影响删除流程
        if (deltaBytes <= 0 && deltaPictures <= 0) {
            log.warn("配额释放未生效, userId = {}, deltaBytes = {}, deltaPictures = {}",
                    user.getId(), deltaBytes, deltaPictures);
            return true;
        }
        String reason = rejectReason(user, deltaBytes);
        pictureMetrics.quotaRejected(reason);
        log.info("配额不足，拒绝上传, userId = {}, reason = {}", user.getId(), reason);
        return false;
    }

    /**
     * 判断拒绝原因（仅用于指标与提示；并发下读取可能略滞后，以 UPDATE 的结果为准）
     */
    private String rejectReason(User user, long deltaBytes) {
        long usedStorage = user.getStorageUsed() == null ? 0L : user.getStorageUsed();
        long storageQuota = user.getStorageQuota() == null ? 0L : user.getStorageQuota();
        if (deltaBytes > 0 && usedStorage + deltaBytes > storageQuota) {
            return REJECT_STORAGE;
        }
        return REJECT_COUNT;
    }

    @Override
    public void release(Long userId, long deltaBytes, int deltaPictures) {
        if (userId == null || userId <= 0) {
            return;
        }
        if (deltaBytes == 0 && deltaPictures == 0) {
            return;
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            log.warn("配额调整跳过：用户不存在, userId = {}", userId);
            return;
        }
        if (isExempt(user)) {
            return;
        }
        userMapper.adjustQuota(userId, deltaBytes, deltaPictures);
    }

    @Override
    public UserQuotaVO updateQuota(Long userId, Long storageQuota, Integer pictureQuota) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户 id 不能为空");
        }
        if (storageQuota == null && pictureQuota == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "至少需要修改一项配额");
        }
        if (storageQuota != null && storageQuota < 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "存储配额不能小于 0");
        }
        if (pictureQuota != null && pictureQuota < 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片数量上限不能小于 0");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        int updated = userMapper.updateQuota(userId, storageQuota, pictureQuota);
        if (updated <= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "调整配额失败");
        }
        log.info("管理员调整配额, userId = {}, storageQuota = {}, pictureQuota = {}",
                userId, storageQuota, pictureQuota);
        return getQuota(userId);
    }

    @Override
    public int recalc(Long userId) {
        if (userId != null) {
            if (userId <= 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户 id 不能为空");
            }
            return userMapper.recalcQuota(userId);
        }
        int batchSize = Math.max(1, quotaProperties.getRecalcBatchSize());
        long afterId = 0L;
        int affected = 0;
        while (true) {
            List<Long> ids = userMapper.selectIdsForQuotaRecalc(afterId, batchSize);
            if (ids == null || ids.isEmpty()) {
                break;
            }
            for (Long id : ids) {
                affected += userMapper.recalcQuota(id);
            }
            afterId = ids.get(ids.size() - 1);
            if (ids.size() < batchSize) {
                break;
            }
        }
        log.info("配额重算完成, userId = {}, affected = {}", userId, affected);
        return affected;
    }

    @Override
    public void applyDefaultQuota(User user) {
        if (user == null) {
            return;
        }
        user.setStorageQuota(quotaProperties.getDefaultStorageBytes());
        user.setStorageUsed(0L);
        user.setPictureQuota(quotaProperties.getDefaultPictureCount());
        user.setPictureUsed(0);
    }

    @Override
    public boolean isExempt(User user) {
        if (!quotaProperties.isEnabled()) {
            return true;
        }
        return quotaProperties.isExemptAdmin() && user != null
                && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }

    private UserQuotaVO toVO(User user) {
        UserQuotaVO vo = new UserQuotaVO();
        vo.setStorageUsed(user.getStorageUsed() == null ? 0L : user.getStorageUsed());
        vo.setStorageQuota(user.getStorageQuota() == null ? 0L : user.getStorageQuota());
        vo.setPictureUsed(user.getPictureUsed() == null ? 0 : user.getPictureUsed());
        vo.setPictureQuota(user.getPictureQuota() == null ? 0 : user.getPictureQuota());
        vo.setExempt(isExempt(user));
        return vo;
    }
}
