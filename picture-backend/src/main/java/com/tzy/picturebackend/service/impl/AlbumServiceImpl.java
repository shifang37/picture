package com.tzy.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.mapper.AlbumMapper;
import com.tzy.picturebackend.mapper.AlbumPictureMapper;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.model.dto.album.AlbumAddRequest;
import com.tzy.picturebackend.model.dto.album.AlbumPageRequest;
import com.tzy.picturebackend.model.dto.album.AlbumPictureRequest;
import com.tzy.picturebackend.model.dto.album.AlbumUpdateRequest;
import com.tzy.picturebackend.model.entity.Album;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.vo.AlbumDetailVO;
import com.tzy.picturebackend.model.vo.AlbumPictureCountVO;
import com.tzy.picturebackend.model.vo.AlbumVO;
import com.tzy.picturebackend.model.vo.PictureVO;
import com.tzy.picturebackend.service.AlbumService;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 图片专辑实现。
 * <p>
 * 一致性要点：
 * <ol>
 *   <li>{@code pictureCount} 与 {@code album_picture} 在同一事务里更新，并提供管理端重算入口；</li>
 *   <li>加图用 {@code INSERT IGNORE} 幂等，重复图片不会重复计数；</li>
 *   <li>公开口径只统计已过审图片：{@code publicCount}、封面回退、公开图片列表都遵循同一条规则；</li>
 *   <li>私密专辑对非作者统一返回 404，不暴露「存在但无权访问」。</li>
 * </ol>
 */
@Service
@Slf4j
public class AlbumServiceImpl extends ServiceImpl<AlbumMapper, Album> implements AlbumService {

    /**
     * 公开分页上限，与公开图片列表保持一致
     */
    private static final int MAX_PAGE_SIZE = 20;

    /**
     * 计数重算单批条数
     */
    private static final int RECOUNT_BATCH_SIZE = 500;

    private final AlbumPictureMapper albumPictureMapper;

    private final PictureMapper pictureMapper;

    private final PictureService pictureService;

    private final UserService userService;

    public AlbumServiceImpl(AlbumPictureMapper albumPictureMapper,
                            PictureMapper pictureMapper,
                            PictureService pictureService,
                            UserService userService) {
        this.albumPictureMapper = albumPictureMapper;
        this.pictureMapper = pictureMapper;
        this.pictureService = pictureService;
        this.userService = userService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AlbumVO add(AlbumAddRequest request, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        String name = StrUtil.trim(request.getName());
        ThrowUtils.throwIf(StrUtil.isEmpty(name), ErrorCode.PARAMS_ERROR, "专辑名称不能为空");
        Album album = new Album();
        album.setUserId(loginUser.getId());
        album.setName(name);
        album.setDescription(normalizeText(request.getDescription()));
        album.setPictureCount(0);
        album.setIsPublic(Boolean.TRUE.equals(request.getIsPublic()) ? 1 : 0);
        ThrowUtils.throwIf(!this.save(album), ErrorCode.OPERATION_ERROR, "创建专辑失败");
        return toVO(album, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AlbumVO update(AlbumUpdateRequest request, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null || request.getId() == null || request.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        Album album = requireAlbum(request.getId());
        requireManage(album, loginUser);
        Album update = new Album();
        update.setId(album.getId());
        boolean changed = false;
        if (request.getName() != null) {
            String name = StrUtil.trim(request.getName());
            ThrowUtils.throwIf(StrUtil.isEmpty(name), ErrorCode.PARAMS_ERROR, "专辑名称不能为空");
            update.setName(name);
            changed = true;
        }
        if (request.getDescription() != null) {
            update.setDescription(normalizeText(request.getDescription()));
            changed = true;
        }
        if (request.getIsPublic() != null) {
            update.setIsPublic(request.getIsPublic());
            changed = true;
        }
        if (request.getCoverPictureId() != null) {
            // 封面必须已经在专辑内，避免把任意图片挂成一个专辑的门面
            ThrowUtils.throwIf(
                    albumPictureMapper.countByAlbumAndPictureId(album.getId(), request.getCoverPictureId()) <= 0,
                    ErrorCode.PARAMS_ERROR, "封面图片不在专辑内");
            update.setCoverPictureId(request.getCoverPictureId());
            changed = true;
        }
        ThrowUtils.throwIf(!changed, ErrorCode.PARAMS_ERROR, "没有需要更新的字段");
        update.setEditTime(new Date());
        ThrowUtils.throwIf(!this.updateById(update), ErrorCode.OPERATION_ERROR, "更新专辑失败");
        return toVO(requireAlbum(album.getId()), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long id, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "专辑 id 不能为空");
        Album album = requireAlbum(id);
        requireManage(album, loginUser);
        ThrowUtils.throwIf(!this.removeById(id), ErrorCode.OPERATION_ERROR, "删除专辑失败");
        // 关系表物理删除；图片本身不受影响
        albumPictureMapper.deleteByAlbumId(id);
        return true;
    }

    @Override
    public AlbumDetailVO get(Long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "专辑 id 不能为空");
        Album album = requireAlbum(id);
        User loginUser = userService.getLoginUserOrNull(request);
        boolean canManage = canManage(album, loginUser);
        if (!canManage && !isPublic(album)) {
            // 私密专辑对非作者一律当作不存在，避免探测
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "专辑不存在");
        }
        AlbumDetailVO detailVO = new AlbumDetailVO();
        BeanUtils.copyProperties(toVO(album, !canManage), detailVO);
        detailVO.setCanManage(canManage);
        return detailVO;
    }

    @Override
    public Page<AlbumVO> pageMy(AlbumPageRequest request, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        long current = request == null ? 1 : request.getCurrent();
        long size = request == null ? 10 : request.getPageSize();
        ThrowUtils.throwIf(current < 1 || size < 1, ErrorCode.PARAMS_ERROR, "分页参数不合法");
        size = Math.min(size, MAX_PAGE_SIZE);
        QueryWrapper<Album> queryWrapper = new QueryWrapper<Album>()
                .eq("userId", loginUser.getId())
                .orderByDesc("createTime")
                .orderByDesc("id");
        Page<Album> albumPage = this.page(new Page<>(current, size), queryWrapper);
        return toVOPage(albumPage, false);
    }

    @Override
    public Page<AlbumVO> pagePublic(Long userId, long current, long pageSize) {
        ThrowUtils.throwIf(current < 1 || pageSize < 1, ErrorCode.PARAMS_ERROR, "分页参数不合法");
        long size = Math.min(pageSize, MAX_PAGE_SIZE);
        QueryWrapper<Album> queryWrapper = new QueryWrapper<Album>().eq("isPublic", 1);
        if (userId != null) {
            ThrowUtils.throwIf(userId <= 0, ErrorCode.PARAMS_ERROR, "用户 id 不合法");
            queryWrapper.eq("userId", userId);
        }
        queryWrapper.orderByDesc("createTime").orderByDesc("id");
        Page<Album> albumPage = this.page(new Page<>(current, size), queryWrapper);
        return toVOPage(albumPage, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AlbumVO addPictures(AlbumPictureRequest request, User loginUser) {
        Album album = requireAlbumForWrite(request, loginUser);
        Set<Long> pictureIds = normalizePictureIds(request.getPictureIds());
        boolean isAdmin = userService.isAdmin(loginUser);
        // 一条 IN 查询校验归属，避免逐张查库
        List<Picture> pictures = pictureService.listByIds(pictureIds);
        ThrowUtils.throwIf(pictures.size() != pictureIds.size(), ErrorCode.PARAMS_ERROR,
                "存在不存在的图片，整批未加入");
        for (Picture picture : pictures) {
            if (!isAdmin && !Objects.equals(picture.getUserId(), album.getUserId())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能把自己上传的图片加入专辑");
            }
        }
        int inserted = albumPictureMapper.insertIgnore(album.getId(), pictureIds, new Date());
        if (inserted > 0) {
            baseMapper.incrementPictureCount(album.getId(), inserted);
            // 首次加图时自动挑一张已过审图片作为封面，避免公开页一直空白
            if (album.getCoverPictureId() == null) {
                Long coverId = pictures.stream()
                        .filter(picture -> Objects.equals(picture.getReviewStatus(),
                                PictureReviewStatusEnum.PASS.getValue()))
                        .map(Picture::getId)
                        .findFirst()
                        .orElse(null);
                if (coverId != null) {
                    this.update(new UpdateWrapper<Album>()
                            .eq("id", album.getId())
                            .set("coverPictureId", coverId));
                }
            }
        }
        return toVO(requireAlbum(album.getId()), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AlbumVO removePictures(AlbumPictureRequest request, User loginUser) {
        Album album = requireAlbumForWrite(request, loginUser);
        Set<Long> pictureIds = normalizePictureIds(request.getPictureIds());
        int removed = albumPictureMapper.deleteByAlbumAndPictureIds(album.getId(), pictureIds);
        if (removed > 0) {
            baseMapper.incrementPictureCount(album.getId(), -removed);
            // 封面被移除时自动回退到最新一张；没有图片则清空封面
            if (album.getCoverPictureId() != null
                    && pictureIds.contains(album.getCoverPictureId())) {
                Long fallbackId = albumPictureMapper.selectLatestPictureId(album.getId());
                this.update(new UpdateWrapper<Album>()
                        .eq("id", album.getId())
                        .set("coverPictureId", fallbackId));
            }
        }
        return toVO(requireAlbum(album.getId()), false);
    }

    @Override
    public Page<PictureVO> pagePictures(Long albumId, long current, long size, HttpServletRequest request) {
        ThrowUtils.throwIf(albumId == null || albumId <= 0, ErrorCode.PARAMS_ERROR, "专辑 id 不能为空");
        ThrowUtils.throwIf(current < 1 || size < 1, ErrorCode.PARAMS_ERROR, "分页参数不合法");
        Album album = requireAlbum(albumId);
        User loginUser = userService.getLoginUserOrNull(request);
        boolean canManage = canManage(album, loginUser);
        if (!canManage && !isPublic(album)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "专辑不存在");
        }
        Page<Picture> picturePage = new Page<>(current, Math.min(size, MAX_PAGE_SIZE));
        pictureMapper.selectPageByAlbum(picturePage, albumId, !canManage);
        return pictureService.getPictureVOPage(picturePage, request);
    }

    @Override
    public int recount(Long albumId) {
        if (albumId != null) {
            ThrowUtils.throwIf(albumId <= 0, ErrorCode.PARAMS_ERROR, "专辑 id 必须为正数");
            return baseMapper.recountPictureCounts(List.of(albumId));
        }
        long afterId = 0L;
        int total = 0;
        while (true) {
            List<Long> ids = baseMapper.selectIdsForRecount(afterId, RECOUNT_BATCH_SIZE);
            if (CollUtil.isEmpty(ids)) {
                return total;
            }
            total += baseMapper.recountPictureCounts(ids);
            afterId = ids.get(ids.size() - 1);
            if (ids.size() < RECOUNT_BATCH_SIZE) {
                return total;
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByUserId(Long userId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户 id 不能为空");
        List<Album> albums = listByUserId(userId);
        if (CollUtil.isEmpty(albums)) {
            return;
        }
        List<Long> albumIds = albums.stream().map(Album::getId).toList();
        albumPictureMapper.deleteByAlbumIds(albumIds);
        this.remove(new QueryWrapper<Album>().eq("userId", userId));
        log.info("删除用户时清理专辑, userId = {}, albums = {}", userId, albumIds.size());
    }

    @Override
    public List<Album> listByUserId(Long userId) {
        if (userId == null || userId <= 0) {
            return Collections.emptyList();
        }
        return this.list(new QueryWrapper<Album>().eq("userId", userId));
    }

    /**
     * 校验写操作：请求、专辑存在性与管理权限
     */
    private Album requireAlbumForWrite(AlbumPictureRequest request, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(request == null || request.getAlbumId() == null || request.getAlbumId() <= 0,
                ErrorCode.PARAMS_ERROR, "专辑 id 不能为空");
        Album album = requireAlbum(request.getAlbumId());
        requireManage(album, loginUser);
        return album;
    }

    private Album requireAlbum(Long id) {
        Album album = this.getById(id);
        ThrowUtils.throwIf(album == null, ErrorCode.NOT_FOUND_ERROR, "专辑不存在");
        return album;
    }

    private void requireManage(Album album, User loginUser) {
        if (!canManage(album, loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权操作该专辑");
        }
    }

    private boolean canManage(Album album, User loginUser) {
        if (album == null || loginUser == null) {
            return false;
        }
        return Objects.equals(album.getUserId(), loginUser.getId()) || userService.isAdmin(loginUser);
    }

    private static boolean isPublic(Album album) {
        return album != null && Objects.equals(album.getIsPublic(), 1);
    }

    /**
     * 图片 id 去重与合法性校验：单次最多 100 张，与 DTO 上限一致
     */
    private static Set<Long> normalizePictureIds(List<Long> pictureIds) {
        ThrowUtils.throwIf(CollUtil.isEmpty(pictureIds), ErrorCode.PARAMS_ERROR, "请选择要操作的图片");
        Set<Long> ids = new LinkedHashSet<>();
        for (Long pictureId : pictureIds) {
            ThrowUtils.throwIf(pictureId == null || pictureId <= 0, ErrorCode.PARAMS_ERROR,
                    "存在非法图片 id");
            ids.add(pictureId);
        }
        ThrowUtils.throwIf(ids.size() > 100, ErrorCode.PARAMS_ERROR, "单次最多操作 100 张图片");
        return ids;
    }

    private static String normalizeText(String value) {
        String trimmed = StrUtil.trim(value);
        return StrUtil.isEmpty(trimmed) ? null : trimmed;
    }

    private Page<AlbumVO> toVOPage(Page<Album> albumPage, boolean publicCoverOnly) {
        Page<AlbumVO> voPage = new Page<>(albumPage.getCurrent(), albumPage.getSize(), albumPage.getTotal());
        voPage.setRecords(buildVOs(albumPage.getRecords(), publicCoverOnly));
        return voPage;
    }

    private AlbumVO toVO(Album album, boolean publicCoverOnly) {
        List<AlbumVO> albumVOs = buildVOs(List.of(album), publicCoverOnly);
        return albumVOs.isEmpty() ? null : albumVOs.get(0);
    }

    /**
     * 批量转换：公开数量、封面、作者信息都按批查询，避免逐条查库
     */
    private List<AlbumVO> buildVOs(List<Album> albums, boolean publicCoverOnly) {
        if (CollUtil.isEmpty(albums)) {
            return List.of();
        }
        List<Long> albumIds = albums.stream().map(Album::getId).toList();
        // 公开数量：一次 GROUP BY 拿到整页数据
        Map<Long, Long> publicCounts = new HashMap<>();
        List<AlbumPictureCountVO> counts = albumPictureMapper.selectPublicCounts(albumIds);
        if (CollUtil.isNotEmpty(counts)) {
            for (AlbumPictureCountVO count : counts) {
                publicCounts.put(count.getAlbumId(),
                        count.getPublicCount() == null ? 0L : count.getPublicCount());
            }
        }
        // 配置的封面一次性取回；失效时再按专辑做一次回退查询
        Set<Long> coverIds = albums.stream()
                .map(Album::getCoverPictureId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Picture> coverMap = new HashMap<>();
        if (!coverIds.isEmpty()) {
            for (Picture cover : pictureService.listByIds(coverIds)) {
                coverMap.put(cover.getId(), cover);
            }
        }
        Set<Long> userIds = albums.stream()
                .map(Album::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (User user : userService.listByIds(userIds)) {
                userMap.put(user.getId(), user);
            }
        }

        List<AlbumVO> result = new ArrayList<>(albums.size());
        for (Album album : albums) {
            AlbumVO albumVO = new AlbumVO();
            BeanUtils.copyProperties(album, albumVO);
            albumVO.setPublicCount(publicCounts.getOrDefault(album.getId(), 0L));
            Picture cover = resolveCover(album, coverMap.get(album.getCoverPictureId()), publicCoverOnly);
            albumVO.setCoverUrl(cover == null ? null : cover.getUrl());
            albumVO.setUser(userService.getPublicUserVO(userMap.get(album.getUserId())));
            result.add(albumVO);
        }
        return result;
    }

    /**
     * 封面解析：配置的封面不可用（被删除 / 未过审且对外展示）时，
     * 回退到专辑里最新一张可见图片；公开视角只接受已过审图片。
     */
    private Picture resolveCover(Album album, Picture configuredCover, boolean publicCoverOnly) {
        if (configuredCover != null && (!publicCoverOnly
                || Objects.equals(configuredCover.getReviewStatus(),
                PictureReviewStatusEnum.PASS.getValue()))) {
            return configuredCover;
        }
        Long fallbackId = publicCoverOnly
                ? albumPictureMapper.selectFirstPublicPictureId(album.getId())
                : albumPictureMapper.selectLatestPictureId(album.getId());
        return fallbackId == null ? null : pictureService.getById(fallbackId);
    }
}
