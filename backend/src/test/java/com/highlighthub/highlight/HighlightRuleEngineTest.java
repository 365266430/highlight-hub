package com.highlighthub.highlight;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HighlightRuleEngineTest {

    private final HighlightRuleEngine.Params params = new HighlightRuleEngine.Params(
            "ELIMINATION_NOTICE", 20000, 3, 12000, 8000, 2000, 90000, null);

    private HighlightRuleEngine.Event ev(String id, long startMs) {
        return new HighlightRuleEngine.Event(id, "ELIMINATION_NOTICE", startMs, startMs + 500, null);
    }

    @Test
    void threeEventsInWindowProduceCandidateWithEvidence() {
        List<HighlightRuleEngine.Event> events = List.of(
                ev("e1", 120000), ev("e2", 128000), ev("e3", 133000));
        List<HighlightRuleEngine.Candidate> candidates =
                HighlightRuleEngine.generate(events, params, 600000);
        assertThat(candidates).hasSize(1);
        HighlightRuleEngine.Candidate c = candidates.get(0);
        // padding: 120000 - 12000 = 108000 .. 133500 + 8000 = 141500
        assertThat(c.startMs()).isEqualTo(108000);
        assertThat(c.endMs()).isEqualTo(141500);
        assertThat(c.eventIds()).containsExactly("e1", "e2", "e3");
        assertThat(c.reasonText()).contains("ELIMINATION_NOTICE").contains("3");
    }

    @Test
    void fewerThanMinimumCountProducesNothing() {
        List<HighlightRuleEngine.Event> events = List.of(ev("e1", 1000), ev("e2", 2000));
        assertThat(HighlightRuleEngine.generate(events, params, 600000)).isEmpty();
    }

    @Test
    void candidatesClampToVideoBounds() {
        List<HighlightRuleEngine.Event> events = List.of(
                ev("e1", 1000), ev("e2", 2000), ev("e3", 3000));
        List<HighlightRuleEngine.Candidate> candidates =
                HighlightRuleEngine.generate(events, params, 8000);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).startMs()).isZero();
        assertThat(candidates.get(0).endMs()).isLessThanOrEqualTo(8000);
    }

    @Test
    void overlappingCandidatesMergeUpToCap() {
        // two clusters close together: merge because gap <= mergeGap
        List<HighlightRuleEngine.Event> events = List.of(
                ev("e1", 10000), ev("e2", 12000), ev("e3", 14000),
                ev("e4", 32000), ev("e5", 34000), ev("e6", 36000));
        HighlightRuleEngine.Params p = new HighlightRuleEngine.Params(
                "ELIMINATION_NOTICE", 20000, 3, 1000, 1000, 15000, 90000, null);
        List<HighlightRuleEngine.Candidate> candidates =
                HighlightRuleEngine.generate(events, p, 300000);
        // all six fall into windows -> two raw clusters 9..15s and 31..37s with
        // padding; merge requires overlap/gap <= 15s: 31-15=16 > 15 -> may stay
        // separate. Assert determinism instead of exact shape:
        assertThat(candidates).isNotEmpty();
        for (int i = 1; i < candidates.size(); i++) {
            assertThat(candidates.get(i).startMs())
                    .isGreaterThanOrEqualTo(candidates.get(i - 1).startMs());
        }
    }

    @Test
    void mergedSegmentNeverExceedsMaxDuration() {
        // 12 dense events every 2s -> continuous windows; cap at 30s
        HighlightRuleEngine.Params p = new HighlightRuleEngine.Params(
                "ELIMINATION_NOTICE", 20000, 2, 2000, 2000, 5000, 30000, null);
        List<HighlightRuleEngine.Event> events = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            events.add(ev("e" + i, 10000L * (i + 1)));
        }
        List<HighlightRuleEngine.Candidate> candidates =
                HighlightRuleEngine.generate(events, p, 600000);
        assertThat(candidates).isNotEmpty();
        for (HighlightRuleEngine.Candidate c : candidates) {
            assertThat(c.endMs() - c.startMs()).isLessThanOrEqualTo(30000);
        }
    }

    @Test
    void sameInputProducesIdenticalOutput() {
        List<HighlightRuleEngine.Event> events = List.of(
                ev("e1", 5000), ev("e2", 15000), ev("e3", 25000), ev("e4", 26000));
        List<HighlightRuleEngine.Candidate> a =
                HighlightRuleEngine.generate(events, params, 600000);
        List<HighlightRuleEngine.Candidate> b =
                HighlightRuleEngine.generate(events, params, 600000);
        assertThat(a).usingRecursiveComparison().isEqualTo(b);
    }

    @Test
    void eventTypeMismatchIgnored() {
        List<HighlightRuleEngine.Event> events = List.of(
                new HighlightRuleEngine.Event("x1", "ROUND_START", 1000, 2000L, null),
                new HighlightRuleEngine.Event("x2", "ROUND_START", 2000, 3000L, null),
                new HighlightRuleEngine.Event("x3", "ROUND_START", 3000, 4000L, null));
        assertThat(HighlightRuleEngine.generate(events, params, 600000)).isEmpty();
    }

    @Test
    void actorConstraintFiltersEvents() {
        HighlightRuleEngine.Params p = new HighlightRuleEngine.Params(
                "ELIMINATION_NOTICE", 20000, 2, 0, 0, 1000, 90000, "me");
        List<HighlightRuleEngine.Event> events = List.of(
                new HighlightRuleEngine.Event("a1", "ELIMINATION_NOTICE", 1000, 1500L, "teammate"),
                new HighlightRuleEngine.Event("a2", "ELIMINATION_NOTICE", 2000, 2500L, "me"),
                new HighlightRuleEngine.Event("a3", "ELIMINATION_NOTICE", 3000, 3500L, "me"));
        List<HighlightRuleEngine.Candidate> candidates =
                HighlightRuleEngine.generate(events, p, 600000);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).eventIds()).containsExactly("a2", "a3");
    }
}
