package com.tzy.picturebackend.filter;

import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.manager.RateLimitManager;
import com.tzy.picturebackend.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录 / 上传限流过滤器测试
 */
class RateLimitFilterTest {

    private RateLimitManager rateLimitManager;

    private RateLimitFilter rateLimitFilter;

    @BeforeEach
    void setUp() {
        rateLimitManager = new RateLimitManager();
        rateLimitFilter = new RateLimitFilter();
        ReflectionTestUtils.setField(rateLimitFilter, "rateLimitManager", rateLimitManager);
    }

    @Test
    @DisplayName("登录：同一 IP 超过 10 次/分钟后返回 42900")
    void login_blocksAfterLimit() throws Exception {
        MockHttpServletRequest lastRequest = null;
        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;

        for (int i = 0; i < 11; i++) {
            lastRequest = new MockHttpServletRequest("POST", "/api/user/login");
            lastRequest.setContextPath("/api");
            lastRequest.setRemoteAddr("10.0.0.1");
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            rateLimitFilter.doFilter(lastRequest, lastResponse, lastChain);
        }

        assertEquals(200, lastResponse.getStatus());
        assertTrue(lastResponse.getContentAsString().contains("\"code\":42900"));
        assertNull(lastChain.getRequest(), "超过限流后不应继续执行");
    }

    @Test
    @DisplayName("上传：同一用户超过 60 次/分钟返回 42900")
    void upload_blocksAfterLimit() throws Exception {
        MockHttpSession session = new MockHttpSession();
        User user = new User();
        user.setId(100L);
        session.setAttribute(UserConstant.USER_LOGIN_STATE, user);

        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        for (int i = 0; i < 61; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/picture/upload");
            request.setContextPath("/api");
            request.setSession(session);
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            rateLimitFilter.doFilter(request, lastResponse, lastChain);
        }

        assertTrue(lastResponse.getContentAsString().contains("\"code\":42900"));
        assertNull(lastChain.getRequest(), "超过限流后不应继续执行");
    }

    @Test
    @DisplayName("登录：伪造 X-Forwarded-For 不能绕过同一来源 IP 的限流")
    void login_ignoresSpoofedForwardedHeader() throws Exception {
        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        for (int i = 0; i < 11; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/login");
            request.setContextPath("/api");
            request.setRemoteAddr("10.0.0.9");
            // 每次都伪造不同的转发头，限流仍应按真实 remoteAddr 计数
            request.addHeader("X-Forwarded-For", "203.0.113." + i);
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            rateLimitFilter.doFilter(request, lastResponse, lastChain);
        }

        assertTrue(lastResponse.getContentAsString().contains("\"code\":42900"));
        assertNull(lastChain.getRequest(), "超过限流后不应继续执行");
    }

    @Test
    @DisplayName("限流：未达到限制的正常请求继续放行")
    void normalRequest_passes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/login");
        request.setContextPath("/api");
        request.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        rateLimitFilter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
    }
}
