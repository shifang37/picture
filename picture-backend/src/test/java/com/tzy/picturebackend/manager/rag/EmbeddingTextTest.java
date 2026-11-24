package com.tzy.picturebackend.manager.rag;

import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语料拼接与 hash：相同输入必须得到相同语料/hash，规则版本可追踪。
 */
class EmbeddingTextTest {

    private final EmbeddingText embeddingText = new EmbeddingText();

    @Test
    @DisplayName("拼接：名称、简介、分类、标签按固定顺序用句号连接")
    void build_joinsFieldsInOrder() {
        Picture picture = picture("海边日落", "暖色调的海边日落，适合做海报", "风景", "[\"日落\",\"海边\"]");

        String text = embeddingText.build(picture);

        assertEquals("海边日落。暖色调的海边日落，适合做海报。分类：风景。标签：日落、海边", text);
    }

    @Test
    @DisplayName("拼接：空字段跳过，不产生连续句号")
    void build_skipsBlankFields() {
        Picture picture = picture(" 沙漠 ", " ", "", "[]");

        assertEquals("沙漠", embeddingText.build(picture));
    }

    @Test
    @DisplayName("拼接：脏 tags JSON 不影响其他字段")
    void build_toleratesInvalidTags() {
        Picture picture = picture("星空", "银河与星轨", "夜景", "not-a-json");

        assertEquals("星空。银河与星轨。分类：夜景", embeddingText.build(picture));
    }

    @Test
    @DisplayName("拼接：审核意见只在已通过且非空时参与")
    void build_includesReviewMessageOnlyWhenPassed() {
        Picture picture = picture("星空", "银河与星轨", "夜景", "[]");
        picture.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());
        picture.setReviewMessage("等待复核");
        assertFalse(embeddingText.build(picture).contains("审核意见"));

        picture.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        assertTrue(embeddingText.build(picture).contains("审核意见：等待复核"));
    }

    @Test
    @DisplayName("截断：语料最长 512 字符")
    void build_truncatesToMaxLength() {
        Picture picture = picture("名".repeat(200), "介".repeat(400), "分类", "[]");

        String text = embeddingText.build(picture);

        assertEquals(512, text.length());
    }

    @Test
    @DisplayName("hash：SHA-256 十六进制、稳定且对内容敏感")
    void hash_isStableHex() {
        String first = embeddingText.hash("海边日落");
        String second = embeddingText.hash("海边日落");
        String different = embeddingText.hash("海边日出");

        assertEquals(first, second);
        assertFalse(first.equals(different));
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("版本：语料规则固定为 v1")
    void version_isStable() {
        assertEquals("v1", embeddingText.version());
        assertEquals("v1", EmbeddingText.VERSION);
    }

    private Picture picture(String name, String introduction, String category, String tags) {
        Picture picture = new Picture();
        picture.setId(1L);
        picture.setName(name);
        picture.setIntroduction(introduction);
        picture.setCategory(category);
        picture.setTags(tags);
        picture.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        return picture;
    }
}
