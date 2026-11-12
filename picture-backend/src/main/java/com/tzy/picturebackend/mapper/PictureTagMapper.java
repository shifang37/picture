package com.tzy.picturebackend.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Collection;

/**
 * 图片标签关系表 Mapper。
 * <p>
 * 关系表使用联合主键 {@code (pictureId, tag)}，不使用 MyBatis-Plus 的单主键 CRUD，
 * 只提供按图片整体替换标签所需的批量写入与删除。
 */
public interface PictureTagMapper {

    /**
     * 批量写入某张图片的标签；重复标签由 INSERT IGNORE 兜底
     *
     * @param pictureId 图片 id
     * @param tags      已经去重、去空、裁剪过的标签
     * @return 写入行数
     */
    int insertBatch(@Param("pictureId") Long pictureId, @Param("tags") Collection<String> tags);

    /**
     * 删除某张图片的全部标签关系
     *
     * @param pictureId 图片 id
     * @return 删除行数
     */
    int deleteByPictureId(@Param("pictureId") Long pictureId);

    /**
     * 批量删除多张图片的标签关系，供回收站到期物理清理使用
     *
     * @param pictureIds 图片 id 集合
     * @return 删除行数
     */
    int deleteByPictureIds(@Param("pictureIds") Collection<Long> pictureIds);
}
