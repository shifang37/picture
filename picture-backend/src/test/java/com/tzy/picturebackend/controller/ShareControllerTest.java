package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.config.ShareProperties;
import com.tzy.picturebackend.exception.GlobalExceptionHandler;
import com.tzy.picturebackend.manager.ShareCardRenderer;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.service.PictureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分享卡片接口：HTML 输出、缓存头、未过审 / 不存在时返回 404
 */
class ShareControllerTest {

    private static final String BASE_URL = "https://img.example.com";

    private MockMvc mockMvc;

    private PictureService pictureService;

    private PictureMetrics pictureMetrics;

    @BeforeEach
    void setUp() {
        pictureService = mock(PictureService.class);
        pictureMetrics = mock(PictureMetrics.class);

        ShareProperties shareProperties = new ShareProperties();
        shareProperties.setFrontendBaseUrl(BASE_URL + "/");
        shareProperties.setOgImageWidth(1200);
        shareProperties.setCacheSeconds(300);

        ShareController shareController = new ShareController();
        ReflectionTestUtils.setField(shareController, "pictureService", pictureService);
        ReflectionTestUtils.setField(shareController, "shareCardRenderer", new ShareCardRenderer());
        ReflectionTestUtils.setField(shareController, "shareProperties", shareProperties);
        ReflectionTestUtils.setField(shareController, "pictureMetrics", pictureMetrics);

        mockMvc = MockMvcBuilders.standaloneSetup(shareController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Picture passedPicture() {
        Picture picture = new Picture();
        picture.setId(1L);
        picture.setName("日落海边");
        picture.setIntroduction("夏天傍晚的海边");
        picture.setUrl("https://bucket.cos.ap-beijing.myqcloud.com/picture/1.jpg");
        picture.setReviewStatus(1);
        return picture;
    }

    @Test
    @DisplayName("已过审图片：返回 HTML 卡片、OG 标签与 5 分钟缓存")
    void sharePicture_returnsOgHtml() throws Exception {
        when(pictureService.getById(1L)).thenReturn(passedPicture());

        mockMvc.perform(get("/share/picture/1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/html")))
                // 结尾斜杠会被去掉，避免拼出 //picture/1
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(header().string("Cache-Control", containsString("public")))
                .andExpect(content().string(containsString(
                        "<meta property=\"og:title\" content=\"日落海边 - 云图库\">")))
                .andExpect(content().string(containsString(
                        "<meta property=\"og:url\" content=\"" + BASE_URL + "/picture/1\">")))
                .andExpect(content().string(containsString("查看图片详情")));

        verify(pictureMetrics).share("rendered");
    }

    @Test
    @DisplayName("图片不存在：返回 404 页面且不缓存")
    void sharePicture_returnsNotFoundWhenMissing() throws Exception {
        when(pictureService.getById(404L)).thenReturn(null);

        mockMvc.perform(get("/share/picture/404"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(containsString("图片不存在或已不可访问")))
                .andExpect(content().string(not(containsString("og:title"))));

        verify(pictureMetrics).share("not_found");
    }

    @Test
    @DisplayName("未过审 / 已拒绝的图片：返回 404，不泄露标题")
    void sharePicture_hidesUnreviewedPicture() throws Exception {
        Picture reviewing = passedPicture();
        reviewing.setReviewStatus(0);
        when(pictureService.getById(2L)).thenReturn(reviewing);

        mockMvc.perform(get("/share/picture/2"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("日落海边"))));

        verify(pictureMetrics).share("not_found");
    }

    @Test
    @DisplayName("非法 id：直接返回 404，不查库")
    void sharePicture_rejectsInvalidId() throws Exception {
        mockMvc.perform(get("/share/picture/0"))
                .andExpect(status().isNotFound());

        verify(pictureService, never()).getById(any());
        verify(pictureMetrics).share("not_found");
    }
}
