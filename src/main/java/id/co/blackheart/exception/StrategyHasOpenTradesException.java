package id.co.blackheart.exception;

/**
 * Thrown when an action would mid-flight a live position — e.g. activating a
 * different strategy_param preset on an account_strategy that has OPEN /
 * PARTIALLY_CLOSED trades. Mapped to HTTP 409 in {@code GlobalExceptionHandler}
 * so the frontend can show a "close positions first" hint instead of a generic
 * 400.
 */
public class StrategyHasOpenTradesException extends RuntimeException {
    public StrategyHasOpenTradesException(String message) {
        super(message);
    }
}
