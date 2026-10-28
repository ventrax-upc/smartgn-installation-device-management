package com.smartgn.installationdevicemanagement.shared.application.port.out;

import com.smartgn.installationdevicemanagement.shared.domain.Role;

import java.util.*;

public interface IdentityGateway {
    record Account(
            UUID accountId,
            String email,
            Role role,
            String accountKind,
            String status,
            UUID principalAccountId) {}

    Account resolve(String token);

    Account account(UUID id);

    boolean authorize(UUID account, UUID meter, String function);

    Map<UUID, Set<String>> meters(UUID account);
}
