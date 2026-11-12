package com.tzy.picturebackend.filter;

import com.tzy.picturebackend.constant.UserConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首次登录强制改密过滤器测试
 */
class PasswordChangeRequiredFilterTest {

    private PasswordChangeRequiredFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PasswordChangeRequiredFilter();
    }

    @Test
    @DisplayName("未改密：除白名单外的接口返回 40301")
    void blocksBusinessApiWhenPasswordChangeRequired() throws Exception {
        MockHttpServletRequest request = request("/api/picture/list/page/vo", true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":40301"));
        assertNull(chain.getRequest(), "未改密时不应继续执行业务接口");
    }

    @Test
    @DisplayName("未改密：改密、登出、读取登录态接口放行")
    void allowsWhitelistedPaths() throws Exception {
        for (String path : new String[]{"/api/user/update/password", "/api/user/logout",
                "/api/user/get/login"}) {
            MockHttpServletRequest request = request(path, true);
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertNotNull(chain.getRequest(), "白名单接口应放行: " + path);
        }
    }

    @Test
    @DisplayName("已改密或未登录的会话不拦截")
    void passesWhenFlagMissing() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("/api/picture/list/page/vo", false),
                new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());

        MockHttpServletRequest anonymous = request("/api/picture/list/page/vo", true);
        anonymous.getSession(true).removeAttribute(UserConstant.USER_LOGIN_ID);
        MockFilterChain anonymousChain = new MockFilterChain();
        filter.doFilter(anonymous, new MockHttpServletResponse(), anonymousChain);
        assertNotNull(anonymousChain.getRequest());
    }

    @Test
    @DisplayName("跨域预检请求直接放行")
    void allowsOptionsRequest() throws Exception {
        MockHttpServletRequest request = request("/api/picture/list/page/vo", true);
        request.setMethod("OPTIONS");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
    }

    @Test
    @DisplayName("过滤器顺序：晚于限流过滤器")
    void order_isAfterRateLimitFilter() {
        Order order = PasswordChangeRequiredFilter.class.getAnnotation(Order.class);
        Order rateLimitOrder = RateLimitFilter.class.getAnnotation(Order.class);
        assertNotNull(order);
        assertTrue(order.value() > rateLimitOrder.value(),
                "强制改密过滤应排在限流之后，实际 order=" + order.value());
    }

    private MockHttpServletRequest request(String path, boolean passwordChangeRequired) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContextPath("/api");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(UserConstant.USER_LOGIN_ID, 1L);
        if (passwordChangeRequired) {
            session.setAttribute(UserConstant.USER_PASSWORD_CHANGE_REQUIRED, true);
        }
        request.setSession(session);
        return request;
    }
}
