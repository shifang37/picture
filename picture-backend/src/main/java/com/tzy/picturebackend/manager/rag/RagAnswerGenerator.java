package com.tzy.picturebackend.manager.rag;

/**
 * 图库问答的生成模型客户端。
 * <p>
 * 只负责「把提示词发出去、把 JSON 解析成 {@link RagAnswerPayload}」；
 * 候选召回、引用校验、限额、缓存与降级都在 {@code RagAskService} 中完成，
 * 这样生成器可以独立用本地 HTTP stub 测试，不连接真实模型。
 */
public interface RagAnswerGenerator {

    /**
     * 调用生成模型
     *
     * @param systemPrompt 固定系统提示词（版本化）
     * @param userPrompt   问题 + 候选图片
     * @return 模型返回的原始结构，尚未做任何业务校验
     * @throws RagAnswerException 网络、鉴权、响应格式等任意失败
     */
    RagAnswerPayload generate(String systemPrompt, String userPrompt);

    /**
     * 模型标识，进入回答缓存 key：换模型后旧答案立即不可达
     */
    String model();
}
