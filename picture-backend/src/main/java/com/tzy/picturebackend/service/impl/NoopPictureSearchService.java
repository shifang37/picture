package com.tzy.picturebackend.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.service.PictureSearchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 未启用 Elasticsearch 时的空实现。
 * <p>
 * 所有索引操作都是 no-op，公开列表仍走原来的 MySQL 查询，行为与接入搜索前完全一致。
 */
@Service
@ConditionalOnProperty(prefix = "app.search", name = "engine", havingValue = "mysql", matchIfMissing = true)
public class NoopPictureSearchService implements PictureSearchService {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void indexPicture(Picture picture) {
        // 未启用搜索：不需要维护索引
    }

    @Override
    public void deletePicture(Long pictureId) {
        // 未启用搜索：不需要维护索引
    }

    @Override
    public Page<Long> searchPictureIds(PictureQueryRequest request) {
        throw new IllegalStateException("未启用 Elasticsearch 搜索");
    }

    @Override
    public int reindexAll() {
        return 0;
    }

    @Override
    public long countIndexed() {
        return 0;
    }
}
