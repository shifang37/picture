package com.tzy.picturebackend.manager;

import cn.hutool.core.util.StrUtil;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.BucketRefererConfiguration;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.auditing.ImageAuditingRequest;
import com.qcloud.cos.model.ciModel.auditing.ImageAuditingResponse;
import com.qcloud.cos.model.ciModel.auditing.TextAuditingRequest;
import com.qcloud.cos.model.ciModel.auditing.TextAuditingResponse;
import com.qcloud.cos.model.ciModel.persistence.PicOperations;
import com.tzy.picturebackend.config.CosClientConfig;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class CosManager {
    @Resource
    private COSClient cosClient;

    @Resource
    private CosClientConfig cosClientConfig;

    public PutObjectResult putObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(),key,file);
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 下载对象
     *
     * @param key 唯一键
     */
    public COSObject getObject(String key) {
        GetObjectRequest getObjectRequest = new GetObjectRequest(cosClientConfig.getBucket(), key);
        return cosClient.getObject(getObjectRequest);
    }

    /**
     * 上传对象（附带图片信息）
     *
     * @param key  唯一键
     * @param file 文件
     */
    public PutObjectResult putPictureObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key,
                file);
        // 对图片进行处理（获取基本信息也被视作为一种处理）
        PicOperations picOperations = new PicOperations();
        // 1 表示返回原图信息
        picOperations.setIsPicInfo(1);
        // 构造处理参数
        putObjectRequest.setPicOperations(picOperations);
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 调用数据万象单次图片审核（同步）。
     * <p>
     * 不传 bizType 时使用控制台配置的默认审核策略。调用方负责处理审核失败与重试，
     * 这里只做 SDK 请求封装。
     *
     * @param key COS 对象键
     * @return 审核响应
     */
    public ImageAuditingResponse auditImage(String key) {
        ImageAuditingRequest request = new ImageAuditingRequest();
        request.setBucketName(cosClientConfig.getBucket());
        request.setObjectKey(key);
        return cosClient.imageAuditing(request);
    }

    /**
     * 创建数据万象文字审核任务（异步）。
     * <p>
     * 返回的 JobsDetail 里带 JobId，调用方需要再调用 {@link #describeTextAuditingJob(String)} 轮询结果。
     *
     * @param content 已按接口要求做 Base64 编码的文本内容
     * @param bizType 审核策略 ID，为空时使用控制台默认策略
     * @return 创建任务响应
     */
    public TextAuditingResponse createTextAuditingJob(String content, String bizType) {
        TextAuditingRequest request = new TextAuditingRequest();
        request.setBucketName(cosClientConfig.getBucket());
        request.getInput().setContent(content);
        if (StrUtil.isNotBlank(bizType)) {
            request.getConf().setBizType(bizType.trim());
        }
        return cosClient.createAuditingTextJobs(request);
    }

    /**
     * 查询数据万象文字审核任务状态与结果。
     *
     * @param jobId 任务 ID
     * @return 查询响应
     */
    public TextAuditingResponse describeTextAuditingJob(String jobId) {
        TextAuditingRequest request = new TextAuditingRequest();
        request.setBucketName(cosClientConfig.getBucket());
        request.setJobId(jobId);
        return cosClient.describeAuditingTextJob(request);
    }

    /**
     * 删除对象
     *
     * @param key 唯一键
     */
    public void deleteObject(String key) {
        cosClient.deleteObject(cosClientConfig.getBucket(), key);
    }

    /**
     * 设置桶的防盗链配置（Referer 白名单）。
     * <p>
     * 仓库默认在应用启动时按配置自动写入，避免只依赖控制台手工配置；
     * 配置失败只记录日志，不影响应用启动和图片读写。
     *
     * @param configuration 防盗链配置
     */
    public void setBucketReferer(BucketRefererConfiguration configuration) {
        cosClient.setBucketRefererConfiguration(cosClientConfig.getBucket(), configuration);
    }

    /**
     * 从图片访问地址中解析出 COS 对象键
     * <p>
     * 只有地址以当前配置的 host 开头时才认为是本桶对象；其他域名、空地址一律返回 null，
     * 避免误删历史桶、CDN 或其他站点的对象。
     *
     * @param url 图片访问地址
     * @return 对象键；无法解析时返回 null
     */
    public String parseObjectKey(String url) {
        if (StrUtil.isBlank(url) || cosClientConfig == null || StrUtil.isBlank(cosClientConfig.getHost())) {
            return null;
        }
        // 配置项可能带结尾斜杠，统一去掉后再比较
        String host = StrUtil.removeSuffix(cosClientConfig.getHost().trim(), "/");
        if (StrUtil.isBlank(host)) {
            return null;
        }
        // 去掉 query 和 fragment，例如图片处理参数、防盗链参数
        String cleanUrl = url.trim();
        int end = cleanUrl.length();
        int queryIndex = cleanUrl.indexOf('?');
        if (queryIndex >= 0) {
            end = queryIndex;
        }
        int fragmentIndex = cleanUrl.indexOf('#');
        if (fragmentIndex >= 0 && fragmentIndex < end) {
            end = fragmentIndex;
        }
        cleanUrl = cleanUrl.substring(0, end);
        // host 比较忽略大小写，但必须完整匹配，防止 host.evil.com 之类的地址通过
        if (cleanUrl.length() <= host.length() || !cleanUrl.regionMatches(true, 0, host, 0, host.length())) {
            return null;
        }
        if (cleanUrl.charAt(host.length()) != '/') {
            return null;
        }
        String key = cleanUrl.substring(host.length() + 1);
        return StrUtil.isBlank(key) ? null : key;
    }

}
