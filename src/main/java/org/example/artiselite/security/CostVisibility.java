package org.example.artiselite.security;

import org.example.artiselite.enums.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The single rule for who may see cost data (average cost, valuations, total values).
 * Used both in @PreAuthorize("@costVisibility.canViewCostData()") and when mapping
 * responses, so endpoints and DTOs can never disagree.
 */
@Component("costVisibility")
public class CostVisibility {

    private static final Set<String> COST_ROLES = Set.of(Role.ADMIN.name(), Role.MANAGER.name());

    public boolean canViewCostData() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(COST_ROLES::contains);
    }
}
