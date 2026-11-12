package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.annotation.AuthCheck;
import com.tzy.picturebackend.common.BaseResponse;
import com.tzy.picturebackend.common.ResultUtils;
import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.model.dto.user.UserQuotaRecalcRequest;
import com.tzy.picturebackend.model.dto.user.UserQuotaUpdateRequest;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.UserQuotaVO;
import com.tzy.picturebackend.service.UserQuotaService;
import com.tzy.picturebackend.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户存储配额：个人中心展示已用/上限，管理员调整配额并可按图片表重算。
 */
@RestController
@RequestMapping("/user/quota")
public class UserQuotaController {

    @Resource
    private UserQuotaService userQuotaService;

    @Resource
    private UserService userService;

    /**
     * 查询当前登录用户的配额
     */
    @GetMapping("")
    public BaseResponse<UserQuotaVO> getMyQuota(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(userQuotaService.getQuota(loginUser.getId()));
    }

    /**
     * 管理员调整指定用户的配额，立即生效
     * <p>
     * 只传需要修改的项；调低到已用量以下不会删除已有图片，但会阻止继续上传。
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<UserQuotaVO> updateQuota(@Valid @RequestBody UserQuotaUpdateRequest request) {
        ThrowUtils.throwIf(request == null || request.getUserId() == null,
                ErrorCode.PARAMS_ERROR, "用户 id 不能为空");
        UserQuotaVO quota = userQuotaService.updateQuota(
                request.getUserId(), request.getStorageQuota(), request.getPictureQuota());
        return ResultUtils.success(quota);
    }

    /**
     * 管理员按图片表重算已用量；不传 userId 时全量分批重算
     * <p>
     * 用于「迁移后、发版前」上传造成的用量偏差，以及日常修复计数漂移。
     */
    @PostMapping("/recalc")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Integer> recalcQuota(@RequestBody(required = false) UserQuotaRecalcRequest request) {
        Long userId = request == null ? null : request.getUserId();
        ThrowUtils.throwIf(userId != null && userId <= 0, ErrorCode.PARAMS_ERROR, "用户 id 不合法");
        return ResultUtils.success(userQuotaService.recalc(userId));
    }
}
