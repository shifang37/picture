package com.tzy.picturebackend.config;

import com.tzy.picturebackend.model.enums.MachineReviewModeEnum;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 机审配置。
 * <p>
 * 默认关闭：关闭时不会发起任何数据万象审核调用，上传与人工审核流程与接入前一致。
 */
@Component
@ConfigurationProperties(prefix = "app.review.machine")
@Data
public class MachineReviewProperties {

    /**
     * 是否启用机审
     */
    private boolean enabled = false;

    /**
     * 机审结果效力：assist（默认）| auto-reject | auto
     */
    private MachineReviewModeEnum mode = MachineReviewModeEnum.ASSIST;
}
