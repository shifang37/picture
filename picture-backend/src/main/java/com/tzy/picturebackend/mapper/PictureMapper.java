package com.tzy.picturebackend.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.vo.AuthorStatsVO;
import com.tzy.picturebackend.model.vo.SearchHotKeywordVO;
import com.tzy.picturebackend.model.vo.SearchNameSuggestionVO;
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
     * 批量移入回收站：一条 UPDATE 写入整批记录的删除时间与操作人。
     * <p>
     * 条件包含 {@code isDelete = 0}，并发删除同一张图时只有一方成功，
     * 调用方用返回行数与请求数量比对，得到准确的失败明细。
     *
     * @param ids       图片 id 集合
     * @param deletedBy 删除操作人 id
     * @param deletedAt 移入回收站时间
     * @return 受影响行数
     */
    int logicalDeleteByIds(@Param("ids") Collection<Long> ids,
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

    /**
     * 原子调整互动计数列（收藏数 / 点赞数）。
     * <p>
     * {@code column} 只能来自 {@code PictureInteractionTypeEnum#getCounterColumn()}，不接受客户端输入；
     * {@code delta} 为负时用 {@code GREATEST(0, ...)} 兜底，避免并发取消导致计数变成负数。
     *
     * @param id     图片 id
     * @param column 计数列名（favoriteCount / likeCount）
     * @param delta  增量，可为负
     * @return 受影响行数；0 表示图片已被删除
     */
    int adjustInteractionCount(@Param("id") Long id,
                               @Param("column") String column,
                               @Param("delta") int delta);

    /**
     * 按主键游标分批取图片 id，供计数重算使用
     *
     * @param afterId 只取 id 大于该值的记录（首次传 0）
     * @param limit   单批条数
     */
    List<Long> selectIdsForRecount(@Param("afterId") Long afterId,
                                   @Param("limit") int limit);

    /**
     * 按关系表重算一批图片的收藏数 / 点赞数，用于修正计数漂移
     *
     * @param ids 单批图片 id
     * @return 受影响行数
     */
    int recountInteractions(@Param("ids") Collection<Long> ids);

    /**
     * 作者维度的公开作品聚合统计。
     * <p>
     * 口径固定为 {@code isDelete = 0 AND reviewStatus = 1}：作者主页只展示公开作品，
     * 待审核 / 已拒绝的内容不能被外部通过统计数字推断出来。
     *
     * @param userId 作者 id
     * @return 作品数、下载量、收藏数、点赞数；没有公开作品时各项为 0
     */
    AuthorStatsVO selectAuthorStats(@Param("userId") Long userId);

    /**
     * 专辑内的图片分页。
     * <p>
     * 自定义 SQL 不经过逻辑删除插件，{@code isDelete = 0} 显式写死；公开访问时
     * {@code onlyPublic = true} 只返回已过审图片，作者/管理员可以看全部。
     *
     * @param page       分页参数
     * @param albumId    专辑 id
     * @param onlyPublic 是否只返回已过审图片
     * @return 图片分页，按加入专辑时间倒序
     */
    IPage<Picture> selectPageByAlbum(Page<Picture> page,
                                     @Param("albumId") Long albumId,
                                     @Param("onlyPublic") boolean onlyPublic);

    /**
     * 搜索联想：名称前缀匹配的公开图片。
     * <p>
     * 只用「前缀」而不是「包含」：{@code name LIKE 'kw%'} 能走 {@code idx_name}，
     * 包含匹配会退化为全表扫描。排序把「完全等于搜索词」的图片放最前，其余按名称长度升序，
     * 让更短、更精确的名称优先出现在下拉框里。
     *
     * @param keyword 已裁剪的搜索词（1–32 字符）
     * @param limit   最多返回条数
     * @return 命中的公开图片 id 与名称
     */
    List<SearchNameSuggestionVO> selectNameSuggestions(@Param("keyword") String keyword,
                                                       @Param("limit") int limit);

    /**
     * 搜索联想：标签前缀匹配，只统计公开图片下的标签。
     *
     * @param keyword 已裁剪的搜索词
     * @param limit   最多返回条数
     * @return 去重后的标签名
     */
    List<String> selectTagSuggestions(@Param("keyword") String keyword,
                                      @Param("limit") int limit);

    /**
     * 搜索联想：分类前缀匹配，只统计公开图片的分类。
     *
     * @param keyword 已裁剪的搜索词
     * @param limit   最多返回条数
     * @return 去重后的分类名
     */
    List<String> selectCategorySuggestions(@Param("keyword") String keyword,
                                           @Param("limit") int limit);

    /**
     * 热门标签：按公开图片的标签使用次数倒序。
     *
     * @param limit 最多返回条数
     * @return 标签名与公开图片数量
     */
    List<SearchHotKeywordVO> selectHotTags(@Param("limit") int limit);

    /**
     * 热门分类：按公开图片的分类使用次数倒序。
     *
     * @param limit 最多返回条数
     * @return 分类名与公开图片数量
     */
    List<SearchHotKeywordVO> selectHotCategories(@Param("limit") int limit);

}
