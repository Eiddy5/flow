package org.flow.core.models.flows.sla;

import java.util.List;

import org.flow.core.models.Label;

public record Violation(String slaId, SLA.Behavior behavior, List<Label> labels, String reason) {
}
