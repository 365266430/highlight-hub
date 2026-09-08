package com.highlighthub.admin;

import com.highlighthub.adapter.AdapterDefinitionEntity;
import com.highlighthub.adapter.AdapterDefinitionMapper;
import com.highlighthub.adapter.AdapterVersionEntity;
import com.highlighthub.adapter.AdapterVersionMapper;
import com.highlighthub.auth.SecurityUtils;
import com.highlighthub.common.BusinessException;
import com.highlighthub.common.Utils;
import com.highlighthub.user.UserEntity;
import com.highlighthub.user.UserMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Administrator surface: measured operational statistics, per-user quota
 * management and adapter lifecycle control. Every number here is computed
 * from real rows - nothing is estimated or faked.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final UserMapper userMapper;
    private final AdapterDefinitionMapper definitionMapper;
    private final AdapterVersionMapper versionMapper;

    public AdminController(JdbcTemplate jdbc, UserMapper userMapper,
                           AdapterDefinitionMapper definitionMapper, AdapterVersionMapper versionMapper) {
        this.jdbc = jdbc;
        this.userMapper = userMapper;
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
    }

    /** allowed adapter version lifecycle transitions */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "DRAFT", Set.of("EXPERIMENTAL", "DISABLED"),
            "EXPERIMENTAL", Set.of("VERIFIED", "DISABLED"),
            "VERIFIED", Set.of("DISABLED"),
            "DISABLED", Set.of("EXPERIMENTAL"));

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        SecurityUtils.requireAdmin();
        Map<String, Object> out = new LinkedHashMap<>();

        out.put("tasksByStatus", jdbc.queryForList(
                "SELECT status, COUNT(*) n FROM tasks GROUP BY status"));
        out.put("tasksByType", jdbc.queryForList(
                "SELECT type, status, COUNT(*) n FROM tasks GROUP BY type, status"));
        // queue latency: measured seconds between task creation and first claim
        out.put("avgQueueSeconds", jdbc.queryForList(
                "SELECT type, ROUND(AVG(TIMESTAMPDIFF(SECOND, created_at, started_at)),1) avg_s " +
                        "FROM tasks WHERE started_at IS NOT NULL GROUP BY type"));
        // retries actually incurred
        out.put("retriedTasks", jdbc.queryForObject(
                "SELECT COUNT(*) FROM tasks WHERE attempt > 1", Integer.class));
        // render success rate over terminal renders
        Integer renderOk = jdbc.queryForObject(
                "SELECT COUNT(*) FROM render_jobs WHERE status='SUCCEEDED'", Integer.class);
        Integer renderTerminal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM render_jobs WHERE status IN ('SUCCEEDED','FAILED','CANCELLED')",
                Integer.class);
        out.put("renderSuccessRate", renderTerminal == null || renderTerminal == 0 ? null
                : Math.round(100.0 * renderOk / renderTerminal) / 100.0);
        // candidate decision ratio (user feedback signal)
        out.put("candidatesByStatus", jdbc.queryForList(
                "SELECT status, COUNT(*) n FROM highlight_candidates GROUP BY status"));
        // storage footprint
        out.put("users", jdbc.queryForList(
                "SELECT COUNT(*) n, COALESCE(SUM(used_bytes),0) used_bytes FROM users"));
        out.put("mediaByStatus", jdbc.queryForList(
                "SELECT status, COUNT(*) n FROM media GROUP BY status"));
        return out;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        SecurityUtils.requireAdmin();
        return userMapper.selectList(null).stream().map(u -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", u.getId());
            v.put("username", u.getUsername());
            v.put("role", u.getRole());
            v.put("storageQuotaBytes", u.getStorageQuotaBytes());
            v.put("usedBytes", u.getUsedBytes());
            v.put("createdAt", u.getCreatedAt().toString());
            return v;
        }).toList();
    }

    public record QuotaRequest(Long quotaBytes) {}

    @PutMapping("/users/{id}/quota")
    public Map<String, Object> setQuota(@PathVariable Long id, @RequestBody QuotaRequest req) {
        SecurityUtils.requireAdmin();
        UserEntity user = userMapper.selectById(id);
        if (user == null) throw BusinessException.notFound("user not found");
        if (req.quotaBytes() == null || req.quotaBytes() < 0 || req.quotaBytes() > 1_000_000_000_000L) {
            throw BusinessException.badRequest("quotaBytes out of range");
        }
        // never shrink below current usage
        long effective = Math.max(req.quotaBytes(), user.getUsedBytes() == null ? 0 : user.getUsedBytes());
        userMapper.updateQuota(id, effective);
        return Map.of("id", id, "storageQuotaBytes", effective);
    }

    @GetMapping("/adapters")
    public List<Map<String, Object>> adapters() {
        SecurityUtils.requireAdmin();
        return definitionMapper.listAll().stream().map(d -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", d.getId());
            v.put("gameKey", d.getGameKey());
            v.put("displayName", d.getDisplayName());
            v.put("versions", versionMapper.listByAdapter(d.getId()).stream().map(av -> {
                Map<String, Object> avv = new LinkedHashMap<>();
                avv.put("id", av.getId());
                avv.put("adapterVersion", av.getAdapterVersion());
                avv.put("templateVersion", av.getTemplateVersion());
                avv.put("status", av.getStatus());
                avv.put("notes", av.getNotes());
                return avv;
            }).toList());
            return v;
        }).toList();
    }

    public record StatusRequest(String status, String attestation) {}

    @PutMapping("/adapters/versions/{versionId}/status")
    public Map<String, Object> setVersionStatus(@PathVariable String versionId,
                                                @RequestBody StatusRequest req) {
        SecurityUtils.requireAdmin();
        AdapterVersionEntity v = versionMapper.findById(versionId);
        if (v == null) throw BusinessException.notFound("adapter version not found");
        String next = req.status();
        Set<String> allowed = TRANSITIONS.getOrDefault(v.getStatus(), Set.of());
        if (!allowed.contains(next)) {
            throw BusinessException.conflict("CONFLICT",
                    "transition " + v.getStatus() + " -> " + next + " is not allowed");
        }
        // honest gate: VERIFIED requires a written real-game evaluation attestation
        if ("VERIFIED".equals(next)) {
            if (req.attestation() == null || req.attestation().isBlank()) {
                throw BusinessException.badRequest(
                        "VERIFIED requires an attestation of real-game evaluation (samples + metrics)");
            }
            v.setNotes((v.getNotes() == null ? "" : v.getNotes() + " | ")
                    + "VERIFIED attestation: " + req.attestation());
        }
        v.setStatus(next);
        versionMapper.updateById(v);
        return Utils.fromJson(Utils.toJson(Map.of("id", v.getId(), "status", v.getStatus())), Map.class);
    }
}
