package com.tzy.picturebackend.filter;

import cn.hutool.core.util.StrUtil;
import com.tzy.picturebackend.constant.UserConstant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 首次登录强制改密：登录态携带 {@code USER_PASSWORD_CHANGE_REQUIRED=true} 时，
 * 除改密、登出、读取当前登录态之外的所有接口都返回 40301。
 * <p>
 * 之所以放在过滤器而不是 Controller：用户可能绕过前端直接调用任意业务接口，
 * 只有服务端统一拦截才能保证「未改密前不能用系统」。
 * 顺序晚于 {@code RateLimitFilter}，保证超限请求优先得到 42900。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 70)
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    /**
     * 未改密时仍允许访问的接口：改密、登出、读取登录态（前端据此跳转）
     */
    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/user/update/password", "/user/logout", "/user/get/login");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 跨域预检不带业务语义，直接放行，避免浏览器的 OPTIONS 也被拦截
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        HttpSession session = request.getSession(false);
        if (session == null
                || !(session.getAttribute(UserConstant.USER_LOGIN_ID) instanceof Long)
                || !Boolean.TRUE.equals(session.getAttribute(
                        UserConstant.USER_PASSWORD_CHANGE_REQUIRED))) {
            filterChain.doFilter(request, response);
            return;
        }
        if (ALLOWED_PATHS.contains(relativePath(request))) {
            filterChain.doFilter(request, response);
            return;
        }
        writeError(response);
    }

    private String relativePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StrUtil.isNotBlank(contextPath) && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    private void writeError(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":40301,\"data\":null,\"message\":\"首次登录请先修改初始密码\"}");
    }
}
