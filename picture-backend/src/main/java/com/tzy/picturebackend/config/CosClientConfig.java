package com.tzy.picturebackend.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.region.Region;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "cos.client")
@Data
public class CosClientConfig {

    /**
     * 域名
     */
    private String host;

    /**
     * secretId
     */
    private String secretId;

    /**
     * 密钥（注意不要泄露）
     */
    private String secretKey;

    /**
     * 区域
     */
    private String region;

    /**
     * 桶名
     */
    private String bucket;

    /**
     * 防盗链（Referer 白名单）配置；应用启动时自动写入 COS 桶
     */
    private Referer referer = new Referer();

    @Data
    public static class Referer {

        /**
         * 是否在启动时写入防盗链配置；域名列表为空时会自动跳过
         */
        private boolean enabled = true;

        /**
         * 允许的 Referer 域名，例如 example.com、*.example.com，不要带协议和路径
         */
        private List<String> domains = new ArrayList<>();

        /**
         * 是否允许空 Referer（浏览器直接打开图片地址、部分安全软件会去掉 Referer）
         */
        private boolean allowEmptyReferer = false;
    }

    @Bean
    public COSClient cosClient() {
        // 初始化用户身份信息(secretId, secretKey)
        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
        // 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        // 生成cos客户端
        return new COSClient(cred, clientConfig);
    }
}
