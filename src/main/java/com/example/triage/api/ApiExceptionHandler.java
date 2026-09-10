package com.example.triage.api;

import com.example.triage.dto.ApiError;
import com.example.triage.validation.InputValidationException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(InputValidationException.class)
    public ResponseEntity<ApiError> invalidInput(InputValidationException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "入力内容を確認してください。", exception.fieldErrors());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> invalidJson() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "JSON形式の入力を確認してください。", Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> unsupportedMediaType() {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Content-Typeにapplication/jsonを指定してください。", Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> notAcceptable() {
        return error(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", "応答形式はapplication/jsonです。", Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed() {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "許可されていないHTTPメソッドです。", Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected() {
        // Never serialize or log the exception: its message/cause may contain raw input.
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "処理中にエラーが発生しました。", Map.of());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message,
            Map<String, String> fields) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(new ApiError(UUID.randomUUID().toString(), code, message, fields));
    }
}
