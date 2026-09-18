package cn.yl.api.support;

import cn.yl.common.api.R;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 全局异常处理：把异常统一收敛为 {@link R}，避免堆栈信息外泄（合规要求）。 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e, HttpServletRequest request) {
        log.warn("业务异常 uri={} code={} msg={}", request.getRequestURI(), e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValidation(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message =
                fieldError == null
                        ? ErrorCode.PARAM_INVALID.getMessage()
                        : fieldError.getField() + " " + fieldError.getDefaultMessage();
        return R.fail(ErrorCode.PARAM_INVALID.getCode(), message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return R.fail(ErrorCode.PARAM_INVALID.getCode(), "缺少必填参数：" + e.getParameterName());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleUnexpected(Exception e, HttpServletRequest request) {
        // 仅记录服务端堆栈，不向客户端返回细节（PRD §9 禁止敏感信息外泄）
        log.error("未预期异常 uri={}", request.getRequestURI(), e);
        return R.fail(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage());
    }
}
