package com.tzy.picturebackend.filter;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CSRF double-submit cookie 过滤器测试
 */
class CsrfFilterTest {

    private CsrfFilter csrfFilter;

    @BeforeEach
    void setUp() {
        csrfFilter = new CsrfFilter();
    }

    @Test
    @DisplayName("安全方法：自动下发 XSRF-TOKEN cookie")
    void safeMethod_issuesTokenCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/picture/list/page/vo");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        csrfFilter.doFilter(request, response, chain);

        assertNotNull(response.getCookie(CsrfFilter.COOKIE_NAME));
        assertNotNull(chain.getRequest(), "安全方法应放行");
    }

    @Test
    @DisplayName("写操作：缺少 CSRF token 时拒绝")
    void unsafeMethod_rejectsMissingToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        csrfFilter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(true, response.getContentAsString().contains("\"code\":40300"));
        assertNull(chain.getRequest(), "缺少 token 不应放行");
    }

    @Test
    @DisplayName("写操作：cookie 与请求头一致时放行")
    void unsafeMethod_acceptsMatchingToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/picture/upload");
        request.setCookies(new Cookie(CsrfFilter.COOKIE_NAME, "abc123"));
        request.addHeader(CsrfFilter.HEADER_NAME, "abc123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        csrfFilter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "token 一致应放行");
    }

    @Test
    @DisplayName("写操作：cookie 与请求头不一致时拒绝")
    void unsafeMethod_rejectsMismatchedToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/picture/delete");
        request.setCookies(new Cookie(CsrfFilter.COOKIE_NAME, "abc123"));
        request.addHeader(CsrfFilter.HEADER_NAME, "other");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        csrfFilter.doFilter(request, response, chain);

        assertEquals(true, response.getContentAsString().contains("\"code\":40300"));
        assertNull(chain.getRequest(), "token 不一致不应放行");
    }
}
