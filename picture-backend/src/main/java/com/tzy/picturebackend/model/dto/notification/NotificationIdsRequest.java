package com.tzy.picturebackend.model.dto.notification;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 通知 id 集合请求：标记已读、删除共用。
 * <p>
 * 单次最多 100 条，避免一个请求触发超长 IN 条件；混入他人的 id 会被忽略而不是报错，
 * 防止通过错误信息枚举他人的通知是否存在。
 */
@Data
public class NotificationIdsRequest implements Serializable {

    /**
     * 通知 id 列表
     */
    @NotEmpty(message = "请选择通知")
    @Size(max = 100, message = "单次最多操作 100 条通知")
    private List<@Positive(message = "通知 id 必须为正数") Long> ids;

    private static final long serialVersionUID = 1L;
}
