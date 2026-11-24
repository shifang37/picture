package com.tzy.picturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tzy.picturebackend.model.entity.PictureEmbedding;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 图片向量化状态 Mapper。
 * <p>
 * 表以 pictureId 为主键，写入统一走 {@code INSERT ... ON DUPLICATE KEY UPDATE}，
 * 避免「先查再插」在重复消息并发时撞主键。
 */
@Mapper
public interface PictureEmbeddingMapper extends BaseMapper<PictureEmbedding> {

    /**
     * 文本向量成功：写入本次语料 hash、模型与版本，状态置为成功。
     */
    @Insert("""
            INSERT INTO picture_embedding (pictureId, textHash, textModel, textVersion, textStatus, lastError)
            VALUES (#{pictureId}, #{hash}, #{model}, #{version}, 1, NULL) AS new
            ON DUPLICATE KEY UPDATE
                textHash = new.textHash,
                textModel = new.textModel,
                textVersion = new.textVersion,
                textStatus = 1,
                lastError = NULL
            """)
    int markTextSuccess(@Param("pictureId") Long pictureId,
                        @Param("hash") String hash,
                        @Param("model") String model,
                        @Param("version") String version);

    /**
     * 图片向量成功。
     */
    @Insert("""
            INSERT INTO picture_embedding (pictureId, imageHash, imageModel, imageVersion, imageStatus, lastError)
            VALUES (#{pictureId}, #{hash}, #{model}, #{version}, 1, NULL) AS new
            ON DUPLICATE KEY UPDATE
                imageHash = new.imageHash,
                imageModel = new.imageModel,
                imageVersion = new.imageVersion,
                imageStatus = 1,
                lastError = NULL
            """)
    int markImageSuccess(@Param("pictureId") Long pictureId,
                         @Param("hash") String hash,
                         @Param("model") String model,
                         @Param("version") String version);

    /**
     * 记录一次失败：只把本次尝试过的一侧置为失败，另一侧保持原状态；
     * attemptCount 累加，lastError 截断由调用方负责。
     */
    @Insert("""
            INSERT INTO picture_embedding (pictureId, textStatus, imageStatus, attemptCount, lastError)
            VALUES (#{pictureId}, #{textStatus}, #{imageStatus}, 1, #{error}) AS new
            ON DUPLICATE KEY UPDATE
                textStatus = IF(#{textFailed}, 2, picture_embedding.textStatus),
                imageStatus = IF(#{imageFailed}, 2, picture_embedding.imageStatus),
                attemptCount = picture_embedding.attemptCount + 1,
                lastError = new.lastError
            """)
    int markFailure(@Param("pictureId") Long pictureId,
                    @Param("textFailed") boolean textFailed,
                    @Param("imageFailed") boolean imageFailed,
                    @Param("textStatus") int textStatus,
                    @Param("imageStatus") int imageStatus,
                    @Param("error") String error);

    /**
     * 公开图片中文本向量尚未成功的数量（包含尚未建档的图片），用于状态页的待处理数。
     */
    @Select("""
            SELECT COUNT(*)
            FROM picture p
            LEFT JOIN picture_embedding e ON e.pictureId = p.id
            WHERE p.isDelete = 0
              AND p.reviewStatus = 1
              AND (e.pictureId IS NULL OR e.textStatus <> 1)
            """)
    long countPendingPublic();

    /**
     * 任一侧失败的图片数量，用于状态页与告警。
     */
    @Select("""
            SELECT COUNT(*)
            FROM picture_embedding
            WHERE textStatus = 2 OR imageStatus = 2
            """)
    long countFailed();

    /**
     * 文本向量已成功且当前公开的图片数量，用于向量索引一致性巡检。
     */
    @Select("""
            SELECT COUNT(*)
            FROM picture p
            JOIN picture_embedding e ON e.pictureId = p.id
            WHERE p.isDelete = 0
              AND p.reviewStatus = 1
              AND e.textStatus = 1
            """)
    long countSuccessPublic();
}
