package com.zenzmoney.common.domain;

public enum AccountStatus {
    ACTIVE,
    ARCHIVED,
    /** Soft-deleted: the row is retained in the DB but hidden from all listings and operations. */
    DELETED
}
