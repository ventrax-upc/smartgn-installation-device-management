package com.smartgn.installationdevicemanagement.properties.adapter.in.web;

import com.smartgn.installationdevicemanagement.shared.application.port.out.*;
import com.smartgn.installationdevicemanagement.shared.domain.*;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class MeterController {
    private final InstallationStore store;
    private final IdentityGateway identities;

    public MeterController(InstallationStore store, IdentityGateway identities) {
        this.store = store;
        this.identities = identities;
    }

    @GetMapping("/api/v1/meters")
    public List<Map<String, Object>> list(
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestAttribute("actor")
                    Actor actor) {
        if (actor.role() == Role.SUPERADMIN)
            throw new DomainException("FORBIDDEN", "Principal or delegated USER is required");
        List<Map<String, Object>> result = new ArrayList<>();
        if (actor.delegated()) {
            for (var e : identities.meters(actor.accountId()).entrySet()) {
                var point = store.findSupplyPoint(e.getKey()).orElse(null);
                if (point == null || !point.active()) continue;
                var property = store.findProperty(point.propertyId()).orElse(null);
                if (property == null
                        || !property.active()
                        || !property.accountId().equals(actor.principalAccountId())) continue;
                result.add(Map.of("meter", point, "permissions", e.getValue()));
            }
        } else
            for (var scope : store.listActivePointScopes(actor.accountId(), List.of()))
                result.add(
                        Map.of(
                                "meter",
                                scope.point(),
                                "permissions",
                                Set.of(
                                        "VIEW_CONSUMPTION",
                                        "VIEW_HISTORY",
                                        "VIEW_ALERTS",
                                        "USE_REPORTS",
                                        "USE_TICKETS",
                                        "USE_VISITS")));
        return result;
    }
}
