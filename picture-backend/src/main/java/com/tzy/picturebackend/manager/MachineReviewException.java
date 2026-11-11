package com.tzy.picturebackend.manager;

/**
 * 机审调用或结果解析失败。
 * <p>
 * 消费者捕获后会重新抛出，让 RabbitMQ 按配置重试；重试耗尽进入死信队列，
 * 同时兜底任务仍会重新发布机审字段为空的图片。
 */
public class MachineReviewException extends RuntimeException {

    public MachineReviewException(String message) {
        super(message);
    }

    public MachineReviewException(String message, Throwable cause) {
        super(message, cause);
    }
}
