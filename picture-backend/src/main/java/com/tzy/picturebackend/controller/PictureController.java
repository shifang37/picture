package com.tzy.picturebackend.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.annotation.AuthCheck;
import com.tzy.picturebackend.common.BaseResponse;
import com.tzy.picturebackend.common.DeleteRequest;
import com.tzy.picturebackend.common.ResultUtils;
import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.event.PictureEventPublisher;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.manager.PictureListCache;
import com.tzy.picturebackend.manager.PictureSearchSortException;
import com.tzy.picturebackend.model.dto.picture.*;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.vo.PictureAiSuggestionVO;
import com.tzy.picturebackend.model.vo.PictureDetailVO;
import com.tzy.picturebackend.model.vo.PictureReviewBatchResult;
import com.tzy.picturebackend.model.vo.PictureReviewLogVO;
import com.tzy.picturebackend.model.vo.PictureTagCategory;
import com.tzy.picturebackend.model.vo.PictureVO;
import com.tzy.picturebackend.service.AiSuggestionService;
import com.tzy.picturebackend.service.PictureSearchService;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("picture")
@Slf4j
public class PictureController {

    @Resource
    private PictureService pictureService;

    @Resource
    private UserService userService;

    @Resource
    private PictureListCache pictureListCache;

    @Resource
    private PictureEventPublisher pictureEventPublisher;

    @Resource
    private AiSuggestionService aiSuggestionService;

    @Resource
    private PictureSearchService pictureSearchService;



    /**
     * 上传图片（登录用户可用，可重新上传）
     * <p>
     * 普通用户上传后进入待审核状态，管理员上传后自动通过审核。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaseResponse<PictureVO> uploadPicture(
            @RequestPart("file") MultipartFile multipartFile,
            PictureUploadRequest pictureUploadRequest,
            HttpServletRequest request) {
        // 上传只要求登录，普通用户也可以调用
        User loginUser = userService.getLoginUser(request);
        PictureVO pictureVO = pictureService.uploadPicture(multipartFile, pictureUploadRequest, loginUser);
        return ResultUtils.success(pictureVO);
    }

    /**
     * 删除图片
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deletePicture(@Valid @RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        // 权限校验、数据库逻辑删除、COS 清理统一收敛到 service
        pictureService.deletePicture(deleteRequest.getId(), loginUser);
        return ResultUtils.success(true);
    }

    /**
     * 更新图片（仅管理员可用）
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updatePicture(@Valid @RequestBody PictureUpdateRequest pictureUpdateRequest) {
        if (pictureUpdateRequest == null || pictureUpdateRequest.getId() == null
                || pictureUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 将实体类和 DTO 进行转换
        Picture picture = new Picture();
        BeanUtils.copyProperties(pictureUpdateRequest, picture);
        // 注意将 list 转为 string
        picture.setTags(JSONUtil.toJsonStr(pictureUpdateRequest.getTags()));
        // 数据校验
        pictureService.validPicture(picture);
        // 判断是否存在
        long id = pictureUpdateRequest.getId();
        Picture oldPicture = pictureService.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR);
        // 操作数据库
        boolean result = pictureService.updateById(picture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 管理员直接改图也可能改变首页展示内容
        pictureListCache.invalidate();
        // 管理端直接更新绕过了 service 的编辑逻辑，这里补发内容变更事件
        pictureEventPublisher.pictureChanged(id);
        return ResultUtils.success(true);
    }

    /**
     * 根据 id 获取图片（仅管理员可用）
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Picture> getPictureById(Long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        Picture picture = pictureService.getById(id);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类
        return ResultUtils.success(picture);
    }

    /**
     * 根据 id 获取图片（封装类）
     * <p>
     * 审核意见、审核人等内部字段仅作者本人和管理员可见，其他访客只能拿到公开字段。
     */
    @GetMapping("/get/vo")
    public BaseResponse<PictureVO> getPictureVOById(Long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        Picture picture = pictureService.getById(id);
        ThrowUtils.throwIf(picture == null, ErrorCode.NOT_FOUND_ERROR);
        // 未通过审核的图片仅管理员和作者本人可见
        pictureService.checkPictureVisible(picture, request);
        // 获取封装类
        return ResultUtils.success(pictureService.getPictureVO(picture, request));
    }

    /**
     * 记录图片下载次数
     */
    @PostMapping("/download/{id}")
    public BaseResponse<Boolean> downloadPicture(@PathVariable Long id, HttpServletRequest request) {
        return ResultUtils.success(pictureService.incrementDownloadCount(id, request));
    }

    /**
     * 分页获取图片列表（仅管理员可用）
     */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Picture>> listPictureByPage(@Valid @RequestBody PictureQueryRequest pictureQueryRequest) {
        ThrowUtils.throwIf(pictureQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();
        validPage(current, size, 100);
        // 查询数据库
        Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
                pictureService.getQueryWrapper(pictureQueryRequest));
        return ResultUtils.success(picturePage);
    }

    /**
     * 分页获取图片列表（封装类）
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<PictureVO>> listPictureVOByPage(@Valid @RequestBody PictureQueryRequest pictureQueryRequest,
                                                             HttpServletRequest request) {
        ThrowUtils.throwIf(pictureQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long current = pictureQueryRequest.getCurrent();
        long size = pictureQueryRequest.getPageSize();
        // 公开列表限制爬虫：每页最多 20 条
        validPage(current, size, 20);
        // 公开列表只展示审核通过的图片，避免未审核 / 被拒绝的内容外泄
        pictureQueryRequest.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        // 公开列表是唯一允许缓存的列表；我的图片、管理列表、详情页都不缓存
        PictureListPage cached = pictureListCache.get(pictureQueryRequest);
        if (cached != null) {
            return ResultUtils.success(cached.toPage());
        }
        // 启用 Elasticsearch 时由索引召回 id，再回 MySQL 取数据；索引不可用时自动回落
        Page<PictureVO> pictureVOPage = null;
        if (pictureSearchService.enabled()) {
            try {
                pictureVOPage = searchPublicPictureVOPage(pictureQueryRequest, request, current, size);
            } catch (PictureSearchSortException e) {
                // 排序字段不合法属于参数问题，交给 MySQL 查询路径返回统一的 40000
                log.debug("搜索排序字段不合法，回落到 MySQL 校验: {}", e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Elasticsearch 搜索失败，回落到 MySQL 查询, searchText={}",
                        pictureQueryRequest.getSearchText(), e);
            }
        }
        if (pictureVOPage == null) {
            // 查询数据库
            Page<Picture> picturePage = pictureService.page(new Page<>(current, size),
                    pictureService.getQueryWrapper(pictureQueryRequest));
            // 获取封装类
            pictureVOPage = pictureService.getPictureVOPage(picturePage, request);
        }
        pictureListCache.put(pictureQueryRequest, PictureListPage.of(pictureVOPage));
        return ResultUtils.success(pictureVOPage);
    }

    /**
     * 通过 Elasticsearch 召回图片 id，再回 MySQL 取完整数据。
     * <p>
     * MySQL 是唯一事实来源：这里会再次过滤 {@code reviewStatus = PASS}，
     * 索引延迟最多导致「少搜到」，不会把未过审内容展示出去。
     */
    private Page<PictureVO> searchPublicPictureVOPage(PictureQueryRequest pictureQueryRequest,
                                                      HttpServletRequest request,
                                                      long current, long size) {
        Page<Long> idPage = pictureSearchService.searchPictureIds(pictureQueryRequest);
        List<Long> ids = idPage.getRecords();
        if (CollUtil.isEmpty(ids)) {
            return new Page<>(current, size, idPage.getTotal());
        }
        List<Picture> found = pictureService.listByIds(ids);
        Map<Long, Picture> pictureMap = (found == null ? List.<Picture>of() : found).stream()
                .filter(picture -> picture.getReviewStatus() != null
                        && picture.getReviewStatus() == PictureReviewStatusEnum.PASS.getValue())
                .collect(Collectors.toMap(Picture::getId, picture -> picture, (left, right) -> left));
        // 按索引返回的顺序还原记录，过滤掉的失效文档从 total 中扣除
        List<Picture> pictures = ids.stream()
                .map(pictureMap::get)
                .filter(Objects::nonNull)
                .toList();
        long total = Math.max(idPage.getTotal() - (ids.size() - pictures.size()), pictures.size());
        Page<Picture> picturePage = new Page<>(current, size, total);
        picturePage.setRecords(pictures);
        return pictureService.getPictureVOPage(picturePage, request);
    }

    /**
     * 分页获取当前登录用户的图片（含待审核 / 已拒绝）
     * <p>
     * 用于普通用户在「我的图片」里管理自己上传的内容，不要求管理员权限。
     */
    @PostMapping("/my/page/vo")
    public BaseResponse<Page<PictureDetailVO>> listMyPictureVOByPage(
            @Valid @RequestBody PictureQueryRequest pictureQueryRequest,
            HttpServletRequest request) {
        ThrowUtils.throwIf(pictureQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long size = pictureQueryRequest.getPageSize();
        validPage(pictureQueryRequest.getCurrent(), size, 20);
        User loginUser = userService.getLoginUser(request);
        // 强制只查本人图片，忽略客户端传入的 userId，避免越权查看他人待审核内容
        pictureQueryRequest.setUserId(loginUser.getId());
        // 查询数据库
        Page<Picture> picturePage = pictureService.page(new Page<>(pictureQueryRequest.getCurrent(), size),
                pictureService.getQueryWrapper(pictureQueryRequest));
        // 「我的图片」全部属于当前用户，可以返回审核意见等内部字段
        return ResultUtils.success(pictureService.getPictureDetailVOPage(picturePage));
    }

    /**
     * 编辑图片（给用户使用）
     * <p>
     * 是否重新进入待审核等业务规则收敛到 service，controller 只负责取当前登录用户。
     */
    @PostMapping("/edit")
    public BaseResponse<Boolean> editPicture(@Valid @RequestBody PictureEditRequest pictureEditRequest, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(pictureService.editPicture(pictureEditRequest, loginUser));
    }

    @GetMapping("/tag_category")
    public BaseResponse<PictureTagCategory> listPictureTagCategory() {
        return ResultUtils.success(pictureService.listTagCategory());
    }

    /**
     * 查询图片的 AI 建议（仅作者和管理员可读）
     * <p>
     * 尚未生成时 data 为 null，前端据此继续轮询。
     */
    @GetMapping("/ai_suggestion")
    public BaseResponse<PictureAiSuggestionVO> getPictureAiSuggestion(@RequestParam Long id,
                                                                      HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(aiSuggestionService.getSuggestion(id, loginUser));
    }

    @PostMapping("/review")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> doPictureReview(@Valid @RequestBody PictureReviewRequest pictureReviewRequest,
                                                 HttpServletRequest request) {
        ThrowUtils.throwIf(pictureReviewRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        pictureService.doPictureReview(pictureReviewRequest, loginUser);
        return ResultUtils.success(true);
    }

    /**
     * 查询图片审核历史（仅管理员可用）
     */
    @GetMapping("/review/log")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<List<PictureReviewLogVO>> listPictureReviewLogs(@RequestParam Long pictureId) {
        return ResultUtils.success(pictureService.listPictureReviewLogs(pictureId));
    }

    /**
     * 批量审核图片（仅管理员可用）
     */
    @PostMapping("/review/batch")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<PictureReviewBatchResult> batchReview(
            @Valid @RequestBody PictureReviewBatchRequest pictureReviewBatchRequest,
            HttpServletRequest request) {
        ThrowUtils.throwIf(pictureReviewBatchRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(pictureService.batchReview(pictureReviewBatchRequest, loginUser));
    }

    /**
     * 统一校验分页参数，避免 pageSize 过大拖垮数据库
     */
    private void validPage(long current, long size, long maxSize) {
        ThrowUtils.throwIf(current < 1 || size < 1, ErrorCode.PARAMS_ERROR, "分页参数不合法");
        ThrowUtils.throwIf(size > maxSize, ErrorCode.PARAMS_ERROR,
                String.format("分页大小不能超过 %d", maxSize));
    }

}
