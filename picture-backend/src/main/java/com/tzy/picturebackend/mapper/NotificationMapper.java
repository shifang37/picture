package com.tzy.picturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.model.entity.Notification;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * 站内通知的数据库操作。
 * <p>
 * 所有按用户维度的查询与更新都在 SQL 里带上 {@code userId} 条件，避免调用方遗漏归属校验；
 * 批量写入用 {@code INSERT IGNORE}，唯一键 {@code uk_user_dedupe (userId, dedupeKey)} 负责幂等。
 */
public interface NotificationMapper extends BaseMapper<Notification> {

    /**
     * 批量幂等插入；命中 {@code (userId, dedupeKey)} 唯一键的行会被忽略
     *
     * @param notifications 单批不超过 500 条
     * @return 实际插入行数（可能小于入参数量）
     */
    int insertIgnoreBatch(@Param("list") List<Notification> notifications);

    /**
     * 某个用户的通知分页，按创建时间倒序
     *
     * @param onlyUnread 是否只看未读
     */
    IPage<Notification> selectUserPage(Page<Notification> page,
                                       @Param("userId") Long userId,
                                       @Param("onlyUnread") boolean onlyUnread);

    /**
     * 未读数
     */
    long countUnread(@Param("userId") Long userId);

    /**
     * 批量标记已读；混入他人的通知 id 会被忽略（不会报错，避免枚举他人通知）
     *
     * @return 实际更新行数
     */
    int markRead(@Param("userId") Long userId,
                 @Param("ids") Collection<Long> ids);

    /**
     * 全部标记已读
     */
    int markAllRead(@Param("userId") Long userId);

    /**
     * 批量删除自己的通知
     */
    int deleteByIds(@Param("userId") Long userId,
                    @Param("ids") Collection<Long> ids);

    /**
     * 未读上限保护：只保留最新的 {@code keepCount} 条未读，其余置为已读
     *
     * @return 被自动置为已读的行数
     */
    int markOldestRead(@Param("userId") Long userId,
                       @Param("keepCount") int keepCount);

    /**
     * 清理已读且超过保留期的通知，单批限量避免长事务
     *
     * @return 删除行数
     */
    int deleteReadBefore(@Param("before") Date before,
                         @Param("limit") int limit);

    /**
     * 用户被删除时清理其全部通知
     */
    int deleteByUserId(@Param("userId") Long userId);
}
