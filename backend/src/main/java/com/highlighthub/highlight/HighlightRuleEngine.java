package com.highlighthub.highlight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure, deterministic highlight-candidate generator.
 *
 * Rules are evidence-based: a candidate exists because >= minimumCount matching
 * events occurred inside a window. The engine never invents capability claims
 * (no "you played well"), only counts and time ranges. Identical input +
 * params produce identical output.
 */
public final class HighlightRuleEngine {

    public record Params(String eventType, long windowMs, int minimumCount,
                         long paddingBeforeMs, long paddingAfterMs,
                         long mergeGapMs, long maxSegmentDurationMs, String actorConstraint) {}

    public record Candidate(long startMs, long endMs, double score, String reasonCode,
                            String reasonText, List<String> eventIds) {}

    public record Event(String id, String type, long startMs, Long endMs, String actor) {}

    private HighlightRuleEngine() {}

    public static List<Candidate> generate(List<Event> events, Params params, long durationMs) {
        List<Candidate> result = new ArrayList<>();
        if (params.minimumCount() <= 0 || params.windowMs() <= 0) {
            return result;
        }
        List<Event> matched = new ArrayList<>();
        for (Event e : events) {
            if (e.type().equals(params.eventType())
                    && (params.actorConstraint() == null || params.actorConstraint().isBlank()
                        || params.actorConstraint().equals(e.actor()))) {
                matched.add(e);
            }
        }
        matched.sort(Comparator.comparingLong(Event::startMs));

        // sliding window over matched events
        List<Candidate> raw = new ArrayList<>();
        int left = 0;
        for (int right = 0; right < matched.size(); right++) {
            while (matched.get(right).startMs() - matched.get(left).startMs() > params.windowMs()) {
                left++;
            }
            int count = right - left + 1;
            if (count >= params.minimumCount()) {
                Event first = matched.get(left);
                Event last = matched.get(right);
                long start = Math.max(0, first.startMs() - params.paddingBeforeMs());
                long lastEnd = last.endMs() != null ? last.endMs() : last.startMs();
                long end = durationMs > 0 ? Math.min(durationMs, lastEnd + params.paddingAfterMs())
                        : lastEnd + params.paddingAfterMs();
                if (end <= start) end = start + 1;
                List<String> ids = new ArrayList<>();
                long windowStart = Math.max(0, last.startMs() - params.windowMs());
                for (Event e : matched) {
                    if (e.startMs() >= windowStart && e.startMs() <= last.startMs()) {
                        ids.add(e.id());
                    }
                }
                raw.add(new Candidate(start, end, count,
                        "EVENT_CLUSTER",
                        count + " 次 " + params.eventType() + " 事件出现在 " + params.windowMs()
                                + " ms 窗口内（事件 " + first.startMs() + "–" + last.startMs() + " ms）",
                        ids));
            }
        }
        raw.sort(Comparator.comparingLong(Candidate::startMs));

        // merge overlapping candidates; never exceed maxSegmentDurationMs
        for (Candidate c : raw) {
            if (!result.isEmpty()) {
                Candidate prev = result.get(result.size() - 1);
                long gap = c.startMs() - prev.endMs();
                if (gap <= params.mergeGapMs()) {
                    long mergedEnd = Math.max(prev.endMs(), c.endMs());
                    if (mergedEnd - prev.startMs() <= params.maxSegmentDurationMs()) {
                        List<String> ids = new ArrayList<>(prev.eventIds());
                        for (String id : c.eventIds()) {
                            if (!ids.contains(id)) ids.add(id);
                        }
                        double score = Math.max(prev.score(), c.score());
                        result.set(result.size() - 1, new Candidate(prev.startMs(), mergedEnd, score,
                                prev.reasonCode(), prev.reasonText(), ids));
                        continue;
                    }
                    // exceeding the cap: drop a redundant overlapping candidate
                    if (c.startMs() < prev.endMs()) continue;
                }
            }
            if (c.endMs() - c.startMs() > params.maxSegmentDurationMs()) {
                long end = c.startMs() + params.maxSegmentDurationMs();
                result.add(new Candidate(c.startMs(), end, c.score(), c.reasonCode(),
                        c.reasonText(), c.eventIds()));
            } else {
                result.add(c);
            }
        }
        return result;
    }
}
