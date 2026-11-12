package com.tzy.picturebackend.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 搜索建议中的图片名称条目。
 * <p>
 * 只返回公开可见（已过审、未删除）的图片，点击建议后可以直接跳到详情页；
 * 名称相同但 id 不同的图片会各自成条，由前端自行去重展示。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchNameSuggestionVO implements Serializable {

    /**
     * 图片 id
     */
    private Long id;

    /**
     * 图片名称
     */
    private String name;

    private static final long serialVersionUID = 1L;
}
