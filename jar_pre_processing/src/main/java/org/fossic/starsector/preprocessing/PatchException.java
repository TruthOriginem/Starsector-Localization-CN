package org.fossic.starsector.preprocessing;

public class PatchException extends RuntimeException {
    public PatchException(String message) {
        super(message);
    }

    public PatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
