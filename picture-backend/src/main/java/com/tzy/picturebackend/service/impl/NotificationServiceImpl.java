package com.tzy.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tzy.picturebackend.config.NotificationProperties;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.mapper.NotificationMapper;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.dto.notification.NotificationMessage;
import com.tzy.picturebackend.model.entity.Notification;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.NotificationVO;
import com.tzy.picturebackend.service.NotificationService;
import com.tzy.picturebackend.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 站内通知实现。
 * <p>
 * 三条硬约束：
 * <ol>
 *   <li>写入幂等：{@code INSERT IGNORE} + 唯一键 {@code (userId, dedupeKey)}，重复投递不会刷屏；</li>
 *   <li>写入失败不影响主流程：由监听器 try/catch 兜底，这里抛出的异常只应出现在管理端显式调用的接口上；</li>
 *   <li>未读上限：插入后把最旧的未读自动置为已读，保证红点一定有办法消掉。</li>
 * </ol>
 */
@Service
@Slf4j
public class NotificationServiceImpl
        extends ServiceImpl<NotificationMapper, Notification>
        implements NotificationService {

    /**
     * 单批插入条数，避免超长 SQL 与过大的事务
     */
    static final int INSERT_BATCH_SIZE = 500;

    /**
     * title / content 的数据库字段长度，超长时截断而不是让整批插入失败
     */
    private static final int TITLE_MAX_LENGTH = 128;

    private static final int CONTENT_MAX_LENGTH = 1024;

    private final NotificationProperties notificationProperties;

    private final PictureMetrics pictureMetrics;

    private final UserService userService;

    public NotificationServiceImpl(NotificationProperties notificationProperties,
                                   PictureMetrics pictureMetrics,
                                   UserService userService) {
        this.notificationProperties = notificationProperties;
        this.pictureMetrics = pictureMetrics;
        this.userService = userService;
    }

    @Override
    public void notifyBatch(Collection<NotificationMessage> messages) {
        if (CollUtil.isEmpty(messages)) {
            return;
        }
        Date createTime = new Date();
        List<Notification> rows = new ArrayList<>(messages.size());
        Set<Long> userIds = new LinkedHashSet<>();
        for (NotificationMessage message : messages) {
            Notification row = toEntity(message, createTime);
            if (row != null) {
                rows.add(row);
                userIds.add(row.getUserId());
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        for (int start = 0; start < rows.size(); start += INSERT_BATCH_SIZE) {
            List<Notification> batch = rows.subList(start, Math.min(start + INSERT_BATCH_SIZE, rows.size()));
            baseMapper.insertIgnoreBatch(batch);
        }
        for (Notification row : rows) {
            // 按「尝试发送」计数：唯一键去重命中的那一部分也计入，便于观察事件量是否异常
            pictureMetrics.notificationSent(row.getType());
        }
        // 未读上限保护：只保留最新 maxUnread 条未读。
        // 先查未读数再决定是否收敛：绝大多数用户远低于上限，避免每次写通知都跑一条带子查询的 UPDATE
        int maxUnread = Math.max(1, notificationProperties.getMaxUnread());
        for (Long userId : userIds) {
            try {
                if (baseMapper.countUnread(userId) > maxUnread) {
                    baseMapper.markOldestRead(userId, maxUnread);
                }
            } catch (RuntimeException e) {
                // 清理失败不影响通知已经写入的事实，下一批写入时会再次尝试
                log.warn("收敛未读通知失败, userId = {}", userId, e);
            }
        }
    }

    @Override
    public Page<NotificationVO> page(Long userId, boolean onlyUnread, long current, long pageSize) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        // 直接使用 mapper 返回的分页对象：它带着数据库统计出来的 total 与本页记录
        IPage<Notification> notificationPage = baseMapper.selectUserPage(
                new Page<>(current, pageSize), userId, onlyUnread);
        Page<NotificationVO> voPage = new Page<>(current, pageSize, notificationPage.getTotal());
        List<NotificationVO> records = new ArrayList<>(notificationPage.getRecords().size());
        for (Notification notification : notificationPage.getRecords()) {
            records.add(NotificationVO.objToVo(notification));
        }
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public long unreadCount(Long userId) {
        if (userId == null || userId <= 0) {
            return 0L;
        }
        return baseMapper.countUnread(userId);
    }

    @Override
    public int markRead(Long userId, Collection<Long> ids) {
        List<Long> validIds = validIds(userId, ids);
        if (validIds.isEmpty()) {
            return 0;
        }
        int updated = baseMapper.markRead(userId, validIds);
        if (updated > 0) {
            pictureMetrics.notificationRead(updated);
        }
        return updated;
    }

    @Override
    public int markAllRead(Long userId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        int updated = baseMapper.markAllRead(userId);
        if (updated > 0) {
            pictureMetrics.notificationRead(updated);
        }
        return updated;
    }

    @Override
    public int delete(Long userId, Collection<Long> ids) {
        List<Long> validIds = validIds(userId, ids);
        if (validIds.isEmpty()) {
            return 0;
        }
        return baseMapper.deleteByIds(userId, validIds);
    }

    @Override
    public int broadcast(String title, String content) {
        ThrowUtils.throwIf(StrUtil.isBlank(title), ErrorCode.PARAMS_ERROR, "标题不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(content), ErrorCode.PARAMS_ERROR, "正文不能为空");
        List<Long> userIds = listAllUserIds();
        if (userIds.isEmpty()) {
            return 0;
        }
        List<NotificationMessage> messages = new ArrayList<>(userIds.size());
        for (Long userId : userIds) {
            messages.add(NotificationMessage.system(userId, title, content));
        }
        notifyBatch(messages);
        return userIds.size();
    }

    @Override
    public int purgeReadBefore(Date before, int limit) {
        if (before == null) {
            return 0;
        }
        return baseMapper.deleteReadBefore(before, Math.max(1, Math.min(limit, 5000)));
    }

    @Override
    public void deleteByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }
        baseMapper.deleteByUserId(userId);
    }

    /**
     * 只取 id：公告需要遍历全部用户，没必要把密码哈希等字段读出来
     */
    private List<Long> listAllUserIds() {
        List<User> users = userService.list(new QueryWrapper<User>().select("id"));
        return users.stream()
                .map(User::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 消息转实体：补齐默认标题、截断超长文本、生成主键
     */
    private Notification toEntity(NotificationMessage message, Date createTime) {
        if (message == null || message.getUserId() == null || message.getType() == null) {
            return null;
        }
        Notification notification = new Notification();
        // 自定义多行 INSERT 不经过 MyBatis-Plus 的主键生成，这里显式生成
        notification.setId(IdWorker.getId());
        notification.setUserId(message.getUserId());
        notification.setType(message.getType().getValue());
        notification.setTitle(truncate(StrUtil.blankToDefault(message.getTitle(),
                message.getType().getDefaultTitle()), TITLE_MAX_LENGTH));
        notification.setContent(truncate(message.getContent(), CONTENT_MAX_LENGTH));
        notification.setBizType(message.getBizType());
        notification.setBizId(message.getBizId());
        notification.setDedupeKey(message.getDedupeKey());
        notification.setIsRead(0);
        notification.setCreateTime(createTime);
        return notification;
    }

    private static List<Long> validIds(Long userId, Collection<Long> ids) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.NOT_LOGIN_ERROR);
        if (CollUtil.isEmpty(ids)) {
            return List.of();
        }
        return ids.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }

    /**
     * 截断到数据库字段长度：审核意见最长 512，加上前缀可能超出，宁可截断也不能让整批插入失败
     */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
