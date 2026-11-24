package com.tzy.picturebackend.model.vo.rag;

import lombok.Data;

/**
 * RAG 前端开关配置（公开接口）。
 * <p>
 * 首页 / 问答页需要知道「现在能不能用 hybrid、能不能问」才能决定是否展示入口与传
 * {@code searchMode=hybrid}。这些开关随部署环境变化，不能写死在前端构建产物里，
 * 因此由后端在运行时下发；只暴露布尔值与公开限额，不包含模型地址、Key 等敏感配置。
 */
@Data
public class RagConfigVO {

    /**
     * RAG 总开关（{@code app.rag.enabled}）
     */
    private boolean enabled;

    /**
     * 是否可用混合检索：RAG 开启且搜索后端为 Elasticsearch
     */
    private boolean hybridSearch;

    /**
     * 是否可用图库问答：与混合检索同一条件；未配置 DeepSeek Key 时接口会降级，但入口仍可展示
     */
    private boolean askEnabled;

    /**
     * 每人每日问答次数上限
     */
    private int askDailyLimit;

    /**
     * 单次问答默认召回的候选数
     */
    private int askTopK;
}
