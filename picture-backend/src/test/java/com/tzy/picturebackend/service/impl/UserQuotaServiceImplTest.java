package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.config.QuotaProperties;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.mapper.UserMapper;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.UserQuotaVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 配额服务的规则测试：原子预留、豁免、释放、调整与重算，不依赖数据库。
 */
class UserQuotaServiceImplTest {

    private UserQuotaServiceImpl quotaService;

    private UserMapper userMapper;

    private QuotaProperties quotaProperties;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        quotaService = new UserQuotaServiceImpl();
        userMapper = mock(UserMapper.class);
        quotaProperties = new QuotaProperties();
        meterRegistry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(quotaService, "userMapper", userMapper);
        ReflectionTestUtils.setField(quotaService, "quotaProperties", quotaProperties);
        ReflectionTestUtils.setField(quotaService, "pictureMetrics", new PictureMetrics(meterRegistry));
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("查询配额：返回已用/上限，普通用户不豁免")
    void getQuota_returnsUsage() {
        when(userMapper.selectById(100L)).thenReturn(user(100L, "user", 1024L, 4096L, 3, 10));

        UserQuotaVO quota = quotaService.getQuota(100L);

        assertEquals(Long.valueOf(1024L), quota.getStorageUsed());
        assertEquals(Long.valueOf(4096L), quota.getStorageQuota());
        assertEquals(Integer.valueOf(3), quota.getPictureUsed());
        assertEquals(Integer.valueOf(10), quota.getPictureQuota());
        assertFalse(quota.getExempt());
    }

    @Test
    @DisplayName("查询配额：用户不存在返回 40400")
    void getQuota_rejectsMissingUser() {
        when(userMapper.selectById(100L)).thenReturn(null);

        assertEquals(40400, assertThrows(BusinessException.class,
                () -> quotaService.getQuota(100L)).getCode());
    }

    // ==================== 预留 ====================

    @Test
    @DisplayName("预留：额度充足时原子自增并返回成功")
    void reserve_succeedsWhenQuotaAvailable() {
        User user = user(100L, "user", 0L, 1024L, 0, 10);
        when(userMapper.reserveQuota(100L, 512L, 1)).thenReturn(1);

        assertTrue(quotaService.reserve(user, 512L, 1));
    }

    @Test
    @DisplayName("预留：存储不足时返回失败并记录 storage 指标")
    void reserve_rejectsWhenStorageExceeded() {
        User user = user(100L, "user", 900L, 1000L, 0, 10);
        when(userMapper.reserveQuota(100L, 512L, 1)).thenReturn(0);

        assertFalse(quotaService.reserve(user, 512L, 1));
        assertEquals(1.0, meterRegistry.get(PictureMetrics.QUOTA_REJECTED)
                .tag("type", "storage").counter().count());
    }

    @Test
    @DisplayName("预留：数量到达上限时记录 count 指标")
    void reserve_rejectsWhenPictureCountExceeded() {
        User user = user(100L, "user", 100L, 1000L, 10, 10);
        when(userMapper.reserveQuota(100L, 100L, 1)).thenReturn(0);

        assertFalse(quotaService.reserve(user, 100L, 1));
        assertEquals(1.0, meterRegistry.get(PictureMetrics.QUOTA_REJECTED)
                .tag("type", "count").counter().count());
    }

    @Test
    @DisplayName("预留：管理员默认豁免，不写数据库")
    void reserve_skipsExemptAdmin() {
        User admin = user(1L, "admin", 0L, 0L, 0, 0);

        assertTrue(quotaService.reserve(admin, 10_000L, 5));
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("预留：关闭配额后所有用户豁免")
    void reserve_skipsWhenQuotaDisabled() {
        quotaProperties.setEnabled(false);
        User user = user(100L, "user", 9999L, 1000L, 99, 10);

        assertTrue(quotaService.reserve(user, 10_000L, 5));
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("预留：纯释放（增量为负）不会因额度条件失败")
    void reserve_allowsNegativeDelta() {
        User user = user(100L, "user", 500L, 1000L, 1, 10);
        when(userMapper.reserveQuota(100L, -200L, -1)).thenReturn(1);

        assertTrue(quotaService.reserve(user, -200L, -1));
    }

    @Test
    @DisplayName("预留：按用户 id 预留时用户不存在返回 false，不抛异常")
    void reserveByUserId_missingUserReturnsFalse() {
        when(userMapper.selectById(100L)).thenReturn(null);

        assertFalse(quotaService.reserve(100L, 10L, 1));
        verify(userMapper, never()).reserveQuota(anyLong(), anyLong(), anyInt());
    }

    // ==================== 释放 ====================

    @Test
    @DisplayName("释放：走不做额度校验的调整 SQL，允许减到 0 但不为负")
    void release_adjustsUsageWithoutLimitCheck() {
        when(userMapper.selectById(100L)).thenReturn(user(100L, "user", 500L, 1000L, 1, 10));

        quotaService.release(100L, -500L, -1);

        verify(userMapper).adjustQuota(100L, -500L, -1);
        verify(userMapper, never()).reserveQuota(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("释放：管理员豁免时同样不写数据库，避免把用量减成负数")
    void release_skipsExemptAdmin() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "admin", 0L, 0L, 0, 0));

        quotaService.release(1L, -100L, -1);

        verify(userMapper, never()).adjustQuota(anyLong(), anyLong(), anyInt());
    }

    // ==================== 调整与重算 ====================

    @Test
    @DisplayName("调整配额：只传一项时只更新该项，返回最新配额")
    void updateQuota_updatesSingleField() {
        when(userMapper.selectById(100L))
                .thenReturn(user(100L, "user", 0L, 1000L, 0, 10))
                .thenReturn(user(100L, "user", 0L, 2048L, 0, 10));
        when(userMapper.updateQuota(100L, 2048L, null)).thenReturn(1);

        UserQuotaVO quota = quotaService.updateQuota(100L, 2048L, null);

        assertEquals(Long.valueOf(2048L), quota.getStorageQuota());
        assertEquals(Integer.valueOf(10), quota.getPictureQuota());
    }

    @Test
    @DisplayName("调整配额：两项都为空或为负时拒绝")
    void updateQuota_validatesArguments() {
        assertThrows(BusinessException.class, () -> quotaService.updateQuota(100L, null, null));
        assertThrows(BusinessException.class, () -> quotaService.updateQuota(100L, -1L, null));
        assertThrows(BusinessException.class, () -> quotaService.updateQuota(100L, null, -1));
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("重算：指定用户只重算该用户")
    void recalc_singleUser() {
        when(userMapper.recalcQuota(100L)).thenReturn(1);

        assertEquals(1, quotaService.recalc(100L));
        verify(userMapper).recalcQuota(100L);
        verify(userMapper, never()).selectIdsForQuotaRecalc(anyLong(), anyInt());
    }

    @Test
    @DisplayName("重算：不传用户时分批游标遍历全部用户")
    void recalc_allUsersInBatches() {
        quotaProperties.setRecalcBatchSize(2);
        when(userMapper.selectIdsForQuotaRecalc(anyLong(), eq(2)))
                .thenReturn(List.of(1L, 2L), List.of(3L));
        when(userMapper.recalcQuota(anyLong())).thenReturn(1);

        assertEquals(3, quotaService.recalc(null));
        verify(userMapper).recalcQuota(1L);
        verify(userMapper).recalcQuota(2L);
        verify(userMapper).recalcQuota(3L);
    }

    @Test
    @DisplayName("默认配额：新建用户按配置写入上限与 0 已用")
    void applyDefaultQuota_setsConfiguredDefaults() {
        quotaProperties.setDefaultStorageBytes(2048L);
        quotaProperties.setDefaultPictureCount(50);
        User user = new User();

        quotaService.applyDefaultQuota(user);

        assertEquals(Long.valueOf(2048L), user.getStorageQuota());
        assertEquals(Long.valueOf(0L), user.getStorageUsed());
        assertEquals(Integer.valueOf(50), user.getPictureQuota());
        assertEquals(Integer.valueOf(0), user.getPictureUsed());
    }

    private User user(Long id, String role, Long storageUsed, Long storageQuota,
                      Integer pictureUsed, Integer pictureQuota) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        user.setStorageUsed(storageUsed);
        user.setStorageQuota(storageQuota);
        user.setPictureUsed(pictureUsed);
        user.setPictureQuota(pictureQuota);
        return user;
    }
}
