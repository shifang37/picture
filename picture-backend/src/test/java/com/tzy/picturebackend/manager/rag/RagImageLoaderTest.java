package com.tzy.picturebackend.manager.rag;

import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.manager.CosManager;
import com.tzy.picturebackend.model.entity.Picture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 图片向量输入：base64 读取、URL 模式、非本桶拒绝与超限压缩。
 */
class RagImageLoaderTest {

    private CosManager cosManager;

    private RagProperties ragProperties;

    private RagImageLoader imageLoader;

    @BeforeEach
    void setUp() {
        cosManager = mock(CosManager.class);
        ragProperties = new RagProperties();
        imageLoader = new RagImageLoader(cosManager, ragProperties);
    }

    @Test
    @DisplayName("base64：从 COS 读字节并按图片格式生成 data URL")
    void buildBase64Input_usesCosBytes() {
        Picture picture = picture("public/1/a.jpg", "jpg");
        when(cosManager.parseObjectKey(picture.getUrl())).thenReturn("public/1/a.jpg");
        when(cosManager.getObjectBytes("public/1/a.jpg")).thenReturn(new byte[]{1, 2, 3});

        String input = imageLoader.buildBase64Input(picture);

        assertTrue(input.startsWith("data:image/jpeg;base64,"));
        assertEquals("AQID", input.substring("data:image/jpeg;base64,".length()));
    }

    @Test
    @DisplayName("base64：PNG 使用 image/png")
    void buildBase64Input_pngMime() {
        Picture picture = picture("public/1/a.png", "PNG");
        when(cosManager.parseObjectKey(picture.getUrl())).thenReturn("public/1/a.png");
        when(cosManager.getObjectBytes("public/1/a.png")).thenReturn(new byte[]{9, 8});

        assertTrue(imageLoader.buildBase64Input(picture).startsWith("data:image/png;base64,"));
    }

    @Test
    @DisplayName("URL 模式：拼接 imageMogr2/thumbnail/1024x，已有 query 用 &")
    void buildUrlInput_appendsThumbnailParam() {
        ragProperties.getEmbedding().setImageInput("url");
        Picture picture = picture("https://cos.example.com/a.jpg", "jpg");

        assertEquals("https://cos.example.com/a.jpg?imageMogr2/thumbnail/1024x",
                imageLoader.buildInput(picture));

        picture.setUrl("https://cos.example.com/a.jpg?sign=1");
        assertEquals("https://cos.example.com/a.jpg?sign=1&imageMogr2/thumbnail/1024x",
                imageLoader.buildInput(picture));
    }

    @Test
    @DisplayName("非本桶图片：直接永久失败，不回退公网下载")
    void buildBase64Input_rejectsForeignUrl() {
        Picture picture = picture("https://other.example.com/a.jpg", "jpg");
        when(cosManager.parseObjectKey(picture.getUrl())).thenReturn(null);

        EmbeddingException exception = assertThrows(EmbeddingException.class,
                () -> imageLoader.buildBase64Input(picture));

        assertTrue(!exception.isRetryable());
        assertTrue(exception.getMessage().contains("COS"));
    }

    @Test
    @DisplayName("超限：大图压缩到 2MB 以内并转为 JPEG")
    void compress_largePngFitsBase64Limit() throws Exception {
        byte[] png = randomPng(900, 900);
        assertTrue(png.length > RagImageLoader.MAX_RAW_BYTES, "测试图必须超过 2MB");

        byte[] compressed = imageLoader.compress(png);

        assertTrue(compressed.length <= RagImageLoader.MAX_RAW_BYTES);
        assertTrue(Base64.getEncoder().encodeToString(compressed).length() < 3 * 1024 * 1024);
        // 压缩结果是 JPEG：FF D8 开头
        assertEquals((byte) 0xFF, compressed[0]);
        assertEquals((byte) 0xD8, compressed[1]);
    }

    private Picture picture(String url, String picFormat) {
        Picture picture = new Picture();
        picture.setId(1L);
        picture.setUrl(url);
        picture.setPicFormat(picFormat);
        return picture;
    }

    /**
     * 生成随机像素 PNG：随机内容几乎不可压缩，确保超过 base64 上限。
     */
    static byte[] randomPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
