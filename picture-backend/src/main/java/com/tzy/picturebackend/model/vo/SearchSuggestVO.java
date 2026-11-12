package com.tzy.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 搜索联想结果：按「名称 / 标签 / 分类」分组。
 * <p>
 * 三个分组都只基于公开可见的图片（{@code isDelete = 0 AND reviewStatus = 1}），
 * 避免待审核内容的名称通过联想被外部探测到。
 */
@Data
public class SearchSuggestVO implements Serializable {

    /**
     * 名称前缀匹配的图片，点击可直接进入详情
     */
    private List<SearchNameSuggestionVO> names = new ArrayList<>();

    /**
     * 标签前缀匹配结果（去重）
     */
    private List<String> tags = new ArrayList<>();

    /**
     * 分类前缀匹配结果（去重）
     */
    private List<String> categories = new ArrayList<>();

    private static final long serialVersionUID = 1L;
}
