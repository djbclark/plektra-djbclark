package it.palsoftware.pastiera.core.suggestions

data class SuggestionSettings(
    val textReplacementsEnabled: Boolean = true,
    val suggestionsEnabled: Boolean = true,
    val accentMatching: Boolean = true,
    val autoReplaceOnSpaceEnter: Boolean = false,
    val maxAutoReplaceDistance: Int = 1,
    // djb/autocorrect: how clearly a candidate must beat the runner-up before it is applied
    // rather than merely offered. 0.0 commits anything the shape gates permit. 0.02 matches
    // the rebuilt English dictionary: with a clean word list the margin only needs to break
    // near-ties, and a higher cutoff costs recall for nothing. See
    // [AutoReplaceController.Confidence] for what the number measures.
    val minAutoReplaceConfidence: Double = 0.02,
    val maxSuggestions: Int = 3,
    val useKeyboardProximity: Boolean = false,
    val useEditTypeRanking: Boolean = false,
    val frenchPunctuationSpacing: Boolean = false,
    val commaSpace: Boolean = false,
    val autoSpacePunctuation: String = it.palsoftware.pastiera.core.Punctuation.DEFAULT_AUTO_SPACE
)
