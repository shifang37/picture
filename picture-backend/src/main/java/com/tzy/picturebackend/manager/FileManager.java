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
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
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
        try {
            // 创建临时文件
            file =File.createTempFile(uploadPath, null);
            multipartFile.transferTo(file);
            // 上传图片
            PutObjectResult putObjectResult = cosManager.putPictureObject(uploadPath, file);
            ImageInfo imageInfo = putObjectResult.getCiUploadResult().getOriginalInfo().getImageInfo();
            // 封装返回结果
            UploadPictureResult uploadPictureResult = new UploadPictureResult();
            int picWidth = imageInfo.getWidth();
            int picHeight = imageInfo.getHeight();
            double picScale = NumberUtil.round(picWidth * 1.0 / picHeight, 2).doubleValue();
            uploadPictureResult.setPicName(FileUtil.mainName(originFilename));
            uploadPictureResult.setPicWidth(picWidth);
            uploadPictureResult.setPicHeight(picHeight);
            uploadPictureResult.setPicScale(picScale);
            uploadPictureResult.setPicFormat(imageInfo.getFormat());
            uploadPictureResult.setPicSize(FileUtil.size(file));
            uploadPictureResult.setUrl(cosClientConfig.getHost() + "/" + uploadPath);
            return uploadPictureResult;
        } catch (Exception e) {
            log.error("图片上传到存储失败",e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,"上传失败");
        } finally {
            this.deleteTempFile(file);
        }
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
