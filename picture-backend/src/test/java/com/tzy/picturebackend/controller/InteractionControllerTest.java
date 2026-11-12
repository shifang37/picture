package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.GlobalExceptionHandler;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.InteractionStatusVO;
import com.tzy.picturebackend.service.PictureInteractionService;
import com.tzy.picturebackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InteractionController 接口测试：登录校验、参数校验、分页上限与归属用户
 */
class InteractionControllerTest {

    private MockMvc mockMvc;

    private PictureInteractionService interactionService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        interactionService = mock(PictureInteractionService.class);
        userService = mock(UserService.class);
        InteractionController controller = new InteractionController();
        ReflectionTestUtils.setField(controller, "pictureInteractionService", interactionService);
        ReflectionTestUtils.setField(controller, "userService", userService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("收藏：未登录返回 40100，且不调用互动服务")
    void add_rejectsAnonymous() throws Exception {
        when(userService.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        mockMvc.perform(post("/interaction/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pictureId\":\"1\",\"type\":\"favorite\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN_ERROR.getCode()));

        verifyNoInteractions(interactionService);
    }

    @Test
    @DisplayName("收藏：非法类型在 service 层转成 40000")
    void add_rejectsIllegalType() throws Exception {
        User loginUser = user(100L);
        when(userService.getLoginUser(any())).thenReturn(loginUser);
        when(interactionService.add(any(), eq(loginUser)))
                .thenThrow(new BusinessException(ErrorCode.PARAMS_ERROR, "互动类型不合法"));

        mockMvc.perform(post("/interaction/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pictureId\":\"1\",\"type\":\"collect\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("互动类型不合法"));
    }

    @Test
    @DisplayName("收藏：缺图片 id 时参数校验拦截")
    void add_rejectsMissingPictureId() throws Exception {
        mockMvc.perform(post("/interaction/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"favorite\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()));

        verifyNoInteractions(interactionService);
    }

    @Test
    @DisplayName("我的收藏：使用当前登录用户，pageSize 超过 20 被拒绝")
    void pageMy_capsPageSize() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(user(100L));

        mockMvc.perform(post("/interaction/my/page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"favorite\",\"current\":1,\"pageSize\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()));

        verifyNoInteractions(interactionService);
    }

    @Test
    @DisplayName("状态查询：匿名可访问，直接透传请求上下文")
    void status_allowsAnonymous() throws Exception {
        InteractionStatusVO statusVO = new InteractionStatusVO();
        statusVO.setPictureId(1L);
        statusVO.setFavoriteCount(3L);
        when(interactionService.status(eq(1L), any())).thenReturn(statusVO);

        mockMvc.perform(get("/interaction/status").param("pictureId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.favoriteCount").value(3));

        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        verify(interactionService).status(idCaptor.capture(), any());
        assertEquals(Long.valueOf(1L), idCaptor.getValue());
        verify(userService, never()).getLoginUser(any());
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUserRole("user");
        return user;
    }
}
