package com.tzy.picturebackend.constant;

/**
 * 图片标签的统一上限。
 * <p>
 * 这些值必须同时对应：
 * <ul>
 *     <li>接口 DTO 校验（{@code PictureEditRequest} / {@code PictureUpdateRequest} / {@code PictureQueryRequest}）；</li>
 *     <li>数据库 {@code picture_tag.tag} 与标签字典 {@code tag_category.name}（type = tag）；</li>
 *     <li>AI 提示词与模型输出清洗（{@code AiSuggestionServiceImpl} / {@code DeepSeekPictureAiTagger}）。</li>
 * </ul>
 * 后端修改时只改这里（前端对应 {@code picture-frontend/src/constants/picture.ts}），避免出现
 * 「DTO 允许 32 字符 × 20 个，数据库却只能存 512 字符」这类自相矛盾的限制。
 */
public final class PictureTagConstant {

    /**
     * 单个标签的最大字符数
     */
    public static final int TAG_MAX_LENGTH = 32;

    /**
     * 单张图片最多拥有的标签数
     */
    public static final int TAG_MAX_COUNT = 20;

    private PictureTagConstant() {
    }
}
