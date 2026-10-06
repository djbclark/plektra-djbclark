package it.palsoftware.pastiera.core.suggestions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoReplaceConfidenceTest {

    private fun result(candidate: String, score: Double) = SuggestionResult(
        candidate = candidate,
        distance = 1,
        score = score,
        source = SuggestionSource.MAIN,
        kind = SuggestionKind.CURRENT_WORD
    )

    private fun facts(confidence: AutoReplaceController.Confidence) = AutoReplaceController.ReplaceFacts(
        word = "definately",
        lookupWord = "definately",
        top = result("definitely", 10.0),
        isKnownWord = false,
        isExactKnownWord = false,
        hasExactPrimaryCase = false,
        isRejected = false,
        isOrthographicVariant = false,
        isCaseVariant = false,
        isSafeCandidate = true,
        confidence = confidence
    )

    @Test
    fun lonelyCandidateIsCertain() {
        assertEquals(1.0, AutoReplaceController.Confidence.of(top = 5.0, runnerUp = null).value, 0.0)
    }

    @Test
    fun marginIsRelativeToTheTopScore() {
        assertEquals(0.5, AutoReplaceController.Confidence.of(top = 10.0, runnerUp = 5.0).value, 1e-9)
        assertEquals(0.0, AutoReplaceController.Confidence.of(top = 10.0, runnerUp = 10.0).value, 1e-9)
    }

    @Test
    fun nonPositiveTopScoreIsNeverConfident() {
        assertEquals(0.0, AutoReplaceController.Confidence.of(top = 0.0, runnerUp = 0.0).value, 0.0)
        assertEquals(0.0, AutoReplaceController.Confidence.of(top = -1.0, runnerUp = -2.0).value, 0.0)
    }

    @Test
    fun tooCloseToCallIsOfferedNotImposed() {
        val settings = SuggestionSettings(minAutoReplaceConfidence = 0.10)
        assertFalse(
            AutoReplaceController.shouldAutoReplace(
                facts(AutoReplaceController.Confidence.of(top = 10.0, runnerUp = 9.9)),
                settings
            )
        )
        assertTrue(
            AutoReplaceController.shouldAutoReplace(
                facts(AutoReplaceController.Confidence.of(top = 10.0, runnerUp = 5.0)),
                settings
            )
        )
    }

    @Test
    fun knownWordIsNeverOverruled() {
        assertFalse(
            AutoReplaceController.shouldAutoReplace(
                facts(AutoReplaceController.Confidence.CERTAIN).copy(isKnownWord = true),
                SuggestionSettings()
            )
        )
    }

    @Test
    fun zeroThresholdKeepsTheOldBehaviour() {
        assertTrue(
            AutoReplaceController.shouldAutoReplace(
                facts(AutoReplaceController.Confidence(0.0)),
                SuggestionSettings(minAutoReplaceConfidence = 0.0)
            )
        )
    }
}
