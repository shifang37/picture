package com.tzy.picturebackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 搜索配置。
 * <p>
 * 默认 {@code mysql}：完全沿用原有的 MyBatis 模糊查询，不连接 Elasticsearch；
 * 设为 {@code es} 时公开列表改由 Elasticsearch 召回 id，Elasticsearch 异常时自动回落到 MySQL。
 */
@Component
@ConfigurationProperties(prefix = "app.search")
@Data
public class SearchProperties {

    /**
     * 搜索后端：mysql（默认）| es
     */
    private String engine = "mysql";

    /**
     * 是否启用 Elasticsearch 搜索
     */
    public boolean isElasticsearchEnabled() {
        return "es".equalsIgnoreCase(engine);
    }
}
