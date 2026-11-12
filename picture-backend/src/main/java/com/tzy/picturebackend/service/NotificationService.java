package com.tzy.picturebackend.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.tzy.picturebackend.model.dto.notification.NotificationMessage;
import com.tzy.picturebackend.model.entity.Notification;
import com.tzy.picturebackend.model.vo.NotificationVO;

import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * 站内通知服务。
 * <p>
 * 读写都按「接收人」隔离：查询、已读、删除的 SQL 一律带 {@code userId} 条件，
 * 混入他人 id 时静默忽略而不是报错，避免通过错误信息枚举他人通知。
 */
public interface NotificationService extends IService<Notification> {

    /**
     * 批量写入通知（幂等）；失败由调用方兜住，不影响业务流程
     *
     * @param messages 单批建议不超过 500 条，内部按 500 自动分片
     */
    void notifyBatch(Collection<NotificationMessage> messages);

    /**
     * 我的通知分页，按时间倒序
     *
     * @param onlyUnread 是否只看未读
     */
    Page<NotificationVO> page(Long userId, boolean onlyUnread, long current, long pageSize);

    /**
     * 未读数量
     */
    long unreadCount(Long userId);

    /**
     * 批量标记已读
     *
     * @return 实际更新条数
     */
    int markRead(Long userId, Collection<Long> ids);

    /**
     * 全部标记已读
     */
    int markAllRead(Long userId);

    /**
     * 批量删除自己的通知
     */
    int delete(Long userId, Collection<Long> ids);

    /**
     * 管理员发送系统公告：给每个用户各写一行
     *
     * @return 收到公告的用户数
     */
    int broadcast(String title, String content);

    /**
     * 清理已读且超过保留期的通知
     *
     * @return 删除条数
     */
    int purgeReadBefore(Date before, int limit);

    /**
     * 用户被删除时清理其通知
     */
    void deleteByUserId(Long userId);
}
