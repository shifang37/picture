package com.tzy.picturebackend.model.dto.rag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 图库问答请求。
 * <p>
 * 问题长度上限 200 字符：问答会把它拼进提示词并调用生成模型，过长的输入既贵又容易
 * 把候选挤到上下文之外。{@code topK} 不传时使用 {@code app.rag.ask.top-k}（默认 8）。
 */
@Data
public class RagAskRequest implements Serializable {

    /**
     * 问题
     */
    @NotBlank(message = "问题不能为空")
    @Size(max = 200, message = "问题不能超过 200 个字符")
    private String question;

    /**
     * 召回候选数量，1-20；不传时使用服务端默认值
     */
    @Min(value = 1, message = "topK 最小为 1")
    @Max(value = 20, message = "topK 最大为 20")
    private Integer topK;

    private static final long serialVersionUID = 1L;
}
