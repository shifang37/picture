package com.tzy.picturebackend.exception;

import com.tzy.picturebackend.common.BaseResponse;
import com.tzy.picturebackend.common.ResultUtils;
import com.tzy.picturebackend.metrics.PictureMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：统一返回 BaseResponse，避免预期内的 4xx 被包装成「系统错误」。
 * <p>
 * 所有响应都是 HTTP 200 + 业务码，因此每个分支都要把业务码记进
 * {@code picture_api_errors_total{code}}，Prometheus 才能按真实错误告警。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private final PictureMetrics pictureMetrics;

    /**
     * 供 Spring 容器注入
     */
    @Autowired
    public GlobalExceptionHandler(PictureMetrics pictureMetrics) {
        this.pictureMetrics = pictureMetrics;
    }

    /**
     * 供 MockMvcBuilders.standaloneSetup 的单元测试直接 new；
     * 使用独立的 SimpleMeterRegistry，不会污染生产的 Prometheus 注册表。
     */
    public GlobalExceptionHandler() {
        this(new PictureMetrics(new SimpleMeterRegistry()));
    }

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        // 预期内的业务错误只打 warn，5xx 才输出完整堆栈
        if (e.getCode() >= ErrorCode.SYSTEM_ERROR.getCode()) {
            log.error("BusinessException: code = {}, message = {}", e.getCode(), e.getMessage(), e);
        } else {
            log.warn("BusinessException: code = {}, message = {}", e.getCode(), e.getMessage());
        }
        pictureMetrics.apiError(e.getCode());
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * 上传文件超过 Spring 的 multipart 限制时给出明确提示，而不是笼统的“系统错误”
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public BaseResponse<?> maxUploadSizeExceededExceptionHandler(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小限制: {}", e.getMessage());
        pictureMetrics.apiError(ErrorCode.PARAMS_ERROR.getCode());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "文件大小不能超过 2M");
    }

    /**
     * 请求体不是合法 JSON，或字段类型无法转换
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public BaseResponse<?> httpMessageNotReadableExceptionHandler(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        pictureMetrics.apiError(ErrorCode.PARAMS_ERROR.getCode());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请求体格式错误");
    }

    /**
     * URL 查询参数类型不匹配，例如 id=abc
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public BaseResponse<?> methodArgumentTypeMismatchExceptionHandler(MethodArgumentTypeMismatchException e) {
        String name = e.getName();
        log.warn("参数类型错误: name = {}, value = {}", name, e.getValue());
        pictureMetrics.apiError(ErrorCode.PARAMS_ERROR.getCode());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, String.format("参数类型错误：%s", name));
    }

    /**
     * 缺少必填的请求参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public BaseResponse<?> missingServletRequestParameterExceptionHandler(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        pictureMetrics.apiError(ErrorCode.PARAMS_ERROR.getCode());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, String.format("缺少参数：%s", e.getParameterName()));
    }

    /**
     * 缺少必填的 multipart 表单部分
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public BaseResponse<?> missingServletRequestPartExceptionHandler(MissingServletRequestPartException e) {
        log.warn("缺少请求表单部分: {}", e.getRequestPartName());
        pictureMetrics.apiError(ErrorCode.PARAMS_ERROR.getCode());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, String.format("缺少参数：%s", e.getRequestPartName()));
    }

    /**
     * 不支持的 Content-Type
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public BaseResponse<?> httpMediaTypeNotSupportedExceptionHandler(HttpMediaTypeNotSupportedException e) {
        log.warn("不支持的 Content-Type: {}", e.getContentType());
        pictureMetrics.apiError(ErrorCode.PARAMS_ERROR.getCode());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR,
                String.format("不支持的请求内容类型：%s", e.getContentType()));
    }

    /**
     * 不支持的请求方法
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public BaseResponse<?> httpRequestMethodNotSupportedExceptionHandler(HttpRequestMethodNotSupportedException e) {
        log.warn("不支持的请求方法: {}", e.getMethod());
        pictureMetrics.apiError(ErrorCode.METHOD_NOT_ALLOWED.getCode());
        return ResultUtils.error(ErrorCode.METHOD_NOT_ALLOWED,
                String.format("请求方法不支持：%s", e.getMethod()));
    }

    /**
     * 访问不存在的接口路径
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public BaseResponse<?> noResourceFoundExceptionHandler(NoResourceFoundException e) {
        log.warn("请求地址不存在: {}", e.getResourcePath());
        pictureMetrics.apiError(ErrorCode.NOT_FOUND_ERROR.getCode());
        return ResultUtils.error(ErrorCode.NOT_FOUND_ERROR, "请求地址不存在");
    }

    /**
     * @Valid 校验失败（MethodArgumentNotValidException 在 Spring 6 中继承 BindException）
     */
    @ExceptionHandler(BindException.class)
    public BaseResponse<?> bindExceptionHandler(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null ? ErrorCode.PARAMS_ERROR.getMessage() : fieldError.getDefaultMessage();
        log.warn("参数校验失败: {}", message);
        pictureMetrics.apiError(ErrorCode.PARAMS_ERROR.getCode());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, message);
    }

    /**
     * 方法参数上的约束校验失败（@Validated + @RequestParam 等）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public BaseResponse<?> constraintViolationExceptionHandler(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse(ErrorCode.PARAMS_ERROR.getMessage());
        log.warn("参数校验失败: {}", message);
        pictureMetrics.apiError(ErrorCode.PARAMS_ERROR.getCode());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, message);
    }

    /**
     * 数据库约束异常（长度超限、唯一索引冲突等）应归为参数错误
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public BaseResponse<?> dataIntegrityViolationExceptionHandler(DataIntegrityViolationException e) {
        log.warn("数据库约束校验失败: {}", e.getMostSpecificCause().getMessage());
        pictureMetrics.apiError(ErrorCode.PARAMS_ERROR.getCode());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "数据不合法或已存在");
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        pictureMetrics.apiError(ErrorCode.SYSTEM_ERROR.getCode());
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }
}
