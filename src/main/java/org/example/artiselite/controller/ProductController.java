package org.example.artiselite.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import org.example.artiselite.dto.product.*;
import org.example.artiselite.service.ProductService;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('INVENTORY_READ', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<List<ProductResponse>> getAllProducts() {
        return ResponseEntity.ok(productService.getAllProducts());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('INVENTORY_READ', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getProduct(id));
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyAuthority('INVENTORY_READ', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<List<ProductResponse>> searchProducts(@RequestParam String keyword) {
        return ResponseEntity.ok(productService.searchProducts(keyword));
    }

    @GetMapping("/low-stock")
    @PreAuthorize("hasAnyAuthority('INVENTORY_READ', 'ADMIN', 'MANAGER', 'OPERATOR')")
    public ResponseEntity<List<ProductResponse>> getLowStockProducts() {
        return ResponseEntity.ok(productService.getLowStockProducts());
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('INVENTORY_WRITE', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductRequest request) {
        return ResponseEntity.ok(productService.createProduct(request));
    }

    @PutMapping("/{id}")
    //@PreAuthorize("hasAnyAuthority('INVENTORY_WRITE', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody ProductRequest request) {
        return ResponseEntity.ok(productService.updateProduct(id, request));
    }

    @PatchMapping("/{id}/archive")
    @PreAuthorize("hasAnyAuthority('INVENTORY_WRITE', 'ADMIN', 'MANAGER')")
    public ResponseEntity<Void> archiveProduct(@PathVariable Long id) {
        productService.archiveProduct(id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('INVENTORY_DELETE', 'ADMIN')")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/valuation")
    @PreAuthorize("hasAnyAuthority('INVENTORY_READ', 'ADMIN', 'MANAGER')")
    public ResponseEntity<BigDecimal> getProductValuation(@PathVariable Long id) {
        return ResponseEntity.ok(productService.calculateProductValuation(id));
    }

    @GetMapping("/valuation/total")
    @PreAuthorize("hasAnyAuthority('INVENTORY_READ', 'ADMIN', 'MANAGER')")
    public ResponseEntity<BigDecimal> getTotalInventoryValuation() {
        return ResponseEntity.ok(productService.calculateTotalInventoryValuation());
    }
}