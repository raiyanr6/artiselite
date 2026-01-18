package org.example.artiselite.dto.inbound;


import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class InboundRequest {
    @NotNull(message = "Product ID is required")
    private Long productId;

    private Long supplierId;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;

    private LocalDate receivedDate;
    private String invoiceReference;
    private String batchNumber;
    private LocalDate expiryDate;
    private BigDecimal unitCost;
    private String notes;
}
