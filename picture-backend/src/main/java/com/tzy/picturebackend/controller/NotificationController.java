package com.tzy.picturebackend.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.annotation.AuthCheck;
import com.tzy.picturebackend.common.BaseResponse;
import com.tzy.picturebackend.common.ResultUtils;
import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.manager.RateLimitManager;
import com.tzy.picturebackend.model.dto.notification.NotificationBroadcastRequest;
import com.tzy.picturebackend.model.dto.notification.NotificationIdsRequest;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.NotificationVO;
import com.tzy.picturebackend.service.NotificationService;
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

import java.time.Duration;
import java.util.Map;

/**
 * 站内通知接口。
 * <p>
 * 除公告外全部只操作「自己的」通知：service 层的 SQL 一律带 userId 条件，
 * 混入他人 id 时静默忽略，避免枚举他人通知。
 */
@RestController
@RequestMapping("/notification")
public class NotificationController {

    private static final long PAGE_SIZE_MAX = 50;

    @Resource
    private NotificationService notificationService;

    @Resource
    private UserService userService;

    @Resource
    private RateLimitManager rateLimitManager;

    /**
     * 我的通知分页
     *
     * @param onlyUnread 只看未读
     */
    @GetMapping("/page")
    public BaseResponse<Page<NotificationVO>> page(@RequestParam(defaultValue = "1") long current,
                                                   @RequestParam(defaultValue = "10") long pageSize,
                                                   @RequestParam(defaultValue = "false") boolean onlyUnread,
                                                   HttpServletRequest request) {
        ThrowUtils.throwIf(current < 1 || pageSize < 1, ErrorCode.PARAMS_ERROR, "分页参数不合法");
        ThrowUtils.throwIf(pageSize > PAGE_SIZE_MAX, ErrorCode.PARAMS_ERROR,
                "分页大小不能超过 " + PAGE_SIZE_MAX);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(notificationService.page(loginUser.getId(), onlyUnread, current, pageSize));
    }

    /**
     * 未读数（顶栏红点轮询用）
     */
    @GetMapping("/unread-count")
    public BaseResponse<Map<String, Long>> unreadCount(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(Map.of("count", notificationService.unreadCount(loginUser.getId())));
    }

    /**
     * 批量标记已读
     */
    @PostMapping("/read")
    public BaseResponse<Map<String, Integer>> markRead(@Valid @RequestBody NotificationIdsRequest idsRequest,
                                                       HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        int updated = notificationService.markRead(loginUser.getId(), idsRequest.getIds());
        return ResultUtils.success(Map.of("updated", updated));
    }

    /**
     * 全部标记已读
     */
    @PostMapping("/read/all")
    public BaseResponse<Map<String, Integer>> markAllRead(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(Map.of("updated", notificationService.markAllRead(loginUser.getId())));
    }

    /**
     * 批量删除自己的通知
     */
    @PostMapping("/delete")
    public BaseResponse<Map<String, Integer>> delete(@Valid @RequestBody NotificationIdsRequest idsRequest,
                                                     HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(Map.of("deleted",
                notificationService.delete(loginUser.getId(), idsRequest.getIds())));
    }

    /**
     * 发送系统公告（仅管理员）
     * <p>
     * 按「每个用户一行」写入；限流 1 次/分钟，避免重复点击把公告发给全员多次。
     */
    @PostMapping("/broadcast")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Map<String, Integer>> broadcast(@Valid @RequestBody NotificationBroadcastRequest broadcastRequest,
                                                        HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        rateLimitManager.check("notification:broadcast:user:" + loginUser.getId(), 1, Duration.ofMinutes(1));
        int sent = notificationService.broadcast(broadcastRequest.getTitle(), broadcastRequest.getContent());
        return ResultUtils.success(Map.of("sent", sent));
    }
}
