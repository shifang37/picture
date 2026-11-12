package com.tzy.picturebackend.controller;

import cn.hutool.core.util.StrUtil;
import com.tzy.picturebackend.annotation.AuthCheck;
import com.tzy.picturebackend.common.BaseResponse;
import com.tzy.picturebackend.common.ResultUtils;
import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.manager.PictureListCache;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.vo.SearchHotVO;
import com.tzy.picturebackend.model.vo.SearchSuggestVO;
import com.tzy.picturebackend.service.PictureSearchService;
import com.tzy.picturebackend.service.SearchMetaService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索接口：公开的搜索联想 / 热词，以及管理员的索引重建。
 */
@RestController
@RequestMapping("search")
public class SearchController {

    @Resource
    private PictureSearchService pictureSearchService;

    @Resource
    private PictureListCache pictureListCache;

    @Resource
    private SearchMetaService searchMetaService;

    @Resource
    private PictureMetrics pictureMetrics;

    /**
     * 从 MySQL 全量重建公开图片索引（仅管理员可用）。
     * <p>
     * 首次启用 Elasticsearch、索引被误删或一致性巡检发现数量不一致时调用。
     * 重建期间旧索引继续提供查询，完成后原子切换别名，不会出现搜索为空的窗口。
     *
     * @return 写入索引的图片数量
     */
    @PostMapping("/reindex")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Integer> reindex() {
        ThrowUtils.throwIf(!pictureSearchService.enabled(),
                ErrorCode.OPERATION_ERROR, "未启用 Elasticsearch 搜索");
        int indexed = pictureSearchService.reindexAll();
        // 重建前缓存的搜索结果可能来自旧索引，重建后立即失效
        pictureListCache.invalidate();
        return ResultUtils.success(indexed);
    }

    /**
     * 搜索联想：输入框边打字边请求，返回名称 / 标签 / 分类三组前缀匹配结果。
     * <p>
     * 只查公开图片，未过审内容不会出现在联想里；结果进 Redis 缓存，重复输入直接命中。
     *
     * @param q     搜索词，1–32 字符（首尾空白会被裁剪）
     * @param limit 期望返回条数，默认 10，最多 20
     * @return 三组联想结果
     */
    @GetMapping("/suggest")
    public BaseResponse<SearchSuggestVO> suggest(@RequestParam(value = "q", required = false) String q,
                                                 @RequestParam(value = "limit", required = false) Integer limit) {
        String keyword = StrUtil.trim(q);
        ThrowUtils.throwIf(StrUtil.isBlank(keyword), ErrorCode.PARAMS_ERROR, "搜索词不能为空");
        ThrowUtils.throwIf(keyword.length() > SearchMetaService.MAX_KEYWORD_LENGTH,
                ErrorCode.PARAMS_ERROR,
                "搜索词不能超过 " + SearchMetaService.MAX_KEYWORD_LENGTH + " 个字符");
        pictureMetrics.searchMeta("suggest");
        return ResultUtils.success(searchMetaService.suggest(keyword, normalizeLimit(limit)));
    }

    /**
     * 热门搜索词：首页空结果与首次进入时展示，引导用户发现内容。
     *
     * @param limit 期望返回条数，默认 10，最多 20
     * @return 热门标签与热门分类
     */
    @GetMapping("/hot")
    public BaseResponse<SearchHotVO> hot(@RequestParam(value = "limit", required = false) Integer limit) {
        pictureMetrics.searchMeta("hot");
        return ResultUtils.success(searchMetaService.hotKeywords(normalizeLimit(limit)));
    }

    /**
     * 条数在 Controller 收敛一次，保证缓存键只可能是 1–20 的有限集合
     */
    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return SearchMetaService.DEFAULT_LIMIT;
        }
        return Math.min(limit, SearchMetaService.MAX_LIMIT);
    }
}
