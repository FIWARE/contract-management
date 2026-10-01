package org.fiware.iam.handlers;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * What happens to an order, used to describe it in the logs.
 */
@Getter
@RequiredArgsConstructor
public enum OrderAction {

	START("start"),
	NEGOTIATION("negotiation"),
	COMPLETION("completion"),
	STOP("stop"),
	DELETION("deletion");

	private final String label;

	@Override
	public String toString() {
		return label;
	}
}
