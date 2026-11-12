package com.tzy.picturebackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上传图片处理配置：控制上传体积上限与解码并发，避免大图并发解码耗尽容器内存。
 */
@Component
@ConfigurationProperties(prefix = "app.upload")
@Data
public class UploadProperties {

    /**
     * 单张图片大小上限：默认 10MB。
     * <p>
     * 服务端放宽到 10MB 是为了兼容不经过前端压缩的调用方（脚本、批量工具、HEIC 转换失败回退原文件），
     * 避免用户被硬性拒绝；前端仍以 2MB 为目标先压缩。
     */
    private long maxFileSizeBytes = 10 * 1024 * 1024L;

    /**
     * multipart 请求体上限：默认 12MB，留给表单字段与 boundary 的余量
     */
    private long maxRequestSizeBytes = 12 * 1024 * 1024L;

    /**
     * 同时进行像素解码/缩放的图片数量上限
     */
    private int maxConcurrentDecodes = 2;

    /**
     * 等待解码许可的最长时间，单位毫秒；超时返回 42900
     */
    private long decodeWaitMillis = 5000L;
}
