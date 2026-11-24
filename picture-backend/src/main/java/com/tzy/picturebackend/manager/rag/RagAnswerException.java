package com.tzy.picturebackend.manager.rag;

/**
 * 问答生成失败。
 * <p>
 * 与 {@code AiSuggestionException} 同风格：区分可重试（429 / 5xx / 网络）与永久错误
 * （Key 缺失、响应结构不合法）。M5 的问答链路不做自动重试——25s 超时下重试会把
 * 响应时间推到不可接受；失败直接降级为固定文案，由用户重问触发新的调用。
 */
public class RagAnswerException extends RuntimeException {

    /**
     * 是否属于可重试错误（当前只用于日志与指标分类，不触发自动重试）
     */
    private final boolean retryable;

    private RagAnswerException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public static RagAnswerException retryable(String message) {
        return new RagAnswerException(message, true, null);
    }

    public static RagAnswerException permanent(String message) {
        return new RagAnswerException(message, false, null);
    }

    public static RagAnswerException permanent(String message, Throwable cause) {
        return new RagAnswerException(message, false, cause);
    }

    public boolean isRetryable() {
        return retryable;
    }

    /**
     * 是否属于超时：用于把降级原因区分为 {@code ask_timeout} 与 {@code ask_error}
     */
    public boolean isTimeout() {
        String message = getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("timeout") || lower.contains("timed out") || message.contains("超时");
    }
}
