package com.tzy.picturebackend.manager;

import com.tzy.picturebackend.config.CosClientConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * CosManager 对象键解析测试：只允许删除当前桶内的对象，避免误删历史桶或 CDN 资源
 */
class CosManagerTest {

    private static final String HOST = "https://shifang-1410438387.cos.ap-beijing.myqcloud.com";

    private CosManager cosManager;

    private CosClientConfig cosClientConfig;

    @BeforeEach
    void setUp() {
        cosClientConfig = new CosClientConfig();
        cosClientConfig.setHost(HOST);
        cosManager = new CosManager();
        ReflectionTestUtils.setField(cosManager, "cosClientConfig", cosClientConfig);
    }

    @Test
    @DisplayName("解析：本桶地址返回对象键")
    void parseObjectKey_returnsKeyForOwnHost() {
        assertEquals("public/100/2026-11-11_abc.jpg",
                cosManager.parseObjectKey(HOST + "/public/100/2026-11-11_abc.jpg"));
    }

    @Test
    @DisplayName("解析：忽略 query 参数（图片处理、防盗链参数）")
    void parseObjectKey_stripsQuery() {
        assertEquals("public/100/a.jpg",
                cosManager.parseObjectKey(HOST + "/public/100/a.jpg?imageMogr2/thumbnail/200x"));
    }

    @Test
    @DisplayName("解析：忽略 fragment")
    void parseObjectKey_stripsFragment() {
        assertEquals("public/100/a.jpg", cosManager.parseObjectKey(HOST + "/public/100/a.jpg#preview"));
    }

    @Test
    @DisplayName("解析：host 配置带结尾斜杠时仍然可以解析")
    void parseObjectKey_acceptsHostWithTrailingSlash() {
        cosClientConfig.setHost(HOST + "/");

        assertEquals("public/100/a.jpg", cosManager.parseObjectKey(HOST + "/public/100/a.jpg"));
    }

    @Test
    @DisplayName("解析：其他域名返回 null，不能误删其他桶")
    void parseObjectKey_rejectsOtherHost() {
        assertNull(cosManager.parseObjectKey("https://other-bucket.cos.ap-beijing.myqcloud.com/public/a.jpg"));
        assertNull(cosManager.parseObjectKey("https://example.com/public/a.jpg"));
    }

    @Test
    @DisplayName("解析：相似前缀的恶意域名不能通过")
    void parseObjectKey_rejectsLookalikeHost() {
        assertNull(cosManager.parseObjectKey(HOST + ".evil.com/public/a.jpg"));
        assertNull(cosManager.parseObjectKey("https://evil-shifang-1410438387.cos.ap-beijing.myqcloud.com/a.jpg"));
    }

    @Test
    @DisplayName("解析：空地址、空 host、纯 host、空 key 均返回 null")
    void parseObjectKey_returnsNullForInvalidInput() {
        assertNull(cosManager.parseObjectKey(null));
        assertNull(cosManager.parseObjectKey(""));
        assertNull(cosManager.parseObjectKey("   "));
        assertNull(cosManager.parseObjectKey(HOST));
        assertNull(cosManager.parseObjectKey(HOST + "/"));
        assertNull(cosManager.parseObjectKey(HOST + "/?imageMogr2/thumbnail/200x"));

        cosClientConfig.setHost(null);
        assertNull(cosManager.parseObjectKey(HOST + "/public/a.jpg"));
    }

    @Test
    @DisplayName("解析：host 比较忽略大小写，key 保持原样")
    void parseObjectKey_isCaseInsensitiveForHostOnly() {
        assertEquals("Public/100/ABC.jpg",
                cosManager.parseObjectKey("HTTPS://SHIFANG-1410438387.COS.AP-BEIJING.MYQCLOUD.COM/Public/100/ABC.jpg"));
    }

    @Test
    @DisplayName("解析：非本桶协议（http）不会被当作本桶")
    void parseObjectKey_rejectsDifferentScheme() {
        assertNull(cosManager.parseObjectKey("http://shifang-1410438387.cos.ap-beijing.myqcloud.com/public/a.jpg"));
    }
}
