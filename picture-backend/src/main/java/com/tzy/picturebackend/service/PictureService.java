package com.tzy.picturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.picture.PictureReviewRequest;
import com.tzy.picturebackend.model.dto.picture.PictureUploadRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.baomidou.mybatisplus.spring.service.IService;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.PictureVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.multipart.MultipartFile;

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

    PictureVO getPictureVO(Picture picture, HttpServletRequest request);

    Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request);

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

}
