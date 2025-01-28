package org.fiware.iam.exception;

/**
 * Exception to be thrown in case of issues with the tmforum api
 */
public class TMForumException extends RuntimeException{
    public TMForumException(String message) {
        super(message);
    }

    public TMForumException(String message, Throwable cause) {
        super(message, cause);
    }
}
