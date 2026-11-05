package org.example.artiselite.repository;

import java.math.BigDecimal;

// Spring Data projection for the aggregate queries in InboundRepository / OutboundRepository.
// Getters match the JPQL aliases. Sums are null when no rows match.
public interface MovementTotals {
    Long getTransactionCount();
    Long getTotalUnits();
    BigDecimal getTotalValue();
}
