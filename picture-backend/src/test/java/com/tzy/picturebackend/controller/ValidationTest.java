package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.exception.GlobalExceptionHandler;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DTO 参数校验测试：覆盖图片与用户字段的长度、角色、分页上限等边界
 */
class ValidationTest {

    private MockMvc mockMvc;

    private PictureService pictureService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        pictureService = mock(PictureService.class);
        userService = mock(UserService.class);

        PictureController pictureController = new PictureController();
        ReflectionTestUtils.setField(pictureController, "pictureService", pictureService);
        ReflectionTestUtils.setField(pictureController, "userService", userService);

        UserController userController = new UserController();
        ReflectionTestUtils.setField(userController, "userService", userService);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(pictureController, userController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    // ==================== 图片字段长度 ====================

    @Test
    @DisplayName("图片名称：超过 128 个字符返回 40000 与明确提示")
    void editPicture_rejectsTooLongName() throws Exception {
        mockMvc.perform(post("/picture/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody("a".repeat(129), "分类", "简介", Collections.emptyList())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("图片名称不能超过 128 个字符"));
    }

    @Test
    @DisplayName("图片名称：刚好 128 个字符可以通过校验")
    void editPicture_acceptsNameAtLimit() throws Exception {
        stubOwnerCanEdit();

        mockMvc.perform(post("/picture/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody("a".repeat(128), "分类", "简介", Collections.emptyList())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("图片分类：超过 64 个字符返回 40000")
    void editPicture_rejectsTooLongCategory() throws Exception {
        mockMvc.perform(post("/picture/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody("名称", "c".repeat(65), "简介", Collections.emptyList())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("分类不能超过 64 个字符"));
    }

    @Test
    @DisplayName("图片简介：超过 512 个字符返回 40000")
    void editPicture_rejectsTooLongIntroduction() throws Exception {
        mockMvc.perform(post("/picture/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody("名称", "分类", "i".repeat(513), Collections.emptyList())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("简介不能超过 512 个字符"));
    }

    @Test
    @DisplayName("图片标签：超过 20 个返回 40000")
    void editPicture_rejectsTooManyTags() throws Exception {
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tags.add("tag" + i);
        }
        mockMvc.perform(post("/picture/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody("名称", "分类", "简介", tags)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("标签最多 20 个"));
    }

    @Test
    @DisplayName("图片标签：单个标签超过 32 个字符返回 40000")
    void editPicture_rejectsTooLongTag() throws Exception {
        mockMvc.perform(post("/picture/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody("名称", "分类", "简介", List.of("t".repeat(33)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("单个标签不能超过 32 个字符"));
    }

    @Test
    @DisplayName("审核意见：超过 512 个字符返回 40000")
    void reviewPicture_rejectsTooLongMessage() throws Exception {
        mockMvc.perform(post("/picture/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\",\"reviewStatus\":1,\"reviewMessage\":\""
                                + "m".repeat(513) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("审核意见不能超过 512 个字符"));
    }

    @Test
    @DisplayName("审核状态：不允许待审核 0")
    void reviewPicture_rejectsReviewingStatus() throws Exception {
        mockMvc.perform(post("/picture/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"1\",\"reviewStatus\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
    }

    // ==================== 用户字段与角色 ====================

    @Test
    @DisplayName("用户角色：superadmin 返回 40000")
    void addUser_rejectsIllegalRole() throws Exception {
        mockMvc.perform(post("/user/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"testuser\",\"userRole\":\"superadmin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("用户角色只能是 user 或 admin"));
    }

    @Test
    @DisplayName("用户账号：少于 4 个字符返回 40000")
    void addUser_rejectsTooShortAccount() throws Exception {
        mockMvc.perform(post("/user/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"abc\",\"userRole\":\"user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("用户账号：重复创建返回 40000，而不是数据库唯一索引异常")
    void addUser_rejectsDuplicateAccount() throws Exception {
        when(userService.count(any())).thenReturn(1L);

        mockMvc.perform(post("/user/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userAccount\":\"testuser\",\"userRole\":\"user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("账号已存在"));
    }

    // ==================== 分页上限 ====================

    @Test
    @DisplayName("用户列表：pageSize 超过 100 返回 40000")
    void listUsers_rejectsTooLargePageSize() throws Exception {
        mockMvc.perform(post("/user/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":1,\"pageSize\":100000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("每页数量不能超过 100"));
    }

    @Test
    @DisplayName("图片列表：pageSize 超过 100 返回 40000")
    void listPictures_rejectsTooLargePageSize() throws Exception {
        mockMvc.perform(post("/picture/list/page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"current\":1,\"pageSize\":100000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(jsonPath("$.message").value("每页数量不能超过 100"));
    }

    private void stubOwnerCanEdit() {
        User user = new User();
        user.setId(100L);
        user.setUserRole("user");
        when(userService.getLoginUser(any())).thenReturn(user);
        Picture picture = new Picture();
        picture.setId(1L);
        picture.setUserId(100L);
        when(pictureService.getById(1L)).thenReturn(picture);
        when(pictureService.updateById(any(Picture.class))).thenReturn(true);
    }

    private String editBody(String name, String category, String introduction, List<String> tags) {
        StringBuilder tagJson = new StringBuilder("[");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) {
                tagJson.append(',');
            }
            tagJson.append('"').append(tags.get(i)).append('"');
        }
        tagJson.append(']');
        return String.format("{\"id\":\"1\",\"name\":\"%s\",\"category\":\"%s\",\"introduction\":\"%s\",\"tags\":%s}",
                name, category, introduction, tagJson);
    }
}
