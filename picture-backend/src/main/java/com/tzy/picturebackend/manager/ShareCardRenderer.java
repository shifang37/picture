package com.tzy.picturebackend.manager;

import cn.hutool.core.util.StrUtil;
import com.tzy.picturebackend.model.entity.Picture;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * 分享卡片 HTML 渲染器。
 * <p>
 * 单页应用无法让爬虫执行 JS，因此爬虫访问 {@code /picture/{id}} 时返回这份最小 HTML：
 * 只包含 OG / Twitter Card 元信息与一个指向详情页的链接，不渲染整站。
 * 所有来自用户的内容（名称、简介）都必须转义，避免图片名称里的 {@code <} 破坏页面或注入脚本。
 * <p>
 * 注意：统一使用 {@link HtmlUtils#htmlEscape}，它会把 Latin-1 区间的标点也转成实体
 * （例如 {@code ·} → {@code &middot;}、{@code …} → {@code &hellip;}）。这是合法 HTML，
 * 浏览器与社交平台爬虫都会按实体解码，不要为了「看起来更干净」改成手写白名单转义。
 */
@Component
public class ShareCardRenderer {

    /**
     * 站点名，出现在 og:site_name 与页面标题后缀
     */
    public static final String SITE_NAME = "云图库";

    /**
     * 标题最多保留的字符数：社交平台的卡片标题普遍会截断，提前裁掉避免出现半个字
     */
    static final int MAX_TITLE_LENGTH = 70;

    /**
     * 简介最多保留的字符数
     */
    static final int MAX_DESCRIPTION_LENGTH = 200;

    /**
     * 渲染图片分享页。
     *
     * @param picture    已通过审核的图片
     * @param pageUrl    详情页地址（og:url / canonical / 跳转链接）
     * @param imageWidth og:image 缩略图长边
     * @return 完整 HTML
     */
    public String renderPicture(Picture picture, String pageUrl, int imageWidth) {
        String name = StrUtil.blankToDefault(StrUtil.trim(picture.getName()), "未命名图片");
        String title = truncate(name, MAX_TITLE_LENGTH) + " - " + SITE_NAME;
        String description = StrUtil.isBlank(picture.getIntroduction())
                ? "「" + truncate(name, MAX_TITLE_LENGTH) + "」· 来自" + SITE_NAME + "的图片分享"
                : truncate(collapseWhitespace(picture.getIntroduction()), MAX_DESCRIPTION_LENGTH);
        String imageUrl = thumbnailUrl(picture.getUrl(), imageWidth);

        StringBuilder html = new StringBuilder(1024);
        html.append("<!doctype html>\n")
                .append("<html lang=\"zh-CN\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(escape(title)).append("</title>\n")
                .append("<meta name=\"description\" content=\"").append(escape(description)).append("\">\n");
        if (StrUtil.isNotBlank(pageUrl)) {
            html.append("<link rel=\"canonical\" href=\"").append(escape(pageUrl)).append("\">\n");
        }
        html.append("<meta property=\"og:type\" content=\"article\">\n")
                .append("<meta property=\"og:site_name\" content=\"").append(escape(SITE_NAME)).append("\">\n")
                .append("<meta property=\"og:title\" content=\"").append(escape(title)).append("\">\n")
                .append("<meta property=\"og:description\" content=\"").append(escape(description)).append("\">\n");
        if (StrUtil.isNotBlank(pageUrl)) {
            html.append("<meta property=\"og:url\" content=\"").append(escape(pageUrl)).append("\">\n");
        }
        if (StrUtil.isNotBlank(imageUrl)) {
            html.append("<meta property=\"og:image\" content=\"").append(escape(imageUrl)).append("\">\n")
                    .append("<meta property=\"og:image:width\" content=\"").append(imageWidth).append("\">\n");
        }
        // 微信 / Twitter 抓取时不带 Referer，封面必须能被匿名访问（见 COS 防盗链 allow-empty-referer）
        html.append("<meta name=\"twitter:card\" content=\"summary_large_image\">\n")
                .append("<meta name=\"twitter:title\" content=\"").append(escape(title)).append("\">\n")
                .append("<meta name=\"twitter:description\" content=\"").append(escape(description)).append("\">\n");
        if (StrUtil.isNotBlank(imageUrl)) {
            html.append("<meta name=\"twitter:image\" content=\"").append(escape(imageUrl)).append("\">\n")
                    .append("<meta name=\"twitter:image:alt\" content=\"")
                    .append(escape(truncate(name, MAX_TITLE_LENGTH))).append("\">\n");
        }
        html.append("</head>\n<body>\n")
                .append("<h1>").append(escape(title)).append("</h1>\n")
                .append("<p>").append(escape(description)).append("</p>\n");
        if (StrUtil.isNotBlank(pageUrl)) {
            html.append("<p><a href=\"").append(escape(pageUrl)).append("\">查看图片详情</a></p>\n");
        }
        html.append("</body>\n</html>\n");
        return html.toString();
    }

    /**
     * 渲染「图片不存在 / 未过审」页：不包含任何图片信息，避免未过审内容通过分享链接泄露。
     *
     * @param homeUrl 站点首页地址
     * @return 完整 HTML
     */
    public String renderNotFound(String homeUrl) {
        StringBuilder html = new StringBuilder(512);
        html.append("<!doctype html>\n")
                .append("<html lang=\"zh-CN\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"robots\" content=\"noindex\">\n")
                .append("<title>图片不存在 - ").append(escape(SITE_NAME)).append("</title>\n")
                .append("</head>\n<body>\n")
                .append("<p>图片不存在或已不可访问</p>\n");
        if (StrUtil.isNotBlank(homeUrl)) {
            html.append("<p><a href=\"").append(escape(homeUrl)).append("\">返回首页</a></p>\n");
        }
        html.append("</body>\n</html>\n");
        return html.toString();
    }

    /**
     * 生成 og:image 地址：复用 COS 数据万象的缩略图参数。
     * <p>
     * 这里固定输出 JPEG 而不是 WebP：微信等平台的抓取器对 WebP 支持不稳定，
     * 分享卡片会变成空白封面。
     *
     * @param url   原图地址
     * @param width 缩略图长边
     * @return 缩略图地址；原图地址为空时返回空串
     */
    public String thumbnailUrl(String url, int width) {
        if (StrUtil.isBlank(url)) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.contains("imageMogr2")) {
            return trimmed;
        }
        int size = width > 0 ? width : 1200;
        String separator = trimmed.contains("?") ? "&" : "?";
        return trimmed + separator + "imageMogr2/thumbnail/" + size + "x/format/jpeg/quality/85";
    }

    /**
     * 按 Unicode 码点截断，避免把 emoji 的代理对切成半个字符
     */
    private String truncate(String text, int maxCodePoints) {
        if (text == null) {
            return "";
        }
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        int endIndex = text.offsetByCodePoints(0, maxCodePoints);
        return text.substring(0, endIndex) + "…";
    }

    /**
     * 把换行 / 连续空白压成单个空格：meta 描述里的换行在社交平台上会显示成乱码
     */
    private String collapseWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
