package com.tzy.picturebackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tzy.picturebackend.config.AiProperties;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.manager.AiSuggestionException;
import com.tzy.picturebackend.manager.AiSuggestionPayload;
import com.tzy.picturebackend.manager.PictureAiTagger;
import com.tzy.picturebackend.mapper.PictureAiSuggestionMapper;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.PictureAiSuggestion;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.AiSuggestionStatusEnum;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.vo.PictureAiSuggestionVO;
import com.tzy.picturebackend.model.vo.PictureTagCategory;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.TagCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 建议生成：幂等、每日上限、重试、输出校验与查询权限
 */
class AiSuggestionServiceImplTest {

    private PictureAiSuggestionMapper suggestionMapper;

    private PictureAiTagger pictureAiTagger;

    private AiProperties aiProperties;

    private TagCategoryService tagCategoryService;

    private PictureService pictureService;

    private StringRedisTemplate stringRedisTemplate;

    private ValueOperations<String, String> valueOperations;

    private AiSuggestionServiceImpl aiSuggestionService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        suggestionMapper = mock(PictureAiSuggestionMapper.class);
        pictureAiTagger = mock(PictureAiTagger.class);
        aiProperties = new AiProperties();
        aiProperties.setEnabled(true);
        aiProperties.setModel("deepseek-flash");
        aiProperties.setDailyLimit(20);
        aiProperties.setMaxRetries(2);
        aiProperties.setRetryDelayMillis(0);
        tagCategoryService = mock(TagCategoryService.class);
        pictureService = mock(PictureService.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        aiSuggestionService = new AiSuggestionServiceImpl(suggestionMapper, pictureAiTagger,
                aiProperties, tagCategoryService, pictureService, stringRedisTemplate);
    }

    @Test
    @DisplayName("生成成功：写处理中记录、调用缩略图、校验后写成功结果")
    void generate_success() {
        Picture picture = picture(1L, PictureReviewStatusEnum.REVIEWING.getValue());
        when(suggestionMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(tagCategoryService.listAll()).thenReturn(dictionary());
        when(pictureAiTagger.suggest(anyString(), any(), any()))
                .thenReturn(new AiSuggestionPayload("海边日落", "夕阳下的海面", "风景",
                        List.of("海边", "日落")));
        when(suggestionMapper.insert(any(PictureAiSuggestion.class))).thenReturn(1);
        when(suggestionMapper.updateById(any(PictureAiSuggestion.class))).thenReturn(1);

        aiSuggestionService.generate(picture);

        ArgumentCaptor<PictureAiSuggestion> insertCaptor =
                ArgumentCaptor.forClass(PictureAiSuggestion.class);
        verify(suggestionMapper).insert(insertCaptor.capture());
        assertEquals(Long.valueOf(1L), insertCaptor.getValue().getPictureId());
        assertEquals(Integer.valueOf(AiSuggestionStatusEnum.PROCESSING.getValue()),
                insertCaptor.getValue().getStatus());
        assertEquals("deepseek-flash", insertCaptor.getValue().getModel());

        ArgumentCaptor<PictureAiSuggestion> updateCaptor =
                ArgumentCaptor.forClass(PictureAiSuggestion.class);
        verify(suggestionMapper).updateById(updateCaptor.capture());
        PictureAiSuggestion saved = updateCaptor.getValue();
        assertEquals(Integer.valueOf(AiSuggestionStatusEnum.SUCCESS.getValue()), saved.getStatus());
        assertEquals("海边日落", saved.getName());
        assertEquals("风景", saved.getCategory());
        assertEquals("[\"海边\",\"日落\"]", saved.getTags());

        verify(pictureAiTagger).suggest(
                "https://bucket.cos.ap-beijing.myqcloud.com/public/1/a.jpg?imageMogr2/thumbnail/1024x",
                List.of("风景", "人物"), List.of("海边", "日落"));
        verify(stringRedisTemplate).expire(anyString(), eq(Duration.ofDays(2)));
    }

    @Test
    @DisplayName("输出校验：清理超长名称、字典外分类、重复/超长标签")
    void sanitize_cleansModelOutput() {
        Picture picture = picture(1L, PictureReviewStatusEnum.REVIEWING.getValue());
        AiSuggestionPayload raw = new AiSuggestionPayload(
                "a".repeat(200), "b".repeat(600), "字典外分类",
                List.of(" 海边 ", "海边", "", "x".repeat(33), "日落"));

        AiSuggestionPayload result = aiSuggestionService.sanitize(raw, List.of("风景", "人物"), picture);

        assertEquals(128, result.name().length());
        assertEquals(512, result.introduction().length());
        assertNull(result.category());
        assertEquals(List.of("海边", "日落"), result.tags());
    }

    @Test
    @DisplayName("每日上限：超出后不调用模型，写失败记录")
    void generate_dailyLimitExceeded() {
        Picture picture = picture(1L, PictureReviewStatusEnum.REVIEWING.getValue());
        when(suggestionMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(valueOperations.increment(anyString())).thenReturn(21L);

        aiSuggestionService.generate(picture);

        ArgumentCaptor<PictureAiSuggestion> captor =
                ArgumentCaptor.forClass(PictureAiSuggestion.class);
        verify(suggestionMapper).insert(captor.capture());
        assertEquals(Integer.valueOf(AiSuggestionStatusEnum.FAILED.getValue()),
                captor.getValue().getStatus());
        verifyNoInteractions(pictureAiTagger);
    }

    @Test
    @DisplayName("幂等：已有成功建议时不再调用模型")
    void generate_existingSuccessSkips() {
        Picture picture = picture(1L, PictureReviewStatusEnum.REVIEWING.getValue());
        when(suggestionMapper.selectOne(any(Wrapper.class))).thenReturn(suggestionRow(1L, 1));

        aiSuggestionService.generate(picture);

        verifyNoInteractions(pictureAiTagger);
        verify(suggestionMapper, never()).insert(any(PictureAiSuggestion.class));
    }

    @Test
    @DisplayName("重传：旧建议早于图片 editTime 时删除并重新生成")
    void generate_staleSuggestionRegenerates() {
        Picture picture = picture(1L, PictureReviewStatusEnum.REVIEWING.getValue());
        picture.setEditTime(new Date());
        PictureAiSuggestion old = suggestionRow(1L, 1);
        old.setCreateTime(new Date(System.currentTimeMillis() - 60_000));
        when(suggestionMapper.selectOne(any(Wrapper.class))).thenReturn(old);
        when(suggestionMapper.deleteById(10L)).thenReturn(1);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(tagCategoryService.listAll()).thenReturn(dictionary());
        when(pictureAiTagger.suggest(anyString(), any(), any()))
                .thenReturn(new AiSuggestionPayload("新建议", null, "风景", List.of("海边")));
        when(suggestionMapper.insert(any(PictureAiSuggestion.class))).thenReturn(1);
        when(suggestionMapper.updateById(any(PictureAiSuggestion.class))).thenReturn(1);

        aiSuggestionService.generate(picture);

        verify(suggestionMapper).deleteById(10L);
        verify(pictureAiTagger).suggest(anyString(), any(), any());
        verify(suggestionMapper).insert(any(PictureAiSuggestion.class));
    }

    @Test
    @DisplayName("重试：可重试错误在 maxRetries 内退避重试")
    void generate_retriesRetryableFailure() {
        Picture picture = picture(1L, PictureReviewStatusEnum.REVIEWING.getValue());
        when(suggestionMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(tagCategoryService.listAll()).thenReturn(dictionary());
        when(pictureAiTagger.suggest(anyString(), any(), any()))
                .thenThrow(AiSuggestionException.retryable("429"))
                .thenThrow(AiSuggestionException.retryable("429"))
                .thenReturn(new AiSuggestionPayload("最终成功", null, "风景", List.of("海边")));
        when(suggestionMapper.insert(any(PictureAiSuggestion.class))).thenReturn(1);
        when(suggestionMapper.updateById(any(PictureAiSuggestion.class))).thenReturn(1);

        aiSuggestionService.generate(picture);

        verify(pictureAiTagger, times(3)).suggest(anyString(), any(), any());
        ArgumentCaptor<PictureAiSuggestion> captor =
                ArgumentCaptor.forClass(PictureAiSuggestion.class);
        verify(suggestionMapper).updateById(captor.capture());
        assertEquals(Integer.valueOf(AiSuggestionStatusEnum.SUCCESS.getValue()),
                captor.getValue().getStatus());
    }

    @Test
    @DisplayName("不重试：拒答/解析失败只调用一次并标记失败")
    void generate_doesNotRetryPermanentFailure() {
        Picture picture = picture(1L, PictureReviewStatusEnum.REVIEWING.getValue());
        when(suggestionMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(tagCategoryService.listAll()).thenReturn(dictionary());
        when(pictureAiTagger.suggest(anyString(), any(), any()))
                .thenThrow(AiSuggestionException.permanent("解析失败"));
        when(suggestionMapper.insert(any(PictureAiSuggestion.class))).thenReturn(1);
        when(suggestionMapper.updateById(any(PictureAiSuggestion.class))).thenReturn(1);

        aiSuggestionService.generate(picture);

        verify(pictureAiTagger, times(1)).suggest(anyString(), any(), any());
        ArgumentCaptor<PictureAiSuggestion> captor =
                ArgumentCaptor.forClass(PictureAiSuggestion.class);
        verify(suggestionMapper).updateById(captor.capture());
        assertEquals(Integer.valueOf(AiSuggestionStatusEnum.FAILED.getValue()),
                captor.getValue().getStatus());
    }

    @Test
    @DisplayName("异常：Redis 计数失败时放弃本次生成")
    void generate_redisFailureSkipsModel() {
        Picture picture = picture(1L, PictureReviewStatusEnum.REVIEWING.getValue());
        when(suggestionMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(valueOperations.increment(anyString())).thenThrow(new RuntimeException("redis down"));

        aiSuggestionService.generate(picture);

        verifyNoInteractions(pictureAiTagger);
        verify(suggestionMapper).insert(any(PictureAiSuggestion.class));
    }

    @Test
    @DisplayName("开关关闭或图片已拒绝时不生成")
    void generate_disabledOrRejectedSkips() {
        aiProperties.setEnabled(false);
        aiSuggestionService.generate(picture(1L, PictureReviewStatusEnum.REVIEWING.getValue()));
        verifyNoInteractions(suggestionMapper, pictureAiTagger);

        aiProperties.setEnabled(true);
        aiSuggestionService.generate(picture(2L, PictureReviewStatusEnum.REJECT.getValue()));
        verifyNoInteractions(suggestionMapper, pictureAiTagger);
    }

    @Test
    @DisplayName("查询：作者可读，标签从 JSON 还原")
    void getSuggestion_ownerCanRead() {
        User owner = user(100L, "user");
        when(pictureService.getById(1L)).thenReturn(picture(1L, 0));
        PictureAiSuggestion row = suggestionRow(1L, 1);
        row.setName("海边日落");
        row.setCategory("风景");
        row.setTags("[\"海边\",\"日落\"]");
        when(suggestionMapper.selectOne(any(Wrapper.class))).thenReturn(row);

        PictureAiSuggestionVO vo = aiSuggestionService.getSuggestion(1L, owner);

        assertEquals("海边日落", vo.getName());
        assertEquals("风景", vo.getCategory());
        assertEquals(List.of("海边", "日落"), vo.getTags());
        assertEquals(Integer.valueOf(1), vo.getStatus());
    }

    @Test
    @DisplayName("查询：管理员可读他人图片的建议")
    void getSuggestion_adminCanRead() {
        when(pictureService.getById(1L)).thenReturn(picture(1L, 0));
        when(suggestionMapper.selectOne(any(Wrapper.class))).thenReturn(suggestionRow(1L, 1));

        PictureAiSuggestionVO vo = aiSuggestionService.getSuggestion(1L, user(9L, "admin"));

        assertEquals(Long.valueOf(1L), vo.getPictureId());
    }

    @Test
    @DisplayName("查询：非作者非管理员拒绝，图片不存在报 404")
    void getSuggestion_rejectsOtherUserAndMissingPicture() {
        when(pictureService.getById(1L)).thenReturn(picture(1L, 0));
        assertThrows(BusinessException.class,
                () -> aiSuggestionService.getSuggestion(1L, user(200L, "user")));

        when(pictureService.getById(2L)).thenReturn(null);
        assertThrows(BusinessException.class,
                () -> aiSuggestionService.getSuggestion(2L, user(100L, "user")));
    }

    @Test
    @DisplayName("查询：尚未生成时返回 null")
    void getSuggestion_returnsNullWhenAbsent() {
        when(pictureService.getById(1L)).thenReturn(picture(1L, 0));
        when(suggestionMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertNull(aiSuggestionService.getSuggestion(1L, user(100L, "user")));
    }

    @Test
    @DisplayName("缩略图：按原地址拼接 imageMogr2，已是处理地址则不重复拼接")
    void buildThumbnailUrl_appendsImageMogr2() {
        assertEquals("https://a.com/x.jpg?imageMogr2/thumbnail/1024x",
                AiSuggestionServiceImpl.buildThumbnailUrl("https://a.com/x.jpg"));
        assertEquals("https://a.com/x.jpg?a=1&imageMogr2/thumbnail/1024x",
                AiSuggestionServiceImpl.buildThumbnailUrl("https://a.com/x.jpg?a=1"));
        assertEquals("https://a.com/x.jpg?imageMogr2/thumbnail/512x",
                AiSuggestionServiceImpl.buildThumbnailUrl("https://a.com/x.jpg?imageMogr2/thumbnail/512x"));
    }

    private Picture picture(Long id, int reviewStatus) {
        Picture picture = new Picture();
        picture.setId(id);
        picture.setUserId(100L);
        picture.setName("原名称");
        picture.setUrl("https://bucket.cos.ap-beijing.myqcloud.com/public/1/a.jpg");
        picture.setReviewStatus(reviewStatus);
        return picture;
    }

    private PictureAiSuggestion suggestionRow(Long pictureId, int status) {
        PictureAiSuggestion row = new PictureAiSuggestion();
        row.setId(10L);
        row.setPictureId(pictureId);
        row.setStatus(status);
        row.setModel("deepseek-flash");
        row.setCreateTime(new Date());
        return row;
    }

    private PictureTagCategory dictionary() {
        PictureTagCategory dictionary = new PictureTagCategory();
        dictionary.setCategoryList(List.of("风景", "人物"));
        dictionary.setTagList(List.of("海边", "日落"));
        return dictionary;
    }

    private User user(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }
}
