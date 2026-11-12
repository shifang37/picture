package com.tzy.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tzy.picturebackend.constant.PictureTagConstant;
import com.tzy.picturebackend.event.PictureEventPublisher;
import com.tzy.picturebackend.event.NotificationEventPublisher;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.manager.FileManager;
import com.tzy.picturebackend.manager.PictureListCache;
import com.tzy.picturebackend.mapper.PictureAiSuggestionMapper;
import com.tzy.picturebackend.mapper.PictureInteractionMapper;
import com.tzy.picturebackend.metrics.PictureMetrics;
import com.tzy.picturebackend.model.dto.file.UploadPictureResult;
import com.tzy.picturebackend.model.dto.notification.NotificationMessage;
import com.tzy.picturebackend.model.dto.picture.PictureEditRequest;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.picture.PictureReviewBatchRequest;
import com.tzy.picturebackend.model.dto.picture.PictureReviewRequest;
import com.tzy.picturebackend.model.dto.picture.PictureUpdateRequest;
import com.tzy.picturebackend.model.dto.picture.PictureUploadRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.PictureAiSuggestion;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.enums.NotificationTypeEnum;
import com.tzy.picturebackend.model.vo.PictureDetailVO;
import com.tzy.picturebackend.model.vo.PictureReviewBatchResult;
import com.tzy.picturebackend.model.vo.PictureReviewLogVO;
import com.tzy.picturebackend.model.vo.PictureTagCategory;
import com.tzy.picturebackend.model.vo.PictureVO;
import com.tzy.picturebackend.service.PictureReviewLogService;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.mapper.PictureTagMapper;
import com.tzy.picturebackend.service.TagCategoryService;
import com.tzy.picturebackend.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
* @author shifang37
* @description 针对表【picture(图片)】的数据库操作Service实现
* @createDate 2026-03-11 16:30:50
*/
@Service
@Slf4j
public class PictureServiceImpl extends ServiceImpl<PictureMapper, Picture>
    implements PictureService{

    /**
     * 允许排序的字段白名单：sortField 会被原样拼进 ORDER BY，不能直接使用客户端传入的值
     */
    static final Set<String> SORT_FIELD_WHITELIST = Set.of(
            "id", "name", "category", "picSize", "picWidth", "picHeight", "picScale", "picFormat",
            "userId", "createTime", "editTime", "updateTime", "reviewStatus", "reviewTime",
            "machineReviewStatus", "downloadCount", "favoriteCount", "likeCount");

    /**
     * 热度排序字段：收藏/点赞/下载大量同值，必须追加 id 次级排序，否则分页会出现重复或漏项
     */
    static final Set<String> HOT_SORT_FIELDS = Set.of("downloadCount", "favoriteCount", "likeCount");

    /**
     * 管理员上传自动通过时写入的审核意见
     */
    private static final String ADMIN_AUTO_REVIEW_MESSAGE = "管理员上传，自动通过";

    @Resource
    private FileManager fileManager;

    @Resource
    private PictureListCache pictureListCache;

    @Resource
    private UserService userService;

    @Resource
    private PictureReviewLogService pictureReviewLogService;

    @Resource
    private TagCategoryService tagCategoryService;

    @Resource
    private PictureTagMapper pictureTagMapper;

    @Resource
    private PictureAiSuggestionMapper pictureAiSuggestionMapper;

    @Resource
    private PictureInteractionMapper pictureInteractionMapper;

    @Resource
    private PictureMetrics pictureMetrics;

    /**
     * 发布图片事件（Spring 应用事件，由监听器转发到 RabbitMQ）
     */
    @Resource
    private PictureEventPublisher pictureEventPublisher;

    /**
     * 审核结论通知（Spring 应用事件，由监听器在事务提交后落库）
     */
    @Resource
    private NotificationEventPublisher notificationEventPublisher;

    @Override
    public PictureVO uploadPicture(MultipartFile multipartFile, PictureUploadRequest pictureUploadRequest, User loginUser) {
        try {
            PictureVO pictureVO = doUploadPicture(multipartFile, pictureUploadRequest, loginUser);
            pictureMetrics.upload("success");
            return pictureVO;
        } catch (RuntimeException e) {
            // 上传失败包含校验失败、COS 失败、入库失败等所有异常
            pictureMetrics.upload("failure");
            throw e;
        }
    }

    /**
     * 上传主流程；指标埋点放在外层，保证任何异常路径都能被统计到
     */
    private PictureVO doUploadPicture(MultipartFile multipartFile, PictureUploadRequest pictureUploadRequest,
                                      User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        // 普通用户可以上传，管理员上传后自动通过审核
        boolean isAdmin = userService.isAdmin(loginUser);
        // 用于判断是新增还是更新图片
        Long pictureId = null;
        if (pictureUploadRequest != null) {
            pictureId = pictureUploadRequest.getId();
        }
        // 如果是更新图片，需要校验图片是否存在
        Picture oldPicture = null;
        if (pictureId != null) {
            oldPicture = this.getById(pictureId);
            ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
            // 仅本人或管理员可编辑
            if (!oldPicture.getUserId().equals(loginUser.getId()) && !isAdmin) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
            }
        }
        // 上传图片，得到信息
        // 按照用户 id 划分目录
        String uploadPathPrefix = String.format("public/%s", loginUser.getId());
        UploadPictureResult uploadPictureResult = fileManager.uploadPicture(multipartFile, uploadPathPrefix);
        String newUrl = uploadPictureResult.getUrl();
        // 构造要入库的图片信息
        Picture picture = new Picture();
        picture.setUrl(newUrl);
        picture.setName(uploadPictureResult.getPicName());
        picture.setPicSize(uploadPictureResult.getPicSize());
        picture.setPicWidth(uploadPictureResult.getPicWidth());
        picture.setPicHeight(uploadPictureResult.getPicHeight());
        picture.setPicScale(uploadPictureResult.getPicScale());
        picture.setPicFormat(uploadPictureResult.getPicFormat());
        picture.setUserId(loginUser.getId());
        // 管理员上传/重传直接通过；普通用户上传/重传一律进入待审核
        Date reviewTime = isAdmin ? new Date() : null;
        picture.setReviewStatus(isAdmin
                ? PictureReviewStatusEnum.PASS.getValue()
                : PictureReviewStatusEnum.REVIEWING.getValue());
        picture.setReviewMessage(isAdmin ? ADMIN_AUTO_REVIEW_MESSAGE : null);
        picture.setReviewerId(isAdmin ? loginUser.getId() : null);
        picture.setReviewTime(reviewTime);
        // 如果 pictureId 不为空，表示更新，否则是新增
        if (pictureId != null) {
            // 如果是更新，需要补充 id 和编辑时间
            picture.setId(pictureId);
            // 保留原归属：管理员代用户重传时，图片作者不能被改成管理员
            picture.setUserId(oldPicture.getUserId());
            picture.setEditTime(new Date());
            // 普通用户重传后必须重新审核，因此显式清空上一次的审核信息
            // （updateById 默认忽略 null 字段，这里用 UpdateWrapper 才能把字段置空）
            // reviewStatus 由上面的实体字段更新；这里只负责把其余可能为 null 的审核字段显式置空
            UpdateWrapper<Picture> updateWrapper = new UpdateWrapper<Picture>()
                    .eq("id", pictureId)
                    .set("reviewMessage", picture.getReviewMessage())
                    .set("reviewerId", picture.getReviewerId())
                    .set("reviewTime", picture.getReviewTime())
                    // 重传的是新文件，上一次的机审结论必须清空，等待重新机审
                    .set("machineReviewStatus", null)
                    .set("machineReviewLabel", null)
                    .set("machineReviewScore", null)
                    .set("machineReviewTime", null);
            boolean result;
            try {
                result = this.update(picture, updateWrapper);
            } catch (RuntimeException e) {
                // 数据库更新失败：删除刚上传的新对象，避免产生孤儿文件
                deletePictureFile(newUrl);
                throw e;
            }
            if (!result) {
                deletePictureFile(newUrl);
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "图片上传失败");
            }
            // 更新成功后再清理旧对象；旧对象清理失败不影响新图片
            if (!Objects.equals(newUrl, oldPicture.getUrl())) {
                deletePictureFile(oldPicture.getUrl());
            }
        } else {
            boolean result;
            try {
                result = this.save(picture);
            } catch (RuntimeException e) {
                // 数据库保存失败：删除刚上传的对象，避免产生孤儿文件
                deletePictureFile(newUrl);
                throw e;
            }
            if (!result) {
                deletePictureFile(newUrl);
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "图片上传失败");
            }
        }
        // 管理员上传/重传会自动通过，补一条审核历史（best-effort，失败不影响上传结果）
        if (isAdmin) {
            pictureReviewLogService.record(picture.getId(), loginUser.getId(),
                    PictureReviewStatusEnum.PASS.getValue(), ADMIN_AUTO_REVIEW_MESSAGE);
        }
        // 新增或重传都会改变公开列表内容（重传会让已通过图片重新进入待审核）
        pictureListCache.invalidate();
        // 新上传或重传成功：通知机审与搜索索引
        pictureEventPublisher.pictureUploaded(picture.getId());
        return PictureVO.objToVo(picture);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean editPicture(PictureEditRequest pictureEditRequest, User loginUser) {
        ThrowUtils.throwIf(pictureEditRequest == null || pictureEditRequest.getId() == null
                || pictureEditRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        long id = pictureEditRequest.getId();
        Picture oldPicture = this.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        boolean isAdmin = userService.isAdmin(loginUser);
        // 仅本人或管理员可编辑
        if (!Objects.equals(oldPicture.getUserId(), loginUser.getId()) && !isAdmin) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 只更新请求中出现的字段：updateById 会忽略 null，未传的字段保持原值
        Picture picture = new Picture();
        BeanUtils.copyProperties(pictureEditRequest, picture);
        // 显式判空，避免 JSONUtil.toJsonStr(null) 把标签写成字符串 "null"
        List<String> normalizedTags = null;
        if (pictureEditRequest.getTags() != null) {
            normalizedTags = normalizeTags(pictureEditRequest.getTags());
            picture.setTags(JSONUtil.toJsonStr(normalizedTags));
        }
        picture.setEditTime(new Date());
        validPicture(picture);
        // 普通用户改动已审核图片的内容后必须重新审核，否则过审后改文字就能绕过审核
        boolean contentChanged = hasContentChanged(pictureEditRequest, oldPicture);
        boolean needReviewAgain = !isAdmin && isReviewed(oldPicture) && contentChanged;
        boolean result;
        if (needReviewAgain) {
            // updateById 会忽略 null，清空审核信息必须用 UpdateWrapper.set 显式置空
            UpdateWrapper<Picture> updateWrapper = new UpdateWrapper<Picture>()
                    .eq("id", id)
                    .set("reviewStatus", PictureReviewStatusEnum.REVIEWING.getValue())
                    .set("reviewMessage", null)
                    .set("reviewerId", null)
                    .set("reviewTime", null);
            result = this.update(picture, updateWrapper);
        } else {
            result = this.updateById(picture);
        }
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "编辑失败");
        // 标签同时写入关系表；与图片更新在同一个事务内，避免 JSON 与关系表不一致
        if (normalizedTags != null) {
            replacePictureTags(id, normalizedTags);
        }
        if (contentChanged) {
            // 名称、简介、分类、标签变化都会影响公开列表
            pictureListCache.invalidate();
            // 内容变化也要同步搜索索引
            pictureEventPublisher.pictureChanged(id);
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updatePicture(PictureUpdateRequest pictureUpdateRequest) {
        ThrowUtils.throwIf(pictureUpdateRequest == null || pictureUpdateRequest.getId() == null
                || pictureUpdateRequest.getId() <= 0, ErrorCode.PARAMS_ERROR);
        long id = pictureUpdateRequest.getId();
        Picture oldPicture = this.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        Picture picture = new Picture();
        BeanUtils.copyProperties(pictureUpdateRequest, picture);
        List<String> normalizedTags = null;
        if (pictureUpdateRequest.getTags() != null) {
            normalizedTags = normalizeTags(pictureUpdateRequest.getTags());
            picture.setTags(JSONUtil.toJsonStr(normalizedTags));
        }
        validPicture(picture);
        boolean result = this.updateById(picture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新失败");
        if (normalizedTags != null) {
            replacePictureTags(id, normalizedTags);
        }
        return true;
    }

    /**
     * 用给定标签整体替换图片的标签关系；空列表表示清空标签
     */
    private void replacePictureTags(Long pictureId, List<String> tags) {
        pictureTagMapper.deleteByPictureId(pictureId);
        if (CollUtil.isNotEmpty(tags)) {
            pictureTagMapper.insertBatch(pictureId, tags);
        }
    }

    /**
     * 标签归一化：去空、去首尾空格、去重，保持原有顺序。
     * <p>
     * 去重按忽略大小写处理，与 MySQL utf8mb4_unicode_ci 的比较规则保持一致；
     * 否则 ["Tag", "tag"] 会在 Java 侧算成 2 个标签，而数据库 DISTINCT 只算 1 个，导致 JOIN 永远不命中。
     */
    private static List<String> normalizeTags(List<String> tags) {
        if (CollUtil.isEmpty(tags)) {
            return List.of();
        }
        Map<String, String> unique = new LinkedHashMap<>();
        for (String tag : tags) {
            String trimmed = StrUtil.trim(tag);
            if (StrUtil.isNotEmpty(trimmed)) {
                unique.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
            }
        }
        return List.copyOf(unique.values());
    }

    /**
     * 图片是否已经过审（通过或拒绝）：只有这两种状态才需要「改内容后重新审核」
     */
    private boolean isReviewed(Picture picture) {
        Integer reviewStatus = picture.getReviewStatus();
        return reviewStatus != null
                && (reviewStatus == PictureReviewStatusEnum.PASS.getValue()
                || reviewStatus == PictureReviewStatusEnum.REJECT.getValue());
    }

    /**
     * 判断本次编辑是否真的改动了展示内容。
     * <p>
     * 未传的字段（null）表示保留原值，按原值参与比较，避免「打开弹窗直接保存」被误判为改动；
     * 标签按集合比较，忽略顺序和首尾空格差异。
     */
    private boolean hasContentChanged(PictureEditRequest request, Picture oldPicture) {
        String newName = request.getName() != null ? request.getName() : oldPicture.getName();
        String newIntroduction = request.getIntroduction() != null
                ? request.getIntroduction() : oldPicture.getIntroduction();
        String newCategory = request.getCategory() != null
                ? request.getCategory() : oldPicture.getCategory();
        if (!Objects.equals(normalizeText(newName), normalizeText(oldPicture.getName()))
                || !Objects.equals(normalizeText(newIntroduction), normalizeText(oldPicture.getIntroduction()))
                || !Objects.equals(normalizeText(newCategory), normalizeText(oldPicture.getCategory()))) {
            return true;
        }
        List<String> newTags = request.getTags() != null ? request.getTags() : parseTags(oldPicture.getTags());
        return !toTagSet(newTags).equals(toTagSet(parseTags(oldPicture.getTags())));
    }

    /**
     * 比较用的文本归一化：首尾空白不影响判断，空串与 null 等价
     */
    private static String normalizeText(String value) {
        String trimmed = StrUtil.trim(value);
        return StrUtil.isEmpty(trimmed) ? null : trimmed;
    }

    private static List<String> parseTags(String tagsJson) {
        if (StrUtil.isBlank(tagsJson)) {
            return List.of();
        }
        try {
            List<String> tags = JSONUtil.toList(tagsJson, String.class);
            return tags == null ? List.of() : tags;
        } catch (RuntimeException e) {
            log.warn("解析图片标签失败, tags = {}", tagsJson, e);
            return List.of();
        }
    }

    private static Set<String> toTagSet(List<String> tags) {
        if (CollUtil.isEmpty(tags)) {
            return Set.of();
        }
        return tags.stream()
                .filter(Objects::nonNull)
                .map(StrUtil::trim)
                .filter(StrUtil::isNotEmpty)
                .collect(Collectors.toSet());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deletePicture(Long id, User loginUser) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "id 不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        Picture oldPicture = this.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        // 仅本人或管理员可删除
        if (!Objects.equals(oldPicture.getUserId(), loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 移入回收站：保留图片行与 COS 对象，7 天内可由管理员恢复
        int updated = baseMapper.logicalDeleteById(id, loginUser.getId(), new Date());
        ThrowUtils.throwIf(updated <= 0, ErrorCode.OPERATION_ERROR, "图片已被删除，请刷新后重试");
        // 级联清理 AI 建议：它只对正常图片有意义，且模型输出可能包含敏感内容，不保留在回收站
        pictureAiSuggestionMapper.delete(new QueryWrapper<PictureAiSuggestion>()
                .eq("pictureId", id));
        // 标签关系不随回收站保留；恢复时会按 picture.tags JSON 重建
        pictureTagMapper.deleteByPictureId(id);
        // 审核日志（picture_review_log）按合规要求保留，这里不做任何删除
        pictureListCache.invalidate();
        // 删除后索引侧也要移除文档
        pictureEventPublisher.pictureChanged(id);
        return true;
    }

    @Override
    public Page<Picture> pageRecyclePictures(Page<Picture> page, PictureQueryRequest pictureQueryRequest) {
        ThrowUtils.throwIf(page == null, ErrorCode.PARAMS_ERROR);
        QueryWrapper<Picture> queryWrapper = new QueryWrapper<>();
        // 自定义 SQL 不经过逻辑删除插件，回收站条件必须显式指定
        queryWrapper.eq("isDelete", 1);
        if (pictureQueryRequest != null) {
            queryWrapper.eq(ObjUtil.isNotEmpty(pictureQueryRequest.getId()),
                    "id", pictureQueryRequest.getId());
            queryWrapper.eq(ObjUtil.isNotEmpty(pictureQueryRequest.getUserId()),
                    "userId", pictureQueryRequest.getUserId());
            queryWrapper.like(StrUtil.isNotBlank(pictureQueryRequest.getName()),
                    "name", pictureQueryRequest.getName());
            queryWrapper.like(StrUtil.isNotBlank(pictureQueryRequest.getIntroduction()),
                    "introduction", pictureQueryRequest.getIntroduction());
            queryWrapper.eq(StrUtil.isNotBlank(pictureQueryRequest.getCategory()),
                    "category", pictureQueryRequest.getCategory());
            queryWrapper.eq(ObjUtil.isNotEmpty(pictureQueryRequest.getReviewStatus()),
                    "reviewStatus", pictureQueryRequest.getReviewStatus());
            String searchText = pictureQueryRequest.getSearchText();
            if (StrUtil.isNotBlank(searchText)) {
                queryWrapper.and(qw -> qw.like("name", searchText)
                        .or()
                        .like("introduction", searchText));
            }
        }
        // 固定按删除时间倒序，最近删除的排在最前面；历史数据没有 deletedAt 时排在最后
        queryWrapper.orderByDesc("deletedAt").orderByDesc("id");
        baseMapper.selectRecyclePage(page, queryWrapper);
        return page;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean restorePicture(Long id) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "id 不能为空");
        Picture deletedPicture = baseMapper.selectDeletedById(id);
        ThrowUtils.throwIf(deletedPicture == null, ErrorCode.NOT_FOUND_ERROR, "回收站中不存在该图片");
        int restored = baseMapper.restoreById(id);
        ThrowUtils.throwIf(restored <= 0, ErrorCode.OPERATION_ERROR, "图片已被恢复或清理，请刷新后重试");
        // 删除时清理了标签关系，恢复时用图片上的 JSON 副本重建；
        // 历史数据可能存在超长/超量标签，先按 picture_tag 的约束过滤，避免恢复整体失败
        replacePictureTags(id, restorableTags(deletedPicture.getTags()));
        // 恢复后可能重新出现在公开列表，缓存与搜索索引都要刷新
        pictureListCache.invalidate();
        pictureEventPublisher.pictureChanged(id);
        return true;
    }

    /**
     * 从图片的 tags JSON 中提取可写回 {@code picture_tag} 的标签：
     * 去空、去重、单标签长度与总数量都按 {@link PictureTagConstant} 限制。
     */
    private static List<String> restorableTags(String tagsJson) {
        List<String> tags = normalizeTags(parseTags(tagsJson));
        List<String> result = new ArrayList<>(Math.min(tags.size(), PictureTagConstant.TAG_MAX_COUNT));
        for (String tag : tags) {
            if (tag.length() > PictureTagConstant.TAG_MAX_LENGTH) {
                continue;
            }
            result.add(tag);
            if (result.size() >= PictureTagConstant.TAG_MAX_COUNT) {
                break;
            }
        }
        return result;
    }

    @Override
    public int purgeExpiredPictures(Date before, int batchSize) {
        ThrowUtils.throwIf(before == null, ErrorCode.PARAMS_ERROR, "清理截止时间不能为空");
        int limit = Math.max(1, Math.min(batchSize, 500));
        List<Picture> candidates = baseMapper.selectPurgeCandidates(before, limit);
        if (CollUtil.isEmpty(candidates)) {
            return 0;
        }
        // 先删 COS 对象：删除失败的图片保留数据库行，下一轮继续重试，避免产生孤儿文件
        List<Long> purgeableIds = new ArrayList<>(candidates.size());
        for (Picture picture : candidates) {
            if (fileManager.deletePictureForPurge(picture.getUrl())) {
                purgeableIds.add(picture.getId());
            }
        }
        if (purgeableIds.isEmpty()) {
            return 0;
        }
        try {
            // 防御性清理：正常删除流程已经处理过，这里覆盖历史数据与上次清理失败的记录
            pictureTagMapper.deleteByPictureIds(purgeableIds);
            // 互动关系随图片一起物理删除：回收站阶段保留计数，恢复后收藏仍然有效
            pictureInteractionMapper.deleteByPictureIds(purgeableIds);
            pictureAiSuggestionMapper.delete(new QueryWrapper<PictureAiSuggestion>()
                    .in("pictureId", purgeableIds));
            int deleted = baseMapper.deletePhysicallyByIds(purgeableIds);
            log.info("回收站物理清理完成, candidates = {}, deleted = {}", candidates.size(), deleted);
            // 审核日志按合规要求保留，物理删除图片后也不清理 picture_review_log
            return deleted;
        } catch (RuntimeException e) {
            log.error("回收站物理清理失败，保留记录等待下一轮重试, ids = {}", purgeableIds, e);
            return 0;
        }
    }

    @Override
    public void deletePictureFile(String url) {
        try {
            fileManager.deletePicture(url);
        } catch (Exception e) {
            // COS 清理是 best-effort，不能影响数据库结果，但要留可检索日志
            log.error("清理图片文件失败, url = {}", url, e);
        }
    }

    @Override
    public QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest) {
        QueryWrapper<Picture> queryWrapper = new QueryWrapper<>();
        if (pictureQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = pictureQueryRequest.getId();
        String name = pictureQueryRequest.getName();
        String introduction = pictureQueryRequest.getIntroduction();
        String category = pictureQueryRequest.getCategory();
        Long picSize = pictureQueryRequest.getPicSize();
        Integer picWidth = pictureQueryRequest.getPicWidth();
        Integer picHeight = pictureQueryRequest.getPicHeight();
        Double picScale = pictureQueryRequest.getPicScale();
        String picFormat = pictureQueryRequest.getPicFormat();
        String searchText = pictureQueryRequest.getSearchText();
        Long userId = pictureQueryRequest.getUserId();
        String sortField = pictureQueryRequest.getSortField();
        String sortOrder = pictureQueryRequest.getSortOrder();
        Integer reviewStatus = pictureQueryRequest.getReviewStatus();
        // 从多字段中搜索
        if (StrUtil.isNotBlank(searchText)) {
            // 需要拼接查询条件
            queryWrapper.and(qw -> qw.like("name", searchText)
                    .or()
                    .like("introduction", searchText)
            );
        }
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.like(StrUtil.isNotBlank(name), "name", name);
        queryWrapper.like(StrUtil.isNotBlank(introduction), "introduction", introduction);
        queryWrapper.like(StrUtil.isNotBlank(picFormat), "picFormat", picFormat);
        queryWrapper.eq(StrUtil.isNotBlank(category), "category", category);
        queryWrapper.eq(ObjUtil.isNotEmpty(picWidth), "picWidth", picWidth);
        queryWrapper.eq(ObjUtil.isNotEmpty(picHeight), "picHeight", picHeight);
        queryWrapper.eq(ObjUtil.isNotEmpty(picSize), "picSize", picSize);
        queryWrapper.eq(ObjUtil.isNotEmpty(picScale), "picScale", picScale);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewStatus), "reviewStatus", reviewStatus);
        // 标签过滤需要 JOIN picture_tag，由 pagePictures 统一处理，不能放进 QueryWrapper
        // 排序：sortField 会被拼进 ORDER BY，只接受白名单内的字段
        if (StrUtil.isNotEmpty(sortField)) {
            ThrowUtils.throwIf(!SORT_FIELD_WHITELIST.contains(sortField), ErrorCode.PARAMS_ERROR, "排序字段不合法");
            queryWrapper.orderBy(true, "ascend".equals(sortOrder), sortField);
            // 审核页按机审状态优先排序时，同状态内再按创建时间倒序，保证分页顺序稳定
            if ("machineReviewStatus".equals(sortField)) {
                queryWrapper.orderByDesc("createTime");
            }
            // 热度排序同值极多（大量图片都是 0 收藏/0 下载），不加次级键时 LIMIT 分页会重复或漏项
            if (HOT_SORT_FIELDS.contains(sortField)) {
                queryWrapper.orderByAsc("id");
            }
        }
        return queryWrapper;
    }

    @Override
    public Page<Picture> pagePictures(Page<Picture> page, PictureQueryRequest pictureQueryRequest) {
        ThrowUtils.throwIf(page == null, ErrorCode.PARAMS_ERROR);
        List<String> tags = normalizeTags(pictureQueryRequest == null ? null : pictureQueryRequest.getTags());
        QueryWrapper<Picture> queryWrapper = getQueryWrapper(pictureQueryRequest);
        if (tags.isEmpty()) {
            return this.page(page, queryWrapper);
        }
        // 自定义 SQL 不经过 MyBatis-Plus 逻辑删除插件，显式补上 isDelete = 0
        queryWrapper.eq("isDelete", 0);
        baseMapper.selectPageByTags(page, queryWrapper, tags, tags.size());
        return page;
    }

    @Override
    public PictureVO getPictureVO(Picture picture, HttpServletRequest request) {
        // 审核内部字段只对作者本人和管理员可见，其余访客只能拿到公开 VO
        User loginUser = userService.getLoginUserOrNull(request);
        PictureVO pictureVO = canViewReviewInfo(picture, loginUser)
                ? PictureDetailVO.objToDetailVo(picture)
                : PictureVO.objToVo(picture);
        fillPublicUser(pictureVO, picture.getUserId());
        return pictureVO;
    }

    /**
     * 分页获取公开图片封装：不包含审核意见和审核人
     */
    @Override
    public Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request) {
        List<Picture> pictureList = picturePage.getRecords();
        Page<PictureVO> pictureVOPage = new Page<>(picturePage.getCurrent(), picturePage.getSize(), picturePage.getTotal());
        if (CollUtil.isEmpty(pictureList)) {
            return pictureVOPage;
        }
        // 对象列表 => 公开封装对象列表
        List<PictureVO> pictureVOList = pictureList.stream().map(PictureVO::objToVo).collect(Collectors.toList());
        fillPublicUsers(pictureVOList, pictureList);
        pictureVOPage.setRecords(pictureVOList);
        return pictureVOPage;
    }

    @Override
    public Page<PictureDetailVO> getPictureDetailVOPage(Page<Picture> picturePage) {
        Page<PictureDetailVO> detailVOPage = new Page<>(picturePage.getCurrent(), picturePage.getSize(),
                picturePage.getTotal());
        List<Picture> pictureList = picturePage.getRecords();
        if (CollUtil.isEmpty(pictureList)) {
            return detailVOPage;
        }
        List<PictureDetailVO> detailVOList = pictureList.stream()
                .map(PictureDetailVO::objToDetailVo)
                .collect(Collectors.toList());
        fillPublicUsers(detailVOList, pictureList);
        detailVOPage.setRecords(detailVOList);
        return detailVOPage;
    }

    /**
     * 当前请求者是否可以查看审核内部字段：仅作者本人和管理员
     */
    private boolean canViewReviewInfo(Picture picture, User loginUser) {
        if (picture == null || loginUser == null) {
            return false;
        }
        return Objects.equals(picture.getUserId(), loginUser.getId()) || userService.isAdmin(loginUser);
    }

    /**
     * 填充单张图片的作者公开信息
     */
    private void fillPublicUser(PictureVO pictureVO, Long userId) {
        if (pictureVO == null || userId == null || userId <= 0) {
            return;
        }
        pictureVO.setUser(userService.getPublicUserVO(userService.getById(userId)));
    }

    /**
     * 批量填充图片作者公开信息，避免逐条查库
     */
    private void fillPublicUsers(List<? extends PictureVO> pictureVOList, List<Picture> pictureList) {
        Set<Long> userIdSet = pictureList.stream()
                .map(Picture::getUserId)
                .filter(Objects::nonNull)
                .filter(userId -> userId > 0)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = userIdSet.isEmpty()
                ? Map.of()
                : userService.listByIds(userIdSet).stream()
                        .collect(Collectors.toMap(User::getId, user -> user, (left, right) -> left));
        pictureVOList.forEach(pictureVO ->
                pictureVO.setUser(userService.getPublicUserVO(userMap.get(pictureVO.getUserId()))));
    }

    @Override
    public void validPicture(Picture picture) {
        ThrowUtils.throwIf(picture == null, ErrorCode.PARAMS_ERROR);
        // 从对象中取值
        Long id = picture.getId();
        String url = picture.getUrl();
        String name = picture.getName();
        String introduction = picture.getIntroduction();
        String category = picture.getCategory();
        String tags = picture.getTags();
        String picFormat = picture.getPicFormat();
        // 修改数据时，id 不能为空，有参数则校验
        ThrowUtils.throwIf(ObjUtil.isNull(id), ErrorCode.PARAMS_ERROR, "id 不能为空");
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "id 必须为正数");
        // 以下长度均对应数据库字段长度，避免超长时才由数据库报错
        if (StrUtil.isNotBlank(url)) {
            ThrowUtils.throwIf(url.length() > 512, ErrorCode.PARAMS_ERROR, "url 不能超过 512 个字符");
        }
        if (StrUtil.isNotBlank(name)) {
            ThrowUtils.throwIf(name.length() > 128, ErrorCode.PARAMS_ERROR, "图片名称不能超过 128 个字符");
        }
        if (StrUtil.isNotBlank(introduction)) {
            ThrowUtils.throwIf(introduction.length() > 512, ErrorCode.PARAMS_ERROR, "简介不能超过 512 个字符");
        }
        if (StrUtil.isNotBlank(category)) {
            ThrowUtils.throwIf(category.length() > 64, ErrorCode.PARAMS_ERROR, "分类不能超过 64 个字符");
        }
        if (StrUtil.isNotBlank(tags)) {
            // 与 DTO、AI、picture_tag.tag 共用同一组上限：单个标签 32 字符、最多 20 个
            List<String> tagList;
            try {
                tagList = JSONUtil.toList(tags, String.class);
            } catch (RuntimeException e) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "标签格式不合法");
            }
            if (tagList == null) {
                tagList = List.of();
            }
            ThrowUtils.throwIf(tagList.size() > PictureTagConstant.TAG_MAX_COUNT,
                    ErrorCode.PARAMS_ERROR,
                    "标签最多 " + PictureTagConstant.TAG_MAX_COUNT + " 个");
            for (String tag : tagList) {
                String trimmed = StrUtil.trim(tag);
                ThrowUtils.throwIf(trimmed != null && trimmed.length() > PictureTagConstant.TAG_MAX_LENGTH,
                        ErrorCode.PARAMS_ERROR,
                        "单个标签不能超过 " + PictureTagConstant.TAG_MAX_LENGTH + " 个字符");
            }
        }
        if (StrUtil.isNotBlank(picFormat)) {
            ThrowUtils.throwIf(picFormat.length() > 32, ErrorCode.PARAMS_ERROR, "图片格式不能超过 32 个字符");
        }
    }

    @Override
    public void checkPictureVisible(Picture picture, HttpServletRequest request) {
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        Integer reviewStatus = picture.getReviewStatus();
        // 已通过审核的图片所有人可见
        if (reviewStatus != null && reviewStatus == PictureReviewStatusEnum.PASS.getValue()) {
            return;
        }
        // 未通过审核的图片仅管理员和作者本人可见，其余一律当作不存在
        User loginUser = userService.getLoginUserOrNull(request);
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }
        boolean isOwner = picture.getUserId() != null && picture.getUserId().equals(loginUser.getId());
        if (!isOwner && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        }
    }

    @Override
    public void doPictureReview(PictureReviewRequest pictureReviewRequest, User loginUser) {
        ThrowUtils.throwIf(pictureReviewRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        Long id = pictureReviewRequest.getId();
        Integer reviewStatus = pictureReviewRequest.getReviewStatus();
        PictureReviewStatusEnum reviewStatusEnum = PictureReviewStatusEnum.getEnumByValue(reviewStatus);
        // 审核的目标状态必须是通过或拒绝，不能是待审核
        if (id == null || reviewStatusEnum == null || PictureReviewStatusEnum.REVIEWING.equals(reviewStatusEnum)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String reviewMessage = pictureReviewRequest.getReviewMessage();
        if (reviewMessage != null && reviewMessage.length() > 512) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "审核意见不能超过 512 个字符");
        }

        Picture oldPicture = this.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        // reviewStatus 理论上非空，这里仍判空以避免 NPE
        Integer currentStatus = oldPicture.getReviewStatus();
        if (currentStatus != null && currentStatus.equals(reviewStatus)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请勿重复审核");
        }
        // 只更新审核相关字段，避免整对象覆盖
        Date reviewTime = new Date();
        UpdateWrapper<Picture> updateWrapper = new UpdateWrapper<Picture>()
                .eq("id", id)
                .set("reviewStatus", reviewStatus)
                .set("reviewMessage", reviewMessage)
                .set("reviewerId", loginUser.getId())
                .set("reviewTime", reviewTime);
        // 条件更新：机审自动处置可能同时发生，只有仍处于读取时的状态才允许写入
        if (currentStatus == null) {
            updateWrapper.isNull("reviewStatus");
        } else {
            updateWrapper.eq("reviewStatus", currentStatus);
        }
        boolean result = this.update(updateWrapper);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "图片已被其他操作处理，请刷新后重试");
        // 审核结果落库成功后再记录历史，失败不影响本次审核
        pictureReviewLogService.record(id, loginUser.getId(), reviewStatus, reviewMessage);
        // 通知作者审核结论：落库成功后才发，通知失败不影响审核结果
        publishReviewNotification(oldPicture, reviewStatus, reviewMessage, loginUser.getId());
        // 通过/拒绝都会改变公开列表（以及名称、简介等展示内容）
        pictureListCache.invalidate();
        // 审核结果（可见性）变化要同步搜索索引
        pictureEventPublisher.pictureChanged(id);
    }

    /**
     * 发布审核结论通知。
     * <p>
     * 自己审核自己的图片不通知，避免管理员给自己的作品发无用消息；
     * {@code dedupeKey = 结论:图片 id} 保证重复审核、机审兜底重发都不会产生第二条通知。
     */
    private void publishReviewNotification(Picture picture, Integer reviewStatus,
                                           String reviewMessage, Long reviewerId) {
        if (picture == null || picture.getUserId() == null) {
            return;
        }
        if (Objects.equals(picture.getUserId(), reviewerId)) {
            return;
        }
        NotificationTypeEnum type = Objects.equals(reviewStatus, PictureReviewStatusEnum.PASS.getValue())
                ? NotificationTypeEnum.REVIEW_PASS
                : NotificationTypeEnum.REVIEW_REJECT;
        notificationEventPublisher.publish(NotificationMessage.review(
                type, picture.getUserId(), picture.getId(), picture.getName(), reviewMessage));
    }

    /**
     * 批量审核的审核结论通知：整批聚合成一次事件、一次批量插入
     */
    private void publishBatchReviewNotifications(Map<Long, Picture> pictureMap, List<Long> reviewedIds,
                                                 Integer reviewStatus, String reviewMessage,
                                                 Long reviewerId) {
        List<NotificationMessage> messages = new ArrayList<>(reviewedIds.size());
        NotificationTypeEnum type = Objects.equals(reviewStatus, PictureReviewStatusEnum.PASS.getValue())
                ? NotificationTypeEnum.REVIEW_PASS
                : NotificationTypeEnum.REVIEW_REJECT;
        for (Long reviewedId : reviewedIds) {
            Picture picture = pictureMap.get(reviewedId);
            if (picture == null || picture.getUserId() == null) {
                continue;
            }
            if (Objects.equals(picture.getUserId(), reviewerId)) {
                continue;
            }
            messages.add(NotificationMessage.review(type, picture.getUserId(), picture.getId(),
                    picture.getName(), reviewMessage));
        }
        notificationEventPublisher.publishAll(messages);
    }

    @Override
    public List<PictureReviewLogVO> listPictureReviewLogs(Long pictureId) {
        ThrowUtils.throwIf(pictureId == null || pictureId <= 0,
                ErrorCode.PARAMS_ERROR, "图片 id 不能为空");
        return pictureReviewLogService.listVOByPictureId(pictureId);
    }

    @Override
    public Picture getDownloadablePicture(Long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "图片 id 不能为空");
        Picture picture = this.getById(id);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        // 未通过审核的图片只有作者/管理员可访问，下载也要遵守同样的可见性规则
        checkPictureVisible(picture, request);
        return picture;
    }

    @Override
    public boolean incrementDownloadCount(Long id) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "图片 id 不能为空");
        // 调用方在流式下载成功后才走到这里，不再重复校验可见性
        return this.update(new UpdateWrapper<Picture>()
                .eq("id", id)
                .setSql("downloadCount = downloadCount + 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PictureReviewBatchResult batchReview(PictureReviewBatchRequest request, User loginUser) {
        ThrowUtils.throwIf(request == null || CollUtil.isEmpty(request.getIds()),
                ErrorCode.PARAMS_ERROR, "请选择要审核的图片");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        Integer reviewStatus = request.getReviewStatus();
        PictureReviewStatusEnum reviewStatusEnum = PictureReviewStatusEnum.getEnumByValue(reviewStatus);
        // 审核的目标状态必须是通过或拒绝，不能是待审核
        ThrowUtils.throwIf(reviewStatusEnum == null || PictureReviewStatusEnum.REVIEWING.equals(reviewStatusEnum),
                ErrorCode.PARAMS_ERROR, "审核状态只能是通过或拒绝");
        String reviewMessage = request.getReviewMessage();
        ThrowUtils.throwIf(reviewMessage != null && reviewMessage.length() > 512,
                ErrorCode.PARAMS_ERROR, "审核意见不能超过 512 个字符");

        PictureReviewBatchResult result = new PictureReviewBatchResult();
        // 去重并剔除非法 id：批量 SQL 只带有效 id，非法 id 直接计入失败明细
        Set<Long> pictureIds = new LinkedHashSet<>();
        for (Long id : request.getIds()) {
            if (id == null || id <= 0) {
                addBatchFailure(result, null, "存在非法图片 id");
            } else {
                pictureIds.add(id);
            }
        }
        if (pictureIds.isEmpty()) {
            return result;
        }
        // 一条 IN 查询拿到整批图片的当前状态，替代逐张 getById
        Map<Long, Picture> pictureMap = this.listByIds(pictureIds).stream()
                .collect(Collectors.toMap(Picture::getId, Function.identity(), (left, right) -> left));
        List<Long> pendingIds = new ArrayList<>(pictureIds.size());
        for (Long id : pictureIds) {
            Picture picture = pictureMap.get(id);
            if (picture == null) {
                addBatchFailure(result, id, "图片不存在");
                continue;
            }
            Integer currentStatus = picture.getReviewStatus();
            if (!Objects.equals(currentStatus, PictureReviewStatusEnum.REVIEWING.getValue())) {
                addBatchFailure(result, id, currentStatus == null
                        ? "图片状态异常，请刷新后重试"
                        : Objects.equals(currentStatus, reviewStatus)
                                ? "请勿重复审核"
                                : "图片已审核，请刷新后重试");
                continue;
            }
            pendingIds.add(id);
        }
        if (pendingIds.isEmpty()) {
            return result;
        }
        Date reviewTime = new Date();
        // 一条 UPDATE 处理整批，并带上原状态条件：并发的人工审核/机审只会有一方成功
        UpdateWrapper<Picture> updateWrapper = new UpdateWrapper<Picture>()
                .in("id", pendingIds)
                .eq("reviewStatus", PictureReviewStatusEnum.REVIEWING.getValue())
                .set("reviewStatus", reviewStatus)
                .set("reviewMessage", reviewMessage)
                .set("reviewerId", loginUser.getId())
                .set("reviewTime", reviewTime);
        int updated = baseMapper.update(null, updateWrapper);
        List<Long> reviewedIds = pendingIds;
        if (updated != pendingIds.size()) {
            // 少量图片在查询后已被其他请求处理：回查仍未审核的 id，给出准确的失败明细
            Set<Long> stillPendingIds = this.list(new QueryWrapper<Picture>()
                            .in("id", pendingIds)
                            .eq("reviewStatus", PictureReviewStatusEnum.REVIEWING.getValue()))
                    .stream()
                    .map(Picture::getId)
                    .collect(Collectors.toSet());
            reviewedIds = pendingIds.stream()
                    .filter(id -> !stillPendingIds.contains(id))
                    .toList();
            stillPendingIds.forEach(id ->
                    addBatchFailure(result, id, "图片已被其他操作处理，请刷新后重试"));
        }
        result.setSuccessCount(reviewedIds.size());
        if (reviewedIds.isEmpty()) {
            return result;
        }
        // 整批只写一条批量审核历史
        pictureReviewLogService.recordBatch(reviewedIds, loginUser.getId(), reviewStatus, reviewMessage);
        // 整批只发一条通知事件（事务提交后写入），避免 100 次单条 insert
        publishBatchReviewNotifications(pictureMap, reviewedIds, reviewStatus, reviewMessage, loginUser.getId());
        // 整批只失效一次公开列表缓存
        pictureListCache.invalidate();
        // 整批只发一条批量事件；方法带事务，转发会等事务提交后再执行
        pictureEventPublisher.picturesChanged(reviewedIds);
        return result;
    }

    /**
     * 追加一条批量审核失败明细
     */
    private static void addBatchFailure(PictureReviewBatchResult result, Long pictureId, String message) {
        result.setFailCount(result.getFailCount() + 1);
        result.getErrors().add(pictureId == null ? message : "图片 " + pictureId + "：" + message);
    }

    @Override
    public PictureTagCategory listTagCategory() {
        // 数据源为标签/分类字典，由管理员维护，不再从图片动态汇总
        return tagCategoryService.listAll();
    }

}




