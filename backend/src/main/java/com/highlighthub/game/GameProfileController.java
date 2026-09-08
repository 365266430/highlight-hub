package com.highlighthub.game;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.highlighthub.auth.SecurityUtils;
import com.highlighthub.common.BusinessException;
import com.highlighthub.common.Utils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * User-owned game profiles. A profile bundles a display name, a genre preset
 * (calibration guidance only) and a default ROI template that pre-fills the
 * analysis calibration. Profiles never claim verified recognition.
 */
@RestController
@RequestMapping("/api/games")
public class GameProfileController {
    private final UserGameMapper gameMapper;

    public GameProfileController(UserGameMapper gameMapper) {
        this.gameMapper = gameMapper;
    }

    public record CreateGameRequest(@NotBlank @Size(max = 128) String displayName,
                                    @NotBlank String genre,
                                    Object defaultRois,
                                    String notes) {}

    public static Map<String, Object> view(UserGameEntity g) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", g.getId());
        view.put("displayName", g.getDisplayName());
        view.put("genre", g.getGenre());
        view.put("defaultRois", g.getDefaultRois() == null ? List.of()
                : Utils.fromJson(g.getDefaultRois(), List.class));
        view.put("notes", g.getNotes());
        view.put("createdAt", g.getCreatedAt().toString());
        return view;
    }

    @GetMapping("/genres")
    public List<Map<String, Object>> genres() {
        return GenreCatalog.ALL.stream().map(g -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("key", g.key());
            view.put("name", g.name());
            view.put("description", g.description());
            view.put("typicalEvents", g.typicalEvents());
            view.put("exampleGames", g.exampleGames());
            view.put("suggestedRoi", g.suggestedRoi());
            return view;
        }).toList();
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return gameMapper.listByUser(SecurityUtils.currentUserId()).stream()
                .map(GameProfileController::view).toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody @Valid CreateGameRequest req) {
        if (!GenreCatalog.exists(req.genre())) {
            throw BusinessException.badRequest("unknown genre: " + req.genre()
                    + " (see /api/games/genres)");
        }
        validateRois(req.defaultRois());
        UserGameEntity g = new UserGameEntity();
        g.setUserId(SecurityUtils.currentUserId());
        g.setDisplayName(req.displayName().trim());
        g.setGenre(req.genre());
        if (req.defaultRois() != null) {
            g.setDefaultRois(Utils.toJson(req.defaultRois()));
        }
        g.setNotes(req.notes());
        g.setCreatedAt(Utils.utcNow());
        g.setUpdatedAt(Utils.utcNow());
        gameMapper.insert(g);
        return view(g);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody @Valid CreateGameRequest req) {
        UserGameEntity g = requireOwned(id);
        if (!GenreCatalog.exists(req.genre())) {
            throw BusinessException.badRequest("unknown genre: " + req.genre());
        }
        validateRois(req.defaultRois());
        g.setDisplayName(req.displayName().trim());
        g.setGenre(req.genre());
        if (req.defaultRois() != null) {
            g.setDefaultRois(Utils.toJson(req.defaultRois()));
        }
        g.setNotes(req.notes());
        g.setUpdatedAt(Utils.utcNow());
        gameMapper.updateById(g);
        return view(g);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        requireOwned(id);
        gameMapper.deleteById(id);
        return Map.of("id", id, "deleted", true);
    }

    private UserGameEntity requireOwned(String id) {
        UserGameEntity g = gameMapper.findById(id);
        if (g == null || !g.getUserId().equals(SecurityUtils.currentUserId())) {
            throw BusinessException.notFound("game profile not found");
        }
        return g;
    }

    /** ROI templates are relative rectangles: {id,x,y,w,h,mode,eventType} */
    @SuppressWarnings("unchecked")
    private void validateRois(Object rois) {
        if (rois == null) return;
        if (!(rois instanceof List<?> list)) {
            throw BusinessException.badRequest("defaultRois must be a JSON array");
        }
        if (list.size() > 8) throw BusinessException.badRequest("too many ROIs (max 8)");
        for (Object o : list) {
            if (!(o instanceof Map)) {
                throw BusinessException.badRequest("each ROI must be an object");
            }
            Map<String, Object> roi = (Map<String, Object>) o;
            for (String field : List.of("x", "y", "w", "h")) {
                Object v = roi.get(field);
                if (!(v instanceof Number n) || n.doubleValue() < 0 || n.doubleValue() > 1) {
                    throw BusinessException.badRequest("ROI " + field + " must be within 0..1");
                }
            }
            if (((Number) roi.get("w")).doubleValue()
                    + ((Number) roi.get("x")).doubleValue() > 1.0
                    || ((Number) roi.get("h")).doubleValue()
                    + ((Number) roi.get("y")).doubleValue() > 1.0) {
                throw BusinessException.badRequest("ROI must stay inside the frame");
            }
        }
    }
}
