package com.tzy.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 热门搜索词：首页在空结果或首次进入时展示，引导用户发现内容。
 * <p>
 * 不做「真实搜索词」统计：那需要把用户输入落库或写 Redis ZSET，既有写入放大，
 * 也容易被脚本刷榜；这里用公开图片的标签 / 分类热度作为替代，数据量小、可缓存。
 */
@Data
public class SearchHotVO implements Serializable {

    /**
     * 热门标签
     */
    private List<SearchHotKeywordVO> tags = new ArrayList<>();

    /**
     * 热门分类
     */
    private List<SearchHotKeywordVO> categories = new ArrayList<>();

    private static final long serialVersionUID = 1L;
}
