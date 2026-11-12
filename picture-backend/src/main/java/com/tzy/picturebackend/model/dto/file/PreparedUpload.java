package com.tzy.picturebackend.model.dto.file;

import lombok.Data;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 上传“准备阶段”的产物：已完成校验、方向纠正、压缩与哈希，但还没有写入对象存储。
 * <p>
 * 拆成准备 / 上传两段的目的：
 * <ul>
 *   <li>秒传判断要在任何 COS 调用之前完成，否则命中时对象已经上传，既浪费流量又留下孤儿文件；</li>
 *   <li>配额校验也发生在准备之后、上传之前，失败时不会产生对象；</li>
 *   <li>哈希在最终产物上计算，保证与真正存储的内容一致。</li>
 * </ul>
 * 调用方必须在使用完（无论成功还是失败）后调用 {@link #cleanup()} 删除临时文件。
 */
@Data
public class PreparedUpload {

    /**
     * 对象存储里的相对路径，例如 {@code public/1/2026-11-12_abcd.jpg}
     */
    private String uploadPath;

    /**
     * 图片名称（取原文件名，不含后缀）
     */
    private String picName;

    /**
     * 最终文件体积（字节）
     */
    private Long picSize;

    /**
     * 最终文件的 SHA-256（小写十六进制），用于同一用户的秒传
     */
    private String fileHash;

    /**
     * 文件后缀（小写），例如 jpg / png / webp
     */
    private String suffix;

    /**
     * 最终要上传的临时文件（可能是原文件，也可能是旋转/压缩后的产物）
     */
    private File uploadFile;

    /**
     * 本次准备过程创建的所有临时文件，清理时统一删除
     */
    private List<File> tempFiles = new ArrayList<>();

    /**
     * 删除准备过程中产生的全部临时文件（best-effort，重复调用安全）
     */
    public void cleanup() {
        List<File> files = this.tempFiles;
        if (files == null || files.isEmpty()) {
            return;
        }
        for (File file : files) {
            if (file == null) {
                continue;
            }
            try {
                if (file.exists() && !file.delete()) {
                    file.deleteOnExit();
                }
            } catch (RuntimeException ignored) {
                // 临时文件清理失败不影响上传结果，交给 deleteOnExit 兜底
            }
        }
        files.clear();
    }
}
