package com.tzy.picturebackend.manager.rag;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 语料拼接规则：集中一处，便于升级版本。
 * <p>
 * 规则（{@link #VERSION} = v1）：
 * <pre>
 * name。introduction。分类：category。标签：tag1、tag2（。审核意见：xxx，仅当已通过且非空）
 * </pre>
 * 去空白、去重后截断到 {@link #MAX_LENGTH} 字符。拼接规则变化必须同时升 {@link #VERSION}，
 * 否则旧向量会被误用。
 */
@Component
public class EmbeddingText {

    /**
     * 语料规则版本；修改拼接规则时必须升级
     */
    public static final String VERSION = "v1";

    /**
     * 单条语料最大长度：避免超出模型输入上限，也避免无意义的超长文本抬高成本
     */
    static final int MAX_LENGTH = 512;

    private static final String TAG_SEPARATOR = "、";

    private static final String PART_SEPARATOR = "。";

    /**
     * 把一张图片拼成语料；所有字段都为空时返回空字符串，调用方据此跳过文本向量。
     */
    public String build(Picture picture) {
        if (picture == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        addPart(parts, picture.getName());
        addPart(parts, picture.getIntroduction());
        if (StrUtil.isNotBlank(picture.getCategory())) {
            addPart(parts, "分类：" + picture.getCategory().trim());
        }
        List<String> tags = parseTags(picture.getTags());
        if (!tags.isEmpty()) {
            addPart(parts, "标签：" + String.join(TAG_SEPARATOR, tags));
        }
        if (Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.PASS.getValue())
                && StrUtil.isNotBlank(picture.getReviewMessage())) {
            addPart(parts, "审核意见：" + picture.getReviewMessage().trim());
        }
        String text = String.join(PART_SEPARATOR, parts);
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH);
    }

    /**
     * 语料 sha256；相同语料必须得到相同 hash，消费者据此跳过重算。
     */
    public String hash(String text) {
        return sha256Hex(text == null ? "" : text);
    }

    /**
     * 语料规则版本；写入 picture_embedding 与向量文档，规则变化时旧向量会被判定为过期。
     */
    public String version() {
        return VERSION;
    }

    /**
     * 通用 sha256 十六进制；图片内容 hash 也复用这里。
     */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    /**
     * picture.tags 是 JSON 数组；脏数据或非数组时按无标签处理，不影响其他字段参与检索。
     * <p>
     * 向量文档的 tags 过滤字段也复用这个解析，保证索引与语料看到同一份标签。
     */
    public List<String> parseTags(String tagsJson) {
        if (StrUtil.isBlank(tagsJson)) {
            return List.of();
        }
        try {
            List<String> tags = JSONUtil.parseArray(tagsJson).toList(String.class);
            List<String> result = new ArrayList<>(tags.size());
            for (String tag : tags) {
                if (StrUtil.isNotBlank(tag)) {
                    result.add(tag.trim());
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void addPart(List<String> parts, String value) {
        if (StrUtil.isNotBlank(value)) {
            parts.add(value.trim());
        }
    }
}
