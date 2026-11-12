package com.tzy.picturebackend.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 图形验证码：image 是可直接放进 img src 的 data URL
 */
@Data
public class CaptchaVO implements Serializable {

    /**
     * 验证码 id，注册时随验证码一起提交
     */
    private String captchaId;

    /**
     * 验证码图片（data:image/png;base64,...）
     */
    private String image;

    /**
     * 有效期（秒），前端可据此提示用户
     */
    private long expiresInSeconds;

    private static final long serialVersionUID = 1L;
}
