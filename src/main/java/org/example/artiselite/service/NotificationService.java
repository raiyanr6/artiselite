package org.example.artiselite.service;

import org.example.artiselite.entity.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Service
@RequiredArgsConstructor
public class NotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    public void sendLowStockAlert(List<Product> products) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", "LOW_STOCK_ALERT");
        notification.put("message", products.size() + " products are below threshold");
        notification.put("products", products);
        notification.put("timestamp", LocalDateTime.now());

        messagingTemplate.convertAndSend("/topic/notifications", notification);
    }

    public void sendInboundNotification(String productName, Integer quantity) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", "INBOUND_RECEIVED");
        notification.put("message", "New stock received: " + quantity + " units of " + productName);
        notification.put("timestamp", LocalDateTime.now());

        messagingTemplate.convertAndSend("/topic/notifications", notification);
    }

    public void sendOutboundNotification(String productName, Integer quantity) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", "OUTBOUND_DISPATCHED");
        notification.put("message", "Stock dispatched: " + quantity + " units of " + productName);
        notification.put("timestamp", LocalDateTime.now());

        messagingTemplate.convertAndSend("/topic/notifications", notification);
    }

    public void sendBulkUploadNotification(String type, int recordsProcessed) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", "BULK_UPLOAD_COMPLETE");
        notification.put("message", "Bulk " + type + " upload completed: " +
                recordsProcessed + " records processed");
        notification.put("timestamp", LocalDateTime.now());

        messagingTemplate.convertAndSend("/topic/notifications", notification);
    }
}

