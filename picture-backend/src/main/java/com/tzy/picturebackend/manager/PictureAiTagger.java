package com.tzy.picturebackend.manager;

import java.util.List;

/**
 * AI 图片标注供应方抽象。
 * <p>
 * 当前实现为 DeepSeek；更换模型或供应方时只需要提供新的实现，业务层不感知具体 HTTP 细节。
 */
public interface PictureAiTagger {

    /**
     * 根据图片生成名称、简介、分类与标签建议。
     *
     * @param imageUrl      可公网访问的图片地址（建议传缩略图）
     * @param categoryDict  分类字典，模型应优先从中选择
     * @param tagDict       标签字典，模型应优先从中选择
     * @return 模型输出，仍需业务层校验后再入库
     * @throws AiSuggestionException 调用失败或输出无法解析
     */
    AiSuggestionPayload suggest(String imageUrl, List<String> categoryDict, List<String> tagDict);
}
