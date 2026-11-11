package com.tzy.picturebackend.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.entity.Picture;

/**
 * 公开图片搜索。
 * <p>
 * 实现有两种：{@code app.search.engine=mysql} 时的空实现（行为与接入搜索前完全一致），
 * 以及 {@code app.search.engine=es} 时的 Elasticsearch 实现。
 * <p>
 * 约定：MySQL 是唯一事实来源，搜索只负责召回一页图片 id，调用方再回 MySQL 取数据并过滤审核状态；
 * 索引里只保存已通过审核的公开图片。
 */
public interface PictureSearchService {

    /**
     * 是否启用了 Elasticsearch 搜索
     */
    boolean enabled();

    /**
     * 把一张图片写入索引；图片不可公开时转为删除索引文档
     */
    void indexPicture(Picture picture);

    /**
     * 按 id 删除索引文档；文档不存在时静默忽略
     */
    void deletePicture(Long pictureId);

    /**
     * 按查询条件召回一页图片 id；total 为索引侧命中的总数
     */
    Page<Long> searchPictureIds(PictureQueryRequest request);

    /**
     * 从 MySQL 全量重建索引，返回写入的图片数量
     */
    int reindexAll();

    /**
     * 索引中的文档数量，用于一致性巡检
     */
    long countIndexed();
}
