package org.example.artiselite.agent.tool;

import org.example.artiselite.enums.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writes one audit line per tool call: who (user + role), which tool, with what arguments, and the outcome.
 * Kept separate from AuditService/audit_logs on purpose: those rows feed the dashboard's
 * "recent activity" list, which shouldn't fill up with chat lookups.
 */
@Component
public class ToolCallAuditor {

    private static final Logger AUDIT = LoggerFactory.getLogger("agent.audit");
    private static final int MAX_ARGS_LOGGED = 500;
    private static final Set<String> ROLE_NAMES =
            Arrays.stream(Role.values()).map(Enum::name).collect(Collectors.toSet());

    public void record(ToolCall call, ToolResult result, long durationMs) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String user = auth != null ? auth.getName() : "anonymous";
        String role = auth == null ? "NONE" : auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(ROLE_NAMES::contains)
                .findFirst().orElse("NONE");

        AUDIT.info("tool_call user={} role={} tool={} outcome={} durationMs={} args={}",
                clean(user), role, clean(call.name()), result.outcome(), durationMs, clean(call.argumentsJson()));
    }

    // The model writes the tool name and arguments, so strip line breaks (no forged log lines) and cap the length
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replaceAll("[\\r\\n]+", " ");
        return oneLine.length() > MAX_ARGS_LOGGED ? oneLine.substring(0, MAX_ARGS_LOGGED) + "...(truncated)" : oneLine;
    }
}
