package com.tzy.picturebackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 站内通知配置。
 * <p>
 * 保留期只作用于「已读」通知：未读通知永远不会被定时任务清理，避免用户错过审核结论。
 */
@Component
@ConfigurationProperties(prefix = "app.notification")
@Data
public class NotificationProperties {

    /**
     * 已读通知保留天数；到期后由定时任务物理删除
     */
    private int retentionDays = 90;

    /**
     * 单用户未读上限：超过时把最旧的未读自动置为已读，避免红点永远消不掉
     */
    private int maxUnread = 200;

    /**
     * 单批清理条数
     */
    private int purgeBatchSize = 1000;
}
