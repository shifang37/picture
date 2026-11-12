package com.tzy.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tzy.picturebackend.event.PictureEventPublisher;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.mapper.PictureInteractionMapper;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.dto.interaction.InteractionPageRequest;
import com.tzy.picturebackend.model.dto.interaction.InteractionRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.PictureInteraction;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.PictureInteractionTypeEnum;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.vo.InteractionStatusVO;
import com.tzy.picturebackend.model.vo.PictureVO;
import com.tzy.picturebackend.service.PictureInteractionService;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 收藏 / 点赞实现。
 * <p>
 * 一致性要点：
 * <ol>
 *   <li>先写关系行、再改计数，且两者在同一个事务内；关系行插入失败（唯一键冲突）时不加计数；</li>
 *   <li>计数使用 {@code UPDATE ... SET x = x + delta} 原子自增，减操作由 SQL 里的 {@code GREATEST(0, ...)} 兜底；</li>
 *   <li>互动成功不失效公开列表缓存：列表里的计数最多滞后一个缓存周期（L1 30 秒 / L2 5 分钟），
 *       详情与状态接口实时，避免热门图片的互动把列表缓存打成筛子。</li>
 * </ol>
 */
@Service
@Slf4j
public class PictureInteractionServiceImpl
        extends ServiceImpl<PictureInteractionMapper, PictureInteraction>
        implements PictureInteractionService {

    /**
     * 「我的收藏」单页上限，与公开列表保持一致，避免一次拉取过多图片
     */
    private static final int MY_PAGE_MAX_SIZE = 20;

    /**
     * 计数重算单批条数
     */
    private static final int RECOUNT_BATCH_SIZE = 500;

    private final PictureService pictureService;

    private final PictureMapper pictureMapper;

    private final UserService userService;

    private final PictureMetrics pictureMetrics;

    private final PictureEventPublisher pictureEventPublisher;

    public PictureInteractionServiceImpl(PictureService pictureService,
                                         PictureMapper pictureMapper,
                                         UserService userService,
                                         PictureMetrics pictureMetrics,
                                         PictureEventPublisher pictureEventPublisher) {
        this.pictureService = pictureService;
        this.pictureMapper = pictureMapper;
        this.userService = userService;
        this.pictureMetrics = pictureMetrics;
        this.pictureEventPublisher = pictureEventPublisher;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InteractionStatusVO add(InteractionRequest request, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        PictureInteractionTypeEnum type = requireType(request == null ? null : request.getType());
        Picture picture = requirePublicPicture(request == null ? null : request.getPictureId());
        Long pictureId = picture.getId();
        Long userId = loginUser.getId();
        // 自定义多行 INSERT 走的是幂等 SQL，主键需要显式生成
        int inserted = baseMapper.insertIgnore(IdWorker.getId(), pictureId, userId, type.getValue(), new Date());
        if (inserted > 0) {
            // 只有真正新增关系行才累加计数，并发重复点击不会重复加
            int updated = pictureMapper.adjustInteractionCount(pictureId, type.getCounterColumn(), 1);
            ThrowUtils.throwIf(updated <= 0, ErrorCode.OPERATION_ERROR, "图片已被删除，请刷新后重试");
            pictureMetrics.interaction("add", type.getValue());
            // ES 模式下热度排序读的是索引里的计数快照，必须同步一次；列表缓存仍然不失效
            pictureEventPublisher.pictureChanged(pictureId);
        }
        InteractionStatusVO statusVO = statusOf(pictureId, userId);
        if (inserted == 0) {
            // 并发场景：另一请求刚插入了同一行，本事务的一致性读快照可能还看不到它。
            // 本次请求的语义就是「确保已收藏 / 已点赞」，因此强制把这一位置为 true，
            // 避免前端把乐观更新回滚成未选中状态。
            markSelected(statusVO, type, true);
        }
        return statusVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InteractionStatusVO remove(InteractionRequest request, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        PictureInteractionTypeEnum type = requireType(request == null ? null : request.getType());
        ThrowUtils.throwIf(request.getPictureId() == null || request.getPictureId() <= 0,
                ErrorCode.PARAMS_ERROR, "图片 id 不能为空");
        Long pictureId = request.getPictureId();
        Long userId = loginUser.getId();
        int deleted = baseMapper.deleteOne(pictureId, userId, type.getValue());
        if (deleted > 0) {
            // 图片可能已被物理删除：调整影响 0 行不算失败，计数列已经随行一起消失
            pictureMapper.adjustInteractionCount(pictureId, type.getCounterColumn(), -1);
            pictureMetrics.interaction("remove", type.getValue());
            pictureEventPublisher.pictureChanged(pictureId);
        } else {
            // 取消一个本来就不存在的互动：不报错、不改计数，但图片本身必须存在
            requireExistingPicture(pictureId);
        }
        InteractionStatusVO statusVO = statusOf(pictureId, userId);
        if (deleted == 0) {
            // 同上：并发下另一个请求刚删除了关系行，本事务快照可能仍显示「已选中」
            markSelected(statusVO, type, false);
        }
        return statusVO;
    }

    @Override
    public InteractionStatusVO status(Long pictureId, HttpServletRequest request) {
        ThrowUtils.throwIf(pictureId == null || pictureId <= 0, ErrorCode.PARAMS_ERROR, "图片 id 不能为空");
        Picture picture = pictureService.getById(pictureId);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        // 未过审图片只有作者/管理员可见，其他人一律当作不存在
        pictureService.checkPictureVisible(picture, request);
        User loginUser = userService.getLoginUserOrNull(request);
        return statusOf(pictureId, loginUser == null ? null : loginUser.getId());
    }

    @Override
    public Page<PictureVO> pageMy(InteractionPageRequest request, User loginUser,
                                  HttpServletRequest httpRequest) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        PictureInteractionTypeEnum type = requireType(request == null ? null : request.getType());
        long current = request.getCurrent();
        // 公开列表与「我的收藏」共用同一个分页上限，避免一次拉取过多数据
        long size = Math.min(request.getPageSize(), MY_PAGE_MAX_SIZE);
        Page<Picture> picturePage = new Page<>(current, size);
        // 自定义 SQL 内部已限定 isDelete = 0 且 reviewStatus = 1
        baseMapper.selectMyPicturePage(picturePage, loginUser.getId(), type.getValue());
        return pictureService.getPictureVOPage(picturePage, httpRequest);
    }

    @Override
    public int recount(Long pictureId) {
        if (pictureId != null) {
            ThrowUtils.throwIf(pictureId <= 0, ErrorCode.PARAMS_ERROR, "图片 id 必须为正数");
            return pictureMapper.recountInteractions(List.of(pictureId));
        }
        long afterId = 0L;
        int total = 0;
        while (true) {
            List<Long> ids = pictureMapper.selectIdsForRecount(afterId, RECOUNT_BATCH_SIZE);
            if (CollUtil.isEmpty(ids)) {
                return total;
            }
            total += pictureMapper.recountInteractions(ids);
            afterId = ids.get(ids.size() - 1);
            if (ids.size() < RECOUNT_BATCH_SIZE) {
                return total;
            }
        }
    }

    /**
     * 查询互动状态与计数；{@code userId} 为 null 表示未登录，只返回计数
     */
    private InteractionStatusVO statusOf(Long pictureId, Long userId) {
        Picture picture = requireExistingPicture(pictureId);
        InteractionStatusVO statusVO = InteractionStatusVO.of(picture);
        if (userId == null) {
            return statusVO;
        }
        List<PictureInteraction> interactions = baseMapper.selectByPictureAndUser(pictureId, userId);
        if (CollUtil.isNotEmpty(interactions)) {
            for (PictureInteraction interaction : interactions) {
                if (PictureInteractionTypeEnum.FAVORITE.getValue().equals(interaction.getType())) {
                    statusVO.setFavorite(true);
                } else if (PictureInteractionTypeEnum.LIKE.getValue().equals(interaction.getType())) {
                    statusVO.setLike(true);
                }
            }
        }
        return statusVO;
    }

    /**
     * 互动只允许发生在「已通过审核」的图片上：作者本人也不能收藏自己待审核的作品，
     * 保持「公开内容才可互动」这条规则没有例外。
     */
    private Picture requirePublicPicture(Long pictureId) {
        ThrowUtils.throwIf(pictureId == null || pictureId <= 0, ErrorCode.PARAMS_ERROR, "图片 id 不能为空");
        Picture picture = pictureService.getById(pictureId);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        ThrowUtils.throwIf(!Objects.equals(picture.getReviewStatus(), PictureReviewStatusEnum.PASS.getValue()),
                ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        return picture;
    }

    private Picture requireExistingPicture(Long pictureId) {
        Picture picture = pictureService.getById(pictureId);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        return picture;
    }

    /**
     * 按本次操作的语义强制设置选中位，避免并发下的一致性问题把响应结果带偏
     */
    private static void markSelected(InteractionStatusVO statusVO, PictureInteractionTypeEnum type,
                                     boolean selected) {
        if (type == PictureInteractionTypeEnum.FAVORITE) {
            statusVO.setFavorite(selected);
        } else {
            statusVO.setLike(selected);
        }
    }

    private static PictureInteractionTypeEnum requireType(String type) {
        PictureInteractionTypeEnum typeEnum = PictureInteractionTypeEnum.getEnumByValue(type);
        ThrowUtils.throwIf(typeEnum == null, ErrorCode.PARAMS_ERROR, "互动类型不合法");
        return typeEnum;
    }
}
