package com.tzy.picturebackend.service;

import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.UserQuotaVO;

/**
 * 用户存储配额：上传前预留、删除后释放、恢复前重新预留，并提供管理端调整与重算入口。
 * <p>
 * 配额只统计“未删除图片”，软删除立即释放额度；回收站保留期内 COS 仍占用存储，
 * 因此配额不等于 COS 实际用量。
 */
public interface UserQuotaService {

    /**
     * 查询用户配额（已用 / 上限）
     *
     * @param userId 用户 id
     * @return 配额信息；用户不存在时抛出 40400
     */
    UserQuotaVO getQuota(Long userId);

    /**
     * 原子预留配额：额度不足返回 false，调用方应拒绝上传。
     * <p>
     * 管理员（且开启 {@code app.quota.exempt-admin}）与关闭配额时始终返回 true，
     * 且不会修改已用量，因此释放时也必须走同一豁免判断。
     *
     * @param user          用户
     * @param deltaBytes    存储增量（字节），可为负
     * @param deltaPictures 图片数量增量，可为负
     * @return 是否预留成功
     */
    boolean reserve(User user, long deltaBytes, int deltaPictures);

    /**
     * 按用户 id 预留配额（恢复回收站图片等没有登录用户的场景）
     *
     * @param userId        用户 id
     * @param deltaBytes    存储增量
     * @param deltaPictures 图片数量增量
     * @return 是否预留成功；用户不存在时返回 false
     */
    boolean reserve(Long userId, long deltaBytes, int deltaPictures);

    /**
     * 调整已用量（不做额度校验）：释放时传负数，上传失败补偿时传正数。
     * <p>
     * 释放必须永远成功，否则用户删了图片却腾不出空间；因此这里不走
     * {@link #reserve(User, long, int)} 的限额条件。
     *
     * @param userId        用户 id
     * @param deltaBytes    存储增量（字节）：负数表示释放
     * @param deltaPictures 图片数量增量：负数表示释放
     */
    void release(Long userId, long deltaBytes, int deltaPictures);

    /**
     * 管理员调整配额
     *
     * @param userId       用户 id
     * @param storageQuota 存储配额；null 表示不修改
     * @param pictureQuota 图片数量上限；null 表示不修改
     * @return 调整后的配额
     */
    UserQuotaVO updateQuota(Long userId, Long storageQuota, Integer pictureQuota);

    /**
     * 按图片表重算已用量
     *
     * @param userId 用户 id；null 表示全量分批重算
     * @return 受影响的用户数
     */
    int recalc(Long userId);

    /**
     * 新建用户时写入配置里的默认配额
     *
     * @param user 待保存的用户
     */
    void applyDefaultQuota(User user);

    /**
     * 是否不受配额限制（全局关闭配额，或管理员且开启 exempt-admin）
     *
     * @param user 用户
     * @return 是否豁免
     */
    boolean isExempt(User user);
}
