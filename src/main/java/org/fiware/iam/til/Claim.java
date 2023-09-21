package org.fiware.iam.til;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Claim{
    private String target;
    private List<String> roles = new ArrayList<>();
}
