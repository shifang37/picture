package com.tzy.picturebackend.manager.rag;

import java.util.List;

/**
 * 生成模型返回的原始 JSON 结构。
 * <p>
 * 这是**不可信输入**：调用方必须先校验 {@code citedPictureIds} 属于本次召回集、
 * 回答长度未超限、{@code refused=true} 时没有引用，校验通过后才允许返回给前端。
 *
 * @param answer          回答文本
 * @param citedPictureIds 引用的图片 id
 * @param refused         模型是否拒答
 */
public record RagAnswerPayload(String answer, List<Long> citedPictureIds, boolean refused) {
}
