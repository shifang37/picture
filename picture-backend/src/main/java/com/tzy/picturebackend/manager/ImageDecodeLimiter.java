package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.config.UploadProperties;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 图片解码并发闸门。
 * <p>
 * 大图解码会一次性占用几十 MB 堆内存，而后端容器内存上限只有 768MB；上传接口必须限制
 * 同时解码的图片数量，否则并发上传会直接 OOM。这里用公平信号量控制进入解码/缩放阶段的
 * 请求数，等待超时返回 42900，而不是让请求线程无限排队。
 */
@Slf4j
@Component
public class ImageDecodeLimiter {

    private final Semaphore semaphore;

    private final long waitMillis;

    public ImageDecodeLimiter(UploadProperties uploadProperties) {
        int permits = Math.max(1, uploadProperties.getMaxConcurrentDecodes());
        this.semaphore = new Semaphore(permits, true);
        this.waitMillis = Math.max(0L, uploadProperties.getDecodeWaitMillis());
    }

    /**
     * 获取解码许可，等待超过配置时间时抛出 42900
     */
    public void acquire() {
        try {
            if (semaphore.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片处理被中断");
        }
        log.warn("图片解码并发已达上限，拒绝本次上传");
        throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "图片处理繁忙，请稍后重试");
    }

    /**
     * 释放解码许可，必须与 {@link #acquire()} 成对调用
     */
    public void release() {
        semaphore.release();
    }
}
