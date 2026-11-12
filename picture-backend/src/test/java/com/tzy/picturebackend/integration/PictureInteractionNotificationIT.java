package com.tzy.picturebackend.integration;

import com.tzy.picturebackend.manager.FileManager;
import com.tzy.picturebackend.mapper.PictureMapper;
import com.tzy.picturebackend.model.dto.album.AlbumAddRequest;
import com.tzy.picturebackend.model.dto.album.AlbumPictureRequest;
import com.tzy.picturebackend.model.dto.interaction.InteractionRequest;
import com.tzy.picturebackend.model.dto.notification.NotificationMessage;
import com.tzy.picturebackend.model.entity.Picture;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.enums.NotificationTypeEnum;
import com.tzy.picturebackend.model.enums.PictureReviewStatusEnum;
import com.tzy.picturebackend.model.vo.AlbumVO;
import com.tzy.picturebackend.model.vo.InteractionStatusVO;
import com.tzy.picturebackend.model.vo.NotificationVO;
import com.tzy.picturebackend.model.vo.PublicUserProfileVO;
import com.tzy.picturebackend.model.vo.UserQuotaVO;
import com.tzy.picturebackend.service.AlbumService;
import com.tzy.picturebackend.service.NotificationService;
import com.tzy.picturebackend.service.PictureInteractionService;
import com.tzy.picturebackend.service.PictureService;
import com.tzy.picturebackend.service.UserQuotaService;
import com.tzy.picturebackend.service.UserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * M1 集成测试：真实 MySQL + Redis 下验证 Flyway 迁移、唯一键幂等、并发计数与通知去重。
 * <p>
 * 这些行为单元测试（Mock Mapper）验证不了：唯一键是否真的生效、原子 UPDATE 是否真的加 1、
 * {@code INSERT IGNORE} 在并发下是否只插入一行。本机没有 Docker 时自动跳过。
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PictureInteractionNotificationIT {

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
    private PictureInteractionService pictureInteractionService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private PictureService pictureService;

    @Autowired
    private UserService userService;

    @Autowired
    private AlbumService albumService;

    @Autowired
    private UserQuotaService userQuotaService;

    @Autowired
    private PictureMapper pictureMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 集成测试不访问 COS：物理清理的删除动作直接返回成功
     */
    @MockitoBean
    private FileManager fileManager;

    private User owner;

    private Picture picture;

    @BeforeEach
    void setUp() {
        when(fileManager.deletePictureForPurge(anyString())).thenReturn(true);
        owner = createUser();
        picture = createPicture(owner);
    }

    @Test
    @DisplayName("迁移：V10 计数列与互动表、V11 通知表都已创建")
    void migrationsApplied() {
        Integer pictureColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'picture'
                  AND column_name IN ('favoriteCount', 'likeCount')
                """, Integer.class);
        assertEquals(2, pictureColumns);

        Integer tables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name IN ('picture_interaction', 'notification')
                """, Integer.class);
        assertEquals(2, tables);

        // M2 / F6：专辑表
        Integer albumTables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name IN ('album', 'album_picture')
                """, Integer.class);
        assertEquals(2, albumTables);

        // M4 / F10：用户配额列与图片哈希列
        Integer quotaColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'user'
                  AND column_name IN ('storageQuota', 'storageUsed', 'pictureQuota', 'pictureUsed')
                """, Integer.class);
        assertEquals(4, quotaColumns);
        Integer hashColumn = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'picture' AND column_name = 'fileHash'
                """, Integer.class);
        assertEquals(1, hashColumn);
        Integer hashIndex = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'picture' AND index_name = 'idx_user_hash'
                """, Integer.class);
        assertTrue(hashIndex > 0);
    }

    @Test
    @DisplayName("配额：V13 的列默认值生效，新用户拿到 1GB / 1000 张")
    void quotaDefaultsAppliedByMigration() {
        UserQuotaVO quota = userQuotaService.getQuota(owner.getId());

        assertEquals(Long.valueOf(1073741824L), quota.getStorageQuota());
        assertEquals(Integer.valueOf(1000), quota.getPictureQuota());
        assertEquals(Long.valueOf(0L), quota.getStorageUsed());
        assertEquals(Integer.valueOf(0), quota.getPictureUsed());
        assertEquals(Boolean.FALSE, quota.getExempt());
    }

    @Test
    @DisplayName("配额：10 线程同时预留，成功数不超过上限且用量不会被加超")
    void quotaReserve_isAtomicUnderConcurrency() throws Exception {
        jdbcTemplate.update("""
                UPDATE user SET storageQuota = 5, pictureQuota = 3,
                                storageUsed = 0, pictureUsed = 0
                WHERE id = ?
                """, owner.getId());
        User target = userService.getById(owner.getId());

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>(threads);
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return userQuotaService.reserve(target, 1, 1);
                }));
            }
            start.countDown();
            int success = 0;
            for (Future<Boolean> future : futures) {
                if (Boolean.TRUE.equals(future.get(30, TimeUnit.SECONDS))) {
                    success++;
                }
            }
            assertEquals(3, success, "只有 3 次预留能成功");
        } finally {
            pool.shutdownNow();
        }

        User after = userService.getById(owner.getId());
        assertEquals(3, after.getPictureUsed());
        assertEquals(3L, after.getStorageUsed());
    }

    @Test
    @DisplayName("配额：软删除释放额度，恢复重新占用；额度不足时拒绝恢复")
    void quotaReleasedOnDeleteAndRecheckedOnRestore() {
        userQuotaService.reserve(userService.getById(owner.getId()), 1024L, 1);
        assertEquals(1024L, userService.getById(owner.getId()).getStorageUsed());

        pictureService.deletePicture(picture.getId(), owner);
        User afterDelete = userService.getById(owner.getId());
        assertEquals(0L, afterDelete.getStorageUsed(), "软删除必须释放额度");
        assertEquals(0, afterDelete.getPictureUsed());

        // 配额被调小后不允许恢复，避免恢复出超额数据
        jdbcTemplate.update("UPDATE user SET storageQuota = 512 WHERE id = ?", owner.getId());
        assertThrows(com.tzy.picturebackend.exception.BusinessException.class,
                () -> pictureService.restorePicture(picture.getId()));

        jdbcTemplate.update("UPDATE user SET storageQuota = 8192 WHERE id = ?", owner.getId());
        assertTrue(pictureService.restorePicture(picture.getId()));
        User afterRestore = userService.getById(owner.getId());
        assertEquals(1024L, afterRestore.getStorageUsed());
        assertEquals(1, afterRestore.getPictureUsed());
    }

    @Test
    @DisplayName("秒传：同用户同哈希命中，排除自身与已删除图片")
    void dedupe_matchesSameUserHash() {
        String hash = "b".repeat(64);
        Picture existing = createPicture(owner);
        existing.setFileHash(hash);
        assertTrue(pictureService.updateById(existing));

        Picture found = pictureMapper.selectByUserAndHash(owner.getId(), hash, null);
        assertNotNull(found);
        assertEquals(existing.getId(), found.getId());
        assertNull(pictureMapper.selectByUserAndHash(owner.getId(), hash, existing.getId()),
                "重传时必须排除自身");
        assertNull(pictureMapper.selectByUserAndHash(owner.getId(), "c".repeat(64), null),
                "不同哈希不应命中");

        pictureService.deletePicture(existing.getId(), owner);
        assertNull(pictureMapper.selectByUserAndHash(owner.getId(), hash, null),
                "已删除图片不参与秒传");
    }

    @Test
    @DisplayName("收藏：10 线程并发 add 同一图片，只产生 1 行关系、计数为 1")
    void add_isIdempotentUnderConcurrency() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<InteractionStatusVO>> futures = new ArrayList<>(threads);
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return pictureInteractionService.add(request("favorite"), owner);
                }));
            }
            start.countDown();
            for (Future<InteractionStatusVO> future : futures) {
                InteractionStatusVO status = future.get(30, TimeUnit.SECONDS);
                // 并发下重复请求的计数快照可能滞后，但「已收藏」这个语义必须成立
                assertTrue(status.isFavorite(), "并发结束后每个请求都应返回已收藏");
            }
        } finally {
            pool.shutdownNow();
        }

        Integer relations = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM picture_interaction
                WHERE pictureId = ? AND userId = ? AND type = 'favorite'
                """, Integer.class, picture.getId(), owner.getId());
        assertEquals(1, relations);
        assertEquals(1L, pictureService.getById(picture.getId()).getFavoriteCount());
    }

    @Test
    @DisplayName("取消收藏：删除关系行后计数回到 0，重复取消不报错也不减成负数")
    void remove_decrementsCounterWithoutGoingNegative() {
        pictureInteractionService.add(request("favorite"), owner);

        pictureInteractionService.remove(request("favorite"), owner);
        pictureInteractionService.remove(request("favorite"), owner);

        assertEquals(0L, pictureService.getById(picture.getId()).getFavoriteCount());
        Integer relations = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM picture_interaction
                WHERE pictureId = ? AND type = 'favorite'
                """, Integer.class, picture.getId());
        assertEquals(0, relations);
    }

    @Test
    @DisplayName("通知：同一 dedupeKey 重复投递只落一行，未读数与已读状态正确")
    void notification_isDeduplicatedByKey() {
        NotificationMessage message = NotificationMessage.review(
                NotificationTypeEnum.REVIEW_PASS, owner.getId(), picture.getId(), "集成测试图", null);

        notificationService.notifyBatch(List.of(message));
        notificationService.notifyBatch(List.of(message));

        Integer rows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notification WHERE userId = ? AND dedupeKey = ?
                """, Integer.class, owner.getId(), "REVIEW_PASS:" + picture.getId());
        assertEquals(1, rows);
        assertEquals(1L, notificationService.unreadCount(owner.getId()));

        Page<NotificationVO> page = notificationService.page(owner.getId(), true, 1, 10);
        assertEquals(1, page.getRecords().size());
        assertEquals("图片审核通过", page.getRecords().get(0).getTypeText());

        notificationService.markRead(owner.getId(), List.of(page.getRecords().get(0).getId()));
        assertEquals(0L, notificationService.unreadCount(owner.getId()));
    }

    @Test
    @DisplayName("通知：只能读自己的，混入他人 id 会被忽略")
    void notification_cannotReadOthers() {
        User other = createUser();
        NotificationMessage message = NotificationMessage.system(
                other.getId(), "公告", "只发给 other");
        notificationService.notifyBatch(List.of(message));

        Long otherNotificationId = jdbcTemplate.queryForObject("""
                SELECT id FROM notification WHERE userId = ? AND title = '公告'
                """, Long.class, other.getId());

        int updated = notificationService.markRead(owner.getId(), List.of(otherNotificationId));

        assertEquals(0, updated, "标记他人通知必须无效");
        assertEquals(0L, notificationService.unreadCount(owner.getId()));
        assertEquals(1L, notificationService.unreadCount(other.getId()));
        assertTrue(notificationService.page(owner.getId(), false, 1, 10).getRecords().isEmpty(),
                "他人的通知不会出现在自己的列表里");
    }

    @Test
    @DisplayName("回收站到期物理清理：级联删除互动关系")
    void purge_cascadesInteractionRows() {
        pictureInteractionService.add(request("favorite"), owner);
        pictureService.deletePicture(picture.getId(), owner);
        // 手动把删除时间调到保留期之外，避免依赖 7 天真实等待
        jdbcTemplate.update("UPDATE picture SET deletedAt = ? WHERE id = ?",
                new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)), picture.getId());

        int purged = pictureService.purgeExpiredPictures(new Date(), 100);

        assertEquals(1, purged);
        Integer relations = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM picture_interaction WHERE pictureId = ?
                """, Integer.class, picture.getId());
        assertEquals(0, relations, "物理删除图片时必须级联清理互动关系");
    }

    @Test
    @DisplayName("作者主页统计：只统计已过审未删除作品，删除后缓存失效并归零")
    void publicUserProfile_countsOnlyPublicPictures() {
        // 同一作者的待审核作品不能被统计进去
        Picture reviewing = createPicture(owner);
        reviewing.setReviewStatus(PictureReviewStatusEnum.REVIEWING.getValue());
        assertTrue(pictureService.updateById(reviewing));
        // 已过审作品产生一次收藏，统计应包含该收藏
        pictureInteractionService.add(request("favorite"), owner);

        PublicUserProfileVO profile = userService.getPublicUserProfile(owner.getId());

        assertEquals(1L, profile.getPictureCount(), "只统计已过审作品");
        assertEquals(1L, profile.getFavoriteCount());
        assertEquals("集成测试用户", profile.getUser().getUserName());

        // 删除后必须让缓存失效，否则 5 分钟 TTL 内主页还会显示旧统计
        assertTrue(pictureService.deletePicture(picture.getId(), owner));
        userService.evictPublicUserProfile(owner.getId());

        PublicUserProfileVO afterDelete = userService.getPublicUserProfile(owner.getId());
        assertEquals(0L, afterDelete.getPictureCount());
        assertEquals(0L, afterDelete.getFavoriteCount());
    }

    @Test
    @DisplayName("专辑：创建 → 加图 → 公开统计 → 移图，计数与封面保持一致")
    void albumFlow_updatesCountAndCover() {
        AlbumAddRequest addRequest = new AlbumAddRequest();
        addRequest.setName("集成测试专辑");
        addRequest.setIsPublic(true);
        AlbumVO album = albumService.add(addRequest, owner);
        assertNotNull(album.getId());

        AlbumPictureRequest pictureRequest = new AlbumPictureRequest();
        pictureRequest.setAlbumId(album.getId());
        pictureRequest.setPictureIds(List.of(picture.getId()));
        AlbumVO updated = albumService.addPictures(pictureRequest, owner);

        assertEquals(1, updated.getPictureCount());
        assertEquals(picture.getId(), updated.getCoverPictureId(), "首次加图应自动设置封面");
        assertEquals(1L, updated.getPublicCount(), "公开数量只统计已过审图片");

        AlbumVO removed = albumService.removePictures(pictureRequest, owner);
        assertEquals(0, removed.getPictureCount());
        assertNull(removed.getCoverPictureId(), "封面被移除后应回退为空");
    }

    private InteractionRequest request(String type) {
        InteractionRequest request = new InteractionRequest();
        request.setPictureId(picture.getId());
        request.setType(type);
        return request;
    }

    private User createUser() {
        User user = new User();
        user.setUserAccount("it_" + UUID.randomUUID().toString().substring(0, 8));
        user.setUserPassword("not-used-in-it");
        user.setUserName("集成测试用户");
        user.setUserRole("user");
        user.setPasswordChangeRequired(false);
        assertTrue(userService.save(user));
        return user;
    }

    private Picture createPicture(User author) {
        Picture entity = new Picture();
        entity.setUrl("https://example.invalid/it/" + UUID.randomUUID() + ".jpg");
        entity.setName("集成测试图");
        entity.setCategory("风景");
        entity.setTags("[\"测试\"]");
        entity.setPicSize(1024L);
        entity.setPicWidth(100);
        entity.setPicHeight(100);
        entity.setPicScale(1.0);
        entity.setPicFormat("jpg");
        entity.setUserId(author.getId());
        entity.setReviewStatus(PictureReviewStatusEnum.PASS.getValue());
        assertTrue(pictureService.save(entity));
        return entity;
    }
}
