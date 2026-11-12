package com.tzy.picturebackend.service.impl;

import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.manager.SessionInvalidationManager;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.mapper.UserMapper;
import com.tzy.picturebackend.model.dto.user.UserQueryRequest;
import com.tzy.picturebackend.model.dto.user.UserUpdateProfileRequest;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.AuthorStatsVO;
import com.tzy.picturebackend.model.vo.LoginUserVO;
import com.tzy.picturebackend.model.vo.PublicUserProfileVO;
import com.tzy.picturebackend.support.InMemoryRedis;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static com.tzy.picturebackend.constant.UserConstant.USER_LOGIN_ID;
import static com.tzy.picturebackend.constant.UserConstant.USER_LOGIN_VERSION;
import static com.tzy.picturebackend.constant.UserConstant.USER_PASSWORD_CHANGE_REQUIRED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserServiceImpl 单元测试，不依赖 Spring 容器与数据库
 */
class UserServiceImplTest {

    private static final String LEGACY_SALT = "yupi";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private UserServiceImpl userService;

    private UserMapper userMapper;

    private PictureMapper pictureMapper;

    private SessionInvalidationManager sessionInvalidationManager;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl();
        userMapper = mock(UserMapper.class);
        sessionInvalidationManager = new SessionInvalidationManager(
                InMemoryRedis.createTemplate(new ConcurrentHashMap<>()));
        // baseMapper 定义在 MyBatis-Plus 的 CrudRepository 中
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        ReflectionTestUtils.setField(userService, "sessionInvalidationManager", sessionInvalidationManager);
        pictureMapper = mock(PictureMapper.class);
        ReflectionTestUtils.setField(userService, "pictureMapper", pictureMapper);
    }

    // ==================== 密码加密 ====================

    @Test
    @DisplayName("加密结果应为 BCrypt 格式")
    void getEncryptPassword_producesBcryptHash() {
        String hash = userService.getEncryptPassword("123456");
        assertTrue(hash.startsWith("$2a$"), "BCrypt 前缀应为 $2a$，实际: " + hash);
        assertEquals(60, hash.length());
        assertTrue(encoder.matches("123456", hash));
    }

    @Test
    @DisplayName("同一明文两次加密结果不同（自带随机盐）")
    void getEncryptPassword_usesRandomSalt() {
        assertNotEquals(userService.getEncryptPassword("123456"), userService.getEncryptPassword("123456"));
    }

    // ==================== 注册校验 ====================

    @Test
    @DisplayName("注册：参数为空应拒绝")
    void userRegister_rejectsBlankParams() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.userRegister("", "123456", "123456"));
        assertEquals("参数为空", e.getMessage());
    }

    @Test
    @DisplayName("注册：账号少于 4 位应拒绝")
    void userRegister_rejectsShortAccount() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.userRegister("abc", "123456", "123456"));
        assertEquals("账号过短", e.getMessage());
    }

    @Test
    @DisplayName("注册：密码少于 8 位应拒绝")
    void userRegister_rejectsShortPassword() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.userRegister("tzyy", "123", "123"));
        assertEquals("密码长度不能少于 8 位", e.getMessage());
    }

    @Test
    @DisplayName("注册：密码必须同时包含字母和数字")
    void userRegister_rejectsWeakPassword() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.userRegister("tzyy", "abcdefgh", "abcdefgh"));
        assertEquals("密码必须同时包含字母和数字", e.getMessage());
    }

    @Test
    @DisplayName("注册：密码不能包含账号")
    void userRegister_rejectsPasswordContainingAccount() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.userRegister("tzyy", "tzyy1234", "tzyy1234"));
        assertEquals("密码不能包含账号", e.getMessage());
    }

    @Test
    @DisplayName("注册：两次密码不一致应拒绝")
    void userRegister_rejectsMismatchedPassword() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.userRegister("tzyy", "Abcdef12", "Abcdef34"));
        assertEquals("密码不一致", e.getMessage());
    }

    @Test
    @DisplayName("注册：账号已存在应拒绝")
    void userRegister_rejectsDuplicateAccount() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.userRegister("tzyy", "Abcdef12", "Abcdef12"));
        assertEquals("账号重复", e.getMessage());
    }

    @Test
    @DisplayName("注册：入库密码应为 BCrypt 密文，角色为 user，并要求首次登录改密")
    void userRegister_storesBcryptPassword() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(100L);
            return 1;
        });

        long id = userService.userRegister("tzyy", "Abcdef12", "Abcdef12");
        assertEquals(100L, id);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertTrue(encoder.matches("Abcdef12", saved.getUserPassword()), "入库密码应能用明文校验通过");
        assertEquals("user", saved.getUserRole());
        assertEquals(Boolean.TRUE, saved.getPasswordChangeRequired(), "新注册用户首次登录必须改密");
    }

    // ==================== 登录 ====================

    @Test
    @DisplayName("登录：密码错误应拒绝")
    void userLogin_rejectsWrongPassword() {
        when(userMapper.selectOne(any())).thenReturn(userWithPassword(encoder.encode("123456")));

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.userLogin("tzyy", "wrong6", new MockHttpServletRequest()));
        assertEquals("用户不存在或密码错误", e.getMessage());
    }

    @Test
    @DisplayName("登录：账号不存在应拒绝")
    void userLogin_rejectsUnknownAccount() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> userService.userLogin("nobody", "123456", new MockHttpServletRequest()));
    }

    @Test
    @DisplayName("登录：成功后会更换 session id，防止会话固定")
    void userLogin_rotatesSessionId() {
        User user = userWithPassword(encoder.encode("123456"));
        when(userMapper.selectOne(any())).thenReturn(user);
        MockHttpServletRequest request = new MockHttpServletRequest();
        String oldSessionId = request.getSession(true).getId();

        userService.userLogin("tzyy", "123456", request);

        assertNotEquals(oldSessionId, request.getSession().getId());
        assertNotNull(request.getSession().getAttribute(USER_LOGIN_ID), "登录后应写入用户 id");
        assertNotNull(request.getSession().getAttribute(USER_LOGIN_VERSION), "登录后应写入会话版本");
    }

    @Test
    @DisplayName("登录：BCrypt 密码校验通过并写入 Session")
    void userLogin_successWithBcryptPassword() {
        User user = userWithPassword(encoder.encode("123456"));
        when(userMapper.selectOne(any())).thenReturn(user);
        MockHttpServletRequest request = new MockHttpServletRequest();

        LoginUserVO vo = userService.userLogin("tzyy", "123456", request);

        assertNotNull(vo);
        assertNotNull(request.getSession().getAttribute(USER_LOGIN_ID), "登录后应只写入用户 id");
        assertNotNull(request.getSession().getAttribute(USER_LOGIN_VERSION), "登录后应写入会话版本");
        assertEquals(Boolean.FALSE, request.getSession().getAttribute(USER_PASSWORD_CHANGE_REQUIRED));
        // 已是 BCrypt，不应触发升级
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("登录：临时密码账号会写入强制改密标记，并在返回信息中标记")
    void userLogin_marksPasswordChangeRequired() {
        User user = userWithPassword(encoder.encode("Abcdef12"));
        user.setPasswordChangeRequired(true);
        when(userMapper.selectOne(any())).thenReturn(user);
        MockHttpServletRequest request = new MockHttpServletRequest();

        LoginUserVO vo = userService.userLogin("tzyy", "Abcdef12", request);

        assertEquals(Boolean.TRUE, vo.getPasswordChangeRequired());
        assertEquals(Boolean.TRUE,
                request.getSession().getAttribute(USER_PASSWORD_CHANGE_REQUIRED),
                "首次登录应让后续请求被强制改密过滤器拦截");
    }

    @Test
    @DisplayName("登录态：密码变更后旧版本 Session 按未登录处理")
    void getLoginUser_rejectsInvalidatedSession() {
        User user = userWithPassword(encoder.encode("123456"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpSession session = request.getSession(true);
        session.setAttribute(USER_LOGIN_ID, user.getId());
        session.setAttribute(USER_LOGIN_VERSION, sessionInvalidationManager.currentVersion());
        when(userMapper.selectById(1L)).thenReturn(user);

        sessionInvalidationManager.invalidate(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.getLoginUser(request));
        assertEquals(40100, e.getCode());
        assertNull(session.getAttribute(USER_LOGIN_ID));
    }

    @Test
    @DisplayName("登录态：密码变更后 getLoginUserOrNull 返回 null")
    void getLoginUserOrNull_returnsNullForInvalidatedSession() {
        User user = userWithPassword(encoder.encode("123456"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpSession session = request.getSession(true);
        session.setAttribute(USER_LOGIN_ID, user.getId());
        session.setAttribute(USER_LOGIN_VERSION, sessionInvalidationManager.currentVersion());

        sessionInvalidationManager.invalidate(1L);

        assertNull(userService.getLoginUserOrNull(request));
        assertNull(session.getAttribute(USER_LOGIN_ID));
    }

    @Test
    @DisplayName("登录态：未失效版本可以正常读取登录用户")
    void getLoginUser_acceptsCurrentSessionVersion() {
        User user = userWithPassword(encoder.encode("123456"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpSession session = request.getSession(true);
        session.setAttribute(USER_LOGIN_ID, user.getId());
        session.setAttribute(USER_LOGIN_VERSION, sessionInvalidationManager.currentVersion());
        when(userMapper.selectById(1L)).thenReturn(user);

        assertNotNull(userService.getLoginUser(request));
    }

    @Test
    @DisplayName("登录：存量 MD5 密码校验通过后自动升级为 BCrypt")
    void userLogin_upgradesLegacyMd5Password() {
        String legacyHash = DigestUtils.md5DigestAsHex(
                (LEGACY_SALT + "123456").getBytes(StandardCharsets.UTF_8));
        User user = userWithPassword(legacyHash);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        userService.userLogin("tzyy", "123456", new MockHttpServletRequest());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        User upgraded = captor.getValue();
        assertEquals(user.getId(), upgraded.getId());
        assertTrue(upgraded.getUserPassword().startsWith("$2a$"), "升级后的密文应为 BCrypt");
        assertTrue(encoder.matches("123456", upgraded.getUserPassword()));
    }

    // ==================== 修改密码 ====================

    @Test
    @DisplayName("改密：未登录应拒绝")
    void updatePassword_rejectsAnonymous() {
        assertThrows(BusinessException.class,
                () -> userService.updatePassword(null, "123456", "abcdef"));
    }

    @Test
    @DisplayName("改密：新密码少于 8 位应拒绝")
    void updatePassword_rejectsShortNewPassword() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.updatePassword(userWithPassword("x"), "123456", "123"));
        assertEquals("密码长度不能少于 8 位", e.getMessage());
    }

    @Test
    @DisplayName("改密：新旧密码相同应拒绝")
    void updatePassword_rejectsSamePassword() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.updatePassword(userWithPassword("x"), "Abcdef12", "Abcdef12"));
        assertEquals("新密码不能与原密码相同", e.getMessage());
    }

    @Test
    @DisplayName("改密：原密码错误应拒绝")
    void updatePassword_rejectsWrongOldPassword() {
        User stored = userWithPassword(encoder.encode("123456"));
        when(userMapper.selectById(any())).thenReturn(stored);

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.updatePassword(loginUser(), "wrong-old", "newpass1"));
        assertEquals("原密码错误", e.getMessage());
    }

    @Test
    @DisplayName("改密：校验通过后写入新的 BCrypt 密文")
    void updatePassword_updatesHash() {
        User stored = userWithPassword(encoder.encode("123456"));
        when(userMapper.selectById(any())).thenReturn(stored);
        lenient().when(userMapper.updateById(any(User.class))).thenReturn(1);

        userService.updatePassword(loginUser(), "123456", "newpass1");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertTrue(encoder.matches("newpass1", captor.getValue().getUserPassword()));
        assertEquals(Boolean.FALSE, captor.getValue().getPasswordChangeRequired(),
                "用户自行改密后应清除强制改密标记");
        assertTrue(sessionInvalidationManager.isInvalidated(1L, 0L),
                "修改密码后旧会话版本应失效");
    }

    // ==================== 管理员重置密码 ====================

    @Test
    @DisplayName("重置密码：管理员重置后 BCrypt 可校验新密码")
    void resetPassword_updatesBcryptHash() {
        when(userMapper.selectById(1L)).thenReturn(userWithPassword(encoder.encode("123456")));
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        assertTrue(userService.resetPassword(1L, "newpass1"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        User updated = captor.getValue();
        assertEquals(Long.valueOf(1L), updated.getId());
        assertTrue(updated.getUserPassword().startsWith("$2a$"));
        assertTrue(encoder.matches("newpass1", updated.getUserPassword()));
        assertEquals(Boolean.TRUE, updated.getPasswordChangeRequired(),
                "管理员重置的临时密码应在首次登录后强制修改");
        assertTrue(sessionInvalidationManager.isInvalidated(1L, 0L),
                "重置密码后目标用户旧会话应失效");
    }

    @Test
    @DisplayName("重置密码：新密码包含账号应拒绝")
    void resetPassword_rejectsPasswordContainingAccount() {
        when(userMapper.selectById(1L)).thenReturn(userWithPassword(encoder.encode("123456")));

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.resetPassword(1L, "tzyy1234"));
        assertEquals("密码不能包含账号", e.getMessage());
    }

    @Test
    @DisplayName("重置密码：用户不存在应抛 40400")
    void resetPassword_rejectsMissingUser() {
        when(userMapper.selectById(1L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.resetPassword(1L, "newpass1"));
        assertEquals("用户不存在", e.getMessage());
    }

    @Test
    @DisplayName("重置密码：新密码少于 8 位应拒绝")
    void resetPassword_rejectsShortPassword() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.resetPassword(1L, "123"));
        assertEquals("密码长度不能少于 8 位", e.getMessage());
    }

    @Test
    @DisplayName("重置密码：新密码超过 64 位应拒绝")
    void resetPassword_rejectsTooLongPassword() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.resetPassword(1L, "p".repeat(65)));
        assertEquals("密码长度不能超过 64 位", e.getMessage());
    }

    @Test
    @DisplayName("重置密码：空密码应拒绝")
    void resetPassword_rejectsBlankPassword() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.resetPassword(1L, "   "));
        assertEquals("新密码不能为空", e.getMessage());
    }

    // ==================== 个人资料 ====================

    @Test
    @DisplayName("个人资料：未登录应拒绝")
    void updateProfile_rejectsAnonymous() {
        assertThrows(BusinessException.class,
                () -> userService.updateProfile(null, new UserUpdateProfileRequest()));
    }

    @Test
    @DisplayName("个人资料：请求为空应拒绝")
    void updateProfile_rejectsNullRequest() {
        assertThrows(BusinessException.class,
                () -> userService.updateProfile(loginUser(), null));
    }

    @Test
    @DisplayName("个人资料：只更新当前登录用户的昵称、头像和简介")
    void updateProfile_updatesOnlyAllowedFields() {
        UserUpdateProfileRequest request = new UserUpdateProfileRequest();
        request.setUserName("新昵称");
        request.setUserAvatar("http://a.png");
        request.setUserProfile("新简介");
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        assertTrue(userService.updateProfile(loginUser(), request));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        User updated = captor.getValue();
        assertEquals(Long.valueOf(1L), updated.getId());
        assertEquals("新昵称", updated.getUserName());
        assertEquals("http://a.png", updated.getUserAvatar());
        assertEquals("新简介", updated.getUserProfile());
        assertNull(updated.getUserRole(), "不允许通过个人资料接口改角色");
        assertNull(updated.getUserPassword(), "不允许通过个人资料接口改密码");
    }

    @Test
    @DisplayName("个人资料：数据库更新失败应抛操作异常")
    void updateProfile_throwsWhenUpdateFails() {
        when(userMapper.updateById(any(User.class))).thenReturn(0);

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.updateProfile(loginUser(), new UserUpdateProfileRequest()));
        assertEquals("更新个人资料失败", e.getMessage());
    }

    // ==================== 作者主页 ====================

    @Test
    @DisplayName("作者主页：返回公开资料与已过审作品统计")
    void getPublicUserProfile_returnsPublicFieldsAndStats() {
        User author = new User();
        author.setId(9L);
        author.setUserAccount("author001");
        author.setUserName("作者");
        author.setUserAvatar("http://a.png");
        author.setUserProfile("简介");
        author.setUserRole("user");
        author.setCreateTime(new java.util.Date(1700000000000L));
        when(userMapper.selectById(9L)).thenReturn(author);
        AuthorStatsVO stats = new AuthorStatsVO();
        stats.setPictureCount(12L);
        stats.setDownloadCount(340L);
        stats.setFavoriteCount(56L);
        stats.setLikeCount(88L);
        when(pictureMapper.selectAuthorStats(9L)).thenReturn(stats);

        PublicUserProfileVO profile = userService.getPublicUserProfile(9L);

        assertNotNull(profile);
        assertEquals("作者", profile.getUser().getUserName());
        assertEquals(12L, profile.getPictureCount());
        assertEquals(340L, profile.getDownloadCount());
        assertEquals(56L, profile.getFavoriteCount());
        assertEquals(88L, profile.getLikeCount());
        assertEquals(author.getCreateTime(), profile.getJoinedAt());
        // 公开资料不允许包含账号与角色
        assertEquals(9L, profile.getUser().getId());
    }

    @Test
    @DisplayName("作者主页：没有公开作品时统计全部为 0")
    void getPublicUserProfile_returnsZeroStatsWhenNoPublicPicture() {
        User author = new User();
        author.setId(9L);
        author.setUserName("作者");
        when(userMapper.selectById(9L)).thenReturn(author);
        when(pictureMapper.selectAuthorStats(9L)).thenReturn(null);

        PublicUserProfileVO profile = userService.getPublicUserProfile(9L);

        assertEquals(0L, profile.getPictureCount());
        assertEquals(0L, profile.getDownloadCount());
        assertEquals(0L, profile.getFavoriteCount());
        assertEquals(0L, profile.getLikeCount());
    }

    @Test
    @DisplayName("作者主页：用户不存在返回 40400")
    void getPublicUserProfile_throwsNotFoundForMissingUser() {
        when(userMapper.selectById(9L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.getPublicUserProfile(9L));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), e.getCode());
    }

    @Test
    @DisplayName("作者主页：非法 id 返回 40000，且不查库")
    void getPublicUserProfile_rejectsIllegalId() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.getPublicUserProfile(0L));
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), e.getCode());
        verify(userMapper, never()).selectById(any());
    }

    // ==================== 排序 ====================

    @Test
    @DisplayName("排序：白名单内的字段按指定方向拼接 ORDER BY")
    void getQueryWrapper_sortsByWhitelistedField() {
        String descSql = userService.getQueryWrapper(queryRequest("createTime", "descend")).getSqlSegment();
        String ascSql = userService.getQueryWrapper(queryRequest("userAccount", "ascend")).getSqlSegment();

        assertTrue(descSql.contains("ORDER BY createTime DESC"), "实际 SQL 片段: " + descSql);
        assertTrue(ascSql.contains("ORDER BY userAccount ASC"), "实际 SQL 片段: " + ascSql);
    }

    @Test
    @DisplayName("排序：不在白名单内的字段应拒绝，包括密码列与注入片段")
    void getQueryWrapper_rejectsSortFieldOutsideWhitelist() {
        List<String> illegalSortFields = List.of("userPassword", "createTime,(select 1)", "isDelete", " ");

        for (String sortField : illegalSortFields) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> userService.getQueryWrapper(queryRequest(sortField, "descend")),
                    "应拒绝排序字段: " + sortField);
            assertEquals("排序字段不合法", e.getMessage());
        }
    }

    @Test
    @DisplayName("排序：不传排序字段时不拼接 ORDER BY，sortOrder 为空也不抛空指针")
    void getQueryWrapper_skipsOrderByWithoutSortField() {
        String sql = userService.getQueryWrapper(queryRequest(null, null)).getSqlSegment();

        assertFalse(sql.contains("ORDER BY"), "实际 SQL 片段: " + sql);
    }

    @Test
    @DisplayName("排序：白名单内的字段必须都是 User 实体的属性")
    void sortFieldWhitelist_containsOnlyEntityFields() {
        for (String sortField : UserServiceImpl.SORT_FIELD_WHITELIST) {
            assertDoesNotThrow(() -> User.class.getDeclaredField(sortField),
                    "白名单字段不是 User 的属性: " + sortField);
        }
    }

    // ==================== 辅助方法 ====================

    private UserQueryRequest queryRequest(String sortField, String sortOrder) {
        UserQueryRequest request = new UserQueryRequest();
        request.setSortField(sortField);
        request.setSortOrder(sortOrder);
        return request;
    }

    private User userWithPassword(String password) {
        User user = new User();
        user.setId(1L);
        user.setUserAccount("tzyy");
        user.setUserPassword(password);
        user.setUserRole("user");
        return user;
    }

    private User loginUser() {
        User user = new User();
        user.setId(1L);
        user.setUserAccount("tzyy");
        return user;
    }
}
