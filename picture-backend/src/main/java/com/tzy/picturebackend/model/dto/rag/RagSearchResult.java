package com.tzy.picturebackend.model.dto.rag;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 一次混合 / 语义检索的结果。
 * <p>
 * {@code mode} 是**实际提供数据**的路径，用于埋点与降级观测：
 * 成功时为 {@code hybrid} / {@code semantic}，降级到 BM25 时为 {@code es}；
 * 若连 BM25 也不可用，服务层抛异常，由控制层继续回落到 MySQL（{@code mysql}）。
 *
 * @param ids            当前页图片 id，按最终顺序排列
 * @param mode           实际路径：hybrid / semantic / es
 * @param degradedReason 降级原因；未降级时为 null
 */
public record RagSearchResult(Page<Long> ids, String mode, String degradedReason) {

    public static final String MODE_HYBRID = "hybrid";

    public static final String MODE_SEMANTIC = "semantic";

    public static final String MODE_ES = "es";

    /**
     * 是否发生了降级（结果实际来自 BM25）
     */
    public boolean degraded() {
        return degradedReason != null;
    }
}
