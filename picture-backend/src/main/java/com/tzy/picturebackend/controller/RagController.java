package com.tzy.picturebackend.controller;

import com.tzy.picturebackend.annotation.AuthCheck;
import com.tzy.picturebackend.common.BaseResponse;
import com.tzy.picturebackend.common.ResultUtils;
import com.tzy.picturebackend.config.RagProperties;
import com.tzy.picturebackend.constant.UserConstant;
import com.tzy.picturebackend.exception.BusinessException;
import com.tzy.picturebackend.exception.ErrorCode;
import com.tzy.picturebackend.exception.ThrowUtils;
import com.tzy.picturebackend.manager.RagSearchCache;
import com.tzy.picturebackend.manager.rag.EmbeddingClient;
import com.tzy.picturebackend.manager.rag.EmbeddingText;
import com.tzy.picturebackend.model.dto.rag.RagAskRequest;
import com.tzy.picturebackend.model.entity.User;
import com.tzy.picturebackend.model.vo.rag.RagAskResponse;
import com.tzy.picturebackend.model.vo.rag.RagReindexVO;
import com.tzy.picturebackend.model.vo.rag.RagStatusVO;
import com.tzy.picturebackend.service.RagAskService;
import com.tzy.picturebackend.service.RagEmbeddingStateService;
import com.tzy.picturebackend.service.RagIndexService;
import com.tzy.picturebackend.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 接口：管理端（状态、重建，仅管理员）+ 图库问答（需登录）。
 * <p>
 * 管理接口在 {@code app.rag.enabled=false} 时统一返回 OPERATION_ERROR「未启用 RAG」；
 * RAG 开启但 {@code app.search.engine=mysql} 时返回「未启用 Elasticsearch 搜索」，
 * 与规划的开关矩阵一致。重建接口同步执行，图片较多时耗时较长，网关对该路径单独放宽了超时。
 * <p>
 * 问答接口不返回错误码表示「没有相关图片」：无候选 / 模型拒答 / 引用校验失败都是
 * HTTP 200 + {@code code=0} + {@code refused=true}，只有参数非法（40000）与超限（42900）
 * 才使用业务错误码。
 */
@RestController
@RequestMapping("rag")
@Slf4j
public class RagController {

    @Resource
    private RagProperties ragProperties;

    @Resource
    private EmbeddingClient embeddingClient;

    @Resource
    private EmbeddingText embeddingText;

    @Resource
    private RagEmbeddingStateService ragEmbeddingStateService;

    @Resource
    private RagSearchCache ragSearchCache;

    @Resource
    private ObjectProvider<RagIndexService> ragIndexServiceProvider;

    @Resource
    private RagAskService ragAskService;

    @Resource
    private UserService userService;

    /**
     * 图库问答（需登录，按用户每日限额）。
     * <p>
     * 只基于公开图片的检索结果回答，引用 id 由服务层做「⊆ 召回集」硬校验；
     * 无候选、模型拒答或校验失败返回 {@code refused=true} 且引用为空；
     * 检索 / 生成失败返回 {@code degraded=true} + 固定文案，HTTP 仍为 200。
     */
    @PostMapping("/ask")
    @AuthCheck
    public BaseResponse<RagAskResponse> ask(@Valid @RequestBody RagAskRequest askRequest,
                                            HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(ragAskService.ask(askRequest, loginUser.getId()));
    }

    /**
     * RAG 状态：开关、模型、索引文档数、待处理与失败数量。
     */
    @GetMapping("/status")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<RagStatusVO> status() {
        RagIndexService ragIndexService = requireVectorIndex();
        RagStatusVO vo = new RagStatusVO();
        vo.setEnabled(true);
        vo.setTextModel(embeddingClient.model());
        vo.setImageModel(ragProperties.getEmbedding().getImageModel());
        vo.setDims(embeddingClient.dims());
        vo.setAlias(ragProperties.getIndex().getAlias());
        vo.setVersion(embeddingClient.version());
        vo.setTextVersion(embeddingText.version());
        vo.setPendingCount(ragEmbeddingStateService.countPendingPublic());
        vo.setFailedCount(ragEmbeddingStateService.countFailed());
        try {
            vo.setIndexName(ragIndexService.indexName());
            vo.setDocCount(ragIndexService.countIndexed());
            vo.setStale(ragIndexService.stale());
        } catch (RuntimeException e) {
            log.error("读取向量索引状态失败", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "读取向量索引状态失败");
        }
        return ResultUtils.success(vo);
    }

    /**
     * 全量重建向量索引（管理员）。
     * <p>
     * 成功时返回写入数量并把别名切到新索引；有失败时不切别名，旧索引继续可用。
     */
    @PostMapping("/reindex")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<RagReindexVO> reindex() {
        RagIndexService ragIndexService = requireVectorIndex();
        try {
            RagReindexVO result = ragIndexService.reindexAll();
            if (result.isSwitched()) {
                // 别名已经切到新索引，旧的 hybrid 结果不再可信
                ragSearchCache.clear();
            }
            return ResultUtils.success(result);
        } catch (RuntimeException e) {
            log.error("重建向量索引失败", e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "重建向量索引失败: " + e.getMessage());
        }
    }

    /**
     * 拿到可用的向量索引实现；未开启或缺少 ES 时给出明确错误。
     */
    private RagIndexService requireVectorIndex() {
        ThrowUtils.throwIf(!ragProperties.isEnabled(),
                ErrorCode.OPERATION_ERROR, "未启用 RAG");
        RagIndexService ragIndexService = ragIndexServiceProvider.getIfAvailable();
        ThrowUtils.throwIf(ragIndexService == null,
                ErrorCode.OPERATION_ERROR, "未启用 Elasticsearch 搜索，RAG 向量索引不可用");
        return ragIndexService;
    }
}
