package com.tzy.picturebackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.config.NotificationProperties;
import com.tzy.picturebackend.mapper.NotificationMapper;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.dto.notification.NotificationMessage;
import com.tzy.picturebackend.model.entity.Notification;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.NotificationTypeEnum;
import com.tzy.picturebackend.model.vo.NotificationVO;
import com.tzy.picturebackend.service.UserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 站内通知：批量写入、幂等键、未读上限、分页映射与清理
 */
class NotificationServiceImplTest {

    private NotificationServiceImpl notificationService;

    private NotificationMapper notificationMapper;

    private UserService userService;

    private NotificationProperties properties;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        notificationMapper = mock(NotificationMapper.class);
        userService = mock(UserService.class);
        properties = new NotificationProperties();
        meterRegistry = new SimpleMeterRegistry();
        notificationService = new NotificationServiceImpl(properties, new PictureMetrics(meterRegistry),
                userService);
        ReflectionTestUtils.setField(notificationService, "baseMapper", notificationMapper);
    }

    @Test
    @DisplayName("批量写入：一次批量插入、按类型计数，未读超上限时收敛最旧的未读")
    void notifyBatch_insertsBatchAndTrimsUnread() {
        when(notificationMapper.countUnread(100L)).thenReturn(201L);

        notificationService.notifyBatch(List.of(
                NotificationMessage.review(NotificationTypeEnum.REVIEW_PASS, 100L, 1L, "风景", null),
                NotificationMessage.review(NotificationTypeEnum.REVIEW_REJECT, 100L, 2L, "海报", "违规")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationMapper).insertIgnoreBatch(captor.capture());
        assertEquals(2, captor.getValue().size());
        assertTrue(captor.getValue().stream().allMatch(row -> row.getIsRead() == 0));
        assertTrue(captor.getValue().stream().allMatch(row -> row.getId() != null));
        verify(notificationMapper, times(1)).markOldestRead(100L, properties.getMaxUnread());
        assertEquals(1.0, meterRegistry.get("notification.sent")
                .tag("type", "REVIEW_PASS").counter().count());
    }

    @Test
    @DisplayName("批量写入：未读未超上限时不执行清理 UPDATE")
    void notifyBatch_skipsTrimWhenUnderLimit() {
        when(notificationMapper.countUnread(100L)).thenReturn(3L);

        notificationService.notifyBatch(List.of(
                NotificationMessage.system(100L, "公告", "正文")));

        verify(notificationMapper, never()).markOldestRead(any(), anyInt());
    }

    @Test
    @DisplayName("批量写入：超过 500 条按批分片，避免超长 SQL")
    void notifyBatch_splitsLargeBatches() {
        List<NotificationMessage> messages = new ArrayList<>();
        for (int i = 0; i < NotificationServiceImpl.INSERT_BATCH_SIZE + 1; i++) {
            messages.add(NotificationMessage.system(100L, "公告", "正文" + i));
        }

        notificationService.notifyBatch(messages);

        verify(notificationMapper, times(2)).insertIgnoreBatch(any());
    }

    @Test
    @DisplayName("批量写入：空集合不产生任何 SQL")
    void notifyBatch_ignoresEmptyInput() {
        notificationService.notifyBatch(List.of());

        verifyNoInteractions(notificationMapper);
    }

    @Test
    @DisplayName("幂等键与业务字段：审核通知保留 dedupeKey，公告为 NULL 表示不去重")
    void notifyBatch_keepsDedupeKeyAndDefaults() {
        notificationService.notifyBatch(List.of(
                NotificationMessage.review(NotificationTypeEnum.REVIEW_REJECT, 100L, 9L, null, "不合规"),
                NotificationMessage.system(200L, "维护通知", "今晚 23 点维护")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationMapper).insertIgnoreBatch(captor.capture());
        Notification review = captor.getValue().get(0);
        assertEquals("REVIEW_REJECT:9", review.getDedupeKey());
        assertEquals("你的图片《未命名图片》未通过审核：不合规", review.getContent());
        Notification announcement = captor.getValue().get(1);
        assertNull(announcement.getDedupeKey());
        assertEquals("维护通知", announcement.getTitle());
        assertEquals("SYSTEM", announcement.getBizType());
    }

    @Test
    @DisplayName("超长正文按 1024 截断，不让整批插入失败")
    void notifyBatch_truncatesContent() {
        notificationService.notifyBatch(List.of(
                NotificationMessage.system(100L, "t".repeat(200), "c".repeat(2000))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationMapper).insertIgnoreBatch(captor.capture());
        Notification row = captor.getValue().get(0);
        assertEquals(128, row.getTitle().length());
        assertEquals(1024, row.getContent().length());
    }

    @Test
    @DisplayName("分页：实体映射为 VO 并补上类型中文名")
    void page_mapsTypeText() {
        Notification notification = new Notification();
        notification.setId(1L);
        notification.setUserId(100L);
        notification.setType("REVIEW_PASS");
        notification.setTitle("图片审核通过");
        notification.setIsRead(0);
        Page<Notification> mapperPage = new Page<>(1, 10, 1);
        mapperPage.setRecords(List.of(notification));
        when(notificationMapper.selectUserPage(any(), eq(100L), eq(true))).thenReturn(mapperPage);

        Page<NotificationVO> page = notificationService.page(100L, true, 1, 10);

        assertEquals(1, page.getRecords().size());
        assertEquals("图片审核通过", page.getRecords().get(0).getTypeText());
        assertEquals(1L, page.getTotal());
    }

    @Test
    @DisplayName("已读：过滤非法 id，全部非法时不写库")
    void markRead_filtersIllegalIds() {
        assertEquals(0, notificationService.markRead(100L, List.of(-1L, 0L)));
        verify(notificationMapper, never()).markRead(any(), any());

        when(notificationMapper.markRead(eq(100L), any())).thenReturn(1);
        assertEquals(1, notificationService.markRead(100L, List.of(1L, 1L, 2L)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<Long>> captor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(notificationMapper).markRead(eq(100L), captor.capture());
        assertEquals(List.of(1L, 2L), List.copyOf(captor.getValue()));
    }

    @Test
    @DisplayName("全部已读：记录已读指标")
    void markAllRead_recordsMetric() {
        when(notificationMapper.markAllRead(100L)).thenReturn(3);

        assertEquals(3, notificationService.markAllRead(100L));
        assertEquals(3.0, meterRegistry.get("notification.read").counter().count());
    }

    @Test
    @DisplayName("公告：为每个用户各写一行，标题与正文来自管理员输入")
    void broadcast_writesOneRowPerUser() {
        when(userService.list(any(Wrapper.class))).thenReturn(List.of(user(1L), user(2L), user(3L)));

        int sent = notificationService.broadcast("系统维护", "今晚 23 点维护");

        assertEquals(3, sent);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(notificationMapper).insertIgnoreBatch(captor.capture());
        assertEquals(3, captor.getValue().size());
        assertTrue(captor.getValue().stream().allMatch(row -> "SYSTEM".equals(row.getType())));
        assertTrue(captor.getValue().stream().allMatch(row -> row.getDedupeKey() == null));
    }

    @Test
    @DisplayName("清理：只删已读超期数据，单批条数有上限")
    void purgeReadBefore_capsBatchSize() {
        Date before = new Date(System.currentTimeMillis() - Duration.ofDays(90).toMillis());
        when(notificationMapper.deleteReadBefore(any(), anyInt())).thenReturn(10);

        notificationService.purgeReadBefore(before, 10_000);

        verify(notificationMapper).deleteReadBefore(eq(before), eq(5000));
    }

    @Test
    @DisplayName("删除用户：清理该用户的全部通知")
    void deleteByUserId_delegates() {
        notificationService.deleteByUserId(100L);

        verify(notificationMapper).deleteByUserId(100L);
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUserRole("user");
        return user;
    }
}
