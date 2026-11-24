package com.tzy.picturebackend.manager.rag;

/**
 * 向量化调用失败。
 * <p>
 * retryable=true 表示网络异常、429 或 5xx，可以由客户端按退避策略重试；
 * retryable=false 表示鉴权失败、参数错误、维度不合法、超时等，重试没有意义。
 */
public class EmbeddingException extends RuntimeException {

    private final boolean retryable;

    private final long retryAfterMillis;

    private EmbeddingException(String message, boolean retryable, long retryAfterMillis, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.retryAfterMillis = retryAfterMillis;
    }

    public static EmbeddingException retryable(String message) {
        return new EmbeddingException(message, true, 0L, null);
    }

    public static EmbeddingException retryable(String message, long retryAfterMillis) {
        return new EmbeddingException(message, true, retryAfterMillis, null);
    }

    public static EmbeddingException permanent(String message) {
        return new EmbeddingException(message, false, 0L, null);
    }

    public static EmbeddingException permanent(String message, Throwable cause) {
        return new EmbeddingException(message, false, 0L, cause);
    }

    public boolean isRetryable() {
        return retryable;
    }

    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }
}
