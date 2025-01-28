package org.fiware.iam.exception;

/**
 * Exception to be thrown in case of problems with rainbow
 */
public class RainbowException extends RuntimeException {

	public RainbowException(String message) {
		super(message);
	}

	public RainbowException(String message, Throwable cause) {
		super(message, cause);
	}
}
