package com.highlighthub.render;

import com.highlighthub.auth.SecurityUtils;
import com.highlighthub.common.BusinessException;
import com.highlighthub.media.MediaAssetEntity;
import com.highlighthub.media.MediaAssetMapper;
import com.highlighthub.media.MediaService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/renders")
public class RenderController {
    private final RenderService renderService;
    private final MediaService mediaService;
    private final MediaAssetMapper assetMapper;

    public RenderController(RenderService renderService, MediaService mediaService,
                            MediaAssetMapper assetMapper) {
        this.renderService = renderService;
        this.mediaService = mediaService;
        this.assetMapper = assetMapper;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return renderService.view(renderService.requireOwned(id, SecurityUtils.currentUserId()));
    }

    /** authorized download of the finished output; physical paths never leave the server */
    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable String id,
                                                        @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        RenderJobEntity job = renderService.requireOwned(id, SecurityUtils.currentUserId());
        if (!"SUCCEEDED".equals(job.getStatus()) || job.getOutputAssetId() == null) {
            throw new BusinessException("RENDER_NOT_READY", 409, "render output is not available yet");
        }
        MediaAssetEntity asset = assetMapper.findById(job.getOutputAssetId());
        if (asset == null || !"ACTIVE".equals(asset.getStatus())) {
            throw new BusinessException("RENDER_NOT_READY", 409, "render output is not available yet");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("video/mp4"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"highlight-" + job.getId() + ".mp4\"");
        headers.set("Accept-Ranges", "bytes");
        headers.setContentLength(asset.getSize());
        if (range != null) {
            MediaService.RangeResult r = mediaService.openRangeByKey(job.getOwnerId(),
                    asset.getStorageKey(), asset.getSize(), range);
            headers.setContentLength(r.length());
            headers.set(HttpHeaders.CONTENT_RANGE,
                    "bytes " + r.start() + "-" + (r.start() + r.length() - 1) + "/" + r.size());
            return new ResponseEntity<>(new InputStreamResource(r.stream()), headers, HttpStatus.PARTIAL_CONTENT);
        }
        return new ResponseEntity<>(new InputStreamResource(mediaService.openByKey(asset.getStorageKey())),
                headers, HttpStatus.OK);
    }
}
