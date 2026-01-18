package org.example.artiselite.dto.inbound;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class InboundResponse {
    private Long id;
    private Long productId;
    private String productName;
    private String productSku;
    private Long supplierId;
    private String supplierName;
    private Integer quantity;
    private LocalDate receivedDate;
    private String invoiceReference;
    private String documentPath;
    private String batchNumber;
    private LocalDate expiryDate;
    private BigDecimal unitCost;
    private String notes;
    private String receivedBy;
    private LocalDateTime createdAt;
}
