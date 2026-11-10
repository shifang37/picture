package com.tzy.picturebackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tzy.picturebackend.model.dto.picture.PictureQueryRequest;
import com.tzy.picturebackend.model.dto.picture.PictureReviewRequest;
import com.tzy.picturebackend.model.dto.picture.PictureUploadRequest;
import com.tzy.picturebackend.model.entity.Picture;
import com.baomidou.mybatisplus.extension.service.IService;
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


    QueryWrapper<Picture> getQueryWrapper(PictureQueryRequest pictureQueryRequest);

    PictureVO getPictureVO(Picture picture, HttpServletRequest request);

    Page<PictureVO> getPictureVOPage(Page<Picture> picturePage, HttpServletRequest request);

    void validPicture(Picture picture);

    /**
     * 图片审核
     *
     * @param pictureReviewRequest
     * @param loginUser
     */
    void doPictureReview(PictureReviewRequest pictureReviewRequest, User loginUser);

}
