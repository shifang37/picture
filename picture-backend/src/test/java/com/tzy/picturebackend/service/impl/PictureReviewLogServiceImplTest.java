package com.tzy.picturebackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tzy.picturebackend.mapper.PictureReviewLogMapper;
import com.tzy.picturebackend.model.entity.PictureReviewLog;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.PictureReviewLogVO;
import com.tzy.picturebackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审核记录服务测试
 */
class PictureReviewLogServiceImplTest {

    private PictureReviewLogServiceImpl reviewLogService;

    private PictureReviewLogMapper reviewLogMapper;

    private UserService userService;

    @BeforeEach
    void setUp() {
        reviewLogService = new PictureReviewLogServiceImpl();
        reviewLogMapper = mock(PictureReviewLogMapper.class);
        userService = mock(UserService.class);
        ReflectionTestUtils.setField(reviewLogService, "baseMapper", reviewLogMapper);
        ReflectionTestUtils.setField(reviewLogService, "userService", userService);
    }

    @Test
    @DisplayName("记录审核：写入图片、审核人、状态与意见")
    void record_savesReviewLog() {
        when(reviewLogMapper.insert(any(PictureReviewLog.class))).thenReturn(1);

        reviewLogService.record(1L, 99L, 2, "内容不合规");

        ArgumentCaptor<PictureReviewLog> captor = ArgumentCaptor.forClass(PictureReviewLog.class);
        verify(reviewLogMapper).insert(captor.capture());
        PictureReviewLog saved = captor.getValue();
        assertEquals(Long.valueOf(1L), saved.getPictureId());
        assertEquals(Long.valueOf(99L), saved.getReviewerId());
        assertEquals(Integer.valueOf(2), saved.getReviewStatus());
        assertEquals("内容不合规", saved.getReviewMessage());
        assertNotNull(saved.getCreateTime());
    }

    @Test
    @DisplayName("记录审核：数据库异常不影响调用方")
    void record_swallowsDatabaseException() {
        when(reviewLogMapper.insert(any(PictureReviewLog.class))).thenThrow(new RuntimeException("db down"));

        reviewLogService.record(1L, 99L, 1, null);
    }

    @Test
    @DisplayName("查询历史：按图片 id 返回记录")
    void listByPictureId_returnsLogs() {
        PictureReviewLog log = new PictureReviewLog();
        log.setId(1L);
        when(reviewLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(log));

        List<PictureReviewLog> logs = reviewLogService.listByPictureId(1L);

        assertEquals(1, logs.size());
        assertEquals(Long.valueOf(1L), logs.get(0).getId());
    }

    @Test
    @DisplayName("审核历史 VO：批量补全审核人昵称与账号")
    void listVOByPictureId_fillsReviewerNameAndAccount() {
        PictureReviewLog first = reviewLog(1L, 10L, 1, "管理员上传，自动通过");
        PictureReviewLog second = reviewLog(2L, 20L, 2, "内容不合规");
        when(reviewLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(userService.listByIds(anyCollection())).thenReturn(List.of(
                user(10L, "admin", "管理员", "admin"),
                user(20L, "user", "张三", "zhangsan")));

        List<PictureReviewLogVO> result = reviewLogService.listVOByPictureId(1L);

        assertEquals(2, result.size());
        PictureReviewLogVO firstVO = result.get(0);
        assertEquals(Long.valueOf(1L), firstVO.getId());
        assertEquals(Long.valueOf(1L), firstVO.getPictureId());
        assertEquals(Long.valueOf(10L), firstVO.getReviewerId());
        assertEquals("管理员", firstVO.getReviewerName());
        assertEquals("admin", firstVO.getReviewerAccount());
        assertEquals(Integer.valueOf(1), firstVO.getReviewStatus());
        assertEquals("管理员上传，自动通过", firstVO.getReviewMessage());
        assertNotNull(firstVO.getCreateTime());

        assertEquals("张三", result.get(1).getReviewerName());
        assertEquals("zhangsan", result.get(1).getReviewerAccount());
    }

    @Test
    @DisplayName("审核历史 VO：昵称为空时回退为账号")
    void listVOByPictureId_fallsBackToAccountWhenNameBlank() {
        when(reviewLogMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(reviewLog(1L, 10L, 1, null)));
        when(userService.listByIds(anyCollection()))
                .thenReturn(List.of(user(10L, "admin", "  ", "admin")));

        List<PictureReviewLogVO> result = reviewLogService.listVOByPictureId(1L);

        assertEquals("admin", result.get(0).getReviewerName());
        assertEquals("admin", result.get(0).getReviewerAccount());
    }

    @Test
    @DisplayName("审核历史 VO：审核人已被删除时回退为「用户#id」")
    void listVOByPictureId_fallsBackForDeletedReviewer() {
        when(reviewLogMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(reviewLog(1L, 99L, 2, "内容不合规")));
        when(userService.listByIds(anyCollection())).thenReturn(List.of());

        List<PictureReviewLogVO> result = reviewLogService.listVOByPictureId(1L);

        assertEquals("用户#99", result.get(0).getReviewerName());
        assertNull(result.get(0).getReviewerAccount());
    }

    @Test
    @DisplayName("审核历史 VO：无记录时不查询用户")
    void listVOByPictureId_returnsEmptyWithoutQueryingUsers() {
        when(reviewLogMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        List<PictureReviewLogVO> result = reviewLogService.listVOByPictureId(1L);

        assertEquals(0, result.size());
        verify(userService, never()).listByIds(anyCollection());
    }

    private PictureReviewLog reviewLog(Long id, Long reviewerId, int reviewStatus, String message) {
        PictureReviewLog log = new PictureReviewLog();
        log.setId(id);
        log.setPictureId(1L);
        log.setReviewerId(reviewerId);
        log.setReviewStatus(reviewStatus);
        log.setReviewMessage(message);
        log.setCreateTime(new java.util.Date());
        return log;
    }

    private User user(Long id, String role, String name, String account) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        user.setUserName(name);
        user.setUserAccount(account);
        return user;
    }
}
