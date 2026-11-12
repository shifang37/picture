package com.tzy.picturebackend.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.annotation.AuthCheck;
import com.tzy.picturebackend.common.BaseResponse;
import com.tzy.picturebackend.common.DeleteRequest;
import com.tzy.picturebackend.common.ResultUtils;
import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.event.NotificationEventPublisher;
import com.tzy.picturebackend.manager.CaptchaManager;
import com.tzy.picturebackend.manager.RegistrationGuard;
import com.tzy.picturebackend.manager.SessionInvalidationManager;
import com.tzy.picturebackend.model.dto.notification.NotificationMessage;
import com.tzy.picturebackend.model.dto.user.*;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.NotificationTypeEnum;
import com.tzy.picturebackend.model.enums.UserRoleEnum;
import com.tzy.picturebackend.model.vo.CaptchaVO;
import com.tzy.picturebackend.model.vo.LoginUserVO;
import com.tzy.picturebackend.model.vo.PublicUserProfileVO;
import com.tzy.picturebackend.model.vo.PublicUserVO;
import com.tzy.picturebackend.model.vo.UserAddResult;
import com.tzy.picturebackend.model.vo.UserVO;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.NotificationService;
import com.tzy.picturebackend.service.UserService;
import com.tzy.picturebackend.validation.PasswordPolicy;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    @Resource
    private PictureService pictureService;

    @Resource
    private SessionInvalidationManager sessionInvalidationManager;

    @Resource
    private RegistrationGuard registrationGuard;

    @Resource
    private CaptchaManager captchaManager;

    @Resource
    private NotificationService notificationService;

    @Resource
    private NotificationEventPublisher notificationEventPublisher;

    /**
     * 获取注册用图形验证码。
     * <p>
     * 返回的 captchaId 与图片一次性使用，5 分钟内有效；注册时必须同时提交。
     */
    @GetMapping("/captcha")
    public BaseResponse<CaptchaVO> getRegisterCaptcha() {
        return ResultUtils.success(captchaManager.generate());
    }

    @PostMapping("/register")
    public BaseResponse<Long> userRegister(@Valid @RequestBody UserRegisterRequest userRegisterRequest) {
        ThrowUtils.throwIf(userRegisterRequest == null, ErrorCode.PARAMS_ERROR);
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        // 图形验证码 + 账号维度限流；IP 维度已在 RateLimitFilter 中完成
        registrationGuard.check(userAccount,
                userRegisterRequest.getCaptchaId(), userRegisterRequest.getCaptchaCode());
        long result = userService.userRegister(userAccount, userPassword, checkPassword);
        return ResultUtils.success(result);
    }

    @PostMapping("/login")
    public BaseResponse<LoginUserVO> userLogin(@Valid @RequestBody UserLoginRequest userLoginRequest,
                                               HttpServletRequest request) {
        ThrowUtils.throwIf(userLoginRequest == null, ErrorCode.PARAMS_ERROR);
        String userAccount = userLoginRequest.getUserAccount();
        String userPassword = userLoginRequest.getUserPassword();
        LoginUserVO loginUserVO = userService.userLogin(userAccount, userPassword, request);
        return ResultUtils.success(loginUserVO);
    }

    @GetMapping("/get/login")
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request) {
        User user = userService.getLoginUser(request);
        return ResultUtils.success(userService.getLoginUserVO(user));
    }

    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        boolean result = userService.userLogout(request);
        return ResultUtils.success(result);
    }

    /**
     * 修改当前登录用户的密码
     */
    @PostMapping("/update/password")
    public BaseResponse<Boolean> updatePassword(
            @Valid @RequestBody UserUpdatePasswordRequest userUpdatePasswordRequest,
            HttpServletRequest request) {
        ThrowUtils.throwIf(userUpdatePasswordRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        boolean result = userService.updatePassword(loginUser,
                userUpdatePasswordRequest.getOldPassword(),
                userUpdatePasswordRequest.getNewPassword());
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 修改密码会让其他设备的旧会话失效；当前会话刷新到最新版本，保持登录
        request.getSession().setAttribute(UserConstant.USER_LOGIN_VERSION,
                sessionInvalidationManager.currentVersion());
        // 本次改密已完成，解除首次登录强制改密限制
        request.getSession().setAttribute(UserConstant.USER_PASSWORD_CHANGE_REQUIRED, false);
        return ResultUtils.success(true);
    }

    /**
     * 管理员重置指定用户的密码
     * <p>
     * 不需要用户原密码；重置后旧密码立即失效，用户需使用新密码登录。
     */
    @PostMapping("/reset/password")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> resetPassword(@Valid @RequestBody UserResetPasswordRequest userResetPasswordRequest) {
        ThrowUtils.throwIf(userResetPasswordRequest == null, ErrorCode.PARAMS_ERROR);
        boolean result = userService.resetPassword(userResetPasswordRequest.getId(),
                userResetPasswordRequest.getNewPassword());
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 安全提醒：不把新密码写进通知，只提示「已被重置」
        notificationEventPublisher.publish(NotificationMessage.account(
                NotificationTypeEnum.PASSWORD_RESET, userResetPasswordRequest.getId(),
                "你的登录密码已被管理员重置，请使用新的初始密码登录，并按提示尽快修改为自己的密码。"));
        return ResultUtils.success(true);
    }

    /**
     * 修改当前登录用户的个人资料（普通用户可用）
     */
    @PostMapping("/update/profile")
    public BaseResponse<Boolean> updateProfile(
            @Valid @RequestBody UserUpdateProfileRequest userUpdateProfileRequest,
            HttpServletRequest request) {
        ThrowUtils.throwIf(userUpdateProfileRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        boolean result = userService.updateProfile(loginUser, userUpdateProfileRequest);
        return ResultUtils.success(result);
    }

    /**
     * 创建用户
     * <p>
     * 初始密码由服务端随机生成，只在本响应中返回一次；用户首次登录必须修改密码。
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<UserAddResult> addUser(@Valid @RequestBody UserAddRequest userAddRequest) {
        ThrowUtils.throwIf(userAddRequest == null, ErrorCode.PARAMS_ERROR);
        // 账号重复预检查，避免直接触发数据库唯一索引异常
        long count = userService.count(new QueryWrapper<User>()
                .eq("userAccount", userAddRequest.getUserAccount()));
        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR, "账号已存在");
        User user = new User();
        BeanUtils.copyProperties(userAddRequest, user);
        user.setUserRole(normalizeRole(userAddRequest.getUserRole()));
        String initialPassword = PasswordPolicy.generateTemporaryPassword();
        user.setUserPassword(userService.getEncryptPassword(initialPassword));
        user.setPasswordChangeRequired(true);
        boolean result = userService.save(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(new UserAddResult(user.getId(), initialPassword));
    }

    /**
     * 根据 id 获取用户（仅管理员）
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<User> getUserById(Long id) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(user);
    }

    /**
     * 根据 id 获取公开用户信息（昵称、头像、简介）
     * <p>
     * 该接口无需登录即可访问，不能返回账号、角色等内部字段。
     */
    @GetMapping("/get/vo")
    public BaseResponse<PublicUserVO> getUserVOById(Long id) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(userService.getPublicUserVO(user));
    }

    /**
     * 获取公开作者主页信息（昵称、头像、简介、作品与互动统计）
     * <p>
     * 无需登录；只统计已通过审核且未删除的作品，结果缓存 5 分钟。
     * 用户不存在或已删除返回 40400。
     */
    @GetMapping("/public/{id}")
    public BaseResponse<PublicUserProfileVO> getPublicUserProfile(@PathVariable Long id) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "用户 id 不能为空");
        return ResultUtils.success(userService.getPublicUserProfile(id));
    }

    /**
     * 删除用户
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteUser(@Valid @RequestBody DeleteRequest deleteRequest,
                                            HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null
                || deleteRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        ThrowUtils.throwIf(deleteRequest.getId().equals(loginUser.getId()),
                ErrorCode.PARAMS_ERROR, "不能删除自己");
        User oldUser = userService.getById(deleteRequest.getId());
        ThrowUtils.throwIf(oldUser == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        // 用户还有图片时拒绝删除，避免留下公开的孤儿图片；图片需由管理员先处理
        long pictureCount = pictureService.count(
                new QueryWrapper<Picture>().eq("userId", deleteRequest.getId()));
        ThrowUtils.throwIf(pictureCount > 0, ErrorCode.PARAMS_ERROR, "该用户还有图片，请先处理该用户的图片");
        boolean result = userService.removeById(deleteRequest.getId());
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除用户失败");
        sessionInvalidationManager.forget(deleteRequest.getId());
        // 作者主页缓存必须立即失效，否则 TTL 内仍会返回一个已删除的用户
        userService.evictPublicUserProfile(deleteRequest.getId());
        // 用户已删除，其通知不会再有人查看，直接清理
        notificationService.deleteByUserId(deleteRequest.getId());
        return ResultUtils.success(true);
    }

    /**
     * 更新用户
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateUser(@Valid @RequestBody UserUpdateRequest userUpdateRequest) {
        ThrowUtils.throwIf(userUpdateRequest == null || userUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR);
        User oldUser = userService.getById(userUpdateRequest.getId());
        ThrowUtils.throwIf(oldUser == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        User user = new User();
        user.setId(userUpdateRequest.getId());
        user.setUserName(userUpdateRequest.getUserName());
        user.setUserAvatar(userUpdateRequest.getUserAvatar());
        user.setUserProfile(userUpdateRequest.getUserProfile());
        // 不传角色表示不修改，避免把管理员误降级为普通用户
        boolean roleChanged = false;
        String newRole = null;
        if (StrUtil.isNotBlank(userUpdateRequest.getUserRole())) {
            newRole = normalizeRole(userUpdateRequest.getUserRole());
            boolean wasAdmin = UserRoleEnum.ADMIN.getValue().equals(oldUser.getUserRole());
            boolean willBeAdmin = UserRoleEnum.ADMIN.getValue().equals(newRole);
            if (wasAdmin && !willBeAdmin) {
                long adminCount = userService.count(
                        new QueryWrapper<User>().eq("userRole", UserRoleEnum.ADMIN.getValue()));
                ThrowUtils.throwIf(adminCount <= 1, ErrorCode.PARAMS_ERROR, "系统至少需要保留一个管理员");
            }
            user.setUserRole(newRole);
            // 角色真的发生变化才提醒，避免每次保存资料都发一条安全通知
            roleChanged = !Objects.equals(oldUser.getUserRole(), newRole);
        }
        boolean result = userService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        if (roleChanged) {
            notificationEventPublisher.publish(NotificationMessage.account(
                    NotificationTypeEnum.ROLE_CHANGED, userUpdateRequest.getId(),
                    "你的账号角色已变更为「"
                            + (UserRoleEnum.ADMIN.getValue().equals(newRole) ? "管理员" : "普通用户")
                            + "」，如非本人操作请立即联系管理员。"));
        }
        return ResultUtils.success(true);
    }

    /**
     * 分页获取用户封装列表（仅管理员）
     *
     * @param userQueryRequest 查询请求参数
     */
    @PostMapping("/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<UserVO>> listUserVOByPage(
            @Valid @RequestBody UserQueryRequest userQueryRequest) {
        ThrowUtils.throwIf(userQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long current = userQueryRequest.getCurrent();
        long pageSize = userQueryRequest.getPageSize();
        ThrowUtils.throwIf(current < 1 || pageSize < 1, ErrorCode.PARAMS_ERROR, "分页参数不合法");
        ThrowUtils.throwIf(pageSize > 100, ErrorCode.PARAMS_ERROR, "分页大小不能超过 100");
        Page<User> userPage = userService.page(new Page<>(current, pageSize),
                userService.getQueryWrapper(userQueryRequest));
        Page<UserVO> userVOPage = new Page<>(current, pageSize, userPage.getTotal());
        List<UserVO> userVOList = userService.getUserVOList(userPage.getRecords());
        userVOPage.setRecords(userVOList);
        return ResultUtils.success(userVOPage);
    }

    /**
     * 校验并归一化用户角色，空值缺省为普通用户
     */
    private String normalizeRole(String userRole) {
        if (StrUtil.isBlank(userRole)) {
            return UserRoleEnum.USER.getValue();
        }
        UserRoleEnum roleEnum = UserRoleEnum.getEnumByValue(userRole);
        ThrowUtils.throwIf(roleEnum == null, ErrorCode.PARAMS_ERROR, "用户角色不合法");
        return roleEnum.getValue();
    }
}
