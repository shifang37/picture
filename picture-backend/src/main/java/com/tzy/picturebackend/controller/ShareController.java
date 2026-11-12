package com.tzy.picturebackend.controller;

import cn.hutool.core.util.StrUtil;
import com.tzy.picturebackend.config.ShareProperties;
import com.tzy.picturebackend.manager.ShareCardRenderer;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.service.PictureService;
import jakarta.annotation.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Objects;

/**
 * 图片分享页（面向爬虫的 OG 卡片）。
 * <p>
 * nginx 按 UA 把 {@code /picture/{id}} 分流到这里：人类用户仍然拿到 SPA 的 index.html，
 * 由前端动态写 title / og:*；爬虫拿到这份带 meta 的最小 HTML，微信 / Twitter 才能生成卡片。
 * 只暴露已通过审核且未删除的图片，其余一律返回 404 页面，不泄露任何标题或简介。
 */
@RestController
@RequestMapping("share")
public class ShareController {

    private static final MediaType HTML_UTF8 = MediaType.parseMediaType("text/html;charset=UTF-8");

    @Resource
    private PictureService pictureService;

    @Resource
    private ShareCardRenderer shareCardRenderer;

    @Resource
    private ShareProperties shareProperties;

    @Resource
    private PictureMetrics pictureMetrics;

    /**
     * 分享卡片 HTML。
     *
     * @param id 图片 id
     * @return 已过审图片返回 200 + 带 OG 标签的 HTML；不存在 / 未过审 / 已删除返回 404 页面
     */
    @GetMapping(value = "/picture/{id}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> sharePicture(@PathVariable("id") Long id) {
        Picture picture = id == null || id <= 0 ? null : pictureService.getById(id);
        if (picture == null
                || !Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.PASS.getValue())) {
            pictureMetrics.share("not_found");
            return notFound();
        }
        String pageUrl = siteBaseUrl() + "/picture/" + id;
        String html = shareCardRenderer.renderPicture(picture, pageUrl, shareProperties.getOgImageWidth());
        pictureMetrics.share("rendered");
        return ResponseEntity.ok()
                .contentType(HTML_UTF8)
                .cacheControl(cacheControl())
                .body(html);
    }

    private ResponseEntity<String> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(HTML_UTF8)
                // 404 不缓存：图片刚过审时爬虫重试能立刻拿到内容
                .cacheControl(CacheControl.noStore())
                .body(shareCardRenderer.renderNotFound(siteBaseUrl()));
    }

    private CacheControl cacheControl() {
        int seconds = Math.max(0, shareProperties.getCacheSeconds());
        return CacheControl.maxAge(Duration.ofSeconds(seconds)).cachePublic();
    }

    /**
     * 站点根地址：去掉配置里可能多写的结尾斜杠，避免拼出 {@code //picture/1}
     */
    private String siteBaseUrl() {
        String baseUrl = StrUtil.trim(shareProperties.getFrontendBaseUrl());
        if (StrUtil.isBlank(baseUrl)) {
            return "";
        }
        return StrUtil.removeSuffix(baseUrl, "/");
    }
}
