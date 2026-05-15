package id.co.blackheart.exception;

public class BacktestConcurrencyLimitException extends RuntimeException {
    public BacktestConcurrencyLimitException(String message) {
        super(message);
    }
}
