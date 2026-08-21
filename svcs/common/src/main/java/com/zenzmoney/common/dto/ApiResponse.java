package com.zenzmoney.common.dto;

import com.zenzmoney.common.status.StatusCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApiResponse<T> {

    private String status;
    private T data;
    private String message;
    private String errorCode;

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.status = "success";
        resp.data = data;
        return resp;
    }

    /**
     * An error body in English. The code comes from the registry, never from a literal.
     * Prefer {@link #error(StatusCode, String)} anywhere a caller's language is known.
     */
    public static <T> ApiResponse<T> error(StatusCode statusCode) {
        return error(statusCode, statusCode.description());
    }

    /**
     * An error body carrying an already-rendered message. <b>Boundary code only</b> — the message
     * is resolved from the caller's language there, and nowhere else knows the locale.
     */
    public static <T> ApiResponse<T> error(StatusCode statusCode, String message) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.status = "error";
        resp.errorCode = statusCode.code();
        resp.message = message;
        return resp;
    }
}
