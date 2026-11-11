package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.annotation.AuthCheck;
import com.tzy.picturebackend.common.BaseResponse;
import com.tzy.picturebackend.common.DeleteRequest;
import com.tzy.picturebackend.common.ResultUtils;
import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.model.dto.tagcategory.TagCategoryAddRequest;
import com.tzy.picturebackend.model.entity.TagCategory;
import com.tzy.picturebackend.service.TagCategoryService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 标签/分类字典管理接口（仅管理员）
 */
@RestController
@RequestMapping("/tag_category")
public class TagCategoryController {

    @Resource
    private TagCategoryService tagCategoryService;

    /**
     * 新增标签/分类
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> addTagCategory(@Valid @RequestBody TagCategoryAddRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        boolean result = tagCategoryService.add(request.getType(), request.getName());
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "新增失败");
        return ResultUtils.success(true);
    }

    /**
     * 删除标签/分类
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteTagCategory(@Valid @RequestBody DeleteRequest request) {
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        boolean result = tagCategoryService.delete(request.getId());
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除失败");
        return ResultUtils.success(true);
    }

    /**
     * 从已通过审核的图片中同步标签/分类，返回新增数量
     */
    @PostMapping("/sync")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Integer> syncTagCategory() {
        return ResultUtils.success(tagCategoryService.syncFromPictures());
    }

    /**
     * 管理端列表：返回带 id 的完整字典，供管理页删除
     */
    @GetMapping("/list")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<TagCategory>> listTagCategory() {
        return ResultUtils.success(tagCategoryService.listAllWithId());
    }
}
