package com.tzy.picturebackend.config;

import cn.hutool.core.util.StrUtil;
import com.qcloud.cos.model.BucketRefererConfiguration;
import com.tzy.picturebackend.manager.CosManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时把配置中的 Referer 白名单写入 COS 桶，开启防盗链。
 * <p>
 * 防盗链只约束浏览器对 COS 的匿名读取；后端使用带签名的 SDK 请求下载不经过浏览器的 Referer，
 * 因此开启后不影响「免费下载」的后端代理。若未配置域名（例如本地开发直连 `application-local.yml`
 * 但还没填站点域名），这里会跳过并打印 warn，避免把桶配置成「全部拒绝」导致图片全挂。
 */
@Component
@Slf4j
public class CosRefererConfigurer implements ApplicationRunner {

    private final CosManager cosManager;

    private final CosClientConfig cosClientConfig;

    public CosRefererConfigurer(CosManager cosManager, CosClientConfig cosClientConfig) {
        this.cosManager = cosManager;
        this.cosClientConfig = cosClientConfig;
    }

    @Override
    public void run(ApplicationArguments args) {
        CosClientConfig.Referer referer = cosClientConfig.getReferer();
        if (referer == null || !referer.isEnabled()) {
            return;
        }
        List<String> domains = referer.getDomains() == null
                ? List.of()
                : referer.getDomains().stream()
                        .filter(StrUtil::isNotBlank)
                        .map(String::trim)
                        .distinct()
                        .toList();
        if (domains.isEmpty()) {
            log.warn("COS 防盗链已启用但未配置域名，跳过写入。请设置 cos.client.referer.domains 或 COS_REFERER_DOMAINS");
            return;
        }
        if (StrUtil.isBlank(cosClientConfig.getBucket())) {
            log.warn("COS 防盗链已启用但未配置 bucket，跳过写入");
            return;
        }

        BucketRefererConfiguration configuration = new BucketRefererConfiguration();
        configuration.setStatus(BucketRefererConfiguration.ENABLED);
        configuration.setRefererType(BucketRefererConfiguration.WHITELIST);
        configuration.setDomainList(domains);
        configuration.setEmptyReferConfiguration(referer.isAllowEmptyReferer()
                ? BucketRefererConfiguration.ALLOW
                : BucketRefererConfiguration.DENY);
        try {
            cosManager.setBucketReferer(configuration);
            log.info("COS 防盗链已开启, bucket = {}, domains = {}, allowEmptyReferer = {}",
                    cosClientConfig.getBucket(), domains, referer.isAllowEmptyReferer());
        } catch (RuntimeException e) {
            // 没有 PutBucketReferer 权限、网络不通都不应阻止应用启动；图片读取仍可正常进行
            log.error("COS 防盗链配置失败，应用继续启动，请检查 PutBucketReferer 权限与桶配置", e);
        }
    }
}
