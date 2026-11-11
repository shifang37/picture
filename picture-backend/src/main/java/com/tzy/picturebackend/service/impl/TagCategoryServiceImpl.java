package com.tzy.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.mapper.TagCategoryMapper;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.TagCategory;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.vo.PictureTagCategory;
import com.tzy.picturebackend.service.TagCategoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 标签/分类字典服务实现
 */
@Service
@Slf4j
public class TagCategoryServiceImpl
        extends ServiceImpl<TagCategoryMapper, TagCategory>
        implements TagCategoryService {

    /**
     * 名称最大长度，与数据库字段一致
     */
    private static final int MAX_NAME_LENGTH = 64;

    @Resource
    private PictureMapper pictureMapper;

    @Override
    public PictureTagCategory listAll() {
        Set<String> tagSet = new TreeSet<>();
        Set<String> categorySet = new TreeSet<>();
        for (TagCategory tagCategory : listAllWithId()) {
            if (TYPE_TAG.equals(tagCategory.getType()) && StrUtil.isNotBlank(tagCategory.getName())) {
                tagSet.add(tagCategory.getName());
            } else if (TYPE_CATEGORY.equals(tagCategory.getType()) && StrUtil.isNotBlank(tagCategory.getName())) {
                categorySet.add(tagCategory.getName());
            }
        }
        PictureTagCategory pictureTagCategory = new PictureTagCategory();
        pictureTagCategory.setTagList(new ArrayList<>(tagSet));
        pictureTagCategory.setCategoryList(new ArrayList<>(categorySet));
        return pictureTagCategory;
    }

    @Override
    public List<TagCategory> listAllWithId() {
        return this.list(new QueryWrapper<TagCategory>()
                .orderByAsc("type")
                .orderByAsc("name"));
    }

    @Override
    public boolean add(String type, String name) {
        String normalizedType = normalizeType(type);
        String normalizedName = normalizeName(name);
        long count = this.count(new QueryWrapper<TagCategory>()
                .eq("type", normalizedType)
                .eq("name", normalizedName));
        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR,
                "该" + labelOf(normalizedType) + "已存在");
        TagCategory tagCategory = new TagCategory();
        tagCategory.setType(normalizedType);
        tagCategory.setName(normalizedName);
        tagCategory.setCreateTime(new Date());
        return this.save(tagCategory);
    }

    @Override
    public boolean delete(Long id) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR, "id 不能为空");
        TagCategory existing = this.getById(id);
        ThrowUtils.throwIf(existing == null, ErrorCode.NOT_FOUND_ERROR, "标签/分类不存在");
        return this.removeById(id);
    }

    @Override
    public int syncFromPictures() {
        // 只同步已通过审核的图片，保持首页可见内容与字典一致
        List<Picture> pictures = pictureMapper.selectList(new QueryWrapper<Picture>()
                .select("id", "category", "tags")
                .eq("reviewStatus", PictureReviewStatusEnum.PASS.getValue()));
        if (CollUtil.isEmpty(pictures)) {
            return 0;
        }
        // 已存在的字典项先放入集合，避免逐条查库
        Set<String> existingKeys = this.list().stream()
                .filter(tagCategory -> StrUtil.isNotBlank(tagCategory.getType())
                        && StrUtil.isNotBlank(tagCategory.getName()))
                .map(tagCategory -> keyOf(tagCategory.getType(), tagCategory.getName()))
                .collect(Collectors.toSet());
        int added = 0;
        for (Picture picture : pictures) {
            if (StrUtil.isNotBlank(picture.getCategory())) {
                added += insertIfMissing(TYPE_CATEGORY, picture.getCategory().trim(), existingKeys);
            }
            if (StrUtil.isNotBlank(picture.getTags())) {
                try {
                    for (String tag : JSONUtil.toList(picture.getTags(), String.class)) {
                        if (StrUtil.isNotBlank(tag)) {
                            added += insertIfMissing(TYPE_TAG, tag.trim(), existingKeys);
                        }
                    }
                } catch (Exception e) {
                    log.warn("同步标签时解析图片标签失败, pictureId = {}", picture.getId(), e);
                }
            }
        }
        return added;
    }

    /**
     * 字典项不存在时写入，返回新增数量
     */
    private int insertIfMissing(String type, String name, Set<String> existingKeys) {
        String normalizedName = name.trim();
        if (StrUtil.isBlank(normalizedName) || normalizedName.length() > MAX_NAME_LENGTH) {
            log.warn("跳过不符合字典长度要求的名称, type = {}, name = {}", type, name);
            return 0;
        }
        if (!existingKeys.add(keyOf(type, normalizedName))) {
            return 0;
        }
        TagCategory tagCategory = new TagCategory();
        tagCategory.setType(type);
        tagCategory.setName(normalizedName);
        tagCategory.setCreateTime(new Date());
        try {
            return this.save(tagCategory) ? 1 : 0;
        } catch (DuplicateKeyException e) {
            // 并发同步或唯一索引兜底，跳过重复项即可
            log.debug("标签/分类已存在，跳过, type = {}, name = {}", type, normalizedName);
            return 0;
        }
    }

    /**
     * 校验并归一化类型
     */
    private String normalizeType(String type) {
        if (TYPE_TAG.equals(type) || TYPE_CATEGORY.equals(type)) {
            return type;
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "类型只能是 tag 或 category");
    }

    /**
     * 校验并归一化名称
     */
    private String normalizeName(String name) {
        ThrowUtils.throwIf(StrUtil.isBlank(name), ErrorCode.PARAMS_ERROR, "名称不能为空");
        String trimmed = name.trim();
        ThrowUtils.throwIf(trimmed.length() > MAX_NAME_LENGTH, ErrorCode.PARAMS_ERROR,
                "名称不能超过 " + MAX_NAME_LENGTH + " 个字符");
        return trimmed;
    }

    private String keyOf(String type, String name) {
        return type + "\u0000" + name;
    }

    private String labelOf(String type) {
        return TYPE_TAG.equals(type) ? "标签" : "分类";
    }
}
