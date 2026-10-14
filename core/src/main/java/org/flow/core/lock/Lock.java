package org.flow.core.lock;

import java.time.Instant;

import org.flow.core.models.HasUID;
import org.flow.core.utils.IdUtils;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Lock implements HasUID {
    private String category;
    private String id;
    private String owner;
    private Instant createdAt;

    @Override
    public String uid() {
        return IdUtils.fromParts(this.category, this.id);
    }
}
