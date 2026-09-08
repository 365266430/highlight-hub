package com.highlighthub.auth;

import com.highlighthub.common.BusinessException;
import com.highlighthub.user.UserEntity;
import com.highlighthub.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Validated
public class AuthController {
    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(UserService userService, AuthenticationManager authenticationManager) {
        this.userService = userService;
        this.authenticationManager = authenticationManager;
    }

    public record RegisterRequest(@NotBlank @jakarta.validation.constraints.Pattern(
            regexp = "^[a-zA-Z0-9_]{3,32}$", message = "username must be 3-32 chars of letters/digits/underscore") String username,
            @NotBlank @Size(min = 8, max = 128) String password,
            String displayName) {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record UserView(Long id, String username, String displayName, String role,
                           Long storageQuotaBytes, Long usedBytes) {}

    public static UserView view(UserEntity u) {
        return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(),
                u.getStorageQuotaBytes(), u.getUsedBytes());
    }

    @PostMapping("/auth/register")
    public UserView register(@RequestBody @Validated RegisterRequest req) {
        UserEntity user = userService.register(req.username(), req.password(), req.displayName());
        return view(user);
    }

    @PostMapping("/auth/login")
    public UserView login(@RequestBody @Validated LoginRequest req, HttpServletRequest request,
                          HttpServletResponse response) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        return view(userService.requireUser(principal.getId()));
    }

    @PostMapping("/auth/logout")
    public void logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    public UserView me() {
        Long id = SecurityUtils.currentUserId();
        return view(userService.requireUser(id));
    }
}
