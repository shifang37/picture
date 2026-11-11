package com.tzy.picturebackend.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tzy.picturebackend.model.entity.TagCategory;
import com.tzy.picturebackend.model.vo.PictureTagCategory;

import java.util.List;

/**
 * 标签/分类字典服务
 */
public interface TagCategoryService extends IService<TagCategory> {

    /**
     * 标签类型
     */
    String TYPE_TAG = "tag";

    /**
     * 分类类型
     */
    String TYPE_CATEGORY = "category";

    /**
     * 汇总标签和分类，供上传页与首页使用
     */
    PictureTagCategory listAll();

    /**
     * 返回带 id 的完整字典列表，供管理页删除
     */
    List<TagCategory> listAllWithId();

    /**
     * 新增标签或分类，重名返回参数错误
     *
     * @param type tag/category
     * @param name 名称
     * @return 是否成功
     */
    boolean add(String type, String name);

    /**
     * 删除标签或分类
     *
     * @param id 字典 id
     * @return 是否成功
     */
    boolean delete(Long id);

    /**
     * 从已通过审核的图片中补齐缺失的标签和分类
     *
     * @return 新增数量
     */
    int syncFromPictures();
}
