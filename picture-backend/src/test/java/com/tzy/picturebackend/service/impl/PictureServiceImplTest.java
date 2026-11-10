package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

    // ==================== 辅助方法 ====================

    private PictureQueryRequest queryRequest(String sortField, String sortOrder) {
        PictureQueryRequest request = new PictureQueryRequest();
        request.setSortField(sortField);
        request.setSortOrder(sortOrder);
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
