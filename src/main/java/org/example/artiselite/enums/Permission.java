package org.example.artiselite.enums;

public enum Permission {
    // Inventory permissions
    INVENTORY_READ,
    INVENTORY_WRITE,
    INVENTORY_DELETE,

    // Inbound permissions
    INBOUND_READ,
    INBOUND_WRITE,
    INBOUND_DELETE,

    // Outbound permissions
    OUTBOUND_READ,
    OUTBOUND_WRITE,
    OUTBOUND_DELETE,

    // User management permissions
    USER_READ,
    USER_WRITE,
    USER_DELETE,

    // Supplier permissions
    SUPPLIER_READ,
    SUPPLIER_WRITE,
    SUPPLIER_DELETE,

    // Audit log permissions
    AUDIT_READ,

    // Dashboard permissions
    DASHBOARD_VIEW,

    // Settings permissions
    SETTINGS_MANAGE
}
