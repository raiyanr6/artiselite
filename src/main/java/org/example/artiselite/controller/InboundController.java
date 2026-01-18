package org.example.artiselite.controller;

import org.example.artiselite.dto.inbound.*;
import org.example.artiselite.service.InboundService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/inbound")
@RequiredArgsConstructor
public class InboundController {

    private final InboundService inboundService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('INBOUND_READ', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<List<InboundResponse>> getAllInbounds() {
        return ResponseEntity.ok(inboundService.getAllInbounds());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('INBOUND_READ', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<InboundResponse> getInbound(@PathVariable Long id) {
        return ResponseEntity.ok(inboundService.getInbound(id));
    }

    @GetMapping("/date/{date}")
    @PreAuthorize("hasAnyAuthority('INBOUND_READ', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<List<InboundResponse>> getInboundsByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(inboundService.getInboundsByDate(date));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('INBOUND_WRITE', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<InboundResponse> createInbound(
            @Valid @RequestBody InboundRequest request) {
        return ResponseEntity.ok(inboundService.createInbound(request, null));
    }
}