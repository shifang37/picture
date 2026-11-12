package com.tzy.picturebackend.model.dto.user;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理员调整用户配额：只传需要修改的项，不传表示保持不变。
 */
@Data
public class UserQuotaUpdateRequest {

    /**
     * 目标用户 id
     */
    @NotNull(message = "用户 id 不能为空")
    private Long userId;

    /**
     * 存储配额（字节）；不传表示不修改
     */
    @Min(value = 0, message = "存储配额不能小于 0")
    private Long storageQuota;

    /**
     * 图片数量上限；不传表示不修改
     */
    @Min(value = 0, message = "图片数量上限不能小于 0")
    @Max(value = 1_000_000, message = "图片数量上限过大")
    private Integer pictureQuota;
}
