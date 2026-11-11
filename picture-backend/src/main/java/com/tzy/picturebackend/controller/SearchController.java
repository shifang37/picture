package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.annotation.AuthCheck;
import com.tzy.picturebackend.common.BaseResponse;
import com.tzy.picturebackend.common.ResultUtils;
import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.manager.PictureListCache;
import com.tzy.picturebackend.service.PictureSearchService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索运维接口。
 */
@RestController
@RequestMapping("search")
public class SearchController {

    @Resource
    private PictureSearchService pictureSearchService;

    @Resource
    private PictureListCache pictureListCache;

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
}
