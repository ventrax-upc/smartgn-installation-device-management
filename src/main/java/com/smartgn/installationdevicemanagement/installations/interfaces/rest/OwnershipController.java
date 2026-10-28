package com.smartgn.installationdevicemanagement.installations.interfaces.rest;

import com.smartgn.installationdevicemanagement.shared.application.port.out.InstallationStore;
import com.smartgn.installationdevicemanagement.shared.domain.*;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class OwnershipController {
    private final InstallationStore store;

    public OwnershipController(InstallationStore store) {
        this.store = store;
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "serviceAuth")
    @GetMapping("/internal/v1/meters/{id}/ownership")
    public Map<String, Object> ownership(@PathVariable UUID id) {
        var point =
                store.findSupplyPoint(id)
                        .orElseThrow(() -> new DomainException("NOT_FOUND", "Meter not found"));
        var p =
                store.findProperty(point.propertyId())
                        .orElseThrow(() -> new DomainException("NOT_FOUND", "Property not found"));
        return Map.of(
                "meterId",
                id,
                "ownerAccountId",
                p.accountId(),
                "active",
                p.active() && point.active());
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "serviceAuth")
    @GetMapping("/internal/v1/devices/{id}/authorization")
    public Map<String, Object> authorization(
            @PathVariable UUID id, @RequestParam long associationVersion) {
        var d =
                store.findDevice(id)
                        .orElseThrow(() -> new DomainException("NOT_FOUND", "Device not found"));
        boolean scopeActive =
                store.findProperty(d.propertyId())
                                .filter(p -> p.active() && p.accountId().equals(d.accountId()))
                                .isPresent()
                        && store.findSupplyPoint(d.pointId())
                                .filter(p -> p.active() && p.propertyId().equals(d.propertyId()))
                                .isPresent();
        boolean associationActive =
                store.findAssociation(id, d.associationVersion())
                        .filter(
                                a ->
                                        a.confirmed()
                                                && a.validTo() == null
                                                && a.accountId().equals(d.accountId())
                                                && a.pointId().equals(d.pointId()))
                        .isPresent();
        return Map.of(
                "deviceId",
                id,
                "associationVersion",
                d.associationVersion(),
                "supplyPointId",
                d.pointId(),
                "authorized",
                scopeActive
                        && associationActive
                        && d.status()
                                == com.smartgn.installationdevicemanagement.shared.domain
                                        .InstallationModels.DeviceStatus.ACTIVE
                        && d.brokerConfirmed()
                        && d.telemetryConfirmed()
                        && associationVersion == d.associationVersion());
    }
}
