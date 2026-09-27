package com.claimflow.common.error;

import com.claimflow.common.correlation.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Centralised exception -> HTTP mapping so controllers stay free of try/catch
 * and every service returns the same error shape.
 *
 * Extends {@link ResponseEntityExceptionHandler}, which already maps every standard Spring MVC
 * exception to its correct status (400 bad parameter, 404 unknown route, 405 wrong method,
 * 415 wrong content type, ...). We only change how the body is rendered, instead of re-implementing
 * that mapping one exception at a time (see BUG-001 / BUG-006).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---- Our domain exceptions ----

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req.getRequestURI(), List.of());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req.getRequestURI(), List.of());
    }

    /**
     * Two transactions updated the same row at the same moment: JPA @Version made the second commit fail
     * (UPDATE ... WHERE version = ? matched 0 rows) instead of silently overwriting the first.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest req) {
        log.info("Concurrent update rejected on {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, "The resource was modified concurrently by another request. Reload and retry.",
                req.getRequestURI(), List.of());
    }

    @ExceptionHandler(PreconditionFailedException.class)
    public ResponseEntity<ApiError> handlePreconditionFailed(PreconditionFailedException ex, HttpServletRequest req) {
        return build(HttpStatus.PRECONDITION_FAILED, ex.getMessage(), req.getRequestURI(), List.of());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiError> handleBusinessRule(BusinessRuleException ex, HttpServletRequest req) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), req.getRequestURI(), List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        // Log the stack trace, but never leak internals to the client.
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", req.getRequestURI(), List.of());
    }

    // ---- Spring MVC exceptions: keep Spring's status, render our body ----

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return toObject(build(status, "Request validation failed", path(request), violations));
    }

    /**
     * Spring 6.1+: once a controller method has constraints directly on its parameters (e.g. @Size on a
     * header, @Max on a query param), Spring validates ALL its parameters as a method call, including
     * the @Valid @RequestBody, and throws this exception instead of MethodArgumentNotValidException.
     * Without this override the status is still 400 but the field violations are silently lost (BUG-007).
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<ApiError.FieldViolation> violations = new ArrayList<>();
        for (ParameterValidationResult result : ex.getAllValidationResults()) {
            if (result instanceof ParameterErrors errors) {
                // an object argument such as the request body: report its fields
                errors.getFieldErrors().forEach(fe ->
                        violations.add(new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage())));
            } else {
                // a simple argument (header, query param, path variable): report the parameter itself
                String name = result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(err ->
                        violations.add(new ApiError.FieldViolation(name, err.getDefaultMessage())));
            }
        }
        return toObject(build(status, "Request validation failed", path(request), violations));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String name = ex.getPropertyName() != null ? ex.getPropertyName() : "parameter";
        String message = "Invalid value '" + ex.getValue() + "' for '" + name + "'";
        Class<?> required = ex.getRequiredType();
        if (required != null && required.isEnum()) {
            message += "; allowed: " + Arrays.toString(required.getEnumConstants());
        } else if (required != null) {
            message += "; expected " + required.getSimpleName();
        }
        return toObject(build(status, message, path(request), List.of()));
    }

    /** Every other standard MVC exception ends up here with the right status already chosen. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        String message = body instanceof ProblemDetail pd && pd.getDetail() != null
                ? pd.getDetail()
                : ex.getMessage();
        return toObject(build(status, message, path(request), List.of()));
    }

    // ---- helpers ----

    private ResponseEntity<ApiError> build(HttpStatusCode status, String message, String path,
                                           List<ApiError.FieldViolation> violations) {
        String reason = status instanceof HttpStatus hs ? hs.getReasonPhrase() : String.valueOf(status.value());
        ApiError body = new ApiError(Instant.now(), status.value(), reason, message, path,
                CorrelationId.current(), violations);
        return ResponseEntity.status(status).body(body);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<Object> toObject(ResponseEntity<ApiError> response) {
        return (ResponseEntity) response;
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
    }
}
