package id.co.blackheart.util;

import lombok.Getter;

@Getter
public enum HeaderName {

    X_CORRELATION_ID("X-Correlation-ID"),
    X_REQUEST_ID("X-Request-ID"),
    X_CLIENT_ID("x-client-id"),
    X_ROLE_ID("x-role-id"),
    X_BRANCH_ID("x-branch-id"),
    X_USER_ID("x-user-id");

    private final String value;

    HeaderName(String value) {
        this.value = value;
    }
}