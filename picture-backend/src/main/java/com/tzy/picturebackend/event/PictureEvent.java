package com.tzy.picturebackend.event;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 图片事件消息体。
 * <p>
 * 只带标识，不带名称、简介、状态等业务数据：消费者一律按 {@code pictureIds} 回查数据库。
 * 这样重复投递和乱序都不会把过期数据写进下游。
 * <p>
 * 单图操作（上传、机审、编辑、删除）只带一个 id；批量审核用一条事件带整批 id，
 * 避免一次批量操作向队列投递 N 条消息。
 *
 * @param eventId    事件唯一标识，用于追踪和排查重复投递
 * @param type       事件类型，对应 RabbitMQ 路由键
 * @param pictureIds 一张或多张图片 id
 * @param occurredAt 事件产生时间
 */
public record PictureEvent(String eventId, PictureEventType type, List<Long> pictureIds, Instant occurredAt) {

    public PictureEvent {
        Objects.requireNonNull(eventId, "eventId 不能为空");
        Objects.requireNonNull(type, "type 不能为空");
        Objects.requireNonNull(pictureIds, "pictureIds 不能为空");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
        if (pictureIds.isEmpty()) {
            throw new IllegalArgumentException("pictureIds 不能为空");
        }
        // 顺带拒绝 null 元素，并把列表固定下来，避免事件发布后被调用方修改
        pictureIds = List.copyOf(pictureIds);
    }

    /**
     * 构造一条单图事件：自动生成事件 id 和发生时间
     */
    public static PictureEvent of(PictureEventType type, Long pictureId) {
        return of(type, List.of(Objects.requireNonNull(pictureId, "pictureId 不能为空")));
    }

    /**
     * 构造一条多图事件：自动生成事件 id 和发生时间
     */
    public static PictureEvent of(PictureEventType type, Collection<Long> pictureIds) {
        Objects.requireNonNull(pictureIds, "pictureIds 不能为空");
        return new PictureEvent(UUID.randomUUID().toString(), type, List.copyOf(pictureIds), Instant.now());
    }
}
