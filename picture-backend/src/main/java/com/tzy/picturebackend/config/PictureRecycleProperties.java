package com.tzy.picturebackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图片回收站配置。
 * <p>
 * 删除图片时只做逻辑删除并记录删除时间；超过保留期后由定时任务物理删除数据库行与 COS 对象。
 * 审核日志不随图片删除，按合规要求长期保留。
 */
@Component
@ConfigurationProperties(prefix = "app.recycle")
@Data
public class PictureRecycleProperties {

    /**
     * 回收站保留天数；到期后物理删除，默认 7 天
     */
    private int retentionDays = 7;

    /**
     * 是否启用到期物理清理；关闭后图片会一直留在回收站
     */
    private boolean purgeEnabled = true;

    /**
     * 单批清理条数，避免一次清理占用过多数据库连接与 COS 请求
     */
    private int purgeBatchSize = 100;
}
