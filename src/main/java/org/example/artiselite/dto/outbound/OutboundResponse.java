package org.example.artiselite.dto.outbound;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class OutboundResponse {
    private Long id;
    private Long productId;
    private String productName;
    private String productSku;
    private Integer quantity;
    private LocalDate dispatchDate;
    private String customerName;
    private String salesOrderReference;
    private String documentPath;
    private String batchNumber;
    private BigDecimal unitValue;
    private BigDecimal totalValue;
    private String notes;
    private String dispatchedBy;
    private LocalDateTime createdAt;
}
