package org.fiware.iam.exception;

public class TMForumException extends RuntimeException{
    public TMForumException(String message) {
        super(message);
    }

    public TMForumException(String message, Throwable cause) {
        super(message, cause);
    }
}
