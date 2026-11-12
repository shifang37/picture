package com.tzy.picturebackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.event.PictureEventPublisher;
import com.tzy.picturebackend.event.NotificationEventPublisher;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.manager.FileManager;
import com.tzy.picturebackend.manager.PictureListCache;
import com.tzy.picturebackend.mapper.PictureAiSuggestionMapper;
import com.tzy.picturebackend.mapper.PictureInteractionMapper;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.mapper.PictureTagMapper;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.dto.file.UploadPictureResult;
import com.tzy.picturebackend.model.dto.notification.NotificationMessage;
import com.tzy.picturebackend.model.dto.picture.PictureEditRequest;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.picture.PictureReviewBatchRequest;
import com.tzy.picturebackend.model.dto.picture.PictureReviewRequest;
import com.tzy.picturebackend.model.dto.picture.PictureUpdateRequest;
import com.tzy.picturebackend.model.dto.picture.PictureUploadRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.enums.NotificationTypeEnum;
import com.tzy.picturebackend.model.vo.PictureDetailVO;
import com.tzy.picturebackend.model.vo.PictureReviewBatchResult;
import com.tzy.picturebackend.model.vo.PictureReviewLogVO;
import com.tzy.picturebackend.model.vo.PictureTagCategory;
import com.tzy.picturebackend.model.vo.PictureVO;
import com.tzy.picturebackend.service.PictureReviewLogService;
import com.tzy.picturebackend.service.TagCategoryService;
import com.tzy.picturebackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PictureServiceImpl 单元测试：审核可见性、审核动作与排序字段校验，不依赖数据库
 */
class PictureServiceImplTest {

    private PictureServiceImpl pictureService;

    private UserService userService;

    private PictureMapper pictureMapper;

    private PictureTagMapper pictureTagMapper;

    private PictureAiSuggestionMapper pictureAiSuggestionMapper;

    private PictureInteractionMapper pictureInteractionMapper;

    private FileManager fileManager;

    private PictureReviewLogService pictureReviewLogService;

    private TagCategoryService tagCategoryService;

    private PictureListCache pictureListCache;

    private PictureEventPublisher pictureEventPublisher;

    private NotificationEventPublisher notificationEventPublisher;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        pictureService = new PictureServiceImpl();
        userService = mock(UserService.class);
        pictureMapper = mock(PictureMapper.class);
        pictureTagMapper = mock(PictureTagMapper.class);
        pictureAiSuggestionMapper = mock(PictureAiSuggestionMapper.class);
        pictureInteractionMapper = mock(PictureInteractionMapper.class);
        fileManager = mock(FileManager.class);
        pictureReviewLogService = mock(PictureReviewLogService.class);
        tagCategoryService = mock(TagCategoryService.class);
        pictureListCache = mock(PictureListCache.class);
        pictureEventPublisher = mock(PictureEventPublisher.class);
        notificationEventPublisher = mock(NotificationEventPublisher.class);
        meterRegistry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(pictureService, "userService", userService);
        ReflectionTestUtils.setField(pictureService, "baseMapper", pictureMapper);
        ReflectionTestUtils.setField(pictureService, "pictureTagMapper", pictureTagMapper);
        ReflectionTestUtils.setField(pictureService, "pictureAiSuggestionMapper", pictureAiSuggestionMapper);
        ReflectionTestUtils.setField(pictureService, "pictureInteractionMapper", pictureInteractionMapper);
        ReflectionTestUtils.setField(pictureService, "fileManager", fileManager);
        ReflectionTestUtils.setField(pictureService, "pictureReviewLogService", pictureReviewLogService);
        ReflectionTestUtils.setField(pictureService, "tagCategoryService", tagCategoryService);
        ReflectionTestUtils.setField(pictureService, "pictureListCache", pictureListCache);
        ReflectionTestUtils.setField(pictureService, "pictureEventPublisher", pictureEventPublisher);
        ReflectionTestUtils.setField(pictureService, "notificationEventPublisher", notificationEventPublisher);
        ReflectionTestUtils.setField(pictureService, "pictureMetrics", new PictureMetrics(meterRegistry));
    }

    // ==================== 可见性 ====================

    @Test
    @DisplayName("可见性：已通过审核的图片所有人可见，且不查用户")
    void checkPictureVisible_allowsApproved() {
        Picture picture = picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue());

        assertDoesNotThrow(() -> pictureService.checkPictureVisible(picture, new MockHttpServletRequest()));
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("可见性：匿名访问待审核图片应视为不存在")
    void checkPictureVisible_rejectsAnonymousOnPending() {
        Picture picture = picture(1L, 100L, PictureReviewStatusEnum.REVIEWING.getValue());
        when(userService.getLoginUserOrNull(any())).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> pictureService.checkPictureVisible(picture, new MockHttpServletRequest()));
        assertEquals("图片不存在", e.getMessage());
    }

    @Test
    @DisplayName("可见性：作者本人可见自己的待审核图片")
    void checkPictureVisible_allowsOwner() {
        Picture picture = picture(1L, 100L, PictureReviewStatusEnum.REVIEWING.getValue());
        User owner = user(100L, "user");
        when(userService.getLoginUserOrNull(any())).thenReturn(owner);
        when(userService.isAdmin(owner)).thenReturn(false);

        assertDoesNotThrow(() -> pictureService.checkPictureVisible(picture, new MockHttpServletRequest()));
    }

    @Test
    @DisplayName("可见性：管理员可见待审核图片")
    void checkPictureVisible_allowsAdmin() {
        Picture picture = picture(1L, 100L, PictureReviewStatusEnum.REVIEWING.getValue());
        User admin = user(1L, "admin");
        when(userService.getLoginUserOrNull(any())).thenReturn(admin);
        when(userService.isAdmin(admin)).thenReturn(true);

        assertDoesNotThrow(() -> pictureService.checkPictureVisible(picture, new MockHttpServletRequest()));
    }

    @Test
    @DisplayName("可见性：其他普通用户不可见别人的待审核图片")
    void checkPictureVisible_rejectsOtherUser() {
        Picture picture = picture(1L, 100L, PictureReviewStatusEnum.REVIEWING.getValue());
        User other = user(200L, "user");
        when(userService.getLoginUserOrNull(any())).thenReturn(other);
        when(userService.isAdmin(other)).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> pictureService.checkPictureVisible(picture, new MockHttpServletRequest()));
        assertEquals("图片不存在", e.getMessage());
    }

    @Test
    @DisplayName("可见性：图片为空应视为不存在")
    void checkPictureVisible_rejectsNullPicture() {
        assertThrows(BusinessException.class,
                () -> pictureService.checkPictureVisible(null, new MockHttpServletRequest()));
    }

    // ==================== 审核信息可见性 ====================

    @Test
    @DisplayName("详情封装：匿名访客看不到审核意见、审核人和审核时间")
    void getPictureVO_hidesReviewInfoFromAnonymous() {
        Picture picture = rejectedPicture();
        when(userService.getLoginUserOrNull(any())).thenReturn(null);

        PictureVO pictureVO = pictureService.getPictureVO(picture, new MockHttpServletRequest());

        assertFalse(pictureVO instanceof PictureDetailVO, "公开访客不能拿到详情 VO");
    }

    @Test
    @DisplayName("详情封装：作者本人可以看到审核意见和审核人")
    void getPictureVO_ownerSeesReviewInfo() {
        Picture picture = rejectedPicture();
        User owner = user(100L, "user");
        when(userService.getLoginUserOrNull(any())).thenReturn(owner);
        when(userService.isAdmin(owner)).thenReturn(false);

        PictureVO pictureVO = pictureService.getPictureVO(picture, new MockHttpServletRequest());

        assertTrue(pictureVO instanceof PictureDetailVO, "作者本人应拿到详情 VO");
        PictureDetailVO detailVO = (PictureDetailVO) pictureVO;
        assertEquals("内容不合规", detailVO.getReviewMessage());
        assertEquals(Long.valueOf(99L), detailVO.getReviewerId());
        assertNotNull(detailVO.getReviewTime());
    }

    @Test
    @DisplayName("详情封装：管理员可以看到审核意见和审核人")
    void getPictureVO_adminSeesReviewInfo() {
        Picture picture = rejectedPicture();
        User admin = admin();
        when(userService.getLoginUserOrNull(any())).thenReturn(admin);
        when(userService.isAdmin(admin)).thenReturn(true);

        PictureVO pictureVO = pictureService.getPictureVO(picture, new MockHttpServletRequest());

        assertTrue(pictureVO instanceof PictureDetailVO, "管理员应拿到详情 VO");
        assertEquals("内容不合规", ((PictureDetailVO) pictureVO).getReviewMessage());
    }

    @Test
    @DisplayName("公开列表封装：即使请求者是管理员也不返回审核内部字段")
    void getPictureVOPage_neverIncludesReviewInfo() {
        Picture picture = rejectedPicture();
        User admin = admin();
        when(userService.listByIds(any())).thenReturn(List.of(admin));
        Page<Picture> picturePage = new Page<>(1, 10, 1);
        picturePage.setRecords(List.of(picture));

        Page<PictureVO> result = pictureService.getPictureVOPage(picturePage, new MockHttpServletRequest());

        assertFalse(result.getRecords().get(0) instanceof PictureDetailVO,
                "公开列表不能因为请求者是管理员就返回审核信息");
    }

    @Test
    @DisplayName("我的图片封装：作者本人可以看到审核意见、审核人和审核时间")
    void getPictureDetailVOPage_includesReviewInfo() {
        Picture picture = rejectedPicture();
        User owner = user(100L, "user");
        when(userService.listByIds(any())).thenReturn(List.of(owner));
        Page<Picture> picturePage = new Page<>(1, 10, 1);
        picturePage.setRecords(List.of(picture));

        Page<PictureDetailVO> result = pictureService.getPictureDetailVOPage(picturePage);

        PictureDetailVO detailVO = result.getRecords().get(0);
        assertEquals("内容不合规", detailVO.getReviewMessage());
        assertEquals(Long.valueOf(99L), detailVO.getReviewerId());
        assertNotNull(detailVO.getReviewTime());
    }

    // ==================== 上传 ====================

    @Test
    @DisplayName("上传：普通用户上传后进入待审核，不写审核人")
    void uploadPicture_normalUserEntersReviewing() {
        User normalUser = user(100L, "user");
        when(userService.isAdmin(normalUser)).thenReturn(false);
        when(fileManager.uploadPicture(any(), anyString())).thenReturn(uploadResult());
        when(pictureMapper.insert(any(Picture.class))).thenAnswer(invocation -> {
            Picture saved = invocation.getArgument(0);
            saved.setId(1L);
            return 1;
        });

        PictureVO pictureVO = pictureService.uploadPicture(jpegFile(), null, normalUser);

        ArgumentCaptor<Picture> captor = ArgumentCaptor.forClass(Picture.class);
        verify(pictureMapper).insert(captor.capture());
        Picture saved = captor.getValue();
        assertEquals(Integer.valueOf(PictureReviewStatusEnum.REVIEWING.getValue()), saved.getReviewStatus());
        assertNull(saved.getReviewerId());
        assertNull(saved.getReviewTime());
        assertEquals(Long.valueOf(100L), saved.getUserId());
        assertEquals(Integer.valueOf(PictureReviewStatusEnum.REVIEWING.getValue()), pictureVO.getReviewStatus());
        verifyNoInteractions(pictureReviewLogService);
        verify(pictureEventPublisher).pictureUploaded(1L);
        assertEquals(1.0, meterRegistry.get(PictureMetrics.UPLOAD)
                .tag("result", "success").counter().count());
    }

    @Test
    @DisplayName("上传：管理员上传自动通过并记录审核人")
    void uploadPicture_adminAutoPasses() {
        User admin = admin();
        when(userService.isAdmin(admin)).thenReturn(true);
        when(fileManager.uploadPicture(any(), anyString())).thenReturn(uploadResult());
        when(pictureMapper.insert(any(Picture.class))).thenAnswer(invocation -> {
            Picture saved = invocation.getArgument(0);
            saved.setId(1L);
            return 1;
        });

        pictureService.uploadPicture(jpegFile(), null, admin);

        ArgumentCaptor<Picture> captor = ArgumentCaptor.forClass(Picture.class);
        verify(pictureMapper).insert(captor.capture());
        Picture saved = captor.getValue();
        assertEquals(Integer.valueOf(PictureReviewStatusEnum.PASS.getValue()), saved.getReviewStatus());
        assertEquals(admin.getId(), saved.getReviewerId());
        assertNotNull(saved.getReviewTime());
        verify(pictureReviewLogService).record(1L, admin.getId(),
                PictureReviewStatusEnum.PASS.getValue(), "管理员上传，自动通过");
        verify(pictureListCache).invalidate();
        verify(pictureEventPublisher).pictureUploaded(1L);
    }

    @Test
    @DisplayName("上传：普通用户重传已通过图片会重新进入待审核并清空审核信息")
    void uploadPicture_normalUserReuploadResetsReview() {
        User normalUser = user(100L, "user");
        when(userService.isAdmin(normalUser)).thenReturn(false);
        when(pictureMapper.selectById(1L))
                .thenReturn(picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue()));
        when(fileManager.uploadPicture(any(), anyString())).thenReturn(uploadResult());
        when(pictureMapper.update(any(Picture.class), any(Wrapper.class))).thenReturn(1);

        pictureService.uploadPicture(jpegFile(), uploadRequest(1L), normalUser);

        ArgumentCaptor<Picture> entityCaptor = ArgumentCaptor.forClass(Picture.class);
        ArgumentCaptor<Wrapper<Picture>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(pictureMapper).update(entityCaptor.capture(), wrapperCaptor.capture());
        Picture updated = entityCaptor.getValue();
        assertEquals(Long.valueOf(1L), updated.getId());
        assertEquals(Long.valueOf(100L), updated.getUserId(), "重传不能改变作者");
        assertEquals(Integer.valueOf(PictureReviewStatusEnum.REVIEWING.getValue()), updated.getReviewStatus());
        assertNull(updated.getReviewerId());
        assertNull(updated.getReviewTime());
        UpdateWrapper<Picture> wrapper = (UpdateWrapper<Picture>) wrapperCaptor.getValue();
        String sqlSet = wrapper.getSqlSet();
        assertTrue(sqlSet.contains("reviewMessage"), "实际 SET: " + sqlSet);
        assertTrue(sqlSet.contains("reviewerId"), "实际 SET: " + sqlSet);
        assertTrue(sqlSet.contains("reviewTime"), "实际 SET: " + sqlSet);
        assertTrue(sqlSet.contains("machineReviewStatus"), "重传必须清空机审状态: " + sqlSet);
        assertTrue(sqlSet.contains("machineReviewLabel"), "重传必须清空机审标签: " + sqlSet);
        assertTrue(sqlSet.contains("machineReviewScore"), "重传必须清空机审分数: " + sqlSet);
        assertTrue(sqlSet.contains("machineReviewTime"), "重传必须清空机审时间: " + sqlSet);
        verifyNoInteractions(pictureReviewLogService);
        verify(pictureEventPublisher).pictureUploaded(1L);
    }

    @Test
    @DisplayName("上传：管理员重传他人图片时保留原作者并自动通过")
    void uploadPicture_adminReuploadPreservesOwner() {
        User admin = admin();
        when(userService.isAdmin(admin)).thenReturn(true);
        when(pictureMapper.selectById(1L))
                .thenReturn(picture(1L, 100L, PictureReviewStatusEnum.REJECT.getValue()));
        when(fileManager.uploadPicture(any(), anyString())).thenReturn(uploadResult());
        when(pictureMapper.update(any(Picture.class), any(Wrapper.class))).thenReturn(1);

        pictureService.uploadPicture(jpegFile(), uploadRequest(1L), admin);

        ArgumentCaptor<Picture> captor = ArgumentCaptor.forClass(Picture.class);
        verify(pictureMapper).update(captor.capture(), any(Wrapper.class));
        Picture updated = captor.getValue();
        assertEquals(Long.valueOf(100L), updated.getUserId(), "不能把作者改成管理员");
        assertEquals(Integer.valueOf(PictureReviewStatusEnum.PASS.getValue()), updated.getReviewStatus());
        assertEquals(admin.getId(), updated.getReviewerId());
        verify(pictureReviewLogService).record(1L, admin.getId(),
                PictureReviewStatusEnum.PASS.getValue(), "管理员上传，自动通过");
        verify(pictureEventPublisher).pictureUploaded(1L);
    }

    @Test
    @DisplayName("上传：普通用户不能重传他人的图片")
    void uploadPicture_rejectsOtherUsersPicture() {
        User otherUser = user(200L, "user");
        when(userService.isAdmin(otherUser)).thenReturn(false);
        when(pictureMapper.selectById(1L))
                .thenReturn(picture(1L, 100L, PictureReviewStatusEnum.REVIEWING.getValue()));

        assertThrows(BusinessException.class,
                () -> pictureService.uploadPicture(jpegFile(), uploadRequest(1L), otherUser));
        verifyNoInteractions(fileManager);
        verifyNoInteractions(pictureEventPublisher);
    }

    @Test
    @DisplayName("上传：未登录用户不能上传")
    void uploadPicture_rejectsAnonymous() {
        assertThrows(BusinessException.class,
                () -> pictureService.uploadPicture(jpegFile(), null, null));
        verifyNoInteractions(fileManager);
        verifyNoInteractions(pictureEventPublisher);
        assertEquals(1.0, meterRegistry.get(PictureMetrics.UPLOAD)
                .tag("result", "failure").counter().count());
    }

    // ==================== 回收站：删除 ====================

    @Test
    @DisplayName("删除：移入回收站并记录删除人，级联清理 AI 建议与标签关系，保留 COS 对象")
    void deletePicture_movesToRecycleBinAndCascades() {
        User owner = user(100L, "user");
        Picture existing = picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue());
        existing.setUrl("https://example.com/public/100/2026-11-11_old.jpg");
        when(pictureMapper.selectById(1L)).thenReturn(existing);
        when(pictureMapper.logicalDeleteById(eq(1L), eq(100L), any(Date.class))).thenReturn(1);

        assertTrue(pictureService.deletePicture(1L, owner));

        ArgumentCaptor<Date> deletedAtCaptor = ArgumentCaptor.forClass(Date.class);
        verify(pictureMapper).logicalDeleteById(eq(1L), eq(100L), deletedAtCaptor.capture());
        assertNotNull(deletedAtCaptor.getValue(), "应记录移入回收站时间");
        // AI 建议只对正常图片有意义，删除时级联清理
        verify(pictureAiSuggestionMapper).delete(any());
        // 标签关系清理；恢复时按 picture.tags 重建
        verify(pictureTagMapper).deleteByPictureId(1L);
        // 回收站期间必须保留 COS 对象，否则恢复后图片无法访问
        verifyNoInteractions(fileManager);
        verify(pictureListCache).invalidate();
        verify(pictureEventPublisher).pictureChanged(1L);
        // 审核日志属于合规数据，不随图片删除
        verifyNoInteractions(pictureReviewLogService);
    }

    @Test
    @DisplayName("删除：管理员删除他人图片时记录管理员为删除人")
    void deletePicture_adminDeletesOthersPicture() {
        User admin = admin();
        when(userService.isAdmin(admin)).thenReturn(true);
        Picture existing = picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue());
        existing.setUrl("https://example.com/public/100/a.jpg");
        when(pictureMapper.selectById(1L)).thenReturn(existing);
        when(pictureMapper.logicalDeleteById(eq(1L), eq(admin.getId()), any(Date.class))).thenReturn(1);

        assertTrue(pictureService.deletePicture(1L, admin));

        verify(pictureMapper).logicalDeleteById(eq(1L), eq(admin.getId()), any(Date.class));
        verify(pictureAiSuggestionMapper).delete(any());
        verify(pictureEventPublisher).pictureChanged(1L);
    }

    @Test
    @DisplayName("删除：图片不存在或已删除时不写回收站、不清理关联数据")
    void deletePicture_missingPictureDoesNothing() {
        when(pictureMapper.selectById(1L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> pictureService.deletePicture(1L, user(100L, "user")));

        verify(pictureMapper, never()).logicalDeleteById(any(), any(), any());
        verifyNoInteractions(pictureAiSuggestionMapper, pictureTagMapper, fileManager, pictureEventPublisher);
    }

    @Test
    @DisplayName("删除：普通用户不能删除他人图片，也不清理关联数据")
    void deletePicture_rejectsOtherUser() {
        User other = user(200L, "user");
        when(userService.isAdmin(other)).thenReturn(false);
        when(pictureMapper.selectById(1L)).thenReturn(picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue()));

        assertThrows(BusinessException.class, () -> pictureService.deletePicture(1L, other));

        verify(pictureMapper, never()).logicalDeleteById(any(), any(), any());
        verifyNoInteractions(pictureAiSuggestionMapper, pictureTagMapper, fileManager, pictureEventPublisher);
    }

    @Test
    @DisplayName("删除：并发下已被其他请求移入回收站时不再重复清理")
    void deletePicture_concurrentDeleteDoesNotCascadeAgain() {
        User owner = user(100L, "user");
        Picture existing = picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue());
        when(pictureMapper.selectById(1L)).thenReturn(existing);
        // 条件更新 isDelete = 0 未命中，说明另一请求已完成删除
        when(pictureMapper.logicalDeleteById(eq(1L), eq(100L), any(Date.class))).thenReturn(0);

        assertThrows(BusinessException.class, () -> pictureService.deletePicture(1L, owner));

        verifyNoInteractions(pictureAiSuggestionMapper, pictureTagMapper, fileManager, pictureEventPublisher);
    }

    // ==================== 回收站：列表与恢复 ====================

    @Test
    @DisplayName("回收站列表：只查 isDelete = 1，支持条件过滤并按删除时间倒序")
    void pageRecyclePictures_queriesDeletedRows() {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setName("海边");
        request.setCategory("风景");
        request.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        request.setUserId(100L);
        request.setSearchText("日落");
        when(pictureMapper.selectRecyclePage(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Page<Picture> result = pictureService.pageRecyclePictures(new Page<>(1, 10), request);

        assertNotNull(result);
        ArgumentCaptor<Wrapper<Picture>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(pictureMapper).selectRecyclePage(any(), wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getSqlSegment();
        assertTrue(sql.contains("isDelete"), "实际 SQL: " + sql);
        assertTrue(sql.contains("name"), "实际 SQL: " + sql);
        assertTrue(sql.contains("category"), "实际 SQL: " + sql);
        assertTrue(sql.contains("reviewStatus"), "实际 SQL: " + sql);
        assertTrue(sql.contains("userId"), "实际 SQL: " + sql);
        assertTrue(sql.contains("ORDER BY deletedAt DESC"), "实际 SQL: " + sql);
        verify(pictureMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("恢复：清除删除标记并按 picture.tags 重建标签关系，同时刷新缓存与索引")
    void restorePicture_restoresRowAndTags() {
        Picture deleted = picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue());
        deleted.setTags("[\"标签A\",\"标签B\"]");
        when(pictureMapper.selectDeletedById(1L)).thenReturn(deleted);
        when(pictureMapper.restoreById(1L)).thenReturn(1);

        assertTrue(pictureService.restorePicture(1L));

        verify(pictureMapper).restoreById(1L);
        verify(pictureTagMapper).deleteByPictureId(1L);
        verify(pictureTagMapper).insertBatch(1L, List.of("标签A", "标签B"));
        verify(pictureListCache).invalidate();
        verify(pictureEventPublisher).pictureChanged(1L);
    }

    @Test
    @DisplayName("恢复：超长标签按 picture_tag 约束过滤，不因历史脏数据整体失败")
    void restorePicture_filtersTagsExceedingRelationLimit() {
        Picture deleted = picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue());
        deleted.setTags("[\"标签A\",\"" + "x".repeat(40) + "\"]");
        when(pictureMapper.selectDeletedById(1L)).thenReturn(deleted);
        when(pictureMapper.restoreById(1L)).thenReturn(1);

        assertTrue(pictureService.restorePicture(1L));

        verify(pictureTagMapper).insertBatch(1L, List.of("标签A"));
    }

    @Test
    @DisplayName("恢复：不在回收站时返回不存在，不写数据库")
    void restorePicture_rejectsMissingDeletedPicture() {
        when(pictureMapper.selectDeletedById(1L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> pictureService.restorePicture(1L));

        assertEquals("回收站中不存在该图片", e.getMessage());
        verify(pictureMapper, never()).restoreById(any());
        verifyNoInteractions(pictureTagMapper, pictureListCache, pictureEventPublisher);
    }

    @Test
    @DisplayName("恢复：并发下已被恢复或清理时提示刷新，不重建标签")
    void restorePicture_rejectsConcurrentRestore() {
        Picture deleted = picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue());
        when(pictureMapper.selectDeletedById(1L)).thenReturn(deleted);
        when(pictureMapper.restoreById(1L)).thenReturn(0);

        assertThrows(BusinessException.class, () -> pictureService.restorePicture(1L));

        verifyNoInteractions(pictureTagMapper, pictureListCache, pictureEventPublisher);
    }

    // ==================== 回收站：到期物理清理 ====================

    @Test
    @DisplayName("清理：先删 COS 再物理删除数据库行，并防御性清理标签与 AI 建议")
    void purgeExpiredPictures_deletesCosThenRows() {
        Picture first = expiredPicture(1L, "https://example.com/a.jpg");
        Picture second = expiredPicture(2L, "https://example.com/b.jpg");
        when(pictureMapper.selectPurgeCandidates(any(Date.class), eq(100)))
                .thenReturn(List.of(first, second));
        when(fileManager.deletePictureForPurge(anyString())).thenReturn(true);
        when(pictureMapper.deletePhysicallyByIds(anyCollection())).thenReturn(2);

        int purged = pictureService.purgeExpiredPictures(new Date(), 100);

        assertEquals(2, purged);
        verify(fileManager).deletePictureForPurge(first.getUrl());
        verify(fileManager).deletePictureForPurge(second.getUrl());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(pictureMapper).deletePhysicallyByIds(idsCaptor.capture());
        assertEquals(2, idsCaptor.getValue().size());
        assertTrue(idsCaptor.getValue().containsAll(List.of(1L, 2L)));
        verify(pictureTagMapper).deleteByPictureIds(idsCaptor.getValue());
        // 互动关系随图片物理删除；回收站阶段保留计数，恢复后收藏仍然有效
        verify(pictureInteractionMapper).deleteByPictureIds(idsCaptor.getValue());
        verify(pictureAiSuggestionMapper).delete(any());
        // 审核日志按合规要求保留，物理清理不得触碰
        verifyNoInteractions(pictureReviewLogService);
    }

    @Test
    @DisplayName("清理：COS 删除失败的图片保留记录，成功删除的图片继续物理清理")
    @SuppressWarnings("unchecked")
    void purgeExpiredPictures_keepsRowWhenCosDeleteFails() {
        Picture ok = expiredPicture(1L, "https://example.com/a.jpg");
        Picture failed = expiredPicture(2L, "https://example.com/b.jpg");
        when(pictureMapper.selectPurgeCandidates(any(Date.class), eq(100)))
                .thenReturn(List.of(ok, failed));
        when(fileManager.deletePictureForPurge(ok.getUrl())).thenReturn(true);
        when(fileManager.deletePictureForPurge(failed.getUrl())).thenReturn(false);
        when(pictureMapper.deletePhysicallyByIds(anyCollection())).thenReturn(1);

        int purged = pictureService.purgeExpiredPictures(new Date(), 100);

        assertEquals(1, purged);
        ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(pictureMapper).deletePhysicallyByIds(idsCaptor.capture());
        assertEquals(List.of(1L), List.copyOf(idsCaptor.getValue()),
                "COS 删除失败的图片必须保留到下一轮");
        verify(pictureTagMapper).deleteByPictureIds(idsCaptor.getValue());
        verify(pictureAiSuggestionMapper).delete(any());
    }

    @Test
    @DisplayName("清理：全部 COS 删除失败时不碰数据库")
    void purgeExpiredPictures_allCosFailuresSkipDatabase() {
        Picture failed = expiredPicture(1L, "https://example.com/a.jpg");
        when(pictureMapper.selectPurgeCandidates(any(Date.class), eq(100)))
                .thenReturn(List.of(failed));
        when(fileManager.deletePictureForPurge(failed.getUrl())).thenReturn(false);

        assertEquals(0, pictureService.purgeExpiredPictures(new Date(), 100));

        verify(pictureMapper, never()).deletePhysicallyByIds(anyCollection());
        verifyNoInteractions(pictureTagMapper, pictureAiSuggestionMapper);
    }

    @Test
    @DisplayName("清理：没有到期图片时不访问 COS")
    void purgeExpiredPictures_emptyCandidates() {
        when(pictureMapper.selectPurgeCandidates(any(Date.class), eq(100))).thenReturn(List.of());

        assertEquals(0, pictureService.purgeExpiredPictures(new Date(), 100));

        verifyNoInteractions(fileManager, pictureTagMapper, pictureAiSuggestionMapper);
    }

    @Test
    @DisplayName("清理：数据库删除异常时只记录日志，不中断定时任务")
    void purgeExpiredPictures_swallowsDatabaseFailure() {
        Picture expired = expiredPicture(1L, "https://example.com/a.jpg");
        when(pictureMapper.selectPurgeCandidates(any(Date.class), eq(100)))
                .thenReturn(List.of(expired));
        when(fileManager.deletePictureForPurge(expired.getUrl())).thenReturn(true);
        when(pictureMapper.deletePhysicallyByIds(anyCollection()))
                .thenThrow(new RuntimeException("db down"));

        assertEquals(0, assertDoesNotThrow(
                () -> pictureService.purgeExpiredPictures(new Date(), 100)));
    }

    @Test
    @DisplayName("上传补偿：数据库保存返回 false 时删除刚上传的 COS 对象")
    void uploadPicture_saveReturnsFalseCompensatesCos() {
        User normalUser = user(100L, "user");
        when(userService.isAdmin(normalUser)).thenReturn(false);
        when(fileManager.uploadPicture(any(), anyString())).thenReturn(uploadResult("https://example.com/new.jpg"));
        when(pictureMapper.insert(any(Picture.class))).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> pictureService.uploadPicture(jpegFile(), null, normalUser));

        verify(fileManager).deletePicture("https://example.com/new.jpg");
        verifyNoInteractions(pictureEventPublisher);
    }

    @Test
    @DisplayName("上传补偿：数据库保存抛异常时删除刚上传的 COS 对象并继续抛错")
    void uploadPicture_saveThrowsCompensatesCos() {
        User normalUser = user(100L, "user");
        when(userService.isAdmin(normalUser)).thenReturn(false);
        when(fileManager.uploadPicture(any(), anyString())).thenReturn(uploadResult("https://example.com/new.jpg"));
        when(pictureMapper.insert(any(Picture.class))).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class,
                () -> pictureService.uploadPicture(jpegFile(), null, normalUser));

        verify(fileManager).deletePicture("https://example.com/new.jpg");
        verifyNoInteractions(pictureEventPublisher);
    }

    @Test
    @DisplayName("重传：更新成功后删除旧 URL")
    void uploadPicture_reuploadDeletesOldUrl() {
        User normalUser = user(100L, "user");
        when(userService.isAdmin(normalUser)).thenReturn(false);
        Picture oldPicture = picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue());
        oldPicture.setUrl("https://example.com/old.jpg");
        when(pictureMapper.selectById(1L)).thenReturn(oldPicture);
        when(fileManager.uploadPicture(any(), anyString())).thenReturn(uploadResult("https://example.com/new.jpg"));
        when(pictureMapper.update(any(Picture.class), any(Wrapper.class))).thenReturn(1);

        pictureService.uploadPicture(jpegFile(), uploadRequest(1L), normalUser);

        verify(fileManager).deletePicture("https://example.com/old.jpg");
        verify(pictureEventPublisher).pictureUploaded(1L);
    }

    @Test
    @DisplayName("重传：更新失败时删除新 URL，保留旧 URL")
    void uploadPicture_reuploadFailureDeletesNewUrl() {
        User normalUser = user(100L, "user");
        when(userService.isAdmin(normalUser)).thenReturn(false);
        Picture oldPicture = picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue());
        oldPicture.setUrl("https://example.com/old.jpg");
        when(pictureMapper.selectById(1L)).thenReturn(oldPicture);
        when(fileManager.uploadPicture(any(), anyString())).thenReturn(uploadResult("https://example.com/new.jpg"));
        when(pictureMapper.update(any(Picture.class), any(Wrapper.class))).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> pictureService.uploadPicture(jpegFile(), uploadRequest(1L), normalUser));

        verify(fileManager).deletePicture("https://example.com/new.jpg");
        verify(fileManager, never()).deletePicture("https://example.com/old.jpg");
        verifyNoInteractions(pictureEventPublisher);
    }

    // ==================== 编辑 ====================

    @Test
    @DisplayName("编辑：普通用户修改已通过图片的名称后重新进入待审核并清空审核信息")
    void editPicture_normalUserContentChangeResetsReview() {
        User owner = user(100L, "user");
        when(userService.isAdmin(owner)).thenReturn(false);
        Picture oldPicture = editablePicture(PictureReviewStatusEnum.PASS.getValue());
        oldPicture.setReviewMessage("审核通过");
        oldPicture.setReviewerId(1L);
        oldPicture.setReviewTime(new Date());
        when(pictureMapper.selectById(1L)).thenReturn(oldPicture);
        when(pictureMapper.update(any(Picture.class), any(Wrapper.class))).thenReturn(1);

        PictureEditRequest request = editRequest("新名称");
        assertTrue(pictureService.editPicture(request, owner));

        ArgumentCaptor<Picture> entityCaptor = ArgumentCaptor.forClass(Picture.class);
        ArgumentCaptor<Wrapper<Picture>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(pictureMapper).update(entityCaptor.capture(), wrapperCaptor.capture());
        assertEquals("新名称", entityCaptor.getValue().getName());
        assertNotNull(entityCaptor.getValue().getEditTime(), "编辑时间应被刷新");
        UpdateWrapper<Picture> wrapper = (UpdateWrapper<Picture>) wrapperCaptor.getValue();
        String sqlSet = wrapper.getSqlSet();
        assertTrue(sqlSet.contains("reviewStatus"), "实际 SET: " + sqlSet);
        assertTrue(sqlSet.contains("reviewMessage"), "实际 SET: " + sqlSet);
        assertTrue(sqlSet.contains("reviewerId"), "实际 SET: " + sqlSet);
        assertTrue(sqlSet.contains("reviewTime"), "实际 SET: " + sqlSet);
        assertTrue(wrapper.getParamNameValuePairs()
                        .containsValue(PictureReviewStatusEnum.REVIEWING.getValue()),
                "reviewStatus 应被置为待审核，实际参数: " + wrapper.getParamNameValuePairs());
        verify(pictureMapper, never()).updateById(any(Picture.class));
        verify(pictureListCache).invalidate();
        verify(pictureEventPublisher).pictureChanged(1L);
    }

    @Test
    @DisplayName("编辑：内容没有变化（含标签顺序不同）时不动审核状态")
    void editPicture_noContentChangeKeepsReviewStatus() {
        User owner = user(100L, "user");
        when(userService.isAdmin(owner)).thenReturn(false);
        Picture oldPicture = editablePicture(PictureReviewStatusEnum.PASS.getValue());
        when(pictureMapper.selectById(1L)).thenReturn(oldPicture);
        when(pictureMapper.updateById(any(Picture.class))).thenReturn(1);

        PictureEditRequest request = editRequest("旧名称");
        // 只调整标签顺序，语义上没有变化
        request.setTags(List.of("标签B", "标签A"));
        assertTrue(pictureService.editPicture(request, owner));

        ArgumentCaptor<Picture> entityCaptor = ArgumentCaptor.forClass(Picture.class);
        verify(pictureMapper).updateById(entityCaptor.capture());
        assertNull(entityCaptor.getValue().getReviewStatus(), "内容未变化时不得改动审核状态");
        verify(pictureMapper, never()).update(any(Picture.class), any(Wrapper.class));
        verify(pictureListCache, never()).invalidate();
        verifyNoInteractions(pictureEventPublisher);
    }

    @Test
    @DisplayName("编辑：管理员修改他人图片内容不改变审核状态")
    void editPicture_adminContentChangeKeepsReviewStatus() {
        User admin = admin();
        when(userService.isAdmin(admin)).thenReturn(true);
        when(pictureMapper.selectById(1L))
                .thenReturn(editablePicture(PictureReviewStatusEnum.PASS.getValue()));
        when(pictureMapper.updateById(any(Picture.class))).thenReturn(1);

        PictureEditRequest request = editRequest("管理员改名");
        assertTrue(pictureService.editPicture(request, admin));

        ArgumentCaptor<Picture> entityCaptor = ArgumentCaptor.forClass(Picture.class);
        verify(pictureMapper).updateById(entityCaptor.capture());
        assertNull(entityCaptor.getValue().getReviewStatus(), "管理员编辑不应改动审核状态");
        verify(pictureMapper, never()).update(any(Picture.class), any(Wrapper.class));
        verify(pictureEventPublisher).pictureChanged(1L);
    }

    @Test
    @DisplayName("编辑：已拒绝的图片修改内容后重新进入待审核")
    void editPicture_rejectedPictureChangeResetsReview() {
        User owner = user(100L, "user");
        when(userService.isAdmin(owner)).thenReturn(false);
        when(pictureMapper.selectById(1L))
                .thenReturn(editablePicture(PictureReviewStatusEnum.REJECT.getValue()));
        when(pictureMapper.update(any(Picture.class), any(Wrapper.class))).thenReturn(1);

        assertTrue(pictureService.editPicture(editRequest("申诉后改名"), owner));

        ArgumentCaptor<Wrapper<Picture>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(pictureMapper).update(any(Picture.class), wrapperCaptor.capture());
        UpdateWrapper<Picture> wrapper = (UpdateWrapper<Picture>) wrapperCaptor.getValue();
        assertTrue(wrapper.getParamNameValuePairs()
                        .containsValue(PictureReviewStatusEnum.REVIEWING.getValue()),
                "已拒绝的图片改内容后应回到待审核");
        verify(pictureEventPublisher).pictureChanged(1L);
    }

    @Test
    @DisplayName("编辑：普通用户不能编辑他人图片，且不写数据库")
    void editPicture_rejectsOtherUser() {
        User other = user(200L, "user");
        when(userService.isAdmin(other)).thenReturn(false);
        when(pictureMapper.selectById(1L))
                .thenReturn(editablePicture(PictureReviewStatusEnum.PASS.getValue()));

        assertThrows(BusinessException.class,
                () -> pictureService.editPicture(editRequest("越权改名"), other));

        verify(pictureMapper, never()).updateById(any(Picture.class));
        verify(pictureMapper, never()).update(any(), any(Wrapper.class));
        verifyNoInteractions(pictureEventPublisher);
    }

    @Test
    @DisplayName("编辑：未登录用户不能编辑图片")
    void editPicture_rejectsAnonymous() {
        assertThrows(BusinessException.class,
                () -> pictureService.editPicture(editRequest("匿名改名"), null));
        verify(pictureMapper, never()).selectById(any());
        verifyNoInteractions(pictureEventPublisher);
    }

    // ==================== 审核 ====================

    @Test
    @DisplayName("审核：请求为空应拒绝")
    void doPictureReview_rejectsNullRequest() {
        assertThrows(BusinessException.class,
                () -> pictureService.doPictureReview(null, admin()));
    }

    @Test
    @DisplayName("审核：目标状态不能是待审核")
    void doPictureReview_rejectsReviewingStatus() {
        PictureReviewRequest request = new PictureReviewRequest();
        request.setId(1L);
        request.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());

        assertThrows(BusinessException.class, () -> pictureService.doPictureReview(request, admin()));
    }

    @Test
    @DisplayName("审核：状态未变化视为重复审核")
    void doPictureReview_rejectsDuplicateStatus() {
        PictureReviewRequest request = new PictureReviewRequest();
        request.setId(1L);
        request.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        when(pictureMapper.selectById(1L)).thenReturn(
                picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue()));

        BusinessException e = assertThrows(BusinessException.class,
                () -> pictureService.doPictureReview(request, admin()));
        assertEquals("请勿重复审核", e.getMessage());
        verifyNoInteractions(pictureEventPublisher);
    }

    @Test
    @DisplayName("审核：图片不存在应拒绝")
    void doPictureReview_rejectsMissingPicture() {
        PictureReviewRequest request = new PictureReviewRequest();
        request.setId(1L);
        request.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        when(pictureMapper.selectById(1L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> pictureService.doPictureReview(request, admin()));
        verifyNoInteractions(pictureEventPublisher);
    }

    @Test
    @DisplayName("审核：只更新审核字段，不覆盖业务字段")
    void doPictureReview_updatesOnlyReviewFields() {
        PictureReviewRequest request = new PictureReviewRequest();
        request.setId(1L);
        request.setReviewStatus(PictureReviewStatusEnum.REJECT.getValue());
        request.setReviewMessage("内容不合规");
        when(pictureMapper.selectById(1L)).thenReturn(
                picture(1L, 100L, PictureReviewStatusEnum.REVIEWING.getValue()));
        when(pictureMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        User admin = admin();
        pictureService.doPictureReview(request, admin);

        ArgumentCaptor<Wrapper<Picture>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(pictureMapper).update(org.mockito.ArgumentMatchers.isNull(), wrapperCaptor.capture());
        UpdateWrapper<Picture> wrapper = (UpdateWrapper<Picture>) wrapperCaptor.getValue();
        String sqlSet = wrapper.getSqlSet();
        assertTrue(sqlSet.contains("reviewStatus"), "实际 SET: " + sqlSet);
        assertTrue(sqlSet.contains("reviewMessage"), "实际 SET: " + sqlSet);
        assertTrue(sqlSet.contains("reviewerId"), "实际 SET: " + sqlSet);
        assertTrue(sqlSet.contains("reviewTime"), "实际 SET: " + sqlSet);
        assertFalse(sqlSet.contains("url"), "不应覆盖 url: " + sqlSet);
        assertFalse(sqlSet.contains("name"), "不应覆盖 name: " + sqlSet);
        assertFalse(sqlSet.contains("userId"), "不应覆盖 userId: " + sqlSet);
        assertTrue(wrapper.getParamNameValuePairs()
                .containsValue(PictureReviewStatusEnum.REJECT.getValue()), "审核状态应写入参数");
        assertTrue(wrapper.getParamNameValuePairs().containsValue("内容不合规"), "审核意见应写入参数");
        assertTrue(wrapper.getParamNameValuePairs().containsValue(admin.getId()), "审核人应写入参数");
        assertTrue(wrapper.getSqlSegment().contains("reviewStatus"), "条件更新必须带上原状态");
        verify(pictureMapper, never()).updateById(any(Picture.class));
        verify(pictureReviewLogService).record(1L, admin.getId(),
                PictureReviewStatusEnum.REJECT.getValue(), "内容不合规");
        verify(pictureListCache).invalidate();
        verify(pictureEventPublisher).pictureChanged(1L);
    }

    @Test
    @DisplayName("审核：并发下状态已被其他操作改变时不写历史、不失效缓存")
    void doPictureReview_rejectsConcurrentChange() {
        PictureReviewRequest request = new PictureReviewRequest();
        request.setId(1L);
        request.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        when(pictureMapper.selectById(1L)).thenReturn(
                picture(1L, 100L, PictureReviewStatusEnum.REVIEWING.getValue()));
        // 机审自动处置或另一位管理员已经先完成更新
        when(pictureMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        BusinessException e = assertThrows(BusinessException.class,
                () -> pictureService.doPictureReview(request, admin()));

        assertEquals("图片已被其他操作处理，请刷新后重试", e.getMessage());
        verify(pictureReviewLogService, never()).record(any(), any(), any(), any());
        verify(pictureListCache, never()).invalidate();
        verifyNoInteractions(pictureEventPublisher);
    }

    @Test
    @DisplayName("审核历史：按图片 id 查询审核记录并返回审核人字段")
    void listPictureReviewLogs_returnsLogs() {
        PictureReviewLogVO reviewLog = new PictureReviewLogVO();
        reviewLog.setId(1L);
        reviewLog.setPictureId(2L);
        reviewLog.setReviewerName("管理员");
        when(pictureReviewLogService.listVOByPictureId(2L)).thenReturn(List.of(reviewLog));

        List<PictureReviewLogVO> logs = pictureService.listPictureReviewLogs(2L);

        assertEquals(1, logs.size());
        assertEquals(Long.valueOf(1L), logs.get(0).getId());
        assertEquals("管理员", logs.get(0).getReviewerName());
    }

    // ==================== 排序 ====================

    @Test
    @DisplayName("排序：白名单内的字段按指定方向拼接 ORDER BY")
    void getQueryWrapper_sortsByWhitelistedField() {
        String descSql = pictureService.getQueryWrapper(queryRequest("createTime", "descend")).getSqlSegment();
        String ascSql = pictureService.getQueryWrapper(queryRequest("picSize", "ascend")).getSqlSegment();

        assertTrue(descSql.contains("ORDER BY createTime DESC"), "实际 SQL 片段: " + descSql);
        assertTrue(ascSql.contains("ORDER BY picSize ASC"), "实际 SQL 片段: " + ascSql);
    }

    @Test
    @DisplayName("排序：审核页按机审状态优先时，同状态再按创建时间倒序")
    void getQueryWrapper_sortsByMachineReviewStatusThenCreateTime() {
        String sql = pictureService.getQueryWrapper(queryRequest("machineReviewStatus", "descend"))
                .getSqlSegment();

        assertTrue(sql.contains("ORDER BY machineReviewStatus DESC"), "实际 SQL 片段: " + sql);
        assertTrue(sql.contains("createTime DESC"), "实际 SQL 片段: " + sql);
    }

    @Test
    @DisplayName("排序：不在白名单内的字段应拒绝，注入片段不能进入 SQL")
    void getQueryWrapper_rejectsSortFieldOutsideWhitelist() {
        List<String> illegalSortFields = List.of(
                "createTime,(select 1)", "(select 1)", "createTime desc", "isDelete", "notAColumn", " ");

        for (String sortField : illegalSortFields) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> pictureService.getQueryWrapper(queryRequest(sortField, "descend")),
                    "应拒绝排序字段: " + sortField);
            assertEquals("排序字段不合法", e.getMessage());
        }
    }

    @Test
    @DisplayName("排序：不传排序字段时不拼接 ORDER BY，sortOrder 为空也不抛空指针")
    void getQueryWrapper_skipsOrderByWithoutSortField() {
        String sql = pictureService.getQueryWrapper(queryRequest(null, null)).getSqlSegment();

        assertFalse(sql.contains("ORDER BY"), "实际 SQL 片段: " + sql);
    }

    @Test
    @DisplayName("排序：sortOrder 为空时按降序处理")
    void getQueryWrapper_treatsNullSortOrderAsDescend() {
        String sql = pictureService.getQueryWrapper(queryRequest("createTime", null)).getSqlSegment();

        assertTrue(sql.contains("ORDER BY createTime DESC"), "实际 SQL 片段: " + sql);
    }

    @Test
    @DisplayName("排序：白名单内的字段必须都是 Picture 实体的属性")
    void sortFieldWhitelist_containsOnlyEntityFields() {
        for (String sortField : PictureServiceImpl.SORT_FIELD_WHITELIST) {
            assertDoesNotThrow(() -> Picture.class.getDeclaredField(sortField),
                    "白名单字段不是 Picture 的属性: " + sortField);
        }
    }

    @Test
    @DisplayName("排序：热度字段在排序时追加 id 次级排序，同值翻页不会重复或漏项")
    void getQueryWrapper_hotSortAddsIdTieBreaker() {
        for (String hotField : List.of("downloadCount", "favoriteCount", "likeCount")) {
            String sql = pictureService.getQueryWrapper(queryRequest(hotField, "descend"))
                    .getSqlSegment();

            assertTrue(sql.contains("ORDER BY " + hotField + " DESC"), "实际 SQL 片段: " + sql);
            assertTrue(sql.contains("id ASC"), "热度排序必须追加 id 次级排序: " + sql);
        }
    }

    @Test
    @DisplayName("排序：非热度字段不追加 id 次级排序，保持既有行为")
    void getQueryWrapper_nonHotSortKeepsLegacyOrder() {
        String sql = pictureService.getQueryWrapper(queryRequest("createTime", "descend"))
                .getSqlSegment();

        assertTrue(sql.contains("ORDER BY createTime DESC"), "实际 SQL 片段: " + sql);
        assertFalse(sql.contains("id ASC"), "非热度排序不应改变既有 ORDER BY: " + sql);
    }

    @Test
    @DisplayName("分页查询：带标签时走 picture_tag JOIN，不拼 LIKE")
    void pagePictures_withTagsUsesJoinMapper() {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setTags(List.of(" 标签A ", "标签A", "", "标签B"));
        when(pictureMapper.selectPageByTags(any(), any(), any(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Page<Picture> result = pictureService.pagePictures(new Page<>(1, 10), request);

        ArgumentCaptor<List<String>> tagsCaptor = ArgumentCaptor.forClass(List.class);
        verify(pictureMapper).selectPageByTags(any(), any(), tagsCaptor.capture(), eq(2));
        assertEquals(List.of("标签A", "标签B"), tagsCaptor.getValue(),
                "标签应去空、去重、去首尾空格后再查关系表");
        assertNotNull(result);
        verify(pictureMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("分页查询：不带标签时沿用 QueryWrapper 分页")
    void pagePictures_withoutTagsUsesPageWrapper() {
        PictureQueryRequest request = new PictureQueryRequest();
        when(pictureMapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        Page<Picture> result = pictureService.pagePictures(new Page<>(1, 10), request);

        assertNotNull(result);
        verify(pictureMapper).selectPage(any(), any());
        verify(pictureMapper, never()).selectPageByTags(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("编辑：标签写库时同步替换 picture_tag 关系")
    void editPicture_syncsPictureTagRelation() {
        User owner = user(100L, "user");
        when(userService.isAdmin(owner)).thenReturn(false);
        when(pictureMapper.selectById(1L))
                .thenReturn(editablePicture(PictureReviewStatusEnum.REVIEWING.getValue()));
        when(pictureMapper.updateById(any(Picture.class))).thenReturn(1);

        PictureEditRequest request = editRequest("旧名称");
        request.setTags(List.of(" 标签A ", "标签A", "", "标签B"));

        assertTrue(pictureService.editPicture(request, owner));

        verify(pictureTagMapper).deleteByPictureId(1L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> tagsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(pictureTagMapper).insertBatch(eq(1L), tagsCaptor.capture());
        assertEquals(List.of("标签A", "标签B"), List.copyOf(tagsCaptor.getValue()));
    }

    @Test
    @DisplayName("管理员更新：标签写库时同步替换 picture_tag 关系")
    void updatePicture_syncsPictureTagRelation() {
        when(pictureMapper.selectById(1L))
                .thenReturn(editablePicture(PictureReviewStatusEnum.PASS.getValue()));
        when(pictureMapper.updateById(any(Picture.class))).thenReturn(1);

        PictureUpdateRequest request = new PictureUpdateRequest();
        request.setId(1L);
        request.setTags(List.of("标签C"));

        assertTrue(pictureService.updatePicture(request));

        verify(pictureTagMapper).deleteByPictureId(1L);
        verify(pictureTagMapper).insertBatch(1L, List.of("标签C"));
    }

    @Test
    @DisplayName("标签分类：直接从字典服务读取，不再扫描图片")
    void listTagCategory_delegatesToDictionary() {
        PictureTagCategory fromDictionary = new PictureTagCategory();
        fromDictionary.setCategoryList(List.of("模板"));
        fromDictionary.setTagList(List.of("热门"));
        when(tagCategoryService.listAll()).thenReturn(fromDictionary);

        PictureTagCategory result = pictureService.listTagCategory();

        assertSame(fromDictionary, result);
        verify(pictureMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("下载：已通过审核的图片可以读取用于下载")
    void getDownloadablePicture_returnsApprovedPicture() {
        Picture approved = picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue());
        when(pictureMapper.selectById(1L)).thenReturn(approved);

        assertSame(approved, pictureService.getDownloadablePicture(1L, new MockHttpServletRequest()));
    }

    @Test
    @DisplayName("下载：图片不存在时不返回内容")
    void getDownloadablePicture_rejectsMissingPicture() {
        when(pictureMapper.selectById(1L)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> pictureService.getDownloadablePicture(1L, new MockHttpServletRequest()));
    }

    @Test
    @DisplayName("下载统计：下载完成后原子自增下载次数")
    void incrementDownloadCount_incrementsDownloadCount() {
        when(pictureMapper.update(org.mockito.ArgumentMatchers.isNull(), any(Wrapper.class))).thenReturn(1);

        assertTrue(pictureService.incrementDownloadCount(1L));

        verify(pictureMapper).update(org.mockito.ArgumentMatchers.isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("下载统计：图片在下载过程中被删除时返回 false")
    void incrementDownloadCount_returnsFalseWhenPictureMissing() {
        when(pictureMapper.update(org.mockito.ArgumentMatchers.isNull(), any(Wrapper.class))).thenReturn(0);

        assertFalse(pictureService.incrementDownloadCount(1L));
    }

    @Test
    @DisplayName("批量审核：一条条件 UPDATE 更新整批，只写一条日志、失效一次缓存、发一条批量事件")
    void batchReview_updatesBatchAndSendsSingleEvent() {
        when(pictureMapper.selectByIds(anyCollection())).thenReturn(List.of(
                picture(1L, 100L, PictureReviewStatusEnum.REVIEWING.getValue()),
                picture(2L, 100L, PictureReviewStatusEnum.REVIEWING.getValue())));
        when(pictureMapper.update(org.mockito.ArgumentMatchers.isNull(), any(Wrapper.class))).thenReturn(2);

        PictureReviewBatchRequest request = new PictureReviewBatchRequest();
        request.setIds(List.of(1L, 2L));
        request.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());

        PictureReviewBatchResult result = pictureService.batchReview(request, admin());

        assertEquals(2, result.getSuccessCount());
        assertEquals(0, result.getFailCount());
        assertEquals(0, result.getErrors().size());
        ArgumentCaptor<Wrapper<Picture>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(pictureMapper).update(org.mockito.ArgumentMatchers.isNull(), wrapperCaptor.capture());
        UpdateWrapper<Picture> wrapper = (UpdateWrapper<Picture>) wrapperCaptor.getValue();
        assertTrue(wrapper.getSqlSegment().contains("id IN"), "实际 WHERE: " + wrapper.getSqlSegment());
        assertTrue(wrapper.getSqlSegment().contains("reviewStatus"), "实际 WHERE: " + wrapper.getSqlSegment());
        assertTrue(wrapper.getSqlSet().contains("reviewStatus"), "实际 SET: " + wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSet().contains("reviewerId"), "实际 SET: " + wrapper.getSqlSet());
        // 整批只写一条审核历史、只失效一次缓存、只发一条批量事件
        verify(pictureReviewLogService).recordBatch(List.of(1L, 2L), 1L,
                PictureReviewStatusEnum.PASS.getValue(), null);
        verify(pictureListCache).invalidate();
        verify(pictureEventPublisher).picturesChanged(List.of(1L, 2L));
        verify(pictureEventPublisher, never()).pictureChanged(any());
    }

    @Test
    @DisplayName("批量审核：不存在与非待审核的图片计入失败明细，不影响其他图片")
    void batchReview_continuesAfterFailure() {
        when(pictureMapper.selectByIds(anyCollection())).thenReturn(List.of(
                picture(1L, 100L, PictureReviewStatusEnum.REVIEWING.getValue()),
                picture(2L, 100L, PictureReviewStatusEnum.PASS.getValue())));
        when(pictureMapper.update(org.mockito.ArgumentMatchers.isNull(), any(Wrapper.class))).thenReturn(1);

        PictureReviewBatchRequest request = new PictureReviewBatchRequest();
        request.setIds(List.of(1L, 2L, 3L));
        request.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());

        PictureReviewBatchResult result = pictureService.batchReview(request, admin());

        assertEquals(1, result.getSuccessCount(), "只有 id=1 是待审核");
        assertEquals(2, result.getFailCount());
        assertEquals(2, result.getErrors().size());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("图片 2")));
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("图片 3")));
        verify(pictureReviewLogService).recordBatch(List.of(1L), 1L,
                PictureReviewStatusEnum.PASS.getValue(), null);
        verify(pictureEventPublisher).picturesChanged(List.of(1L));
    }

    @Test
    @DisplayName("批量审核：并发下被其他请求抢先审核的图片重新回查，并给出失败明细")
    void batchReview_rechecksIdsChangedConcurrently() {
        when(pictureMapper.selectByIds(anyCollection())).thenReturn(List.of(
                picture(1L, 100L, PictureReviewStatusEnum.REVIEWING.getValue()),
                picture(2L, 100L, PictureReviewStatusEnum.REVIEWING.getValue())));
        // 查询时两张都是待审核，UPDATE 只命中了一张
        when(pictureMapper.update(org.mockito.ArgumentMatchers.isNull(), any(Wrapper.class))).thenReturn(1);
        when(pictureMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                picture(2L, 100L, PictureReviewStatusEnum.REVIEWING.getValue())));

        PictureReviewBatchRequest request = new PictureReviewBatchRequest();
        request.setIds(List.of(1L, 2L));
        request.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());

        PictureReviewBatchResult result = pictureService.batchReview(request, admin());

        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        assertTrue(result.getErrors().get(0).contains("图片 2"));
        verify(pictureReviewLogService).recordBatch(List.of(1L), 1L,
                PictureReviewStatusEnum.PASS.getValue(), null);
        verify(pictureEventPublisher).picturesChanged(List.of(1L));
    }

    @Test
    @DisplayName("批量审核：全部图片都已审核时不更新、不写日志、不失效缓存、不发事件")
    void batchReview_skipsSideEffectsWhenNothingUpdated() {
        when(pictureMapper.selectByIds(anyCollection())).thenReturn(List.of(
                picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue())));

        PictureReviewBatchRequest request = new PictureReviewBatchRequest();
        request.setIds(List.of(1L));
        request.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());

        PictureReviewBatchResult result = pictureService.batchReview(request, admin());

        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        assertTrue(result.getErrors().get(0).contains("请勿重复审核"));
        verify(pictureMapper, never()).update(any(), any(Wrapper.class));
        verifyNoInteractions(pictureReviewLogService, pictureListCache, pictureEventPublisher);
    }

    @Test
    @DisplayName("批量审核：目标状态不能是待审核")
    void batchReview_rejectsReviewingTarget() {
        PictureReviewBatchRequest request = new PictureReviewBatchRequest();
        request.setIds(List.of(1L));
        request.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());

        assertThrows(BusinessException.class, () -> pictureService.batchReview(request, admin()));
        verifyNoInteractions(pictureMapper);
    }

    // ==================== 字段长度校验 ====================

    @Test
    @DisplayName("校验：名称超过 128 个字符应拒绝")
    void validPicture_rejectsTooLongName() {
        Picture picture = validPictureEntity();
        picture.setName("a".repeat(129));

        BusinessException e = assertThrows(BusinessException.class, () -> pictureService.validPicture(picture));
        assertEquals("图片名称不能超过 128 个字符", e.getMessage());
    }

    @Test
    @DisplayName("校验：名称刚好 128 个字符可以通过")
    void validPicture_acceptsNameAtLimit() {
        Picture picture = validPictureEntity();
        picture.setName("a".repeat(128));

        assertDoesNotThrow(() -> pictureService.validPicture(picture));
    }

    @Test
    @DisplayName("校验：分类超过 64 个字符应拒绝")
    void validPicture_rejectsTooLongCategory() {
        Picture picture = validPictureEntity();
        picture.setCategory("c".repeat(65));

        BusinessException e = assertThrows(BusinessException.class, () -> pictureService.validPicture(picture));
        assertEquals("分类不能超过 64 个字符", e.getMessage());
    }

    @Test
    @DisplayName("校验：简介超过 512 个字符应拒绝")
    void validPicture_rejectsTooLongIntroduction() {
        Picture picture = validPictureEntity();
        picture.setIntroduction("i".repeat(513));

        BusinessException e = assertThrows(BusinessException.class, () -> pictureService.validPicture(picture));
        assertEquals("简介不能超过 512 个字符", e.getMessage());
    }

    @Test
    @DisplayName("校验：单个标签超过 32 个字符应拒绝")
    void validPicture_rejectsTooLongTag() {
        Picture picture = validPictureEntity();
        picture.setTags("[\"" + "t".repeat(33) + "\"]");

        BusinessException e = assertThrows(BusinessException.class, () -> pictureService.validPicture(picture));
        assertEquals("单个标签不能超过 32 个字符", e.getMessage());
    }

    @Test
    @DisplayName("校验：标签超过 20 个应拒绝")
    void validPicture_rejectsTooManyTags() {
        Picture picture = validPictureEntity();
        picture.setTags(IntStream.range(0, 21)
                .mapToObj(i -> "\"标签" + i + "\"")
                .collect(Collectors.joining(",", "[", "]")));

        BusinessException e = assertThrows(BusinessException.class, () -> pictureService.validPicture(picture));
        assertEquals("标签最多 20 个", e.getMessage());
    }

    @Test
    @DisplayName("校验：20 个 32 字符标签（JSON 超过 512）应通过，不再受旧总长度限制")
    void validPicture_acceptsMaxTagsBeyondLegacyJsonLimit() {
        Picture picture = validPictureEntity();
        String tag = "t".repeat(32);
        String tagsJson = IntStream.range(0, 20)
                .mapToObj(i -> "\"" + tag + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        assertTrue(tagsJson.length() > 512, "构造的 JSON 应超过旧上限，实际 " + tagsJson.length());
        picture.setTags(tagsJson);

        assertDoesNotThrow(() -> pictureService.validPicture(picture));
    }

    @Test
    @DisplayName("校验：url 超过 512 个字符应拒绝")
    void validPicture_rejectsTooLongUrl() {
        Picture picture = validPictureEntity();
        picture.setUrl("https://example.com/" + "u".repeat(500));

        BusinessException e = assertThrows(BusinessException.class, () -> pictureService.validPicture(picture));
        assertEquals("url 不能超过 512 个字符", e.getMessage());
    }

    @Test
    @DisplayName("审核：审核意见超过 512 个字符应拒绝")
    void doPictureReview_rejectsTooLongMessage() {
        PictureReviewRequest request = new PictureReviewRequest();
        request.setId(1L);
        request.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        request.setReviewMessage("m".repeat(513));

        BusinessException e = assertThrows(BusinessException.class,
                () -> pictureService.doPictureReview(request, admin()));
        assertEquals("审核意见不能超过 512 个字符", e.getMessage());
    }

    // ==================== M1：审核通知 ====================

    @Test
    @DisplayName("审核通知：审核通过后给作者发一条通知，dedupeKey 按图片去重")
    void doPictureReview_publishesPassNotification() {
        Picture picture = picture(1L, 100L, PictureReviewStatusEnum.REVIEWING.getValue());
        picture.setName("风景照");
        when(pictureMapper.selectById(1L)).thenReturn(picture);
        when(pictureMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        PictureReviewRequest request = new PictureReviewRequest();
        request.setId(1L);
        request.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());

        pictureService.doPictureReview(request, admin());

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notificationEventPublisher).publish(captor.capture());
        NotificationMessage message = captor.getValue();
        assertEquals(NotificationTypeEnum.REVIEW_PASS, message.getType());
        assertEquals(100L, message.getUserId());
        assertEquals(1L, message.getBizId());
        assertEquals("REVIEW_PASS:1", message.getDedupeKey(), "重复审核不能产生第二条通知");
        assertTrue(message.getContent().contains("风景照"), message.getContent());
    }

    @Test
    @DisplayName("审核通知：拒绝时把审核意见带给作者，意见为空时给默认文案")
    void doPictureReview_publishesRejectNotification() {
        Picture picture = picture(2L, 100L, PictureReviewStatusEnum.REVIEWING.getValue());
        picture.setName("海报");
        when(pictureMapper.selectById(2L)).thenReturn(picture);
        when(pictureMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        PictureReviewRequest request = new PictureReviewRequest();
        request.setId(2L);
        request.setReviewStatus(PictureReviewStatusEnum.REJECT.getValue());

        pictureService.doPictureReview(request, admin());

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notificationEventPublisher).publish(captor.capture());
        NotificationMessage message = captor.getValue();
        assertEquals(NotificationTypeEnum.REVIEW_REJECT, message.getType());
        assertEquals("REVIEW_REJECT:2", message.getDedupeKey());
        assertTrue(message.getContent().contains("海报"), message.getContent());
        assertTrue(message.getContent().contains("未填写审核意见"), message.getContent());
    }

    @Test
    @DisplayName("审核通知：自己审核自己的图片不发通知")
    void doPictureReview_skipsNotificationForOwnPicture() {
        Picture picture = picture(1L, admin().getId(), PictureReviewStatusEnum.REVIEWING.getValue());
        when(pictureMapper.selectById(1L)).thenReturn(picture);
        when(pictureMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        PictureReviewRequest request = new PictureReviewRequest();
        request.setId(1L);
        request.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());

        pictureService.doPictureReview(request, admin());

        verifyNoInteractions(notificationEventPublisher);
    }

    @Test
    @DisplayName("审核通知：批量审核按整批聚合，只发一次事件并排除自己的图片")
    void batchReview_publishesAggregatedNotifications() {
        Picture ownPicture = picture(3L, admin().getId(), PictureReviewStatusEnum.REVIEWING.getValue());
        ownPicture.setName("管理员的图");
        when(pictureMapper.selectByIds(anyCollection())).thenReturn(List.of(
                picture(1L, 100L, PictureReviewStatusEnum.REVIEWING.getValue()),
                picture(2L, 100L, PictureReviewStatusEnum.REVIEWING.getValue()),
                ownPicture));
        when(pictureMapper.update(org.mockito.ArgumentMatchers.isNull(), any(Wrapper.class))).thenReturn(3);

        PictureReviewBatchRequest request = new PictureReviewBatchRequest();
        request.setIds(List.of(1L, 2L, 3L));
        request.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());

        pictureService.batchReview(request, admin());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<NotificationMessage>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(notificationEventPublisher).publishAll(captor.capture());
        List<Long> notifiedUserIds = captor.getValue().stream()
                .map(NotificationMessage::getUserId)
                .toList();
        assertEquals(List.of(100L, 100L), notifiedUserIds, "自己的图片不通知，其余每张一条");
        assertTrue(captor.getValue().stream().allMatch(message ->
                NotificationTypeEnum.REVIEW_PASS.equals(message.getType())));
        verify(notificationEventPublisher, never()).publish(any());
    }

    // ==================== 辅助方法 ====================
    private Picture validPictureEntity() {
        Picture picture = new Picture();
        picture.setId(1L);
        picture.setUrl("https://example.com/public/1/a.jpg");
        picture.setName("图片");
        picture.setCategory("分类");
        picture.setIntroduction("简介");
        picture.setTags("[\"标签\"]");
        picture.setPicFormat("jpg");
        return picture;
    }

    private PictureUploadRequest uploadRequest(Long id) {
        PictureUploadRequest request = new PictureUploadRequest();
        request.setId(id);
        return request;
    }

    private UploadPictureResult uploadResult() {
        return uploadResult("https://example.com/public/100/2026-11-11_test.jpg");
    }

    private UploadPictureResult uploadResult(String url) {
        UploadPictureResult result = new UploadPictureResult();
        result.setUrl(url);
        result.setPicName("test");
        result.setPicSize(1024L);
        result.setPicWidth(100);
        result.setPicHeight(100);
        result.setPicScale(1.0);
        result.setPicFormat("jpg");
        return result;
    }

    private MockMultipartFile jpegFile() {
        // 最小 JPEG 文件头，仅用于 service 层测试（不经过 FileManager 校验）
        return new MockMultipartFile("file", "test.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0});
    }

    private PictureQueryRequest queryRequest(String sortField, String sortOrder) {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setSortField(sortField);
        request.setSortOrder(sortOrder);
        return request;
    }

    private Picture editablePicture(int reviewStatus) {
        Picture picture = picture(1L, 100L, reviewStatus);
        picture.setName("旧名称");
        picture.setIntroduction("旧简介");
        picture.setCategory("风景");
        picture.setTags("[\"标签A\",\"标签B\"]");
        return picture;
    }

    private PictureEditRequest editRequest(String name) {
        PictureEditRequest request = new PictureEditRequest();
        request.setId(1L);
        request.setName(name);
        request.setIntroduction("旧简介");
        request.setCategory("风景");
        request.setTags(List.of("标签A", "标签B"));
        return request;
    }

    private Picture picture(Long id, Long userId, int reviewStatus) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setUserId(userId);
        picture.setReviewStatus(reviewStatus);
        return picture;
    }

    private Picture expiredPicture(Long id, String url) {
        Picture picture = picture(id, 100L, PictureReviewStatusEnum.PASS.getValue());
        picture.setUrl(url);
        picture.setIsDelete(1);
        picture.setDeletedAt(new Date(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000));
        return picture;
    }

    private Picture rejectedPicture() {
        Picture picture = picture(1L, 100L, PictureReviewStatusEnum.REJECT.getValue());
        picture.setName("被拒绝的图片");
        picture.setReviewMessage("内容不合规");
        picture.setReviewerId(99L);
        picture.setReviewTime(new Date());
        return picture;
    }

    private User user(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }

    private User admin() {
        return user(1L, "admin");
    }
}
