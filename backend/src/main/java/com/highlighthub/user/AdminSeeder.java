package com.highlighthub.user;

import com.highlighthub.common.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Creates the first ADMIN account only when ADMIN_INITIAL_PASSWORD is set and
 * no administrator exists. The password is never hardcoded or logged.
 */
@Component
@DependsOn("flywayInitializer")
public class AdminSeeder {
    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserMapper userMapper;

    @Value("${highlight-hub.admin.initial-password:}")
    private String initialPassword;

    public AdminSeeder(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @PostConstruct
    public void seed() {
        if (initialPassword == null || initialPassword.isBlank()) return;
        Integer admins = userMapper.countAdmins();
        if (admins != null && admins > 0) return;
        UserEntity admin = new UserEntity();
        admin.setUsername("admin");
        admin.setPasswordHash(new BCryptPasswordEncoder().encode(initialPassword));
        admin.setDisplayName("Administrator");
        admin.setRole("ADMIN");
        admin.setStorageQuotaBytes(2147483648L);
        admin.setUsedBytes(0L);
        admin.setStatus("ACTIVE");
        admin.setCreatedAt(Utils.utcNow());
        admin.setUpdatedAt(Utils.utcNow());
        userMapper.insert(admin);
        log.info("initial ADMIN account 'admin' created from environment configuration");
    }
}
