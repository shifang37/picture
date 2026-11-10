package com.tzy.picturebackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 允许跨域的来源白名单，逗号分隔。
     * <p>
     * 默认仅放行本地开发地址；生产环境通过配置覆盖：
     * app.cors.allowed-origin-patterns=https://your-domain.com
     * <p>
     * 注意：不能使用 "*"，否则与 allowCredentials(true) 冲突。
     */
    @Value("${app.cors.allowed-origin-patterns:http://localhost:5173,http://127.0.0.1:5173}")
    private String[] allowedOriginPatterns;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 覆盖所有请求
        registry.addMapping("/**")
                // 允许发送 Cookie
                .allowCredentials(true)
                // 仅放行白名单内的来源
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("*");
    }
}
