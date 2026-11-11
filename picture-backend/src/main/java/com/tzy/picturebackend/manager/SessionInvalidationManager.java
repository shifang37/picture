package com.tzy.picturebackend.manager;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录会话失效管理（Redis 版）。
 * <p>
 * 密码被修改或重置后，旧会话不能继续访问：每次登录把当前的全局版本号写入 Session，
 * 修改密码时递增全局版本并记录该用户「使其旧会话失效的最低版本」；
 * {@code getLoginUser} 发现 Session 版本落后时按未登录处理。
 * <p>
 * 版本号与限流、会话一起放在 Redis，重启后端后失效记录仍然有效，避免旧会话在重启后「复活」。
 */
@Component
public class SessionInvalidationManager {

    /**
     * 全局递增版本号，保证同一毫秒内发生的登录和改密也能正确比较
     */
    private static final String VERSION_KEY = "picture:session:version";

    /**
     * 用户 id -> 使其旧会话失效的最低版本
     */
    private static final String INVALIDATED_KEY_PREFIX = "picture:session:invalidated:";

    /**
     * 失效记录的保留时间。必须明显长于会话有效期（7 天），否则记录先过期会让旧会话复活。
     */
    private static final Duration INVALIDATED_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;

    public SessionInvalidationManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 登录时写入 Session 的全局版本号
     */
    public long currentVersion() {
        String value = redisTemplate.opsForValue().get(VERSION_KEY);
        return value == null ? 0L : Long.parseLong(value);
    }

    /**
     * 使某个用户的全部已有会话失效
     *
     * @param userId 用户 id
     */
    public void invalidate(Long userId) {
        if (userId == null) {
            return;
        }
        Long version = redisTemplate.opsForValue().increment(VERSION_KEY);
        if (version != null) {
            redisTemplate.opsForValue().set(INVALIDATED_KEY_PREFIX + userId,
                    String.valueOf(version), INVALIDATED_TTL);
        }
    }

    /**
     * 判断 Session 中的登录版本是否已失效
     *
     * @param userId       用户 id
     * @param loginVersion 登录时写入 Session 的版本，旧会话可能为空
     * @return 是否已失效
     */
    public boolean isInvalidated(Long userId, Long loginVersion) {
        if (userId == null) {
            return true;
        }
        String value = redisTemplate.opsForValue().get(INVALIDATED_KEY_PREFIX + userId);
        if (value == null) {
            return false;
        }
        long invalidatedAt = Long.parseLong(value);
        return loginVersion == null || loginVersion < invalidatedAt;
    }

    /**
     * 用户被删除时清理记录
     */
    public void forget(Long userId) {
        if (userId != null) {
            redisTemplate.delete(INVALIDATED_KEY_PREFIX + userId);
        }
    }
}
