package com.tzy.picturebackend.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tzy.picturebackend.model.dto.interaction.InteractionPageRequest;
import com.tzy.picturebackend.model.dto.interaction.InteractionRequest;
import com.tzy.picturebackend.model.entity.PictureInteraction;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.InteractionStatusVO;
import com.tzy.picturebackend.model.vo.PictureVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 图片互动（收藏 / 点赞）服务。
 * <p>
 * 幂等语义：重复 add / remove 不报错，直接返回当前状态；只有真正新增或删除关系行时才调整计数。
 */
public interface PictureInteractionService extends IService<PictureInteraction> {

    /**
     * 收藏 / 点赞；已存在时不做任何修改，直接返回当前状态
     *
     * @param request   图片 id 与互动类型
     * @param loginUser 当前登录用户
     * @return 最新的选中状态与计数
     */
    InteractionStatusVO add(InteractionRequest request, User loginUser);

    /**
     * 取消收藏 / 取消点赞；不存在时直接返回当前状态
     */
    InteractionStatusVO remove(InteractionRequest request, User loginUser);

    /**
     * 查询某张图片的互动状态与计数；未登录时选中状态恒为 false
     * <p>
     * 图片不存在或对当前请求者不可见时统一返回 40400，避免通过状态接口探测未过审内容。
     */
    InteractionStatusVO status(Long pictureId, HttpServletRequest request);

    /**
     * 我的收藏 / 我的点赞（只包含已通过审核且未删除的图片，按互动时间倒序）
     */
    Page<PictureVO> pageMy(InteractionPageRequest request, User loginUser, HttpServletRequest httpRequest);

    /**
     * 按关系表重算收藏数与点赞数，修正计数漂移
     *
     * @param pictureId 为 null 时全量重算（按 id 游标分批）
     * @return 实际被重算的图片数
     */
    int recount(Long pictureId);
}
