package com.example.learninghelper.focus

import java.text.Normalizer
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.sqrt

data class EssayConfig(
    val minWords: Int = 100,
    val minUniqueRatio: Double = 0.45,
    val maxContentWordRatio: Double = 0.09,
    val maxFunctionWordRatio: Double = 0.15,
    val minAvgWordLength: Double = 3.4,
    val maxSingleLetterWordRatio: Double = 0.10,
    val minValidWordStructureRatio: Double = 0.88,
    val minUnigramEntropyBits: Double = 3.2,
    val minConditionalBigramEntropyBits: Double = 1.75,
    val maxSentenceCosineSimilarity: Double = 0.82,
    val maxRepeatedWordTrigramRatio: Double = 0.15,
    val maxHistoricalCosineSimilarity: Double = 0.70,
) {
    companion object {
        val EMERGENCY_EXIT = EssayConfig()

        val SKIP_REASON = EssayConfig(
            minWords = 20,
            minUniqueRatio = 0.60,
            maxContentWordRatio = 0.10,
            maxFunctionWordRatio = 0.16,
            minConditionalBigramEntropyBits = 1.05,
        )
    }
}

enum class EssayRejectionReason {
    TOO_SHORT,
    LOW_VARIETY,
    WORD_SPAM,
    GIBBERISH,
    REPEATED_SENTENCES,
    REUSED_ESSAY,
}

sealed interface EssayResult {
    data object Accepted : EssayResult
    data class Rejected(val reason: EssayRejectionReason, val detail: String) : EssayResult
}

/**
 * 100% local, deterministic Roman-alphabet essay and skip-reason validator.
 * Supports English and all languages written in Roman/Latin letters (\p{IsLatin}).
 * Kept completely free of Android and org.json dependencies so it runs directly in JVM unit tests.
 */
object EssayValidator {

    private val WORD_REGEX = Regex("""[\p{IsLatin}]+(?:['-][\p{IsLatin}]+)*""")
    private val COMBINING_MARKS_REGEX = Regex("""\p{Mn}+""")
    private val WHITESPACE_RUN_REGEX = Regex("""\s+""")
    private val SENTENCE_SPLIT_REGEX = Regex("""[.!?;]+""")
    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'y')

    private val KEYBOARD_MASH_PATTERNS = listOf(
        "qwerty", "wertyu", "ertyui", "rtyuio", "tyuiop",
        "asdfgh", "sdfghj", "dfghjk", "fghjkl",
        "zxcvbn", "xcvbnm", "qazwsx", "poiuyt", "lkjhgf", "mnbvcx",
    )

    /**
     * Common 4+ letter function words across Roman-alphabet languages (English, Indonesian/Malay,
     * Spanish/French/German/etc.) that naturally appear more often than content nouns/verbs.
     */
    private val COMMON_ROMAN_FUNCTION_WORDS = setOf(
        // English
        "that", "with", "have", "this", "from", "they", "will", "would", "there", "their",
        "what", "about", "which", "when", "were", "been", "because", "could", "should", "into",
        "more", "some", "than", "then", "them", "these", "those", "very", "just", "only", "also",
        // Indonesian / Malay
        "saya", "aku", "kami", "kita", "yang", "untuk", "dengan", "karena", "tidak", "bukan",
        "harus", "akan", "dari", "pada", "dalam", "atau", "tapi", "tetapi", "sudah", "belum",
        "sedang", "lagi", "bisa", "dapat", "kalau", "jika", "oleh", "sebagai", "seperti", "masih",
        // Common European Latin function words
        "para", "como", "pero", "porque", "esta", "este", "dans", "pour", "avec", "mais",
        "nicht", "auch", "sich", "eine", "einer", "einem", "einen", "oder", "aber", "wenn",
    )

    /**
     * True inserted-span paste detector.
     *
     * Compares the common prefix and common suffix between [oldText] and [newText] so selection-replace
     * pastes are caught accurately. Rejects single edits that insert:
     * - more than [maxInsertedChars] (25) characters at once, OR
     * - [maxWhitespaceTransitions] (2) or more whitespace runs (i.e. >= 3 words in a single edit).
     *
     * Single-word swipe typing and predictive word taps on Samsung Keyboard insert at most 1 word
     * (+ 1 space = 1 whitespace run), so they pass smoothly even for long compound words.
     */
    fun isPasteAttempt(
        oldText: String,
        newText: String,
        maxInsertedChars: Int = 25,
        maxWhitespaceTransitions: Int = 2,
    ): Boolean {
        if (newText.length <= oldText.length && newText == oldText.take(newText.length)) return false

        var prefixLen = 0
        val minLen = minOf(oldText.length, newText.length)
        while (prefixLen < minLen && oldText[prefixLen] == newText[prefixLen]) {
            prefixLen++
        }

        var oldSuffixIdx = oldText.length - 1
        var newSuffixIdx = newText.length - 1
        while (oldSuffixIdx >= prefixLen && newSuffixIdx >= prefixLen && oldText[oldSuffixIdx] == newText[newSuffixIdx]) {
            oldSuffixIdx--
            newSuffixIdx--
        }

        val inserted = newText.substring(prefixLen, newSuffixIdx + 1)
        if (inserted.isEmpty()) return false
        if (inserted.length > maxInsertedChars) return true

        val trimmed = inserted.trim()
        val whitespaceRuns = if (trimmed.isEmpty()) 0 else WHITESPACE_RUN_REGEX.findAll(trimmed).count()
        return whitespaceRuns >= maxWhitespaceTransitions
    }

    /** Counts words written in Roman/Latin letters. */
    fun countRomanWords(text: String): Int = extractRawWords(text).size

    fun extractRawWords(text: String): List<String> =
        WORD_REGEX.findAll(text).map { it.value }.toList()

    /** NFD-normalizes accented Latin letters to base `a-z` for structural/vector comparisons. */
    fun normalizeAsciiLetters(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val stripped = COMBINING_MARKS_REGEX.replace(decomposed, "")
        val sb = StringBuilder(stripped.length)
        for (ch in stripped.lowercase(Locale.ROOT)) {
            when (ch) {
                in 'a'..'z' -> sb.append(ch)
                'ß' -> sb.append("ss")
                'æ' -> sb.append("ae")
                'œ' -> sb.append("oe")
                'ø' -> sb.append('o')
                'ł' -> sb.append('l')
                'đ', 'ð' -> sb.append('d')
                'þ' -> sb.append("th")
            }
        }
        return sb.toString()
    }

    fun validate(
        text: String,
        previousEssays: List<String> = emptyList(),
        config: EssayConfig = EssayConfig.EMERGENCY_EXIT,
    ): EssayResult {
        val rawWords = extractRawWords(text)
        if (rawWords.size < config.minWords) {
            return EssayResult.Rejected(
                EssayRejectionReason.TOO_SHORT,
                "Please write at least ${config.minWords} Roman-alphabet words (currently ${rawWords.size}/${config.minWords}).",
            )
        }

        val normalizedWords = rawWords.map { normalizeAsciiLetters(it) }.filter { it.isNotEmpty() }
        if (normalizedWords.size < config.minWords) {
            return EssayResult.Rejected(
                EssayRejectionReason.TOO_SHORT,
                "Please write at least ${config.minWords} valid Roman-alphabet words (currently ${normalizedWords.size}/${config.minWords}).",
            )
        }

        val totalWords = normalizedWords.size

        // Check average word length and single-letter word padding.
        val avgLength = normalizedWords.sumOf { it.length }.toDouble() / totalWords
        val singleLetterCount = normalizedWords.count { it.length == 1 }
        if (avgLength < config.minAvgWordLength || singleLetterCount.toDouble() / totalWords > config.maxSingleLetterWordRatio) {
            return EssayResult.Rejected(
                EssayRejectionReason.GIBBERISH,
                "Words are too short on average (${String.format(Locale.US, "%.1f", avgLength)} letters/word). Write complete sentences with real words.",
            )
        }

        // Check per-word Roman vowel/consonant/repetition structure.
        var structurallyValidCount = 0
        for (w in normalizedWords) {
            if (isWordStructurallyValid(w)) structurallyValidCount++
            if (hasExtremeGibberishToken(w)) {
                return EssayResult.Rejected(
                    EssayRejectionReason.GIBBERISH,
                    "Detected keyboard mashing or invalid word '$w'. Please write genuine words.",
                )
            }
        }
        val validStructureRatio = structurallyValidCount.toDouble() / totalWords
        if (validStructureRatio < config.minValidWordStructureRatio) {
            return EssayResult.Rejected(
                EssayRejectionReason.GIBBERISH,
                "Too many words look like gibberish or lack normal vowel/consonant structure. Please write clearly.",
            )
        }

        // Overall vowel ratio and character entropy checks.
        val allLetters = normalizedWords.joinToString("")
        val vowelCount = allLetters.count { it in VOWELS }
        val vowelRatio = vowelCount.toDouble() / allLetters.length.coerceAtLeast(1)
        if (vowelRatio !in 0.24..0.66) {
            return EssayResult.Rejected(
                EssayRejectionReason.GIBBERISH,
                "Unnatural vowel-to-consonant balance detected. Please write genuine sentences.",
            )
        }

        val unigramH = unigramEntropyBits(allLetters)
        val condBigramH = conditionalBigramEntropyBits(allLetters)
        if (unigramH < config.minUnigramEntropyBits || condBigramH < config.minConditionalBigramEntropyBits) {
            return EssayResult.Rejected(
                EssayRejectionReason.GIBBERISH,
                "Detected repetitive character patterns or keyboard mashing. Please write natural sentences.",
            )
        }

        // Vocabulary diversity & length-floored single-word frequency caps.
        val counts = normalizedWords.groupingBy { it }.eachCount()
        val uniqueRatio = counts.size.toDouble() / totalWords
        if (uniqueRatio < config.minUniqueRatio) {
            val pct = (uniqueRatio * 100).toInt()
            val reqPct = (config.minUniqueRatio * 100).toInt()
            return EssayResult.Rejected(
                EssayRejectionReason.LOW_VARIETY,
                "Not enough vocabulary variety ($pct% unique words, need at least $reqPct%). Avoid repeating the same words.",
            )
        }

        val maxAllowedContentCount = max(2, ceil(config.maxContentWordRatio * totalWords).toInt())
        val maxAllowedFunctionCount = max(3, ceil(config.maxFunctionWordRatio * totalWords).toInt())
        for ((word, count) in counts) {
            val isFunctionWord = word.length <= 3 || word in COMMON_ROMAN_FUNCTION_WORDS
            val limit = if (isFunctionWord) maxAllowedFunctionCount else maxAllowedContentCount
            if (count > limit) {
                return EssayResult.Rejected(
                    EssayRejectionReason.WORD_SPAM,
                    "The word '$word' is repeated too many times ($count times). Please vary your phrasing.",
                )
            }
        }

        // Word 3-gram repetition check.
        if (normalizedWords.size >= 12) {
            val wordTrigrams = normalizedWords.windowed(3) { "${it[0]} ${it[1]} ${it[2]}" }
            val trigramCounts = wordTrigrams.groupingBy { it }.eachCount()
            val repeatedInstances = trigramCounts.values.sumOf { (it - 1).coerceAtLeast(0) }
            val repeatedRatio = repeatedInstances.toDouble() / wordTrigrams.size
            if (repeatedRatio > config.maxRepeatedWordTrigramRatio) {
                return EssayResult.Rejected(
                    EssayRejectionReason.REPEATED_SENTENCES,
                    "Too many repeated phrases detected. Explain your reasoning without repeating the same clauses.",
                )
            }
        }

        // Clause / sentence character-trigram vector cosine similarity.
        val clauses = extractComparableClauses(text, normalizedWords)
        if (clauses.size >= 2) {
            val clauseVectors = clauses.map { charTrigramVector(it) }
            for (i in clauseVectors.indices) {
                for (j in i + 1 until clauseVectors.size) {
                    val sim = cosineSimilarity(clauseVectors[i], clauseVectors[j])
                    if (sim > config.maxSentenceCosineSimilarity) {
                        return EssayResult.Rejected(
                            EssayRejectionReason.REPEATED_SENTENCES,
                            "Sentence ${i + 1} and sentence ${j + 1} are too similar (${(sim * 100).toInt()}% match). Write distinct thoughts.",
                        )
                    }
                }
            }
        }

        // Historical essay novelty check using sublinear-TF content-word vectors.
        if (previousEssays.isNotEmpty()) {
            val currentVec = contentWordVector(normalizedWords)
            if (currentVec.isNotEmpty()) {
                for (past in previousEssays.take(20)) {
                    val pastWords = extractRawWords(past).map { normalizeAsciiLetters(it) }.filter { it.isNotEmpty() }
                    val pastVec = contentWordVector(pastWords)
                    val sim = cosineSimilarity(currentVec, pastVec)
                    if (sim > config.maxHistoricalCosineSimilarity) {
                        return EssayResult.Rejected(
                            EssayRejectionReason.REUSED_ESSAY,
                            "This text is too similar (${(sim * 100).toInt()}% match) to a previous reason you submitted. Write a fresh explanation for today.",
                        )
                    }
                }
            }
        }

        return EssayResult.Accepted
    }

    private fun isWordStructurallyValid(word: String): Boolean {
        if (word.isEmpty() || word.length > 25) return false
        if (word.none { it in VOWELS }) return false

        var consecutiveIdentical = 1
        var consecutiveConsonants = 0
        for (i in word.indices) {
            val ch = word[i]
            if (i > 0 && ch == word[i - 1]) {
                consecutiveIdentical++
                if (consecutiveIdentical >= 3) return false
            } else {
                consecutiveIdentical = 1
            }

            if (ch in VOWELS) {
                consecutiveConsonants = 0
            } else {
                consecutiveConsonants++
                if (consecutiveConsonants > 5) return false
            }
        }
        return true
    }

    private fun hasExtremeGibberishToken(word: String): Boolean {
        if (word.length > 30) return true
        for (pattern in KEYBOARD_MASH_PATTERNS) {
            if (word.contains(pattern)) return true
        }
        var identicalRun = 1
        for (i in 1 until word.length) {
            if (word[i] == word[i - 1]) {
                identicalRun++
                if (identicalRun >= 4) return true
            } else {
                identicalRun = 1
            }
        }
        return false
    }

    private fun extractComparableClauses(rawText: String, normalizedWords: List<String>): List<String> {
        val rawSentences = SENTENCE_SPLIT_REGEX.split(rawText)
            .map { sentence ->
                extractRawWords(sentence).map { normalizeAsciiLetters(it) }.filter { it.isNotEmpty() }
            }
            .filter { it.size >= 6 }

        val clauses = mutableListOf<String>()
        if (rawSentences.size >= 2) {
            for (s in rawSentences) {
                if (s.size > 24) {
                    s.chunked(12).filter { it.size >= 6 }.forEach { clauses += it.joinToString(" ") }
                } else {
                    clauses += s.joinToString(" ")
                }
            }
        } else if (normalizedWords.size >= 24) {
            // Single long unpunctuated run: split into non-overlapping 12-word chunks.
            normalizedWords.chunked(12).filter { it.size >= 8 }.forEach { clauses += it.joinToString(" ") }
        }
        return clauses
    }

    fun charTrigramVector(text: String): Map<String, Double> {
        val padded = " ${text.lowercase(Locale.ROOT).trim()} "
        if (padded.length < 3) return emptyMap()
        val counts = padded.windowed(3).groupingBy { it }.eachCount()
        return counts.mapValues { (_, c) -> c.toDouble() }
    }

    fun contentWordVector(normalizedWords: List<String>): Map<String, Double> {
        val contentWords = normalizedWords.filter { it.length > 3 && it !in COMMON_ROMAN_FUNCTION_WORDS }
        val source = if (contentWords.size >= 5) contentWords else normalizedWords
        val counts = source.groupingBy { it }.eachCount()
        return counts.mapValues { (_, c) -> 1.0 + ln(c.toDouble()) }
    }

    fun cosineSimilarity(a: Map<String, Double>, b: Map<String, Double>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var dot = 0.0
        for ((k, va) in a) {
            val vb = b[k] ?: continue
            dot += va * vb
        }
        val normA = sqrt(a.values.sumOf { it * it })
        val normB = sqrt(b.values.sumOf { it * it })
        return if (normA == 0.0 || normB == 0.0) 0.0 else dot / (normA * normB)
    }

    fun unigramEntropyBits(letters: String): Double {
        if (letters.isEmpty()) return 0.0
        val n = letters.length.toDouble()
        val counts = letters.groupingBy { it }.eachCount()
        var h = 0.0
        for (c in counts.values) {
            val p = c / n
            h -= p * log2(p)
        }
        return h
    }

    fun conditionalBigramEntropyBits(letters: String): Double {
        if (letters.length < 2) return 0.0
        val unigramH = unigramEntropyBits(letters)
        val bigrams = letters.windowed(2)
        val n = bigrams.size.toDouble()
        val counts = bigrams.groupingBy { it }.eachCount()
        var jointH = 0.0
        for (c in counts.values) {
            val p = c / n
            jointH -= p * log2(p)
        }
        return (jointH - unigramH).coerceAtLeast(0.0)
    }
}
