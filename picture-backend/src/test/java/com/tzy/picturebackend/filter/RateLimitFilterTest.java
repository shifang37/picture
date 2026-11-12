package com.tzy.picturebackend.filter;

import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.manager.InMemoryRateLimitCounter;
import com.tzy.picturebackend.manager.RateLimitManager;
import com.tzy.picturebackend.metrics.PictureMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.web.http.SessionRepositoryFilter;
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

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        rateLimitManager = new RateLimitManager(new InMemoryRateLimitCounter());
        rateLimitFilter = new RateLimitFilter();
        meterRegistry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(rateLimitFilter, "rateLimitManager", rateLimitManager);
        ReflectionTestUtils.setField(rateLimitFilter, "pictureMetrics", new PictureMetrics(meterRegistry));
    }

    @Test
    @DisplayName("过滤器顺序：必须晚于 Spring Session，否则上传限流会退化为按 IP")
    void order_isAfterSpringSessionFilter() {
        Order order = RateLimitFilter.class.getAnnotation(Order.class);
        assertNotNull(order, "RateLimitFilter 必须显式声明 @Order");
        assertTrue(order.value() > SessionRepositoryFilter.DEFAULT_ORDER,
                "RateLimitFilter 必须排在 SessionRepositoryFilter 之后，实际 order=" + order.value());
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
        assertEquals(1.0, meterRegistry.get(PictureMetrics.RATE_LIMITED)
                .tag("type", "login").counter().count());
    }

    @Test
    @DisplayName("上传：同一用户超过 60 次/分钟返回 42900")
    void upload_blocksAfterLimit() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.USER_LOGIN_ID, 100L);

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
        assertEquals(1.0, meterRegistry.get(PictureMetrics.RATE_LIMITED)
                .tag("type", "upload").counter().count());
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
    @DisplayName("注册：同一 IP 10 分钟内超过 10 次返回 42900")
    void register_blocksAfterIpLimit() throws Exception {
        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        for (int i = 0; i < 11; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/register");
            request.setContextPath("/api");
            request.setRemoteAddr("10.0.0.3");
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            rateLimitFilter.doFilter(request, lastResponse, lastChain);
        }

        assertEquals(200, lastResponse.getStatus());
        assertTrue(lastResponse.getContentAsString().contains("\"code\":42900"));
        assertNull(lastChain.getRequest(), "超过限流后不应继续执行");
        assertEquals(1.0, meterRegistry.get(PictureMetrics.RATE_LIMITED)
                .tag("type", "register").counter().count());
    }

    @Test
    @DisplayName("验证码：同一 IP 每分钟超过 30 次返回 42900")
    void captcha_blocksAfterIpLimit() throws Exception {
        MockHttpServletResponse lastResponse = null;
        MockFilterChain lastChain = null;
        for (int i = 0; i < 31; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/user/captcha");
            request.setContextPath("/api");
            request.setRemoteAddr("10.0.0.4");
            lastResponse = new MockHttpServletResponse();
            lastChain = new MockFilterChain();
            rateLimitFilter.doFilter(request, lastResponse, lastChain);
        }

        assertTrue(lastResponse.getContentAsString().contains("\"code\":42900"));
        assertNull(lastChain.getRequest(), "超过限流后不应继续执行");
        assertEquals(1.0, meterRegistry.get(PictureMetrics.RATE_LIMITED)
                .tag("type", "captcha").counter().count());
    }

    @Test
    @DisplayName("注册：未达到 IP 限制的正常请求继续放行")
    void register_passesUnderLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/register");
        request.setContextPath("/api");
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        rateLimitFilter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
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
