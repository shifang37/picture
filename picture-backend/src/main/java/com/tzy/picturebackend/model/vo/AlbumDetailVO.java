package com.tzy.picturebackend.model.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 专辑详情：在列表字段之上补充「当前访问者是否可以管理」。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AlbumDetailVO extends AlbumVO {

    /**
     * 当前访问者是否可以管理该专辑（作者本人或管理员）
     */
    private Boolean canManage;

    private static final long serialVersionUID = 1L;
}
