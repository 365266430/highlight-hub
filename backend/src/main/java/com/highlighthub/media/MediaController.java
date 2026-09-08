package com.highlighthub.media;

import com.highlighthub.auth.SecurityUtils;
import com.highlighthub.common.BusinessException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/media")
public class MediaController {
    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        var p = mediaService.pageForOwner(SecurityUtils.currentUserId(), page, size);
        return Map.of("items", p.getRecords().stream().map(mediaService::view).toList(),
                "page", p.getCurrent(), "total", p.getTotal());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return mediaService.view(mediaService.requireOwnedMedia(id, SecurityUtils.currentUserId()));
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<InputStreamResource> preview(@PathVariable String id,
                                                       @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        Long userId = SecurityUtils.currentUserId();
        MediaService.RangeResult r = mediaService.openRange(userId, id, "PREVIEW", range);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("video/mp4"));
        headers.set("Accept-Ranges", "bytes");
        headers.setContentLength(r.length());
        if (range != null) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + r.start() + "-" + (r.start() + r.length() - 1) + "/" + r.size());
            return new ResponseEntity<>(new InputStreamResource(r.stream()), headers, HttpStatus.PARTIAL_CONTENT);
        }
        return new ResponseEntity<>(new InputStreamResource(r.stream()), headers, HttpStatus.OK);
    }

    @GetMapping("/{id}/thumbnails")
    public ResponseEntity<InputStreamResource> thumbnails(@PathVariable String id,
                                                          @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        Long userId = SecurityUtils.currentUserId();
        MediaService.RangeResult r = mediaService.openRange(userId, id, "THUMBNAIL", null);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_JPEG);
        headers.setContentLength(r.length());
        return new ResponseEntity<>(new InputStreamResource(r.stream()), headers, HttpStatus.OK);
    }

    @GetMapping("/{id}/original")
    public ResponseEntity<InputStreamResource> original(@PathVariable String id,
                                                        @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        Long userId = SecurityUtils.currentUserId();
        MediaEntity m = mediaService.requireOwnedMedia(id, userId);
        mediaService.assertStatusReady(m);
        MediaService.RangeResult r = mediaService.openRange(userId, id, "ORIGINAL", range);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set("Accept-Ranges", "bytes");
        headers.setContentLength(r.length());
        if (range != null) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + r.start() + "-" + (r.start() + r.length() - 1) + "/" + r.size());
            return new ResponseEntity<>(new InputStreamResource(r.stream()), headers, HttpStatus.PARTIAL_CONTENT);
        }
        return new ResponseEntity<>(new InputStreamResource(r.stream()), headers, HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        mediaService.deleteMedia(SecurityUtils.currentUserId(), id);
        return Map.of("id", id, "status", "DELETING");
    }
}
