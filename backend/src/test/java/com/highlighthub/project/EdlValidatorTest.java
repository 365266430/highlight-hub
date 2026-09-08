package com.highlighthub.project;

import com.highlighthub.common.Utils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdlValidatorTest {

    private static final long DURATION = 60_000;

    private EdlValidator.Edl edl(String aspectMode, List<EdlValidator.Mask> masks) {
        return new EdlValidator.Edl(1, "m-1",
                List.of(new EdlValidator.Segment("s1", 1000, 3000, "", 1.0)),
                new EdlValidator.Output(aspectMode, 1280, 720, 30), masks);
    }

    @Test
    void sourceModeStillAccepted() {
        assertThatCode(() -> EdlValidator.validate(edl("SOURCE", null), DURATION, 50, 1800_000, 2160, 200))
                .doesNotThrowAnyException();
    }

    @Test
    void cropModeAccepted() {
        assertThatCode(() -> EdlValidator.validate(edl("CROP", List.of()), DURATION, 50, 1800_000, 2160, 200))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownAspectModeRejected() {
        assertThatThrownBy(() -> EdlValidator.validate(edl("FILL", null), DURATION, 50, 1800_000, 2160, 200))
                .isInstanceOf(com.highlighthub.common.BusinessException.class);
    }

    @Test
    void masksInsideFrameAccepted() {
        List<EdlValidator.Mask> masks = List.of(new EdlValidator.Mask(0.5, 0.0, 0.5, 0.2));
        assertThatCode(() -> EdlValidator.validate(edl("SOURCE", masks), DURATION, 50, 1800_000, 2160, 200))
                .doesNotThrowAnyException();
    }

    @Test
    void maskOutsideFrameRejected() {
        List<EdlValidator.Mask> masks = List.of(new EdlValidator.Mask(0.5, 0.0, 0.6, 0.2));
        assertThatThrownBy(() -> EdlValidator.validate(edl("SOURCE", masks), DURATION, 50, 1800_000, 2160, 200))
                .isInstanceOf(com.highlighthub.common.BusinessException.class)
                .hasMessageContaining("mask 0");
    }

    @Test
    void negativeMaskRejected() {
        List<EdlValidator.Mask> masks = List.of(new EdlValidator.Mask(-0.1, 0.0, 0.3, 0.2));
        assertThatThrownBy(() -> EdlValidator.validate(edl("SOURCE", masks), DURATION, 50, 1800_000, 2160, 200))
                .isInstanceOf(com.highlighthub.common.BusinessException.class);
    }

    @Test
    void jsonRoundTripKeepsMasks() {
        EdlValidator.Edl edl = edl("CROP", List.of(new EdlValidator.Mask(0.1, 0.1, 0.2, 0.2)));
        EdlValidator.Edl parsed = Utils.fromJson(Utils.toJson(edl), EdlValidator.Edl.class);
        assertThatCode(() -> EdlValidator.validate(parsed, DURATION, 50, 1800_000, 2160, 200))
                .doesNotThrowAnyException();
        assert parsed != null;
        org.assertj.core.api.Assertions.assertThat(parsed.masks()).hasSize(1);
    }
}
