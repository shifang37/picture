package com.tzy.picturebackend.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话失效版本管理器测试
 */
class SessionInvalidationManagerTest {

    private SessionInvalidationManager manager;

    @BeforeEach
    void setUp() {
        manager = new SessionInvalidationManager();
    }

    @Test
    @DisplayName("未修改密码时，任意登录版本都有效")
    void isInvalidated_falseWithoutInvalidation() {
        assertFalse(manager.isInvalidated(1L, null));
        assertFalse(manager.isInvalidated(1L, 0L));
        assertFalse(manager.isInvalidated(1L, manager.currentVersion()));
    }

    @Test
    @DisplayName("密码变更后，旧版本失效，新版本有效")
    void isInvalidated_onlyOldVersions() {
        long oldVersion = manager.currentVersion();
        manager.invalidate(1L);

        assertTrue(manager.isInvalidated(1L, oldVersion));
        assertTrue(manager.isInvalidated(1L, null));
        assertFalse(manager.isInvalidated(1L, manager.currentVersion()));
    }

    @Test
    @DisplayName("不同用户互不影响")
    void isInvalidated_keepsUsersIndependent() {
        long oldVersion = manager.currentVersion();
        manager.invalidate(1L);

        assertTrue(manager.isInvalidated(1L, oldVersion));
        assertFalse(manager.isInvalidated(2L, oldVersion));
    }

    @Test
    @DisplayName("用户被删除后清理版本记录")
    void forget_removesRecord() {
        long oldVersion = manager.currentVersion();
        manager.invalidate(1L);
        assertTrue(manager.isInvalidated(1L, oldVersion));

        manager.forget(1L);

        assertFalse(manager.isInvalidated(1L, oldVersion));
    }
}
