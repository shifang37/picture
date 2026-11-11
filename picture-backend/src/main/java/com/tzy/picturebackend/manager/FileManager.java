package com.tzy.picturebackend.manager;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.tzy.picturebackend.config.CosClientConfig;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.model.dto.file.UploadPictureResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
public class FileManager {

    /**
     * 单张图片大小上限：2MB
     */
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024L;

    /**
     * 允许上传的图片后缀
     */
    private static final List<String> ALLOW_FORMAT_LIST = List.of("jpeg", "jpg", "png", "webp");

    /**
     * 允许上传的图片 MIME 类型
     */
    private static final Set<String> ALLOW_CONTENT_TYPE_LIST =
            Set.of("image/jpeg", "image/jpg", "image/png", "image/webp");

    /**
     * 原图长边上限：超过时等比缩小
     */
    static final int MAX_DIMENSION = 1920;

    /**
     * JPEG 压缩质量
     */
    static final double JPEG_QUALITY = 0.85;

    /**
     * JPEG 超过该体积时即便尺寸不大也重新编码压缩
     */
    static final long JPEG_COMPRESS_THRESHOLD = (long) (1.5 * 1024 * 1024);

    /**
     * 单张图片最大像素数：防止解压炸弹在压缩阶段耗尽内存
     */
    static final long MAX_PIXELS = 40_000_000L;

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private CosManager cosManager;

    /**
     * 上传图片
     *
     * @param multipartFile    文件
     * @param uploadPathPrefix 上传路径前缀
     * @return
     */
    public UploadPictureResult uploadPicture(MultipartFile multipartFile, String uploadPathPrefix) {
        // 校验图片
        validPicture(multipartFile);
        // 图片上传地址
        String uuid = RandomUtil.randomString(16);
        String originFilename = multipartFile.getOriginalFilename();
        String uploadFilename = String.format("%s_%s.%s", DateUtil.formatDate(new Date()),uuid,
                FileUtil.getSuffix(originFilename));
        // 注意：这里的路径不能以 "/" 开头，否则拼接 host 后会生成 "https://xxx//public/..." 的双斜杠地址
        String uploadPath = String.format("%s/%s", uploadPathPrefix, uploadFilename);
        File file = null;
        File compressedFile = null;
        try {
            // 创建临时文件
            file =File.createTempFile(uploadPath, null);
            multipartFile.transferTo(file);
            // 上传前按需压缩原图；WebP 没有 JDK 内置编码器，保持原文件
            String suffix = FileUtil.getSuffix(originFilename).toLowerCase(Locale.ROOT);
            File uploadFile = compressIfNeeded(file, suffix);
            if (!uploadFile.equals(file)) {
                compressedFile = uploadFile;
            }
            // 上传图片
            PutObjectResult putObjectResult = cosManager.putPictureObject(uploadPath, uploadFile);
            ImageInfo imageInfo = putObjectResult.getCiUploadResult().getOriginalInfo().getImageInfo();
            // 封装返回结果：宽高/格式以实际上传文件为准，体积使用压缩后的文件大小
            UploadPictureResult uploadPictureResult = new UploadPictureResult();
            int picWidth = imageInfo.getWidth();
            int picHeight = imageInfo.getHeight();
            double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
            uploadPictureResult.setPicName(FileUtil.mainName(originFilename));
            uploadPictureResult.setPicWidth(picWidth);
            uploadPictureResult.setPicHeight(picHeight);
            uploadPictureResult.setPicScale(picScale);
            uploadPictureResult.setPicFormat(imageInfo.getFormat());
            uploadPictureResult.setPicSize(FileUtil.size(uploadFile));
            uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + uploadPath);
            return uploadPictureResult;
        } catch (BusinessException e) {
            // 业务校验异常（例如图片像素过大）需要保留原始错误信息
            throw e;
        } catch (Exception e) {
            log.error("图片上传到存储失败",e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"上传失败");
        } finally {
            this.deleteTempFile(file);
            if (compressedFile != null) {
                this.deleteTempFile(compressedFile);
            }
        }
    }

    /**
     * 按需压缩原图：
     * <ul>
     *   <li>长边超过 {@link #MAX_DIMENSION} 时等比缩小；</li>
     *   <li>JPEG 超过 {@link #JPEG_COMPRESS_THRESHOLD} 时以 0.85 质量重新编码；</li>
     *   <li>PNG 只缩放、保留透明通道；WebP 没有内置编码器，保持原文件；</li>
     *   <li>压缩失败或结果没有变小时回退原文件，不阻断上传。</li>
     * </ul>
     *
     * @param source 原始临时文件
     * @param suffix 文件后缀（小写）
     * @return 压缩后的临时文件；无需压缩或压缩失败时返回原文件
     */
    File compressIfNeeded(File source, String suffix) {
        if (source == null || StrUtil.isBlank(suffix)) {
            return source;
        }
        String normalizedSuffix = suffix.toLowerCase(Locale.ROOT);
        boolean jpeg = "jpg".equals(normalizedSuffix) || "jpeg".equals(normalizedSuffix);
        boolean png = "png".equals(normalizedSuffix);
        if (!jpeg && !png) {
            // WebP 当前 JDK 没有内置编码器，保持原文件
            return source;
        }
        File compressed = null;
        int width;
        int height;
        try {
            // 先只读图片头获取宽高，避免对大尺寸图片直接全量解码
            int[] dimensions = readImageDimensions(source);
            if (dimensions == null) {
                log.warn("无法读取图片尺寸，跳过压缩, suffix = {}", suffix);
                return source;
            }
            width = dimensions[0];
            height = dimensions[1];
        } catch (Exception e) {
            log.warn("读取图片尺寸失败，跳过压缩, suffix = {}", suffix, e);
            return source;
        }
        // 像素超限直接拒绝上传，避免 ImageIO/Thumbnailator 全量解码导致 OOM
        validatePixelCount(width, height);
        try {
            int longSide = Math.max(width, height);
            boolean needResize = longSide > MAX_DIMENSION;
            boolean needQuality = jpeg && source.length() > JPEG_COMPRESS_THRESHOLD;
            if (!needResize && !needQuality) {
                return source;
            }
            compressed = File.createTempFile("picture-compressed-", "." + normalizedSuffix);
            Thumbnails.Builder<File> builder = Thumbnails.of(source);
            if (needResize) {
                double scale = MAX_DIMENSION * 1.0 / longSide;
                builder.size(Math.max(1, (int) Math.round(width * scale)),
                        Math.max(1, (int) Math.round(height * scale)));
            } else {
                builder.scale(1.0);
            }
            if (jpeg) {
                builder.outputFormat("jpg").outputQuality(JPEG_QUALITY);
            } else {
                builder.outputFormat("png");
            }
            builder.toFile(compressed);
            // 仅因体积触发压缩时，压缩结果反而更大就回退原文件
            if (!needResize && compressed.length() >= source.length()) {
                deleteTempFile(compressed);
                return source;
            }
            log.info("原图已压缩, suffix = {}, 原大小 = {}, 压缩后 = {}",
                    suffix, source.length(), compressed.length());
            return compressed;
        } catch (Exception e) {
            // 压缩是优化手段，失败不能阻断上传
            log.warn("压缩图片失败，回退原文件, suffix = {}", suffix, e);
            if (compressed != null) {
                deleteTempFile(compressed);
            }
            return source;
        }
    }

    /**
     * 只读取图片头中的宽高，不进行像素解码
     *
     * @param source 图片文件
     * @return [width, height]，无法识别时返回 null
     */
    private int[] readImageDimensions(File source) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(source)) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * 校验图片像素数，防止高压缩比图片在解码时耗尽内存
     */
    static void validatePixelCount(int width, int height) {
        ThrowUtils.throwIf(width <= 0 || height <= 0, ErrorCode.PARAMS_ERROR, "图片尺寸不合法");
        long pixels = (long) width * height;
        ThrowUtils.throwIf(pixels > MAX_PIXELS, ErrorCode.PARAMS_ERROR,
                "图片像素过大，最多支持 " + (MAX_PIXELS / 10_000) + " 万像素");
    }

    /**
     * 校验文件：空文件、大小、后缀、MIME 类型和文件头都要符合图片要求
     *
     * @param multipartFile multipart 文件
     */
    public void validPicture(MultipartFile multipartFile) {
        ThrowUtils.throwIf(multipartFile == null || multipartFile.isEmpty(), ErrorCode.PARAMS_ERROR, "文件不能为空");
        // 1. 校验文件大小
        ThrowUtils.throwIf(multipartFile.getSize() > MAX_FILE_SIZE, ErrorCode.PARAMS_ERROR, "文件大小不能超过 2M");
        // 2. 校验文件后缀（忽略大小写）
        String originalFilename = multipartFile.getOriginalFilename();
        ThrowUtils.throwIf(StrUtil.isBlank(originalFilename), ErrorCode.PARAMS_ERROR, "文件名不能为空");
        String fileSuffix = FileUtil.getSuffix(originalFilename);
        ThrowUtils.throwIf(StrUtil.isBlank(fileSuffix)
                        || !ALLOW_FORMAT_LIST.contains(fileSuffix.toLowerCase(Locale.ROOT)),
                ErrorCode.PARAMS_ERROR, "文件类型错误");
        // 3. 如果客户端带了 MIME 类型，也要在允许范围内（忽略 charset 等参数）
        String contentType = multipartFile.getContentType();
        if (StrUtil.isNotBlank(contentType)) {
            String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            ThrowUtils.throwIf(!ALLOW_CONTENT_TYPE_LIST.contains(mediaType),
                    ErrorCode.PARAMS_ERROR, "文件类型错误");
        }
        // 4. 校验文件头，防止只改后缀绕过校验
        try (InputStream inputStream = multipartFile.getInputStream()) {
            ThrowUtils.throwIf(!isSupportedImage(inputStream, fileSuffix),
                    ErrorCode.PARAMS_ERROR, "文件内容不是受支持的图片");
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取上传文件失败");
        }
    }

    /**
     * 根据文件头判断是否为受支持的图片
     *
     * @param inputStream 文件流
     * @param fileSuffix  文件后缀
     */
    private boolean isSupportedImage(InputStream inputStream, String fileSuffix) throws IOException {
        // JPEG / PNG 需要 8 字节，WebP 需要 12 字节
        byte[] header = inputStream.readNBytes(12);
        return switch (fileSuffix.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> header.length >= 3
                    && (header[0] & 0xFF) == 0xFF
                    && (header[1] & 0xFF) == 0xD8
                    && (header[2] & 0xFF) == 0xFF;
            case "png" -> header.length >= 8
                    && (header[0] & 0xFF) == 0x89
                    && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                    && (header[4] & 0xFF) == 0x0D && (header[5] & 0xFF) == 0x0A
                    && (header[6] & 0xFF) == 0x1A && (header[7] & 0xFF) == 0x0A;
            case "webp" -> header.length >= 12
                    && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
            default -> false;
        };
    }

    /**
     * 删除图片文件（best-effort）
     * <p>
     * 只有 url 属于当前配置的 COS host 时才会真正删除；删除失败只记录日志，
     * 由调用方决定是否忽略，避免影响数据库操作结果。
     *
     * @param url 图片访问地址
     * @return 是否真正删除了 COS 对象
     */
    public boolean deletePicture(String url) {
        if (StrUtil.isBlank(url)) {
            log.warn("跳过删除图片文件：url 为空");
            return false;
        }
        String key = cosManager.parseObjectKey(url);
        if (StrUtil.isBlank(key)) {
            log.warn("跳过删除图片文件：url 不属于当前存储桶, url = {}", url);
            return false;
        }
        try {
            cosManager.deleteObject(key);
            log.info("已删除图片文件, key = {}", key);
            return true;
        } catch (Exception e) {
            log.error("删除图片文件失败, key = {}", key, e);
            return false;
        }
    }

    /**
     * 删除临时文件
     */
    public void deleteTempFile(File file) {
        if (file == null) {
            return;
        }
        // 删除临时文件
        boolean deleteResult = file.delete();
        if (!deleteResult) {
            log.error("file delete error, filepath = {}", file.getAbsolutePath());
        }
    }

}
