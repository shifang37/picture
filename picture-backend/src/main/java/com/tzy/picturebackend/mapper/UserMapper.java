package com.tzy.picturebackend.mapper;

import com.tzy.picturebackend.model.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
* @author shifang37
* @description 针对表【user(用户)】的数据库操作Mapper
* @createDate 2026-03-05 21:32:07
* @Entity com.tzy.picturebackend.model.entity.User
*/
public interface UserMapper extends BaseMapper<User> {

    /**
     * 原子预留配额：存储与数量在一条 UPDATE 里同时校验，成功返回 1。
     * <p>
     * {@code deltaBytes} / {@code deltaPictures} 可以为负（重传缩小、释放额度），
     * 此时不做限额判断；已用值用 {@code GREATEST(0, ...)} 兜底，避免并发释放出现负数。
     * 额度不足时返回 0，调用方据此拒绝上传。
     *
     * @param userId        用户 id
     * @param deltaBytes    存储增量（字节）
     * @param deltaPictures 图片数量增量
     * @return 受影响行数；0 表示额度不足或用户不存在
     */
    int reserveQuota(@Param("userId") Long userId,
                     @Param("deltaBytes") long deltaBytes,
                     @Param("deltaPictures") int deltaPictures);

    /**
     * 直接调整已用量（不做额度校验）：释放与失败补偿使用。
     * <p>
     * 与 {@link #reserveQuota} 分开，保证“删图释放额度”永远不会因为并发上传占满而失败。
     *
     * @param userId        用户 id
     * @param deltaBytes    存储增量（负数表示释放）
     * @param deltaPictures 图片数量增量（负数表示释放）
     * @return 受影响行数
     */
    int adjustQuota(@Param("userId") Long userId,
                    @Param("deltaBytes") long deltaBytes,
                    @Param("deltaPictures") int deltaPictures);

    /**
     * 调整用户配额（管理员）：只更新传入的项
     *
     * @param userId       用户 id
     * @param storageQuota 存储配额；null 表示不修改
     * @param pictureQuota 图片数量上限；null 表示不修改
     * @return 受影响行数
     */
    int updateQuota(@Param("userId") Long userId,
                    @Param("storageQuota") Long storageQuota,
                    @Param("pictureQuota") Integer pictureQuota);

    /**
     * 按 {@code picture} 重算已用量。
     * <p>
     * 只统计未删除图片，与上传/删除的增量口径保持一致。
     *
     * @param userId 用户 id；null 表示全量重算
     * @return 受影响行数
     */
    int recalcQuota(@Param("userId") Long userId);

    /**
     * 全量重算时按主键游标分批取用户 id
     *
     * @param afterId 只取 id 大于该值的记录（首次传 0）
     * @param limit   单批条数
     * @return 用户 id 列表
     */
    List<Long> selectIdsForQuotaRecalc(@Param("afterId") Long afterId,
                                       @Param("limit") int limit);
}
