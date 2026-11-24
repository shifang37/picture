package com.tzy.picturebackend.manager.rag;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.tzy.picturebackend.model.entity.Picture;

import java.util.ArrayList;
import java.util.List;

/**
 * 图库问答提示词构造。
 * <p>
 * 系统提示词是固定前缀，修改时必须同步递增 {@link #PROMPT_VERSION}：
 * 该版本号参与回答缓存 key，旧答案会立即失效，避免「新提示词 + 旧答案」混用。
 * <p>
 * 候选只包含公开字段（名称、分类、标签、尺寸、简介截断），绝不拼接审核意见、
 * 审核人、用户账号等内部信息。尺寸是「竖版 / 横版」类问题的唯一事实依据，
 * 必须显式提供给模型，不能让它从简介里猜。
 */
public class RagAskPromptBuilder {

    /**
     * 提示词版本：进入回答缓存 key
     */
    public static final String PROMPT_VERSION = "ask-v2";

    /**
     * 单条简介进入提示词的最大长度
     */
    static final int INTRODUCTION_MAX_CHARS = 120;

    /**
     * 模型允许引用的最大图片数（与提示词规则一致，代码另行强制）
     */
    public static final int MAX_CITED_PICTURES = 4;

    private static final String SYSTEM_PROMPT = """
            你是图库检索助手。只能依据 <候选> 中的图片回答，不得编造候选之外的名称、作者、分类。
            输出 JSON：{"answer":"...","citedPictureIds":[1,2],"refused":false}
            规则：
            1. 候选为空或与问题无关时，输出 {"answer":"没有找到相关图片","citedPictureIds":[],"refused":true}；
            2. citedPictureIds 只能取候选中的 id，最多 4 个；
            3. 回答不超过 120 个汉字，不要输出 Markdown 代码块；
            4. 涉及版权、人物身份、审核结论的问题，一律拒答；
            5. 判断竖版、横版、方图时必须使用候选中的「尺寸」，不得猜测；
            6. 回答里提到的每一张图片都必须同时出现在 citedPictureIds 中，没有引用就不要提图；
            7. 只回答与图库图片有关的问题；写诗、写代码、翻译、通用知识、天气等非图库请求一律 refused=true，
               不要提供替代内容，也不要把候选图片当作这些问题的答案。
            """;

    /**
     * 固定系统提示词
     */
    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * 问题与候选图片。候选按召回顺序排列，模型引用时按同一顺序返回更稳定。
     */
    public String userPrompt(String question, List<Picture> candidates) {
        StringBuilder builder = new StringBuilder();
        builder.append("问题：").append(safe(question, 200)).append('\n');
        builder.append("<候选>\n");
        if (candidates != null) {
            for (Picture picture : candidates) {
                if (picture == null || picture.getId() == null) {
                    continue;
                }
                builder.append(candidateLine(picture)).append('\n');
            }
        }
        builder.append("</候选>\n");
        builder.append("请只输出 JSON。");
        return builder.toString();
    }

    /**
     * 单条候选：{@code [id] 名称 | 分类：xxx | 标签：a、b | 尺寸：宽x高（方向） | 简介：...}
     */
    String candidateLine(Picture picture) {
        StringBuilder line = new StringBuilder();
        line.append('[').append(picture.getId()).append("] ");
        line.append(safe(picture.getName(), 60)).append(" | ");
        line.append("分类：").append(safe(picture.getCategory(), 40)).append(" | ");
        line.append("标签：").append(formatTags(picture.getTags())).append(" | ");
        line.append("尺寸：").append(formatSize(picture)).append(" | ");
        line.append("简介：").append(safe(picture.getIntroduction(), INTRODUCTION_MAX_CHARS));
        return line.toString();
    }

    /**
     * 标签是 JSON 数组字符串；解析失败按「无标签」处理，不影响候选可用性
     */
    private String formatTags(String tagsJson) {
        if (StrUtil.isBlank(tagsJson)) {
            return "无";
        }
        List<String> tags;
        try {
            tags = JSONUtil.toList(tagsJson, String.class);
        } catch (RuntimeException e) {
            return "无";
        }
        if (tags == null || tags.isEmpty()) {
            return "无";
        }
        List<String> cleaned = new ArrayList<>();
        for (String tag : tags) {
            String value = StrUtil.trim(tag);
            if (StrUtil.isNotBlank(value)) {
                cleaned.add(safe(value, 24));
            }
        }
        return cleaned.isEmpty() ? "无" : String.join("、", cleaned);
    }

    /**
     * 尺寸与方向：竖版 / 横版 / 方图，比例保留两位小数
     */
    private String formatSize(Picture picture) {
        Integer width = picture.getPicWidth();
        Integer height = picture.getPicHeight();
        if (width == null || height == null || width <= 0 || height <= 0) {
            return "未知";
        }
        String orientation;
        if (width < height) {
            orientation = "竖版";
        } else if (width > height) {
            orientation = "横版";
        } else {
            orientation = "方图";
        }
        double ratio = (double) width / height;
        return width + "x" + height + "（" + orientation + "，比例 "
                + String.format(java.util.Locale.ROOT, "%.2f", ratio) + "）";
    }

    /**
     * 去掉换行与竖线，避免候选字段破坏提示词结构；超长截断
     */
    private static String safe(String value, int maxChars) {
        String text = StrUtil.trimToEmpty(value)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('|', '/');
        if (text.isEmpty()) {
            return "未知";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
