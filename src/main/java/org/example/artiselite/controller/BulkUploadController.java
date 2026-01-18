package org.example.artiselite.controller;
import org.example.artiselite.service.BulkUploadService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/bulk")
@RequiredArgsConstructor
public class BulkUploadController {

    private final BulkUploadService bulkUploadService;

    @PostMapping("/products")
    @PreAuthorize("hasAnyAuthority('INVENTORY_WRITE', 'ADMIN', 'MANAGER')")
    public ResponseEntity<Map<String, Object>> bulkUploadProducts(
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(bulkUploadService.uploadProducts(file));
    }

    @PostMapping("/inbound")
    @PreAuthorize("hasAnyAuthority('INBOUND_WRITE', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<Map<String, Object>> bulkUploadInbound(
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(bulkUploadService.uploadInbound(file));
    }

    @PostMapping("/outbound")
    @PreAuthorize("hasAnyAuthority('OUTBOUND_WRITE', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<Map<String, Object>> bulkUploadOutbound(
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(bulkUploadService.uploadOutbound(file));
    }
}
