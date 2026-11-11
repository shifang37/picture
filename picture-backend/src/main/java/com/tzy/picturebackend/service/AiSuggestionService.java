package com.tzy.picturebackend.service;

import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.PictureAiSuggestionVO;

/**
 * 图片 AI 建议：生成、查询与权限校验
 */
public interface AiSuggestionService {

    /**
     * 为一张图片异步生成建议。
     * <p>
     * 由消息消费者调用；内部处理每日上限、重试、结果校验与落库。
     * 任何外部调用失败都不会影响上传与审核主流程。
     *
     * @param picture 图片
     */
    void generate(Picture picture);

    /**
     * 查询某张图片的 AI 建议，仅作者与管理员可读。
     *
     * @param pictureId 图片 id
     * @param loginUser 当前登录用户
     * @return 建议；尚未生成时返回 null
     */
    PictureAiSuggestionVO getSuggestion(Long pictureId, User loginUser);
}
