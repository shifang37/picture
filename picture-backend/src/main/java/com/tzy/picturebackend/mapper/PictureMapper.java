package com.tzy.picturebackend.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.model.entity.Picture;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;

/**
* @author shifang37
* @description 针对表【picture(图片)】的数据库操作Mapper
* @createDate 2026-03-11 16:30:50
* @Entity com.tzy.picturebackend.model.entity.Picture
*/
public interface PictureMapper extends BaseMapper<Picture> {

    /**
     * 带标签条件的分页查询：JOIN {@code picture_tag}，多个标签为「同时包含」语义。
     * <p>
     * 普通字段条件与排序仍由 {@code ew}（QueryWrapper）提供，标签条件单独用参数传入，
     * 避免把用户输入拼进 SQL 字符串。
     *
     * @param page  分页参数
     * @param ew    不含标签条件的 QueryWrapper（需显式包含 isDelete = 0）
     * @param tags  已去重、去空的标签
     * @return 图片分页
     */
    IPage<Picture> selectPageByTags(Page<Picture> page,
                                    @Param(Constants.WRAPPER) Wrapper<Picture> ew,
                                    @Param("tags") Collection<String> tags,
                                    @Param("tagCount") int tagCount);

}
