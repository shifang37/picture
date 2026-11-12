package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.model.entity.Picture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分享卡片渲染：OG 标签、HTML 转义、截断与缩略图地址
 */
class ShareCardRendererTest {

    private final ShareCardRenderer renderer = new ShareCardRenderer();

    private Picture picture(String name, String introduction) {
        Picture picture = new Picture();
        picture.setId(1L);
        picture.setName(name);
        picture.setIntroduction(introduction);
        picture.setUrl("https://bucket.cos.ap-beijing.myqcloud.com/picture/1.jpg");
        picture.setReviewStatus(1);
        return picture;
    }

    @Test
    @DisplayName("渲染：包含 OG / Twitter Card 与 canonical 标签")
    void renderPicture_containsOgTags() {
        String html = renderer.renderPicture(picture("日落海边", "夏天傍晚的海边"), "https://img.example.com/picture/1", 1200);

        assertTrue(html.contains("<meta property=\"og:type\" content=\"article\">"));
        assertTrue(html.contains("<meta property=\"og:title\" content=\"日落海边 - 云图库\">"));
        assertTrue(html.contains("<meta property=\"og:description\" content=\"夏天傍晚的海边\">"));
        assertTrue(html.contains("<meta property=\"og:url\" content=\"https://img.example.com/picture/1\">"));
        assertTrue(html.contains("<link rel=\"canonical\" href=\"https://img.example.com/picture/1\">"));
        assertTrue(html.contains("<meta name=\"twitter:card\" content=\"summary_large_image\">"));
        assertTrue(html.contains(
                "<meta property=\"og:image\" content=\"https://bucket.cos.ap-beijing.myqcloud.com/picture/1.jpg"
                        + "?imageMogr2/thumbnail/1200x/format/jpeg/quality/85\">"));
        assertTrue(html.contains("<meta property=\"og:image:width\" content=\"1200\">"));
        assertTrue(html.contains("<a href=\"https://img.example.com/picture/1\">查看图片详情</a>"));
    }

    @Test
    @DisplayName("渲染：用户输入被转义，不能注入标签")
    void renderPicture_escapesUserContent() {
        String html = renderer.renderPicture(
                picture("<script>alert(\"x\")</script>", "简介 <img src=x onerror=alert(1)>"),
                "https://img.example.com/picture/1", 800);

        assertFalse(html.contains("<script>alert"));
        assertFalse(html.contains("<img src=x"));
        assertTrue(html.contains("&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;"));
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"));
    }

    @Test
    @DisplayName("渲染：简介为空时给出默认描述，换行压成空格")
    void renderPicture_fallsBackToDefaultDescription() {
        String blank = renderer.renderPicture(picture("星空", "  "), "https://img.example.com/picture/1", 1200);
        // HtmlUtils.htmlEscape 会把 Latin-1 标点转成实体（· → &middot;），浏览器与爬虫都会正确解码
        assertTrue(blank.contains("「星空」&middot; 来自云图库的图片分享"));

        String multiline = renderer.renderPicture(
                picture("星空", "第一行\n第二行\r\n第三行"), "https://img.example.com/picture/1", 1200);
        assertTrue(multiline.contains("content=\"第一行 第二行 第三行\""));
    }

    @Test
    @DisplayName("渲染：名称缺失时用「未命名图片」，超长名称被截断")
    void renderPicture_handlesMissingAndLongName() {
        String unnamed = renderer.renderPicture(picture("   ", null), "", 1200);
        assertTrue(unnamed.contains("<title>未命名图片 - 云图库</title>"));
        // 没有详情页地址时不输出 og:url / canonical，但其它标签仍然齐全
        assertFalse(unnamed.contains("og:url"));
        assertFalse(unnamed.contains("rel=\"canonical\""));

        String longName = "图".repeat(80);
        String html = renderer.renderPicture(picture(longName, null), "", 1200);
        // 省略号同样被转义成 &hellip;
        assertTrue(html.contains("图".repeat(ShareCardRenderer.MAX_TITLE_LENGTH) + "&hellip;"));
        assertFalse(html.contains("图".repeat(ShareCardRenderer.MAX_TITLE_LENGTH + 1)));
    }

    @Test
    @DisplayName("缩略图：区分是否已有查询参数，空地址返回空串")
    void thumbnailUrl_buildsImageMogr2() {
        assertTrue(renderer.thumbnailUrl("https://cos.example.com/a.jpg", 1200)
                .equals("https://cos.example.com/a.jpg?imageMogr2/thumbnail/1200x/format/jpeg/quality/85"));
        assertTrue(renderer.thumbnailUrl("https://cos.example.com/a.jpg?x=1", 600)
                .equals("https://cos.example.com/a.jpg?x=1&imageMogr2/thumbnail/600x/format/jpeg/quality/85"));
        // 已带图片处理参数时不重复拼接
        assertTrue(renderer.thumbnailUrl("https://cos.example.com/a.jpg?imageMogr2/thumbnail/300x", 1200)
                .equals("https://cos.example.com/a.jpg?imageMogr2/thumbnail/300x"));
        assertTrue(renderer.thumbnailUrl("", 1200).isEmpty());
        assertTrue(renderer.thumbnailUrl(null, 1200).isEmpty());
        // 非法宽度回落到 1200
        assertTrue(renderer.thumbnailUrl("https://cos.example.com/a.jpg", 0)
                .contains("thumbnail/1200x"));
    }

    @Test
    @DisplayName("404 页：noindex，且不含任何图片信息")
    void renderNotFound_hidesPictureInfo() {
        String html = renderer.renderNotFound("https://img.example.com");

        assertTrue(html.contains("<meta name=\"robots\" content=\"noindex\">"));
        assertTrue(html.contains("图片不存在或已不可访问"));
        assertTrue(html.contains("<a href=\"https://img.example.com\">返回首页</a>"));
        assertFalse(html.contains("og:"));
    }
}
