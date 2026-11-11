package com.tzy.picturebackend.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 图片事件消息体。
 * <p>
 * 只带标识，不带名称、简介、状态等业务数据：消费者一律按 {@code pictureId} 回查数据库。
 * 这样重复投递和乱序都不会把过期数据写进下游。
 *
 * @param eventId   事件唯一标识，用于追踪和排查重复投递
 * @param type      事件类型，对应 RabbitMQ 路由键
 * @param pictureId 图片 id
 * @param occurredAt 事件产生时间
 */
public record PictureEvent(String eventId, PictureEventType type, Long pictureId, Instant occurredAt) {

    public PictureEvent {
        Objects.requireNonNull(eventId, "eventId 不能为空");
        Objects.requireNonNull(type, "type 不能为空");
        Objects.requireNonNull(pictureId, "pictureId 不能为空");
        Objects.requireNonNull(occurredAt, "occurredAt 不能为空");
    }

    /**
     * 构造一条新的图片事件：自动生成事件 id 和发生时间
     */
    public static PictureEvent of(PictureEventType type, Long pictureId) {
        return new PictureEvent(UUID.randomUUID().toString(), type, pictureId, Instant.now());
    }
}
