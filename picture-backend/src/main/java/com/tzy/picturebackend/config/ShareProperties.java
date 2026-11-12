package com.tzy.picturebackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图片分享卡片配置。
 * <p>
 * 爬虫访问 {@code /picture/{id}} 时由 nginx 分流到 {@code GET /api/share/picture/{id}}，
 * 后端返回带 OG / Twitter Card 标签的最小 HTML。{@code frontendBaseUrl} 用于拼 {@code og:url}
 * 与「查看详情」链接，必须是用户实际访问的站点地址（生产环境为正式域名）。
 */
@Component
@ConfigurationProperties(prefix = "app.share")
@Data
public class ShareProperties {

    /**
     * 站点根地址（不含结尾斜杠），用于拼 og:url 与详情页链接
     */
    private String frontendBaseUrl = "http://localhost:5173";

    /**
     * og:image 使用的缩略图长边；1200 是微信 / Twitter 大图卡片的推荐宽度
     */
    private int ogImageWidth = 1200;

    /**
     * 分享页 Cache-Control 的 max-age（秒）：图片名称 / 简介可能被作者修改，
     * 5 分钟延迟可接受，同时让爬虫频繁抓取不打到数据库
     */
    private int cacheSeconds = 300;
}
