package com.tzy.picturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.model.dto.picture.PictureBatchDeleteRequest;
import com.tzy.picturebackend.model.dto.picture.PictureBatchEditRequest;
import com.tzy.picturebackend.model.dto.picture.PictureEditRequest;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.picture.PictureReviewBatchRequest;
import com.tzy.picturebackend.model.dto.picture.PictureReviewRequest;
import com.tzy.picturebackend.model.dto.picture.PictureUpdateRequest;
import com.tzy.picturebackend.model.dto.picture.PictureUploadRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.baomidou.mybatisplus.spring.service.IService;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.PictureBatchResult;
import com.tzy.picturebackend.model.vo.PictureDetailVO;
import com.tzy.picturebackend.model.vo.PictureReviewBatchResult;
import com.tzy.picturebackend.model.vo.PictureReviewLogVO;
import com.tzy.picturebackend.model.vo.PictureTagCategory;
import com.tzy.picturebackend.model.vo.PictureVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.multipart.MultipartFile;

import java.util.Date;
import java.util.List;

/**
* @author shifang37
* @description 针对表【picture(图片)】的数据库操作Service
* @createDate 2026-03-11 16:30:50
*/
public interface PictureService extends IService<Picture> {

    /**
     * 上传图片
     *
     * @param multipartFile
     * @param pictureUploadRequest
     * @param loginUser
     * @return
     */
    PictureVO uploadPicture(MultipartFile multipartFile,
                            PictureUploadRequest pictureUploadRequest,
                            User loginUser);

    /**
     * 编辑图片信息（名称、简介、分类、标签）
     * <p>
     * 普通用户修改已通过或已拒绝图片的内容后，图片会重新进入待审核并清空上一次的审核信息；
     * 内容没有变化时不动审核状态。管理员编辑不改变审核状态。
     *
     * @param pictureEditRequest 编辑请求
     * @param loginUser          当前登录用户
     * @return 是否编辑成功
     */
    boolean editPicture(PictureEditRequest pictureEditRequest, User loginUser);

    /**
     * 删除图片：移入回收站（逻辑删除），同时级联清理 AI 建议与标签关系。
     * <p>
     * COS 对象不会立即删除，保留期内的回收站恢复需要原文件；审核历史按合规要求保留，
     * 不随图片删除。对象与数据库行由 {@link #purgeExpiredPictures(Date, int)} 在到期后清理。
     *
     * @param id        图片 id
     * @param loginUser 当前登录用户
     * @return 是否删除成功
     */
    boolean deletePicture(Long id, User loginUser);

    /**
     * 分页查询回收站中的图片（仅管理员使用）
     *
     * @param page    分页参数
     * @param request 查询条件；目前支持 id、名称、简介、分类、用户、审核状态与搜索词
     * @return 已删除图片分页，按删除时间倒序
     */
    Page<Picture> pageRecyclePictures(Page<Picture> page, PictureQueryRequest request);

    /**
     * 从回收站恢复图片
     * <p>
     * 恢复图片记录，并按 {@code picture.tags} JSON 重建标签关系；恢复后发布 changed 事件，
     * 让搜索索引按当前审核状态重新同步。
     *
     * @param id 图片 id
     * @return 是否恢复成功
     */
    boolean restorePicture(Long id);

    /**
     * 物理删除超过保留期的回收站图片。
     * <p>
     * 先删除 COS 对象，成功后再删除数据库行；COS 删除失败时保留该行等下一轮重试，
     * 避免对象留在桶里变成孤儿文件。审核日志不删除。
     *
     * @param before    删除时间早于该时刻的图片会被清理
     * @param batchSize 单批最多处理条数
     * @return 实际物理删除的图片数量
     */
    int purgeExpiredPictures(Date before, int batchSize);

    /**
     * 清理图片文件（best-effort，失败只记录日志，不抛异常）
     *
     * @param url 图片访问地址
     */
    void deletePictureFile(String url);

    /**
     * 构造不含标签条件的查询条件。
     * <p>
     * 标签需要 JOIN {@code picture_tag}，不能放进 QueryWrapper；列表查询请统一使用
     * {@link #pagePictures(Page, PictureQueryRequest)}。
     */
    QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest);

    /**
     * 按查询条件分页查询图片。
     * <p>
     * 带标签时使用 {@code picture JOIN picture_tag}，多个标签为「同时包含」语义；
     * 不带标签时沿用原有 QueryWrapper 查询。
     *
     * @param page    分页参数
     * @param request 查询条件
     * @return 图片分页
     */
    Page<Picture> pagePictures(Page<Picture> page, PictureQueryRequest request);

    /**
     * 管理员直接更新图片信息（名称、简介、分类、标签）
     * <p>
     * 与普通用户编辑共用标签关系表的同步逻辑，保证 {@code picture.tags} 与
     * {@code picture_tag} 不会出现不一致。
     *
     * @param pictureUpdateRequest 更新请求
     * @return 是否更新成功
     */
    boolean updatePicture(PictureUpdateRequest pictureUpdateRequest);

    /**
     * 图片详情封装：作者本人或管理员会额外得到审核意见、审核人等内部字段
     *
     * @param picture 图片实体
     * @param request 当前请求
     * @return 图片详情；无权限查看审核信息时退化为公开 {@link PictureVO}
     */
    PictureVO getPictureVO(Picture picture, HttpServletRequest request);

    /**
     * 公开图片列表封装：只包含公开字段，不会返回审核意见和审核人
     *
     * @param picturePage 图片分页
     * @param request     当前请求（保留参数以兼容现有调用）
     * @return 公开图片分页
     */
    Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request);

    /**
     * 作者本人图片列表封装：额外返回审核意见、审核人等内部字段
     * <p>
     * 只能用于已经确认记录属于当前用户的场景（如「我的图片」）。
     *
     * @param picturePage 图片分页
     * @return 含审核详情的图片分页
     */
    Page<PictureDetailVO> getPictureDetailVOPage(Page<Picture> picturePage);

    void validPicture(Picture picture);

    /**
     * 校验图片对当前请求者是否可见：已通过审核的图片所有人可见，
     * 未通过审核的仅管理员和作者本人可见
     *
     * @param picture
     * @param request
     */
    void checkPictureVisible(Picture picture, HttpServletRequest request);

    /**
     * 图片审核
     *
     * @param pictureReviewRequest
     * @param loginUser
     */
    void doPictureReview(PictureReviewRequest pictureReviewRequest, User loginUser);

    /**
     * 查询某张图片的审核历史
     *
     * @param pictureId 图片 id
     * @return 审核记录（含审核人信息），按时间倒序
     */
    List<PictureReviewLogVO> listPictureReviewLogs(Long pictureId);

    /**
     * 从标签/分类字典读取可选项（由管理员维护）
     *
     * @return 标签与分类列表
     */
    PictureTagCategory listTagCategory();

    /**
     * 读取可下载的图片，并校验图片对当前访问者可见
     * <p>
     * 未通过审核的图片只有作者本人和管理员可以下载。
     *
     * @param id      图片 id
     * @param request 当前请求
     * @return 图片实体
     */
    Picture getDownloadablePicture(Long id, HttpServletRequest request);

    /**
     * 下载成功且通过去重后，原子自增下载次数
     *
     * @param id 图片 id
     * @return 是否更新成功（图片在下载过程中被删除时返回 false）
     */
    boolean incrementDownloadCount(Long id);

    /**
     * 批量审核，单张失败不影响其他图片
     *
     * @param request   批量审核请求
     * @param loginUser 当前管理员
     * @return 成功/失败统计
     */
    PictureReviewBatchResult batchReview(PictureReviewBatchRequest request, User loginUser);

    /**
     * 批量编辑图片的分类、标签、简介。
     * <p>
     * <b>整批原子</b>：只要有一张图片不存在或不属于当前用户（管理员例外），整批都不修改，
     * 避免出现「改了一半」的中间状态。只更新请求中非空的字段；普通用户改动已审核图片的
     * 内容后，图片会重新进入待审核，未发生实际变化的图片不会重置审核状态。
     *
     * @param request   批量编辑请求
     * @param loginUser 当前登录用户
     * @return 实际更新数量（内容没有变化时为 0）
     */
    PictureBatchResult batchEdit(PictureBatchEditRequest request, User loginUser);

    /**
     * 批量删除图片（移入回收站）。
     * <p>
     * 与批量审核一致采用「单张失败不影响其他图片」的语义：不存在的图片、他人的图片会计入
     * 失败明细，其余图片正常删除；整批只做一次缓存失效与一次批量事件。
     *
     * @param request   批量删除请求
     * @param loginUser 当前登录用户
     * @return 成功/失败统计
     */
    PictureBatchResult batchDelete(PictureBatchDeleteRequest request, User loginUser);

}
