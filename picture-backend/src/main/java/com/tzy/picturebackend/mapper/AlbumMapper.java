package com.tzy.picturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tzy.picturebackend.model.entity.Album;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 专辑 Mapper。
 * <p>
 * 常规 CRUD 由 MyBatis-Plus 提供；计数的原子增减与重算单独写 SQL，
 * 避免「先查后写」在并发下把 pictureCount 算错。
 */
public interface AlbumMapper extends BaseMapper<Album> {

    /**
     * 原子调整专辑图片数量；delta 为负时用 GREATEST 兜底，不会减成负数
     *
     * @param id    专辑 id
     * @param delta 增量，可为负
     * @return 受影响行数（专辑已删除时为 0）
     */
    int incrementPictureCount(@Param("id") Long id, @Param("delta") int delta);

    /**
     * 按主键游标分批取专辑 id，供计数重算使用
     *
     * @param afterId 只取 id 大于该值的记录（首次传 0）
     * @param limit   单批条数
     */
    List<Long> selectIdsForRecount(@Param("afterId") Long afterId, @Param("limit") int limit);

    /**
     * 用关系表真实行数覆盖冗余计数，修复计数漂移
     *
     * @param ids 单批专辑 id
     * @return 受影响行数
     */
    int recountPictureCounts(@Param("ids") Collection<Long> ids);
}
