package com.tzy.picturebackend.model.vo.rag;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 图库问答响应。
 * <p>
 * 三个状态互斥：
 * <ul>
 *   <li>正常回答：{@code refused=false, degraded=false}，引用至少 1 张；</li>
 *   <li>拒答：{@code refused=true, degraded=false}，引用为空（无候选、模型拒答、引用校验失败）；</li>
 *   <li>降级：{@code refused=true, degraded=true}，引用为空（检索或生成失败、功能未开启）。</li>
 * </ul>
 * 无论哪种状态，HTTP 都是 200 + 业务码 0，沿用项目统一约定。
 */
@Data
public class RagAskResponse implements Serializable {

    /**
     * 没有召回到候选或模型判断问题与图库无关时的固定文案
     */
    public static final String ANSWER_NOT_FOUND = "没有找到与问题相关的图片。";

    /**
     * 检索 / 生成不可用时的固定文案
     */
    public static final String ANSWER_UNAVAILABLE = "图库问答暂时不可用，请稍后再试。";

    /**
     * 回答（纯文本，最多 400 字符）
     */
    private String answer;

    /**
     * 引用图片，按模型引用顺序排列
     */
    private List<RagCitationVO> citations;

    /**
     * 是否拒答（引用必为空）
     */
    private boolean refused;

    /**
     * 是否降级（检索或生成失败）
     */
    private boolean degraded;

    public static RagAskResponse answered(String answer, List<RagCitationVO> citations) {
        RagAskResponse response = new RagAskResponse();
        response.setAnswer(answer);
        response.setCitations(citations == null ? List.of() : List.copyOf(citations));
        response.setRefused(false);
        response.setDegraded(false);
        return response;
    }

    public static RagAskResponse refused() {
        return refused(ANSWER_NOT_FOUND);
    }

    public static RagAskResponse refused(String answer) {
        RagAskResponse response = new RagAskResponse();
        response.setAnswer(answer);
        response.setCitations(List.of());
        response.setRefused(true);
        response.setDegraded(false);
        return response;
    }

    public static RagAskResponse degraded() {
        return degraded(ANSWER_UNAVAILABLE);
    }

    public static RagAskResponse degraded(String answer) {
        RagAskResponse response = new RagAskResponse();
        response.setAnswer(answer);
        response.setCitations(List.of());
        response.setRefused(true);
        response.setDegraded(true);
        return response;
    }

    private static final long serialVersionUID = 1L;
}
