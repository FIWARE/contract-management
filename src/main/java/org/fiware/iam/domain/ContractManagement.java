package org.fiware.iam.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Pojo to hold all information required to connect with the contract management of a data-space participant.
 */
@EqualsAndHashCode
@Data
@NoArgsConstructor
public class ContractManagement {

    private boolean local = false;
    private String address;
    private String clientId;
    private Set<String> scope;

    public ContractManagement(boolean local) {
        if (!local) {
            throw new IllegalArgumentException("If the management is not local, address, clientId and scope need to be provided.");
        }
        this.local = local;
    }

    public ContractManagement(boolean local, String address, String clientId, Set<String> scope) {
        this.local = local;
        if (!local && (address == null || clientId == null || scope == null)) {
            throw new IllegalArgumentException("If the management is not local, address, clientId and scope need to be provided.");
        }
        this.address = address;
        this.clientId = clientId;
        this.scope = scope;
    }
}
