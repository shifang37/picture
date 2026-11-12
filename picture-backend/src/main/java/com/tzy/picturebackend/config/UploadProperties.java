package com.tzy.picturebackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上传图片处理配置：控制解码并发，避免大图并发解码耗尽容器内存。
 */
@Component
@ConfigurationProperties(prefix = "app.upload")
@Data
public class UploadProperties {

    /**
     * 同时进行像素解码/缩放的图片数量上限
     */
    private int maxConcurrentDecodes = 2;

    /**
     * 等待解码许可的最长时间，单位毫秒；超时返回 42900
     */
    private long decodeWaitMillis = 5000L;
}
