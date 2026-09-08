package com.highlighthub.adapter;

import com.highlighthub.common.Utils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Adapter registry read API. Only VERIFIED adapters may be advertised as
 * supporting auto-recognition; the generic OCR adapter ships EXPERIMENTAL
 * until a real game passes validation.
 */
@RestController
@RequestMapping("/api/adapters")
public class AdapterController {
    private final AdapterDefinitionMapper definitionMapper;
    private final AdapterVersionMapper versionMapper;

    public AdapterController(AdapterDefinitionMapper definitionMapper, AdapterVersionMapper versionMapper) {
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return definitionMapper.listAll().stream().map(d -> {
            Map<String, Object> view = new java.util.LinkedHashMap<>();
            view.put("id", d.getId());
            view.put("gameKey", d.getGameKey());
            view.put("displayName", d.getDisplayName());
            view.put("verified", hasVerifiedVersion(d.getId()));
            return view;
        }).toList();
    }

    @GetMapping("/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable String id) {
        return versionMapper.listByAdapter(id).stream().map(v -> {
            Map<String, Object> view = new java.util.LinkedHashMap<>();
            view.put("id", v.getId());
            view.put("adapterId", v.getAdapterId());
            view.put("adapterVersion", v.getAdapterVersion());
            view.put("templateVersion", v.getTemplateVersion());
            view.put("status", v.getStatus());
            view.put("supportedEventTypes", Utils.fromJson(v.getSupportedEventTypes(), List.class));
            view.put("notes", v.getNotes());
            return view;
        }).toList();
    }

    private boolean hasVerifiedVersion(String adapterId) {
        return versionMapper.listByAdapter(adapterId).stream()
                .anyMatch(v -> "VERIFIED".equals(v.getStatus()));
    }
}
