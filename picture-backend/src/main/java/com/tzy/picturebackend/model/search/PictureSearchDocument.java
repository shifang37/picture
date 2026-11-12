package com.tzy.picturebackend.model.search;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.tzy.picturebackend.model.entity.Picture;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 公开图片在 Elasticsearch 中的文档。
 * <p>
 * 只放公开列表需要展示、筛选和排序的字段：索引里只有已通过审核的图片，
 * 未通过、已拒绝或已删除的图片不会出现在文档中。
 */
@Data
public class PictureSearchDocument {

    /**
     * 图片 id（Elasticsearch 文档 _id 也使用同一个值，这里冗余一份便于按 id 过滤）
     */
    private String id;

    /**
     * 图片名称，参与全文检索与相关度计算
     */
    private String name;

    /**
     * 简介，参与全文检索
     */
    private String introduction;

    /**
     * 分类，精确筛选与排序
     */
    private String category;

    /**
     * 标签数组，精确筛选
     */
    private List<String> tags;

    /**
     * 图片格式
     */
    private String picFormat;

    /**
     * 作者 id
     */
    private Long userId;

    /**
     * 图片体积
     */
    private Long picSize;

    /**
     * 下载次数
     */
    private Long downloadCount;

    /**
     * 收藏数（热度排序用）
     */
    private Long favoriteCount;

    /**
     * 点赞数（热度排序用）
     */
    private Long likeCount;

    /**
     * 图片宽度
     */
    private Integer picWidth;

    /**
     * 图片高度
     */
    private Integer picHeight;

    /**
     * 图片宽高比例
     */
    private Float picScale;

    /**
     * 创建时间，默认排序字段
     */
    private Date createTime;

    /**
     * 编辑时间
     */
    private Date editTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 审核状态（索引内只保留已通过，字段用于兜底过滤）
     */
    private Integer reviewStatus;

    /**
     * 审核时间
     */
    private Date reviewTime;

    /**
     * 机审状态
     */
    private Integer machineReviewStatus;

    /**
     * 由数据库实体构造索引文档；标签是 JSON 数组字符串，解析失败时按无标签处理
     */
    public static PictureSearchDocument from(Picture picture) {
        PictureSearchDocument document = new PictureSearchDocument();
        document.setId(picture.getId() == null ? null : String.valueOf(picture.getId()));
        document.setName(picture.getName());
        document.setIntroduction(picture.getIntroduction());
        document.setCategory(picture.getCategory());
        document.setTags(parseTags(picture.getTags()));
        document.setPicFormat(picture.getPicFormat());
        document.setUserId(picture.getUserId());
        document.setPicSize(picture.getPicSize());
        document.setDownloadCount(picture.getDownloadCount());
        document.setFavoriteCount(picture.getFavoriteCount());
        document.setLikeCount(picture.getLikeCount());
        document.setPicWidth(picture.getPicWidth());
        document.setPicHeight(picture.getPicHeight());
        document.setPicScale(picture.getPicScale() == null ? null : picture.getPicScale().floatValue());
        document.setCreateTime(picture.getCreateTime());
        document.setEditTime(picture.getEditTime());
        document.setUpdateTime(picture.getUpdateTime());
        document.setReviewStatus(picture.getReviewStatus());
        document.setReviewTime(picture.getReviewTime());
        document.setMachineReviewStatus(picture.getMachineReviewStatus());
        return document;
    }

    /**
     * 解析标签 JSON 数组：去空白、去重，非法 JSON 不抛异常
     */
    static List<String> parseTags(String tagsJson) {
        if (StrUtil.isBlank(tagsJson)) {
            return List.of();
        }
        List<String> parsed;
        try {
            parsed = JSONUtil.toList(tagsJson, String.class);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (parsed == null) {
            return List.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String tag : parsed) {
            String trimmed = StrUtil.trim(tag);
            if (StrUtil.isNotEmpty(trimmed)) {
                tags.add(trimmed);
            }
        }
        return new ArrayList<>(tags);
    }
}
