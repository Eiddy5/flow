package org.flow.core.repositories;

import org.flow.core.models.AccessScope;
import org.flow.core.models.QueryFilter;

import jakarta.inject.Singleton;

/**
 * Default OSS {@link NamespaceAccessControl}: grants global access to every resource, since OSS has no
 * namespace ACL.
 */
@Singleton
public class GlobalNamespaceAccessControl implements NamespaceAccessControl {

    @Override
    public AccessScope namespaceScope(QueryFilter.Resource resource) {
        return AccessScope.global();
    }
}
