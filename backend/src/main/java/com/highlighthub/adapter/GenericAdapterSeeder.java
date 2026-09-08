package com.highlighthub.adapter;

import com.highlighthub.common.BusinessException;
import com.highlighthub.common.Utils;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seeds the generic OCR adapter (EXPERIMENTAL - explicitly NOT VERIFIED:
 * no real game has been validated). Idempotent; runs once at startup.
 */
@Component
@DependsOn("flywayInitializer")
public class GenericAdapterSeeder {

    private final AdapterDefinitionMapper definitionMapper;
    private final AdapterVersionMapper versionMapper;

    public GenericAdapterSeeder(AdapterDefinitionMapper definitionMapper,
                                AdapterVersionMapper versionMapper) {
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
    }

    public static final String GAME_KEY = "generic-ocr";

    @PostConstruct
    public void seed() {
        AdapterDefinitionEntity def = definitionMapper.findByGameKey(GAME_KEY);
        if (def == null) {
            def = new AdapterDefinitionEntity();
            def.setGameKey(GAME_KEY);
            def.setDisplayName("通用 OCR 区域识别（实验性）");
            java.time.LocalDateTime now = com.highlighthub.common.Utils.utcNow();
            def.setCreatedAt(now);
            def.setUpdatedAt(now);
            definitionMapper.insert(def);
        }
        if (versionMapper.listByAdapter(def.getId()).isEmpty()) {
            AdapterVersionEntity v = new AdapterVersionEntity();
            v.setAdapterId(def.getId());
            v.setAdapterVersion(1);
            v.setTemplateVersion("template-2026.09-v1");
            v.setStatus("EXPERIMENTAL");
            // ROI templates are configured per media by users in the calibration UI;
            // this base config only carries sampling defaults.
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("sampleIntervalMs", 500);
            config.put("preprocess", Map.of("scale", 2, "grayscale", true));
            config.put("rois", new Object[0]);
            v.setConfigJson(Utils.toJson(config));
            v.setSupportedEventTypes(Utils.toJson(java.util.List.of(
                    "ELIMINATION_NOTICE", "SCORE_CHANGE", "ROUND_START", "ROUND_END", "MANUAL_MARKER")));
            v.setNotes("通用区域 OCR 适配器：需要用户自行框选 ROI 并校准；未经真实游戏验证，不得标为 VERIFIED。");
            v.setCreatedAt(com.highlighthub.common.Utils.utcNow());
            versionMapper.insert(v);
        }
    }

    public AdapterVersionEntity requireUsableVersion(String adapterVersionId) {
        AdapterVersionEntity v = versionMapper.findById(adapterVersionId);
        if (v == null) throw BusinessException.notFound("adapter version not found");
        if ("DISABLED".equals(v.getStatus())) {
            throw BusinessException.badRequest("adapter version is disabled");
        }
        return v;
    }
}
