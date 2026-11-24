package com.tzy.picturebackend.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 向量链路生效条件：{@code app.rag.enabled=true} 且 {@code app.search.engine=es}。
 * <p>
 * 按规划的开关矩阵，RAG 开启但搜索后端仍是 mysql 时只打一条 WARN 并降级为纯 MySQL，
 * 不装配任何向量索引 Bean。把条件集中在这里，避免多个 Bean 各自重复判断。
 */
public class RagVectorEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        boolean enabled = Boolean.parseBoolean(environment.getProperty("app.rag.enabled", "false"));
        String engine = environment.getProperty("app.search.engine", "mysql");
        return enabled && "es".equalsIgnoreCase(engine);
    }
}
