package org.example.artiselite.service;
import org.example.artiselite.dto.inbound.InboundRequest;
import org.example.artiselite.dto.inbound.InboundResponse;
import org.example.artiselite.entity.*;
import org.example.artiselite.repository.*;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;


import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InboundService {

    private final InboundRepository inboundRepository;
    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final BatchRepository batchRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Transactional
    public InboundResponse createInbound(InboundRequest request, MultipartFile document) {
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new RuntimeException("Product not found"));

        Supplier supplier = null;
        if (request.getSupplierId() != null) {
            supplier = supplierRepository.findById(request.getSupplierId())
                    .orElseThrow(() -> new RuntimeException("Supplier not found"));
        }

        User currentUser = getCurrentUser();

        Inbound inbound = Inbound.builder()
                .product(product)
                .supplier(supplier)
                .quantity(request.getQuantity())
                .receivedDate(request.getReceivedDate() != null ?
                        request.getReceivedDate() : LocalDate.now())
                .invoiceReference(request.getInvoiceReference())
                .batchNumber(request.getBatchNumber())
                .expiryDate(request.getExpiryDate())
                .unitCost(request.getUnitCost())
                .notes(request.getNotes())
                .receivedBy(currentUser)
                .build();

        // Handle document upload
        if (document != null && !document.isEmpty()) {
            String documentPath = saveDocument(document);
            inbound.setDocumentPath(documentPath);
        }

        inbound = inboundRepository.save(inbound);

        // Create batch entry for expiry tracking
        if (request.getBatchNumber() != null) {
            Batch batch = Batch.builder()
                    .product(product)
                    .batchNumber(request.getBatchNumber())
                    .quantity(request.getQuantity())
                    .receivedDate(inbound.getReceivedDate())
                    .expiryDate(request.getExpiryDate())
                    .unitCost(request.getUnitCost())
                    .inbound(inbound)
                    .build();
            batchRepository.save(batch);
        }

        // Update product quantity and average cost
        updateProductAfterInbound(product, request.getQuantity(), request.getUnitCost());

        // Send real-time notification
        notificationService.sendInboundNotification(product.getName(), request.getQuantity());

        // Audit log
        auditService.logAction(currentUser.getId(), "INBOUND", "Inbound",
                inbound.getId(),
                "Inbound created: " + request.getQuantity() + " units of " + product.getName(),
                null);

        return mapToResponse(inbound);
    }

    @Transactional
    public void updateProductAfterInbound(Product product, Integer quantity, BigDecimal unitCost) {
        int oldQuantity = product.getQuantity();
        BigDecimal oldAverageCost = product.getAverageCost();

        // Update quantity
        product.setQuantity(oldQuantity + quantity);

        // Calculate weighted average cost
        if (unitCost != null && unitCost.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal oldTotalValue = oldAverageCost.multiply(new BigDecimal(oldQuantity));
            BigDecimal newTotalValue = unitCost.multiply(new BigDecimal(quantity));
            BigDecimal totalValue = oldTotalValue.add(newTotalValue);

            BigDecimal newAverageCost = totalValue.divide(
                    new BigDecimal(product.getQuantity()),
                    2,
                    RoundingMode.HALF_UP
            );
            product.setAverageCost(newAverageCost);
        }

        productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public List<InboundResponse> getAllInbounds() {
        return inboundRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public InboundResponse getInbound(Long id) {
        Inbound inbound = inboundRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Inbound not found"));
        return mapToResponse(inbound);
    }

    @Transactional(readOnly = true)
    public List<InboundResponse> getInboundsByDate(LocalDate date) {
        return inboundRepository.findByReceivedDate(date).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private InboundResponse mapToResponse(Inbound inbound) {
        InboundResponse response = new InboundResponse();
        response.setId(inbound.getId());
        response.setProductId(inbound.getProduct().getId());
        response.setProductName(inbound.getProduct().getName());
        response.setProductSku(inbound.getProduct().getSku());
        response.setQuantity(inbound.getQuantity());
        response.setReceivedDate(inbound.getReceivedDate());
        response.setInvoiceReference(inbound.getInvoiceReference());
        response.setDocumentPath(inbound.getDocumentPath());
        response.setBatchNumber(inbound.getBatchNumber());
        response.setExpiryDate(inbound.getExpiryDate());
        response.setUnitCost(inbound.getUnitCost());
        response.setNotes(inbound.getNotes());

        if (inbound.getSupplier() != null) {
            response.setSupplierId(inbound.getSupplier().getId());
            response.setSupplierName(inbound.getSupplier().getName());
        }

        if (inbound.getReceivedBy() != null) {
            response.setReceivedBy(inbound.getReceivedBy().getFullName());
        }

        response.setCreatedAt(inbound.getCreatedAt());

        return response;
    }

    private String saveDocument(MultipartFile file) {
        try {
            String fileName = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
            Path uploadPath = Paths.get("uploads/inbound");

            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            Path filePath = uploadPath.resolve(fileName);
            Files.copy(file.getInputStream(), filePath);

            return "/uploads/inbound/" + fileName;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save document: " + e.getMessage());
        }
    }

    private User getCurrentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}
