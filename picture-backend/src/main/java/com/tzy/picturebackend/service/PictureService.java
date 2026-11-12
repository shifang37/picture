package com.tzy.picturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.model.dto.picture.PictureEditRequest;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.picture.PictureReviewBatchRequest;
import com.tzy.picturebackend.model.dto.picture.PictureReviewRequest;
import com.tzy.picturebackend.model.dto.picture.PictureUploadRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.baomidou.mybatisplus.spring.service.IService;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.PictureDetailVO;
import com.tzy.picturebackend.model.vo.PictureReviewBatchResult;
import com.tzy.picturebackend.model.vo.PictureReviewLogVO;
import com.tzy.picturebackend.model.vo.PictureTagCategory;
import com.tzy.picturebackend.model.vo.PictureVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.multipart.MultipartFile;

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
     * 删除图片：先做数据库逻辑删除，成功后 best-effort 清理 COS 对象
     *
     * @param id        图片 id
     * @param loginUser 当前登录用户
     * @return 是否删除成功
     */
    boolean deletePicture(Long id, User loginUser);

    /**
     * 清理图片文件（best-effort，失败只记录日志，不抛异常）
     *
     * @param url 图片访问地址
     */
    void deletePictureFile(String url);

    QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest);

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

}
