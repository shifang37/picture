package com.tzy.picturebackend.model.dto.user;

import lombok.Data;

/**
 * 管理员重算配额：userId 为空表示全量分批重算。
 */
@Data
public class UserQuotaRecalcRequest {

    /**
     * 目标用户 id；为空表示全量重算
     */
    private Long userId;
}
