package com.tzy.picturebackend.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.annotation.AuthCheck;
import com.tzy.picturebackend.common.BaseResponse;
import com.tzy.picturebackend.common.ResultUtils;
import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.model.dto.interaction.InteractionPageRequest;
import com.tzy.picturebackend.model.dto.interaction.InteractionRequest;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.InteractionStatusVO;
import com.tzy.picturebackend.model.vo.PictureVO;
import com.tzy.picturebackend.service.PictureInteractionService;
import com.tzy.picturebackend.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收藏 / 点赞接口。
 * <p>
 * 写接口要求登录，重复点击是幂等的（返回当前状态而不是报错）；
 * 查询接口允许匿名，未登录时只返回计数、选中状态恒为 false。
 */
@RestController
@RequestMapping("/interaction")
public class InteractionController {

    /**
     * 「我的收藏」单页上限，与公开列表保持一致
     */
    private static final long MY_PAGE_MAX_SIZE = 20;

    @Resource
    private PictureInteractionService pictureInteractionService;

    @Resource
    private UserService userService;

    /**
     * 收藏 / 点赞
     */
    @PostMapping("/add")
    public BaseResponse<InteractionStatusVO> add(@Valid @RequestBody InteractionRequest interactionRequest,
                                                 HttpServletRequest request) {
        ThrowUtils.throwIf(interactionRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(pictureInteractionService.add(interactionRequest, loginUser));
    }

    /**
     * 取消收藏 / 取消点赞
     */
    @PostMapping("/remove")
    public BaseResponse<InteractionStatusVO> remove(@Valid @RequestBody InteractionRequest interactionRequest,
                                                    HttpServletRequest request) {
        ThrowUtils.throwIf(interactionRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(pictureInteractionService.remove(interactionRequest, loginUser));
    }

    /**
     * 查询某张图片的互动状态与计数（匿名可用）
     */
    @GetMapping("/status")
    public BaseResponse<InteractionStatusVO> status(@RequestParam Long pictureId,
                                                    HttpServletRequest request) {
        return ResultUtils.success(pictureInteractionService.status(pictureId, request));
    }

    /**
     * 我的收藏 / 我的点赞
     */
    @PostMapping("/my/page")
    public BaseResponse<Page<PictureVO>> pageMy(@Valid @RequestBody InteractionPageRequest interactionPageRequest,
                                               HttpServletRequest request) {
        ThrowUtils.throwIf(interactionPageRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(interactionPageRequest.getPageSize() > MY_PAGE_MAX_SIZE,
                ErrorCode.PARAMS_ERROR, "分页大小不能超过 " + MY_PAGE_MAX_SIZE);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(pictureInteractionService.pageMy(interactionPageRequest, loginUser, request));
    }

    /**
     * 按关系表重算收藏数 / 点赞数（仅管理员）
     * <p>
     * 不传 pictureId 时全量重算（按主键游标分批），用于修复计数漂移。
     */
    @PostMapping("/recount")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Integer> recount(@RequestParam(required = false) Long pictureId) {
        return ResultUtils.success(pictureInteractionService.recount(pictureId));
    }
}
