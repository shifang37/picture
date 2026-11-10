package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.model.dto.picture.PictureReviewRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PictureServiceImpl 单元测试：审核可见性与审核动作，不依赖数据库
 */
class PictureServiceImplTest {

    private PictureServiceImpl pictureService;

    private UserService userService;

    private PictureMapper pictureMapper;

    @BeforeEach
    void setUp() {
        pictureService = new PictureServiceImpl();
        userService = mock(UserService.class);
        pictureMapper = mock(PictureMapper.class);
        ReflectionTestUtils.setField(pictureService, "userService", userService);
        ReflectionTestUtils.setField(pictureService, "baseMapper", pictureMapper);
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
    }

    // ==================== 辅助方法 ====================

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
