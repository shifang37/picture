package com.tzy.picturebackend.config;

import io.swagger.v3.oas.models.media.StringSchema;
import jakarta.annotation.PostConstruct;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;

/**
 * 让 OpenAPI 文档中的 Long 按 string 描述。
 * <p>
 * 项目通过 {@link JsonConfig} 把 Long 序列化为字符串（避免雪花 ID 精度丢失），
 * 真实响应里 id 是 "2120081934082043905" 而不是数字。若文档仍按 integer(int64) 描述，
 * 前端由 openapi 生成的类型就会是 number，进而诱导调用方做 Number() 转换，
 * 反而造成精度丢失。
 * <p>
 * 只替换包装类型 Long，基本类型 long 不受影响（例如分页的 total 仍按数字处理）。
 */
@Configuration
public class SpringDocConfig {

    @PostConstruct
    public void replaceLongSchema() {
        SpringDocUtils.getConfig().replaceWithSchema(Long.class, new StringSchema());
    }
}
