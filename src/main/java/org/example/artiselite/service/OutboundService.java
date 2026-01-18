package org.example.artiselite.service;

import org.example.artiselite.dto.outbound.*;
import org.example.artiselite.entity.*;
import org.example.artiselite.repository.*;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;



import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OutboundService {

    private final OutboundRepository outboundRepository;
    private final ProductRepository productRepository;
    private final BatchRepository batchRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    @Transactional
    public OutboundResponse createOutbound(OutboundRequest request, MultipartFile document) {
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new RuntimeException("Product not found"));

        // Check if sufficient stock is available
        if (product.getQuantity() < request.getQuantity()) {
            throw new RuntimeException("Insufficient stock. Available: " +
                    product.getQuantity() + ", Requested: " + request.getQuantity());
        }

        User currentUser = getCurrentUser();

        // FIFO batch fulfillment
        String batchNumbers = fulfillBatchesFIFO(product.getId(), request.getQuantity());

        Outbound outbound = Outbound.builder()
                .product(product)
                .quantity(request.getQuantity())
                .dispatchDate(request.getDispatchDate() != null ?
                        request.getDispatchDate() : LocalDate.now())
                .customerName(request.getCustomerName())
                .salesOrderReference(request.getSalesOrderReference())
                .batchNumber(batchNumbers)
                .unitValue(product.getAverageCost())
                .totalValue(product.getAverageCost().multiply(new BigDecimal(request.getQuantity())))
                .notes(request.getNotes())
                .dispatchedBy(currentUser)
                .build();

        // Handle document upload
        if (document != null && !document.isEmpty()) {
            String documentPath = saveDocument(document);
            outbound.setDocumentPath(documentPath);
        }

        outbound = outboundRepository.save(outbound);

        // Update product quantity
        product.setQuantity(product.getQuantity() - request.getQuantity());
        productRepository.save(product);

//        // Send real-time notification
        notificationService.sendOutboundNotification(product.getName(), request.getQuantity());
//
//        // Check for low stock after dispatch
        if (product.getQuantity() <= product.getLowStockThreshold()) {
            notificationService.sendLowStockAlert(List.of(product));
        }

        // Audit log
        auditService.logAction(currentUser.getId(), "OUTBOUND", "Outbound",
                outbound.getId(),
                "Outbound created: " + request.getQuantity() + " units of " + product.getName(),
                null);

        return mapToResponse(outbound);
    }

    /**
     * FIFO batch fulfillment - deducts from oldest batches first
     */
    @Transactional
    public String fulfillBatchesFIFO(Long productId, Integer requiredQuantity) {
        List<Batch> availableBatches = batchRepository.findAvailableBatchesFIFO(productId);

        if (availableBatches.isEmpty()) {
            return null; // No batch tracking for this product
        }

        int remainingQuantity = requiredQuantity;
        StringBuilder batchNumbers = new StringBuilder();

        for (Batch batch : availableBatches) {
            if (remainingQuantity <= 0) break;

            int quantityFromBatch = Math.min(batch.getQuantity(), remainingQuantity);

            batch.setQuantity(batch.getQuantity() - quantityFromBatch);
            batchRepository.save(batch);

            if (batchNumbers.length() > 0) {
                batchNumbers.append(", ");
            }
            batchNumbers.append(batch.getBatchNumber())
                    .append("(").append(quantityFromBatch).append(")");

            remainingQuantity -= quantityFromBatch;
        }

        if (remainingQuantity > 0) {
            throw new RuntimeException("Insufficient quantity in batches");
        }

        return batchNumbers.toString();
    }

    @Transactional(readOnly = true)
    public List<OutboundResponse> getAllOutbounds() {
        return outboundRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OutboundResponse getOutbound(Long id) {
        Outbound outbound = outboundRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Outbound not found"));
        return mapToResponse(outbound);
    }

    @Transactional(readOnly = true)
    public List<OutboundResponse> getOutboundsByDate(LocalDate date) {
        return outboundRepository.findByDispatchDate(date).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private OutboundResponse mapToResponse(Outbound outbound) {
        OutboundResponse response = new OutboundResponse();
        response.setId(outbound.getId());
        response.setProductId(outbound.getProduct().getId());
        response.setProductName(outbound.getProduct().getName());
        response.setProductSku(outbound.getProduct().getSku());
        response.setQuantity(outbound.getQuantity());
        response.setDispatchDate(outbound.getDispatchDate());
        response.setCustomerName(outbound.getCustomerName());
        response.setSalesOrderReference(outbound.getSalesOrderReference());
        response.setDocumentPath(outbound.getDocumentPath());
        response.setBatchNumber(outbound.getBatchNumber());
        response.setUnitValue(outbound.getUnitValue());
        response.setTotalValue(outbound.getTotalValue());
        response.setNotes(outbound.getNotes());

        if (outbound.getDispatchedBy() != null) {
            response.setDispatchedBy(outbound.getDispatchedBy().getFullName());
        }

        response.setCreatedAt(outbound.getCreatedAt());

        return response;
    }

    private String saveDocument(MultipartFile file) {
        try {
            String fileName = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
            Path uploadPath = Paths.get("uploads/outbound");

            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            Path filePath = uploadPath.resolve(fileName);
            Files.copy(file.getInputStream(), filePath);

            return "/uploads/outbound/" + fileName;
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
