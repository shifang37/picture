package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原图压缩测试：只验证本地图片处理，不依赖 COS
 */
class FileManagerCompressionTest {

    private final FileManager fileManager = new FileManager();

    private final List<File> tempFiles = new ArrayList<>();

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
    }

    @Test
    @DisplayName("小图：尺寸和体积都未超阈值时保持原文件")
    void compressIfNeeded_keepsSmallImage() throws Exception {
        File source = writeJpeg(800, 600);

        File result = fileManager.compressIfNeeded(source, "jpg");

        assertSame(source, result);
    }

    @Test
    @DisplayName("PNG：缩放后保留透明通道")
    void compressIfNeeded_keepsPngTransparency() throws Exception {
        File source = writeTransparentPng(3000, 2000);

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
    @DisplayName("像素校验：超过 4000 万像素拒绝，边界值放行")
    void validatePixelCount_enforcesLimit() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> FileManager.validatePixelCount(7000, 6000));
        assertTrue(e.getMessage().contains("像素过大"));

        assertDoesNotThrow(() -> FileManager.validatePixelCount(8000, 5000));
        assertThrows(BusinessException.class, () -> FileManager.validatePixelCount(0, 100));
    }

    @Test
    @DisplayName("未知后缀：不处理，直接返回原文件")
    void compressIfNeeded_ignoresUnknownSuffix() throws Exception {
        File source = createTempFile("fm-test-", ".gif");

        assertSame(source, fileManager.compressIfNeeded(source, "gif"));
        assertSame(source, fileManager.compressIfNeeded(source, ""));
        assertSame(source, fileManager.compressIfNeeded(source, null));
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
