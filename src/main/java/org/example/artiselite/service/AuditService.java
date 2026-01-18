package org.example.artiselite.service;

import org.example.artiselite.entity.AuditLog;
import org.example.artiselite.entity.User;
import org.example.artiselite.enums.ActionType;
import org.example.artiselite.repository.AuditLogRepository;
import org.example.artiselite.repository.UserRepository;


import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    @Transactional
    public void logAction(Long userId, String action, String entityType,
                          Long entityId, String description, String oldValue) {
        User user = userId != null ? userRepository.findById(userId).orElse(null) : null;

        AuditLog log = AuditLog.builder()
                .user(user)
                .action(ActionType.valueOf(action))
                .entityType(entityType)
                .entityId(entityId)
                .description(description)
                .oldValue(oldValue)
                .build();

        auditLogRepository.save(log);
    }

    public Page<AuditLog> getAuditLogs(Pageable pageable) {
        return auditLogRepository.findAll(pageable);
    }

    public Page<AuditLog> getAuditLogsByEntity(String entityType, Long entityId, Pageable pageable) {
        return auditLogRepository.findByEntityTypeAndEntityId(entityType, entityId, pageable);
    }
}
