package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.GlobalExceptionHandler;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.UserQuotaVO;
import com.tzy.picturebackend.service.UserQuotaService;
import com.tzy.picturebackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 配额接口测试：登录校验、参数校验与透传（管理员权限由 @AuthCheck 切面在集成层校验）。
 */
class UserQuotaControllerTest {

    private MockMvc mockMvc;

    private UserQuotaService userQuotaService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userQuotaService = mock(UserQuotaService.class);
        userService = mock(UserService.class);
        UserQuotaController controller = new UserQuotaController();
        ReflectionTestUtils.setField(controller, "userQuotaService", userQuotaService);
        ReflectionTestUtils.setField(controller, "userService", userService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("我的配额：未登录返回 40100")
    void getMyQuota_rejectsAnonymous() throws Exception {
        when(userService.getLoginUser(any()))
                .thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        mockMvc.perform(get("/user/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.NOT_LOGIN_ERROR.getCode()));

        verifyNoInteractions(userQuotaService);
    }

    @Test
    @DisplayName("我的配额：返回已用与上限")
    void getMyQuota_returnsQuota() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(user(100L));
        when(userQuotaService.getQuota(100L)).thenReturn(quota());

        mockMvc.perform(get("/user/quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.storageUsed").value("1024"))
                .andExpect(jsonPath("$.data.storageQuota").value("4096"))
                .andExpect(jsonPath("$.data.pictureUsed").value(3))
                .andExpect(jsonPath("$.data.pictureQuota").value(10))
                .andExpect(jsonPath("$.data.exempt").value(false));
    }

    @Test
    @DisplayName("调整配额：缺少用户 id 时参数校验拦截，不调用 service")
    void updateQuota_rejectsMissingUserId() throws Exception {
        mockMvc.perform(post("/user/quota/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storageQuota\":1024}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAMS_ERROR.getCode()));

        verifyNoInteractions(userQuotaService);
    }

    @Test
    @DisplayName("调整配额：透传存储与数量配额，返回最新配额")
    void updateQuota_delegatesToService() throws Exception {
        when(userQuotaService.updateQuota(100L, 2048L, 20)).thenReturn(quota());

        mockMvc.perform(post("/user/quota/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"100\",\"storageQuota\":2048,\"pictureQuota\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.storageQuota").value("4096"));

        verify(userQuotaService).updateQuota(100L, 2048L, 20);
    }

    @Test
    @DisplayName("重算：不传用户时按全量重算")
    void recalc_allUsersWhenNoUserId() throws Exception {
        when(userQuotaService.recalc(isNull())).thenReturn(12);

        mockMvc.perform(post("/user/quota/recalc").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(12));

        verify(userQuotaService).recalc(isNull());
    }

    @Test
    @DisplayName("重算：传用户 id 时只重算该用户")
    void recalc_singleUser() throws Exception {
        when(userQuotaService.recalc(100L)).thenReturn(1);

        mockMvc.perform(post("/user/quota/recalc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"100\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));

        verify(userQuotaService).recalc(100L);
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUserRole("user");
        return user;
    }

    private UserQuotaVO quota() {
        UserQuotaVO quota = new UserQuotaVO();
        quota.setStorageUsed(1024L);
        quota.setStorageQuota(4096L);
        quota.setPictureUsed(3);
        quota.setPictureQuota(10);
        quota.setExempt(false);
        return quota;
    }
}
