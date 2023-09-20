package org.fiware.iam.exception;

import lombok.ToString;

@ToString(callSuper = true)
public class OrganizationRetrievalException extends RuntimeException {

    private final String organizationId;

    public OrganizationRetrievalException(String message, String organizationId) {
        super(message);
        this.organizationId = organizationId;
    }

    public OrganizationRetrievalException(String message, Throwable cause, String organizationId) {
        super(message, cause);
        this.organizationId = organizationId;
    }

}
