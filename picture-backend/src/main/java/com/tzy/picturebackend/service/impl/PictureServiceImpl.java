package com.tzy.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.manager.FileManager;
import com.tzy.picturebackend.model.dto.file.UploadPictureResult;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.picture.PictureReviewRequest;
import com.tzy.picturebackend.model.dto.picture.PictureUploadRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.PictureReviewLog;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.vo.PictureVO;
import com.tzy.picturebackend.model.vo.UserVO;
import com.tzy.picturebackend.service.PictureReviewLogService;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
            "userId", "createTime", "editTime", "updateTime", "reviewStatus", "reviewTime");

    /**
     * 管理员上传自动通过时写入的审核意见
     */
    private static final String ADMIN_AUTO_REVIEW_MESSAGE = "管理员上传，自动通过";

    @Resource
    private FileManager fileManager;

    @Resource
    private UserService userService;

    @Resource
    private PictureReviewLogService pictureReviewLogService;

    @Override
    public PictureVO uploadPicture(MultipartFile multipartFile, PictureUploadRequest pictureUploadRequest, User loginUser) {
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
                    .set("reviewTime", picture.getReviewTime());
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
        return PictureVO.objToVo(picture);
    }

    @Override
    public boolean deletePicture(Long id, User loginUser) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "id 不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        Picture oldPicture = this.getById(id);
        ThrowUtils.throwIf(oldPicture == null, ErrorCode.NOT_FOUND_ERROR, "图片不存在");
        // 仅本人或管理员可删除
        if (!Objects.equals(oldPicture.getUserId(), loginUser.getId()) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        // 先做数据库逻辑删除，成功后再清理 COS，避免数据库回滚但对象已被删除
        boolean result = this.removeById(id);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除失败");
        deletePictureFile(oldPicture.getUrl());
        return true;
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
        List<String> tags = pictureQueryRequest.getTags();
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
        // JSON 数组查询
        if (CollUtil.isNotEmpty(tags)) {
            for (String tag : tags) {
                queryWrapper.like("tags", "\"" + tag + "\"");
            }
        }
        // 排序：sortField 会被拼进 ORDER BY，只接受白名单内的字段
        if (StrUtil.isNotEmpty(sortField)) {
            ThrowUtils.throwIf(!SORT_FIELD_WHITELIST.contains(sortField), ErrorCode.PARAMS_ERROR, "排序字段不合法");
            queryWrapper.orderBy(true, "ascend".equals(sortOrder), sortField);
        }
        return queryWrapper;
    }

    @Override
    public PictureVO getPictureVO(Picture picture, HttpServletRequest request) {
        // 对象转封装类
        PictureVO pictureVO = PictureVO.objToVo(picture);
        // 关联查询用户信息
        Long userId = picture.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            pictureVO.setUser(userVO);
        }
        return pictureVO;
    }

    /**
     * 分页获取图片封装
     */
    @Override
    public Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request) {
        List<Picture> pictureList = picturePage.getRecords();
        Page<PictureVO> pictureVOPage = new Page<>(picturePage.getCurrent(), picturePage.getSize(), picturePage.getTotal());
        if (CollUtil.isEmpty(pictureList)) {
            return pictureVOPage;
        }
        // 对象列表 => 封装对象列表
        List<PictureVO> pictureVOList = pictureList.stream().map(PictureVO::objToVo).collect(Collectors.toList());
        // 1. 关联查询用户信息
        Set<Long> userIdSet = pictureList.stream().map(Picture::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // 2. 填充信息
        pictureVOList.forEach(pictureVO -> {
            Long userId = pictureVO.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            pictureVO.setUser(userService.getUserVO(user));
        });
        pictureVOPage.setRecords(pictureVOList);
        return pictureVOPage;
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
            // tags 是 JSON 数组字符串，每个标签长度之和也可能超过数据库字段长度
            ThrowUtils.throwIf(tags.length() > 512, ErrorCode.PARAMS_ERROR, "标签总长度不能超过 512 个字符");
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
        Picture updatePicture = new Picture();
        updatePicture.setId(id);
        updatePicture.setReviewStatus(reviewStatus);
        updatePicture.setReviewMessage(pictureReviewRequest.getReviewMessage());
        updatePicture.setReviewerId(loginUser.getId());
        updatePicture.setReviewTime(new Date());
        boolean result = this.updateById(updatePicture);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "审核失败");
        // 审核结果落库成功后再记录历史，失败不影响本次审核
        pictureReviewLogService.record(id, loginUser.getId(), reviewStatus, reviewMessage);
    }

    @Override
    public List<PictureReviewLog> listPictureReviewLogs(Long pictureId) {
        ThrowUtils.throwIf(pictureId == null || pictureId <= 0,
                ErrorCode.PARAMS_ERROR, "图片 id 不能为空");
        return pictureReviewLogService.listByPictureId(pictureId);
    }

}




