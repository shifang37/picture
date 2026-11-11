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
 * 登录与上传接口限流，避免暴力破解和恶意刷上传。
 * <p>
 * 顺序必须晚于 Spring Session 的 SessionRepositoryFilter（Integer.MIN_VALUE + 50）：
 * 否则这里读到的还是 Tomcat 的空会话，上传限流会静悄悄地从「按用户」退化成「按 IP」。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 60)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int LOGIN_LIMIT_PER_MINUTE = 10;

    private static final int UPLOAD_LIMIT_PER_MINUTE = 60;

    @Resource
    private RateLimitManager rateLimitManager;

    @Resource
    private PictureMetrics pictureMetrics;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = relativePath(request);
        try {
            if ("/user/login".equals(path)) {
                rateLimitManager.check("login:ip:" + clientIp(request),
                        LOGIN_LIMIT_PER_MINUTE, Duration.ofMinutes(1));
            } else if ("/picture/upload".equals(path)) {
                rateLimitManager.check(uploadKey(request), UPLOAD_LIMIT_PER_MINUTE, Duration.ofMinutes(1));
            }
        } catch (BusinessException e) {
            // 限流响应同样是 HTTP 200，按业务码无法区分来源，这里单独计数
            pictureMetrics.rateLimited("/user/login".equals(path) ? "login" : "upload");
            writeError(response, e.getCode(), e.getMessage());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String uploadKey(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object userId = session.getAttribute(USER_LOGIN_ID);
            if (userId instanceof Long id) {
                return "upload:user:" + id;
            }
        }
        return "upload:ip:" + clientIp(request);
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
