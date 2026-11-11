package com.tzy.picturebackend.exception;

import com.tzy.picturebackend.common.BaseResponse;
import com.tzy.picturebackend.controller.PictureController;
import com.tzy.picturebackend.controller.UserController;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理测试：预期内的 4xx 应返回明确的业务码，而不是统一的 50000
 */
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        PictureService pictureService = mock(PictureService.class);
        UserService userService = mock(UserService.class);

        PictureController pictureController = new PictureController();
        ReflectionTestUtils.setField(pictureController, "pictureService", pictureService);
        ReflectionTestUtils.setField(pictureController, "userService", userService);

        UserController userController = new UserController();
        ReflectionTestUtils.setField(userController, "userService", userService);

        mockMvc = MockMvcBuilders.standaloneSetup(pictureController, userController)
                .setControllerAdvice(handler)
                .build();
    }

    // ==================== 通过 MockMvc 覆盖参数绑定异常 ====================

    @Test
    @DisplayName("非法 JSON：返回 40000 与明确提示，不再是系统错误")
    void malformedJson_returnsParamsError() throws Exception {
        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("请求体格式错误"));
    }

    @Test
    @DisplayName("id 类型不匹配：id=abc 返回 40000")
    void typeMismatch_returnsParamsError() throws Exception {
        mockMvc.perform(get("/picture/get/vo").param("id", "abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("参数类型错误：id"));
    }

    @Test
    @DisplayName("缺少必填查询参数：返回 40000，而不是 50000")
    void missingParameter_returnsParamsError() throws Exception {
        mockMvc.perform(get("/picture/get/vo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("删除接口空 body：返回 40000，不出现空指针")
    void deleteWithEmptyBody_returnsParamsError() throws Exception {
        mockMvc.perform(post("/picture/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("编辑接口空 body：返回 40000，不出现空指针")
    void editWithEmptyBody_returnsParamsError() throws Exception {
        mockMvc.perform(post("/picture/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("删除接口 id 不是数字：返回 40000")
    void deleteWithInvalidIdType_returnsParamsError() throws Exception {
        mockMvc.perform(post("/picture/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"abc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("请求体格式错误"));
    }

    // ==================== 直接调用 handler ====================

    @Test
    @DisplayName("未知路径：返回 40400 与「请求地址不存在」")
    void noResourceFound_returnsNotFound() {
        BaseResponse<?> response = handler.noResourceFoundExceptionHandler(
                new NoResourceFoundException(HttpMethod.GET, "/api/not-exist"));

        assertEquals(40400, response.getCode());
        assertEquals("请求地址不存在", response.getMessage());
    }

    @Test
    @DisplayName("请求方法不支持：返回 40500")
    void methodNotSupported_returnsMethodNotAllowed() {
        BaseResponse<?> response = handler.httpRequestMethodNotSupportedExceptionHandler(
                new HttpRequestMethodNotSupportedException("DELETE"));

        assertEquals(40500, response.getCode());
        assertTrue(response.getMessage().contains("DELETE"));
    }

    @Test
    @DisplayName("数据库约束异常：转换为 40000，而不是 50000")
    void dataIntegrityViolation_returnsParamsError() {
        BaseResponse<?> response = handler.dataIntegrityViolationExceptionHandler(
                new DataIntegrityViolationException("Data too long for column 'name'"));

        assertEquals(40000, response.getCode());
        assertEquals("数据不合法或已存在", response.getMessage());
    }

    @Test
    @DisplayName("字段校验异常：取出第一个字段的错误信息")
    void bindException_returnsFirstFieldMessage() {
        BindException exception = new BindException(new User(), "user");
        exception.rejectValue("id", "NotNull", "id 不能为空");

        BaseResponse<?> response = handler.bindExceptionHandler(exception);

        assertEquals(40000, response.getCode());
        assertEquals("id 不能为空", response.getMessage());
    }

    @Test
    @DisplayName("缺少请求参数：消息中带参数名")
    void missingParameterHandler_containsParameterName() {
        BaseResponse<?> response = handler.missingServletRequestParameterExceptionHandler(
                new MissingServletRequestParameterException("id", "Long"));

        assertEquals(40000, response.getCode());
        assertEquals("缺少参数：id", response.getMessage());
    }

    @Test
    @DisplayName("业务异常：保留原始业务码与消息")
    void businessException_keepsCodeAndMessage() {
        BaseResponse<?> response = handler.businessExceptionHandler(
                new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限操作"));

        assertEquals(40101, response.getCode());
        assertEquals("无权限操作", response.getMessage());
    }
}
