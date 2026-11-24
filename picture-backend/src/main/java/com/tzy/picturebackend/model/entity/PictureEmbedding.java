package com.tzy.picturebackend.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 图片向量化状态。
 * <p>
 * 一行对应一张图片，文本与图片两侧分别记录 hash、模型、版本与状态：
 * hash 未变且模型版本一致时，消费者跳过重算以省费用；状态为 2 时由重试或全量重建兜底。
 * 这张表只是派生索引的缓存，不是事实源，可整表重建。
 */
@TableName("picture_embedding")
@Data
public class PictureEmbedding implements Serializable {

    /**
     * 图片 id（与 picture 一对一，不使用自增主键）
     */
    @TableId(value = "pictureId", type = IdType.INPUT)
    private Long pictureId;

    /**
     * 语料 sha256
     */
    private String textHash;

    /**
     * 文本向量模型
     */
    private String textModel;

    /**
     * 语料/向量版本
     */
    private String textVersion;

    /**
     * 文本向量状态：0-待处理 1-成功 2-失败
     */
    private Integer textStatus;

    /**
     * 图片内容 hash
     */
    private String imageHash;

    /**
     * 图片向量模型
     */
    private String imageModel;

    /**
     * 图片向量版本
     */
    private String imageVersion;

    /**
     * 图片向量状态：0-待处理 1-成功 2-失败
     */
    private Integer imageStatus;

    /**
     * 累计失败次数
     */
    private Integer attemptCount;

    /**
     * 最近一次失败原因
     */
    private String lastError;

    private Date createTime;

    private Date updateTime;

    private static final long serialVersionUID = 1L;
}
