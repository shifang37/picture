package com.tzy.picturebackend.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.model.entity.Picture;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.Date;
import java.util.List;

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

    /**
     * 移入回收站：置 {@code isDelete = 1}，同时记录删除时间与操作人。
     * <p>
     * 使用自定义 SQL 而不是 {@code deleteById}，因为逻辑删除插件只会更新 {@code isDelete}，
     * 无法在同一句里写入 {@code deletedAt} / {@code deletedBy}；条件里带上 {@code isDelete = 0}
     * 可以避免并发重复删除。
     *
     * @param id        图片 id
     * @param deletedBy 删除操作人 id
     * @param deletedAt 移入回收站时间
     * @return 受影响行数；0 表示图片不存在或已被删除
     */
    int logicalDeleteById(@Param("id") Long id,
                          @Param("deletedBy") Long deletedBy,
                          @Param("deletedAt") Date deletedAt);

    /**
     * 按 id 查询回收站中的图片；普通查询会被逻辑删除插件自动过滤，恢复时必须绕开。
     *
     * @param id 图片 id
     * @return 已删除的图片；不存在或不在回收站时返回 null
     */
    Picture selectDeletedById(@Param("id") Long id);

    /**
     * 从回收站恢复：清除 {@code isDelete}、{@code deletedAt}、{@code deletedBy}。
     *
     * @param id 图片 id
     * @return 受影响行数；0 表示图片不在回收站
     */
    int restoreById(@Param("id") Long id);

    /**
     * 回收站分页查询。自定义 SQL 不会自动加逻辑删除条件，{@code ew} 必须显式包含
     * {@code isDelete = 1}。
     *
     * @param page 分页参数
     * @param ew   回收站查询条件
     * @return 已删除图片分页
     */
    IPage<Picture> selectRecyclePage(Page<Picture> page,
                                     @Param(Constants.WRAPPER) Wrapper<Picture> ew);

    /**
     * 查询超过保留期、可以物理删除的图片。
     * <p>
     * 历史数据可能没有 {@code deletedAt}，用 {@code updateTime} 兜底，避免永久留在回收站。
     *
     * @param before 删除时间早于该时刻的图片会被清理
     * @param limit  单批最多返回条数
     * @return 待物理删除的图片
     */
    List<Picture> selectPurgeCandidates(@Param("before") Date before,
                                        @Param("limit") int limit);

    /**
     * 物理删除回收站中的图片记录；条件带 {@code isDelete = 1}，不会误删正常图片。
     *
     * @param ids 图片 id 集合
     * @return 删除行数
     */
    int deletePhysicallyByIds(@Param("ids") Collection<Long> ids);

}
