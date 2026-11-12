package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.annotation.AuthCheck;
import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.event.NotificationEventPublisher;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.GlobalExceptionHandler;
import com.tzy.picturebackend.manager.CaptchaManager;
import com.tzy.picturebackend.manager.RegistrationGuard;
import com.tzy.picturebackend.manager.SessionInvalidationManager;
import com.tzy.picturebackend.model.dto.user.UserResetPasswordRequest;
import com.tzy.picturebackend.model.dto.notification.NotificationMessage;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.NotificationTypeEnum;
import com.tzy.picturebackend.model.vo.CaptchaVO;
import com.tzy.picturebackend.model.vo.PublicUserProfileVO;
import com.tzy.picturebackend.model.vo.PublicUserVO;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.AlbumService;
import com.tzy.picturebackend.service.NotificationService;
import com.tzy.picturebackend.service.UserService;
import com.tzy.picturebackend.support.InMemoryRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UserController 接口测试：用户管理、个人资料、修改密码与删除保护
 */
class UserControllerTest {

    private MockMvc mockMvc;

    private UserService userService;

    private PictureService pictureService;

    private RegistrationGuard registrationGuard;

    private CaptchaManager captchaManager;

    private NotificationService notificationService;

    private NotificationEventPublisher notificationEventPublisher;

    private AlbumService albumService;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        pictureService = mock(PictureService.class);
        registrationGuard = mock(RegistrationGuard.class);
        captchaManager = mock(CaptchaManager.class);
        notificationService = mock(NotificationService.class);
        notificationEventPublisher = mock(NotificationEventPublisher.class);
        albumService = mock(AlbumService.class);
        UserController userController = new UserController();
        ReflectionTestUtils.setField(userController, "userService", userService);
        ReflectionTestUtils.setField(userController, "pictureService", pictureService);
        ReflectionTestUtils.setField(userController, "registrationGuard", registrationGuard);
        ReflectionTestUtils.setField(userController, "captchaManager", captchaManager);
        ReflectionTestUtils.setField(userController, "notificationService", notificationService);
        ReflectionTestUtils.setField(userController, "notificationEventPublisher", notificationEventPublisher);
        ReflectionTestUtils.setField(userController, "albumService", albumService);
        ReflectionTestUtils.setField(userController, "sessionInvalidationManager",
                new SessionInvalidationManager(InMemoryRedis.createTemplate(new ConcurrentHashMap<>())));

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(userController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    // ==================== 删除用户 ====================

    @Test
    @DisplayName("删除用户：成功返回 true")
    void deleteUser_returnsTrueOnSuccess() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(user(99L, "admin"));
        when(userService.getById(1L)).thenReturn(user(1L, "user"));
        when(pictureService.count(any())).thenReturn(0L);
        when(userService.removeById(1L)).thenReturn(true);

        mockMvc.perform(post("/user/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("删除用户：用户不存在返回 40400")
    void deleteUser_returnsNotFoundForMissingUser() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(user(99L, "admin"));
        when(userService.getById(1L)).thenReturn(null);

        mockMvc.perform(post("/user/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40400));

        verify(userService, never()).removeById(anyLong());
    }

    @Test
    @DisplayName("删除用户：不能删除自己")
    void deleteUser_rejectsSelf() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(user(1L, "admin"));

        mockMvc.perform(post("/user/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("不能删除自己"));

        verify(userService, never()).removeById(anyLong());
    }

    @Test
    @DisplayName("删除用户：用户还有图片时拒绝删除")
    void deleteUser_rejectsUserWithPictures() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(user(99L, "admin"));
        when(userService.getById(1L)).thenReturn(user(1L, "user"));
        when(pictureService.count(any())).thenReturn(2L);

        mockMvc.perform(post("/user/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("该用户还有图片，请先处理该用户的图片"));

        verify(userService, never()).removeById(anyLong());
    }

    @Test
    @DisplayName("删除用户：空 body 返回 40000")
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
        when(userService.getLoginUser(any())).thenReturn(user(99L, "admin"));
        when(userService.getById(1L)).thenReturn(user(1L, "user"));
        when(pictureService.count(any())).thenReturn(0L);
        when(userService.removeById(1L)).thenReturn(false);

        mockMvc.perform(post("/user/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50001));
    }

    // ==================== 新增用户 ====================

    @Test
    @DisplayName("新增用户：返回初始密码，密码加密存储并要求首次登录改密")
    void addUser_returnsNewUserIdAndInitialPassword() throws Exception {
        when(userService.count(any())).thenReturn(0L);
        when(userService.getEncryptPassword(anyString())).thenReturn("encrypted");
        when(userService.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(9L);
            return true;
        });

        mockMvc.perform(post("/user/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"newuser\",\"userName\":\"新用户\",\"userRole\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(9))
                .andExpect(jsonPath("$.data.initialPassword").isString());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("admin", saved.getUserRole());
        assertEquals("encrypted", saved.getUserPassword());
        assertEquals(Boolean.TRUE, saved.getPasswordChangeRequired());
    }

    // ==================== 注册 ====================

    @Test
    @DisplayName("注册：验证码与账号限流校验通过后调用 service")
    void register_success() throws Exception {
        when(userService.userRegister("newuser", "Abcdef12", "Abcdef12")).thenReturn(5L);

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"newuser\",\"userPassword\":\"Abcdef12\","
                                + "\"checkPassword\":\"Abcdef12\",\"captchaId\":\"captcha-id\","
                                + "\"captchaCode\":\"a1b2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(5));

        verify(registrationGuard).check("newuser", "captcha-id", "a1b2");
    }

    @Test
    @DisplayName("注册：验证码错误返回 40000，不调用 service")
    void register_rejectsInvalidCaptcha() throws Exception {
        doThrow(new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误或已过期"))
                .when(registrationGuard).check(anyString(), anyString(), anyString());

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"newuser\",\"userPassword\":\"Abcdef12\","
                                + "\"checkPassword\":\"Abcdef12\",\"captchaId\":\"captcha-id\","
                                + "\"captchaCode\":\"bad\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("验证码错误或已过期"));

        verify(userService, never()).userRegister(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("注册：账号维度超过限流返回 42900，不调用 service")
    void register_rejectsRateLimitedAccount() throws Exception {
        doThrow(new BusinessException(ErrorCode.TOO_MANY_REQUESTS))
                .when(registrationGuard).check(anyString(), anyString(), anyString());

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"newuser\",\"userPassword\":\"Abcdef12\","
                                + "\"checkPassword\":\"Abcdef12\",\"captchaId\":\"captcha-id\","
                                + "\"captchaCode\":\"a1b2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(42900));

        verify(userService, never()).userRegister(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("注册：缺少验证码返回 40000，不进入限流与 service")
    void register_rejectsMissingCaptcha() throws Exception {
        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"newuser\",\"userPassword\":\"Abcdef12\","
                                + "\"checkPassword\":\"Abcdef12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("验证码不能为空"));

        verify(registrationGuard, never()).check(anyString(), anyString(), anyString());
        verify(userService, never()).userRegister(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("验证码接口：返回 captchaId 与 data URL 图片")
    void captcha_returnsImageAndId() throws Exception {
        CaptchaVO captchaVO = new CaptchaVO();
        captchaVO.setCaptchaId("id1");
        captchaVO.setImage("data:image/png;base64,abc");
        captchaVO.setExpiresInSeconds(300);
        when(captchaManager.generate()).thenReturn(captchaVO);

        mockMvc.perform(get("/user/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.captchaId").value("id1"))
                .andExpect(jsonPath("$.data.image").value("data:image/png;base64,abc"));
    }

    // ==================== 编辑用户 ====================

    @Test
    @DisplayName("编辑用户：只更新允许字段，不能改账号和密码")
    void updateUser_updatesAllowedFieldsOnly() throws Exception {
        when(userService.getById(1L)).thenReturn(user(1L, "user"));
        when(userService.updateById(any(User.class))).thenReturn(true);

        mockMvc.perform(post("/user/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\",\"userName\":\"新昵称\",\"userAvatar\":\"http://a.png\","
                                + "\"userProfile\":\"简介\",\"userRole\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).updateById(captor.capture());
        User updated = captor.getValue();
        assertEquals("新昵称", updated.getUserName());
        assertEquals("admin", updated.getUserRole());
        assertNull(updated.getUserAccount(), "不允许通过该接口改账号");
        assertNull(updated.getUserPassword(), "不允许通过该接口改密码");
        // 角色发生变化时提醒本人，避免被提权/降权而无人知晓
        ArgumentCaptor<NotificationMessage> messageCaptor =
                ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notificationEventPublisher).publish(messageCaptor.capture());
        assertEquals(NotificationTypeEnum.ROLE_CHANGED, messageCaptor.getValue().getType());
    }

    @Test
    @DisplayName("编辑用户：目标不存在返回 40400")
    void updateUser_returnsNotFoundForMissingUser() throws Exception {
        when(userService.getById(1L)).thenReturn(null);

        mockMvc.perform(post("/user/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\",\"userName\":\"新昵称\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    @DisplayName("编辑用户：非法角色返回 40000")
    void updateUser_rejectsIllegalRole() throws Exception {
        when(userService.getById(1L)).thenReturn(user(1L, "user"));

        mockMvc.perform(post("/user/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\",\"userRole\":\"superadmin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("编辑用户：不能把最后一个管理员降级")
    void updateUser_preventsDemotingLastAdmin() throws Exception {
        when(userService.getById(1L)).thenReturn(user(1L, "admin"));
        when(userService.count(any())).thenReturn(1L);

        mockMvc.perform(post("/user/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\",\"userRole\":\"user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("系统至少需要保留一个管理员"));

        verify(userService, never()).updateById(any(User.class));
    }

    // ==================== 个人资料 ====================

    @Test
    @DisplayName("个人资料：只能修改当前登录用户的字段")
    void updateProfile_updatesOnlyLoginUser() throws Exception {
        User loginUser = user(100L, "user");
        when(userService.getLoginUser(any())).thenReturn(loginUser);
        when(userService.updateProfile(any(User.class), any())).thenReturn(true);

        mockMvc.perform(post("/user/update/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"我的昵称\",\"userAvatar\":\"http://a.png\","
                                + "\"userProfile\":\"我的简介\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userService).updateProfile(userCaptor.capture(), any());
        assertEquals(Long.valueOf(100L), userCaptor.getValue().getId());
    }

    @Test
    @DisplayName("个人资料：未登录返回 40100")
    void updateProfile_requiresLogin() throws Exception {
        when(userService.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        mockMvc.perform(post("/user/update/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"我的昵称\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100));
    }

    // ==================== 修改密码 ====================

    @Test
    @DisplayName("修改密码：成功返回 true，并刷新当前会话版本、解除强制改密")
    void updatePassword_success() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(user(100L, "user"));
        when(userService.updatePassword(any(User.class), any(), any())).thenReturn(true);

        var mvcResult = mockMvc.perform(post("/user/update/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"123456\",\"newPassword\":\"Abcdef12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        assertNotNull(mvcResult.getRequest().getSession(false)
                .getAttribute(UserConstant.USER_LOGIN_VERSION));
        assertEquals(Boolean.FALSE, mvcResult.getRequest().getSession(false)
                .getAttribute(UserConstant.USER_PASSWORD_CHANGE_REQUIRED));
    }

    @Test
    @DisplayName("修改密码：原密码错误返回 40000 与明确提示")
    void updatePassword_rejectsWrongOldPassword() throws Exception {
        when(userService.getLoginUser(any())).thenReturn(user(100L, "user"));
        when(userService.updatePassword(any(User.class), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.PARAMS_ERROR, "原密码错误"));

        mockMvc.perform(post("/user/update/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"wrong-old\",\"newPassword\":\"Abcdef12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("原密码错误"));
    }

    @Test
    @DisplayName("修改密码：新密码少于 8 位返回 40000")
    void updatePassword_rejectsTooShortNewPassword() throws Exception {
        mockMvc.perform(post("/user/update/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"123456\",\"newPassword\":\"123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("密码长度不能少于 8 位"));
    }

    @Test
    @DisplayName("修改密码：新密码只有字母返回 40000")
    void updatePassword_rejectsPasswordWithoutDigit() throws Exception {
        mockMvc.perform(post("/user/update/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"123456\",\"newPassword\":\"abcdefgh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("密码必须同时包含字母和数字"));
    }

    // ==================== 管理员重置密码 ====================

    @Test
    @DisplayName("重置密码：管理员重置成功返回 true")
    void resetPassword_success() throws Exception {
        when(userService.resetPassword(1L, "newpass1")).thenReturn(true);

        mockMvc.perform(post("/user/reset/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\",\"newPassword\":\"newpass1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        verify(userService).resetPassword(1L, "newpass1");
        // 安全提醒：重置成功后给目标用户发一条站内通知（不含新密码）
        ArgumentCaptor<NotificationMessage> messageCaptor =
                ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notificationEventPublisher).publish(messageCaptor.capture());
        assertEquals(NotificationTypeEnum.PASSWORD_RESET, messageCaptor.getValue().getType());
        assertEquals(1L, messageCaptor.getValue().getUserId());
    }

    @Test
    @DisplayName("重置密码：用户不存在返回 40400")
    void resetPassword_returnsNotFound() throws Exception {
        when(userService.resetPassword(1L, "newpass1"))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在"));

        mockMvc.perform(post("/user/reset/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\",\"newPassword\":\"newpass1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value("用户不存在"));
    }

    @Test
    @DisplayName("重置密码：新密码少于 6 位返回 40000，不调用 service")
    void resetPassword_rejectsTooShortPassword() throws Exception {
        mockMvc.perform(post("/user/reset/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\",\"newPassword\":\"123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));

        verify(userService, never()).resetPassword(anyLong(), any());
    }

    @Test
    @DisplayName("重置密码：id 为空返回 40000")
    void resetPassword_rejectsMissingId() throws Exception {
        mockMvc.perform(post("/user/reset/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"newpass1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));

        verify(userService, never()).resetPassword(anyLong(), any());
    }

    @Test
    @DisplayName("重置密码：接口必须声明管理员权限")
    void resetPassword_requiresAdminRole() throws Exception {
        AuthCheck authCheck = UserController.class
                .getMethod("resetPassword", UserResetPasswordRequest.class)
                .getAnnotation(AuthCheck.class);

        assertNotNull(authCheck, "重置密码接口必须使用 @AuthCheck 限制管理员");
        assertEquals(UserConstant.ADMIN_ROLE, authCheck.mustRole());
    }

    // ==================== 密码哈希不泄露 ====================

    @Test
    @DisplayName("管理员查询用户：响应中不返回 userPassword 哈希")
    void getUserById_doesNotExposePassword() throws Exception {
        User user = user(1L, "user");
        user.setUserPassword("$2a$10$secret-hash");
        when(userService.getById(1L)).thenReturn(user);

        mockMvc.perform(get("/user/get").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userPassword").doesNotExist());
    }

    @Test
    @DisplayName("公开用户信息：只返回昵称、头像和简介，不泄露账号和角色")
    void getUserVOById_returnsPublicFieldsOnly() throws Exception {
        User user = user(1L, "admin");
        user.setUserName("昵称");
        user.setUserAvatar("http://avatar.png");
        user.setUserProfile("简介");
        PublicUserVO publicUserVO = new PublicUserVO();
        publicUserVO.setId(1L);
        publicUserVO.setUserName("昵称");
        publicUserVO.setUserAvatar("http://avatar.png");
        publicUserVO.setUserProfile("简介");
        when(userService.getById(1L)).thenReturn(user);
        when(userService.getPublicUserVO(user)).thenReturn(publicUserVO);

        mockMvc.perform(get("/user/get/vo").param("id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userName").value("昵称"))
                .andExpect(jsonPath("$.data.userAvatar").value("http://avatar.png"))
                .andExpect(jsonPath("$.data.userProfile").value("简介"))
                .andExpect(jsonPath("$.data.userAccount").doesNotExist())
                .andExpect(jsonPath("$.data.userRole").doesNotExist());

        verify(userService).getPublicUserVO(user);
        verify(userService, never()).getUserVO(any());
    }

    // ==================== 公开作者主页 ====================

    @Test
    @DisplayName("公开作者主页：返回公开资料与统计，不泄露账号和角色")
    void getPublicUserProfile_returnsPublicFieldsAndStats() throws Exception {
        PublicUserProfileVO profile = new PublicUserProfileVO();
        PublicUserVO publicUser = new PublicUserVO();
        publicUser.setId(9L);
        publicUser.setUserName("作者");
        publicUser.setUserAvatar("http://a.png");
        publicUser.setUserProfile("简介");
        profile.setUser(publicUser);
        profile.setPictureCount(12L);
        profile.setDownloadCount(340L);
        profile.setFavoriteCount(56L);
        profile.setLikeCount(88L);
        when(userService.getPublicUserProfile(9L)).thenReturn(profile);

        mockMvc.perform(get("/user/public/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.user.id").value("9"))
                .andExpect(jsonPath("$.data.user.userName").value("作者"))
                .andExpect(jsonPath("$.data.pictureCount").value(12))
                .andExpect(jsonPath("$.data.favoriteCount").value(56))
                .andExpect(jsonPath("$.data.user.userAccount").doesNotExist())
                .andExpect(jsonPath("$.data.user.userRole").doesNotExist());
    }

    @Test
    @DisplayName("公开作者主页：用户不存在返回 40400")
    void getPublicUserProfile_returnsNotFound() throws Exception {
        when(userService.getPublicUserProfile(9L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在"));

        mockMvc.perform(get("/user/public/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value("用户不存在"));
    }

    @Test
    @DisplayName("公开作者主页：非法 id 返回 40000，不调用 service")
    void getPublicUserProfile_rejectsIllegalId() throws Exception {
        mockMvc.perform(get("/user/public/0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));

        verify(userService, never()).getPublicUserProfile(anyLong());
    }

    private User user(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserAccount("test");
        user.setUserRole(role);
        return user;
    }
}
