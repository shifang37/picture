package com.tzy.picturebackend.manager.rag;

import cn.hutool.core.util.StrUtil;
import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.manager.CosManager;
import com.tzy.picturebackend.model.entity.Picture;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;

/**
 * 图片向量输入构造。
 * <p>
 * 默认 base64：通过 COS SDK 读取原图，避免公网 URL 的防盗链、私密图与未过审图片问题；
 * 超过 {@link #MAX_RAW_BYTES} 时先用 Thumbnailator 缩到长边 1024、JPEG 0.85 再编码。
 * {@code app.rag.embedding.image-input=url} 时只拼 {@code imageMogr2/thumbnail/1024x} 地址，
 * 由百炼侧拉取；拉取失败由 {@code RagIndexService} 回退 base64。
 */
@Component
@Slf4j
public class RagImageLoader {

    /**
     * 百炼单图上限 3MB；base64 会膨胀约 4/3，这里把原始字节控制在 2MB 以内
     */
    static final int MAX_RAW_BYTES = 2 * 1024 * 1024;

    /**
     * 图片向量输入的长边；与规划中的 imageMogr2/thumbnail/1024x 对齐
     */
    static final int MAX_DIMENSION = 1024;

    /**
     * 压缩质量
     */
    static final double JPEG_QUALITY = 0.85;

    /**
     * 数据万象处理参数
     */
    static final String THUMBNAIL_PARAM = "imageMogr2/thumbnail/1024x";

    private final CosManager cosManager;

    private final RagProperties ragProperties;

    public RagImageLoader(CosManager cosManager, RagProperties ragProperties) {
        this.cosManager = cosManager;
        this.ragProperties = ragProperties;
    }

    /**
     * 按配置构造图片向量输入：base64（默认）或 url。
     */
    public String buildInput(Picture picture) {
        if (picture == null || StrUtil.isBlank(picture.getUrl())) {
            throw EmbeddingException.permanent("图片地址为空，无法向量化");
        }
        return useUrlInput() ? buildUrlInput(picture) : buildBase64Input(picture);
    }

    /**
     * URL 模式：交给百炼公网拉取，附带长边 1024 的缩略参数以控制体积。
     */
    public String buildUrlInput(Picture picture) {
        String url = picture.getUrl().trim();
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + THUMBNAIL_PARAM;
    }

    /**
     * base64 模式：从 COS 读字节，超过上限时压缩。
     */
    public String buildBase64Input(Picture picture) {
        String url = picture.getUrl();
        String key = cosManager.parseObjectKey(url);
        if (StrUtil.isBlank(key)) {
            // 历史数据可能指向其他桶/CDN，不猜测，直接失败并记录，避免把无关图片写进索引
            throw EmbeddingException.permanent("图片不在当前 COS 桶，无法读取字节");
        }
        byte[] bytes = cosManager.getObjectBytes(key);
        if (bytes == null || bytes.length == 0) {
            throw EmbeddingException.permanent("图片内容为空，无法向量化");
        }
        String mimeType = mimeType(picture.getPicFormat());
        if (bytes.length > MAX_RAW_BYTES) {
            bytes = compress(bytes);
            mimeType = "image/jpeg";
        }
        String base64 = Base64.getEncoder().encodeToString(bytes);
        return "data:" + mimeType + ";base64," + base64;
    }

    /**
     * 是否走 URL 入口；只有显式配置 url 才启用，其他值一律按 base64 处理。
     */
    public boolean useUrlInput() {
        return "url".equalsIgnoreCase(StrUtil.trim(ragProperties.getEmbedding().getImageInput()));
    }

    /**
     * 压缩到长边 1024 的 JPEG；压缩后仍超限则报永久错误，避免把超大请求发给模型。
     */
    byte[] compress(byte[] source) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(source.length, MAX_RAW_BYTES));
            Thumbnails.of(new ByteArrayInputStream(source))
                    .size(MAX_DIMENSION, MAX_DIMENSION)
                    .outputFormat("jpg")
                    .outputQuality(JPEG_QUALITY)
                    .toOutputStream(output);
            byte[] compressed = output.toByteArray();
            if (compressed.length == 0) {
                throw EmbeddingException.permanent("图片压缩结果为空，无法向量化");
            }
            if (compressed.length > MAX_RAW_BYTES) {
                throw EmbeddingException.permanent("图片压缩后仍超过 2MB，无法作为 base64 输入");
            }
            log.info("图片向量输入已压缩, 原大小 = {}, 压缩后 = {}", source.length, compressed.length);
            return compressed;
        } catch (IOException e) {
            throw EmbeddingException.permanent("图片压缩失败: " + e.getMessage());
        }
    }

    /**
     * 按图片格式推断 MIME；未知格式按 JPEG 处理，模型侧仍会按内容解码。
     */
    static String mimeType(String picFormat) {
        if (StrUtil.isBlank(picFormat)) {
            return "image/jpeg";
        }
        String format = picFormat.trim().toLowerCase(Locale.ROOT);
        return switch (format) {
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "image/jpeg";
        };
    }
}
