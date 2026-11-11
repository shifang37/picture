package com.tzy.picturebackend.manager;

/**
 * AI 建议调用失败。
 * <p>
 * retryable=true 表示网络异常、429 或 5xx，可以由上层按退避策略重试；
 * retryable=false 表示模型拒答、输出无法解析、鉴权失败等，重试没有意义。
 */
public class AiSuggestionException extends RuntimeException {

    private final boolean retryable;

    private final long retryAfterMillis;

    private AiSuggestionException(String message, boolean retryable, long retryAfterMillis, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.retryAfterMillis = retryAfterMillis;
    }

    public static AiSuggestionException retryable(String message) {
        return new AiSuggestionException(message, true, 0L, null);
    }

    public static AiSuggestionException retryable(String message, long retryAfterMillis) {
        return new AiSuggestionException(message, true, retryAfterMillis, null);
    }

    public static AiSuggestionException permanent(String message) {
        return new AiSuggestionException(message, false, 0L, null);
    }

    public static AiSuggestionException permanent(String message, Throwable cause) {
        return new AiSuggestionException(message, false, 0L, cause);
    }

    public boolean isRetryable() {
        return retryable;
    }

    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }
}
