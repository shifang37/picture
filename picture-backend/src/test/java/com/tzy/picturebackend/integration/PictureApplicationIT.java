package com.tzy.picturebackend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tzy.picturebackend.event.PictureEventPublisher;
import com.tzy.picturebackend.manager.FileManager;
import com.tzy.picturebackend.manager.RedisRateLimitCounter;
import com.tzy.picturebackend.mapper.AnalyzeMapper;
import com.tzy.picturebackend.model.dto.file.UploadPictureResult;
import com.tzy.picturebackend.model.dto.picture.PictureUploadRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.vo.AnalyzeCategoryVO;
import com.tzy.picturebackend.model.vo.AnalyzeOverviewVO;
import com.tzy.picturebackend.model.vo.AnalyzeReviewVO;
import com.tzy.picturebackend.model.vo.AnalyzeTagVO;
import com.tzy.picturebackend.model.vo.AnalyzeTopDownloadVO;
import com.tzy.picturebackend.model.vo.AnalyzeUploadTrendVO;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 MySQL + Redis 的集成测试。
 * <p>
 * 覆盖单元测试覆盖不到的部分：应用上下文装配、Flyway 迁移、{@code UpdateWrapper.set(null)} 的落库行为、
 * 逻辑删除与唯一索引、Spring Session 登录态、Redis Lua 限流与过滤器顺序。
 * 本机没有 Docker 时由 {@link Testcontainers#disabledWithoutDocker()} 自动跳过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PictureApplicationIT {

    private static final String PASSWORD = "Password123";

    private static final String NEW_PASSWORD = "NewPassword456";

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("picture")
            .withUsername("picture")
            .withPassword("picture");

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private PictureService pictureService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisRateLimitCounter redisRateLimitCounter;

    @Autowired
    private AnalyzeMapper analyzeMapper;

    /**
     * 上传集成测试不访问 COS，只用 mock 返回结果，重点验证数据库更新行为
     */
    @MockitoBean
    private FileManager fileManager;

    /**
     * 集成测试不启动 RabbitMQ，事件发布直接短路
     */
    @MockitoBean
    private PictureEventPublisher pictureEventPublisher;

    @BeforeEach
    void setUp() {
        when(fileManager.uploadPicture(any(), anyString())).thenReturn(uploadResult());
    }

    // ==================== 上下文与迁移 ====================

    @Test
    @DisplayName("上下文：应用能启动，Flyway 在空库上执行全部迁移")
    void contextLoadsAndFlywayMigrates() {
        Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = 1", Integer.class);
        assertNotNull(applied);
        assertTrue(applied >= 6, "V1-V6 迁移应全部执行，实际 " + applied);

        List<String> columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = database() and table_name = 'picture'",
                String.class);
        assertTrue(columns.stream().anyMatch("reviewStatus"::equalsIgnoreCase));
        assertTrue(columns.stream().anyMatch("machineReviewStatus"::equalsIgnoreCase));

        Integer aiSuggestionTable = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = database() and table_name = 'picture_ai_suggestion'",
                Integer.class);
        assertEquals(1, aiSuggestionTable, "V6 应创建 picture_ai_suggestion 表");
    }

    // ==================== 重传与删除 ====================

    @Test
    @DisplayName("重传：UpdateWrapper.set(null) 确实把审核与机审字段置空")
    void reuploadClearsReviewFields() {
        User user = createUser("reupload");
        Picture old = savePicture(user.getId(), "old.jpg");
        old.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        old.setReviewMessage("审核通过");
        old.setReviewerId(1L);
        old.setReviewTime(new Date());
        old.setMachineReviewStatus(0);
        old.setMachineReviewLabel("normal");
        old.setMachineReviewScore(99);
        old.setMachineReviewTime(new Date());
        pictureService.updateById(old);

        PictureUploadRequest request = new PictureUploadRequest();
        request.setId(old.getId());
        pictureService.uploadPicture(jpegFile(), request, user);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select reviewStatus as review_status, reviewMessage as review_message, "
                        + "reviewerId as reviewer_id, reviewTime as review_time, "
                        + "machineReviewStatus as machine_review_status, "
                        + "machineReviewLabel as machine_review_label, "
                        + "machineReviewScore as machine_review_score, "
                        + "machineReviewTime as machine_review_time "
                        + "from picture where id = ?", old.getId());
        assertEquals(PictureReviewStatusEnum.REVIEWING.getValue(),
                ((Number) row.get("review_status")).intValue());
        assertNull(row.get("review_message"));
        assertNull(row.get("reviewer_id"));
        assertNull(row.get("review_time"));
        assertNull(row.get("machine_review_status"));
        assertNull(row.get("machine_review_label"));
        assertNull(row.get("machine_review_score"));
        assertNull(row.get("machine_review_time"));
    }

    @Test
    @DisplayName("逻辑删除：查询看不到，数据库行仍然保留且 isDelete=1")
    void logicalDeleteKeepsRowInDatabase() {
        User user = createUser("delete");
        Picture picture = savePicture(user.getId(), "delete.jpg");

        assertTrue(pictureService.removeById(picture.getId()));
        assertNull(pictureService.getById(picture.getId()));

        Integer isDelete = jdbcTemplate.queryForObject(
                "select isDelete from picture where id = ?", Integer.class, picture.getId());
        assertEquals(1, isDelete);
    }

    @Test
    @DisplayName("唯一索引：绕过应用层重复校验直接插入同账号会被数据库拒绝")
    void duplicateUserAccountHitsUniqueIndex() {
        User user = createUser("unique");

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "insert into user (userAccount, userPassword, userName, userRole) values (?, ?, ?, ?)",
                user.getUserAccount(), "hash", "duplicate", "user"));
    }

    // ==================== 会话与密码 ====================

    @Test
    @DisplayName("会话：改密后旧会话失效，当前会话保持登录")
    void changePasswordInvalidatesOldSession() throws Exception {
        User user = createUser("session");
        String csrf = csrfToken();

        Cookie sessionA = login(user.getUserAccount(), PASSWORD, csrf);
        Cookie sessionB = login(user.getUserAccount(), PASSWORD, csrf);

        mockMvc.perform(get("/user/get/login").cookie(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/user/update/password")
                        .cookie(sessionB, new Cookie("XSRF-TOKEN", csrf))
                        .header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldPassword", PASSWORD, "newPassword", NEW_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 旧会话被版本号判定为失效，当前会话由 Controller 刷新版本后仍可用
        mockMvc.perform(get("/user/get/login").cookie(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40100));
        mockMvc.perform(get("/user/get/login").cookie(sessionB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("注册：验证码一次性校验，新账号首次登录必须改密后才能访问业务接口")
    void registerRequiresCaptchaAndForcesPasswordChange() throws Exception {
        String csrf = csrfToken();
        String account = "it_force_" + UUID.randomUUID().toString().substring(0, 8);

        // 1. 获取图形验证码，答案只存在 Redis 中，测试直接读取以完成注册
        MvcResult captchaResult = mockMvc.perform(get("/user/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode captcha = objectMapper.readTree(captchaResult.getResponse().getContentAsString())
                .path("data");
        String captchaId = captcha.path("captchaId").asText();
        String captchaCode = redisTemplate.opsForValue().get("picture:captcha:" + captchaId);
        assertNotNull(captchaCode, "验证码答案应写入 Redis");

        // 2. 带验证码注册成功
        mockMvc.perform(post("/user/register")
                        .cookie(new Cookie("XSRF-TOKEN", csrf))
                        .header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userAccount", account,
                                "userPassword", PASSWORD,
                                "checkPassword", PASSWORD,
                                "captchaId", captchaId,
                                "captchaCode", captchaCode))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 3. 登录返回强制改密标记
        redisTemplate.delete("picture:ratelimit:login:ip:127.0.0.1");
        MvcResult loginResult = mockMvc.perform(post("/user/login")
                        .cookie(new Cookie("XSRF-TOKEN", csrf))
                        .header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("userAccount", account, "userPassword", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.passwordChangeRequired").value(true))
                .andReturn();
        Cookie session = loginResult.getResponse().getCookie("SESSION");
        assertNotNull(session, "登录应返回 SESSION cookie");
        Cookie xsrf = new Cookie("XSRF-TOKEN", csrf);

        // 4. 未改密时业务接口被拦截
        mockMvc.perform(post("/picture/list/page/vo")
                        .cookie(session, xsrf)
                        .header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40301));

        // 5. 修改密码后解除限制
        mockMvc.perform(post("/user/update/password")
                        .cookie(session, xsrf)
                        .header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldPassword", PASSWORD, "newPassword", NEW_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(post("/picture/list/page/vo")
                        .cookie(session, xsrf)
                        .header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/user/get/login").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.passwordChangeRequired").value(false));
    }

    // ==================== Redis 限流 ====================

    @Test
    @DisplayName("Lua 限流：固定窗口计数并设置过期时间，窗口结束后重新计数")
    void rateLimitLuaCountsAndExpires() throws Exception {
        String key = "it:lua:" + UUID.randomUUID();

        assertEquals(1L, redisRateLimitCounter.increment(key, Duration.ofSeconds(1)));
        assertEquals(2L, redisRateLimitCounter.increment(key, Duration.ofSeconds(1)));

        Long ttlMillis = redisTemplate.getExpire("picture:ratelimit:" + key, TimeUnit.MILLISECONDS);
        assertNotNull(ttlMillis);
        assertTrue(ttlMillis > 0, "限流 key 应设置过期时间，实际 " + ttlMillis);

        Thread.sleep(1200);
        assertEquals(1L, redisRateLimitCounter.increment(key, Duration.ofSeconds(1)),
                "窗口过期后应从 1 重新计数");
    }

    @Test
    @DisplayName("过滤器顺序：上传限流读到 Session 用户 id，而不是退化为按 IP")
    void uploadRateLimitUsesSessionUserId() throws Exception {
        User user = createUser("ratelimit");
        String csrf = csrfToken();
        Cookie session = login(user.getUserAccount(), PASSWORD, csrf);

        String userKey = "picture:ratelimit:upload:user:" + user.getId();
        String ipKey = "picture:ratelimit:upload:ip:127.0.0.1";
        redisTemplate.delete(userKey);
        redisTemplate.delete(ipKey);

        mockMvc.perform(multipart("/picture/upload")
                        .cookie(session, new Cookie("XSRF-TOKEN", csrf))
                        .header("X-XSRF-TOKEN", csrf))
                .andReturn();

        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(userKey)),
                "应写入按用户 id 的限流 key：" + userKey);
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(ipKey)),
                "不应退化为按 IP 的限流 key：" + ipKey);
    }

    // ==================== 管理端数据看板 ====================

    @Test
    @DisplayName("数据看板：六个统计查询在真实 MySQL 上返回正确聚合结果")
    void analyzeAggregatesFromRealDatabase() {
        User user = createUser("analyze");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String category = "IT分类-" + suffix;
        String tag = "IT标签-" + suffix;
        Picture picture = savePicture(user.getId(), "analyze-" + suffix + ".jpg");
        jdbcTemplate.update(
                "update picture set category = ?, tags = ?, picSize = ?, downloadCount = ?, "
                        + "reviewStatus = ?, reviewTime = DATE_ADD(createTime, INTERVAL 90 SECOND) "
                        + "where id = ?",
                category, "[\"" + tag + "\"]", 4096L, 11L,
                PictureReviewStatusEnum.PASS.getValue(), picture.getId());

        AnalyzeOverviewVO overview = analyzeMapper.selectOverview();
        assertTrue(overview.getPictureCount() >= 1);
        assertTrue(overview.getUserCount() >= 1);
        assertTrue(overview.getTotalPicSize() >= 4096);
        assertTrue(overview.getTotalDownloadCount() >= 11);
        assertNotNull(overview.getPendingReviewCount());

        List<AnalyzeCategoryVO> categories = analyzeMapper.selectCategoryStats();
        AnalyzeCategoryVO categoryStat = categories.stream()
                .filter(item -> category.equals(item.getCategory()))
                .findFirst()
                .orElse(null);
        assertNotNull(categoryStat, "分类统计应包含 " + category);
        assertTrue(categoryStat.getCount() >= 1);
        assertTrue(categoryStat.getTotalSize() >= 4096);

        List<AnalyzeTagVO> tags = analyzeMapper.selectTopTags(50);
        AnalyzeTagVO tagStat = tags.stream()
                .filter(item -> tag.equals(item.getTag()))
                .findFirst()
                .orElse(null);
        assertNotNull(tagStat, "标签统计应包含 " + tag);
        assertTrue(tagStat.getCount() >= 1);

        List<AnalyzeUploadTrendVO> trend = analyzeMapper.selectUploadTrend(6);
        assertEquals(7, trend.size(), "最近 7 天应返回 7 个日期点");
        long trendTotal = trend.stream()
                .mapToLong(item -> item.getCount() == null ? 0 : item.getCount())
                .sum();
        assertTrue(trendTotal >= 1, "最近 7 天应统计到刚插入的图片");

        AnalyzeReviewVO review = analyzeMapper.selectReviewStats();
        assertTrue(review.getApprovedCount() >= 1);
        assertNotNull(review.getAverageReviewSeconds());
        assertTrue(review.getAverageReviewSeconds() >= 0);

        List<AnalyzeTopDownloadVO> topDownloads = analyzeMapper.selectTopDownload(50);
        AnalyzeTopDownloadVO top = topDownloads.stream()
                .filter(item -> picture.getId().equals(item.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(top, "下载排行应包含刚插入的图片");
        assertEquals(11L, top.getDownloadCount());
    }

    @Test
    @DisplayName("数据看板：普通用户无权限，管理员六个接口可用，非法参数返回参数错误")
    void analyzeEndpointsRequireAdmin() throws Exception {
        String csrf = csrfToken();
        User admin = createUser("analyze-admin");
        jdbcTemplate.update("update user set userRole = 'admin' where id = ?", admin.getId());
        Cookie adminSession = login(admin.getUserAccount(), PASSWORD, csrf);

        List<String> endpoints = List.of(
                "/analyze/overview",
                "/analyze/upload_trend?days=7",
                "/analyze/category",
                "/analyze/tags?limit=5",
                "/analyze/review",
                "/analyze/top_download?limit=5");
        for (String endpoint : endpoints) {
            mockMvc.perform(get(endpoint).cookie(adminSession))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }

        mockMvc.perform(get("/analyze/upload_trend").param("days", "0").cookie(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40000));

        User normal = createUser("analyze-normal");
        Cookie normalSession = login(normal.getUserAccount(), PASSWORD, csrf);
        mockMvc.perform(get("/analyze/overview").cookie(normalSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40101));
    }

    // ==================== 辅助方法 ====================

    private User createUser(String suffix) {
        String account = "it_" + suffix + "_" + UUID.randomUUID().toString().substring(0, 8);
        long id = userService.userRegister(account, PASSWORD, PASSWORD);
        // 业务测试的账号直接清除首次改密标记，聚焦被测流程本身；强制改密由上面的注册用例覆盖
        jdbcTemplate.update("update user set passwordChangeRequired = 0 where id = ?", id);
        User user = userService.getById(id);
        assertNotNull(user);
        return user;
    }

    private Picture savePicture(Long userId, String filename) {
        Picture picture = new Picture();
        picture.setUrl("https://test.example.com/" + filename);
        picture.setName(filename);
        picture.setUserId(userId);
        picture.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());
        assertTrue(pictureService.save(picture));
        assertNotNull(picture.getId());
        return picture;
    }

    private UploadPictureResult uploadResult() {
        UploadPictureResult result = new UploadPictureResult();
        result.setUrl("https://test.example.com/" + UUID.randomUUID() + ".jpg");
        result.setPicName("new");
        result.setPicSize(1024L);
        result.setPicWidth(800);
        result.setPicHeight(600);
        result.setPicScale(1.33);
        result.setPicFormat("jpg");
        return result;
    }

    private MockMultipartFile jpegFile() {
        return new MockMultipartFile("file", "new.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    private String csrfToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie token = result.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(token, "安全方法应补发 XSRF-TOKEN cookie");
        return token.getValue();
    }

    private Cookie login(String account, String password, String csrf) throws Exception {
        // 同一个 IP 的登录限流计数会在测试之间累积，登录前清理避免测试顺序造成假失败
        redisTemplate.delete("picture:ratelimit:login:ip:127.0.0.1");
        MvcResult result = mockMvc.perform(post("/user/login")
                        .cookie(new Cookie("XSRF-TOKEN", csrf))
                        .header("X-XSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("userAccount", account, "userPassword", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        Cookie session = result.getResponse().getCookie("SESSION");
        assertNotNull(session, "登录应返回 SESSION cookie");
        return session;
    }
}
