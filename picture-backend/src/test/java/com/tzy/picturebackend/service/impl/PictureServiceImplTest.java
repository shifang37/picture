package com.tzy.picturebackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.manager.FileManager;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.model.dto.file.UploadPictureResult;
import com.tzy.picturebackend.model.dto.picture.PictureEditRequest;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.picture.PictureReviewBatchRequest;
import com.tzy.picturebackend.model.dto.picture.PictureReviewRequest;
import com.tzy.picturebackend.model.dto.picture.PictureUploadRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
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

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    private FileManager fileManager;

    private PictureReviewLogService pictureReviewLogService;

    private TagCategoryService tagCategoryService;

    @BeforeEach
    void setUp() {
        pictureService = new PictureServiceImpl();
        userService = mock(UserService.class);
        pictureMapper = mock(PictureMapper.class);
        fileManager = mock(FileManager.class);
        pictureReviewLogService = mock(PictureReviewLogService.class);
        tagCategoryService = mock(TagCategoryService.class);
        ReflectionTestUtils.setField(pictureService, "userService", userService);
        ReflectionTestUtils.setField(pictureService, "baseMapper", pictureMapper);
        ReflectionTestUtils.setField(pictureService, "fileManager", fileManager);
        ReflectionTestUtils.setField(pictureService, "pictureReviewLogService", pictureReviewLogService);
        ReflectionTestUtils.setField(pictureService, "tagCategoryService", tagCategoryService);
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

    // ==================== 上传 ====================

    @Test
    @DisplayName("上传：普通用户上传后进入待审核，不写审核人")
    void uploadPicture_normalUserEntersReviewing() {
        User normalUser = user(100L, "user");
        when(userService.isAdmin(normalUser)).thenReturn(false);
        when(fileManager.uploadPicture(any(), anyString())).thenReturn(uploadResult());
        when(pictureMapper.insert(any(Picture.class))).thenReturn(1);

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
        verifyNoInteractions(pictureReviewLogService);
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
    }

    @Test
    @DisplayName("上传：未登录用户不能上传")
    void uploadPicture_rejectsAnonymous() {
        assertThrows(BusinessException.class,
                () -> pictureService.uploadPicture(jpegFile(), null, null));
        verifyNoInteractions(fileManager);
    }

    // ==================== 删除 ====================

    @Test
    @DisplayName("删除：本人删除成功时同时清理 COS")
    void deletePicture_ownerDeletesAndCleansCos() {
        User owner = user(100L, "user");
        Picture existing = picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue());
        existing.setUrl("https://example.com/public/100/2026-11-11_old.jpg");
        when(pictureMapper.selectById(1L)).thenReturn(existing);
        when(pictureMapper.deleteById(1L)).thenReturn(1);

        assertTrue(pictureService.deletePicture(1L, owner));

        verify(pictureMapper).deleteById(1L);
        verify(fileManager).deletePicture(existing.getUrl());
    }

    @Test
    @DisplayName("删除：管理员可以删除他人图片并清理 COS")
    void deletePicture_adminDeletesOthersPicture() {
        User admin = admin();
        when(userService.isAdmin(admin)).thenReturn(true);
        Picture existing = picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue());
        existing.setUrl("https://example.com/public/100/a.jpg");
        when(pictureMapper.selectById(1L)).thenReturn(existing);
        when(pictureMapper.deleteById(1L)).thenReturn(1);

        assertTrue(pictureService.deletePicture(1L, admin));

        verify(pictureMapper).deleteById(1L);
        verify(fileManager).deletePicture(existing.getUrl());
    }

    @Test
    @DisplayName("删除：图片不存在时不删数据库、不删 COS")
    void deletePicture_missingPictureDoesNothing() {
        when(pictureMapper.selectById(1L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> pictureService.deletePicture(1L, user(100L, "user")));

        verify(pictureMapper, never()).deleteById(any());
        verifyNoInteractions(fileManager);
    }

    @Test
    @DisplayName("删除：普通用户不能删除他人图片，也不清理 COS")
    void deletePicture_rejectsOtherUser() {
        User other = user(200L, "user");
        when(userService.isAdmin(other)).thenReturn(false);
        when(pictureMapper.selectById(1L)).thenReturn(picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue()));

        assertThrows(BusinessException.class, () -> pictureService.deletePicture(1L, other));

        verify(pictureMapper, never()).deleteById(any());
        verifyNoInteractions(fileManager);
    }

    @Test
    @DisplayName("删除：数据库删除失败时不清理 COS（避免数据库仍有记录但对象已丢失）")
    void deletePicture_dbFailureKeepsCos() {
        User owner = user(100L, "user");
        Picture existing = picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue());
        existing.setUrl("https://example.com/public/100/a.jpg");
        when(pictureMapper.selectById(1L)).thenReturn(existing);
        when(pictureMapper.deleteById(1L)).thenReturn(0);

        assertThrows(BusinessException.class, () -> pictureService.deletePicture(1L, owner));

        verifyNoInteractions(fileManager);
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
    }

    @Test
    @DisplayName("编辑：未登录用户不能编辑图片")
    void editPicture_rejectsAnonymous() {
        assertThrows(BusinessException.class,
                () -> pictureService.editPicture(editRequest("匿名改名"), null));
        verify(pictureMapper, never()).selectById(any());
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
    }

    @Test
    @DisplayName("审核：图片不存在应拒绝")
    void doPictureReview_rejectsMissingPicture() {
        PictureReviewRequest request = new PictureReviewRequest();
        request.setId(1L);
        request.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        when(pictureMapper.selectById(1L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> pictureService.doPictureReview(request, admin()));
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
        when(pictureMapper.updateById(any(Picture.class))).thenReturn(1);

        User admin = admin();
        pictureService.doPictureReview(request, admin);

        ArgumentCaptor<Picture> captor = ArgumentCaptor.forClass(Picture.class);
        verify(pictureMapper).updateById(captor.capture());
        Picture updated = captor.getValue();
        assertEquals(Long.valueOf(1L), updated.getId());
        assertEquals(Integer.valueOf(PictureReviewStatusEnum.REJECT.getValue()), updated.getReviewStatus());
        assertEquals("内容不合规", updated.getReviewMessage());
        assertEquals(admin.getId(), updated.getReviewerId());
        assertNotNull(updated.getReviewTime());
        assertNull(updated.getUrl(), "不应覆盖 url");
        assertNull(updated.getName(), "不应覆盖 name");
        assertNull(updated.getUserId(), "不应覆盖 userId");
        verify(pictureReviewLogService).record(1L, admin.getId(),
                PictureReviewStatusEnum.REJECT.getValue(), "内容不合规");
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
    @DisplayName("下载统计：已通过审核的图片可以自增下载次数")
    void incrementDownloadCount_incrementsApprovedPicture() {
        when(pictureMapper.selectById(1L))
                .thenReturn(picture(1L, 100L, PictureReviewStatusEnum.PASS.getValue()));
        when(pictureMapper.update(org.mockito.ArgumentMatchers.isNull(), any(Wrapper.class))).thenReturn(1);

        assertTrue(pictureService.incrementDownloadCount(1L, new MockHttpServletRequest()));

        verify(pictureMapper).update(org.mockito.ArgumentMatchers.isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("下载统计：图片不存在时不更新")
    void incrementDownloadCount_rejectsMissingPicture() {
        when(pictureMapper.selectById(1L)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> pictureService.incrementDownloadCount(1L, new MockHttpServletRequest()));

        verify(pictureMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("批量审核：单张失败不影响其他图片，并返回失败明细")
    void batchReview_continuesAfterFailure() {
        when(pictureMapper.selectById(1L))
                .thenReturn(picture(1L, 100L, PictureReviewStatusEnum.REVIEWING.getValue()));
        when(pictureMapper.selectById(2L)).thenReturn(null);
        when(pictureMapper.updateById(any(Picture.class))).thenReturn(1);

        PictureReviewBatchRequest request = new PictureReviewBatchRequest();
        request.setIds(List.of(1L, 2L));
        request.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());

        PictureReviewBatchResult result = pictureService.batchReview(request, admin());

        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("图片 2"));
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
    @DisplayName("校验：标签 JSON 总长度超过 512 应拒绝")
    void validPicture_rejectsTooLongTagsJson() {
        Picture picture = validPictureEntity();
        picture.setTags("[\"" + "t".repeat(510) + "\"]");

        BusinessException e = assertThrows(BusinessException.class, () -> pictureService.validPicture(picture));
        assertEquals("标签总长度不能超过 512 个字符", e.getMessage());
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
