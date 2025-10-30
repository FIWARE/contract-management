package org.fiware.iam.management;

import org.fiware.iam.cm.model.CredentialVO;
import org.fiware.iam.cm.model.OdrlPolicyJsonVO;
import org.fiware.iam.til.model.CredentialsVO;
import org.mapstruct.Mapper;

import java.util.Map;

@Mapper(componentModel = "jsr330")
public interface CMMapper {

    CredentialVO map(CredentialsVO credentialsVO);

    CredentialsVO map(CredentialVO credentialVO);

    default OdrlPolicyJsonVO map(Map<String, Object> policy) {
        if (policy == null) {
            return null;
        }
        OdrlPolicyJsonVO odrlPolicyJsonVO = new OdrlPolicyJsonVO();
        policy.entrySet()
                .forEach(e -> odrlPolicyJsonVO.setAdditionalProperties(e.getKey(), e.getValue()));
        return odrlPolicyJsonVO;
    }
}
