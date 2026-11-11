package com.tzy.picturebackend.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.exception.GlobalExceptionHandler;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.picture.PictureUploadRequest;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.PictureVO;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PictureController 接口测试：普通用户上传、multipart 参数绑定、我的图片归属校验
 */
class PictureControllerTest {

    private MockMvc mockMvc;

    private PictureService pictureService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        pictureService = mock(PictureService.class);
        userService = mock(UserService.class);
        PictureController pictureController = new PictureController();
        ReflectionTestUtils.setField(pictureController, "pictureService", pictureService);
        ReflectionTestUtils.setField(pictureController, "userService", userService);
        mockMvc = MockMvcBuilders.standaloneSetup(pictureController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("上传：普通用户可以通过 multipart 上传，id 能正确绑定")
    void uploadPicture_bindsMultipartForNormalUser() throws Exception {
        User normalUser = user(100L, "user");
        when(userService.getLoginUser(any())).thenReturn(normalUser);
        PictureVO pictureVO = new PictureVO();
        pictureVO.setId(123L);
        when(pictureService.uploadPicture(any(), any(), eq(normalUser))).thenReturn(pictureVO);

        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});

        mockMvc.perform(multipart("/picture/upload")
                        .file(file)
                        .param("id", "123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<PictureUploadRequest> requestCaptor =
                ArgumentCaptor.forClass(PictureUploadRequest.class);
        verify(pictureService).uploadPicture(any(), requestCaptor.capture(), eq(normalUser));
        assertEquals(Long.valueOf(123L), requestCaptor.getValue().getId());
    }

    @Test
    @DisplayName("我的图片：强制使用当前登录用户 id，忽略客户端传入的 userId")
    void listMyPictureVOByPage_forcesLoginUserId() throws Exception {
        User normalUser = user(100L, "user");
        when(userService.getLoginUser(any())).thenReturn(normalUser);
        when(pictureService.page(any(Page.class), any())).thenReturn(new Page<>(1, 10));
        when(pictureService.getPictureVOPage(any(), any())).thenReturn(new Page<>(1, 10));

        mockMvc.perform(post("/picture/my/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"999\",\"pageSize\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<PictureQueryRequest> requestCaptor =
                ArgumentCaptor.forClass(PictureQueryRequest.class);
        verify(pictureService).getQueryWrapper(requestCaptor.capture());
        assertEquals(Long.valueOf(100L), requestCaptor.getValue().getUserId());
    }

    @Test
    @DisplayName("上传：超过大小限制返回参数错误，而不是系统错误")
    void uploadPicture_reportsOversizedFileAsParamsError() throws Exception {
        User normalUser = user(100L, "user");
        when(userService.getLoginUser(any())).thenReturn(normalUser);
        when(pictureService.uploadPicture(any(), any(), eq(normalUser)))
                .thenThrow(new MaxUploadSizeExceededException(2 * 1024 * 1024L));

        MockMultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});

        mockMvc.perform(multipart("/picture/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("文件大小不能超过 2M"));
    }

    private User user(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }
}
