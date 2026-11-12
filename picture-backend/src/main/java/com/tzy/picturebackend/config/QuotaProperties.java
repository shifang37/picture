package com.tzy.picturebackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 用户存储配额配置。
 * <p>
 * 配额只统计“未删除图片”：软删除（进回收站）立即释放额度，恢复时重新占用；
 * 回收站保留期内 COS 仍占用存储，因此配额不等于 COS 实际用量，成本监控仍以 COS 为准。
 */
@Component
@ConfigurationProperties(prefix = "app.quota")
@Data
public class QuotaProperties {

    /**
     * 是否启用配额；关闭后所有用户都不受配额限制
     */
    private boolean enabled = true;

    /**
     * 新用户默认存储配额：1GB
     */
    private long defaultStorageBytes = 1024L * 1024 * 1024;

    /**
     * 新用户默认图片数量上限
     */
    private int defaultPictureCount = 1000;

    /**
     * 管理员是否绕过配额：默认 true，避免审核/运营账号被卡住
     */
    private boolean exemptAdmin = true;

    /**
     * 全量重算时单批用户数
     */
    private int recalcBatchSize = 500;
}
