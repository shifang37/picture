package com.tzy.picturebackend.manager;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.tzy.picturebackend.config.CosClientConfig;
import com.tzy.picturebackend.config.UploadProperties;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.dto.file.PreparedUpload;
import com.tzy.picturebackend.model.dto.file.UploadPictureResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
public class FileManager {

    /**
     * 单张图片大小上限的兜底值：10MB。
     * <p>
     * 实际值取 {@link UploadProperties#getMaxFileSizeBytes()}；这里保留兜底是为了让直接
     * {@code new FileManager()} 的单元测试不依赖 Spring 容器。
     */
    static final long DEFAULT_MAX_FILE_SIZE = 10 * 1024 * 1024L;

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
     * 单张图片最大像素数：防止解压炸弹在压缩阶段耗尽内存。
     * <p>
     * 后端容器内存上限只有 768MB（堆约 75%），20MP 的 ARGB 原图一次性解码约 80MB，
     * 再叠加并发请求很容易 OOM，因此上限从 4000 万像素降到 2000 万像素。
     */
    static final long MAX_PIXELS = 20_000_000L;

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private CosManager cosManager;

    @Resource
    private PictureMetrics pictureMetrics;

    @Resource
    private ImageDecodeLimiter imageDecodeLimiter;

    @Resource
    private UploadProperties uploadProperties;

    /**
     * 上传图片（兼容入口）：等价于「准备 + 上传 + 清理临时文件」。
     * <p>
     * 需要秒传/配额判断的调用方请直接使用 {@link #prepareUpload(MultipartFile, String)}
     * 与 {@link #uploadPrepared(PreparedUpload)}，避免在命中秒传时仍然上传对象。
     *
     * @param multipartFile    文件
     * @param uploadPathPrefix 上传路径前缀
     * @return 上传结果
     */
    public UploadPictureResult uploadPicture(MultipartFile multipartFile, String uploadPathPrefix) {
        PreparedUpload prepared = prepareUpload(multipartFile, uploadPathPrefix);
        try {
            return uploadPrepared(prepared);
        } finally {
            prepared.cleanup();
        }
    }

    /**
     * 上传准备：校验、方向纠正、压缩、哈希，全程不调用对象存储。
     *
     * @param multipartFile    文件
     * @param uploadPathPrefix 上传路径前缀，例如 {@code public/1}
     * @return 准备结果（必须由调用方清理临时文件）
     */
    public PreparedUpload prepareUpload(MultipartFile multipartFile, String uploadPathPrefix) {
        // 校验图片
        validPicture(multipartFile);
        // 图片上传地址
        String uuid = RandomUtil.randomString(16);
        String originFilename = multipartFile.getOriginalFilename();
        String suffix = FileUtil.getSuffix(originFilename).toLowerCase(Locale.ROOT);
        String uploadFilename = String.format("%s_%s.%s", DateUtil.formatDate(new Date()), uuid, suffix);
        // 注意：这里的路径不能以 "/" 开头，否则拼接 host 后会生成 "https://xxx//public/..." 的双斜杠地址
        String uploadPath = String.format("%s/%s", uploadPathPrefix, uploadFilename);
        List<File> tempFiles = new ArrayList<>();
        try {
            // 用固定前缀创建临时文件：原实现把上传路径当文件名前缀，遇到路径分隔符会创建失败
            File file = File.createTempFile("picture-upload-", "." + suffix);
            tempFiles.add(file);
            multipartFile.transferTo(file);
            // M4 阶段在压缩前先按 EXIF 方向纠正（见 rotateIfNeeded）
            File uploadFile = compressIfNeeded(file, suffix);
            if (!tempFiles.contains(uploadFile)) {
                tempFiles.add(uploadFile);
            }
            PreparedUpload prepared = new PreparedUpload();
            prepared.setUploadPath(uploadPath);
            prepared.setPicName(FileUtil.mainName(originFilename));
            prepared.setPicSize(FileUtil.size(uploadFile));
            prepared.setFileHash(sha256(uploadFile));
            prepared.setSuffix(suffix);
            prepared.setUploadFile(uploadFile);
            prepared.setTempFiles(tempFiles);
            return prepared;
        } catch (BusinessException e) {
            // 业务校验异常（例如图片像素过大）需要保留原始错误信息
            deleteTempFiles(tempFiles);
            throw e;
        } catch (Exception e) {
            log.error("图片上传准备失败", e);
            deleteTempFiles(tempFiles);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        }
    }

    /**
     * 上传准备结果到对象存储，并解析万象返回的宽高与格式。
     *
     * @param prepared 准备结果
     * @return 上传结果
     */
    public UploadPictureResult uploadPrepared(PreparedUpload prepared) {
        if (prepared == null || prepared.getUploadFile() == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传准备结果为空");
        }
        try {
            PutObjectResult putObjectResult = cosManager.putPictureObject(
                    prepared.getUploadPath(), prepared.getUploadFile());
            ImageInfo imageInfo = putObjectResult.getCiUploadResult().getOriginalInfo().getImageInfo();
            // 封装返回结果：宽高/格式以实际上传文件为准，体积使用最终文件大小
            UploadPictureResult uploadPictureResult = new UploadPictureResult();
            int picWidth = imageInfo.getWidth();
            int picHeight = imageInfo.getHeight();
            uploadPictureResult.setPicName(prepared.getPicName());
            uploadPictureResult.setPicWidth(picWidth);
            uploadPictureResult.setPicHeight(picHeight);
            uploadPictureResult.setPicScale(NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue());
            uploadPictureResult.setPicFormat(imageInfo.getFormat());
            uploadPictureResult.setPicSize(prepared.getPicSize());
            uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + prepared.getUploadPath());
            return uploadPictureResult;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("图片上传到存储失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        }
    }

    /**
     * 计算文件 SHA-256（小写十六进制），用于同一用户的秒传去重
     *
     * @param file 文件
     * @return 64 位十六进制字符串
     */
    static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // 所有受支持的 JDK 都内置 SHA-256，这里只是把受检异常转换成 IO 异常
            throw new IOException("当前运行环境不支持 SHA-256", e);
        }
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 批量删除临时文件（best-effort）
     */
    private void deleteTempFiles(List<File> files) {
        if (files == null) {
            return;
        }
        for (File file : files) {
            deleteTempFile(file);
        }
        files.clear();
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
        return pictureMetrics.compressTimer().record(() -> doCompressIfNeeded(source, suffix));
    }

    /**
     * 压缩的具体实现；计时放在外层，提前返回和异常回退也会被计时器记录
     */
    private File doCompressIfNeeded(File source, String suffix) {
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
        int longSide = Math.max(width, height);
        boolean needResize = longSide > MAX_DIMENSION;
        boolean needQuality = jpeg && source.length() > JPEG_COMPRESS_THRESHOLD;
        if (!needResize && !needQuality) {
            return source;
        }
        // 信号量只包住真正的解码/缩放：并发大图解码是 768MB 容器最容易 OOM 的地方。
        // 等待超时会抛 42900，必须放在压缩失败回退的 try 之外，否则会被当成普通失败吞掉。
        imageDecodeLimiter.acquire();
        try {
            compressed = File.createTempFile("picture-compressed-", "." + normalizedSuffix);
            writeCompressed(source, compressed, width, height, jpeg, needResize);
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
        } finally {
            imageDecodeLimiter.release();
        }
    }

    /**
     * 解码并写出压缩后的图片。
     * <p>
     * 需要缩放时先用 {@link ImageReadParam#setSourceSubsampling} 在解码阶段降采样，
     * 把解码后的像素规模压到与目标尺寸同一量级，再交给 Thumbnailator 缩放到精确尺寸编码。
     * 这样 20MP 的大图不会把完整原图像素读进堆内存。
     *
     * @param source     原始临时文件
     * @param target     压缩输出文件
     * @param width      原图宽
     * @param height     原图高
     * @param jpeg       是否 JPEG
     * @param needResize 是否需要缩小到 {@link #MAX_DIMENSION}
     */
    private void writeCompressed(File source, File target, int width, int height,
                                 boolean jpeg, boolean needResize) throws IOException {
        int longSide = Math.max(width, height);
        BufferedImage decoded = decodeImage(source, needResize ? subsamplingFactor(longSide) : 1);
        if (decoded == null) {
            throw new IOException("无法解码图片");
        }
        Thumbnails.Builder<BufferedImage> builder = Thumbnails.of(decoded);
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
        builder.toFile(target);
    }

    /**
     * 按需在解码阶段降采样读取图片。
     * <p>
     * 不支持降采样的读取器会退化为完整解码（随后仍会缩放），但常规 JPEG/PNG 读取器都支持；
     * 无法识别或解码失败时返回 null，由调用方回退原文件。
     *
     * @param source      图片文件
     * @param subsampling 降采样倍数，1 表示不降采样
     */
    private BufferedImage decodeImage(File source, int subsampling) throws IOException {
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
                ImageReadParam param = reader.getDefaultReadParam();
                if (subsampling > 1) {
                    param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                }
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * 计算解码降采样倍数。
     * <p>
     * 向下取整，保证降采样后的长边不小于 {@link #MAX_DIMENSION}，
     * 避免 Thumbnailator 因源图太小而无法缩放到目标尺寸。
     */
    static int subsamplingFactor(int longSide) {
        return Math.max(1, longSide / MAX_DIMENSION);
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
     * 当前生效的单张上传上限：优先取配置，未注入（单元测试）时回退默认值
     */
    long maxFileSizeBytes() {
        UploadProperties properties = this.uploadProperties;
        if (properties != null && properties.getMaxFileSizeBytes() > 0) {
            return properties.getMaxFileSizeBytes();
        }
        return DEFAULT_MAX_FILE_SIZE;
    }

    /**
     * 字节换算成整 MB，用于错误提示
     */
    private static long toMegabytes(long bytes) {
        return Math.max(1L, bytes / (1024 * 1024));
    }

    /**
     * 校验文件：空文件、大小、后缀、MIME 类型和文件头都要符合图片要求
     *
     * @param multipartFile multipart 文件
     */
    public void validPicture(MultipartFile multipartFile) {
        ThrowUtils.throwIf(multipartFile == null || multipartFile.isEmpty(), ErrorCode.PARAMS_ERROR, "文件不能为空");
        // 1. 校验文件大小
        long maxFileSize = maxFileSizeBytes();
        ThrowUtils.throwIf(multipartFile.getSize() > maxFileSize, ErrorCode.PARAMS_ERROR,
                "文件大小不能超过 " + toMegabytes(maxFileSize) + "MB");
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
     * 回收站到期清理：删除 COS 对象，并区分「无需删除」与「删除失败」。
     * <p>
     * 与 {@link #deletePicture(String)} 不同，这里把「url 为空 / 不属于当前存储桶」视为无需删除，
     * 返回 {@code true} 让调用方可以继续物理删除数据库行；只有 COS 调用失败才返回 {@code false}，
     * 调用方应保留记录，等下一轮清理重试，避免数据库行先消失、对象却留在桶里。
     *
     * @param url 图片访问地址
     * @return 是否可以安全地物理删除对应的数据库记录
     */
    public boolean deletePictureForPurge(String url) {
        if (StrUtil.isBlank(url)) {
            log.warn("回收站清理：url 为空，直接清理数据库记录");
            return true;
        }
        String key = cosManager.parseObjectKey(url);
        if (StrUtil.isBlank(key)) {
            log.warn("回收站清理：url 不属于当前存储桶，跳过对象删除, url = {}", url);
            return true;
        }
        try {
            cosManager.deleteObject(key);
            log.info("回收站清理：已删除图片文件, key = {}", key);
            return true;
        } catch (Exception e) {
            log.error("回收站清理：删除图片文件失败，保留记录等待重试, key = {}", key, e);
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
