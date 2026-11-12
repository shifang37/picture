package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.config.UploadProperties;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.dto.file.PreparedUpload;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原图压缩测试：只验证本地图片处理，不依赖 COS
 */
class FileManagerCompressionTest {

    private final FileManager fileManager = new FileManager();

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final List<File> tempFiles = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(fileManager, "pictureMetrics", new PictureMetrics(meterRegistry));
        UploadProperties uploadProperties = new UploadProperties();
        uploadProperties.setMaxConcurrentDecodes(2);
        uploadProperties.setDecodeWaitMillis(1000);
        ReflectionTestUtils.setField(fileManager, "imageDecodeLimiter",
                new ImageDecodeLimiter(uploadProperties));
    }

    @AfterEach
    void cleanup() {
        for (File file : tempFiles) {
            if (file.exists() && !file.delete()) {
                file.deleteOnExit();
            }
        }
    }

    @Test
    @DisplayName("大 JPEG：长边缩到 1920 以内且体积下降")
    void compressIfNeeded_resizesLargeJpeg() throws Exception {
        File source = writeJpeg(3000, 2000);
        long originalSize = source.length();

        File compressed = fileManager.compressIfNeeded(source, "jpg");
        tempFiles.add(compressed);

        assertNotSame(source, compressed, "大图应该生成压缩文件");
        BufferedImage result = ImageIO.read(compressed);
        assertEquals(1920, Math.max(result.getWidth(), result.getHeight()));
        assertEquals(1.5, result.getWidth() / (double) result.getHeight(), 0.01);
        assertTrue(compressed.length() < originalSize,
                "压缩后体积应下降: " + originalSize + " -> " + compressed.length());
        assertEquals(1L, meterRegistry.get(PictureMetrics.COMPRESS).timer().count(),
                "压缩耗时应被计时器记录一次");
    }

    @Test
    @DisplayName("小图：尺寸和体积都未超阈值时保持原文件")
    void compressIfNeeded_keepsSmallImage() throws Exception {
        File source = writeJpeg(800, 600);

        File result = fileManager.compressIfNeeded(source, "jpg");

        assertSame(source, result);
    }

    @Test
    @DisplayName("PNG：降采样解码后缩放并保留透明通道")
    void compressIfNeeded_keepsPngTransparency() throws Exception {
        File source = writeTransparentPng(4000, 3000);

        File compressed = fileManager.compressIfNeeded(source, "png");
        tempFiles.add(compressed);

        assertNotSame(source, compressed);
        BufferedImage result = ImageIO.read(compressed);
        assertEquals(1920, Math.max(result.getWidth(), result.getHeight()));
        int alpha = (result.getRGB(100, 100) >>> 24) & 0xFF;
        assertEquals(0, alpha, "透明区域的 alpha 应保持为 0");
        int opaqueAlpha = (result.getRGB(result.getWidth() / 2, result.getHeight() / 2) >>> 24) & 0xFF;
        assertEquals(255, opaqueAlpha, "不透明区域的 alpha 应保持为 255");
    }

    @Test
    @DisplayName("WebP：没有内置编码器，保持原文件")
    void compressIfNeeded_keepsWebp() throws Exception {
        File source = createTempFile("fm-test-", ".webp");
        Files.write(source.toPath(), new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'});

        assertSame(source, fileManager.compressIfNeeded(source, "webp"));
    }

    @Test
    @DisplayName("无法解析的图片：回退原文件，不抛异常")
    void compressIfNeeded_fallsBackForUnreadableImage() throws Exception {
        File source = createTempFile("fm-test-", ".jpg");
        Files.write(source.toPath(), "not-an-image".getBytes(StandardCharsets.UTF_8));

        assertSame(source, fileManager.compressIfNeeded(source, "jpg"));
    }

    @Test
    @DisplayName("像素校验：超过 2000 万像素拒绝，20MP 边界值放行")
    void validatePixelCount_enforcesLimit() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> FileManager.validatePixelCount(5000, 5000));
        assertTrue(e.getMessage().contains("像素过大"));

        assertDoesNotThrow(() -> FileManager.validatePixelCount(5000, 4000));
        assertThrows(BusinessException.class, () -> FileManager.validatePixelCount(5000, 4001));
        assertThrows(BusinessException.class, () -> FileManager.validatePixelCount(0, 100));
    }

    @Test
    @DisplayName("超大图：解码阶段降采样后仍缩放到 1920 长边")
    void compressIfNeeded_subsamplesVeryLargeJpeg() throws Exception {
        File source = writeJpeg(4000, 3000);
        long originalSize = source.length();

        File compressed = fileManager.compressIfNeeded(source, "jpg");
        tempFiles.add(compressed);

        assertNotSame(source, compressed);
        BufferedImage result = ImageIO.read(compressed);
        assertEquals(1920, result.getWidth());
        assertEquals(1440, result.getHeight());
        assertTrue(compressed.length() < originalSize,
                "压缩后体积应下降: " + originalSize + " -> " + compressed.length());
    }

    @Test
    @DisplayName("降采样倍数：向下取整保证降采样后长边不小于目标尺寸")
    void subsamplingFactor_keepsTargetReachable() {
        assertEquals(1, FileManager.subsamplingFactor(0));
        assertEquals(1, FileManager.subsamplingFactor(1920));
        assertEquals(1, FileManager.subsamplingFactor(3839));
        assertEquals(2, FileManager.subsamplingFactor(3840));
        assertEquals(3, FileManager.subsamplingFactor(6000));
    }

    @Test
    @DisplayName("未知后缀：不处理，直接返回原文件")
    void compressIfNeeded_ignoresUnknownSuffix() throws Exception {
        File source = createTempFile("fm-test-", ".gif");

        assertSame(source, fileManager.compressIfNeeded(source, "gif"));
        assertSame(source, fileManager.compressIfNeeded(source, ""));
        assertSame(source, fileManager.compressIfNeeded(source, null));
    }

    // ==================== 准备阶段：哈希与临时文件 ====================

    @Test
    @DisplayName("SHA-256：与标准测试向量一致")
    void sha256_matchesKnownVector() throws Exception {
        File file = createTempFile("fm-hash-", ".txt");
        Files.write(file.toPath(), "abc".getBytes(StandardCharsets.UTF_8));

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                FileManager.sha256(file));
    }

    @Test
    @DisplayName("准备阶段：压缩后计算哈希，体积与最终文件一致，清理后临时文件被删除")
    void prepareUpload_hashesFinalFileAndCleanup() throws Exception {
        File source = writeJpeg(3000, 2000);
        byte[] bytes = Files.readAllBytes(source.toPath());
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "photo.JPG", "image/jpeg", bytes);

        PreparedUpload prepared = fileManager.prepareUpload(multipartFile, "public/1");

        File uploadFile = prepared.getUploadFile();
        assertNotNull(uploadFile);
        assertTrue(uploadFile.exists());
        assertEquals(uploadFile.length(), prepared.getPicSize());
        // 大图会被压缩，哈希必须对应压缩后的最终文件，而不是原始上传文件
        assertNotEquals(bytes.length, prepared.getPicSize().intValue());
        assertEquals(FileManager.sha256(uploadFile), prepared.getFileHash());
        assertEquals(64, prepared.getFileHash().length());
        assertEquals("jpg", prepared.getSuffix());
        assertEquals("photo", prepared.getPicName());
        assertTrue(prepared.getUploadPath().startsWith("public/1/"));
        assertTrue(prepared.getUploadPath().endsWith(".jpg"));

        List<File> tempFiles = new ArrayList<>(prepared.getTempFiles());
        assertEquals(2, tempFiles.size(), "原文件与压缩产物都应在清理列表中");
        prepared.cleanup();
        for (File tempFile : tempFiles) {
            assertFalse(tempFile.exists(), "清理后临时文件应被删除: " + tempFile);
        }
    }

    @Test
    @DisplayName("准备阶段：非法文件在创建临时文件前就被拒绝，不产生残留")
    void prepareUpload_rejectsInvalidFile() {
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "a.jpg", "image/jpeg", "not-an-image".getBytes(StandardCharsets.UTF_8));

        BusinessException e = assertThrows(BusinessException.class,
                () -> fileManager.prepareUpload(multipartFile, "public/1"));
        assertEquals("文件内容不是受支持的图片", e.getMessage());
    }

    @Test
    @DisplayName("准备阶段：未注入 UploadProperties 时按 10MB 上限校验")
    void prepareUpload_usesDefaultSizeLimit() {
        // 10MB + 1 的假 JPEG：只需要文件头正确，大小校验会先命中
        byte[] bytes = new byte[(10 * 1024 * 1024) + 1];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "big.jpg", "image/jpeg", bytes);

        BusinessException e = assertThrows(BusinessException.class,
                () -> fileManager.prepareUpload(multipartFile, "public/1"));
        assertEquals("文件大小不能超过 10MB", e.getMessage());
    }

    // ==================== 辅助方法 ====================

    private File writeJpeg(int width, int height) throws IOException {
        BufferedImage image = gradientImage(width, height, BufferedImage.TYPE_INT_RGB);
        return writeImage(image, "jpg");
    }

    private File writeTransparentPng(int width, int height) throws IOException {
        BufferedImage image = gradientImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            // 左上角挖出透明区域，缩放后仍应保持透明
            graphics.setComposite(AlphaComposite.Clear);
            graphics.fillRect(0, 0, width / 6, height / 6);
        } finally {
            graphics.dispose();
        }
        return writeImage(image, "png");
    }

    private BufferedImage gradientImage(int width, int height, int imageType) {
        BufferedImage image = new BufferedImage(width, height, imageType);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setPaint(new GradientPaint(0, 0, Color.BLUE, width, height, Color.RED));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private File writeImage(BufferedImage image, String format) throws IOException {
        File file = createTempFile("fm-test-", "." + format);
        ImageIO.write(image, format, file);
        return file;
    }

    private File createTempFile(String prefix, String suffix) throws IOException {
        File file = File.createTempFile(prefix, suffix);
        tempFiles.add(file);
        return file;
    }
}
