package com.tzy.picturebackend.model.enums;

/**
 * 公开列表的检索模式。
 * <p>
 * 默认 {@code keyword} 与接入 RAG 之前完全一致；{@code hybrid} 与 {@code semantic}
 * 只有在 {@code app.rag.enabled=true} 且搜索后端为 Elasticsearch 时才生效，
 * 否则由控制层按 {@code keyword} 处理并降级。
 */
public enum PictureSearchModeEnum {

    /**
     * 现有 BM25（或 MySQL）检索，行为不变
     */
    KEYWORD("keyword"),

    /**
     * BM25 + kNN 各取 topN，RRF 融合后分页
     */
    HYBRID("hybrid"),

    /**
     * 纯 kNN，用于「以文搜图」与调试对比
     */
    SEMANTIC("semantic");

    private final String value;

    PictureSearchModeEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 把请求值解析成枚举：null / 空白按 {@code keyword} 处理，未知值直接报错。
     * <p>
     * 控制层的 {@code @Valid} 已经拦截非法值（40000），这里是防御性校验，
     * 避免绕过接口直接调用服务层时产生难以定位的降级。
     */
    public static PictureSearchModeEnum fromValue(String value) {
        if (value == null || value.isBlank()) {
            return KEYWORD;
        }
        for (PictureSearchModeEnum mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("不支持的搜索模式: " + value);
    }
}
