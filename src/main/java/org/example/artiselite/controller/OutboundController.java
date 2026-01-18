package org.example.artiselite.controller;

import org.example.artiselite.dto.outbound.*;
import org.example.artiselite.service.OutboundService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/outbound")
@RequiredArgsConstructor
public class OutboundController {

    private final OutboundService outboundService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('OUTBOUND_READ', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<List<OutboundResponse>> getAllOutbounds() {
        return ResponseEntity.ok(outboundService.getAllOutbounds());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('OUTBOUND_READ', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<OutboundResponse> getOutbound(@PathVariable Long id) {
        return ResponseEntity.ok(outboundService.getOutbound(id));
    }

    @GetMapping("/date/{date}")
    @PreAuthorize("hasAnyAuthority('OUTBOUND_READ', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<List<OutboundResponse>> getOutboundsByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(outboundService.getOutboundsByDate(date));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('OUTBOUND_WRITE', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<OutboundResponse> createOutbound(
            @Valid @RequestBody OutboundRequest request) {
        return ResponseEntity.ok(outboundService.createOutbound(request, null));
    }
}