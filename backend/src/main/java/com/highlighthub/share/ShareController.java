package com.highlighthub.share;

import com.highlighthub.auth.SecurityUtils;
import com.highlighthub.common.BusinessException;
import com.highlighthub.common.Utils;
import com.highlighthub.media.MediaAssetEntity;
import com.highlighthub.media.MediaAssetMapper;
import com.highlighthub.media.MediaService;
import com.highlighthub.render.RenderJobEntity;
import com.highlighthub.render.RenderService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sharing of a single finished render via high-entropy tokens.
 * Only the token hash is stored and never logged; shares are revoked by the
 * owner and expire automatically. Raw footage is never shared.
 */
@RestController
public class ShareController {
    private static final Logger log = LoggerFactory.getLogger(ShareController.class);

    private final ShareLinkMapper shareMapper;
    private final RenderService renderService;
    private final MediaAssetMapper assetMapper;
    private final MediaService mediaService;

    public ShareController(ShareLinkMapper shareMapper, RenderService renderService,
                           MediaAssetMapper assetMapper, MediaService mediaService) {
        this.shareMapper = shareMapper;
        this.renderService = renderService;
        this.assetMapper = assetMapper;
        this.mediaService = mediaService;
    }

    public record CreateShareRequest(Long ttlHours) {}

    @PostMapping("/api/renders/{id}/shares")
    public Map<String, Object> create(@PathVariable String id,
                                      @RequestBody(required = false) CreateShareRequest req) {
        RenderJobEntity job = renderService.requireOwned(id, SecurityUtils.currentUserId());
        if (!"SUCCEEDED".equals(job.getStatus())) {
            throw new BusinessException("RENDER_NOT_READY", 409, "only finished renders can be shared");
        }
        long ttl = req == null || req.ttlHours() == null ? 72
                : Math.max(1, Math.min(req.ttlHours(), 24 * 30));
        String token = Utils.newToken();
        ShareLinkEntity share = new ShareLinkEntity();
        share.setRenderJobId(job.getId());
        share.setOwnerId(job.getOwnerId());
        share.setTokenHash(Utils.sha256Hex(token));
        share.setExpiresAt(Utils.utcPlusSeconds(ttl * 3600));
        share.setCreatedAt(Utils.utcNow());
        shareMapper.insert(share);
        log.info("share {} created for render {} (ttl {}h)", share.getId(), job.getId(), ttl);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", share.getId());
        view.put("token", token); // returned exactly once; only the hash is stored
        view.put("url", "/api/shares/" + token + "/download");
        view.put("expiresAt", share.getExpiresAt().toString());
        return view;
    }

    @DeleteMapping("/api/shares/{id}")
    public Map<String, Object> revoke(@PathVariable String id) {
        ShareLinkEntity share = shareMapper.selectById(id);
        if (share == null || !share.getOwnerId().equals(SecurityUtils.currentUserId())) {
            throw BusinessException.notFound("share not found");
        }
        if (share.getRevokedAt() == null) {
            share.setRevokedAt(Utils.utcNow());
            shareMapper.updateById(share);
        }
        return Map.of("id", id, "revoked", true);
    }

    /** public, token-authorized download of the shared render only */
    @GetMapping("/api/shares/{token}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable String token,
                                                        HttpServletRequest request) {
        if (token == null || token.length() < 32) {
            throw BusinessException.notFound("share not found");
        }
        ShareLinkEntity share = shareMapper.findByTokenHash(Utils.sha256Hex(token));
        if (share == null) throw BusinessException.notFound("share not found");
        LocalDateTime now = Utils.utcNow();
        if (share.getRevokedAt() != null || share.getExpiresAt().isBefore(now)) {
            throw new BusinessException("SHARE_EXPIRED", 410, "share link expired or revoked");
        }
        RenderJobEntity job = renderService.requireOwned(share.getRenderJobId(), share.getOwnerId());
        MediaAssetEntity asset = job.getOutputAssetId() == null ? null
                : assetMapper.findById(job.getOutputAssetId());
        if (asset == null || !"ACTIVE".equals(asset.getStatus())) {
            throw new BusinessException("RENDER_NOT_READY", 409, "render output is not available");
        }
        log.info("share download: share {} job {} ip-hash {}", share.getId(), job.getId(),
                Utils.sha256Hex(request.getRemoteAddr()).substring(0, 8));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("video/mp4"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"shared-highlight-" + job.getId() + ".mp4\"");
        headers.setContentLength(asset.getSize());
        return ResponseEntity.ok(new InputStreamResource(mediaService.openByKey(asset.getStorageKey())));
    }
}
