package com.tzy.picturebackend.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 热门搜索词条目（标签或分类 + 公开图片数量）。
 * <p>
 * 计数口径与公开列表一致：只统计已过审且未删除的图片，回收站 / 待审核内容不参与排名。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchHotKeywordVO implements Serializable {

    /**
     * 关键词（标签名或分类名）
     */
    private String name;

    /**
     * 关联的公开图片数量
     */
    private long count;

    private static final long serialVersionUID = 1L;
}
