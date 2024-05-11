/*
 * Copyright (C) 2017 De'vID jonpIn (David Yonge-Mallo)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tlhInganHol.android.klingonassistant

import android.app.SearchManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.util.regex.Matcher

class EntryFragment : Fragment() {
    private var mEntryName: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.entry, container, false) as ViewGroup

        val resources = activity!!.resources

        val entryTitle = rootView.findViewById<View>(R.id.entry_title) as TextView
        val entryBody = rootView.findViewById<View>(R.id.entry_body) as TextView

        val uri = Uri.parse(arguments!!.getString("uri"))

        // Retrieve the entry's data.
        // Note: managedQuery is deprecated since API 11.
        val cursor =
            activity!!.managedQuery(
                uri,
                KlingonContentDatabase.Companion.ALL_KEYS,
                null,
                null,
                null
            )
        val entry =
            KlingonContentProvider.Entry(cursor, activity!!.baseContext)
        val entryId = entry.id

        // Handle alternative spellings here.
        if (entry.isAlternativeSpelling) {
            // TODO: Immediate redirect to query in entry.getDefinition();
        }

        // Get the shared preferences.
        val sharedPrefs =
            PreferenceManager.getDefaultSharedPreferences(activity!!.baseContext)

        // Set the entry's name (along with info like "slang", formatted in HTML).
        entryTitle.invalidate()
        val useKlingonFont: Boolean = Preferences.Companion.useKlingonFont(
            activity!!.baseContext
        )
        val klingonTypeface: Typeface =
            KlingonAssistant.Companion.getKlingonFontTypeface(activity!!.baseContext)
        if (useKlingonFont) {
            // Preference is set to display this in {pIqaD}!
            entryTitle.text = entry.formattedEntryNameInKlingonFont
        } else {
            // Boring transcription based on English (Latin) alphabet.
            entryTitle.text = Html.fromHtml(entry.getFormattedEntryName( /* isHtml */true))
        }
        mEntryName = entry.entryName

        // Set the colour for the entry name depending on its part of speech.
        entryTitle.setTextColor(entry.textColor)

        // Create the expanded definition.
        val pos = entry.getFormattedPartOfSpeech( /* isHtml */false)
        var expandedDefinition = pos

        // Determine whether to show the other-language definition. If shown, it is primary, and the
        // English definition is shown as secondary.
        val englishDefinition = entry.definition
        val displayOtherLanguageEntry = entry.shouldDisplayOtherLanguageDefinition()
        var englishDefinitionStart = -1
        val englishDefinitionHeader = """
            
            ${resources.getString(R.string.label_english)}: 
            """.trimIndent()
        var otherLanguageDefinition: String? = ""
        if (!displayOtherLanguageEntry) {
            // The simple case: just the English definition.
            expandedDefinition += englishDefinition
        } else {
            // We display the other-language definition as the primary one, but keep track of the location
            // of the English definition to change its font size later.
            otherLanguageDefinition = entry.otherLanguageDefinition
            expandedDefinition += otherLanguageDefinition
            englishDefinitionStart = expandedDefinition!!.length
            expandedDefinition += englishDefinitionHeader + englishDefinition
        }

        // Experimental: Display other languages.
        val showUnsupportedFeatures =
            sharedPrefs.getBoolean(
                Preferences.Companion.KEY_SHOW_UNSUPPORTED_FEATURES_CHECKBOX_PREFERENCE,  /* default */
                false
            )
        if (!entry.isAlternativeSpelling && showUnsupportedFeatures) {
            val definition_DE = entry.definition_DE
            val definition_FA = entry.definition_FA
            val definition_SV = entry.definition_SV
            val definition_RU = entry.definition_RU
            val definition_ZH_HK = entry.definition_ZH_HK
            val definition_PT = entry.definition_PT
            val definition_FI = entry.definition_FI
            val definition_FR = entry.definition_FR

            // Show the other-language definition here only if it isn't already shown as the primary
            // definition (and the experimental flag is set to true).
            if (definition_DE != "" && definition_DE != otherLanguageDefinition) {
                expandedDefinition += "\nde: $definition_DE"
            }
            if (definition_FA != "" && definition_FA != otherLanguageDefinition) {
                // Wrap Persian text with RLI/PDI pair.
                expandedDefinition += "\nfa: \u2067$definition_FA\u2069"
            }
            if (definition_SV != "" && definition_SV != otherLanguageDefinition) {
                expandedDefinition += "\nsv: $definition_SV"
            }
            if (definition_RU != "" && definition_RU != otherLanguageDefinition) {
                expandedDefinition += "\nru: $definition_RU"
            }
            if (definition_ZH_HK != "" && definition_ZH_HK != otherLanguageDefinition) {
                expandedDefinition += "\nzh-HK: $definition_ZH_HK"
            }
            if (definition_PT != "" && definition_PT != otherLanguageDefinition) {
                expandedDefinition += "\npt: $definition_PT"
            }
            if (definition_FI != "" && definition_FI != otherLanguageDefinition) {
                expandedDefinition += "\nfi: $definition_FI"
            }
            if (definition_FR != "" && definition_FR != otherLanguageDefinition) {
                expandedDefinition += "\nfr: $definition_FR"
            }
        }

        // Show the basic notes.
        var notes: String?
        if (entry.shouldDisplayOtherLanguageNotes()) {
            notes = entry.otherLanguageNotes
            if (notes!!.contains("[AUTOTRANSLATED]") || (showUnsupportedFeatures && notes != "")) {
                // In showUnsupportedFeatures mode, if the notes are suppressed, display a message so it's
                // clear that this is what's happened (i.e., not just that the non-English notes were
                // empty because they have not been translated), since the English notes will be displayed
                // in this mode.
                if (notes == "-") {
                    notes = "[English notes will not be shown in other language]"
                }
                // If notes are autotranslated, or unsupported features are enabled, display original
                // English notes also.
                notes += """
                    
                    
                    ${entry.notes}
                    """.trimIndent()
            } else if (notes == "-") {
                // If the non-English notes is just the string "-", this indicates that the display of
                // notes should be suppressed.
                notes = ""
            }
        } else {
            notes = entry.notes
        }
        if (notes != "") {
            expandedDefinition += """
                
                
                $notes
                """.trimIndent()
        }

        // If this entry is hypothetical or extended canon, display warnings.
        if (entry.isHypothetical || entry.isExtendedCanon) {
            expandedDefinition += "\n\n"
            if (entry.isHypothetical) {
                expandedDefinition += resources.getString(R.string.warning_hypothetical)
            }
            if (entry.isExtendedCanon) {
                expandedDefinition += resources.getString(R.string.warning_extended_canon)
            }
        }

        // Show synonyms, antonyms, and related entries.
        val synonyms = entry.synonyms
        val antonyms = entry.antonyms
        val seeAlso = entry.seeAlso
        if (synonyms != "") {
            expandedDefinition += """
                
                
                ${resources.getString(R.string.label_synonyms)}: $synonyms
                """.trimIndent()
        }
        if (antonyms != "") {
            expandedDefinition += """
                
                
                ${resources.getString(R.string.label_antonyms)}: $antonyms
                """.trimIndent()
        }
        if (seeAlso != "") {
            expandedDefinition += """
                
                
                ${resources.getString(R.string.label_see_also)}: $seeAlso
                """.trimIndent()
        }

        // Display components if that field is not empty, unless we are showing an analysis link, in
        // which case we want to hide the components.
        val showAnalysis = entry.isSentence || entry.isDerivative
        val components = entry.components
        if (components != "") {
            // Treat the components column of inherent plurals and their
            // singulars differently than for other entries.
            if (entry.isInherentPlural) {
                expandedDefinition +=
                    """
                    
                    
                    ${String.format(resources.getString(R.string.info_inherent_plural), components)}
                    """.trimIndent()
            } else if (entry.isSingularFormOfInherentPlural) {
                expandedDefinition +=
                    """
                    
                    
                    ${String.format(resources.getString(R.string.info_singular_form), components)}
                    """.trimIndent()
            } else if (!showAnalysis) {
                // This is just a regular list of components.
                expandedDefinition +=
                    """
                    
                    
                    ${resources.getString(R.string.label_components)}: $components
                    """.trimIndent()
            }
        }

        // Display plural information.
        if (!entry.isPlural && !entry.isInherentPlural && !entry.isSingularFormOfInherentPlural) {
            if (entry.isBeingCapableOfLanguage) {
                expandedDefinition += """
                    
                    
                    ${resources.getString(R.string.info_being)}
                    """.trimIndent()
            } else if (entry.isBodyPart) {
                expandedDefinition += """
                    
                    
                    ${resources.getString(R.string.info_body)}
                    """.trimIndent()
            }
        }

        // If the entry is a useful phrase, link back to its category.
        if (entry.isSentence) {
            val sentenceType = entry.sentenceType
            if (sentenceType != "") {
                // Put the query as a placeholder for the actual category.
                expandedDefinition +=
                    """
                    
                    
                    ${resources.getString(R.string.label_category)}: {${entry.sentenceTypeQuery}}
                    """.trimIndent()
            }
        }

        // If the entry is a sentence, make a link to analyse its components.
        if (showAnalysis) {
            var analysisQuery = entry.entryName
            if (components != "") {
                // Strip the brackets around each component so they won't be processed.
                analysisQuery += ":" + entry.partOfSpeech
                val homophoneNumber = entry.homophoneNumber
                if (homophoneNumber != -1) {
                    analysisQuery += ":$homophoneNumber"
                }
                analysisQuery +=
                    KlingonContentProvider.Entry.Companion.COMPONENTS_MARKER + components!!.replace(
                        "[{}]".toRegex(),
                        ""
                    )
            }
            expandedDefinition +=
                """
                
                
                ${resources.getString(R.string.label_analyze)}: {$analysisQuery}
                """.trimIndent()
        }

        // Show the examples.
        val examples = if (entry.shouldDisplayOtherLanguageExamples()) {
            entry.otherLanguageExamples
        } else {
            entry.examples
        }
        if (examples != "") {
            expandedDefinition += """
                
                
                ${resources.getString(R.string.label_examples)}: $examples
                """.trimIndent()
        }

        // Show the source.
        val source = entry.source
        if (source != "") {
            expandedDefinition += """
                
                
                ${resources.getString(R.string.label_sources)}: $source
                """.trimIndent()
        }

        // If this is a verb (but not a prefix or suffix), show the transitivity information.
        var transitivity: String? = ""
        if (entry.isVerb
            && sharedPrefs.getBoolean(
                Preferences.Companion.KEY_SHOW_TRANSITIVITY_CHECKBOX_PREFERENCE,  /* default */true
            )
        ) {
            // This is a verb and show transitivity preference is set to true.
            transitivity = entry.transitivityString
        }
        var transitivityStart = -1
        val transitivityHeader = """
            
            
            ${resources.getString(R.string.label_transitivity)}: 
            """.trimIndent()
        val showTransitivityInformation = transitivity != ""
        if (showTransitivityInformation) {
            transitivityStart = expandedDefinition!!.length
            expandedDefinition += transitivityHeader + transitivity
        }

        // Show the hidden notes.
        var hiddenNotes: String? = ""
        if (sharedPrefs.getBoolean(
                Preferences.Companion.KEY_SHOW_ADDITIONAL_INFORMATION_CHECKBOX_PREFERENCE,  /* default */
                true
            )
        ) {
            // Show additional information preference set to true.
            hiddenNotes = entry.hiddenNotes
        }
        var hiddenNotesStart = -1
        val hiddenNotesHeader =
            """
            
            
            ${resources.getString(R.string.label_additional_information)}: 
            """.trimIndent()
        if (hiddenNotes != "") {
            hiddenNotesStart = expandedDefinition!!.length
            expandedDefinition += hiddenNotesHeader + hiddenNotes
        }

        // Format the expanded definition, including linkifying the links to other entries.
        // We add a newline to the end of the definition because if there is a link on the final line,
        // its tap target target expands to the bottom of the TextView.
        val smallTextScale = 0.8.toFloat()
        val ssb = SpannableStringBuilder(expandedDefinition + "\n")
        if (pos != "") {
            // Italicise the part of speech.
            ssb.setSpan(StyleSpan(Typeface.ITALIC), 0, pos!!.length, FINAL_FLAGS)
        }
        if (displayOtherLanguageEntry) {
            // Reduce the size of the secondary (English) definition.
            ssb.setSpan(
                RelativeSizeSpan(smallTextScale),
                englishDefinitionStart,
                englishDefinitionStart + englishDefinitionHeader.length + englishDefinition!!.length,
                FINAL_FLAGS
            )
        }
        if (showTransitivityInformation) {
            // Reduce the size of the transitivity information.
            ssb.setSpan(
                RelativeSizeSpan(smallTextScale),
                transitivityStart,
                transitivityStart + transitivityHeader.length + transitivity!!.length,
                FINAL_FLAGS
            )
        }
        if (hiddenNotes != "") {
            // Reduce the size of the hidden notes.
            ssb.setSpan(
                RelativeSizeSpan(smallTextScale),
                hiddenNotesStart,
                hiddenNotesStart + hiddenNotesHeader.length + hiddenNotes!!.length,
                FINAL_FLAGS
            )
        }
        processMixedText(ssb, entry)

        // Display the entry name and definition.
        entryBody.invalidate()
        entryBody.text = ssb
        entryBody.movementMethod = LinkMovementMethod.getInstance()

        return rootView
    }

    // Helper function to process text that includes Klingon text.
    protected fun processMixedText(
        ssb: SpannableStringBuilder,
        entry: KlingonContentProvider.Entry?
    ) {
        val smallTextScale = 0.8.toFloat()
        val useKlingonFont: Boolean = Preferences.Companion.useKlingonFont(
            activity!!.baseContext
        )
        val klingonTypeface: Typeface =
            KlingonAssistant.Companion.getKlingonFontTypeface(activity!!.baseContext)

        var mixedText = ssb.toString()
        var m: Matcher = KlingonContentProvider.Entry.Companion.ENTRY_PATTERN.matcher(mixedText)
        while (m.find()) {
            // Strip the brackets {} to get the query.

            val query = mixedText.substring(m.start() + 1, m.end() - 1)
            val viewLauncher = LookupClickableSpan(query)

            // Process the linked entry information.
            val linkedEntry =
                KlingonContentProvider.Entry(query, activity!!.baseContext)

            // Log.d(TAG, "linkedEntry.getEntryName() = " + linkedEntry.getEntryName());

            // Delete the brackets and metadata parts of the string (which includes analysis components).
            ssb.delete(m.start() + 1 + linkedEntry.entryName.length, m.end())
            ssb.delete(m.start(), m.start() + 1)
            var end = m.start() + linkedEntry.entryName.length

            // Insert link to the category for a useful phrase.
            if ((entry != null && entry.isSentence
                        && entry.sentenceType != "") && linkedEntry.entryName == "*"
            ) {
                // Delete the "*" placeholder.
                ssb.delete(m.start(), m.start() + 1)

                // Insert the category name.
                ssb.insert(m.start(), entry.sentenceType)
                end += entry.sentenceType.length - 1
            }

            // Set the font and link.
            // This is true if this entry doesn't launch an EntryActivity. Don't link to an entry if the
            // current text isn't an entry, or there is an explicit "nolink" tag, or the link opens a URL
            // (either a source link or a direct URL link).
            val disableEntryLink =
                ((entry == null)
                        || linkedEntry.doNotLink()
                        || linkedEntry.isSource
                        || linkedEntry.isURL)
            // The last span set on a range must have FINAL_FLAGS.
            val maybeFinalFlags = if (disableEntryLink) FINAL_FLAGS else INTERMEDIATE_FLAGS
            if (linkedEntry.isSource) {
                // If possible, link to the source.
                val url = linkedEntry.url
                if (url != "") {
                    ssb.setSpan(URLSpan(url), m.start(), end, INTERMEDIATE_FLAGS)
                }
                // Names of sources are in italics.
                ssb.setSpan(
                    StyleSpan(Typeface.ITALIC), m.start(), end, maybeFinalFlags
                )
            } else if (linkedEntry.isURL) {
                // Linkify URL if there is one.
                val url = linkedEntry.url
                if (url != "") {
                    ssb.setSpan(URLSpan(url), m.start(), end, maybeFinalFlags)
                }
            } else if (useKlingonFont) {
                // Display the text using the Klingon font. Categories (which have an entry of "*") must
                // be handled specially.
                var replaceWithKlingonFontText = false
                var klingonEntryName: String? = null
                if (linkedEntry.entryName != "*") {
                    // This is just regular Klingon text. Display it in Klingon font.
                    klingonEntryName = linkedEntry.entryNameInKlingonFont
                    replaceWithKlingonFontText = true
                } else if (Preferences.Companion.useKlingonUI(
                        activity!!.baseContext
                    )
                ) {
                    // This is a category, and the option to use Klingon UI is set, so this will be in
                    // Klingon.
                    // Display it in Klingon font.
                    klingonEntryName =
                        KlingonContentProvider.Companion.convertStringToKlingonFont(entry.getSentenceType())
                    replaceWithKlingonFontText = true
                } else {
                    // This is a category, but the option to use Klingon UI is not set, so this will be in the
                    // system language.
                    // Leave it alone.
                    replaceWithKlingonFontText = false
                }
                if (replaceWithKlingonFontText) {
                    ssb.delete(m.start(), end)
                    ssb.insert(m.start(), klingonEntryName)
                    end = m.start() + klingonEntryName!!.length
                    ssb.setSpan(
                        KlingonTypefaceSpan("", klingonTypeface), m.start(), end, maybeFinalFlags
                    )
                }
            } else {
                // Klingon is in bold serif.
                ssb.setSpan(
                    StyleSpan(Typeface.BOLD), m.start(), end, INTERMEDIATE_FLAGS
                )
                ssb.setSpan(TypefaceSpan("serif"), m.start(), end, maybeFinalFlags)
            }
            // If linked entry is hypothetical or extended canon, insert a "?" in front.
            if (linkedEntry.isHypothetical || linkedEntry.isExtendedCanon) {
                ssb.insert(m.start(), "?")
                ssb.setSpan(
                    RelativeSizeSpan(smallTextScale), m.start(), m.start() + 1, INTERMEDIATE_FLAGS
                )
                ssb.setSpan(SuperscriptSpan(), m.start(), m.start() + 1, maybeFinalFlags)
                end++
            }

            // For a suffix, protect the hyphen from being separated from the rest of the suffix.
            if (ssb[m.start()] == '-') {
                // U+2011 is the non-breaking hyphen.
                ssb.replace(m.start(), m.start() + 1, "\u2011")
            }

            // Only apply colours to verbs, nouns, and affixes (exclude BLUE and WHITE).
            if (!disableEntryLink) {
                // Link to view launcher.
                ssb.setSpan(viewLauncher, m.start(), end, INTERMEDIATE_FLAGS)
            }
            // Set the colour last, so it's not overridden by other spans.
            // There is a bug in Android 6 (API 23) and 7 (API 24 and 25) which
            // messes up the sort order of the ForegroundColorSpans.
            // See: https://github.com/De7vID/klingon-assistant/issues/190
            // The work-around does not work when running on Chromebook (version
            // 61.0.3163.120).
            val oldSpans = ssb.getSpans(m.start(), end, ForegroundColorSpan::class.java)
            for (span in oldSpans) {
                ssb.removeSpan(span)
            }
            ssb.setSpan(ForegroundColorSpan(linkedEntry.textColor), m.start(), end, FINAL_FLAGS)
            val linkedPos = linkedEntry.getBracketedPartOfSpeech( /* isHtml */false)
            if (linkedPos != "" && linkedPos!!.length > 1) {
                ssb.insert(end, linkedPos)

                val rightBracketLoc = linkedPos.indexOf(")")
                if (rightBracketLoc != -1) {
                    // linkedPos is always of the form " (pos)[ (def'n N)]", we want to italicise
                    // the "pos" part only.
                    ssb.setSpan(
                        StyleSpan(Typeface.ITALIC),
                        end + 2,
                        end + rightBracketLoc,
                        FINAL_FLAGS
                    )
                }
            }

            // Rinse and repeat.
            mixedText = ssb.toString()
            m = KlingonContentProvider.Entry.Companion.ENTRY_PATTERN.matcher(mixedText)
        }
    }

    // Private class for handling clickable spans.
    private inner class LookupClickableSpan(private val mQuery: String) : ClickableSpan() {
        override fun onClick(view: View) {
            val intent = Intent(view.context, KlingonAssistant::class.java)
            intent.setAction(Intent.ACTION_SEARCH)
            // Internal searches are preceded by a plus to disable "xifan hol" mode.
            intent.putExtra(SearchManager.QUERY, "+$mQuery")

            view.context.startActivity(intent)
        }
    }

    companion object {
        private const val INTERMEDIATE_FLAGS =
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_INTERMEDIATE
        private const val FINAL_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        // Static method for constructing EntryFragment.
        fun newInstance(uri: Uri): EntryFragment {
            val entryFragment = EntryFragment()
            val args = Bundle()
            args.putString("uri", uri.toString())
            entryFragment.arguments = args
            return entryFragment
        }
    }
}
