package com.tzy.picturebackend.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.GlobalExceptionHandler;
import com.tzy.picturebackend.manager.PictureListCache;
import com.tzy.picturebackend.model.dto.picture.PictureEditRequest;
import com.tzy.picturebackend.model.dto.picture.PictureListPage;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.picture.PictureUploadRequest;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.PictureReviewBatchResult;
import com.tzy.picturebackend.model.vo.PictureReviewLogVO;
import com.tzy.picturebackend.model.vo.PictureTagCategory;
import com.tzy.picturebackend.model.vo.PictureVO;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PictureController 接口测试：普通用户上传、multipart 参数绑定、我的图片归属校验
 */
class PictureControllerTest {

    private MockMvc mockMvc;

    private PictureService pictureService;

    private UserService userService;

    private PictureListCache pictureListCache;

    @BeforeEach
    void setUp() {
        pictureService = mock(PictureService.class);
        userService = mock(UserService.class);
        pictureListCache = mock(PictureListCache.class);
        PictureController pictureController = new PictureController();
        ReflectionTestUtils.setField(pictureController, "pictureService", pictureService);
        ReflectionTestUtils.setField(pictureController, "userService", userService);
        ReflectionTestUtils.setField(pictureController, "pictureListCache", pictureListCache);
        mockMvc = MockMvcBuilders.standaloneSetup(pictureController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("上传：普通用户可以通过 multipart 上传，id 能正确绑定")
    void uploadPicture_bindsMultipartForNormalUser() throws Exception {
        User normalUser = user(100L, "user");
        when(userService.getLoginUser(any())).thenReturn(normalUser);
        PictureVO pictureVO = new PictureVO();
        pictureVO.setId(123L);
        when(pictureService.uploadPicture(any(), any(), eq(normalUser))).thenReturn(pictureVO);

        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});

        mockMvc.perform(multipart("/picture/upload")
                        .file(file)
                        .param("id", "123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<PictureUploadRequest> requestCaptor =
                ArgumentCaptor.forClass(PictureUploadRequest.class);
        verify(pictureService).uploadPicture(any(), requestCaptor.capture(), eq(normalUser));
        assertEquals(Long.valueOf(123L), requestCaptor.getValue().getId());
    }

    @Test
    @DisplayName("我的图片：强制使用当前登录用户 id，忽略客户端传入的 userId")
    void listMyPictureVOByPage_forcesLoginUserId() throws Exception {
        User normalUser = user(100L, "user");
        when(userService.getLoginUser(any())).thenReturn(normalUser);
        when(pictureService.page(any(Page.class), any())).thenReturn(new Page<>(1, 10));
        when(pictureService.getPictureVOPage(any(), any())).thenReturn(new Page<>(1, 10));

        mockMvc.perform(post("/picture/my/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"999\",\"pageSize\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<PictureQueryRequest> requestCaptor =
                ArgumentCaptor.forClass(PictureQueryRequest.class);
        verify(pictureService).getQueryWrapper(requestCaptor.capture());
        assertEquals(Long.valueOf(100L), requestCaptor.getValue().getUserId());
    }

    @Test
    @DisplayName("上传：超过大小限制返回参数错误，而不是系统错误")
    void uploadPicture_reportsOversizedFileAsParamsError() throws Exception {
        User normalUser = user(100L, "user");
        when(userService.getLoginUser(any())).thenReturn(normalUser);
        when(pictureService.uploadPicture(any(), any(), eq(normalUser)))
                .thenThrow(new MaxUploadSizeExceededException(2 * 1024 * 1024L));

        MockMultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});

        mockMvc.perform(multipart("/picture/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("文件大小不能超过 2M"));
    }

    // ==================== 删除 ====================

    @Test
    @DisplayName("删除：登录用户可以删除自己的图片")
    void deletePicture_delegatesToService() throws Exception {
        User normalUser = user(100L, "user");
        when(userService.getLoginUser(any())).thenReturn(normalUser);
        when(pictureService.deletePicture(1L, normalUser)).thenReturn(true);

        mockMvc.perform(post("/picture/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        verify(pictureService).deletePicture(1L, normalUser);
    }

    @Test
    @DisplayName("删除：空 body 返回参数错误，不触发空指针")
    void deletePicture_rejectsEmptyBody() throws Exception {
        mockMvc.perform(post("/picture/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));

        verify(pictureService, never()).deletePicture(any(), any());
    }

    @Test
    @DisplayName("删除：未登录返回未登录业务码")
    void deletePicture_requiresLogin() throws Exception {
        when(userService.getLoginUser(any())).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        mockMvc.perform(post("/picture/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100));

        verify(pictureService, never()).deletePicture(any(), any());
    }

    // ==================== 编辑 ====================

    @Test
    @DisplayName("编辑：委托给 service，并传入当前登录用户")
    void editPicture_delegatesToService() throws Exception {
        User normalUser = user(100L, "user");
        when(userService.getLoginUser(any())).thenReturn(normalUser);
        when(pictureService.editPicture(any(), eq(normalUser))).thenReturn(true);

        mockMvc.perform(post("/picture/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\",\"name\":\"新名称\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        ArgumentCaptor<PictureEditRequest> requestCaptor =
                ArgumentCaptor.forClass(PictureEditRequest.class);
        verify(pictureService).editPicture(requestCaptor.capture(), eq(normalUser));
        assertEquals(Long.valueOf(1L), requestCaptor.getValue().getId());
        assertEquals("新名称", requestCaptor.getValue().getName());
    }

    // ==================== 公开列表缓存 ====================

    @Test
    @DisplayName("公开列表：缓存命中时直接返回，不查询数据库")
    void listPictureVOByPage_returnsCachedPage() throws Exception {
        PictureVO cachedPicture = new PictureVO();
        cachedPicture.setId(9L);
        cachedPicture.setName("缓存图片");
        PictureListPage cached = new PictureListPage();
        cached.setRecords(List.of(cachedPicture));
        cached.setTotal(1);
        cached.setCurrent(1);
        cached.setSize(10);
        when(pictureListCache.get(any())).thenReturn(cached);

        mockMvc.perform(post("/picture/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":1,\"pageSize\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].name").value("缓存图片"));

        verify(pictureService, never()).page(any(), any());
        verify(pictureListCache, never()).put(any(), any());
    }

    @Test
    @DisplayName("公开列表：未命中时回源数据库，并只缓存已通过审核的内容")
    void listPictureVOByPage_populatesCacheOnMiss() throws Exception {
        when(pictureListCache.get(any())).thenReturn(null);
        when(pictureService.page(any(Page.class), any())).thenReturn(new Page<>(1, 10));
        when(pictureService.getPictureVOPage(any(), any())).thenReturn(new Page<>(1, 10));

        mockMvc.perform(post("/picture/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":1,\"pageSize\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<PictureQueryRequest> requestCaptor =
                ArgumentCaptor.forClass(PictureQueryRequest.class);
        verify(pictureListCache).put(requestCaptor.capture(), any());
        assertEquals(Integer.valueOf(1), requestCaptor.getValue().getReviewStatus(),
                "公开列表的缓存 key 必须带上已通过审核的状态");
    }

    @Test
    @DisplayName("批量审核：返回成功与失败统计")
    void batchReview_returnsResult() throws Exception {
        User admin = user(1L, "admin");
        when(userService.getLoginUser(any())).thenReturn(admin);
        PictureReviewBatchResult result = new PictureReviewBatchResult();
        result.setSuccessCount(2);
        result.setFailCount(1);
        when(pictureService.batchReview(any(), eq(admin))).thenReturn(result);

        mockMvc.perform(post("/picture/review/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"1\",\"2\",\"3\"],\"reviewStatus\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.successCount").value(2))
                .andExpect(jsonPath("$.data.failCount").value(1));
    }

    @Test
    @DisplayName("下载统计：记录下载成功")
    void downloadPicture_recordsCount() throws Exception {
        when(pictureService.incrementDownloadCount(eq(1L), any())).thenReturn(true);

        mockMvc.perform(post("/picture/download/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("标签分类：接口返回服务层动态汇总结果")
    void listPictureTagCategory_returnsDynamicValues() throws Exception {
        PictureTagCategory tagCategory = new PictureTagCategory();
        tagCategory.setTagList(List.of("热门"));
        tagCategory.setCategoryList(List.of("模板"));
        when(pictureService.listTagCategory()).thenReturn(tagCategory);

        mockMvc.perform(get("/picture/tag_category"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tagList[0]").value("热门"))
                .andExpect(jsonPath("$.data.categoryList[0]").value("模板"));
    }

    @Test
    @DisplayName("审核历史：管理员可以按图片 id 查询记录，响应包含审核人昵称")
    void listPictureReviewLogs_returnsLogs() throws Exception {
        PictureReviewLogVO reviewLog = new PictureReviewLogVO();
        reviewLog.setId(1L);
        reviewLog.setReviewStatus(1);
        reviewLog.setReviewerId(99L);
        reviewLog.setReviewerName("管理员");
        reviewLog.setReviewerAccount("admin");
        when(pictureService.listPictureReviewLogs(2L)).thenReturn(List.of(reviewLog));

        mockMvc.perform(get("/picture/review/log").param("pictureId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].reviewerName").value("管理员"))
                .andExpect(jsonPath("$.data[0].reviewerAccount").value("admin"));
    }

    private User user(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }
}
