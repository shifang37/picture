package com.tzy.picturebackend.event;

import lombok.Getter;

/**
 * 图片事件类型。
 * <p>
 * {@code routingKey} 同时是 RabbitMQ topic exchange 的绑定键，新增类型时要同步在 {@code PictureMqConfig}
 * 里补充交换机绑定，否则事件会被路由丢弃。
 */
@Getter
public enum PictureEventType {

    /**
     * 新上传或重传成功
     */
    UPLOADED("picture.uploaded"),

    /**
     * 机审完成
     */
    MODERATED("picture.moderated"),

    /**
     * 人工审核、编辑、删除
     */
    CHANGED("picture.changed"),

    /**
     * 批量审核：一条消息带整批图片 id
     */
    BATCH_CHANGED("picture.batch.changed");

    private final String routingKey;

    PictureEventType(String routingKey) {
        this.routingKey = routingKey;
    }
}
