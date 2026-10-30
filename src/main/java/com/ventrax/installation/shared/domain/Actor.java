package com.ventrax.installation.shared.domain;

import java.util.*;

public record Actor(UUID accountId, Role role, String accountKind, UUID principalAccountId) {
    public Actor(UUID id, Role role) {
        this(id, role, "PRINCIPAL", null);
    }

    public Actor {
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(role);
        Objects.requireNonNull(accountKind);
    }

    public boolean delegated() {
        return "DELEGATED".equals(accountKind);
    }

    public void requireRole(Role required) {
        if (role != required)
            throw new DomainException("FORBIDDEN", "The required role is " + required);
    }

    public void requireOwner(UUID owner) {
        if (delegated() || !accountId.equals(owner))
            throw new DomainException("FORBIDDEN", "Principal ownership is required");
    }
}
