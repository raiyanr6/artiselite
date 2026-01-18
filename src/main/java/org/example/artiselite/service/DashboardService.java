package org.example.artiselite.service;

import org.example.artiselite.dto.dashboard.RecentActivity;
import org.example.artiselite.dto.dashboard.DashboardResponse;

import org.example.artiselite.entity.AuditLog;
import org.example.artiselite.repository.*;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ProductRepository productRepository;
    private final InboundRepository inboundRepository;
    private final OutboundRepository outboundRepository;
    private final AuditLogRepository auditLogRepository;
    private final ProductService productService;

    @Transactional(readOnly = true)
    public DashboardResponse getDashboardStats() {
        LocalDate today = LocalDate.now();

        Long totalProducts = productRepository.count();
        Long totalInboundToday = inboundRepository.countByDate(today);
        Long totalOutboundToday = outboundRepository.countByDate(today);
        Long lowStockCount = (long) productRepository.findLowStockProducts().size();

        // Get recent activities
        List<AuditLog> recentLogs = auditLogRepository.findAll(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"))
        ).getContent();

        List<RecentActivity> recentActivities = recentLogs.stream()
                .map(log -> RecentActivity.builder()
                        .type(log.getAction().name())
                        .description(log.getDescription())
                        .user(log.getUser() != null ? log.getUser().getFullName() : "System")
                        .timestamp(log.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        // Transaction volume chart (last 7 days)
        Map<String, Long> transactionVolumeChart = new HashMap<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            Long inboundCount = inboundRepository.countByDate(date);
            Long outboundCount = outboundRepository.countByDate(date);
            transactionVolumeChart.put(date.toString(), inboundCount + outboundCount);
        }

        return DashboardResponse.builder()
                .totalProducts(totalProducts)
                .totalInboundToday(totalInboundToday)
                .totalOutboundToday(totalOutboundToday)
                .lowStockCount(lowStockCount)
                .totalInventoryValue(productService.calculateTotalInventoryValuation())
                .recentActivities(recentActivities)
                .transactionVolumeChart(transactionVolumeChart)
                .build();
    }
}

