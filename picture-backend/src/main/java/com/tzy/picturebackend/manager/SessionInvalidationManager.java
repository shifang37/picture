package com.tzy.picturebackend.manager;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 登录会话失效管理。
 * <p>
 * 密码被修改或重置后，旧会话不能继续访问：每次登录把当前的版本号写入 Session，
 * 修改密码时递增该用户的失效版本；{@code getLoginUser} 发现 Session 版本落后时按未登录处理。
 * <p>
 * 当前部署为单实例，内存版本足够；多实例部署时应替换为 Redis 等共享存储。
 */
@Component
public class SessionInvalidationManager {

    /**
     * 全局递增版本号，保证同一毫秒内发生的登录和改密也能正确比较
     */
    private final AtomicLong version = new AtomicLong();

    /**
     * 用户 id -> 使其旧会话失效的最低版本
     */
    private final Map<Long, Long> invalidatedVersions = new ConcurrentHashMap<>();

    /**
     * 登录时写入 Session 的版本
     */
    public long currentVersion() {
        return version.get();
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
        invalidatedVersions.put(userId, version.incrementAndGet());
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
        Long invalidatedAt = invalidatedVersions.get(userId);
        if (invalidatedAt == null) {
            return false;
        }
        return loginVersion == null || loginVersion < invalidatedAt;
    }

    /**
     * 用户被删除时清理记录，避免 Map 长期保留无效用户
     */
    public void forget(Long userId) {
        if (userId != null) {
            invalidatedVersions.remove(userId);
        }
    }
}
