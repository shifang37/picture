package com.tzy.picturebackend.model.vo;

import com.tzy.picturebackend.model.entity.Picture;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.beans.BeanUtils;

import java.util.Date;

/**
 * 图片详情（含审核内部信息）。
 * <p>
 * 审核意见、审核人和审核时间只允许作者本人和管理员看到，
 * 因此不放在公开的 {@link PictureVO} 中，避免公开列表/匿名详情接口泄露。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PictureDetailVO extends PictureVO {

    /**
     * 审核意见
     */
    private String reviewMessage;

    /**
     * 审核人 id
     */
    private Long reviewerId;

    /**
     * 审核时间
     */
    private Date reviewTime;

    private static final long serialVersionUID = 1L;

    /**
     * 对象转详情封装类
     */
    public static PictureDetailVO objToDetailVo(Picture picture) {
        PictureVO pictureVO = PictureVO.objToVo(picture);
        if (pictureVO == null) {
            return null;
        }
        PictureDetailVO pictureDetailVO = new PictureDetailVO();
        BeanUtils.copyProperties(pictureVO, pictureDetailVO);
        pictureDetailVO.setReviewMessage(picture.getReviewMessage());
        pictureDetailVO.setReviewerId(picture.getReviewerId());
        pictureDetailVO.setReviewTime(picture.getReviewTime());
        return pictureDetailVO;
    }
}
