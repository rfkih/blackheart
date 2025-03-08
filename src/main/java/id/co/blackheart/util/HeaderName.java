package id.co.blackheart.util;

import lombok.Getter;


import java.util.Arrays;

@Getter
public enum HeaderName {

    X_CORRELATION_ID("X-Correlation-ID"),
    X_REQUEST_ID("X-Request-ID"),
    X_CLIENT_ID("x-client-id"),
    X_ROLE_ID("x-role-id"),
    X_BRANCH_ID("x-branch-id"),
    X_USER_ID("x-user-id"),
    UBER_TRACE_ID("uber-trace-id"),
    PARENT_TRACE_ID("parent-trace-id"),
    TRACE_ID("trace-id"),
    SPAN_ID("span-id");
    private final String value;

    HeaderName(String value) {
        this.value = value;
    }

    public static HeaderName fromString(String val) {
        return Arrays.stream(values())
                .filter(headerName -> headerName.value.equalsIgnoreCase(val))
                .findFirst().orElse(null);
    }



}