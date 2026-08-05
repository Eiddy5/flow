package org.cses.flow.core.validations;

/**
 * Optional cross-field invariant owned by a concrete bound model.
 *
 * <p>{@link ModelValidator} remains the only public validation entry point;
 * this contract lets a concrete plugin keep rules that cannot be expressed by
 * a single Bean Validation annotation beside its fields.</p>
 */
public interface ModelInvariant {

    void verifyModelInvariant();
}
