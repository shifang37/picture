package com.tzy.picturebackend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;

/**
 * CSRF 防护：使用 double-submit cookie 方案。
 * <p>
 * 安全方法（GET/HEAD/OPTIONS/TRACE）会补发一个非 HttpOnly 的 XSRF-TOKEN cookie；
 * 写操作必须同时携带同名 cookie 和 X-XSRF-TOKEN 请求头，两者一致才放行。
 * 前端 axios 默认会自动从 cookie 读取 token 并写入请求头。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CsrfFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "XSRF-TOKEN";

    public static final String HEADER_NAME = "X-XSRF-TOKEN";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private static final int COOKIE_MAX_AGE_SECONDS = 7 * 24 * 60 * 60;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = readToken(request);
        if (token == null || token.isBlank()) {
            token = newToken();
        }
        if (SAFE_METHODS.contains(request.getMethod())) {
            writeTokenCookie(request, response, token);
            filterChain.doFilter(request, response);
            return;
        }
        String headerToken = request.getHeader(HEADER_NAME);
        if (!tokenMatches(token, headerToken)) {
            writeForbidden(response);
            return;
        }
        // 写操作通过校验后也补发 cookie，避免 token 过期后页面无法继续操作
        writeTokenCookie(request, response, token);
        filterChain.doFilter(request, response);
    }

    private String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private boolean tokenMatches(String cookieToken, String headerToken) {
        if (cookieToken == null || headerToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                cookieToken.getBytes(StandardCharsets.UTF_8),
                headerToken.getBytes(StandardCharsets.UTF_8));
    }

    private void writeTokenCookie(HttpServletRequest request, HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setPath("/");
        // 必须允许前端 JS 读取，double-submit 方案依赖它写入请求头
        cookie.setHttpOnly(false);
        cookie.setSecure(request.isSecure());
        cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":40300,\"data\":null,\"message\":\"CSRF 校验失败，请刷新页面后重试\"}");
    }

    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
