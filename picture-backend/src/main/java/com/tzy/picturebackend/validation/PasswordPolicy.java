package com.tzy.picturebackend.validation;

import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;

/**
 * 密码策略：注册、修改密码、管理员重置密码共用同一套规则。
 * <p>
 * 密码用 BCrypt 存储，BCrypt 的长度上限是 72 <b>字节</b>而不是字符，
 * 因此中文等多字节字符需要单独按 UTF-8 字节数校验，否则加密时会直接抛异常。
 */
public final class PasswordPolicy {

    /**
     * 密码最小长度（字符数）
     */
    public static final int MIN_LENGTH = 8;

    /**
     * 密码最大长度（字符数）
     */
    public static final int MAX_LENGTH = 64;

    /**
     * BCrypt 能接受的最大字节数
     */
    private static final int BCRYPT_MAX_BYTES = 72;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 临时密码字符集去掉 0/O、1/l/I 等易混淆字符，方便管理员口头或复制转达
     */
    private static final char[] LOWER = "abcdefghijkmnpqrstuvwxyz".toCharArray();
    private static final char[] UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final char[] DIGITS = "23456789".toCharArray();
    private static final char[] ALL =
            "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /**
     * 常见的弱口令，即使同时包含字母和数字也不允许使用
     */
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "12345678", "123456789", "password", "password1", "password123",
            "qwerty123", "abc12345", "admin123", "11111111", "a1234567",
            "iloveyou", "88888888", "66666666", "aa123456", "abc123456");

    private PasswordPolicy() {
    }

    /**
     * 校验密码本身的强度，不依赖账号
     *
     * @param password 明文密码
     * @return 不合规时返回提示信息，合规返回 null
     */
    public static String checkFormat(String password) {
        if (password == null || password.isBlank()) {
            return "密码不能为空";
        }
        int length = password.codePointCount(0, password.length());
        if (length < MIN_LENGTH) {
            return "密码长度不能少于 " + MIN_LENGTH + " 位";
        }
        if (length > MAX_LENGTH) {
            return "密码长度不能超过 " + MAX_LENGTH + " 位";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            return "密码不能超过 " + BCRYPT_MAX_BYTES + " 字节";
        }
        if (password.chars().anyMatch(Character::isWhitespace)) {
            return "密码不能包含空白字符";
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            return "密码必须同时包含字母和数字";
        }
        if (COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            return "密码过于简单，请更换更复杂的密码";
        }
        return null;
    }

    /**
     * 校验密码强度，不合规时抛出参数错误
     */
    public static void validateFormat(String password) {
        String error = checkFormat(password);
        if (error != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, error);
        }
    }

    /**
     * 校验密码与账号的关系，避免「账号 + 123」这类一眼可猜的密码
     */
    public static void validateAgainstAccount(String password, String account) {
        if (password == null || account == null || account.isBlank()) {
            return;
        }
        String normalizedPassword = password.toLowerCase(Locale.ROOT);
        String normalizedAccount = account.trim().toLowerCase(Locale.ROOT);
        if (normalizedPassword.equals(normalizedAccount)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码不能与账号相同");
        }
        if (normalizedAccount.length() >= 4 && normalizedPassword.contains(normalizedAccount)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码不能包含账号");
        }
    }

    /**
     * 完整校验：先校验强度，再校验与账号的关系
     */
    public static void validate(String password, String account) {
        validateFormat(password);
        validateAgainstAccount(password, account);
    }

    /**
     * 生成管理员创建用户时使用的随机临时密码（12 位，必含大小写字母和数字）。
     * 临时密码只返回一次，用户首次登录后必须修改。
     */
    public static String generateTemporaryPassword() {
        char[] chars = new char[12];
        chars[0] = LOWER[RANDOM.nextInt(LOWER.length)];
        chars[1] = UPPER[RANDOM.nextInt(UPPER.length)];
        chars[2] = DIGITS[RANDOM.nextInt(DIGITS.length)];
        for (int i = 3; i < chars.length; i++) {
            chars[i] = ALL[RANDOM.nextInt(ALL.length)];
        }
        // 打乱前三位固定字符的位置，避免结构可预测
        for (int i = chars.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }
        return new String(chars);
    }
}
