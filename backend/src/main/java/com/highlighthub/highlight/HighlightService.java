package com.highlighthub.highlight;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.highlighthub.analysis.AnalysisRunEntity;
import com.highlighthub.analysis.AnalysisService;
import com.highlighthub.analysis.VideoEventEntity;
import com.highlighthub.common.BusinessException;
import com.highlighthub.common.ErrorCodes;
import com.highlighthub.common.Utils;
import com.highlighthub.media.MediaEntity;
import com.highlighthub.media.MediaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the (Java-side, pure) candidate rule engine over events of a finished
 * analysis run. Re-running with different rules creates a NEW rule version and
 * run; old candidates and editing projects are never touched.
 */
@Service
public class HighlightService {
    private final HighlightRuleVersionMapper ruleMapper;
    private final HighlightRunMapper runMapper;
    private final HighlightCandidateMapper candidateMapper;
    private final AnalysisService analysisService;
    private final MediaService mediaService;

    public HighlightService(HighlightRuleVersionMapper ruleMapper, HighlightRunMapper runMapper,
                            HighlightCandidateMapper candidateMapper,
                            AnalysisService analysisService, MediaService mediaService) {
        this.ruleMapper = ruleMapper;
        this.runMapper = runMapper;
        this.candidateMapper = candidateMapper;
        this.analysisService = analysisService;
        this.mediaService = mediaService;
    }

    public record RuleParams(String eventType, Long windowMs, Integer minimumCount,
                             Long paddingBeforeMs, Long paddingAfterMs,
                             Long mergeGapMs, Long maxSegmentDurationMs, String actorConstraint) {}

    private HighlightRuleEngine.Params normalize(RuleParams p) {
        if (p.eventType() == null || p.eventType().isBlank()) {
            throw BusinessException.badRequest("eventType is required");
        }
        long windowMs = p.windowMs() == null ? 20000 : p.windowMs();
        int minCount = p.minimumCount() == null ? 3 : p.minimumCount();
        long padBefore = p.paddingBeforeMs() == null ? 12000 : p.paddingBeforeMs();
        long padAfter = p.paddingAfterMs() == null ? 8000 : p.paddingAfterMs();
        long mergeGap = p.mergeGapMs() == null ? 2000 : p.mergeGapMs();
        long maxSeg = p.maxSegmentDurationMs() == null ? 90000 : p.maxSegmentDurationMs();
        if (windowMs <= 0 || windowMs > 600_000) throw BusinessException.badRequest("windowMs out of range");
        if (minCount <= 0 || minCount > 100) throw BusinessException.badRequest("minimumCount out of range");
        if (padBefore < 0 || padAfter < 0 || padBefore > 60_000 || padAfter > 60_000) {
            throw BusinessException.badRequest("padding out of range");
        }
        if (mergeGap < 0 || mergeGap > 60_000) throw BusinessException.badRequest("mergeGapMs out of range");
        if (maxSeg < 5_000 || maxSeg > 600_000) throw BusinessException.badRequest("maxSegmentDurationMs out of range");
        return new HighlightRuleEngine.Params(p.eventType(), windowMs, minCount, padBefore, padAfter,
                mergeGap, maxSeg, p.actorConstraint());
    }

    @Transactional
    public HighlightRunEntity createRun(Long ownerId, String analysisRunId, RuleParams params) {
        AnalysisRunEntity run = analysisService.requireOwned(analysisRunId, ownerId);
        if (!"SUCCEEDED".equals(run.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, 409, "analysis run is not finished");
        }
        MediaEntity media = mediaService.requireOwnedMedia(run.getMediaId(), ownerId);
        HighlightRuleEngine.Params norm = normalize(params);
        String paramsJson = Utils.toJson(norm);
        String paramsHash = Utils.sha256Hex(paramsJson);

        // identical params reuse the immutable rule version (spec: reuse by hash)
        HighlightRuleVersionEntity ruleVersion = ruleMapper.findByParamsHash(paramsHash);
        if (ruleVersion == null) {
            ruleVersion = new HighlightRuleVersionEntity();
            ruleVersion.setName("user-rule");
            ruleVersion.setVersion(ruleMapper.nextVersion("user-rule"));
            ruleVersion.setParamsJson(paramsJson);
            ruleVersion.setParamsHash(paramsHash);
            ruleVersion.setCreatedBy(ownerId);
            ruleVersion.setCreatedAt(Utils.utcNow());
            ruleMapper.insert(ruleVersion);
        }

        HighlightRunEntity highlightRun = new HighlightRunEntity();
        highlightRun.setAnalysisRunId(analysisRunId);
        highlightRun.setRuleVersionId(ruleVersion.getId());
        highlightRun.setStatus("SUCCEEDED");
        highlightRun.setCreatedAt(Utils.utcNow());
        highlightRun.setFinishedAt(Utils.utcNow());
        runMapper.insert(highlightRun);

        List<HighlightRuleEngine.Event> events = new ArrayList<>();
        for (VideoEventEntity e : analysisService.eventsOf(analysisRunId, ownerId)) {
            events.add(new HighlightRuleEngine.Event(e.getId(), e.getType(),
                    e.getStartMs(), e.getEndMs(), e.getActor()));
        }
        List<HighlightRuleEngine.Candidate> candidates =
                HighlightRuleEngine.generate(events, norm,
                        media.getDurationMs() == null ? 0 : media.getDurationMs());
        for (HighlightRuleEngine.Candidate c : candidates) {
            HighlightCandidateEntity ce = new HighlightCandidateEntity();
            ce.setHighlightRunId(highlightRun.getId());
            ce.setAnalysisRunId(analysisRunId);
            ce.setMediaId(run.getMediaId());
            ce.setOwnerId(ownerId);
            ce.setStartMs(c.startMs());
            ce.setEndMs(c.endMs());
            ce.setScore(java.math.BigDecimal.valueOf(c.score()));
            ce.setReasonCode(c.reasonCode());
            ce.setReasonText(c.reasonText());
            ce.setEventIds(Utils.toJson(c.eventIds()));
            ce.setStatus("PENDING");
            ce.setCreatedAt(Utils.utcNow());
            ce.setUpdatedAt(Utils.utcNow());
            candidateMapper.insert(ce);
        }
        return highlightRun;
    }

    public HighlightRunEntity requireOwnedRun(String runId, Long ownerId) {
        HighlightRunEntity run = runMapper.findById(runId);
        if (run == null) throw BusinessException.notFound("highlight run not found");
        AnalysisRunEntity analysis = analysisService.requireOwned(run.getAnalysisRunId(), ownerId);
        return run;
    }

    public Map<String, Object> viewRun(HighlightRunEntity run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", run.getId());
        view.put("analysisRunId", run.getAnalysisRunId());
        view.put("ruleVersionId", run.getRuleVersionId());
        view.put("status", run.getStatus());
        view.put("createdAt", run.getCreatedAt().toString());
        HighlightRuleVersionEntity rule = ruleMapper.selectById(run.getRuleVersionId());
        if (rule != null) {
            view.put("params", Utils.fromJson(rule.getParamsJson(), Map.class));
        }
        return view;
    }

    public Map<String, Object> viewCandidate(HighlightCandidateEntity c) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", c.getId());
        view.put("highlightRunId", c.getHighlightRunId());
        view.put("analysisRunId", c.getAnalysisRunId());
        view.put("mediaId", c.getMediaId());
        view.put("startMs", c.getStartMs());
        view.put("endMs", c.getEndMs());
        view.put("score", c.getScore() == null ? null : c.getScore().doubleValue());
        view.put("reasonCode", c.getReasonCode());
        view.put("reasonText", c.getReasonText());
        view.put("eventIds", Utils.fromJson(c.getEventIds(), List.class));
        view.put("status", c.getStatus());
        return view;
    }

    /** accept/reject a candidate; ids are ownership-checked */
    @Transactional
    public HighlightCandidateEntity decide(Long ownerId, String candidateId, String status) {
        HighlightCandidateEntity c = candidateMapper.findById(candidateId);
        if (c == null || !c.getOwnerId().equals(ownerId)) {
            throw BusinessException.notFound("candidate not found");
        }
        if (!"PENDING".equals(c.getStatus()) && !status.equals(c.getStatus())) {
            // decisions are reversible by design (accept <-> reject)
        }
        if (!"ACCEPTED".equals(status) && !"REJECTED".equals(status) && !"PENDING".equals(status)) {
            throw BusinessException.badRequest("status must be ACCEPTED, REJECTED or PENDING");
        }
        c.setStatus(status);
        c.setUpdatedAt(Utils.utcNow());
        candidateMapper.updateById(c);
        return c;
    }

    public List<HighlightRunEntity> listRunsOfAnalysis(String analysisRunId, Long ownerId) {
        analysisService.requireOwned(analysisRunId, ownerId);
        return runMapper.listByAnalysis(analysisRunId);
    }

    public List<HighlightCandidateEntity> listCandidatesOfRun(String runId) {
        return candidateMapper.listByRun(runId);
    }

    public List<Map<String, Object>> acceptedCandidates(Long ownerId, String analysisRunId) {
        return candidateMapper.selectList(new QueryWrapper<HighlightCandidateEntity>()
                .eq("owner_id", ownerId)
                .eq("analysis_run_id", analysisRunId)
                .eq("status", "ACCEPTED")
                .orderByAsc("start_ms")).stream().map(this::viewCandidate).toList();
    }
}
