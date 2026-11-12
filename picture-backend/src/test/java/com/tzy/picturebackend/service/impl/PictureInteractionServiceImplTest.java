package com.tzy.picturebackend.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.event.PictureEventPublisher;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.mapper.PictureInteractionMapper;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.dto.interaction.InteractionPageRequest;
import com.tzy.picturebackend.model.dto.interaction.InteractionRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.PictureInteraction;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.vo.InteractionStatusVO;
import com.tzy.picturebackend.model.vo.PictureVO;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.UserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 收藏 / 点赞：幂等、计数、可见性与「我的收藏」分页
 */
class PictureInteractionServiceImplTest {

    private PictureInteractionServiceImpl interactionService;

    private PictureInteractionMapper interactionMapper;

    private PictureMapper pictureMapper;

    private PictureService pictureService;

    private UserService userService;

    private PictureEventPublisher pictureEventPublisher;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        interactionService = new PictureInteractionServiceImpl(
                mock(PictureService.class), mock(PictureMapper.class), mock(UserService.class),
                new PictureMetrics(meterRegistry), mock(PictureEventPublisher.class));
        interactionMapper = mock(PictureInteractionMapper.class);
        pictureMapper = mock(PictureMapper.class);
        pictureService = mock(PictureService.class);
        userService = mock(UserService.class);
        pictureEventPublisher = mock(PictureEventPublisher.class);
        ReflectionTestUtils.setField(interactionService, "baseMapper", interactionMapper);
        ReflectionTestUtils.setField(interactionService, "pictureMapper", pictureMapper);
        ReflectionTestUtils.setField(interactionService, "pictureService", pictureService);
        ReflectionTestUtils.setField(interactionService, "userService", userService);
        ReflectionTestUtils.setField(interactionService, "pictureEventPublisher", pictureEventPublisher);
        ReflectionTestUtils.setField(interactionService, "pictureMetrics", new PictureMetrics(meterRegistry));
    }

    @Test
    @DisplayName("收藏：新增关系行时计数 +1，并同步搜索索引快照")
    void add_insertsRelationAndIncrementsCounter() {
        when(pictureService.getById(1L)).thenReturn(passPicture(1L, 5L));
        when(interactionMapper.insertIgnore(any(), eq(1L), eq(100L), eq("favorite"), any()))
                .thenReturn(1);
        when(pictureMapper.adjustInteractionCount(1L, "favoriteCount", 1)).thenReturn(1);
        when(interactionMapper.selectByPictureAndUser(1L, 100L)).thenReturn(List.of(
                interaction(1L, 100L, "favorite")));

        InteractionStatusVO status = interactionService.add(request(1L, "favorite"), user(100L));

        assertTrue(status.isFavorite());
        assertFalse(status.isLike());
        verify(interactionMapper).insertIgnore(any(), eq(1L), eq(100L), eq("favorite"), any());
        verify(pictureMapper).adjustInteractionCount(1L, "favoriteCount", 1);
        // ES 模式下热度排序读索引快照，互动后必须同步一次
        verify(pictureEventPublisher).pictureChanged(1L);
        assertEquals(1.0, meterRegistry.get("picture.interaction")
                .tags("action", "add", "type", "favorite").counter().count());
    }

    @Test
    @DisplayName("收藏：重复点击命中去重键时不重复累加计数、不发索引事件")
    void add_isIdempotentWhenRelationExists() {
        when(pictureService.getById(1L)).thenReturn(passPicture(1L, 5L));
        when(interactionMapper.insertIgnore(any(), eq(1L), eq(100L), eq("like"), any()))
                .thenReturn(0);
        when(interactionMapper.selectByPictureAndUser(1L, 100L)).thenReturn(List.of(
                interaction(1L, 100L, "like")));

        InteractionStatusVO status = interactionService.add(request(1L, "like"), user(100L));

        assertTrue(status.isLike());
        verify(pictureMapper, never()).adjustInteractionCount(any(), anyString(), anyInt());
        verifyNoInteractions(pictureEventPublisher);
    }

    @Test
    @DisplayName("收藏：待审核图片不可互动，统一按不存在处理")
    void add_rejectsUnapprovedPicture() {
        when(pictureService.getById(1L)).thenReturn(picture(1L, 5L, PictureReviewStatusEnum.REVIEWING.getValue()));

        BusinessException e = assertThrows(BusinessException.class,
                () -> interactionService.add(request(1L, "favorite"), user(100L)));

        assertEquals(40400, e.getCode());
        verifyNoInteractions(interactionMapper, pictureMapper);
    }

    @Test
    @DisplayName("收藏：图片在写关系后被删除时抛操作失败，事务回滚关系行")
    void add_rollsBackWhenPictureDisappears() {
        when(pictureService.getById(1L)).thenReturn(passPicture(1L, 5L));
        when(interactionMapper.insertIgnore(any(), eq(1L), eq(100L), eq("favorite"), any()))
                .thenReturn(1);
        when(pictureMapper.adjustInteractionCount(1L, "favoriteCount", 1)).thenReturn(0);

        BusinessException e = assertThrows(BusinessException.class,
                () -> interactionService.add(request(1L, "favorite"), user(100L)));

        assertEquals(50001, e.getCode());
        verify(pictureEventPublisher, never()).pictureChanged(any());
    }

    @Test
    @DisplayName("取消收藏：删除关系行后计数 -1")
    void remove_deletesRelationAndDecrementsCounter() {
        when(interactionMapper.deleteOne(1L, 100L, "favorite")).thenReturn(1);
        when(pictureService.getById(1L)).thenReturn(passPicture(1L, 5L));
        when(interactionMapper.selectByPictureAndUser(1L, 100L)).thenReturn(List.of());

        InteractionStatusVO status = interactionService.remove(request(1L, "favorite"), user(100L));

        assertFalse(status.isFavorite());
        verify(pictureMapper).adjustInteractionCount(1L, "favoriteCount", -1);
        verify(pictureEventPublisher).pictureChanged(1L);
    }

    @Test
    @DisplayName("取消收藏：本来就没有收藏时不改计数，直接返回当前状态")
    void remove_isIdempotentWhenRelationMissing() {
        when(interactionMapper.deleteOne(1L, 100L, "favorite")).thenReturn(0);
        when(pictureService.getById(1L)).thenReturn(passPicture(1L, 5L));
        when(interactionMapper.selectByPictureAndUser(1L, 100L)).thenReturn(List.of());

        InteractionStatusVO status = interactionService.remove(request(1L, "favorite"), user(100L));

        assertFalse(status.isFavorite());
        verify(pictureMapper, never()).adjustInteractionCount(any(), anyString(), anyInt());
        verifyNoInteractions(pictureEventPublisher);
    }

    @Test
    @DisplayName("状态查询：未登录只返回计数，选中状态恒为 false")
    void status_anonymousOnlyReturnsCounts() {
        Picture picture = passPicture(1L, 5L);
        picture.setFavoriteCount(7L);
        picture.setLikeCount(3L);
        when(pictureService.getById(1L)).thenReturn(picture);
        when(userService.getLoginUserOrNull(any())).thenReturn(null);

        InteractionStatusVO status = interactionService.status(1L, new MockHttpServletRequest());

        assertFalse(status.isFavorite());
        assertFalse(status.isLike());
        assertEquals(7L, status.getFavoriteCount());
        assertEquals(3L, status.getLikeCount());
        verifyNoInteractions(interactionMapper);
    }

    @Test
    @DisplayName("状态查询：已登录时同时返回收藏与点赞状态")
    void status_returnsBothFlagsForLoggedInUser() {
        when(pictureService.getById(1L)).thenReturn(passPicture(1L, 5L));
        when(userService.getLoginUserOrNull(any())).thenReturn(user(100L));
        when(interactionMapper.selectByPictureAndUser(1L, 100L)).thenReturn(List.of(
                interaction(1L, 100L, "favorite"), interaction(1L, 100L, "like")));

        InteractionStatusVO status = interactionService.status(1L, new MockHttpServletRequest());

        assertTrue(status.isFavorite());
        assertTrue(status.isLike());
    }

    @Test
    @DisplayName("我的收藏：委托 custom SQL 分页，并把结果转成公开 VO")
    void pageMy_delegatesToJoinQuery() {
        when(pictureService.getPictureVOPage(any(), any())).thenReturn(new Page<>(1, 20));

        InteractionPageRequest request = new InteractionPageRequest();
        request.setType("favorite");
        request.setCurrent(1);
        request.setPageSize(20);
        Page<PictureVO> result = interactionService.pageMy(request, user(100L),
                new MockHttpServletRequest());

        assertEquals(1, result.getCurrent());
        verify(interactionMapper).selectMyPicturePage(any(), eq(100L), eq("favorite"));
        verify(pictureService).getPictureVOPage(any(), any());
    }

    @Test
    @DisplayName("互动类型非法时返回参数错误")
    void add_rejectsUnknownType() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> interactionService.add(request(1L, "collect"), user(100L)));

        assertEquals(40000, e.getCode());
        verifyNoInteractions(pictureService, interactionMapper);
    }

    @Test
    @DisplayName("计数重算：指定图片时只重算该图片")
    void recount_singlePictureUsesMapper() {
        when(pictureMapper.recountInteractions(List.of(1L))).thenReturn(1);

        assertEquals(1, interactionService.recount(1L));
        verify(pictureMapper).recountInteractions(List.of(1L));
    }

    @Test
    @DisplayName("计数重算：全量时按主键游标分批，返回实际重算的图片数")
    void recount_allPicturesUsesKeysetBatches() {
        when(pictureMapper.selectIdsForRecount(0L, 500)).thenReturn(List.of(1L, 2L));
        when(pictureMapper.recountInteractions(List.of(1L, 2L))).thenReturn(2);

        assertEquals(2, interactionService.recount(null));
        verify(pictureMapper).selectIdsForRecount(0L, 500);
        verify(pictureMapper).recountInteractions(List.of(1L, 2L));
    }

    private InteractionRequest request(Long pictureId, String type) {
        InteractionRequest request = new InteractionRequest();
        request.setPictureId(pictureId);
        request.setType(type);
        return request;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUserRole("user");
        return user;
    }

    private Picture passPicture(Long id, Long userId) {
        return picture(id, userId, PictureReviewStatusEnum.PASS.getValue());
    }

    private Picture picture(Long id, Long userId, int reviewStatus) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setUserId(userId);
        picture.setReviewStatus(reviewStatus);
        picture.setFavoriteCount(0L);
        picture.setLikeCount(0L);
        return picture;
    }

    private PictureInteraction interaction(Long pictureId, Long userId, String type) {
        PictureInteraction interaction = new PictureInteraction();
        interaction.setPictureId(pictureId);
        interaction.setUserId(userId);
        interaction.setType(type);
        return interaction;
    }
}
