package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.config.UploadProperties;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解码并发闸门测试：验证许可数、等待超时与释放行为
 */
class ImageDecodeLimiterTest {

    @Test
    @DisplayName("并发解码：许可耗尽时等待超时返回 42900，释放后可以继续获取")
    void acquire_limitsConcurrentDecodes() throws Exception {
        UploadProperties properties = new UploadProperties();
        properties.setMaxConcurrentDecodes(1);
        properties.setDecodeWaitMillis(50);
        ImageDecodeLimiter limiter = new ImageDecodeLimiter(properties);

        limiter.acquire();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<BusinessException> blocked = executor.submit(() -> {
                try {
                    limiter.acquire();
                    limiter.release();
                    return null;
                } catch (BusinessException e) {
                    return e;
                }
            });
            BusinessException e = blocked.get(5, TimeUnit.SECONDS);
            assertTrue(e != null, "第二个解码请求应等待超时");
            assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), e.getCode());
        } finally {
            limiter.release();
            executor.shutdownNow();
        }

        assertDoesNotThrow(limiter::acquire);
        limiter.release();
    }

    @Test
    @DisplayName("非法配置：许可数归一为 1，等待时间为负数时立即拒绝第二个请求")
    void constructor_normalizesInvalidConfig() {
        UploadProperties properties = new UploadProperties();
        properties.setMaxConcurrentDecodes(0);
        properties.setDecodeWaitMillis(-1);
        ImageDecodeLimiter limiter = new ImageDecodeLimiter(properties);

        limiter.acquire();
        try {
            BusinessException e = assertThrows(BusinessException.class, limiter::acquire);
            assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), e.getCode());
        } finally {
            limiter.release();
        }
        assertDoesNotThrow(limiter::acquire);
        limiter.release();
    }
}
