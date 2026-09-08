package com.highlighthub.upload;

import com.highlighthub.auth.SecurityUtils;
import com.highlighthub.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.Map;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {
    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    public record CreateUploadRequest(String originalFilename, Long declaredSize) {}

    @PostMapping
    public UploadService.CreatedSession create(@RequestBody CreateUploadRequest req) {
        if (req == null || req.declaredSize() == null) {
            throw BusinessException.badRequest("declaredSize is required");
        }
        return uploadService.createSession(SecurityUtils.currentUserId(), req.originalFilename(), req.declaredSize());
    }

    @GetMapping("/{id}")
    public UploadService.SessionView get(@PathVariable String id) {
        return uploadService.view(uploadService.requireOwnedSession(id, SecurityUtils.currentUserId()));
    }

    /** raw chunk bytes as the request body; server verifies size and checksum, client claims are untrusted */
    @PutMapping("/{id}/chunks/{index}")
    public Map<String, Object> putChunk(@PathVariable String id, @PathVariable int index,
                                        HttpServletRequest request) {
        long contentLength = request.getContentLengthLong();
        if (contentLength == 0) {
            throw BusinessException.badRequest("empty chunk body");
        }
        return uploadService.putChunk(SecurityUtils.currentUserId(), id, index, unchecked(request));
    }

    private static InputStream unchecked(HttpServletRequest request) {
        try {
            return request.getInputStream();
        } catch (Exception e) {
            throw BusinessException.badRequest("cannot read request body");
        }
    }

    @PostMapping("/{id}/complete")
    public Map<String, Object> complete(@PathVariable String id) {
        return uploadService.complete(SecurityUtils.currentUserId(), id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> cancel(@PathVariable String id) {
        uploadService.cancel(SecurityUtils.currentUserId(), id);
        return Map.of("cancelled", true);
    }
}
