package com.typefacilitator

import android.service.textservice.SpellCheckerService
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo

/**
 * Lets Android underline misspelled words in any text field using the keyboard's own offline
 * dictionary (Settings > System > Languages > Spell checker > TypeFacilitator).
 */
class TfSpellChecker : SpellCheckerService() {
    override fun createSession(): Session = object : Session() {
        override fun onCreate() { Data.init(this@TfSpellChecker) }

        override fun onGetSuggestions(textInfo: TextInfo?, suggestionsLimit: Int): SuggestionsInfo {
            val e = Data.engine
            val w = textInfo?.text
            if (e == null || w.isNullOrEmpty()) return SuggestionsInfo(0, arrayOfNulls<String>(0))
            if (e.isValid(w)) return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, arrayOfNulls<String>(0))
            val words = e.suggest(w, null, maxOf(suggestionsLimit, 1) + 1).words.filter { it != w }.take(maxOf(suggestionsLimit, 1))
            var flags = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
            if (words.isNotEmpty()) flags = flags or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS
            return SuggestionsInfo(flags, words.toTypedArray())
        }
    }
}
