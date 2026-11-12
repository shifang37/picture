package com.tzy.picturebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tzy.picturebackend.model.entity.PictureReviewLog;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author shifang37
 */
public interface PictureReviewLogMapper extends BaseMapper<PictureReviewLog> {

    /**
     * 一条 INSERT 写入整批审核记录，避免批量审核时逐条写库
     *
     * @param logs 已补全 id、审核人、状态与时间的记录
     * @return 写入行数
     */
    int insertBatch(@Param("logs") List<PictureReviewLog> logs);
}
