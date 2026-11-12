package com.tzy.picturebackend.config;

import com.qcloud.cos.model.BucketRefererConfiguration;
import com.tzy.picturebackend.manager.CosManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * COS 防盗链启动配置测试：域名列表为空时跳过，配置齐全时写入白名单
 */
class CosRefererConfigurerTest {

    private CosManager cosManager;

    private CosClientConfig cosClientConfig;

    private CosRefererConfigurer configurer;

    @BeforeEach
    void setUp() {
        cosManager = mock(CosManager.class);
        cosClientConfig = new CosClientConfig();
        cosClientConfig.setBucket("test-1250000000");
        configurer = new CosRefererConfigurer(cosManager, cosClientConfig);
    }

    @Test
    @DisplayName("防盗链：配置域名后写入白名单，默认拒绝空 Referer")
    void run_writesWhitelist() {
        cosClientConfig.getReferer().setDomains(List.of("example.com", " *.example.com "));

        configurer.run(null);

        ArgumentCaptor<BucketRefererConfiguration> captor =
                ArgumentCaptor.forClass(BucketRefererConfiguration.class);
        verify(cosManager).setBucketReferer(captor.capture());
        BucketRefererConfiguration configuration = captor.getValue();
        assertEquals(BucketRefererConfiguration.ENABLED, configuration.getStatus());
        assertEquals(BucketRefererConfiguration.WHITELIST, configuration.getRefererType());
        assertEquals(List.of("example.com", "*.example.com"), configuration.getDomainList());
        assertEquals(BucketRefererConfiguration.DENY, configuration.getEmptyReferConfiguration());
    }

    @Test
    @DisplayName("防盗链：允许空 Referer 时写入 ALLOW")
    void run_allowsEmptyRefererWhenConfigured() {
        cosClientConfig.getReferer().setDomains(List.of("example.com"));
        cosClientConfig.getReferer().setAllowEmptyReferer(true);

        configurer.run(null);

        ArgumentCaptor<BucketRefererConfiguration> captor =
                ArgumentCaptor.forClass(BucketRefererConfiguration.class);
        verify(cosManager).setBucketReferer(captor.capture());
        assertEquals(BucketRefererConfiguration.ALLOW, captor.getValue().getEmptyReferConfiguration());
    }

    @Test
    @DisplayName("防盗链：未配置域名时跳过，避免把桶改成全部拒绝")
    void run_skipsWhenDomainsEmpty() {
        cosClientConfig.getReferer().setDomains(List.of());

        configurer.run(null);

        verifyNoInteractions(cosManager);
    }

    @Test
    @DisplayName("防盗链：关闭开关后不访问 COS")
    void run_skipsWhenDisabled() {
        cosClientConfig.getReferer().setEnabled(false);
        cosClientConfig.getReferer().setDomains(List.of("example.com"));

        configurer.run(null);

        verify(cosManager, never()).setBucketReferer(any());
    }
}
