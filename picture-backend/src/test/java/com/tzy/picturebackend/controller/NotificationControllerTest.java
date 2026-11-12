package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.manager.RateLimitManager;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.service.NotificationService;
import com.tzy.picturebackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NotificationController 接口测试：登录校验、分页上限、参数校验与公告透传
 */
class NotificationControllerTest {

    private MockMvc mockMvc;

    private NotificationService notificationService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        userService = mock(UserService.class);
        NotificationController controller = new NotificationController();
        ReflectionTestUtils.setField(controller, "notificationService", notificationService);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "rateLimitManager", mock(RateLimitManager.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new com.tzy.picturebackend.exception.GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("未读数：未登录返回 40100")
    void unreadCount_rejectsAnonymous() throws Exception {
        when(userService.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        mockMvc.perform(get("/notification/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN_ERROR.getCode()));

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("通知列表：pageSize 超过上限被拒绝，不查库")
    void page_rejectsOversizedPageSize() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(user(100L));

        mockMvc.perform(get("/notification/page").param("current", "1").param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()));

        verify(notificationService, org.mockito.Mockito.never()).page(any(), eq(false), anyInt(), anyInt());
    }

    @Test
    @DisplayName("标记已读：ids 为空时参数校验拦截")
    void markRead_rejectsEmptyIds() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(user(100L));

        mockMvc.perform(post("/notification/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()));

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("全部已读：返回实际更新条数")
    void markAllRead_returnsUpdatedCount() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(user(100L));
        when(notificationService.markAllRead(100L)).thenReturn(5);

        mockMvc.perform(post("/notification/read/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.updated").value(5));
    }

    @Test
    @DisplayName("公告：标题正文透传给 service，并返回送达人数")
    void broadcast_delegatesToService() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(user(1L));
        when(notificationService.broadcast("维护通知", "今晚 23 点维护")).thenReturn(12);

        mockMvc.perform(post("/notification/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"维护通知\",\"content\":\"今晚 23 点维护\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sent").value(12));

        verify(notificationService).broadcast("维护通知", "今晚 23 点维护");
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUserRole("user");
        return user;
    }
}
