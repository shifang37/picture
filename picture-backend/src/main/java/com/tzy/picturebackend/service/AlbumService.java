package com.tzy.picturebackend.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.model.dto.album.AlbumAddRequest;
import com.tzy.picturebackend.model.dto.album.AlbumPageRequest;
import com.tzy.picturebackend.model.dto.album.AlbumPictureRequest;
import com.tzy.picturebackend.model.dto.album.AlbumUpdateRequest;
import com.tzy.picturebackend.model.entity.Album;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.AlbumDetailVO;
import com.tzy.picturebackend.model.vo.AlbumVO;
import com.tzy.picturebackend.model.vo.PictureVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * 图片专辑服务。
 * <p>
 * 权限与可见性约定：
 * <ul>
 *   <li>写操作（改/删/加图/移图）仅作者本人或管理员；</li>
 *   <li>私密专辑对其他人一律返回 404，不区分「不存在」与「无权限」；</li>
 *   <li>公开专辑的图片与数量只统计已过审、未删除的图片；作者本人可看到自己的全部作品。</li>
 * </ul>
 */
public interface AlbumService {

    /**
     * 创建专辑（默认私密）
     *
     * @param request   创建请求
     * @param loginUser 当前登录用户
     * @return 新建的专辑
     */
    AlbumVO add(AlbumAddRequest request, User loginUser);

    /**
     * 更新专辑：只修改请求中出现的字段
     *
     * @param request   更新请求
     * @param loginUser 当前登录用户
     * @return 更新后的专辑
     */
    AlbumVO update(AlbumUpdateRequest request, User loginUser);

    /**
     * 删除专辑：逻辑删除专辑主体、物理删除关系，不删除图片
     *
     * @param id        专辑 id
     * @param loginUser 当前登录用户
     * @return 是否成功
     */
    boolean delete(Long id, User loginUser);

    /**
     * 获取专辑详情；私密专辑仅作者和管理员可见
     *
     * @param id      专辑 id
     * @param request 当前请求，用于判断访问者身份
     * @return 专辑详情
     */
    AlbumDetailVO get(Long id, HttpServletRequest request);

    /**
     * 分页获取当前用户的专辑（含私密）
     *
     * @param request   分页请求
     * @param loginUser 当前登录用户
     * @return 专辑分页
     */
    Page<AlbumVO> pageMy(AlbumPageRequest request, User loginUser);

    /**
     * 分页获取公开专辑；userId 为空时返回全站公开专辑
     *
     * @param userId   作者 id，可为空
     * @param current  当前页
     * @param pageSize 每页数量
     * @return 公开专辑分页
     */
    Page<AlbumVO> pagePublic(Long userId, long current, long pageSize);

    /**
     * 批量加入图片（单次最多 100 张，重复自动跳过）
     *
     * @param request   加图请求
     * @param loginUser 当前登录用户
     * @return 更新后的专辑
     */
    AlbumVO addPictures(AlbumPictureRequest request, User loginUser);

    /**
     * 批量移除图片（不删除图片本身）
     *
     * @param request   移图请求
     * @param loginUser 当前登录用户
     * @return 更新后的专辑
     */
    AlbumVO removePictures(AlbumPictureRequest request, User loginUser);

    /**
     * 分页获取专辑内的图片：公开访问只返回已过审图片
     *
     * @param albumId 专辑 id
     * @param current 当前页
     * @param size    每页数量
     * @param request 当前请求
     * @return 图片分页
     */
    Page<PictureVO> pagePictures(Long albumId, long current, long size, HttpServletRequest request);

    /**
     * 重算专辑图片数量，修正冗余计数漂移
     *
     * @param albumId 专辑 id；为空时全量重算
     * @return 重算行数
     */
    int recount(Long albumId);

    /**
     * 删除某个用户的全部专辑（删除用户时清理，避免留下孤儿数据）
     *
     * @param userId 用户 id
     */
    void deleteByUserId(Long userId);

    /**
     * 查询作者的全部专辑（内部使用）
     *
     * @param userId 作者 id
     * @return 专辑列表
     */
    List<Album> listByUserId(Long userId);
}
