package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.exception.GlobalExceptionHandler;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UserController 接口测试：删除结果与参数校验
 */
class UserControllerTest {

    private MockMvc mockMvc;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        UserController userController = new UserController();
        ReflectionTestUtils.setField(userController, "userService", userService);
        mockMvc = MockMvcBuilders.standaloneSetup(userController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("删除用户：成功返回 true")
    void deleteUser_returnsTrueOnSuccess() throws Exception {
        when(userService.getById(1L)).thenReturn(user(1L));
        when(userService.removeById(1L)).thenReturn(true);

        mockMvc.perform(post("/user/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("删除用户：用户不存在返回 40400，前端不再显示删除成功")
    void deleteUser_returnsNotFoundForMissingUser() throws Exception {
        when(userService.getById(1L)).thenReturn(null);

        mockMvc.perform(post("/user/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40400));

        verify(userService, never()).removeById(anyLong());
    }

    @Test
    @DisplayName("删除用户：空 body 返回 40000，不出现空指针")
    void deleteUser_rejectsEmptyBody() throws Exception {
        mockMvc.perform(post("/user/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));

        verify(userService, never()).removeById(anyLong());
    }

    @Test
    @DisplayName("删除用户：数据库删除失败返回 50001")
    void deleteUser_returnsOperationErrorWhenRemoveFails() throws Exception {
        when(userService.getById(1L)).thenReturn(user(1L));
        when(userService.removeById(1L)).thenReturn(false);

        mockMvc.perform(post("/user/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50001));
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUserAccount("test");
        user.setUserRole("user");
        return user;
    }
}
