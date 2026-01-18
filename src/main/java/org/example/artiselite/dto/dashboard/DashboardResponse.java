package org.example.artiselite.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardResponse {
    private Long totalProducts;
    private Long totalInboundToday;
    private Long totalOutboundToday;
    private Long lowStockCount;
    private BigDecimal totalInventoryValue;
    private List<RecentActivity> recentActivities;
    private Map<String, Long> transactionVolumeChart;
}


