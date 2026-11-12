package com.tzy.picturebackend.service;

import com.tzy.picturebackend.model.vo.SearchHotVO;
import com.tzy.picturebackend.model.vo.SearchSuggestVO;

/**
 * 搜索元数据服务：搜索联想与热门搜索词。
 * <p>
 * 固定走 MySQL，不放进 {@code PictureSearchService}：Elasticsearch 实现与空实现都不应承担
 * 元数据查询，而且 {@code app.search.engine=mysql}（默认）时也必须可用。
 * 数据量小、变化慢，结果统一进 {@code searchMeta} 缓存（TTL 10 分钟）。
 */
public interface SearchMetaService {

    /**
     * 联想词长度上限，与 {@code picture.name} / {@code picture_tag.tag} 的展示长度保持一致
     */
    int MAX_KEYWORD_LENGTH = 32;

    /**
     * 默认返回条数
     */
    int DEFAULT_LIMIT = 10;

    /**
     * 单次最多返回条数，避免一次联想把整张表的前缀结果全捞出来
     */
    int MAX_LIMIT = 20;

    /**
     * 搜索联想：名称（前缀）、标签（前缀）、分类（前缀）三组结果。
     * <p>
     * 只匹配公开可见的图片（{@code isDelete = 0 AND reviewStatus = 1}），
     * 待审核 / 已拒绝内容的名称不会出现在联想里。
     *
     * @param keyword 已裁剪的搜索词，长度 1–32
     * @param limit   期望返回条数，超出范围时按 {@link #DEFAULT_LIMIT}/{@link #MAX_LIMIT} 收敛
     * @return 三组联想结果；无匹配时各组为空列表
     */
    SearchSuggestVO suggest(String keyword, Integer limit);

    /**
     * 热门搜索词：按公开图片的标签订阅量与分类数量排序。
     *
     * @param limit 期望返回条数，超出范围时按 {@link #DEFAULT_LIMIT}/{@link #MAX_LIMIT} 收敛
     * @return 热门标签与热门分类；没有公开图片时两组均为空
     */
    SearchHotVO hotKeywords(Integer limit);
}
