package com.highlighthub.auth;

import com.highlighthub.common.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {
    private SecurityUtils() {}

    public static UserPrincipal currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal p) {
            return p;
        }
        throw BusinessException.notFound("user not authenticated");
    }

    public static Long currentUserId() {
        return currentUser().getId();
    }
}
