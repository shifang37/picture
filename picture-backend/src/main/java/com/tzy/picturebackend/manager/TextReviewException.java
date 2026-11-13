package com.tzy.picturebackend.manager;

/**
 * 文本审核调用异常：任务创建失败、任务失败、超时或响应无法识别。
 * <p>
 * 消费者捕获后交由 RabbitMQ 重试，重试耗尽进入死信队列。
 */
public class TextReviewException extends RuntimeException {

    public TextReviewException(String message) {
        super(message);
    }

    public TextReviewException(String message, Throwable cause) {
        super(message, cause);
    }
}
