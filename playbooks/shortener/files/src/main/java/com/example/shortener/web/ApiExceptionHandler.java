package com.example.shortener.web;

import com.example.shortener.domain.Errors;
import com.example.shortener.web.Dtos.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain errors to stable JSON error bodies that carry the request id. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(Errors.ApiException.class)
    ResponseEntity<ErrorResponse> api(Errors.ApiException e, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status());
        if (e instanceof Errors.RateLimited limited) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(limited.retryAfterSeconds()));
        }
        return response.body(new ErrorResponse(e.code(), e.getMessage(), RequestIdFilter.current(request)));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalid(Exception e, HttpServletRequest request) {
        String detail = e instanceof MethodArgumentNotValidException m && m.getFieldError() != null
                ? m.getFieldError().getField() + ": " + m.getFieldError().getDefaultMessage()
                : "request body is not valid JSON for this endpoint";
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(new ErrorResponse("invalid_request", detail, RequestIdFilter.current(request)));
    }
}
