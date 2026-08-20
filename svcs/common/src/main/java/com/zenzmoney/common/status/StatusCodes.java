package com.zenzmoney.common.status;

/** The codes the boundary machinery itself needs. Everything else lives in {@link ServiceCodes}. */
public interface StatusCodes {

    StatusCode SC_INTERNAL_ERROR = new StatusCode("E1000", 500, "An unexpected error occurred.");

    StatusCode SC_NOT_FOUND = new StatusCode("E1010", 404, "Not found");
    StatusCode SC_BAD_REQUEST = new StatusCode("E1013", 400, "Bad request");
    StatusCode SC_NOT_AUTHORIZED = new StatusCode("E1014", 403, "Access denied");
    StatusCode SC_VALIDATION_FAILED = new StatusCode("E1015", 400, "Validation failed");
}
