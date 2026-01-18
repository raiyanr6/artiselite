package org.example.artiselite.dto.product;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

@Data
public class ProductResponse {
    private Long id;
    private String name;
    private String sku;
    private String description;
    private String category;
    private Set<String> tags;
    private Integer quantity;
    private Integer lowStockThreshold;
    private BigDecimal unitPrice;
    private BigDecimal averageCost;
    private String barcodeData;
    private String barcodeImagePath;
    private Boolean isArchived;
    private Boolean isLowStock;
    private BigDecimal totalValue;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}