package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.config.UploadProperties;
import com.tzy.picturebackend.metrics.PictureMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EXIF 处理测试：方向纠正、GPS 剥离与失败回退。
 * <p>
 * 测试用 JPEG 由 {@link ImageIO} 生成，再手工插入最小 EXIF APP1 段，避免依赖真实手机照片。
 */
class FileManagerExifTest {

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

    // ==================== 读取 ====================

    @Test
    @DisplayName("读取 EXIF：能识别方向 6 与 GPS IFD")
    void readExif_readsOrientationAndGps() throws Exception {
        File file = writeTempFile(".jpg", jpegWithExif(400, 300, 6, true));

        FileManager.ExifInfo exif = FileManager.readExif(file);

        assertEquals(6, exif.orientation());
        assertTrue(exif.hasGps());
        assertTrue(exif.needsRewrite());
        assertEquals(90, exif.rotationDegrees());
    }

    @Test
    @DisplayName("读取 EXIF：普通 JPEG 返回方向 1、无 GPS")
    void readExif_withoutMetadata() throws Exception {
        File file = writeTempFile(".jpg", jpegBytes(400, 300));

        FileManager.ExifInfo exif = FileManager.readExif(file);

        assertEquals(1, exif.orientation());
        assertFalse(exif.hasGps());
        assertFalse(exif.needsRewrite());
    }

    @Test
    @DisplayName("读取 EXIF：文件损坏或不存在时不抛异常")
    void readExif_toleratesBrokenFile() throws Exception {
        File broken = writeTempFile(".jpg", "not-an-image".getBytes());

        assertEquals(FileManager.ExifInfo.NONE, FileManager.readExif(broken));
        assertEquals(FileManager.ExifInfo.NONE, FileManager.readExif(null));
        assertEquals(FileManager.ExifInfo.NONE, FileManager.readExif(new File("missing.jpg")));
    }

    // ==================== 方向纠正 ====================

    @Test
    @DisplayName("方向 6：3000x2000 横图纠正为 2000x3000 竖图，并剥离 EXIF")
    void applyExif_rotates90() throws Exception {
        File source = writeTempFile(".jpg", jpegWithExif(3000, 2000, 6, false));

        File result = fileManager.applyExif(source, "jpg", FileManager.readExif(source));
        tempFiles.add(result);

        assertEquals(2000, ImageIO.read(result).getWidth());
        assertEquals(3000, ImageIO.read(result).getHeight());
        assertEquals(1, FileManager.readExif(result).orientation());
        assertEquals(1L, meterRegistry.get(PictureMetrics.EXIF).tag("action", "rotated").counter().count());
    }

    @Test
    @DisplayName("方向 3：180° 旋转后像素上下左右翻转")
    void applyExif_rotates180() throws Exception {
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 10, 10);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(30, 10, 10, 10);
        } finally {
            graphics.dispose();
        }
        File source = writeTempFile(".jpg", jpegWithExif(image, 3, false));

        File result = fileManager.applyExif(source, "jpg", FileManager.readExif(source));
        tempFiles.add(result);

        BufferedImage rotated = ImageIO.read(result);
        assertEquals(40, rotated.getWidth());
        assertEquals(20, rotated.getHeight());
        // 左上角原本是红色，旋转 180° 后右下角是红色；JPEG 有损，按容差比较
        assertColorNear(Color.RED, new Color(rotated.getRGB(39, 19)), 60);
        assertColorNear(Color.BLUE, new Color(rotated.getRGB(0, 0)), 60);
    }

    @Test
    @DisplayName("方向 8：270° 旋转，宽高互换")
    void applyExif_rotates270() throws Exception {
        File source = writeTempFile(".jpg", jpegWithExif(600, 400, 8, false));

        File result = fileManager.applyExif(source, "jpg", FileManager.readExif(source));
        tempFiles.add(result);

        BufferedImage rotated = ImageIO.read(result);
        assertEquals(400, rotated.getWidth());
        assertEquals(600, rotated.getHeight());
    }

    @Test
    @DisplayName("只有 GPS：不旋转但重新编码剥离元数据")
    void applyExif_stripsGpsOnly() throws Exception {
        File source = writeTempFile(".jpg", jpegWithExif(800, 600, 1, true));

        File result = fileManager.applyExif(source, "jpg", FileManager.readExif(source));
        tempFiles.add(result);

        BufferedImage stripped = ImageIO.read(result);
        assertEquals(800, stripped.getWidth());
        assertEquals(600, stripped.getHeight());
        assertFalse(FileManager.readExif(result).hasGps());
        assertEquals(1L, meterRegistry.get(PictureMetrics.EXIF).tag("action", "stripped").counter().count());
        var rotatedCounter = meterRegistry.find(PictureMetrics.EXIF).tag("action", "rotated").counter();
        assertEquals(0L, rotatedCounter == null ? 0L : rotatedCounter.count());
    }

    // ==================== 跳过与回退 ====================

    @Test
    @DisplayName("无需处理：方向 1 且无 GPS 时返回原文件")
    void applyExif_keepsFileWhenNothingToDo() throws Exception {
        File source = writeTempFile(".jpg", jpegBytes(400, 300));

        assertSame(source,
                fileManager.applyExif(source, "jpg", FileManager.readExif(source)));
    }

    @Test
    @DisplayName("非 JPEG：PNG/WebP 保持原文件（JDK 无对应 EXIF 写入语义）")
    void applyExif_skipsNonJpeg() throws Exception {
        File source = writeTempFile(".png", pngBytes(100, 100));
        FileManager.ExifInfo rotated = new FileManager.ExifInfo(6, true);

        assertSame(source, fileManager.applyExif(source, "png", rotated));
        assertSame(source, fileManager.applyExif(source, "webp", rotated));
    }

    @Test
    @DisplayName("处理失败：无法解码时回退原文件，不抛异常")
    void applyExif_fallsBackForUnreadableImage() throws Exception {
        byte[] header = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
        File source = writeTempFile(".jpg", header);

        File result = fileManager.applyExif(source, "jpg", new FileManager.ExifInfo(6, false));

        assertSame(source, result);
    }

    @Test
    @DisplayName("旋转：90° 与 270° 的像素映射方向正确")
    void rotate_mapsPixelsClockwise() {
        BufferedImage source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, Color.RED.getRGB());
        source.setRGB(1, 0, Color.BLUE.getRGB());

        BufferedImage clockwise = FileManager.rotate(source, 90);
        assertEquals(1, clockwise.getWidth());
        assertEquals(2, clockwise.getHeight());
        assertColorNear(Color.RED, new Color(clockwise.getRGB(0, 0)));
        assertColorNear(Color.BLUE, new Color(clockwise.getRGB(0, 1)));

        BufferedImage counterClockwise = FileManager.rotate(source, 270);
        assertEquals(1, counterClockwise.getWidth());
        assertEquals(2, counterClockwise.getHeight());
        assertColorNear(Color.BLUE, new Color(counterClockwise.getRGB(0, 0)));
        assertColorNear(Color.RED, new Color(counterClockwise.getRGB(0, 1)));
    }

    // ==================== 准备阶段串联 ====================

    @Test
    @DisplayName("准备阶段：先纠正方向再压缩，最终文件是竖图且不含 EXIF")
    void prepareUpload_rotatesBeforeResize() throws Exception {
        File source = writeTempFile(".jpg", jpegWithExif(3000, 2000, 6, true));
        byte[] bytes = Files.readAllBytes(source.toPath());

        var multipartFile = new org.springframework.mock.web.MockMultipartFile(
                "file", "portrait.jpg", "image/jpeg", bytes);
        var prepared = fileManager.prepareUpload(multipartFile, "public/1");
        try {
            BufferedImage uploaded = ImageIO.read(prepared.getUploadFile());
            assertEquals(1280, uploaded.getWidth(), "3000x2000 旋转后 2000x3000，长边缩到 1920");
            assertEquals(1920, uploaded.getHeight());
            assertFalse(FileManager.readExif(prepared.getUploadFile()).needsRewrite());
            assertTrue(prepared.getFileHash().length() == 64);
        } finally {
            prepared.cleanup();
        }
    }

    // ==================== 辅助方法 ====================

    private void assertColorNear(Color expected, Color actual) {
        assertColorNear(expected, actual, 12);
    }

    private void assertColorNear(Color expected, Color actual, int tolerance) {
        assertEquals(expected.getRed(), actual.getRed(), tolerance);
        assertEquals(expected.getGreen(), actual.getGreen(), tolerance);
        assertEquals(expected.getBlue(), actual.getBlue(), tolerance);
    }

    private File writeTempFile(String suffix, byte[] content) throws IOException {
        File file = File.createTempFile("fm-exif-", suffix);
        Files.write(file.toPath(), content);
        tempFiles.add(file);
        return file;
    }

    private byte[] jpegBytes(int width, int height) throws IOException {
        return imageBytes(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg");
    }

    private byte[] pngBytes(int width, int height) throws IOException {
        return imageBytes(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png");
    }

    private byte[] imageBytes(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    private byte[] jpegWithExif(int width, int height, int orientation, boolean gps) throws IOException {
        return jpegWithExif(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), orientation, gps);
    }

    private byte[] jpegWithExif(BufferedImage image, int orientation, boolean gps) throws IOException {
        byte[] plain = imageBytes(image, "jpg");
        byte[] app1 = exifApp1(orientation, gps);
        byte[] result = new byte[plain.length + app1.length];
        // SOI（FFD8）之后插入 APP1，其余字节原样保留
        result[0] = plain[0];
        result[1] = plain[1];
        System.arraycopy(app1, 0, result, 2, app1.length);
        System.arraycopy(plain, 2, result, 2 + app1.length, plain.length - 2);
        return result;
    }

    /**
     * 构造最小 EXIF APP1 段：IFD0 至少包含 Orientation，可选 GPS IFD 指针。
     */
    private byte[] exifApp1(int orientation, boolean gps) {
        int entryCount = gps ? 2 : 1;
        int ifd0Offset = 8;
        int gpsIfdOffset = ifd0Offset + 2 + entryCount * 12 + 4;
        int tiffLength = gpsIfdOffset + (gps ? 2 + 12 + 4 : 0);

        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        // TIFF header：小端
        tiff.writeBytes(new byte[]{'I', 'I', 0x2A, 0x00});
        writeInt(tiff, ifd0Offset);
        writeShort(tiff, entryCount);
        // Orientation：SHORT，count 1，值内联
        writeShort(tiff, 0x0112);
        writeShort(tiff, 3);
        writeInt(tiff, 1);
        writeShort(tiff, orientation);
        writeShort(tiff, 0);
        if (gps) {
            // GPS IFD 指针：LONG，count 1，值 = GPS IFD 偏移
            writeShort(tiff, 0x8825);
            writeShort(tiff, 4);
            writeInt(tiff, 1);
            writeInt(tiff, gpsIfdOffset);
            // 下一个 IFD 为空
            writeInt(tiff, 0);
            // GPS IFD：一个 ASCII 条目（GPSLatitudeRef = "N"）
            writeShort(tiff, 1);
            writeShort(tiff, 0x0001);
            writeShort(tiff, 2);
            writeInt(tiff, 2);
            writeShort(tiff, 'N');
            writeShort(tiff, 0);
            writeInt(tiff, 0);
        } else {
            writeInt(tiff, 0);
        }

        byte[] tiffBytes = tiff.toByteArray();
        assertEquals(tiffLength, tiffBytes.length);

        ByteArrayOutputStream app1 = new ByteArrayOutputStream();
        app1.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE1});
        writeShortBigEndian(app1, 2 + 6 + tiffBytes.length);
        app1.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        app1.writeBytes(tiffBytes);
        return app1.toByteArray();
    }

    private void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
    }

    private void writeInt(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
        output.write((value >> 16) & 0xFF);
        output.write((value >> 24) & 0xFF);
    }

    private void writeShortBigEndian(ByteArrayOutputStream output, int value) {
        output.write((value >> 8) & 0xFF);
        output.write(value & 0xFF);
    }
}
