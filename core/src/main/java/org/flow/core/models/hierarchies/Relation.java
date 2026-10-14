package org.flow.core.models.hierarchies;

import lombok.*;

@ToString
@EqualsAndHashCode
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class Relation {
    private RelationType relationType;
    private String value;
}
