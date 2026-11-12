package com.tzy.picturebackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.mapper.AlbumMapper;
import com.tzy.picturebackend.mapper.AlbumPictureMapper;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.model.dto.album.AlbumAddRequest;
import com.tzy.picturebackend.model.dto.album.AlbumPageRequest;
import com.tzy.picturebackend.model.dto.album.AlbumPictureRequest;
import com.tzy.picturebackend.model.dto.album.AlbumUpdateRequest;
import com.tzy.picturebackend.model.entity.Album;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.vo.AlbumDetailVO;
import com.tzy.picturebackend.model.vo.AlbumPictureCountVO;
import com.tzy.picturebackend.model.vo.AlbumVO;
import com.tzy.picturebackend.model.vo.PictureVO;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AlbumServiceImpl 单元测试：可见性、权限、计数与封面回退
 */
class AlbumServiceImplTest {

    private AlbumServiceImpl albumService;

    private AlbumMapper albumMapper;

    private AlbumPictureMapper albumPictureMapper;

    private PictureMapper pictureMapper;

    private PictureService pictureService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        albumMapper = mock(AlbumMapper.class);
        albumPictureMapper = mock(AlbumPictureMapper.class);
        pictureMapper = mock(PictureMapper.class);
        pictureService = mock(PictureService.class);
        userService = mock(UserService.class);
        albumService = new AlbumServiceImpl(albumPictureMapper, pictureMapper, pictureService, userService);
        ReflectionTestUtils.setField(albumService, "baseMapper", albumMapper);
    }

    // ==================== 创建 ====================

    @Test
    @DisplayName("创建专辑：默认私密，作者为当前登录用户")
    void add_createsPrivateAlbumForLoginUser() {
        when(albumMapper.insert(any(Album.class))).thenAnswer(invocation -> {
            Album album = invocation.getArgument(0);
            album.setId(100L);
            return 1;
        });

        AlbumAddRequest request = new AlbumAddRequest();
        request.setName("  我的风景  ");
        request.setDescription("  随手拍  ");
        AlbumVO albumVO = albumService.add(request, user(1L, "user"));

        assertEquals(100L, albumVO.getId());
        assertEquals(1L, albumVO.getUserId());
        assertEquals("我的风景", albumVO.getName());
        assertEquals("随手拍", albumVO.getDescription());
        assertEquals(0, albumVO.getIsPublic());
        assertEquals(0, albumVO.getPictureCount());

        ArgumentCaptor<Album> captor = ArgumentCaptor.forClass(Album.class);
        verify(albumMapper).insert(captor.capture());
        assertEquals(1L, captor.getValue().getUserId());
        assertEquals(0, captor.getValue().getPictureCount());
        assertEquals(0, captor.getValue().getIsPublic());
    }

    @Test
    @DisplayName("创建专辑：名称为空拒绝，未登录拒绝")
    void add_rejectsBlankNameAndAnonymous() {
        AlbumAddRequest request = new AlbumAddRequest();
        request.setName("   ");

        BusinessException blank = assertThrows(BusinessException.class,
                () -> albumService.add(request, user(1L, "user")));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), blank.getCode());

        request.setName("风景");
        BusinessException anonymous = assertThrows(BusinessException.class,
                () -> albumService.add(request, null));
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), anonymous.getCode());
    }

    // ==================== 详情可见性 ====================

    @Test
    @DisplayName("详情：私密专辑对他人返回 40400，作者可见且 canManage=true")
    void get_hidesPrivateAlbumFromOthers() {
        Album album = album(10L, 1L, 0, 3);
        when(albumMapper.selectById(10L)).thenReturn(album);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userService.getLoginUserOrNull(request)).thenReturn(user(2L, "user"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> albumService.get(10L, request));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), e.getCode());

        when(userService.getLoginUserOrNull(request)).thenReturn(user(1L, "user"));
        AlbumDetailVO detail = albumService.get(10L, request);
        assertTrue(detail.getCanManage());
        assertEquals(10L, detail.getId());
    }

    @Test
    @DisplayName("详情：公开专辑对匿名可访问，canManage=false 且统计公开数量")
    void get_allowsPublicAlbumForAnonymous() {
        Album album = album(10L, 1L, 1, 5);
        when(albumMapper.selectById(10L)).thenReturn(album);
        AlbumPictureCountVO count = new AlbumPictureCountVO();
        count.setAlbumId(10L);
        count.setPublicCount(2L);
        when(albumPictureMapper.selectPublicCounts(anyCollection())).thenReturn(List.of(count));
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userService.getLoginUserOrNull(request)).thenReturn(null);

        AlbumDetailVO detail = albumService.get(10L, request);

        assertFalse(detail.getCanManage());
        assertEquals(5, detail.getPictureCount());
        assertEquals(2L, detail.getPublicCount());
    }

    @Test
    @DisplayName("详情：专辑不存在返回 40400")
    void get_throwsNotFoundForMissingAlbum() {
        when(albumMapper.selectById(99L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> albumService.get(99L, new MockHttpServletRequest()));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), e.getCode());
    }

    // ==================== 更新 / 删除权限 ====================

    @Test
    @DisplayName("更新：非作者非管理员返回 40101")
    void update_rejectsOtherUser() {
        when(albumMapper.selectById(10L)).thenReturn(album(10L, 1L, 0, 0));
        AlbumUpdateRequest request = new AlbumUpdateRequest();
        request.setId(10L);
        request.setName("新名字");

        BusinessException e = assertThrows(BusinessException.class,
                () -> albumService.update(request, user(2L, "user")));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), e.getCode());
        verify(albumMapper, never()).updateById(any(Album.class));
    }

    @Test
    @DisplayName("更新：设置封面时校验图片已在专辑内")
    void update_rejectsCoverOutsideAlbum() {
        when(albumMapper.selectById(10L)).thenReturn(album(10L, 1L, 0, 0));
        when(albumPictureMapper.countByAlbumAndPictureId(10L, 5L)).thenReturn(0);
        AlbumUpdateRequest request = new AlbumUpdateRequest();
        request.setId(10L);
        request.setCoverPictureId(5L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> albumService.update(request, user(1L, "user")));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), e.getCode());
    }

    @Test
    @DisplayName("删除：作者删除后专辑逻辑删除、关系物理删除，图片不受影响")
    void delete_removesAlbumAndRelations() {
        when(albumMapper.selectById(10L)).thenReturn(album(10L, 1L, 0, 2));
        when(albumMapper.deleteById(10L)).thenReturn(1);

        assertTrue(albumService.delete(10L, user(1L, "user")));

        verify(albumPictureMapper).deleteByAlbumId(10L);
        verify(pictureService, never()).deletePicture(any(), any());
    }

    // ==================== 分页 ====================

    @Test
    @DisplayName("我的专辑：强制按当前用户过滤，分页上限 20")
    void pageMy_filtersByLoginUserAndCapsPageSize() {
        when(albumMapper.selectPage(any(), any(Wrapper.class))).thenReturn(new Page<>(1, 20));
        AlbumPageRequest request = new AlbumPageRequest();
        request.setCurrent(1);
        request.setPageSize(100);

        albumService.pageMy(request, user(1L, "user"));

        ArgumentCaptor<Page<Album>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<Wrapper<Album>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(albumMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertEquals(20, pageCaptor.getValue().getSize());
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("userId"));
    }

    @Test
    @DisplayName("公开专辑：只查 isPublic=1，可按作者过滤并填充 publicCount")
    void pagePublic_returnsOnlyPublicAlbums() {
        Page<Album> albumPage = new Page<>(1, 10, 1);
        albumPage.setRecords(List.of(album(10L, 1L, 1, 3)));
        when(albumMapper.selectPage(any(), any(Wrapper.class))).thenReturn(albumPage);
        AlbumPictureCountVO count = new AlbumPictureCountVO();
        count.setAlbumId(10L);
        count.setPublicCount(2L);
        when(albumPictureMapper.selectPublicCounts(anyCollection())).thenReturn(List.of(count));

        Page<AlbumVO> result = albumService.pagePublic(1L, 1, 10);

        ArgumentCaptor<Wrapper<Album>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(albumMapper).selectPage(any(), wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getSqlSegment();
        assertTrue(sql.contains("isPublic"), "实际 SQL: " + sql);
        assertTrue(sql.contains("userId"), "实际 SQL: " + sql);
        assertEquals(1, result.getRecords().size());
        assertEquals(2L, result.getRecords().get(0).getPublicCount());
        assertEquals(3, result.getRecords().get(0).getPictureCount());
    }

    // ==================== 加图 / 移图 ====================

    @Test
    @DisplayName("加图：幂等插入、计数累加，封面为空时自动取第一张过审图片")
    void addPictures_incrementsCountAndSetsCover() {
        Album album = album(10L, 1L, 0, 0);
        when(albumMapper.selectById(10L)).thenReturn(album);
        Picture passed = picture(5L, 1L, PictureReviewStatusEnum.PASS.getValue());
        Picture reviewing = picture(6L, 1L, PictureReviewStatusEnum.REVIEWING.getValue());
        when(pictureService.listByIds(anyCollection())).thenReturn(List.of(reviewing, passed));
        when(albumPictureMapper.insertIgnore(eq(10L), anyCollection(), any())).thenReturn(2);
        when(albumMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        AlbumVO albumVO = albumService.addPictures(pictureRequest(10L, 6L, 5L), user(1L, "user"));

        assertEquals(10L, albumVO.getId());
        verify(albumPictureMapper).insertIgnore(eq(10L), anyCollection(), any());
        verify(albumMapper).incrementPictureCount(10L, 2);
        // 封面只在为空时自动设置，且必须取已过审图片
        ArgumentCaptor<Wrapper<Album>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(albumMapper).update(isNull(), wrapperCaptor.capture());
        UpdateWrapper<Album> wrapper = (UpdateWrapper<Album>) wrapperCaptor.getValue();
        assertTrue(wrapper.getParamNameValuePairs().containsValue(5L),
                "封面应取第一张已过审图片，实际参数: " + wrapper.getParamNameValuePairs());
    }

    @Test
    @DisplayName("加图：把他人图片加入专辑返回 40101，整批不写入")
    void addPictures_rejectsOthersPictures() {
        when(albumMapper.selectById(10L)).thenReturn(album(10L, 1L, 0, 0));
        when(pictureService.listByIds(anyCollection())).thenReturn(List.of(
                picture(5L, 1L, PictureReviewStatusEnum.PASS.getValue()),
                picture(6L, 2L, PictureReviewStatusEnum.PASS.getValue())));

        BusinessException e = assertThrows(BusinessException.class,
                () -> albumService.addPictures(pictureRequest(10L, 5L, 6L), user(1L, "user")));

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), e.getCode());
        verify(albumPictureMapper, never()).insertIgnore(anyLong(), anyCollection(), any());
        verify(albumMapper, never()).incrementPictureCount(anyLong(), anyInt());
    }

    @Test
    @DisplayName("加图：管理员可以把任意图片加入他人专辑")
    void addPictures_allowsAdminToAddOthersPictures() {
        when(albumMapper.selectById(10L)).thenReturn(album(10L, 1L, 0, 0));
        when(userService.isAdmin(any(User.class))).thenReturn(true);
        when(pictureService.listByIds(anyCollection())).thenReturn(List.of(
                picture(6L, 2L, PictureReviewStatusEnum.PASS.getValue())));
        when(albumPictureMapper.insertIgnore(eq(10L), anyCollection(), any())).thenReturn(1);

        albumService.addPictures(pictureRequest(10L, 6L), user(99L, "admin"));

        verify(albumPictureMapper).insertIgnore(eq(10L), anyCollection(), any());
    }

    @Test
    @DisplayName("移图：封面被移除时回退到最新一张，计数回减")
    void removePictures_recomputesCoverAndCount() {
        Album album = album(10L, 1L, 0, 3);
        album.setCoverPictureId(5L);
        when(albumMapper.selectById(10L)).thenReturn(album);
        when(albumPictureMapper.deleteByAlbumAndPictureIds(eq(10L), anyCollection())).thenReturn(1);
        when(albumPictureMapper.selectLatestPictureId(10L)).thenReturn(9L);
        when(pictureService.listByIds(anyCollection())).thenReturn(List.of(
                picture(5L, 1L, PictureReviewStatusEnum.PASS.getValue())));
        when(albumMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        albumService.removePictures(pictureRequest(10L, 5L), user(1L, "user"));

        verify(albumMapper).incrementPictureCount(10L, -1);
        verify(albumPictureMapper).selectLatestPictureId(10L);
        ArgumentCaptor<Wrapper<Album>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(albumMapper).update(isNull(), wrapperCaptor.capture());
        assertTrue(((UpdateWrapper<Album>) wrapperCaptor.getValue()).getSqlSet()
                .contains("coverPictureId"));
    }

    // ==================== 专辑图片分页 ====================

    @Test
    @DisplayName("专辑图片：匿名访问公开专辑只查已过审图片")
    void pagePictures_publicVisitorOnlySeesPassedPictures() {
        when(albumMapper.selectById(10L)).thenReturn(album(10L, 1L, 1, 3));
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userService.getLoginUserOrNull(request)).thenReturn(null);
        when(pictureService.getPictureVOPage(any(), eq(request))).thenReturn(new Page<>(1, 10));

        albumService.pagePictures(10L, 1, 10, request);

        verify(pictureMapper).selectPageByAlbum(any(), eq(10L), eq(true));
    }

    @Test
    @DisplayName("专辑图片：私密专辑对他人返回 40400")
    void pagePictures_hidesPrivateAlbum() {
        when(albumMapper.selectById(10L)).thenReturn(album(10L, 1L, 0, 3));
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userService.getLoginUserOrNull(request)).thenReturn(user(2L, "user"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> albumService.pagePictures(10L, 1, 10, request));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), e.getCode());
        verify(pictureMapper, never()).selectPageByAlbum(any(), anyLong(), anyBoolean());
    }

    // ==================== 计数重算 / 用户删除 ====================

    @Test
    @DisplayName("重算：指定专辑时只重算该专辑")
    void recount_singleAlbum() {
        when(albumMapper.recountPictureCounts(List.of(10L))).thenReturn(1);

        assertEquals(1, albumService.recount(10L));

        verify(albumMapper).recountPictureCounts(List.of(10L));
        verify(albumMapper, never()).selectIdsForRecount(anyLong(), anyInt());
    }

    @Test
    @DisplayName("删除用户：批量清理该用户的专辑与关系")
    void deleteByUserId_cleansAlbumsAndRelations() {
        when(albumMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                album(10L, 1L, 0, 1), album(11L, 1L, 1, 0)));

        albumService.deleteByUserId(1L);

        verify(albumPictureMapper).deleteByAlbumIds(List.of(10L, 11L));
        verify(albumMapper).delete(any(Wrapper.class));
    }

    @Test
    @DisplayName("专辑图片分页：作者本人可以看未过审图片")
    void pagePictures_ownerSeesAllPictures() {
        when(albumMapper.selectById(10L)).thenReturn(album(10L, 1L, 0, 3));
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userService.getLoginUserOrNull(request)).thenReturn(user(1L, "user"));
        when(pictureService.getPictureVOPage(any(), eq(request))).thenReturn(new Page<>(1, 10));

        albumService.pagePictures(10L, 1, 10, request);

        verify(pictureMapper).selectPageByAlbum(any(), eq(10L), eq(false));
    }

    @Test
    @DisplayName("详情：封面配置失效时公开视角回退到最新公开图片")
    void get_fallsBackToPublicCover() {
        Album album = album(10L, 1L, 1, 3);
        album.setCoverPictureId(5L);
        when(albumMapper.selectById(10L)).thenReturn(album);
        // 5 号图片是待审核状态，公开视角不能使用
        when(pictureService.listByIds(anyCollection())).thenReturn(List.of(
                picture(5L, 1L, PictureReviewStatusEnum.REVIEWING.getValue())));
        when(albumPictureMapper.selectFirstPublicPictureId(10L)).thenReturn(9L);
        Picture publicPicture = picture(9L, 1L, PictureReviewStatusEnum.PASS.getValue());
        publicPicture.setUrl("https://example.invalid/9.jpg");
        when(pictureService.getById(9L)).thenReturn(publicPicture);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userService.getLoginUserOrNull(request)).thenReturn(null);

        AlbumDetailVO detail = albumService.get(10L, request);

        assertEquals("https://example.invalid/9.jpg", detail.getCoverUrl());
    }

    @Test
    @DisplayName("详情：封面为空且没有公开图片时不返回封面")
    void get_returnsNullCoverWhenAlbumIsEmpty() {
        when(albumMapper.selectById(10L)).thenReturn(album(10L, 1L, 1, 0));
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(userService.getLoginUserOrNull(request)).thenReturn(null);

        AlbumDetailVO detail = albumService.get(10L, request);

        assertNull(detail.getCoverUrl());
        verify(albumPictureMapper).selectFirstPublicPictureId(10L);
    }

    // ==================== 辅助方法 ====================

    private Album album(Long id, Long userId, int isPublic, int pictureCount) {
        Album album = new Album();
        album.setId(id);
        album.setUserId(userId);
        album.setName("专辑");
        album.setIsPublic(isPublic);
        album.setPictureCount(pictureCount);
        return album;
    }

    private Picture picture(Long id, Long userId, int reviewStatus) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setUserId(userId);
        picture.setReviewStatus(reviewStatus);
        return picture;
    }

    private AlbumPictureRequest pictureRequest(Long albumId, Long... pictureIds) {
        AlbumPictureRequest request = new AlbumPictureRequest();
        request.setAlbumId(albumId);
        request.setPictureIds(List.of(pictureIds));
        return request;
    }

    private User user(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }
}
