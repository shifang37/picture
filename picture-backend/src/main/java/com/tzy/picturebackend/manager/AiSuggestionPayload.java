package com.tzy.picturebackend.manager;

import java.util.List;

/**
 * AI 图片标注建议。
 *
 * @param name         建议名称
 * @param introduction 建议简介
 * @param category     建议分类
 * @param tags         建议标签
 */
public record AiSuggestionPayload(String name, String introduction, String category, List<String> tags) {
}
