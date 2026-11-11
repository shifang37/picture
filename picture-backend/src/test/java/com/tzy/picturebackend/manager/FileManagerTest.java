package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FileManager 图片校验测试：普通用户也能上传，因此上传入口需要挡住非图片和超大文件
 */
class FileManagerTest {

    private static final byte[] JPEG_HEADER = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01};

    private static final byte[] PNG_HEADER = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};

    private static final byte[] WEBP_HEADER = new byte[]{
            0x52, 0x49, 0x46, 0x46, 0x24, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50};

    private final FileManager fileManager = new FileManager();

    @Test
    @DisplayName("校验：合法的 jpg / png / webp 可以通过")
    void validPicture_acceptsSupportedImages() {
        assertDoesNotThrow(() -> fileManager.validPicture(file("a.jpg", "image/jpeg", JPEG_HEADER)));
        assertDoesNotThrow(() -> fileManager.validPicture(file("a.png", "image/png", PNG_HEADER)));
        assertDoesNotThrow(() -> fileManager.validPicture(file("a.webp", "image/webp", WEBP_HEADER)));
    }

    @Test
    @DisplayName("校验：后缀大小写不敏感")
    void validPicture_acceptsUppercaseSuffix() {
        assertDoesNotThrow(() -> fileManager.validPicture(file("A.JPG", "image/jpeg", JPEG_HEADER)));
    }

    @Test
    @DisplayName("校验：空文件应拒绝")
    void validPicture_rejectsEmptyFile() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> fileManager.validPicture(file("a.jpg", "image/jpeg", new byte[0])));
        assertEquals("文件不能为空", e.getMessage());
    }

    @Test
    @DisplayName("校验：超过 2MB 应拒绝")
    void validPicture_rejectsOversizedFile() {
        byte[] bytes = new byte[(2 * 1024 * 1024) + 1];
        System.arraycopy(JPEG_HEADER, 0, bytes, 0, JPEG_HEADER.length);

        BusinessException e = assertThrows(BusinessException.class,
                () -> fileManager.validPicture(file("big.jpg", "image/jpeg", bytes)));
        assertEquals("文件大小不能超过 2M", e.getMessage());
    }

    @Test
    @DisplayName("校验：不支持的后缀应拒绝")
    void validPicture_rejectsUnsupportedSuffix() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> fileManager.validPicture(file("a.gif", "image/gif", JPEG_HEADER)));
        assertEquals("文件类型错误", e.getMessage());
    }

    @Test
    @DisplayName("校验：改后缀绕过无效，文件头必须匹配")
    void validPicture_rejectsSpoofedSuffix() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> fileManager.validPicture(file("fake.jpg", "image/jpeg", PNG_HEADER)));
        assertEquals("文件内容不是受支持的图片", e.getMessage());
    }

    @Test
    @DisplayName("校验：MIME 类型不在白名单应拒绝")
    void validPicture_rejectsUnsupportedContentType() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> fileManager.validPicture(file("a.jpg", "text/plain", JPEG_HEADER)));
        assertEquals("文件类型错误", e.getMessage());
    }

    @Test
    @DisplayName("校验：客户端未提供 MIME 类型时按文件头判断")
    void validPicture_allowsMissingContentType() {
        assertDoesNotThrow(() -> fileManager.validPicture(file("a.jpg", null, JPEG_HEADER)));
    }

    @Test
    @DisplayName("校验：带 charset 参数的 MIME 类型可以识别")
    void validPicture_acceptsContentTypeWithParameters() {
        assertDoesNotThrow(() -> fileManager.validPicture(file("a.png", "image/png; charset=binary", PNG_HEADER)));
    }

    // ==================== 删除 COS 对象 ====================

    @Test
    @DisplayName("删除文件：本桶地址解析出 key 后调用 COS 删除")
    void deletePicture_deletesOwnObject() {
        FileManager manager = new FileManager();
        CosManager cosManager = mock(CosManager.class);
        ReflectionTestUtils.setField(manager, "cosManager", cosManager);
        String url = "https://example.cos.myqcloud.com/public/1/a.jpg";
        when(cosManager.parseObjectKey(url)).thenReturn("public/1/a.jpg");

        assertTrue(manager.deletePicture(url));
        verify(cosManager).deleteObject("public/1/a.jpg");
    }

    @Test
    @DisplayName("删除文件：非本桶地址跳过删除且不抛异常")
    void deletePicture_skipsForeignUrl() {
        FileManager manager = new FileManager();
        CosManager cosManager = mock(CosManager.class);
        ReflectionTestUtils.setField(manager, "cosManager", cosManager);
        String url = "https://other.com/a.jpg";
        when(cosManager.parseObjectKey(url)).thenReturn(null);

        assertFalse(manager.deletePicture(url));
        verify(cosManager, never()).deleteObject(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("删除文件：空地址跳过删除且不抛异常")
    void deletePicture_skipsBlankUrl() {
        FileManager manager = new FileManager();
        CosManager cosManager = mock(CosManager.class);
        ReflectionTestUtils.setField(manager, "cosManager", cosManager);

        assertFalse(manager.deletePicture(null));
        assertFalse(manager.deletePicture(""));
        verify(cosManager, never()).deleteObject(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("删除文件：COS 删除异常只记录日志，不影响调用方")
    void deletePicture_swallowsCosException() {
        FileManager manager = new FileManager();
        CosManager cosManager = mock(CosManager.class);
        ReflectionTestUtils.setField(manager, "cosManager", cosManager);
        String url = "https://example.cos.myqcloud.com/public/1/a.jpg";
        when(cosManager.parseObjectKey(url)).thenReturn("public/1/a.jpg");
        doThrow(new RuntimeException("cos down")).when(cosManager).deleteObject("public/1/a.jpg");

        assertFalse(assertDoesNotThrow(() -> manager.deletePicture(url)));
    }

    private MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }
}
