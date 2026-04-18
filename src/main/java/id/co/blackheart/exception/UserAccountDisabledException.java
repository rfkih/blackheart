package id.co.blackheart.exception;

public class UserAccountDisabledException extends RuntimeException {
    public UserAccountDisabledException(String message) {
        super(message);
    }
}
