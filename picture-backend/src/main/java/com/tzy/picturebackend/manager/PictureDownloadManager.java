package com.tzy.picturebackend.manager;

import cn.hutool.core.util.StrUtil;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ObjectMetadata;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 图片下载代理：由后端从 COS 读取原图并流式写回，成功写完后才记录下载次数。
 * <p>
 * 这样做解决了两个问题：
 * <ul>
 *   <li>下载地址始终是同源的 {@code /api/picture/download/{id}}，浏览器一定按
 *       {@code Content-Disposition: attachment} 保存文件，不依赖 COS 桶的跨域配置；</li>
 *   <li>「下载成功」由后端确认：只有文件字节完整写给客户端后才自增，用户取消、连接中断都不会计数。</li>
 * </ul>
 * 下载计数还会经过 {@link DownloadDedupManager} 按「用户/IP + 图片 + 天」去重，避免重复点击刷高次数。
 */
@Component
@Slf4j
public class PictureDownloadManager {

    private static final String DEFAULT_FILE_NAME = "picture";

    /**
     * Windows 文件名非法字符、控制字符与路径分隔符统一替换，避免下载到本地时保存失败
     */
    private static final Pattern ILLEGAL_FILE_NAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\r\\n\\t]");

    private static final Pattern FILE_EXTENSION = Pattern.compile("[A-Za-z0-9]{1,10}");

    private static final int MAX_FILE_NAME_LENGTH = 120;

    private final PictureService pictureService;

    private final CosManager cosManager;

    private final DownloadDedupManager downloadDedupManager;

    private final UserService userService;

    public PictureDownloadManager(PictureService pictureService,
                                  CosManager cosManager,
                                  DownloadDedupManager downloadDedupManager,
                                  UserService userService) {
        this.pictureService = pictureService;
        this.cosManager = cosManager;
        this.downloadDedupManager = downloadDedupManager;
        this.userService = userService;
    }

    /**
     * 流式下载图片，并在成功写完后记录一次（去重后的）下载。
     *
     * @param id       图片 id
     * @param request  当前请求，用于可见性校验与下载者标识
     * @param response 响应，直接写入图片字节
     * @throws IOException 写响应失败（通常是客户端断开）
     */
    public void download(Long id, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Picture picture = pictureService.getDownloadablePicture(id, request);
        String objectKey = cosManager.parseObjectKey(picture.getUrl());
        if (StrUtil.isBlank(objectKey)) {
            // 历史数据可能指向已经不存在的桶或其他域名，不能拿任意外链做代理
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片文件不存在");
        }

        COSObject cosObject;
        try {
            cosObject = cosManager.getObject(objectKey);
        } catch (CosServiceException e) {
            log.warn("COS 下载失败, pictureId = {}, key = {}, status = {}",
                    id, objectKey, e.getStatusCode());
            if (e.getStatusCode() == HttpServletResponse.SC_NOT_FOUND) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片文件不存在");
            }
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片下载失败");
        } catch (RuntimeException e) {
            log.error("COS 下载异常, pictureId = {}, key = {}", id, objectKey, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片下载失败");
        }
        if (cosObject == null || cosObject.getObjectMetadata() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片文件不存在");
        }

        String downloaderIdentity = downloaderIdentity(request);
        ObjectMetadata metadata = cosObject.getObjectMetadata();
        String fileName = resolveFileName(picture, metadata.getContentType());
        String contentType = resolveContentType(metadata, fileName);

        InputStream inputStream = cosObject.getObjectContent();
        if (inputStream == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片文件不存在");
        }
        // 先写响应头，再开始传输；传输出错时响应已提交，不能再交给全局异常处理
        writeHeaders(response, metadata, contentType, fileName);
        // 关闭 COSObjectInputStream 会同时释放底层 HTTP 连接
        try (InputStream input = inputStream) {
            input.transferTo(response.getOutputStream());
            response.flushBuffer();
        }

        // 走到这里说明图片已经完整写给客户端，可以计数
        recordDownload(id, downloaderIdentity);
    }

    /**
     * 记录下载次数：先做 Redis 去重，只有当天首次下载才自增。
     * <p>
     * 计数失败不能影响已经成功返回的文件，因此这里吞掉异常只记日志。
     */
    private void recordDownload(Long id, String downloaderIdentity) {
        try {
            if (!downloadDedupManager.markIfFirst(downloaderIdentity, id)) {
                return;
            }
            if (!pictureService.incrementDownloadCount(id)) {
                log.warn("下载次数自增未命中记录, pictureId = {}", id);
            }
        } catch (RuntimeException e) {
            log.error("下载成功后记录下载次数失败, pictureId = {}", id, e);
        }
    }

    private String downloaderIdentity(HttpServletRequest request) {
        User loginUser = userService.getLoginUserOrNull(request);
        if (loginUser != null && loginUser.getId() != null) {
            return "user:" + loginUser.getId();
        }
        String remoteAddr = request == null ? null : request.getRemoteAddr();
        return "ip:" + (StrUtil.isBlank(remoteAddr) ? "unknown" : remoteAddr);
    }

    private void writeHeaders(HttpServletResponse response, ObjectMetadata metadata,
                              String contentType, String fileName) {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(contentType);
        long contentLength = metadata.getContentLength();
        if (contentLength > 0) {
            response.setContentLengthLong(contentLength);
        }
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build()
                .toString());
        // 下载接口每次都要经过后端计数，禁止浏览器/代理缓存
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    private String resolveContentType(ObjectMetadata metadata, String fileName) {
        String contentType = metadata.getContentType();
        if (StrUtil.isNotBlank(contentType)) {
            return contentType;
        }
        return MediaTypeFactory.getMediaType(fileName)
                .map(MediaType::toString)
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    /**
     * 生成浏览器保存时使用的文件名：优先图片名称，补上格式后缀，并清掉路径分隔符等非法字符。
     */
    String resolveFileName(Picture picture, String contentType) {
        String name = StrUtil.isBlank(picture.getName())
                ? DEFAULT_FILE_NAME + "-" + picture.getId()
                : picture.getName().trim();
        name = ILLEGAL_FILE_NAME_CHARS.matcher(name).replaceAll("_").trim();
        name = StrUtil.strip(name, ".");
        if (StrUtil.isBlank(name)) {
            name = DEFAULT_FILE_NAME + "-" + picture.getId();
        }
        String extension = resolveExtension(picture, contentType);
        String suffix = StrUtil.isNotBlank(extension) && !hasExtension(name) ? "." + extension : "";
        // 先按后缀预留长度再截断，避免长名称把扩展名截掉
        int maxBaseLength = Math.max(1, MAX_FILE_NAME_LENGTH - suffix.length());
        if (name.length() > maxBaseLength) {
            name = name.substring(0, maxBaseLength);
        }
        return name + suffix;
    }

    private boolean hasExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        return FILE_EXTENSION.matcher(fileName.substring(dotIndex + 1)).matches();
    }

    private String resolveExtension(Picture picture, String contentType) {
        String picFormat = picture.getPicFormat();
        if (StrUtil.isNotBlank(picFormat)) {
            String cleaned = picFormat.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
            if (FILE_EXTENSION.matcher(cleaned).matches()) {
                return "jpeg".equals(cleaned) ? "jpg" : cleaned;
            }
        }
        String fromUrl = extensionFromUrl(picture.getUrl());
        if (StrUtil.isNotBlank(fromUrl)) {
            return fromUrl;
        }
        String subtype = contentType == null ? null : contentType.substring(contentType.indexOf('/') + 1);
        if (StrUtil.isNotBlank(subtype)) {
            int parameterIndex = subtype.indexOf(';');
            if (parameterIndex >= 0) {
                subtype = subtype.substring(0, parameterIndex);
            }
            subtype = subtype.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
            if (FILE_EXTENSION.matcher(subtype).matches()) {
                return "jpeg".equals(subtype) ? "jpg" : subtype;
            }
        }
        return null;
    }

    private String extensionFromUrl(String url) {
        if (StrUtil.isBlank(url)) {
            return null;
        }
        int queryIndex = url.indexOf('?');
        String path = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        int slashIndex = path.lastIndexOf('/');
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex <= slashIndex || dotIndex == path.length() - 1) {
            return null;
        }
        String extension = path.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        return FILE_EXTENSION.matcher(extension).matches()
                ? ("jpeg".equals(extension) ? "jpg" : extension)
                : null;
    }
}
