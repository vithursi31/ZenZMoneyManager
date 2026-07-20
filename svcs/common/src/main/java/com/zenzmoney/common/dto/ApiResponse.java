package com.zenzmoney.common.dto;

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

    public static <T> ApiResponse<T> error(String errorCode, String message) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.status = "error";
        resp.errorCode = errorCode;
        resp.message = message;
        return resp;
    }
}
