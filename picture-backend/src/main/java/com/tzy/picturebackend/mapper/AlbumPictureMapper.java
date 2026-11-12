package com.tzy.picturebackend.mapper;

import com.tzy.picturebackend.model.vo.AlbumPictureCountVO;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * 专辑-图片关系表 Mapper。
 * <p>
 * 联合主键 {@code (albumId, pictureId)}，不使用 MyBatis-Plus 的单主键 CRUD，
 * 只提供批量写入、删除、计数与封面回退查询。
 */
public interface AlbumPictureMapper {

    /**
     * 批量加入图片；重复图片由 INSERT IGNORE 跳过（幂等）
     *
     * @param albumId    专辑 id
     * @param pictureIds 已去重的图片 id
     * @param createTime 加入时间
     * @return 实际新增行数（不含重复项）
     */
    int insertIgnore(@Param("albumId") Long albumId,
                     @Param("pictureIds") Collection<Long> pictureIds,
                     @Param("createTime") Date createTime);

    /**
     * 物理删除专辑下的全部关系（删除专辑时调用）
     *
     * @param albumId 专辑 id
     * @return 删除行数
     */
    int deleteByAlbumId(@Param("albumId") Long albumId);

    /**
     * 批量物理删除多个专辑的关系（删除用户时调用）
     *
     * @param albumIds 专辑 id 集合
     * @return 删除行数
     */
    int deleteByAlbumIds(@Param("albumIds") Collection<Long> albumIds);

    /**
     * 从专辑移除指定图片
     *
     * @param albumId    专辑 id
     * @param pictureIds 图片 id 集合
     * @return 实际删除行数
     */
    int deleteByAlbumAndPictureIds(@Param("albumId") Long albumId,
                                   @Param("pictureIds") Collection<Long> pictureIds);

    /**
     * 专辑内的图片总数（作者视角，含未过审）
     *
     * @param albumId 专辑 id
     * @return 关系行数
     */
    int countByAlbumId(@Param("albumId") Long albumId);

    /**
     * 判断某张图片是否已在专辑内
     *
     * @param albumId   专辑 id
     * @param pictureId 图片 id
     * @return 1-存在 0-不存在
     */
    int countByAlbumAndPictureId(@Param("albumId") Long albumId,
                                 @Param("pictureId") Long pictureId);

    /**
     * 批量统计专辑的公开图片数量（仅 reviewStatus = 1 且未删除）
     *
     * @param albumIds 专辑 id 集合
     * @return 每个专辑一行；没有公开图片的专辑不会出现在结果里
     */
    List<AlbumPictureCountVO> selectPublicCounts(@Param("albumIds") Collection<Long> albumIds);

    /**
     * 最新一张公开图片 id，用作封面回退
     *
     * @param albumId 专辑 id
     * @return 图片 id；没有公开图片时返回 null
     */
    Long selectFirstPublicPictureId(@Param("albumId") Long albumId);

    /**
     * 最新一张图片 id（作者视角的封面回退，允许未过审）
     *
     * @param albumId 专辑 id
     * @return 图片 id；专辑为空时返回 null
     */
    Long selectLatestPictureId(@Param("albumId") Long albumId);
}
