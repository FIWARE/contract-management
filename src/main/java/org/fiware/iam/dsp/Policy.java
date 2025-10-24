package org.fiware.iam.dsp;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.fiware.rainbow.model.PermissionVO;
import org.fiware.rainbow.model.ProhibitionVO;

import java.util.List;

/**
 * Representation of a policy as expected by rainbow/dsp
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
public class Policy {

	@JsonProperty("odrl:permission")
	private List<PermissionVO> permission;
	@JsonProperty("odrl:prohibition")
	private List<ProhibitionVO> prohibition;
}
