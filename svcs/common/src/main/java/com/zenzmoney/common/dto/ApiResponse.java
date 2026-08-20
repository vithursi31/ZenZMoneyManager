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
     * The only way to build an error body: the code comes from the registry, never from a literal.
     * Use {@link StatusCode#with(String)} for a call-site message.
     */
    public static <T> ApiResponse<T> error(StatusCode statusCode) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.status = "error";
        resp.errorCode = statusCode.code();
        resp.message = statusCode.description();
        return resp;
    }
}
