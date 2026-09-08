package com.highlighthub.user;

import com.highlighthub.auth.UserPrincipal;
import com.highlighthub.common.BusinessException;
import com.highlighthub.common.Utils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserService {
    private final UserMapper userMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Value("${highlight-hub.quota.default-user-quota-bytes}")
    private long defaultQuotaBytes;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public Optional<UserPrincipal> loadUserByUsername(String username) {
        UserEntity user = userMapper.findByUsername(username);
        if (user == null) return Optional.empty();
        return Optional.of(new UserPrincipal(user.getId(), user.getUsername(),
                user.getPasswordHash(), user.getRole(), user.getStatus()));
    }

    public UserEntity register(String username, String password, String displayName) {
        if (username == null || !username.matches("^[a-zA-Z0-9_]{3,32}$")) {
            throw BusinessException.badRequest("username must be 3-32 chars of letters/digits/underscore");
        }
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw BusinessException.badRequest("password must be 8-128 characters");
        }
        if (userMapper.findByUsername(username) != null) {
            throw BusinessException.conflict("CONFLICT", "username already taken");
        }
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(password));
        user.setDisplayName(displayName == null || displayName.isBlank() ? username : displayName);
        user.setRole("USER");
        user.setStorageQuotaBytes(defaultQuotaBytes);
        user.setUsedBytes(0L);
        user.setStatus("ACTIVE");
        user.setCreatedAt(Utils.utcNow());
        user.setUpdatedAt(Utils.utcNow());
        userMapper.insert(user);
        return user;
    }

    public UserEntity requireUser(Long id) {
        UserEntity user = userMapper.selectById(id);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw BusinessException.notFound("user not found");
        }
        return user;
    }
}
