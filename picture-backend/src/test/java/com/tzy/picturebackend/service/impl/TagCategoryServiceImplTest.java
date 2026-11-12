package com.tzy.picturebackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.mapper.TagCategoryMapper;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.TagCategory;
import com.tzy.picturebackend.model.vo.PictureTagCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标签/分类字典服务测试
 */
class TagCategoryServiceImplTest {

    private TagCategoryServiceImpl tagCategoryService;

    private TagCategoryMapper tagCategoryMapper;

    private PictureMapper pictureMapper;

    @BeforeEach
    void setUp() {
        tagCategoryService = new TagCategoryServiceImpl();
        tagCategoryMapper = mock(TagCategoryMapper.class);
        pictureMapper = mock(PictureMapper.class);
        ReflectionTestUtils.setField(tagCategoryService, "baseMapper", tagCategoryMapper);
        ReflectionTestUtils.setField(tagCategoryService, "pictureMapper", pictureMapper);
    }

    // ==================== 新增 ====================

    @Test
    @DisplayName("新增：分类写入 type/name 并返回成功")
    void add_savesCategory() {
        when(tagCategoryMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(tagCategoryMapper.insert(any(TagCategory.class))).thenReturn(1);

        assertTrue(tagCategoryService.add("category", " 新分类 "));

        ArgumentCaptor<TagCategory> captor = ArgumentCaptor.forClass(TagCategory.class);
        verify(tagCategoryMapper).insert(captor.capture());
        TagCategory saved = captor.getValue();
        assertEquals("category", saved.getType());
        assertEquals("新分类", saved.getName());
        assertTrue(saved.getCreateTime() != null);
    }

    @Test
    @DisplayName("新增：同名分类返回参数错误")
    void add_rejectsDuplicate() {
        when(tagCategoryMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> tagCategoryService.add("category", "模板"));
        assertEquals("该分类已存在", e.getMessage());
        verify(tagCategoryMapper, never()).insert(any(TagCategory.class));
    }

    @Test
    @DisplayName("新增：同名标签返回参数错误")
    void add_rejectsDuplicateTag() {
        when(tagCategoryMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        BusinessException e = assertThrows(BusinessException.class,
                () -> tagCategoryService.add("tag", "热门"));
        assertEquals("该标签已存在", e.getMessage());
    }

    @Test
    @DisplayName("新增：非法类型返回参数错误")
    void add_rejectsIllegalType() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> tagCategoryService.add("other", "名称"));
        assertEquals("类型只能是 tag 或 category", e.getMessage());
    }

    @Test
    @DisplayName("新增：空名称返回参数错误")
    void add_rejectsBlankName() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> tagCategoryService.add("tag", "   "));
        assertEquals("名称不能为空", e.getMessage());
    }

    @Test
    @DisplayName("新增：标签超过 32 个字符返回参数错误，与图片标签上限一致")
    void add_rejectsTooLongTagName() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> tagCategoryService.add("tag", "t".repeat(33)));
        assertEquals("标签不能超过 32 个字符", e.getMessage());
    }

    @Test
    @DisplayName("新增：分类名称超过 64 个字符返回参数错误")
    void add_rejectsTooLongCategoryName() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> tagCategoryService.add("category", "c".repeat(65)));
        assertEquals("名称不能超过 64 个字符", e.getMessage());
    }

    // ==================== 删除 ====================

    @Test
    @DisplayName("删除：字典项存在时删除成功")
    void delete_removesExistingItem() {
        TagCategory existing = new TagCategory();
        existing.setId(1L);
        when(tagCategoryMapper.selectById(1L)).thenReturn(existing);
        when(tagCategoryMapper.deleteById(1L)).thenReturn(1);

        assertTrue(tagCategoryService.delete(1L));
        verify(tagCategoryMapper).deleteById(1L);
    }

    @Test
    @DisplayName("删除：字典项不存在返回 40400")
    void delete_rejectsMissingItem() {
        when(tagCategoryMapper.selectById(1L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> tagCategoryService.delete(1L));
        assertEquals("标签/分类不存在", e.getMessage());
    }

    @Test
    @DisplayName("删除：非法 id 返回参数错误")
    void delete_rejectsIllegalId() {
        assertThrows(BusinessException.class, () -> tagCategoryService.delete(0L));
        assertThrows(BusinessException.class, () -> tagCategoryService.delete(null));
        verify(tagCategoryMapper, never()).deleteById(any());
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("列表：分类与标签分别汇总并按名称排序")
    void listAll_splitsTypesAndSorts() {
        when(tagCategoryMapper.selectList(any())).thenReturn(List.of(
                tagCategory(1L, "tag", "热门"),
                tagCategory(2L, "category", "模板"),
                tagCategory(3L, "tag", "生活"),
                tagCategory(4L, "category", "电商")));

        PictureTagCategory result = tagCategoryService.listAll();

        assertEquals(List.of("模板", "电商"), result.getCategoryList());
        assertEquals(List.of("热门", "生活"), result.getTagList());
    }

    @Test
    @DisplayName("列表：空字典返回两个空列表")
    void listAll_returnsEmptyLists() {
        when(tagCategoryMapper.selectList(any())).thenReturn(List.of());

        PictureTagCategory result = tagCategoryService.listAll();

        assertTrue(result.getTagList().isEmpty());
        assertTrue(result.getCategoryList().isEmpty());
    }

    // ==================== 从图片同步 ====================

    @Test
    @DisplayName("同步：把图片中缺失的分类和标签补进字典")
    void syncFromPictures_addsMissingEntries() {
        Picture first = picture(1L, "模板", "[\"热门\",\"高清\"]");
        Picture second = picture(2L, "电商", "[\"热门\"]");
        when(pictureMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(tagCategoryMapper.selectList(any())).thenReturn(List.of(tagCategory(1L, "tag", "热门")));
        when(tagCategoryMapper.insert(any(TagCategory.class))).thenReturn(1);

        int added = tagCategoryService.syncFromPictures();

        assertEquals(3, added);
        ArgumentCaptor<TagCategory> captor = ArgumentCaptor.forClass(TagCategory.class);
        verify(tagCategoryMapper, times(3)).insert(captor.capture());
        List<TagCategory> saved = captor.getAllValues();
        assertTrue(saved.stream().anyMatch(item -> "category".equals(item.getType())
                && "模板".equals(item.getName())));
        assertTrue(saved.stream().anyMatch(item -> "category".equals(item.getType())
                && "电商".equals(item.getName())));
        assertTrue(saved.stream().anyMatch(item -> "tag".equals(item.getType())
                && "高清".equals(item.getName())));
    }

    @Test
    @DisplayName("同步：已存在的标签/分类不重复写入")
    void syncFromPictures_skipsExistingEntries() {
        when(pictureMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(picture(1L, "模板", "[\"热门\"]")));
        when(tagCategoryMapper.selectList(any())).thenReturn(List.of(
                tagCategory(1L, "category", "模板"),
                tagCategory(2L, "tag", "热门")));

        assertEquals(0, tagCategoryService.syncFromPictures());
        verify(tagCategoryMapper, never()).insert(any(TagCategory.class));
    }

    @Test
    @DisplayName("同步：没有可同步的图片时不写数据库")
    void syncFromPictures_handlesNoPictures() {
        when(pictureMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertEquals(0, tagCategoryService.syncFromPictures());
        verify(tagCategoryMapper, never()).insert(any(TagCategory.class));
    }

    @Test
    @DisplayName("同步：无法解析的标签 JSON 不影响其他条目")
    void syncFromPictures_skipsInvalidTagsJson() {
        when(pictureMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(picture(1L, "模板", "not-a-json")));
        when(tagCategoryMapper.selectList(any())).thenReturn(List.of());
        when(tagCategoryMapper.insert(any(TagCategory.class))).thenReturn(1);

        assertEquals(1, tagCategoryService.syncFromPictures());
        verify(tagCategoryMapper, times(1)).insert(any(TagCategory.class));
    }

    @Test
    @DisplayName("同步：唯一索引冲突只跳过，不中断整批")
    void syncFromPictures_swallowsDuplicateKey() {
        when(pictureMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(picture(1L, "模板", "[\"热门\"]")));
        when(tagCategoryMapper.selectList(any())).thenReturn(List.of());
        when(tagCategoryMapper.insert(any(TagCategory.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));

        assertEquals(0, tagCategoryService.syncFromPictures());
    }

    // ==================== 辅助方法 ====================

    private TagCategory tagCategory(Long id, String type, String name) {
        TagCategory tagCategory = new TagCategory();
        tagCategory.setId(id);
        tagCategory.setType(type);
        tagCategory.setName(name);
        return tagCategory;
    }

    private Picture picture(Long id, String category, String tags) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setCategory(category);
        picture.setTags(tags);
        return picture;
    }
}
