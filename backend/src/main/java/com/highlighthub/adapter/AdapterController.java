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
 * supporting auto-recognition; the registry ships empty until a real game
 * adapter passes validation (phase 2).
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
        return definitionMapper.listAll().stream().map(d -> Map.<String, Object>of(
                "id", d.id,
                "gameKey", d.gameKey,
                "displayName", d.displayName,
                "verified", hasVerifiedVersion(d.id))).toList();
    }

    @GetMapping("/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable String id) {
        return versionMapper.listByAdapter(id).stream().map(v -> {
            Map<String, Object> view = new java.util.LinkedHashMap<>();
            view.put("id", v.id);
            view.put("adapterId", v.adapterId);
            view.put("adapterVersion", v.adapterVersion);
            view.put("templateVersion", v.templateVersion);
            view.put("status", v.status);
            view.put("supportedEventTypes", Utils.fromJson(v.supportedEventTypes, List.class));
            view.put("notes", v.notes);
            return view;
        }).toList();
    }

    private boolean hasVerifiedVersion(String adapterId) {
        return versionMapper.listByAdapter(adapterId).stream()
                .anyMatch(v -> "VERIFIED".equals(v.status));
    }
}
