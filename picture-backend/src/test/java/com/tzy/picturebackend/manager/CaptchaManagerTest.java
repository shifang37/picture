package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.model.vo.CaptchaVO;
import com.tzy.picturebackend.support.InMemoryRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图形验证码测试：答案写入 Redis、一次性消费、忽略大小写
 */
class CaptchaManagerTest {

    private static final String KEY_PREFIX = "picture:captcha:";

    private Map<String, String> store;

    private CaptchaManager captchaManager;

    @BeforeEach
    void setUp() {
        store = new ConcurrentHashMap<>();
        StringRedisTemplate redisTemplate = InMemoryRedis.createTemplate(store);
        captchaManager = new CaptchaManager(redisTemplate);
    }

    @Test
    @DisplayName("生成：返回 captchaId 与 data URL 图片，并把 4 位答案写入 Redis")
    void generate_storesAnswer() {
        CaptchaVO captcha = captchaManager.generate();

        assertNotNull(captcha.getCaptchaId());
        assertTrue(captcha.getImage().startsWith("data:image/png;base64,"));
        assertEquals(300, captcha.getExpiresInSeconds());

        String code = store.get(KEY_PREFIX + captcha.getCaptchaId());
        assertNotNull(code, "验证码答案应写入 Redis");
        assertEquals(4, code.length());
    }

    @Test
    @DisplayName("校验：忽略大小写，校验成功后立即删除，不能重复使用")
    void verify_isCaseInsensitiveAndSingleUse() {
        CaptchaVO captcha = captchaManager.generate();
        String code = store.get(KEY_PREFIX + captcha.getCaptchaId());

        captchaManager.verify(captcha.getCaptchaId(), code.toUpperCase());
        assertNull(store.get(KEY_PREFIX + captcha.getCaptchaId()), "验证码应被消费");

        BusinessException e = assertThrows(BusinessException.class,
                () -> captchaManager.verify(captcha.getCaptchaId(), code));
        assertEquals("验证码错误或已过期", e.getMessage());
    }

    @Test
    @DisplayName("校验：答案错误时同样消费验证码，避免同一验证码被反复尝试")
    void verify_consumesOnWrongCode() {
        CaptchaVO captcha = captchaManager.generate();
        String code = store.get(KEY_PREFIX + captcha.getCaptchaId());

        BusinessException e = assertThrows(BusinessException.class,
                () -> captchaManager.verify(captcha.getCaptchaId(), "xxxx"));
        assertEquals("验证码错误或已过期", e.getMessage());

        // 即使之后输入正确答案也已失效
        assertThrows(BusinessException.class,
                () -> captchaManager.verify(captcha.getCaptchaId(), code));
    }

    @Test
    @DisplayName("校验：空参数返回明确提示")
    void verify_rejectsBlankParams() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> captchaManager.verify(" ", "abcd"));
        assertEquals("验证码不能为空", e.getMessage());
    }
}
