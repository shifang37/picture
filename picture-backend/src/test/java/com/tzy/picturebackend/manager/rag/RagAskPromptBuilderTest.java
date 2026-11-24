package com.tzy.picturebackend.manager.rag;

import com.tzy.picturebackend.model.entity.Picture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 问答提示词：固定规则、候选字段、尺寸方向与截断/转义。
 */
class RagAskPromptBuilderTest {

    private final RagAskPromptBuilder builder = new RagAskPromptBuilder();

    @Test
    @DisplayName("系统提示词：约束 JSON 输出、引用只能来自候选、尺寸判断以事实为准")
    void systemPrompt_containsHardRules() {
        String prompt = builder.systemPrompt();

        assertTrue(prompt.contains("citedPictureIds"));
        assertTrue(prompt.contains("候选"));
        assertTrue(prompt.contains("最多 4 个"));
        assertTrue(prompt.contains("竖版"));
        assertTrue(prompt.contains("拒答"));
        assertTrue(prompt.contains("非图库请求"));
        assertEquals("ask-v2", RagAskPromptBuilder.PROMPT_VERSION);
    }

    @Test
    @DisplayName("候选：包含 id、名称、分类、标签、尺寸方向与简介")
    void candidateLine_containsPublicFacts() {
        Picture picture = picture(12L, "海边日落", "风景", "[\"海边\",\"日落\"]");
        picture.setPicWidth(1200);
        picture.setPicHeight(1800);
        picture.setIntroduction("夕阳贴着海面，橙红色天空，竖构图，适合做海报。");

        String prompt = builder.userPrompt("哪些图适合做竖版海报？", List.of(picture));

        assertTrue(prompt.contains("问题：哪些图适合做竖版海报？"));
        assertTrue(prompt.contains("[12] 海边日落"));
        assertTrue(prompt.contains("分类：风景"));
        assertTrue(prompt.contains("标签：海边、日落"));
        assertTrue(prompt.contains("尺寸：1200x1800（竖版"));
        assertTrue(prompt.contains("简介：夕阳贴着海面"));
        assertTrue(prompt.contains("</候选>"));
    }

    @Test
    @DisplayName("候选：缺失字段降级为未知/无，不出现 null")
    void candidateLine_handlesMissingFields() {
        Picture picture = picture(3L, "未命名", null, null);

        String line = builder.candidateLine(picture);

        assertTrue(line.contains("分类：未知"));
        assertTrue(line.contains("标签：无"));
        assertTrue(line.contains("尺寸：未知"));
        assertTrue(line.contains("简介：未知"));
        assertFalse(line.contains("null"));
    }

    @Test
    @DisplayName("候选：换行、竖线与超长简介被清理，避免破坏提示词结构")
    void candidateLine_sanitizesAndTruncates() {
        Picture picture = picture(5L, "标题|带竖线", "分类\n换行", "[\"标签|A\"]");
        picture.setIntroduction("长".repeat(300));

        String line = builder.candidateLine(picture);

        assertFalse(line.contains("\n"));
        assertTrue(line.contains("标题/带竖线"));
        assertTrue(line.contains("分类 换行"));
        String introduction = line.substring(line.indexOf("简介：") + 3);
        assertEquals(RagAskPromptBuilder.INTRODUCTION_MAX_CHARS, introduction.length());
    }

    @Test
    @DisplayName("空候选：仍输出合法结构，但正常流程不会调用模型")
    void userPrompt_emptyCandidates() {
        String prompt = builder.userPrompt("有哪些图？", List.of());

        assertTrue(prompt.contains("<候选>\n</候选>"));
    }

    private static Picture picture(Long id, String name, String category, String tags) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setName(name);
        picture.setCategory(category);
        picture.setTags(tags);
        return picture;
    }
}
