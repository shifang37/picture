package com.tzy.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.tzy.picturebackend.mapper.PictureEmbeddingMapper;
import com.tzy.picturebackend.model.entity.PictureEmbedding;
import com.tzy.picturebackend.model.enums.EmbeddingStatusEnum;
import com.tzy.picturebackend.service.RagEmbeddingStateService;
import org.springframework.stereotype.Service;

import java.util.Collection;

/**
 * 图片向量化状态实现：所有写入都是单条 UPSERT，天然幂等。
 */
@Service
public class RagEmbeddingStateServiceImpl implements RagEmbeddingStateService {

    /**
     * lastError 列长度；超长错误只保留前 512 字符，避免把整段堆栈写进表
     */
    static final int MAX_ERROR_LENGTH = 512;

    private final PictureEmbeddingMapper pictureEmbeddingMapper;

    public RagEmbeddingStateServiceImpl(PictureEmbeddingMapper pictureEmbeddingMapper) {
        this.pictureEmbeddingMapper = pictureEmbeddingMapper;
    }

    @Override
    public PictureEmbedding get(Long pictureId) {
        if (pictureId == null) {
            return null;
        }
        return pictureEmbeddingMapper.selectById(pictureId);
    }

    @Override
    public void markTextSuccess(Long pictureId, String hash, String model, String version) {
        if (pictureId == null) {
            return;
        }
        pictureEmbeddingMapper.markTextSuccess(pictureId, hash, model, version);
    }

    @Override
    public void markImageSuccess(Long pictureId, String hash, String model, String version) {
        if (pictureId == null) {
            return;
        }
        pictureEmbeddingMapper.markImageSuccess(pictureId, hash, model, version);
    }

    @Override
    public void markFailure(Long pictureId, boolean textFailed, boolean imageFailed, String error) {
        if (pictureId == null) {
            return;
        }
        // 插入新行时把未失败的一侧记为待处理，避免出现「伪造成功」
        int textStatus = textFailed
                ? EmbeddingStatusEnum.FAILED.getValue() : EmbeddingStatusEnum.PENDING.getValue();
        int imageStatus = imageFailed
                ? EmbeddingStatusEnum.FAILED.getValue() : EmbeddingStatusEnum.PENDING.getValue();
        pictureEmbeddingMapper.markFailure(pictureId, textFailed, imageFailed,
                textStatus, imageStatus, truncateError(error));
    }

    @Override
    public void delete(Long pictureId) {
        if (pictureId == null) {
            return;
        }
        pictureEmbeddingMapper.deleteById(pictureId);
    }

    @Override
    public void deleteByPictureIds(Collection<Long> pictureIds) {
        if (CollUtil.isEmpty(pictureIds)) {
            return;
        }
        pictureEmbeddingMapper.deleteByIds(pictureIds);
    }

    @Override
    public long countPendingPublic() {
        return pictureEmbeddingMapper.countPendingPublic();
    }

    @Override
    public long countFailed() {
        return pictureEmbeddingMapper.countFailed();
    }

    @Override
    public long countSuccessPublic() {
        return pictureEmbeddingMapper.countSuccessPublic();
    }

    /**
     * 截断错误信息；空错误给一个可检索的占位符
     */
    static String truncateError(String error) {
        if (StrUtil.isBlank(error)) {
            return "未知错误";
        }
        String trimmed = error.trim();
        return trimmed.length() <= MAX_ERROR_LENGTH
                ? trimmed : trimmed.substring(0, MAX_ERROR_LENGTH);
    }
}
