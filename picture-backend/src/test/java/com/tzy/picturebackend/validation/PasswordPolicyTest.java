package com.tzy.picturebackend.validation;

import com.tzy.picturebackend.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 密码策略测试：注册、改密、管理员重置共用同一套规则
 */
class PasswordPolicyTest {

    @Test
    @DisplayName("强度校验：字母+数字且长度合规时通过")
    void checkFormat_acceptsStrongPassword() {
        assertNull(PasswordPolicy.checkFormat("Abcdef12"));
        assertNull(PasswordPolicy.checkFormat("a1b2c3d4"));
        assertNull(PasswordPolicy.checkFormat("密码abc123"));
    }

    @Test
    @DisplayName("强度校验：少于 8 位")
    void checkFormat_rejectsShortPassword() {
        assertEquals("密码长度不能少于 8 位", PasswordPolicy.checkFormat("Abc123"));
    }

    @Test
    @DisplayName("强度校验：超过 64 位")
    void checkFormat_rejectsTooLongPassword() {
        assertEquals("密码长度不能超过 64 位", PasswordPolicy.checkFormat("a1".repeat(33)));
    }

    @Test
    @DisplayName("强度校验：BCrypt 上限按字节计算，长中文密码应被拒绝")
    void checkFormat_rejectsPasswordOverBcryptByteLimit() {
        assertEquals("密码不能超过 72 字节", PasswordPolicy.checkFormat("中".repeat(30) + "12"));
    }

    @Test
    @DisplayName("强度校验：必须同时包含字母和数字")
    void checkFormat_requiresLetterAndDigit() {
        assertEquals("密码必须同时包含字母和数字", PasswordPolicy.checkFormat("12345678"));
        assertEquals("密码必须同时包含字母和数字", PasswordPolicy.checkFormat("abcdefgh"));
    }

    @Test
    @DisplayName("强度校验：常见弱口令即使包含字母数字也拒绝")
    void checkFormat_rejectsCommonPassword() {
        assertEquals("密码过于简单，请更换更复杂的密码", PasswordPolicy.checkFormat("password1"));
    }

    @Test
    @DisplayName("强度校验：不允许空白字符")
    void checkFormat_rejectsWhitespace() {
        assertEquals("密码不能包含空白字符", PasswordPolicy.checkFormat("Abcdef 12"));
    }

    @Test
    @DisplayName("账号关系：密码不能与账号相同或包含账号（忽略大小写和首尾空格）")
    void validateAgainstAccount_rejectsAccountRelatedPassword() {
        BusinessException same = assertThrows(BusinessException.class,
                () -> PasswordPolicy.validateAgainstAccount("Tzyy1234", " tzyy1234 "));
        assertEquals("密码不能与账号相同", same.getMessage());

        BusinessException contains = assertThrows(BusinessException.class,
                () -> PasswordPolicy.validateAgainstAccount("Tzyy1234", "tzyy"));
        assertEquals("密码不能包含账号", contains.getMessage());

        assertDoesNotThrow(() -> PasswordPolicy.validateAgainstAccount("Abcdef12", "tzyy"));
    }

    @Test
    @DisplayName("临时密码：12 位且满足强度要求，每次生成都不同")
    void generateTemporaryPassword_isStrongAndRandom() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String password = PasswordPolicy.generateTemporaryPassword();
            assertEquals(12, password.length());
            assertNull(PasswordPolicy.checkFormat(password), "临时密码必须满足强度规则: " + password);
            generated.add(password);
        }
        assertEquals(200, generated.size(), "临时密码必须使用安全随机数生成");
        assertNotEquals(PasswordPolicy.generateTemporaryPassword(),
                PasswordPolicy.generateTemporaryPassword());
    }
}
