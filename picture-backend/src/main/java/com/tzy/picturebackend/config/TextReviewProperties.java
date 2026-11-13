package com.tzy.picturebackend.config;

import com.tzy.picturebackend.model.enums.TextReviewModeEnum;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文本审核配置（图片名称、简介）。
 * <p>
 * 默认关闭：关闭时不发起任何数据万象调用，上传与编辑行为与接入前一致。
 */
@Component
@ConfigurationProperties(prefix = "app.review.text")
@Data
public class TextReviewProperties {

    /**
     * 是否启用文本审核
     */
    private boolean enabled = false;

    /**
     * 审核结果效力：assist（默认，只记录）| auto-reject（违规自动拒绝）
     */
    private TextReviewModeEnum mode = TextReviewModeEnum.ASSIST;

    /**
     * 数据万象控制台配置的审核策略 ID；为空时使用默认策略
     */
    private String bizType = "";

    /**
     * 轮询任务状态的间隔（毫秒）
     */
    private long pollIntervalMs = 500;

    /**
     * 等待审核任务完成的最长时间（毫秒）
     */
    private long pollTimeoutMs = 8000;

    /**
     * 单次提交审核的文本上限（字符），超出部分截断
     */
    private int maxInputLength = 2000;
}
