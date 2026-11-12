package com.tzy.picturebackend.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.annotation.AuthCheck;
import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.GlobalExceptionHandler;
import com.tzy.picturebackend.model.dto.album.AlbumPageRequest;
import com.tzy.picturebackend.model.dto.album.AlbumPictureRequest;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.AlbumDetailVO;
import com.tzy.picturebackend.model.vo.AlbumVO;
import com.tzy.picturebackend.model.vo.PictureVO;
import com.tzy.picturebackend.service.AlbumService;
import com.tzy.picturebackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AlbumController 接口测试：登录、参数校验与权限声明
 */
class AlbumControllerTest {

    private MockMvc mockMvc;

    private AlbumService albumService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        albumService = mock(AlbumService.class);
        userService = mock(UserService.class);
        AlbumController albumController = new AlbumController();
        ReflectionTestUtils.setField(albumController, "albumService", albumService);
        ReflectionTestUtils.setField(albumController, "userService", userService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(albumController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("创建专辑：登录用户创建成功并返回专辑")
    void addAlbum_returnsCreatedAlbum() throws Exception {
        User user = user(1L, "user");
        when(userService.getLoginUser(any())).thenReturn(user);
        AlbumVO albumVO = new AlbumVO();
        albumVO.setId(10L);
        albumVO.setName("风景");
        when(albumService.add(any(), eq(user))).thenReturn(albumVO);

        mockMvc.perform(post("/album/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"风景\",\"description\":\"随手拍\",\"isPublic\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("10"))
                .andExpect(jsonPath("$.data.name").value("风景"));

        verify(albumService).add(any(), eq(user));
    }

    @Test
    @DisplayName("创建专辑：名称为空返回 40000，不调用 service")
    void addAlbum_rejectsBlankName() throws Exception {
        mockMvc.perform(post("/album/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));

        verify(albumService, never()).add(any(), any());
    }

    @Test
    @DisplayName("删除专辑：成功返回 true；id 为空返回 40000")
    void deleteAlbum_validatesId() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(user(1L, "user"));
        when(albumService.delete(eq(10L), any(User.class))).thenReturn(true);

        mockMvc.perform(post("/album/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(post("/album/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("专辑详情：不存在时向调用方透传 40400")
    void getAlbum_propagatesNotFound() throws Exception {
        when(albumService.get(eq(99L), any())).thenThrow(
                new BusinessException(ErrorCode.NOT_FOUND_ERROR, "专辑不存在"));

        mockMvc.perform(get("/album/get").param("id", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value("专辑不存在"));
    }

    @Test
    @DisplayName("专辑详情：返回 canManage 与封面")
    void getAlbum_returnsDetail() throws Exception {
        AlbumDetailVO detail = new AlbumDetailVO();
        detail.setId(10L);
        detail.setName("风景");
        detail.setCoverUrl("https://example.invalid/1.jpg");
        detail.setCanManage(false);
        detail.setPublicCount(2L);
        when(albumService.get(eq(10L), any())).thenReturn(detail);

        mockMvc.perform(get("/album/get").param("id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.canManage").value(false))
                .andExpect(jsonPath("$.data.coverUrl").value("https://example.invalid/1.jpg"))
                .andExpect(jsonPath("$.data.publicCount").value(2));
    }

    @Test
    @DisplayName("公开专辑分页：透传 userId / 分页参数")
    void listPublicAlbumByPage_passesParams() throws Exception {
        Page<AlbumVO> page = new Page<>(2, 5, 1);
        page.setRecords(List.of(new AlbumVO()));
        when(albumService.pagePublic(1L, 2, 5)).thenReturn(page);

        mockMvc.perform(get("/album/public/page")
                        .param("userId", "1")
                        .param("current", "2")
                        .param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));

        verify(albumService).pagePublic(1L, 2, 5);
    }

    @Test
    @DisplayName("我的专辑：未登录返回 40100")
    void listMyAlbumByPage_requiresLogin() throws Exception {
        when(userService.getLoginUser(any()))
                .thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        mockMvc.perform(post("/album/my/page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":1,\"pageSize\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100));

        verify(albumService, never()).pageMy(any(), any());
    }

    @Test
    @DisplayName("我的专辑：分页参数原样传给 service")
    void listMyAlbumByPage_delegates() throws Exception {
        User user = user(1L, "user");
        when(userService.getLoginUser(any())).thenReturn(user);
        when(albumService.pageMy(any(AlbumPageRequest.class), eq(user)))
                .thenReturn(new Page<>(1, 10));

        mockMvc.perform(post("/album/my/page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":2,\"pageSize\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<AlbumPageRequest> captor = ArgumentCaptor.forClass(AlbumPageRequest.class);
        verify(albumService).pageMy(captor.capture(), eq(user));
        assertEquals(2, captor.getValue().getCurrent());
        assertEquals(5, captor.getValue().getPageSize());
    }

    @Test
    @DisplayName("专辑加图：空图片列表返回 40000，不调用 service")
    void addAlbumPictures_rejectsEmptyIds() throws Exception {
        mockMvc.perform(post("/album/picture/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"albumId\":\"10\",\"pictureIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));

        verify(albumService, never()).addPictures(any(), any());
    }

    @Test
    @DisplayName("专辑加图：成功返回更新后的专辑")
    void addAlbumPictures_returnsUpdatedAlbum() throws Exception {
        User user = user(1L, "user");
        when(userService.getLoginUser(any())).thenReturn(user);
        AlbumVO albumVO = new AlbumVO();
        albumVO.setId(10L);
        albumVO.setPictureCount(3);
        when(albumService.addPictures(any(AlbumPictureRequest.class), eq(user))).thenReturn(albumVO);

        mockMvc.perform(post("/album/picture/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"albumId\":\"10\",\"pictureIds\":[\"1\",\"2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.pictureCount").value(3));

        ArgumentCaptor<AlbumPictureRequest> captor = ArgumentCaptor.forClass(AlbumPictureRequest.class);
        verify(albumService).addPictures(captor.capture(), eq(user));
        assertEquals(List.of(1L, 2L), captor.getValue().getPictureIds());
    }

    @Test
    @DisplayName("专辑图片分页：返回图片分页")
    void listAlbumPictureByPage_returnsPage() throws Exception {
        Page<PictureVO> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(new PictureVO()));
        when(albumService.pagePictures(eq(10L), eq(1L), eq(10L), any())).thenReturn(page);

        mockMvc.perform(get("/album/picture/page").param("albumId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("计数重算：接口必须声明管理员权限")
    void recountAlbum_requiresAdminRole() throws Exception {
        AuthCheck authCheck = AlbumController.class
                .getMethod("recountAlbum", Long.class)
                .getAnnotation(AuthCheck.class);

        assertNotNull(authCheck, "重算接口必须使用 @AuthCheck 限制管理员");
        assertEquals(UserConstant.ADMIN_ROLE, authCheck.mustRole());
    }

    @Test
    @DisplayName("专辑删除：未登录返回 40100")
    void deleteAlbum_requiresLogin() throws Exception {
        when(userService.getLoginUser(any()))
                .thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        mockMvc.perform(post("/album/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100));

        verify(albumService, never()).delete(anyLong(), any());
    }

    private User user(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }
}
