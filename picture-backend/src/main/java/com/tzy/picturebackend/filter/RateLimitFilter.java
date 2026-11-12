package com.tzy.picturebackend.filter;

import cn.hutool.core.util.StrUtil;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.manager.RateLimitManager;
import com.tzy.picturebackend.metrics.PictureMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.annotation.Resource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.tzy.picturebackend.constant.UserConstant.USER_LOGIN_ID;

/**
 * 登录、注册、验证码与上传接口限流，避免暴力破解、批量注册和恶意刷上传。
 * <p>
 * 注册这里只做 IP 维度：账号维度需要读取 JSON 请求体，放在
 * {@link com.tzy.picturebackend.manager.RegistrationGuard} 中，由 Controller 在解析参数后执行。
 * <p>
 * 顺序必须晚于 Spring Session 的 SessionRepositoryFilter（Integer.MIN_VALUE + 50）：
 * 否则这里读到的还是 Tomcat 的空会话，上传限流会静悄悄地从「按用户」退化成「按 IP」。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 60)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int LOGIN_LIMIT_PER_MINUTE = 10;

    /**
     * 同一 IP 10 分钟最多发起 10 次注册请求（账号维度另有限制）
     */
    private static final int REGISTER_LIMIT_PER_TEN_MINUTES = 10;

    /**
     * 同一 IP 每分钟最多获取 30 个验证码，防止刷验证码消耗 CPU
     */
    private static final int CAPTCHA_LIMIT_PER_MINUTE = 30;

    private static final int UPLOAD_LIMIT_PER_MINUTE = 60;

    /**
     * 单个用户每分钟最多 120 次收藏/点赞写操作：防连点与脚本刷量，正常浏览不会触达
     */
    private static final int INTERACTION_LIMIT_PER_MINUTE = 120;

    /**
     * 单个用户每分钟最多 30 次批量编辑/删除：单次最多 100 张，正常操作远低于该阈值
     */
    private static final int BATCH_LIMIT_PER_MINUTE = 30;

    @Resource
    private RateLimitManager rateLimitManager;

    @Resource
    private PictureMetrics pictureMetrics;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = relativePath(request);
        String method = request.getMethod();
        try {
            if ("POST".equalsIgnoreCase(method)) {
                if ("/user/login".equals(path)) {
                    rateLimitManager.check("login:ip:" + clientIp(request),
                            LOGIN_LIMIT_PER_MINUTE, Duration.ofMinutes(1));
                } else if ("/user/register".equals(path)) {
                    rateLimitManager.check("register:ip:" + clientIp(request),
                            REGISTER_LIMIT_PER_TEN_MINUTES, Duration.ofMinutes(10));
                } else if ("/picture/upload".equals(path)) {
                    rateLimitManager.check(uploadKey(request), UPLOAD_LIMIT_PER_MINUTE,
                            Duration.ofMinutes(1));
                } else if ("/interaction/add".equals(path) || "/interaction/remove".equals(path)) {
                    // 收藏/点赞写操作按用户限流；未登录时退化为按 IP
                    rateLimitManager.check(userKey(request, "interaction"),
                            INTERACTION_LIMIT_PER_MINUTE, Duration.ofMinutes(1));
                } else if ("/picture/batch/edit".equals(path) || "/picture/batch/delete".equals(path)) {
                    // 批量操作单次最多 100 张，按用户限制调用频率，避免脚本刷接口
                    rateLimitManager.check(userKey(request, "pictureBatch"),
                            BATCH_LIMIT_PER_MINUTE, Duration.ofMinutes(1));
                }
            } else if ("GET".equalsIgnoreCase(method) && "/user/captcha".equals(path)) {
                rateLimitManager.check("captcha:ip:" + clientIp(request),
                        CAPTCHA_LIMIT_PER_MINUTE, Duration.ofMinutes(1));
            }
        } catch (BusinessException e) {
            // 限流响应同样是 HTTP 200，按业务码无法区分来源，这里单独计数
            pictureMetrics.rateLimited(rateLimitType(path));
            writeError(response, e.getCode(), e.getMessage());
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 被限流接口对应的指标标签，未命中时归入 other，便于发现遗漏的配置
     */
    private String rateLimitType(String path) {
        return switch (path) {
            case "/user/login" -> "login";
            case "/user/register" -> "register";
            case "/user/captcha" -> "captcha";
            case "/picture/upload" -> "upload";
            case "/interaction/add", "/interaction/remove" -> "interaction";
            case "/picture/batch/edit", "/picture/batch/delete" -> "pictureBatch";
            default -> "other";
        };
    }

    private String uploadKey(HttpServletRequest request) {
        return userKey(request, "upload");
    }

    /**
     * 优先按登录用户限流，未登录（或会话尚未建立）时按 IP 兜底
     */
    private String userKey(HttpServletRequest request, String prefix) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object userId = session.getAttribute(USER_LOGIN_ID);
            if (userId instanceof Long id) {
                return prefix + ":user:" + id;
            }
        }
        return prefix + ":ip:" + clientIp(request);
    }

    private String relativePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StrUtil.isNotBlank(contextPath) && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    private String clientIp(HttpServletRequest request) {
        // 只使用容器解析后的远程地址，绝不直接信任客户端可伪造的 X-Forwarded-For。
        // 生产环境通过 server.forward-headers-strategy=native 由 Tomcat RemoteIpValve
        // 仅在请求来自受信代理时解析转发头；nginx 也会用真实 remote_addr 覆盖该头。
        String remoteAddr = request.getRemoteAddr();
        return StrUtil.isBlank(remoteAddr) ? "unknown" : remoteAddr;
    }

    private void writeError(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format(
                "{\"code\":%d,\"data\":null,\"message\":\"%s\"}", code, message));
    }
}
