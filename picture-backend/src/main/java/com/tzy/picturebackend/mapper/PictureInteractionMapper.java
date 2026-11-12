package com.tzy.picturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.PictureInteraction;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * 图片互动（收藏 / 点赞）关系表的数据库操作。
 * <p>
 * 所有写操作都依赖唯一键 {@code uk_picture_user_type}：{@link #insertIgnore} 命中重复时返回 0，
 * 调用方据此决定是否要累加计数，因此并发重复点击不会把计数加两次。
 */
public interface PictureInteractionMapper extends BaseMapper<PictureInteraction> {

    /**
     * 幂等插入一条互动关系；已存在时返回 0（{@code INSERT IGNORE}）
     *
     * @param id         主键，走自定义 SQL 时 MyBatis-Plus 的 ID 生成器不生效，需显式传入
     * @param pictureId  图片 id
     * @param userId     用户 id
     * @param type       互动类型
     * @param createTime 创建时间
     * @return 受影响行数：1 表示新增，0 表示已存在
     */
    int insertIgnore(@Param("id") Long id,
                     @Param("pictureId") Long pictureId,
                     @Param("userId") Long userId,
                     @Param("type") String type,
                     @Param("createTime") Date createTime);

    /**
     * 删除一条互动关系
     *
     * @return 受影响行数：1 表示确实取消过，0 表示本来就不存在
     */
    int deleteOne(@Param("pictureId") Long pictureId,
                  @Param("userId") Long userId,
                  @Param("type") String type);

    /**
     * 查询某个用户对某张图片的全部互动类型
     */
    List<PictureInteraction> selectByPictureAndUser(@Param("pictureId") Long pictureId,
                                                    @Param("userId") Long userId);

    /**
     * 「我的收藏 / 我的点赞」分页：JOIN {@code picture}，只返回已通过审核且未删除的图片，
     * 按互动时间倒序。
     */
    IPage<Picture> selectMyPicturePage(Page<Picture> page,
                                       @Param("userId") Long userId,
                                       @Param("type") String type);

    /**
     * 物理删除图片时级联清理互动关系（回收站阶段保留计数）
     *
     * @return 删除行数
     */
    int deleteByPictureIds(@Param("ids") Collection<Long> ids);
}
