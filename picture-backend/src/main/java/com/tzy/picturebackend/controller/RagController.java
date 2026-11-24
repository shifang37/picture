package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.annotation.AuthCheck;
import com.tzy.picturebackend.common.BaseResponse;
import com.tzy.picturebackend.common.ResultUtils;
import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.manager.rag.EmbeddingClient;
import com.tzy.picturebackend.model.vo.rag.RagStatusVO;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 管理接口（仅管理员）。
 * <p>
 * M1 只提供状态查询；{@code app.rag.enabled=false} 时所有接口统一返回
 * OPERATION_ERROR「未启用 RAG」，不会装配 embedding / 检索链路。
 */
@RestController
@RequestMapping("rag")
public class RagController {

    @Resource
    private RagProperties ragProperties;

    @Resource
    private EmbeddingClient embeddingClient;

    /**
     * RAG 状态：开关、文本/图片模型、维度、索引名。
     */
    @GetMapping("/status")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<RagStatusVO> status() {
        ThrowUtils.throwIf(!ragProperties.isEnabled(),
                ErrorCode.OPERATION_ERROR, "未启用 RAG");
        RagStatusVO vo = new RagStatusVO();
        vo.setEnabled(true);
        vo.setTextModel(embeddingClient.model());
        vo.setImageModel(ragProperties.getEmbedding().getImageModel());
        vo.setDims(embeddingClient.dims());
        vo.setIndexName(ragProperties.getIndex().getName());
        vo.setVersion(embeddingClient.version());
        return ResultUtils.success(vo);
    }
}
