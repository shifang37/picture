package com.tzy.picturebackend.manager;

import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.model.ObjectMetadata;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.UserService;
import org.apache.http.client.methods.HttpRequestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下载代理测试：文件完整返回后才计数，重复下载不计数，COS 异常返回业务错误
 */
class PictureDownloadManagerTest {

    private static final String OBJECT_KEY = "public/100/sunset.jpg";

    private static final String PICTURE_URL = "https://test.cos.ap-beijing.myqcloud.com/" + OBJECT_KEY;

    private PictureService pictureService;

    private CosManager cosManager;

    private DownloadDedupManager downloadDedupManager;

    private UserService userService;

    private PictureDownloadManager downloadManager;

    private MockHttpServletRequest request;

    private MockHttpServletResponse response;

    private Picture picture;

    @BeforeEach
    void setUp() {
        pictureService = mock(PictureService.class);
        cosManager = mock(CosManager.class);
        downloadDedupManager = mock(DownloadDedupManager.class);
        userService = mock(UserService.class);
        downloadManager = new PictureDownloadManager(pictureService, cosManager, downloadDedupManager, userService);
        request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        response = new MockHttpServletResponse();
        picture = new Picture();
        picture.setId(1L);
        picture.setName("sunset");
        picture.setUrl(PICTURE_URL);
        picture.setPicFormat("jpg");
        when(pictureService.getDownloadablePicture(1L, request)).thenReturn(picture);
        when(cosManager.parseObjectKey(PICTURE_URL)).thenReturn(OBJECT_KEY);
    }

    @Test
    @DisplayName("下载：流式返回原图，并在完整写完后自增一次下载次数")
    void download_streamsFileAndCountsOnce() throws Exception {
        byte[] bytes = "fake-image-bytes".getBytes(StandardCharsets.UTF_8);
        when(cosManager.getObject(OBJECT_KEY)).thenReturn(cosObject(bytes, "image/jpeg"));
        when(downloadDedupManager.markIfFirst("ip:127.0.0.1", 1L)).thenReturn(true);
        when(pictureService.incrementDownloadCount(1L)).thenReturn(true);

        downloadManager.download(1L, request, response);

        assertArrayEquals(bytes, response.getContentAsByteArray());
        assertEquals("image/jpeg", response.getContentType());
        assertEquals(bytes.length, response.getContentLength());
        assertEquals("no-store, no-cache, must-revalidate", response.getHeader(HttpHeaders.CACHE_CONTROL));
        String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment"));
        assertTrue(disposition.contains("sunset.jpg"));
        verify(downloadDedupManager).markIfFirst("ip:127.0.0.1", 1L);
        verify(pictureService).incrementDownloadCount(1L);
    }

    @Test
    @DisplayName("下载：当天重复下载仍然返回文件，但不重复计数")
    void download_skipsCountWhenDuplicate() throws Exception {
        byte[] bytes = "fake-image-bytes".getBytes(StandardCharsets.UTF_8);
        when(cosManager.getObject(OBJECT_KEY)).thenReturn(cosObject(bytes, "image/jpeg"));
        when(downloadDedupManager.markIfFirst("ip:127.0.0.1", 1L)).thenReturn(false);

        downloadManager.download(1L, request, response);

        assertArrayEquals(bytes, response.getContentAsByteArray());
        verify(pictureService, never()).incrementDownloadCount(any());
    }

    @Test
    @DisplayName("下载：COS 对象不存在时返回 40400 业务错误")
    void download_mapsMissingObjectToNotFound() {
        CosServiceException exception = new CosServiceException("not found");
        exception.setStatusCode(404);
        when(cosManager.getObject(OBJECT_KEY)).thenThrow(exception);

        BusinessException e = assertThrows(BusinessException.class,
                () -> downloadManager.download(1L, request, response));

        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), e.getCode());
        verifyNoInteractions(downloadDedupManager);
    }

    @Test
    @DisplayName("下载：图片地址不在当前 COS 桶时拒绝代理")
    void download_rejectsForeignUrl() {
        when(cosManager.parseObjectKey(PICTURE_URL)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> downloadManager.download(1L, request, response));

        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), e.getCode());
        verify(cosManager, never()).getObject(anyString());
    }

    @Test
    @DisplayName("文件名：无后缀时补 picFormat，非法字符替换为下划线")
    void resolveFileName_sanitizesAndAddsExtension() {
        picture.setName("a/b:c?d");

        assertEquals("a_b_c_d.jpg", downloadManager.resolveFileName(picture, "image/jpeg"));
    }

    @Test
    @DisplayName("文件名：名称已有后缀时不重复追加")
    void resolveFileName_keepsExistingExtension() {
        picture.setName("sunset.png");

        assertEquals("sunset.png", downloadManager.resolveFileName(picture, "image/png"));
    }

    private COSObject cosObject(byte[] bytes, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType);
        metadata.setContentLength(bytes.length);
        COSObject cosObject = new COSObject();
        cosObject.setObjectMetadata(metadata);
        // COSObjectInputStream 关闭时会释放底层 HTTP 请求，这里传入 mock 避免空指针
        cosObject.setObjectContent(new COSObjectInputStream(
                new ByteArrayInputStream(bytes), mock(HttpRequestBase.class)));
        return cosObject;
    }
}
