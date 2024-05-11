/*
 * Copyright (C) 2014 De'vID jonpIn (David Yonge-Mallo)
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
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.preference.PreferenceManager
import android.provider.BaseColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import java.util.regex.Pattern

/** Provides access to the dictionary database.  */
class KlingonContentProvider : ContentProvider() {
    // The actual Klingon Content Database.
    private var mContentDatabase: KlingonContentDatabase? = null

    override fun onCreate(): Boolean {
        mContentDatabase = KlingonContentDatabase(context)
        return true
    }

    /**
     * Handles all the database searches and suggestion queries from the Search Manager. When
     * requesting a specific entry, the uri alone is required. When searching all of the database for
     * matches, the selectionArgs argument must carry the search query as the first element. All other
     * arguments are ignored.
     */
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        // Use the UriMatcher to see what kind of query we have and format the db query accordingly

        when (sURIMatcher.match(uri)) {
            SEARCH_SUGGEST -> {
                // Uri has SUGGEST_URI_PATH_QUERY, i.e., "search_suggest_query".
                requireNotNull(selectionArgs) { "selectionArgs must be provided for the Uri: $uri" }
                return getSuggestions(selectionArgs[0])
            }

            SEARCH_ENTRIES -> {
                // Uri has "/lookup".
                requireNotNull(selectionArgs) { "selectionArgs must be provided for the Uri: $uri" }
                return search(selectionArgs[0])
            }

            GET_ENTRY -> return getEntry(uri)
            REFRESH_SHORTCUT -> return refreshShortcut(uri)
            GET_ENTRY_BY_ID -> {
                // This case was added to allow getting the entry by its id.
                var entryId: String? = null
                if (uri.pathSegments.size > 1) {
                    entryId = uri.lastPathSegment
                }
                // Log.d(TAG, "entryId = " + entryId);
                return getEntryById(entryId, projection)
            }

            GET_RANDOM_ENTRY -> return getRandomEntry(projection)
            else -> throw IllegalArgumentException("Unknown Uri: $uri")
        }
    }

    // (1) - This is the first way the database can be queried.
    // Called when uri has SUGGEST_URI_PATH_QUERY, i.e., "search_suggest_query".
    // This populates the dropdown list from the search box.
    private fun getSuggestions(query: String): Cursor? {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "getSuggestions called with query: \"$query\"")
        }
        if (query == "") {
            return null
        }

        // First, get all the potentially relevant entries. Include all columns of data.
        val rawCursor = mContentDatabase!!.getEntryMatches(query)

        // Format to two columns for display.
        val formattedCursor = MatrixCursor(SUGGESTION_COLUMNS)
        if (rawCursor!!.count != 0) {
            rawCursor.moveToFirst()
            do {
                formattedCursor.addRow(formatEntryForSearchResults(rawCursor))
            } while (rawCursor.moveToNext())
        }
        return formattedCursor
    }

    private fun formatEntryForSearchResults(cursor: Cursor?): Array<Any> {
        // Format the search results for display here. These are the two-line dropdown results from the
        // search box. We fully indent suffixes, but only half-indent verbs when they have a prefix.
        val entry = Entry(cursor, context)
        val entryId = entry.id
        val indent1 = if (entry.isIndented) (if (entry.isVerb) "  " else "    ") else ""
        val indent2 = if (entry.isIndented) (if (entry.isVerb) "   " else "      ") else ""
        val entryName = indent1 + entry.getFormattedEntryName( /* isHtml */false)
        val formattedDefinition = indent2 + entry.getFormattedDefinition( /* isHtml */false)

        // TODO: Format the "alt" results.

        // Search suggestions must have exactly four columns in exactly this format.
        return arrayOf(
            entryId,  // _id
            entryName,  // text1
            formattedDefinition,  // text2
            entryId,  // intent_data (included when clicking on item)
        )
    }

    // (2) - This is the second way the database can be queried.
    // Called when uri has "/lookup".
    // Either we're following a link, or the user has pressed the "Go" button from search.
    private fun search(query: String): Cursor? {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "search called with query: $query")
        }

        return mContentDatabase!!.getEntryMatches(query)
    }

    private fun getEntry(uri: Uri): Cursor? {
        // Log.d(TAG, "getEntry called with uri: " + uri.toString());
        val rowId = uri.lastPathSegment
        return mContentDatabase!!.getEntry(rowId, KlingonContentDatabase.Companion.ALL_KEYS)
    }

    private fun refreshShortcut(uri: Uri): Cursor? {
        /*
     * This won't be called with the current implementation, but if we include {@link
     * SearchManager#SUGGEST_COLUMN_SHORTCUT_ID} as a column in our suggestions table, we could
     * expect to receive refresh queries when a shortcutted suggestion is displayed in Quick Search
     * Box. In which case, this method will query the table for the specific entry, using the given
     * item Uri and provide all the columns originally provided with the suggestion query.
     */
        val rowId = uri.lastPathSegment

        /*
     * String[] columns = new String[] { KlingonContentDatabase.KEY_ID,
     * KlingonContentDatabase.KEY_ENTRY_NAME, KlingonContentDatabase.KEY_DEFINITION, // Add other
     * keys here. SearchManager.SUGGEST_COLUMN_SHORTCUT_ID,
     * SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID};
     */
        return mContentDatabase!!.getEntry(rowId, KlingonContentDatabase.Companion.ALL_KEYS)
    }

    /** Retrieve a single entry by its _id.  */
    private fun getEntryById(entryId: String?, projection: Array<String>?): Cursor? {
        // Log.d(TAG, "getEntryById called with entryid: " + entryId);
        return mContentDatabase!!.getEntryById(entryId, projection)
    }

    /** Get a single random entry.  */
    private fun getRandomEntry(projection: Array<String>?): Cursor? {
        return mContentDatabase!!.getRandomEntry(projection)
    }

    /**
     * This method is required in order to query the supported types. It's also useful in our own
     * query() method to determine the type of Uri received.
     */
    override fun getType(uri: Uri): String? {
        return when (sURIMatcher.match(uri)) {
            SEARCH_ENTRIES -> ENTRIES_MIME_TYPE
            GET_ENTRY -> DEFINITION_MIME_TYPE
            SEARCH_SUGGEST -> SearchManager.SUGGEST_MIME_TYPE
            REFRESH_SHORTCUT -> SearchManager.SHORTCUT_MIME_TYPE
            else -> throw IllegalArgumentException("Unknown URL $uri")
        }
    }

    // Other required implementations...
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw UnsupportedOperationException()
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        throw UnsupportedOperationException()
    }

    // This class is for managing entries.
    class Entry {
        // The logging tag can be at most 23 characters. "KlingonContentProvider.Entry" was too long.
        var TAG: String = "KCP.Entry"

        // Context.
        private var mContext: Context?

        // Get the _id of the entry.
        // The raw data for the entry.
        // private Uri mUri = null;
        var id: Int = -1
            private set
        var entryName: String? = ""
            private set
        var partOfSpeech: String = ""
            private set
        var definition: String? = ""
            private set
        var synonyms: String = ""
            private set
        var antonyms: String = ""
            private set
        var seeAlso: String = ""
            private set
        var notes: String = ""
            private set
        var hiddenNotes: String = ""
            private set
        var components: String = ""
            private set
        var examples: String = ""
            private set
        var searchTags: String = ""
            private set
        var source: String = ""
            private set

        // Localised definitions.
        private var mDefinition_DE: String? = ""
        private var mNotes_DE: String? = ""
        private var mExamples_DE: String? = ""
        private var mSearchTags_DE: String? = ""
        private var mDefinition_FA: String? = ""
        private var mNotes_FA: String? = ""
        private var mExamples_FA: String? = ""
        private var mSearchTags_FA: String? = ""
        private var mDefinition_SV: String? = ""
        private var mNotes_SV: String? = ""
        private var mExamples_SV: String? = ""
        private var mSearchTags_SV: String? = ""
        private var mDefinition_RU: String? = ""
        private var mNotes_RU: String? = ""
        private var mExamples_RU: String? = ""
        private var mSearchTags_RU: String? = ""
        private var mDefinition_ZH_HK: String? = ""
        private var mNotes_ZH_HK: String? = ""
        private var mExamples_ZH_HK: String? = ""
        private var mSearchTags_ZH_HK: String? = ""
        private var mDefinition_PT: String? = ""
        private var mNotes_PT: String? = ""
        private var mExamples_PT: String? = ""
        private var mSearchTags_PT: String? = ""
        private var mDefinition_FI: String? = ""
        private var mNotes_FI: String? = ""
        private var mExamples_FI: String? = ""
        private var mSearchTags_FI: String? = ""
        private var mDefinition_FR: String? = ""
        private var mNotes_FR: String? = ""
        private var mExamples_FR: String? = ""
        private var mSearchTags_FR: String? = ""

        // Part of speech metadata.
        enum class BasePartOfSpeechEnum {
            NOUN,
            VERB,
            ADVERBIAL,
            CONJUNCTION,
            QUESTION,
            SENTENCE,
            EXCLAMATION,
            SOURCE,
            URL,
            UNKNOWN
        }

        private val basePartOfSpeechAbbreviations = arrayOf(
            "n", "v", "adv", "conj", "ques", "sen", "excl", "src", "url", "???"
        )
        var basePartOfSpeech: BasePartOfSpeechEnum = BasePartOfSpeechEnum.UNKNOWN
            private set

        // Verb attributes.
        enum class VerbTransitivityType {
            TRANSITIVE,
            INTRANSITIVE,
            STATIVE,
            AMBITRANSITIVE,
            UNKNOWN,
            HAS_TYPE_5_NOUN_SUFFIX
        }

        var transitivity: VerbTransitivityType = VerbTransitivityType.UNKNOWN
            private set
        var mTransitivityConfirmed: Boolean = false

        // Noun attributes.
        private enum class NounType {
            GENERAL,
            NUMBER,
            NAME,
            PRONOUN
        }

        private var mNounType = NounType.GENERAL
        var isInherentPlural: Boolean = false
        var isSingularFormOfInherentPlural: Boolean = false

        // This noun is already plural (e.g., the entry already has plural suffixes).
        // This is different from an inherent plural, which acts like a singular object
        // for the purposes of verb agreement.
        var isPlural: Boolean = false

        // Sentence types.
        private enum class SentenceType {
            PHRASE,
            EMPIRE_UNION_DAY,
            CURSE_WARFARE,
            IDIOM,
            NENTAY,
            PROVERB,
            MILITARY_CELEBRATION,
            REJECTION,
            REPLACEMENT_PROVERB,
            SECRECY_PROVERB,
            TOAST,
            LYRICS,
            BEGINNERS_CONVERSATION,
            JOKE
        }

        private var mSentenceType = SentenceType.PHRASE

        // Exclamation attributes.
        var isEpithet: Boolean = false

        // Categories of words and phrases.
        var mIsAnimal: Boolean = false
        var isArchaic: Boolean = false
        var isBeingCapableOfLanguage: Boolean = false
        var isBodyPart: Boolean = false
        var isDerivative: Boolean = false
        var isRegional: Boolean = false
        var isFoodRelated: Boolean = false
        var isInvective: Boolean = false
        var isPlaceName: Boolean = false
        var mIsPrefix: Boolean = false
        var isSlang: Boolean = false
        var mIsSuffix: Boolean = false
        var isWeaponsRelated: Boolean = false

        // Additional metadata.
        var isAlternativeSpelling: Boolean = false
        var isFictionalEntity: Boolean = false
        var isHypothetical: Boolean = false
        var isExtendedCanon: Boolean = false
        var mDoNotLink: Boolean = false

        // For display purposes.
        var isIndented: Boolean = false

        // If there are multiple entries with identitical entry names,
        // they are distinguished with numbers. However, not all entries display
        // them, for various reasons.
        var homophoneNumber: Int = -1
        var mShowHomophoneNumber: Boolean = true

        // Link can be to an URL.
        var mURL: String = ""

        /**
         * Constructor
         *
         *
         * This creates an entry based only on the given query and definition. Note that the database
         * is NOT queried when this constructor is called. This constructor is used to create a fake
         * entry for the purpose of displaying KWOTD entries which are not in our database.
         *
         * @param query A query of the form "entryName:basepos:metadata".
         */
        constructor(query: String?, definition: String, context: Context?) {
            // Log.d(TAG, "Entry constructed from query: \"" + query + "\"");
            entryName = query
            mContext = context

            // Get analysis components, if any.
            val cmLoc = entryName!!.indexOf(COMPONENTS_MARKER)
            if (cmLoc != -1) {
                components = entryName!!.substring(cmLoc + COMPONENTS_MARKER.length)
                entryName = entryName!!.substring(0, cmLoc)
            }

            // Get part of speech and attribute information.
            val colonLoc = entryName!!.indexOf(':')
            if (colonLoc != -1) {
                partOfSpeech = entryName!!.substring(colonLoc + 1)
                entryName = entryName!!.substring(0, colonLoc)
            }

            if (this.definition != null) {
                this.definition = definition
            }

            // Note: The homophone number may be overwritten by this function call.
            processMetadata()
        }

        /**
         * Constructor
         *
         *
         * This creates an entry based on the given query. Note that the database is NOT queried when
         * this constructor is called. This constructor is used to create a fake placeholder entry for
         * the purpose of querying for a real entry from the database.
         */
        constructor(query: String?, context: Context?) : this(query, null, context)

        /**
         * Constructor
         *
         *
         * This creates an entry based on the given cursor, which is assumed to be the result of a
         * query to the database.
         *
         * @param cursor A cursor with position at the desired entry.
         */
        constructor(cursor: Cursor?, context: Context?) {
            mContext = context

            id = cursor!!.getInt(KlingonContentDatabase.Companion.COLUMN_ID)
            entryName = cursor.getString(KlingonContentDatabase.Companion.COLUMN_ENTRY_NAME)
            partOfSpeech = cursor.getString(KlingonContentDatabase.Companion.COLUMN_PART_OF_SPEECH)

            // TODO: Make this dependent on the chosen language.
            definition = cursor.getString(KlingonContentDatabase.Companion.COLUMN_DEFINITION)
            notes = cursor.getString(KlingonContentDatabase.Companion.COLUMN_NOTES)
            searchTags = cursor.getString(KlingonContentDatabase.Companion.COLUMN_SEARCH_TAGS)

            mDefinition_DE = cursor.getString(KlingonContentDatabase.Companion.COLUMN_DEFINITION_DE)
            mNotes_DE = cursor.getString(KlingonContentDatabase.Companion.COLUMN_NOTES_DE)
            mExamples_DE = cursor.getString(KlingonContentDatabase.Companion.COLUMN_EXAMPLES_DE)
            mSearchTags_DE =
                cursor.getString(KlingonContentDatabase.Companion.COLUMN_SEARCH_TAGS_DE)

            mDefinition_FA = cursor.getString(KlingonContentDatabase.Companion.COLUMN_DEFINITION_FA)
            mNotes_FA = cursor.getString(KlingonContentDatabase.Companion.COLUMN_NOTES_FA)
            mExamples_FA = cursor.getString(KlingonContentDatabase.Companion.COLUMN_EXAMPLES_FA)
            mSearchTags_FA =
                cursor.getString(KlingonContentDatabase.Companion.COLUMN_SEARCH_TAGS_FA)

            mDefinition_SV = cursor.getString(KlingonContentDatabase.Companion.COLUMN_DEFINITION_SV)
            mNotes_SV = cursor.getString(KlingonContentDatabase.Companion.COLUMN_NOTES_SV)
            mExamples_SV = cursor.getString(KlingonContentDatabase.Companion.COLUMN_EXAMPLES_SV)
            mSearchTags_SV =
                cursor.getString(KlingonContentDatabase.Companion.COLUMN_SEARCH_TAGS_SV)

            mDefinition_RU = cursor.getString(KlingonContentDatabase.Companion.COLUMN_DEFINITION_RU)
            mNotes_RU = cursor.getString(KlingonContentDatabase.Companion.COLUMN_NOTES_RU)
            mExamples_RU = cursor.getString(KlingonContentDatabase.Companion.COLUMN_EXAMPLES_RU)
            mSearchTags_RU =
                cursor.getString(KlingonContentDatabase.Companion.COLUMN_SEARCH_TAGS_RU)

            mDefinition_ZH_HK =
                cursor.getString(KlingonContentDatabase.Companion.COLUMN_DEFINITION_ZH_HK)
            mNotes_ZH_HK = cursor.getString(KlingonContentDatabase.Companion.COLUMN_NOTES_ZH_HK)
            mExamples_ZH_HK =
                cursor.getString(KlingonContentDatabase.Companion.COLUMN_EXAMPLES_ZH_HK)
            mSearchTags_ZH_HK =
                cursor.getString(KlingonContentDatabase.Companion.COLUMN_SEARCH_TAGS_ZH_HK)

            mDefinition_PT = cursor.getString(KlingonContentDatabase.Companion.COLUMN_DEFINITION_PT)
            mNotes_PT = cursor.getString(KlingonContentDatabase.Companion.COLUMN_NOTES_PT)
            mExamples_PT = cursor.getString(KlingonContentDatabase.Companion.COLUMN_EXAMPLES_PT)
            mSearchTags_PT =
                cursor.getString(KlingonContentDatabase.Companion.COLUMN_SEARCH_TAGS_PT)

            mDefinition_FI = cursor.getString(KlingonContentDatabase.Companion.COLUMN_DEFINITION_FI)
            mNotes_FI = cursor.getString(KlingonContentDatabase.Companion.COLUMN_NOTES_FI)
            mExamples_FI = cursor.getString(KlingonContentDatabase.Companion.COLUMN_EXAMPLES_FI)
            mSearchTags_FI =
                cursor.getString(KlingonContentDatabase.Companion.COLUMN_SEARCH_TAGS_FI)

            mDefinition_FR = cursor.getString(KlingonContentDatabase.Companion.COLUMN_DEFINITION_FR)
            mNotes_FR = cursor.getString(KlingonContentDatabase.Companion.COLUMN_NOTES_FR)
            mExamples_FR = cursor.getString(KlingonContentDatabase.Companion.COLUMN_EXAMPLES_FR)
            mSearchTags_FR =
                cursor.getString(KlingonContentDatabase.Companion.COLUMN_SEARCH_TAGS_FR)

            synonyms = cursor.getString(KlingonContentDatabase.Companion.COLUMN_SYNONYMS)
            antonyms = cursor.getString(KlingonContentDatabase.Companion.COLUMN_ANTONYMS)
            seeAlso = cursor.getString(KlingonContentDatabase.Companion.COLUMN_SEE_ALSO)
            hiddenNotes = cursor.getString(KlingonContentDatabase.Companion.COLUMN_HIDDEN_NOTES)
            components = cursor.getString(KlingonContentDatabase.Companion.COLUMN_COMPONENTS)
            examples = cursor.getString(KlingonContentDatabase.Companion.COLUMN_EXAMPLES)
            source = cursor.getString(KlingonContentDatabase.Companion.COLUMN_SOURCE)

            // The homophone number is -1 by default.
            // Note: The homophone number may be overwritten by this function call.
            processMetadata()
        }

        // Helper method to process metadata.
        private fun processMetadata() {
            // Process metadata from part of speech.

            var base = partOfSpeech
            var attributes = arrayOf<String>()
            val colonLoc = partOfSpeech.indexOf(':')
            if (colonLoc != -1) {
                base = partOfSpeech.substring(0, colonLoc)
                attributes = partOfSpeech.substring(colonLoc + 1).split(",".toRegex())
                    .dropLastWhile { it.isEmpty() }
                    .toTypedArray()
            }

            // First, find the base part of speech.
            basePartOfSpeech = BasePartOfSpeechEnum.UNKNOWN
            if (base == "") {
                // Do nothing if base part of speech is empty.
                // Log.w(TAG, "{" + mEntryName + "} has empty part of speech.");
            } else {
                for (i in basePartOfSpeechAbbreviations.indices) {
                    if (base == basePartOfSpeechAbbreviations[i]) {
                        basePartOfSpeech = BasePartOfSpeechEnum.entries[i]
                    }
                }
                if (basePartOfSpeech == BasePartOfSpeechEnum.UNKNOWN) {
                    // Log warning if part of speech could not be determined.
                    Log.w(
                        TAG,
                        "{" + entryName + "} has unrecognised part of speech: \"" + partOfSpeech + "\""
                    )
                }
            }

            // Now, get other attributes from the part of speech metadata.
            for (attr in attributes) {
                // Note prefixes and suffixes.

                if (attr == "pref") {
                    mIsPrefix = true
                } else if (attr == "suff") {
                    mIsSuffix = true
                } else if (attr == "indent") {
                    // This attribute is used internally to indent affixes which are attached to a word, and
                    // to half-indent verbs with prefixes.
                    isIndented = true

                    // Verb attributes.
                } else if (attr == "ambi") {
                    // All ambitransitive verbs are considered confirmed, since they are never marked as such
                    // otherwise.
                    transitivity = VerbTransitivityType.AMBITRANSITIVE
                    mTransitivityConfirmed = true
                } else if (attr == "i") {
                    transitivity = VerbTransitivityType.INTRANSITIVE
                } else if (attr == "i_c") {
                    transitivity = VerbTransitivityType.INTRANSITIVE
                    mTransitivityConfirmed = true
                } else if (attr == "is") {
                    // All stative verbs are considered confirmed, since they are all of the form "to be [a
                    // quality]". They behave like confirmed intransitive verbs in most cases, except in the
                    // analysis of verbs with a type 5 noun suffix attached.
                    transitivity = VerbTransitivityType.STATIVE
                    mTransitivityConfirmed = true
                } else if (attr == "t") {
                    transitivity = VerbTransitivityType.TRANSITIVE
                } else if (attr == "t_c") {
                    transitivity = VerbTransitivityType.TRANSITIVE
                    mTransitivityConfirmed = true
                } else if (attr == "n5") {
                    // This is an attribute which does not appear in the database, but can be assigned to a
                    // query to find only verbs which are attached to a type 5 noun suffix (verbs acting
                    // adjectivally).
                    transitivity = VerbTransitivityType.HAS_TYPE_5_NOUN_SUFFIX

                    // Noun attributes.
                } else if (attr == "name") {
                    mNounType = NounType.NAME
                    mShowHomophoneNumber = false
                } else if (attr == "num") {
                    mNounType = NounType.NUMBER
                } else if (attr == "pro") {
                    mNounType = NounType.PRONOUN
                } else if (attr == "inhpl") {
                    isInherentPlural = true
                } else if (attr == "inhps") {
                    isSingularFormOfInherentPlural = true
                } else if (attr == "plural") {
                    isPlural = true

                    // Sentence attributes.
                } else if (attr == "eu") {
                    mSentenceType = SentenceType.EMPIRE_UNION_DAY
                } else if (attr == "mv") {
                    mSentenceType = SentenceType.CURSE_WARFARE
                } else if (attr == "idiom") {
                    mSentenceType = SentenceType.IDIOM
                } else if (attr == "nt") {
                    mSentenceType = SentenceType.NENTAY
                } else if (attr == "phr") {
                    mSentenceType = SentenceType.PHRASE
                } else if (attr == "prov") {
                    mSentenceType = SentenceType.PROVERB
                } else if (attr == "Ql") {
                    mSentenceType = SentenceType.MILITARY_CELEBRATION
                } else if (attr == "rej") {
                    mSentenceType = SentenceType.REJECTION
                } else if (attr == "rp") {
                    mSentenceType = SentenceType.REPLACEMENT_PROVERB
                } else if (attr == "sp") {
                    mSentenceType = SentenceType.SECRECY_PROVERB
                } else if (attr == "toast") {
                    mSentenceType = SentenceType.TOAST
                } else if (attr == "lyr") {
                    mSentenceType = SentenceType.LYRICS
                } else if (attr == "bc") {
                    mSentenceType = SentenceType.BEGINNERS_CONVERSATION
                } else if (attr == "joke") {
                    mSentenceType = SentenceType.JOKE

                    // Exclamation attributes.
                } else if (attr == "epithet") {
                    isEpithet = true

                    // TODO: Determine whether epithets are treated as if they always implicitly refer to
                    // beings capable of language, and if so, set mIsBeingCapableOfLanguage to true here.

                    // Categories.
                } else if (attr == "anim") {
                    mIsAnimal = true
                } else if (attr == "archaic") {
                    isArchaic = true
                } else if (attr == "being") {
                    isBeingCapableOfLanguage = true
                } else if (attr == "body") {
                    isBodyPart = true
                } else if (attr == "deriv") {
                    isDerivative = true
                } else if (attr == "reg") {
                    isRegional = true
                } else if (attr == "food") {
                    isFoodRelated = true
                } else if (attr == "inv") {
                    isInvective = true
                } else if (attr == "place") {
                    isPlaceName = true
                } else if (attr == "slang") {
                    isSlang = true
                } else if (attr == "weap") {
                    isWeaponsRelated = true

                    // Additional metadata.
                } else if (attr == "alt") {
                    isAlternativeSpelling = true
                } else if (attr == "fic") {
                    isFictionalEntity = true
                } else if (attr == "hyp") {
                    isHypothetical = true
                } else if (attr == "extcan") {
                    isExtendedCanon = true
                } else if (attr == "nolink") {
                    mDoNotLink = true
                } else if (attr == "noanki") {
                    // This is an attribute which does not appear in the database and is not used by this app.
                    // It's used by the export_to_anki.py script to exclude entries which should be skipped
                    // when generating an Anki deck.
                } else if (attr == "nodict") {
                    // This is an attribute which does not appear in the database and is not used by this app.
                    // It's used when generating word lists to skip over entries which would not appear in a
                    // print dictionary.
                } else if (attr == "klcp1") {
                    // This is an attribute which does not appear in the database and is not used by this app.
                    // It's used by the export_to_anki.py script to tag KLCP1 (Klingon Language Certification
                    // Program level 1) vocabulary.
                } else if (attr == "terran") {
                    // This is an attribute which does not appear in the database and is not used by this app.
                    // It's used to tag Terran loanwords so they can be grouped separatedly.

                    // We have only a few homophonous entries.
                } else if (attr == "1") {
                    homophoneNumber = 1
                } else if (attr == "2") {
                    homophoneNumber = 2
                } else if (attr == "3") {
                    homophoneNumber = 3
                } else if (attr == "4") {
                    homophoneNumber = 4
                } else if (attr == "5") {
                    // Nothing should go as high as even 4.
                    homophoneNumber = 5
                    // Same as above, but the number is hidden.
                } else if (attr == "1h") {
                    homophoneNumber = 1
                    mShowHomophoneNumber = false
                } else if (attr == "2h") {
                    homophoneNumber = 2
                    mShowHomophoneNumber = false
                } else if (attr == "3h") {
                    homophoneNumber = 3
                    mShowHomophoneNumber = false
                } else if (attr == "4h") {
                    homophoneNumber = 4
                    mShowHomophoneNumber = false
                } else if (attr == "5h") {
                    homophoneNumber = 5
                    mShowHomophoneNumber = false

                    // If this is an URL link, the attribute is the URL.
                } else if (isURL) {
                    mURL = attr

                    // No match to attributes.
                } else {
                    Log.d(TAG, "{" + entryName + "} has unrecognised attribute: \"" + attr + "\"")
                }
            }
        }

        private fun maybeItalics(s: String, isHtml: Boolean): String {
            if (isHtml) {
                return "<i>$s</i>"
            }
            return s
        }

        // Get the name of the entry, optionally as an HTML string. Used in the entry title, share
        // intent text, and results lists.
        fun getFormattedEntryName(isHtml: Boolean): String {
            // Note that an entry may have more than one of the archaic,
            // regional, or slang attributes.
            var attr = ""
            val separator = mContext!!.resources.getString(R.string.attribute_separator)
            if (isArchaic) {
                attr =
                    maybeItalics(mContext!!.resources.getString(R.string.attribute_archaic), isHtml)
            }
            if (isRegional) {
                if (attr != "") {
                    attr += separator
                }
                attr +=
                    maybeItalics(
                        mContext!!.resources.getString(R.string.attribute_regional),
                        isHtml
                    )
            }
            if (isSlang) {
                if (attr != "") {
                    attr += separator
                }
                attr += maybeItalics(
                    mContext!!.resources.getString(R.string.attribute_slang),
                    isHtml
                )
            }
            // While whether an entry is a name isn't actually an attribute, treat it as one.
            if (isName) {
                if (attr != "") {
                    attr += separator
                }
                attr += maybeItalics(mContext!!.resources.getString(R.string.pos_name), isHtml)
            }
            if (attr != "") {
                attr = if (isHtml) {
                    // Should also set color to android:textColorSecondary.
                    " <small>($attr)</small>"
                } else {
                    " ($attr)"
                }
            }

            // Mark hypothetical and extended canon entries with a "?".
            var formattedEntryName = entryName + attr
            if (isHypothetical || isExtendedCanon) {
                formattedEntryName = if (isHtml) {
                    "<sup><small>?</small></sup>$formattedEntryName"
                } else {
                    "?$formattedEntryName"
                }
            }

            // Return name plus possible attributes.
            return formattedEntryName
        }

        val formattedEntryNameInKlingonFont: SpannableStringBuilder
            // Get the name of the entry written in {pIqaD} with its attributes.
            get() {
                val entryName = entryNameInKlingonFont
                var ssb = SpannableStringBuilder(entryName)
                val klingonTypeface: Typeface =
                    KlingonAssistant.Companion.getKlingonFontTypeface(mContext)
                ssb.setSpan(
                    KlingonTypefaceSpan("", klingonTypeface),
                    0,
                    entryName.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // TODO: Refactor and combine this with the logic in getFormattedEntryName().
                // TODO: Italicise the attributes.
                var attr = SpannableStringBuilder("")
                val separator = mContext!!.resources.getString(R.string.attribute_separator)
                val archaic = mContext!!.resources.getString(R.string.attribute_archaic)
                val regional = mContext!!.resources.getString(R.string.attribute_regional)
                val slang = mContext!!.resources.getString(R.string.attribute_slang)
                val name = mContext!!.resources.getString(R.string.pos_name)
                var start = 0
                var end = 0
                if (isArchaic) {
                    end = start + archaic.length
                    attr.append(archaic)
                    attr.setSpan(
                        StyleSpan(Typeface.ITALIC),
                        start,
                        start + archaic.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    start = end
                }
                if (isRegional) {
                    if (attr.toString() != "") {
                        attr.append(separator)
                        start += separator.length
                    }
                    end = start + regional.length
                    attr.append(regional)
                    attr.setSpan(
                        StyleSpan(Typeface.ITALIC),
                        start,
                        start + regional.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    start = end
                }
                if (isSlang) {
                    if (attr.toString() != "") {
                        attr.append(separator)
                        start += separator.length
                    }
                    end = start + slang.length
                    attr.append(slang)
                    attr.setSpan(
                        StyleSpan(Typeface.ITALIC),
                        start,
                        start + slang.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    start = end
                }
                // While whether an entry is a name isn't actually an attribute, treat it as one.
                if (isName) {
                    if (attr.toString() != "") {
                        attr.append(separator)
                        start += separator.length
                    }
                    end = start + name.length
                    attr.append(name)
                    attr.setSpan(
                        StyleSpan(Typeface.ITALIC),
                        start,
                        start + name.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    start = end
                }
                if (attr.toString() != "") {
                    attr.append(")")
                    attr = SpannableStringBuilder(" (").append(attr)
                    attr.setSpan(
                        RelativeSizeSpan(0.5f),
                        0,
                        attr.toString().length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    ssb.append(attr)
                }
                if (isHypothetical || isExtendedCanon) {
                    ssb = SpannableStringBuilder("?").append(ssb)
                }

                return ssb
            }

        val entryNameInKlingonFont: String
            // Get the name of the entry written in {pIqaD}.
            get() = convertStringToKlingonFont(entryName)

        private val specificPartOfSpeech: String
            get() {
                var pos = basePartOfSpeechAbbreviations[basePartOfSpeech.ordinal]
                if (basePartOfSpeech == BasePartOfSpeechEnum.NOUN) {
                    pos = if (mNounType == NounType.NUMBER) {
                        mContext!!.resources.getString(R.string.pos_number)
                    } else if (mNounType == NounType.NAME) {
                        mContext!!.resources.getString(R.string.pos_name)
                    } else if (mNounType == NounType.PRONOUN) {
                        mContext!!.resources.getString(R.string.pos_pronoun)
                    } else {
                        mContext!!.resources.getString(R.string.pos_noun)
                    }
                } else if (basePartOfSpeech == BasePartOfSpeechEnum.VERB) {
                    pos = mContext!!.resources.getString(R.string.pos_verb)
                } else if (basePartOfSpeech == BasePartOfSpeechEnum.ADVERBIAL) {
                    pos = mContext!!.resources.getString(R.string.pos_adv)
                } else if (basePartOfSpeech == BasePartOfSpeechEnum.CONJUNCTION) {
                    pos = mContext!!.resources.getString(R.string.pos_conj)
                } else if (basePartOfSpeech == BasePartOfSpeechEnum.QUESTION) {
                    pos = mContext!!.resources.getString(R.string.pos_ques)
                } else if (basePartOfSpeech == BasePartOfSpeechEnum.EXCLAMATION) {
                    pos = mContext!!.resources.getString(R.string.pos_excl)
                }
                return pos
            }

        // This is called when creating the expanded definition in the entry, and also in
        // getFormattedDefinition below.
        fun getFormattedPartOfSpeech(isHtml: Boolean): String {
            // Return abbreviation for part of speech, but suppress for sentences and names.
            var pos = ""
            if (isAlternativeSpelling) {
                pos = mContext!!.resources.getString(R.string.label_see_alt_entry) + ": "
            } else if (basePartOfSpeech == BasePartOfSpeechEnum.SENTENCE
                || basePartOfSpeech == BasePartOfSpeechEnum.NOUN && mNounType == NounType.NAME
            ) {
                // Ignore part of speech for names and sentences.
                pos = ""
            } else {
                pos = specificPartOfSpeech
                pos = if (isHtml) {
                    "<i>$pos.</i> "
                } else {
                    "$pos. "
                }
            }
            return pos
        }

        // Get the definition, including the part of speech. Called to create the sharing text for
        // the entry, and also the text in the search results list.
        fun getFormattedDefinition(isHtml: Boolean): String {
            val pos = getFormattedPartOfSpeech(isHtml)

            // Get definition, and append other-language definition if appropriate.
            var definition = definition
            if (shouldDisplayOtherLanguageDefinition()) {
                // Display the other-language as the primary definition and the English as the secondary.
                definition = otherLanguageDefinition + " / " + definition
            }

            // Replace brackets in definition with bold.
            var matcher = ENTRY_PATTERN.matcher(definition)
            while (matcher.find()) {
                // Strip brackets.
                val query = definition!!.substring(matcher.start() + 1, matcher.end() - 1)
                val linkedEntry =
                    Entry(query, mContext)
                var replacement = if (isHtml) {
                    // Bold a Klingon word if there is one.
                    "<b>" + linkedEntry.entryName + "</b>"
                } else {
                    // Just replace it with plain text.
                    linkedEntry.entryName
                }
                definition =
                    (definition.substring(0, matcher.start())
                            + replacement
                            + definition.substring(matcher.end()))

                // Repeat.
                matcher = ENTRY_PATTERN.matcher(definition)
            }

            // Return the definition, preceded by the part of speech.
            return pos + definition
        }

        // Return the part of speech in brackets, but only for some cases. Called to display the
        // part of speech for linked entries in an entry, and also in the main results screen to
        // show what the original search term was.
        fun getBracketedPartOfSpeech(isHtml: Boolean): String {
            // Return abbreviation for part of speech, but suppress for sentences, exclamations, etc.
            if (basePartOfSpeech == BasePartOfSpeechEnum.SENTENCE || basePartOfSpeech == BasePartOfSpeechEnum.EXCLAMATION || basePartOfSpeech == BasePartOfSpeechEnum.SOURCE || basePartOfSpeech == BasePartOfSpeechEnum.URL || basePartOfSpeech == BasePartOfSpeechEnum.UNKNOWN || (basePartOfSpeech == BasePartOfSpeechEnum.NOUN && mNounType == NounType.NAME)) {
                return ""
            }
            val pos = specificPartOfSpeech

            val defn = mContext!!.resources.getString(R.string.homophone_number)
            if (isHtml) {
                // This is used in the "results found" string.
                var bracketedPos = " <small>(<i>$pos</i>)"
                if (homophoneNumber != -1 && mShowHomophoneNumber) {
                    bracketedPos += " (" + defn + " " + homophoneNumber + ")"
                }
                bracketedPos += "</small>"
                return bracketedPos
            } else {
                // This is used in an entry body next to linked entries.
                var bracketedPos = " ($pos)"
                if (homophoneNumber != -1 && mShowHomophoneNumber) {
                    bracketedPos += " (" + defn + " " + homophoneNumber + ")"
                }
                return bracketedPos
            }
        }

        fun basePartOfSpeechIsUnknown(): Boolean {
            return basePartOfSpeech == BasePartOfSpeechEnum.UNKNOWN
        }

        val otherLanguageDefinition: String?
            // TODO: Refactor the additional languages code to be much more compact.
            get() {
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)
                val otherLang =
                    sharedPrefs.getString(
                        Preferences.Companion.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE,  /* default */
                        "NONE"
                    )
                return when (otherLang) {
                    "de" -> definition_DE
                    "fa" -> definition_FA
                    "ru" -> definition_RU
                    "sv" -> definition_SV
                    "zh-HK" -> definition_ZH_HK
                    "pt" -> definition_PT
                    "fi" -> definition_FI
                    "fr" -> definition_FR
                    else ->           // All definitions should exist (even if they are autotranslated), so this should never
                        // be reached, but in case it is, return the English definition by default.
                        definition
                }
            }

        val definition_DE: String
            get() =// If there is no German definition, the cursor could've returned
                // null, so that needs to be handled.
                if ((mDefinition_DE == null)) "" else mDefinition_DE!!

        val notes_DE: String
            get() =// If there are no German notes, the cursor could've returned
                // null, so that needs to be handled.
                if ((mNotes_DE == null)) "" else mNotes_DE!!

        val examples_DE: String
            get() =// If there are no German examples, the cursor could've returned
                // null, so that needs to be handled.
                if ((mExamples_DE == null)) "" else mExamples_DE!!

        val searchTags_DE: String
            get() =// If there are no German search tags, the cursor could've returned
                // null, so that needs to be handled.
                if ((mSearchTags_DE == null)) "" else mSearchTags_DE!!

        // Returns true iff the other-language definition should displayed.
        fun shouldDisplayOtherLanguageDefinition(): Boolean {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)
            val otherLang =
                sharedPrefs.getString(
                    Preferences.Companion.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE,  /* default */
                    Preferences.Companion.systemPreferredLanguage
                )
            if (otherLang !== "NONE") {
                // Show other-language definitions preference set to a language and that other-language
                // definition is not empty or identical to the English.
                val otherLanguageDefinition = otherLanguageDefinition
                return (otherLanguageDefinition != null && otherLanguageDefinition != ""
                        && otherLanguageDefinition != definition)
            } else {
                return false
            }
        }

        // Returns true iff the other-language notes should be displayed. Note that the other-language
        // notes can be set to the string "-" (meaning the other-language notes are empty, but these
        // empty notes override the English notes), in which case this function will still return true.
        // It's up to the caller to "display" these empty notes (i.e., suppress the English notes).
        fun shouldDisplayOtherLanguageNotes(): Boolean {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)
            val otherLang =
                sharedPrefs.getString(
                    Preferences.Companion.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE,  /* default */
                    Preferences.Companion.systemPreferredLanguage
                )
            if (otherLang !== "NONE") {
                // Show other-language definitions preference set to a language and that other-language
                // notes are not empty or identical to the English.
                val otherLanguageNotes = otherLanguageNotes
                return (otherLanguageNotes != null && otherLanguageNotes != ""
                        && otherLanguageNotes != notes)
            } else {
                return false
            }
        }

        // Returns true iff the other-language examples should be displayed.
        fun shouldDisplayOtherLanguageExamples(): Boolean {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)
            val otherLang =
                sharedPrefs.getString(
                    Preferences.Companion.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE,  /* default */
                    Preferences.Companion.systemPreferredLanguage
                )
            if (otherLang !== "NONE") {
                // Show other-language definitions preference set to a language and that other-language
                // examples are not empty.
                val otherLanguageExamples = otherLanguageExamples
                return otherLanguageExamples != null && otherLanguageExamples != ""
            } else {
                return false
            }
        }

        val definition_FA: String
            get() =// If there is no Persian definition, the cursor could've returned
                // null, so that needs to be handled.
                if ((mDefinition_FA == null)) "" else mDefinition_FA!!

        val notes_FA: String
            get() =// If there are no Persian notes, the cursor could've returned
                // null, so that needs to be handled.
                if ((mNotes_FA == null)) "" else mNotes_FA!!

        val examples_FA: String
            get() =// If there are no Persian examples, the cursor could've returned
                // null, so that needs to be handled.
                if ((mExamples_FA == null)) "" else mExamples_FA!!

        val searchTags_FA: String
            get() =// If there are no Persian search tags, the cursor could've returned
                // null, so that needs to be handled.
                if ((mSearchTags_FA == null)) "" else mSearchTags_FA!!

        val definition_SV: String
            get() =// If there is no Swedish definition, the cursor could've returned
                // null, so that needs to be handled.
                if ((mDefinition_SV == null)) "" else mDefinition_SV!!

        val notes_SV: String
            get() =// If there are no Swedish notes, the cursor could've returned
                // null, so that needs to be handled.
                if ((mNotes_SV == null)) "" else mNotes_SV!!

        val examples_SV: String
            get() =// If there are no Swedish examples, the cursor could've returned
                // null, so that needs to be handled.
                if ((mExamples_SV == null)) "" else mExamples_SV!!

        val searchTags_SV: String
            get() =// If there are no Swedish search tags, the cursor could've returned
                // null, so that needs to be handled.
                if ((mSearchTags_SV == null)) "" else mSearchTags_SV!!

        val definition_RU: String
            get() =// If there is no Russian definition, the cursor could've returned
                // null, so that needs to be handled.
                if ((mDefinition_RU == null)) "" else mDefinition_RU!!

        val notes_RU: String
            get() =// If there are no Russian notes, the cursor could've returned
                // null, so that needs to be handled.
                if ((mNotes_RU == null)) "" else mNotes_RU!!

        val examples_RU: String
            get() =// If there are no Russian examples, the cursor could've returned
                // null, so that needs to be handled.
                if ((mExamples_RU == null)) "" else mExamples_RU!!

        val searchTags_RU: String
            get() =// If there are no Russian search tags, the cursor could've returned
                // null, so that needs to be handled.
                if ((mSearchTags_RU == null)) "" else mSearchTags_RU!!

        val definition_ZH_HK: String
            get() =// If there is no Chinese (Hong Kong) definition, the cursor could've returned
                // null, so that needs to be handled.
                if ((mDefinition_ZH_HK == null)) "" else mDefinition_ZH_HK!!

        val notes_ZH_HK: String
            get() =// If there are no Chinese (Hong Kong) notes, the cursor could've returned
                // null, so that needs to be handled.
                if ((mNotes_ZH_HK == null)) "" else mNotes_ZH_HK!!

        val examples_ZH_HK: String
            get() =// If there are no Chinese (Hong Kong) examples, the cursor could've returned
                // null, so that needs to be handled.
                if ((mExamples_ZH_HK == null)) "" else mExamples_ZH_HK!!

        val searchTags_ZH_HK: String
            get() =// If there are no Chinese (Hong Kong) search tags, the cursor could've returned
                // null, so that needs to be handled.
                if ((mSearchTags_ZH_HK == null)) "" else mSearchTags_ZH_HK!!

        val definition_PT: String
            get() =// If there is no Portuguese definition, the cursor could've returned
                // null, so that needs to be handled.
                if ((mDefinition_PT == null)) "" else mDefinition_PT!!

        val notes_PT: String
            get() =// If there are no Portuguese notes, the cursor could've returned
                // null, so that needs to be handled.
                if ((mNotes_PT == null)) "" else mNotes_PT!!

        val examples_PT: String
            get() =// If there are no Portuguese examples, the cursor could've returned
                // null, so that needs to be handled.
                if ((mExamples_PT == null)) "" else mExamples_PT!!

        val searchTags_PT: String
            get() =// If there are no Portuguese search tags, the cursor could've returned
                // null, so that needs to be handled.
                if ((mSearchTags_PT == null)) "" else mSearchTags_PT!!

        val definition_FI: String
            get() =// If there is no Finnish definition, the cursor could've returned
                // null, so that needs to be handled.
                if ((mDefinition_FI == null)) "" else mDefinition_FI!!

        val notes_FI: String
            get() =// If there are no Finnish notes, the cursor could've returned
                // null, so that needs to be handled.
                if ((mNotes_FI == null)) "" else mNotes_FI!!

        val examples_FI: String
            get() =// If there are no Finnish examples, the cursor could've returned
                // null, so that needs to be handled.
                if ((mExamples_FI == null)) "" else mExamples_FI!!

        val searchTags_FI: String
            get() =// If there are no Finnish search tags, the cursor could've returned
                // null, so that needs to be handled.
                if ((mSearchTags_FI == null)) "" else mSearchTags_FI!!

        val definition_FR: String
            get() =// If there is no French definition, the cursor could've returned
                // null, so that needs to be handled.
                if ((mDefinition_FR == null)) "" else mDefinition_FR!!

        val notes_FR: String
            get() =// If there are no French notes, the cursor could've returned
                // null, so that needs to be handled.
                if ((mNotes_FR == null)) "" else mNotes_FR!!

        val examples_FR: String
            get() =// If there are no French examples, the cursor could've returned
                // null, so that needs to be handled.
                if ((mExamples_FR == null)) "" else mExamples_FR!!

        val searchTags_FR: String
            get() =// If there are no French search tags, the cursor could've returned
                // null, so that needs to be handled.
                if ((mSearchTags_FR == null)) "" else mSearchTags_FR!!

        val otherLanguageNotes: String
            // TODO: Refactor.
            get() {
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)
                val otherLang =
                    sharedPrefs.getString(
                        Preferences.Companion.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE,  /* default */
                        "NONE"
                    )
                return when (otherLang) {
                    "de" -> notes_DE
                    "fa" -> notes_FA
                    "ru" -> notes_RU
                    "sv" -> notes_SV
                    "zh-HK" -> notes_ZH_HK
                    "pt" -> notes_PT
                    "fi" -> notes_FI
                    "fr" -> notes_FR
                    else ->           // By default, return the English notes if other-language notes don't exist.
                        notes
                }
            }

        val componentsAsEntries: ArrayList<Entry>
            get() {
                val componentEntriesList = ArrayList<Entry>()
                if (components.trim { it <= ' ' } == "") {
                    // Components is empty, return empty list.
                    return componentEntriesList
                }
                // There must be exactly one space after the comma, since comma-space separates entries while
                // comma by itself separates attributes of an entry.
                val componentQueries =
                    components.split("\\s*, \\s*".toRegex()).dropLastWhile { it.isEmpty() }
                        .toTypedArray()
                for (i in componentQueries.indices) {
                    val componentQuery = componentQueries[i]
                    val componentEntry = Entry(componentQuery, mContext)
                    componentEntriesList.add(componentEntry)
                }
                return componentEntriesList
            }

        val otherLanguageExamples: String
            // TODO: Refactor.
            get() {
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)
                val otherLang =
                    sharedPrefs.getString(
                        Preferences.Companion.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE,  /* default */
                        "NONE"
                    )
                return when (otherLang) {
                    "de" -> examples_DE
                    "fa" -> examples_FA
                    "ru" -> examples_RU
                    "sv" -> examples_SV
                    "zh-HK" -> examples_ZH_HK
                    "pt" -> examples_PT
                    "fi" -> examples_FI
                    "fr" -> examples_FR
                    else -> examples
                }
            }

        fun isSource(): Boolean {
            return basePartOfSpeech == BasePartOfSpeechEnum.SOURCE
        }

        val isURL: Boolean
            get() = basePartOfSpeech == BasePartOfSpeechEnum.URL

        fun getURL(): String {
            // If this is a source (like "TKD", "KGT", etc.), try to derive the URL from the entry name.
            val TKD_PAGE_PATTERN = Pattern.compile("TKD p.([0-9]+)")
            val TKD_SECTION_PATTERN = Pattern.compile("TKD ([0-9]\\.[0-9](?:\\.[0-9])?)")
            val TKDA_SECTION_PATTERN = Pattern.compile("TKDA ([0-9]\\.[0-9](?:\\.[0-9])?)")
            val KGT_PAGE_PATTERN = Pattern.compile("KGT p.([0-9]+)")
            if (isSource()) {
                // Check TKD page number.
                var m = TKD_PAGE_PATTERN.matcher(entryName)
                if (m.find()) {
                    // There is a second identical copy of the book at ID "WFnPOKSp6uEC".
                    var URL = "https://play.google.com/books/reader?id=dqOwxsg6XnwC"
                    URL += "&pg=GBS.PA" + m.group(1).toInt()
                    return URL
                }

                // Check TKD section number.
                m = TKD_SECTION_PATTERN.matcher(entryName)
                if (m.find()) {
                    // There is a second identical copy of the book at ID "WFnPOKSp6uEC".
                    var URL = "https://play.google.com/books/reader?id=dqOwxsg6XnwC"
                    val section = m.group(1)
                    // TODO: Refactor this into something nicer.
                    if (section != null) {
                        if (section == "3.2.1" || section == "3.2.2") {
                            URL += "&pg=GBS.PA19"
                        } else if (section == "3.2.3") {
                            URL += "&pg=GBS.PA20"
                        } else if (section == "3.3.1" || section == "3.3.2") {
                            URL += "&pg=GBS.PA21"
                        } else if (section == "3.3.3") {
                            URL += "&pg=GBS.PA24"
                        } else if (section == "3.3.4") {
                            URL += "&pg=GBS.PA25"
                        } else if (section == "3.3.5") {
                            URL += "&pg=GBS.PA26"
                        } else if (section == "3.3.6") {
                            URL += "&pg=GBS.PA29"
                        } else if (section == "3.4") {
                            URL += "&pg=GBS.PA30"
                        } else if (section == "4.2.1") {
                            URL += "&pg=GBS.PA35"
                        } else if (section == "4.2.2") {
                            URL += "&pg=GBS.PA36"
                        } else if (section == "4.2.3") {
                            URL += "&pg=GBS.PA37"
                        } else if (section == "4.2.4" || section == "4.2.5") {
                            URL += "&pg=GBS.PA38"
                        } else if (section == "4.2.6") {
                            URL += "&pg=GBS.PA39"
                        } else if (section == "4.2.7") {
                            URL += "&pg=GBS.PA40"
                        } else if (section == "4.2.8" || section == "4.2.9") {
                            URL += "&pg=GBS.PA43"
                        } else if (section == "4.2.10") {
                            URL += "&pg=GBS.PA44"
                        } else if (section == "4.3") {
                            URL += "&pg=GBS.PA46"
                        } else if (section == "4.4") {
                            URL += "&pg=GBS.PA49"
                        } else if (section == "5.1") {
                            URL += "&pg=GBS.PA51"
                        } else if (section == "5.2") {
                            URL += "&pg=GBS.PA52"
                        } else if (section == "5.3" || section == "5.4") {
                            URL += "&pg=GBS.PA55"
                        } else if (section == "5.5") {
                            URL += "&pg=GBS.PA57"
                        } else if (section == "5.6") {
                            URL += "&pg=GBS.PA58"
                        } else if (section == "6.1") {
                            URL += "&pg=GBS.PA59"
                        } else if (section == "6.2.1") {
                            URL += "&pg=GBS.PA61"
                        } else if (section == "6.2.2") {
                            URL += "&pg=GBS.PA62"
                        } else if (section == "6.2.3") {
                            URL += "&pg=GBS.PA63"
                        } else if (section == "6.2.4") {
                            URL += "&pg=GBS.PA64"
                        } else if (section == "6.2.5") {
                            URL += "&pg=GBS.PA65"
                        } else if (section == "6.3") {
                            URL += "&pg=GBS.PA67"
                        } else if (section == "6.4") {
                            URL += "&pg=GBS.PA68"
                        } else if (section == "6.5" || section == "6.6") {
                            URL += "&pg=GBS.PA70"
                        }
                    }
                    return URL
                }

                // Check TKDA section number.
                m = TKDA_SECTION_PATTERN.matcher(entryName)
                if (m.find()) {
                    // There is a second identical copy of the book at ID "WFnPOKSp6uEC".
                    var URL = "https://play.google.com/books/reader?id=dqOwxsg6XnwC"
                    val section = m.group(1)
                    // TODO: Refactor this into something nicer.
                    if (section != null) {
                        if (section == "3.3.1") {
                            URL += "&pg=GBS.PA174"
                        } else if (section == "4.2.6" || section == "4.2.9") {
                            URL += "&pg=GBS.PA175"
                        } else if (section == "6.7" || section == "6.8") {
                            URL += "&pg=GBS.PA179"
                        }
                    }
                    return URL
                }

                // For whatever reason, TKW is not found in Google Play Books.

                // Check KGT.
                m = KGT_PAGE_PATTERN.matcher(entryName)
                if (m.find()) {
                    // There is a second identical copy of the book at ID "9Vz1q4p87GgC".
                    var URL = "https://play.google.com/books/reader?id=B5AiSVBw7nMC"
                    if (m.group(1) != null) {
                        // The page numbers in the Google Play Books version of KGT is offset by about 9 pages
                        // from the physical edition of the book, so adjust for that. There is allegedly another
                        // parameter "PA" which allows linking to the printed page number. But apparently this
                        // doesn't work for this book.
                        val pageNumber = m.group(1).toInt() + 9
                        // The "PA" parameter appears not to work on this book.
                        URL += "&pg=GBS.PT$pageNumber"
                    }
                    return URL
                }
            }

            // Otherwise, return the entry's URL (which will only be non-empty if this is an URL).
            return mURL
        }

        fun doNotLink(): Boolean {
            return mDoNotLink
        }

        val isPronoun: Boolean
            get() = basePartOfSpeech == BasePartOfSpeechEnum.NOUN && mNounType == NounType.PRONOUN

        val isName: Boolean
            get() = basePartOfSpeech == BasePartOfSpeechEnum.NOUN && mNounType == NounType.NAME

        val isNumber: Boolean
            get() = basePartOfSpeech == BasePartOfSpeechEnum.NOUN && mNounType == NounType.NUMBER

        val isSentence: Boolean
            get() = basePartOfSpeech == BasePartOfSpeechEnum.SENTENCE

        val sentenceType: String
            get() {
                if (mSentenceType == SentenceType.EMPIRE_UNION_DAY) {
                    return mContext!!.resources.getString(R.string.empire_union_day)
                } else if (mSentenceType == SentenceType.CURSE_WARFARE) {
                    return mContext!!.resources.getString(R.string.curse_warfare)
                    /*
         * IDIOM is commented out because it's not in the menu yet, since we have no good
         * translation for the word. } else if (mSentenceType == SentenceType.IDIOM) {
         * return mContext.getResources().getString(R.string.idioms); }
         */
                } else if (mSentenceType == SentenceType.NENTAY) {
                    return mContext!!.resources.getString(R.string.nentay)
                    /*
         * PROVERB is also commented out because it's not in the menu yet either, due to
         * incompleteness. } else if (mSentenceType == SentenceType.PROVERB) { return
         * mContext.getResources().getString(R.string.proverbs); }
         */
                } else if (mSentenceType == SentenceType.MILITARY_CELEBRATION) {
                    return mContext!!.resources.getString(R.string.military_celebration)
                } else if (mSentenceType == SentenceType.REJECTION) {
                    return mContext!!.resources.getString(R.string.rejection)
                } else if (mSentenceType == SentenceType.REPLACEMENT_PROVERB) {
                    return mContext!!.resources.getString(R.string.replacement_proverbs)
                } else if (mSentenceType == SentenceType.SECRECY_PROVERB) {
                    return mContext!!.resources.getString(R.string.secrecy_proverbs)
                } else if (mSentenceType == SentenceType.TOAST) {
                    return mContext!!.resources.getString(R.string.toasts)
                } else if (mSentenceType == SentenceType.LYRICS) {
                    return mContext!!.resources.getString(R.string.lyrics)
                } else if (mSentenceType == SentenceType.BEGINNERS_CONVERSATION) {
                    return mContext!!.resources.getString(R.string.beginners_conversation)
                } else if (mSentenceType == SentenceType.JOKE) {
                    return mContext!!.resources.getString(R.string.jokes)
                }

                // The empty string is returned if the type is general PHRASE.
                return ""
            }

        val sentenceTypeQuery: String
            get() {
                // TODO: Refactor this to use existing constants.
                if (mSentenceType == SentenceType.EMPIRE_UNION_DAY) {
                    return "*:sen:eu"
                } else if (mSentenceType == SentenceType.CURSE_WARFARE) {
                    return "*:sen:mv"
                } else if (mSentenceType == SentenceType.IDIOM) {
                    return "*:sen:idiom"
                } else if (mSentenceType == SentenceType.NENTAY) {
                    return "*:sen:nt"
                } else if (mSentenceType == SentenceType.PROVERB) {
                    return "*:sen:prov"
                } else if (mSentenceType == SentenceType.MILITARY_CELEBRATION) {
                    return "*:sen:Ql"
                } else if (mSentenceType == SentenceType.REJECTION) {
                    return "*:sen:rej"
                } else if (mSentenceType == SentenceType.REPLACEMENT_PROVERB) {
                    return "*:sen:rp"
                } else if (mSentenceType == SentenceType.SECRECY_PROVERB) {
                    return "*:sen:sp"
                } else if (mSentenceType == SentenceType.TOAST) {
                    return "*:sen:toast"
                } else if (mSentenceType == SentenceType.LYRICS) {
                    return "*:sen:lyr"
                } else if (mSentenceType == SentenceType.BEGINNERS_CONVERSATION) {
                    return "*:sen:bc"
                } else if (mSentenceType == SentenceType.JOKE) {
                    return "*:sen:joke"
                }

                // A general phrase. In theory this should never be returned.
                return "*:sen:phr"
            }

        val isVerb: Boolean
            // This is a verb (but not a prefix or suffix).
            get() = basePartOfSpeech == BasePartOfSpeechEnum.VERB && !isPrefix && !isSuffix

        val isPrefix: Boolean
            get() =// It's necessary to check that the entry name ends with "-" because links (e.g., the list of
                // components) are not fully annotated.
                (basePartOfSpeech == BasePartOfSpeechEnum.VERB
                        && (mIsPrefix || entryName!!.endsWith("-")))

        val isSuffix: Boolean
            get() =// It's necessary to check that the entry name starts with "-" because links (e.g., the list
                // of components) are not fully annotated.
                mIsSuffix || entryName!!.startsWith("-")

        val isNoun: Boolean
            // This is a noun (including possible a noun suffix).
            get() = basePartOfSpeech == BasePartOfSpeechEnum.NOUN

        val isMisc: Boolean
            // {chuvmey} - not sentences, but not verbs/nouns/affixes either.
            get() = basePartOfSpeech == BasePartOfSpeechEnum.ADVERBIAL || basePartOfSpeech == BasePartOfSpeechEnum.CONJUNCTION || basePartOfSpeech == BasePartOfSpeechEnum.QUESTION

        val textColor: Int
            get() {
                // TODO: Make the colours customisable. For now, use Lieven's system.
                // https://code.google.com/p/klingon-assistant/issues/detail?id=8
                if (isHypothetical || isExtendedCanon) {
                    return Color.GRAY
                } else if (isVerb) {
                    return Color.YELLOW
                } else if (isNoun && !isSuffix && !isNumber && !isPronoun) {
                    // Note: isNoun() also returns true if it's a suffix, but isVerb() returns false.
                    // TODO: fix this asymmetry between isNoun() and isVerb().
                    return Color.GREEN
                } else if (isSuffix || isPrefix) {
                    return Color.RED
                } else if (isMisc || isNumber || isPronoun) {
                    return Color.CYAN
                }
                return Color.WHITE
            }

        val transitivityString: String
            get() = when (transitivity) {
                VerbTransitivityType.AMBITRANSITIVE -> mContext!!.resources.getString(R.string.transitivity_ambi)

                VerbTransitivityType.INTRANSITIVE -> if (mTransitivityConfirmed) {
                    mContext!!.resources.getString(R.string.transitivity_intransitive_confirmed)
                } else {
                    mContext!!.resources.getString(R.string.transitivity_intransitive)
                }

                VerbTransitivityType.STATIVE -> mContext!!.resources.getString(R.string.transitivity_stative)

                VerbTransitivityType.TRANSITIVE -> if (mTransitivityConfirmed) {
                    mContext!!.resources.getString(R.string.transitivity_transitive_confirmed)
                } else {
                    mContext!!.resources.getString(R.string.transitivity_transitive)
                }

                else ->           // This is reached if the verb transitivity type is unknown, or if for some reason this
                    // function is called on a verb with a type 5 noun suffix attached, which shouldn't
                    // happen.
                    mContext!!.resources.getString(R.string.transitivity_unknown)
            }

        // Called on a query entry, determines if the query is satisfied by the candidate entry.
        fun isSatisfiedBy(candidate: Entry): Boolean {
            Log.d(
                TAG, """
     
     isSatisfiedBy candidate: ${candidate.entryName}
     """.trimIndent()
            )

            // Determine whether entry name matches exactly.
            val isExactMatchForEntryName = entryName == candidate.entryName

            // If the part of speech is unknown, be much less strict, because
            // the query was typed from the search box.
            if (!basePartOfSpeechIsUnknown()) {
                // Base part of speech is known, so match exact entry name as
                // well as base part of speech.
                Log.d(TAG, "isExactMatchForEntryName: $isExactMatchForEntryName")
                if (!isExactMatchForEntryName) {
                    return false
                }
                // The parts of speech must match, except when: we're looking for a verb, in which
                // case a pronoun will satisfy the requirement; or we're looking for a noun, in which case
                // the question words {nuq} and {'Iv}, as well as exclamations which are epithets, are
                // accepted. We have these exceptions because we want to allow constructions like {ghaHtaH},
                // for those two question words to take the place of the nouns they are asking about, and
                // to parse constructions such as {petaQpu'}. Note that entries knows nothing about affixes,
                // so it's up to the caller to exclude, e.g., prefixes on pronouns. The homophony of
                // {'Iv:ques} and {'Iv:n} necessitates adding a homophone number (in the database) to
                // distinguish them.
                // TODO: Remove redundant {nuq} + {-Daq}.
                Log.d(TAG, "mBasePartOfSpeech: " + basePartOfSpeech)
                Log.d(TAG, "candidate.getBasePartOfSpeech: " + candidate.basePartOfSpeech)
                Log.d(TAG, "candidate.getEntryName: " + candidate.entryName)
                val candidateIsPronounActingAsVerb =
                    (basePartOfSpeech == BasePartOfSpeechEnum.VERB && candidate.isPronoun)
                val candidateIsQuestionWordActingAsNoun =
                    (basePartOfSpeech == BasePartOfSpeechEnum.NOUN
                            && (candidate.entryName == "nuq" || candidate.entryName == "'Iv"))
                val candidateIsExclamationActingAsNoun =
                    (basePartOfSpeech == BasePartOfSpeechEnum.NOUN && candidate.isEpithet)
                if (basePartOfSpeech != candidate.basePartOfSpeech) {
                    if (!candidateIsPronounActingAsVerb
                        && !candidateIsQuestionWordActingAsNoun
                        && !candidateIsExclamationActingAsNoun
                    ) {
                        return false
                    }
                }
                // However, if we're looking for a verb with a type 5 noun suffix attached, then we disallow
                // transitive verbs as well as pronouns. We also disallow (confirmed) intransitive verbs.
                // Note that pronouns with a type 5 noun suffix are already covered under nouns, so if we
                // allowed it here they would be duplicated. Also, even though only adjectival verbs can
                // take a type 5 noun suffix, we allow not only stative verbs (like {tIn}) and
                // ambitransitive verbs (like {pegh}), but also unconfirmed intransitive verbs, since it's
                // possible they've been misclassified and are actually stative.
                // (So this should exclude the erroneous analysis for {lervaD:n} as {ler:v} + {-vaD:n},
                // since {ler:v} is confirmed intransitive.)
                if (basePartOfSpeech == BasePartOfSpeechEnum.VERB && transitivity == VerbTransitivityType.HAS_TYPE_5_NOUN_SUFFIX && (candidate.isPronoun || candidate.transitivity == VerbTransitivityType.TRANSITIVE || (candidate.transitivity == VerbTransitivityType.INTRANSITIVE
                            && candidate.mTransitivityConfirmed))
                ) {
                    return false
                }
            }
            Log.d(TAG, "Exact name match for entry name: " + candidate.entryName)
            Log.d(TAG, "Part of speech satisfied for: " + candidate.entryName)

            // If the homophone number is given, it must match.
            if (homophoneNumber != -1 && homophoneNumber != candidate.homophoneNumber) {
                return false
            }

            // If search for an attribute, candidate must have it.
            if (isSlang && !candidate.isSlang) {
                return false
            }
            if (isRegional && !candidate.isRegional) {
                return false
            }
            if (isArchaic && !candidate.isArchaic) {
                return false
            }
            if (isName && !candidate.isName) {
                return false
            }
            if (isNumber && !candidate.isNumber) {
                return false
            }

            // Treat fictional differently?
            // if (mIsFictional && !candidate.isFictional()) {
            // return false;
            // }

            // TODO: Test a bunch of other things here.
            Log.d(
                TAG,
                "Candidate passed: "
                        + candidate.entryName
                        + candidate.getBracketedPartOfSpeech( /* html */false)
            )
            return true
        }

        companion object {
            // Pattern for matching entry in text. The letter "ü" is needed to match "Saarbrücken". The "+"
            // is needed for "Google+".
            var ENTRY_PATTERN: Pattern =
                Pattern.compile("\\{[A-Za-zü0-9 '\\\":;,\\.\\-?!_/()@=%&\\*\\+]+\\}")

            // Used for analysis of entries with components.
            // It cannot occur in a link (we cannot use "//" for example because it occurs in URL links,
            // or one "@" because it occurs in email addresses). It should not contain anything that
            // needs to be escaped in a regular expression, since it is stripped and then added back.
            const val COMPONENTS_MARKER: String = "@@"
        }
    }

    // This class is for complex Klingon words. A complex word is a noun or verb with affixes.
    // Note: To debug parsing, you likely want to use "adb logcat -s
    // KlingonContentProvider.ComplexWord"
    // or "adb logcat -s KlingonContentProvider.ComplexWord KlingonContentProvider".
    class ComplexWord {
        // The logging tag can be at most 23 characters. "KlingonContentProvider.ComplexWord" was too
        // long.
        var TAG: String = "KCP.ComplexWord"

        var mNounSuffixes: IntArray = IntArray(nounSuffixesStrings.size)

        var mVerbPrefix: Int
        var mVerbSuffixes: IntArray = IntArray(verbSuffixesStrings.size)

        var mNumberDigit: Int
        var mNumberModifier: Int

        // Get the number suffix ("DIch" or "logh") for a number.
        var numberSuffix: String
        var mIsNumberLike: Boolean

        // The locations of the true rovers. The value indicates the suffix type they appear after,
        // so 0 means they are attached directly to the verb (before any type 1 suffix).
        var mVerbTypeRNegation: Int
        var mVerbTypeREmphatic: Int

        // True if {-be'} appears before {-qu'} in a verb.
        var roverOrderNegationBeforeEmphatic: Boolean

        // Internal information related to processing the complex word candidate.
        // TODO: There are few complex words which are neither nouns nor verbs, e.g., {batlhHa'},
        // {paghlogh}, {HochDIch}. Figure out how to deal with them.
        var mUnparsedPart: String
        var mSuffixLevel: Int = 0
        var mIsNounCandidate: Boolean
        var mIsVerbWithType5NounSuffix: Boolean
        var mHomophoneNumber: Int

        /**
         * Constructor
         *
         * @param candidate A potential candidate for a complex word.
         * @param isNounCandidate Set to true if noun, false if verb.
         */
        constructor(candidate: String, isNounCandidate: Boolean) {
            mUnparsedPart = candidate
            mIsNounCandidate = isNounCandidate
            mIsVerbWithType5NounSuffix = false
            mHomophoneNumber = -1

            mSuffixLevel = if (mIsNounCandidate) {
                // Five types of noun suffixes.
                nounSuffixesStrings.size
            } else {
                // Nine types of verb suffixes.
                verbSuffixesStrings.size
            }

            for (i in mNounSuffixes.indices) {
                mNounSuffixes[i] = 0
            }

            mVerbPrefix = 0
            for (i in mVerbSuffixes.indices) {
                mVerbSuffixes[i] = 0
            }

            // Rovers.
            mVerbTypeRNegation = ROVER_NOT_YET_FOUND
            mVerbTypeREmphatic = ROVER_NOT_YET_FOUND
            roverOrderNegationBeforeEmphatic = false

            // Number parts.
            mNumberDigit = 0
            mNumberModifier = 0
            numberSuffix = ""
            mIsNumberLike = false
        }

        /**
         * Copy constructor
         *
         * @param unparsedPart The unparsedPart of this complex word.
         * @param complexWordToCopy
         */
        constructor(unparsedPart: String, complexWordToCopy: ComplexWord) {
            mUnparsedPart = unparsedPart
            mIsNounCandidate = complexWordToCopy.mIsNounCandidate
            mIsVerbWithType5NounSuffix = complexWordToCopy.mIsVerbWithType5NounSuffix
            mHomophoneNumber = complexWordToCopy.mHomophoneNumber
            mSuffixLevel = complexWordToCopy.mSuffixLevel
            mVerbPrefix = complexWordToCopy.mVerbPrefix
            for (i in mNounSuffixes.indices) {
                mNounSuffixes[i] = complexWordToCopy.mNounSuffixes[i]
            }
            for (j in mVerbSuffixes.indices) {
                mVerbSuffixes[j] = complexWordToCopy.mVerbSuffixes[j]
            }
            mVerbTypeRNegation = complexWordToCopy.mVerbTypeRNegation
            mVerbTypeREmphatic = complexWordToCopy.mVerbTypeREmphatic
            roverOrderNegationBeforeEmphatic = complexWordToCopy.roverOrderNegationBeforeEmphatic
            mNumberDigit = complexWordToCopy.mNumberDigit
            mNumberModifier = complexWordToCopy.mNumberModifier
            numberSuffix = complexWordToCopy.numberSuffix
            mIsNumberLike = complexWordToCopy.mIsNumberLike
        }

        fun setHomophoneNumber(number: Int) {
            // Used for filtering entries. If two entries have homophones, they must each have a
            // unique number.
            mHomophoneNumber = number
        }

        fun stripPrefix(): ComplexWord? {
            if (mIsNounCandidate) {
                return null
            }

            // Count from 1, since index 0 corresponds to no prefix.
            for (i in 1 until Companion.verbPrefixString.size) {
                // Log.d(TAG, "checking prefix: " + verbPrefixString[i]);
                if (mUnparsedPart.startsWith(Companion.verbPrefixString[i])) {
                    val partWithPrefixRemoved =
                        mUnparsedPart.substring(Companion.verbPrefixString[i].length)
                    // Log.d(TAG, "found prefix: " + verbPrefixString[i] + ", remainder: " +
                    // partWithPrefixRemoved);
                    if (partWithPrefixRemoved != "") {
                        val anotherComplexWord = ComplexWord(partWithPrefixRemoved, this)
                        anotherComplexWord.mVerbPrefix = i
                        return anotherComplexWord
                    }
                }
            }
            return null
        }

        // Attempt to strip off the rovers.
        private fun stripRovers(): ComplexWord? {
            // There are a few entries in the database where the {-be'} and {-qu'} are included, e.g.,
            // {motlhbe'} and {Say'qu'}. The logic here allows, e.g., {bImotlhbe'be'}, but we don't care
            // since this is relatively rare. Note that {qu'be'} is itself a word.
            if (mVerbTypeRNegation == ROVER_NOT_YET_FOUND && mUnparsedPart.endsWith("be'")
                && mUnparsedPart != "be'"
            ) {
                val partWithRoversRemoved = mUnparsedPart.substring(0, mUnparsedPart.length - 3)
                val anotherComplexWord = ComplexWord(partWithRoversRemoved, this)
                mVerbTypeRNegation = IGNORE_THIS_ROVER
                anotherComplexWord.mVerbTypeRNegation = mSuffixLevel - 1
                anotherComplexWord.mSuffixLevel = mSuffixLevel
                if (anotherComplexWord.mVerbTypeREmphatic == mSuffixLevel - 1) {
                    // {-be'qu'}
                    anotherComplexWord.roverOrderNegationBeforeEmphatic = true
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "found rover: -be'")
                }
                return anotherComplexWord
            } else if (mVerbTypeREmphatic == ROVER_NOT_YET_FOUND && mUnparsedPart.endsWith("qu'")
                && mUnparsedPart != "qu'"
            ) {
                val partWithRoversRemoved = mUnparsedPart.substring(0, mUnparsedPart.length - 3)
                val anotherComplexWord = ComplexWord(partWithRoversRemoved, this)
                mVerbTypeREmphatic = IGNORE_THIS_ROVER
                anotherComplexWord.mVerbTypeREmphatic = mSuffixLevel - 1
                anotherComplexWord.mSuffixLevel = mSuffixLevel
                if (anotherComplexWord.mVerbTypeRNegation == mSuffixLevel - 1) {
                    // {-qu'be'}
                    anotherComplexWord.roverOrderNegationBeforeEmphatic = false
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "found rover: -qu'")
                }
                return anotherComplexWord
            }
            return null
        }

        // Attempt to strip off one level of suffix from self, if this results in a branch return the
        // branch as a new complex word.
        // At the end of this call, this complex word will have have decreased one suffix level.
        fun stripSuffixAndBranch(): ComplexWord? {
            if (mSuffixLevel == 0) {
                // This should never be reached.
                if (BuildConfig.DEBUG) {
                    Log.e(TAG, "stripSuffixAndBranch: mSuffixLevel == 0")
                }
                return null
            }

            // TODO: Refactor this to merge the two subtractions together.
            val suffixes: Array<String>
            if (mIsNounCandidate) {
                // The types are 1-indexed, but the array is 0-index, so decrement it here.
                mSuffixLevel--
                suffixes = nounSuffixesStrings[mSuffixLevel]
            } else {
                val anotherComplexWord = stripRovers()
                val suffixType: String
                if (anotherComplexWord != null) {
                    if (BuildConfig.DEBUG) {
                        // Verb suffix level doesn't correspond exactly: {-Ha'}, types 1 through 8, {-Qo'}, then
                        // 9.
                        suffixType = if (mSuffixLevel == 1) {
                            "-Ha'"
                        } else if (mSuffixLevel == 10) {
                            "-Qo'"
                        } else if (mSuffixLevel == 11) {
                            "type 9"
                        } else {
                            "type " + (mSuffixLevel - 1)
                        }
                        Log.d(TAG, "rover found while processing verb suffix: $suffixType")
                    }
                    return anotherComplexWord
                }
                // The types are 1-indexed, but the array is 0-index, so decrement it here.
                mSuffixLevel--
                suffixes = verbSuffixesStrings[mSuffixLevel]
            }

            // Count from 1, since index 0 corresponds to no suffix of this type.
            for (i in 1 until suffixes.size) {
                // Log.d(TAG, "checking suffix: " + suffixes[i]);
                if (mUnparsedPart.endsWith(suffixes[i])) {
                    // Found a suffix of the current type, strip it.
                    val partWithSuffixRemoved =
                        mUnparsedPart.substring(0, mUnparsedPart.length - suffixes[i].length)
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            TAG,
                            "found suffix: " + suffixes[i] + ", remainder: " + partWithSuffixRemoved
                        )
                    }
                    // A suffix was successfully stripped if there's something left. Also, if the suffix had
                    // been {-oy}, check that the noun doesn't end in a vowel. The suffix {-oy} preceded by a
                    // vowel is handled separately in maybeStripApostropheOy.
                    if (partWithSuffixRemoved != ""
                        && (suffixes[i] !== "oy" || !partWithSuffixRemoved.matches(".*[aeIou]".toRegex()))
                    ) {
                        val anotherComplexWord = ComplexWord(partWithSuffixRemoved, this)
                        // mSuffixLevel already decremented above.
                        anotherComplexWord.mSuffixLevel = mSuffixLevel
                        if (mIsNounCandidate) {
                            anotherComplexWord.mNounSuffixes[anotherComplexWord.mSuffixLevel] = i
                        } else {
                            anotherComplexWord.mVerbSuffixes[anotherComplexWord.mSuffixLevel] = i
                        }
                        return anotherComplexWord
                    }
                }
            }
            return null
        }

        // Special-case processing for the suffix {-oy} when preceded by a vowel.
        fun maybeStripApostropheOy(): ComplexWord? {
            if (mSuffixLevel == 1 && mIsNounCandidate && mUnparsedPart.endsWith("'oy")) {
                // Remove "'oy" from the end.
                val partWithSuffixRemoved = mUnparsedPart.substring(0, mUnparsedPart.length - 3)
                if (partWithSuffixRemoved.matches(".*[aeIou]".toRegex())) {
                    val anotherComplexWord = ComplexWord(partWithSuffixRemoved, this)
                    anotherComplexWord.mSuffixLevel = 0 // No more suffixes.
                    anotherComplexWord.mNounSuffixes[0] = 3 // Index of "oy".
                    return anotherComplexWord
                }
            }
            return null
        }

        fun hasNoMoreSuffixes(): Boolean {
            return mSuffixLevel == 0
        }

        val isBareWord: Boolean
            // Returns true if this is not a complex word after all.
            get() {
                if (mVerbPrefix != 0) {
                    // A verb prefix was found.
                    return false
                }
                if (mVerbTypeRNegation >= 0 || mVerbTypeREmphatic >= 0) {
                    // Note that -1 indicates ROVER_NOT_YET_FOUND and -2 indicates IGNORE_THIS_ROVER, so a found
                    // rover has position greater than or equal to 0.
                    // A rover was found.
                    return false
                }
                for (i in mNounSuffixes.indices) {
                    if (mNounSuffixes[i] != 0) {
                        // A noun suffix was found.
                        return false
                    }
                }
                for (j in mVerbSuffixes.indices) {
                    if (mVerbSuffixes[j] != 0) {
                        // A verb suffix was found.
                        return false
                    }
                }
                // None found.
                return true
            }

        val isNumberLike: Boolean
            get() =// A complex word is number-like if it's a noun and it's marked as such.
                mIsNounCandidate && mIsNumberLike

        private fun noNounSuffixesFound(): Boolean {
            for (i in mNounSuffixes.indices) {
                if (mNounSuffixes[i] != 0) {
                    // A noun suffix was found.
                    return false
                }
            }
            // None found.
            return true
        }

        override fun toString(): String {
            var s = mUnparsedPart
            if (mIsNounCandidate) {
                s += " (n)"
                for (i in mNounSuffixes.indices) {
                    s += " " + mNounSuffixes[i]
                }
            } else {
                // TODO: Handle negation and emphatic rovers.
                s += " (v) "
                for (i in mVerbSuffixes.indices) {
                    s += " " + mVerbSuffixes[i]
                }
            }
            return s
        }

        // Used for telling stems of complex words apart.
        fun filter(isLenient: Boolean): String {
            if (mIsVerbWithType5NounSuffix) {
                // If this is a candidate for a verb with a type 5 noun suffix attached, mark it so that
                // it's treated specially. In particular, it must be a verb which is not transitive, and it
                // cannot be a pronoun acting as a verb.
                return "$mUnparsedPart:v:n5"
            } else if (isLenient && isBareWord) {
                // If isLenient is true, then also match non-nouns and non-verbs
                // if there are no prefixes or suffixes.
                return mUnparsedPart
            }
            return (mUnparsedPart
                    + ":"
                    + (if (mIsNounCandidate) "n" else "v")
                    + (if (mHomophoneNumber != -1) ":$mHomophoneNumber" else ""))
        }

        fun stem(): String {
            return mUnparsedPart
        }

        val verbPrefix: String
            // Get the entry name for the verb prefix.
            get() = Companion.verbPrefixString[mVerbPrefix] + (if (mVerbPrefix == 0) "" else "-")

        val verbSuffixes: Array<String?>
            // Get the entry names for the verb suffixes.
            get() {
                val suffixes = arrayOfNulls<String>(mVerbSuffixes.size)
                for (i in mVerbSuffixes.indices) {
                    suffixes[i] =
                        (if (mVerbSuffixes[i] == 0) "" else "-") + verbSuffixesStrings[i][mVerbSuffixes[i]]
                }
                return suffixes
            }

        val nounSuffixes: Array<String?>
            // Get the entry names for the noun suffixes.
            get() {
                val suffixes = arrayOfNulls<String>(mNounSuffixes.size)
                for (i in mNounSuffixes.indices) {
                    suffixes[i] =
                        (if (mNounSuffixes[i] == 0) "" else "-") + nounSuffixesStrings[i][mNounSuffixes[i]]
                }
                return suffixes
            }

        val numberRoot: String
            // Get the root for a number.
            get() {
                if (mNumberDigit != 0) {
                    // This is an actual digit from {wa'} to {Hut}.
                    return numberDigitString[mNumberDigit]
                }

                var numberRoot = ""
                if (mUnparsedPart.startsWith("pagh")) {
                    numberRoot = "pagh"
                } else if (mUnparsedPart.startsWith("Hoch")) {
                    numberRoot = "Hoch"
                } else if (mUnparsedPart.startsWith("'ar")) {
                    // Note that this will cause {'arDIch} to be accepted as a word.
                    numberRoot = "'ar"
                }
                return numberRoot
            }

        val numberRootAnnotation: String
            // Get the annotation for the root for a number.
            get() {
                if (mNumberDigit != 0) {
                    return "n:num"
                }

                var numberRoot = ""
                if (mUnparsedPart.startsWith("pagh")) {
                    numberRoot = "n:num"
                } else if (mUnparsedPart.startsWith("Hoch")) {
                    // {Hoch} is a noun but not a number.
                    numberRoot = "n"
                } else if (mUnparsedPart.startsWith("'ar")) {
                    // {'ar} is a question word.
                    numberRoot = "ques"
                } else {
                    // This should never happen.
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, "getNumberRootAnnotation: else case reached")
                    }
                }
                return numberRoot
            }

        val numberModifier: String
            // Get the number modifier for a number.
            get() = numberModifierString[mNumberModifier]

        // Get the rovers at a given suffix level.
        fun getRovers(suffixLevel: Int): Array<String> {
            val negationThenEmphatic = arrayOf("-be'", "-qu'")
            val emphaticThenNegation = arrayOf("-qu'", "-be'")
            val negationOnly = arrayOf("-be'")
            val emphaticOnly = arrayOf("-qu'")
            val none = arrayOf<String>()
            if (mVerbTypeRNegation == suffixLevel && mVerbTypeREmphatic == suffixLevel) {
                return (if (roverOrderNegationBeforeEmphatic) negationThenEmphatic else emphaticThenNegation)
            } else if (mVerbTypeRNegation == suffixLevel) {
                return negationOnly
            } else if (mVerbTypeREmphatic == suffixLevel) {
                return emphaticOnly
            }
            return none
        }

        val verbPrefixString: String
            // For display.
            get() = Companion.verbPrefixString[mVerbPrefix] + (if (mVerbPrefix == 0) "" else "- + ")

        val suffixesString: String
            // For display.
            get() {
                var suffixesString = ""
                // Verb suffixes have to go first, since some can convert a verb to a noun.
                for (i in mVerbSuffixes.indices) {
                    val suffixes = verbSuffixesStrings[i]
                    if (mVerbSuffixes[i] != 0) {
                        suffixesString += " + -"
                        suffixesString += suffixes[mVerbSuffixes[i]]
                    }
                    if (mVerbTypeRNegation == i && mVerbTypeREmphatic == i) {
                        suffixesString += if (roverOrderNegationBeforeEmphatic) {
                            " + -be' + qu'"
                        } else {
                            " + -qu' + be'"
                        }
                    } else if (mVerbTypeRNegation == i) {
                        suffixesString += " + -be'"
                    } else if (mVerbTypeREmphatic == i) {
                        suffixesString += " + -qu'"
                    }
                }
                // Noun suffixes.
                for (j in mNounSuffixes.indices) {
                    val suffixes = nounSuffixesStrings[j]
                    if (mNounSuffixes[j] != 0) {
                        suffixesString += " + -"
                        suffixesString += suffixes[mNounSuffixes[j]]
                    }
                }
                return suffixesString
            }

        val adjectivalVerbWithType5NounSuffix: ComplexWord?
            get() {
                // Note that even if there is a rover, which is legal on a verb acting adjectivally,
                // it's hidden by the type 5 noun suffix and hence at this point we consider the
                // word a bare word.
                if (mIsNounCandidate || !isBareWord) {
                    // This should never be reached.
                    if (BuildConfig.DEBUG) {
                        Log.e(
                            TAG,
                            "getAdjectivalVerbWithType5NounSuffix: is noun candidate or is not bare word"
                        )
                    }
                    return null
                }

                // Count from 1 since 0 corresponds to no such suffix.
                // Note that {-mo'} is both a type 5 noun suffix and a type 9 verb suffix.
                for (i in 1 until nounType5String.size) {
                    if (mUnparsedPart.endsWith(nounType5String[i])) {
                        val adjectivalVerb =
                            mUnparsedPart.substring(
                                0,
                                mUnparsedPart.length - nounType5String[i].length
                            )
                        val adjectivalVerbWithType5NounSuffix =
                            ComplexWord(adjectivalVerb,  /* isNounCandidate */false)

                        // Note that type 5 corresponds to index 4 since the array is 0-indexed.
                        adjectivalVerbWithType5NounSuffix.mNounSuffixes[4] = i
                        adjectivalVerbWithType5NounSuffix.mIsVerbWithType5NounSuffix = true

                        // Done processing.
                        adjectivalVerbWithType5NounSuffix.mSuffixLevel = 0

                        // Since none of the type 5 noun suffixes are a prefix of another, it's okay to return
                        // here.
                        return adjectivalVerbWithType5NounSuffix
                    }
                }
                return null
            }

        val verbRootIfNoun: ComplexWord?
            get() {
                if (!mIsNounCandidate || !hasNoMoreSuffixes()) {
                    // Should never be reached if there are still suffixes remaining.
                    return null
                }

                // Log.d(TAG, "getVerbRootIfNoun on: " + mUnparsedPart);

                // If the unparsed part ends in a suffix that nominalises a verb ({-wI'}, {-ghach}), analysize
                // it further.
                // Do this only if there were noun suffixes, since the bare noun will be analysed as a verb
                // anyway.
                if (!noNounSuffixesFound()
                    && (mUnparsedPart.endsWith("ghach") || mUnparsedPart.endsWith("wI'"))
                ) {
                    // Log.d(TAG, "Creating verb from: " + mUnparsedPart);
                    val complexVerb = ComplexWord(mUnparsedPart,  /* complexWordToCopy */this)
                    complexVerb.mIsNounCandidate = false
                    complexVerb.mSuffixLevel = complexVerb.mVerbSuffixes.size
                    return complexVerb
                }
                return null
            }

        fun attachPrefix(prefix: String?) {
            if (mIsNounCandidate) {
                return
            }
            for (i in 1 until Companion.verbPrefixString.size) {
                if (prefix == Companion.verbPrefixString[i] + "-") {
                    mVerbPrefix = i
                    break
                }
            }
        }

        // Attaches a suffix. Returns the level of the suffix attached.
        fun attachSuffix(suffix: String?, isNounSuffix: Boolean, verbSuffixLevel: Int): Int {
            // Note that when a complex word noun is formed from a verb with {-wI'} or {-ghach}, its
            // stem is considered to be a verb. Furthermore, an adjectival verb can take a type 5
            // noun suffix. Noun suffixes can thus be attached to verbs. The isNounSuffix variable
            // here indicates the type of the suffix, not the type of the stem.

            // Special handling of {-DIch} and {-logh}.

            if (suffix == "-DIch" || suffix == "-logh") {
                mIsNumberLike = true
                numberSuffix = suffix.substring(1) // strip initial "-"
                return verbSuffixLevel
            }

            if (isNounSuffix) {
                // This is a noun suffix. Iterate over noun suffix types.
                for (i in nounSuffixesStrings.indices) {
                    // Count from 1, since 0 corresponds to no suffix of that type.
                    for (j in 1 until nounSuffixesStrings[i].size) {
                        if (suffix == "-" + nounSuffixesStrings[i][j]) {
                            mNounSuffixes[i] = j

                            // The verb suffix level hasn't changed.
                            return verbSuffixLevel
                        }
                    }
                }
            } else {
                // This is a verb suffix. Check if this is a true rover.
                if (suffix == "-be'") {
                    mVerbTypeRNegation = verbSuffixLevel
                    if (mVerbTypeREmphatic == verbSuffixLevel) {
                        // {-qu'be'}
                        roverOrderNegationBeforeEmphatic = false
                    }
                    return verbSuffixLevel
                } else if (suffix == "-qu'") {
                    mVerbTypeREmphatic = verbSuffixLevel
                    if (mVerbTypeRNegation == verbSuffixLevel) {
                        // {-be'qu'}
                        roverOrderNegationBeforeEmphatic = true
                    }
                    return verbSuffixLevel
                }
                // Iterate over verb suffix types.
                for (i in verbSuffixesStrings.indices) {
                    // Count from 1, since 0 corresponds to no suffix of that type.
                    for (j in 1 until verbSuffixesStrings[i].size) {
                        if (suffix == "-" + verbSuffixesStrings[i][j]) {
                            mVerbSuffixes[i] = j

                            // The verb suffix level has been changed.
                            return i
                        }
                    }
                }
            }
            // This should never be reached.
            Log.e(TAG, "Unrecognised suffix: $suffix")
            return verbSuffixLevel
        }

        // Add this complex word to the list.
        fun addSelf(complexWordsList: ArrayList<ComplexWord>) {
            if (!hasNoMoreSuffixes()) {
                // This point should never be reached.
                Log.e(
                    TAG, "addSelf called on $mUnparsedPart with suffix level $mSuffixLevel."
                )
                return
            }
            Log.d(TAG, "Found: $this")

            // Determine if this is a number. Assume that a number is of the form
            // "digit[modifier][suffix]", where digit is {wa'}, etc., modifier is a power of ten such as
            // {maH}, and suffix is one of {-DIch} or {-logh}.
            if (mIsNounCandidate) {
                // Check for {-DIch} or {-logh}.

                var numberRoot = mUnparsedPart
                if (mUnparsedPart.endsWith("DIch") || (isBareWord && mUnparsedPart.endsWith("logh"))) {
                    val rootLength = mUnparsedPart.length - 4
                    numberRoot = mUnparsedPart.substring(0, rootLength)
                    numberSuffix = mUnparsedPart.substring(rootLength)
                }

                // Check for a "power of ten" modifier, such as {maH}.
                // Count from 1, since 0 corresponds to no modifier.
                for (i in 1 until numberModifierString.size) {
                    if (numberRoot.endsWith(numberModifierString[i])) {
                        mNumberModifier = i
                        numberRoot =
                            numberRoot.substring(
                                0,
                                numberRoot.length - numberModifierString[i].length
                            )
                        break
                    }
                }

                // Look for a digit from {wa'} to {Hut}. {pagh} is excluded for now.
                // Count from 1, since 0 corresponds to no digit.
                for (j in 1 until numberDigitString.size) {
                    if (numberRoot == numberDigitString[j]) {
                        // Found a digit, so this is a number.
                        // Note that we leave mUnparsedPart alone, since we still want to add, e.g., {wa'DIch}
                        // as a result.
                        mNumberDigit = j
                        mIsNumberLike = true
                        break
                    }
                }
                // If there is no modifier or suffix, then ignore this as the bare
                // digit word will already be added.
                if (mNumberModifier == 0 && numberSuffix == "") {
                    mNumberDigit = 0
                    mIsNumberLike = false
                }

                // Finally, treat these words specially: {'arlogh}, {paghlogh}, {Hochlogh}, {paghDIch},
                // {HochDIch}.
                if (numberSuffix != ""
                    && (numberRoot == "pagh" || numberRoot == "Hoch" || numberRoot == "'ar")
                ) {
                    // We don't set mUnparsedPart to the root, because we still want the entire
                    // word (e.g., {paghlogh}) to be added to the results if it is in the database.
                    mIsNumberLike = true
                }
            }

            // Add this complex word.
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "adding word to complex words list: $mUnparsedPart")
            }
            complexWordsList.add(this)
        }

        companion object {
            // The noun suffixes.
            var nounType1String: Array<String> = arrayOf("", "'a'", "Hom", "oy")
            var nounType2String: Array<String> = arrayOf("", "pu'", "Du'", "mey")
            var nounType3String: Array<String> = arrayOf("", "qoq", "Hey", "na'")
            var nounType4String: Array<String> = arrayOf(
                "",
                "wIj",
                "wI'",
                "maj",
                "ma'",
                "lIj",
                "lI'",
                "raj",
                "ra'",
                "Daj",
                "chaj",
                "vam",
                "vetlh"
            )
            var nounType5String: Array<String> = arrayOf("", "Daq", "vo'", "mo'", "vaD", "'e'")
            var nounSuffixesStrings: Array<Array<String>> = arrayOf(
                nounType1String, nounType2String, nounType3String, nounType4String, nounType5String
            )

            // The verb prefixes.
            var verbPrefixString: Array<String> = arrayOf(
                "",
                "bI",
                "bo",
                "che",
                "cho",
                "Da",
                "DI",
                "Du",
                "gho",
                "HI",
                "jI",
                "ju",
                "lI",
                "lu",
                "ma",
                "mu",
                "nI",
                "nu",
                "pe",
                "pI",
                "qa",
                "re",
                "Sa",
                "Su",
                "tI",
                "tu",
                "vI",
                "wI",
                "yI"
            )
            var verbTypeRUndoString: Array<String> =
                arrayOf( // {-Ha'} always occurs immediately after
                    // the verb.
                    "", "Ha'"
                )
            var verbType1String: Array<String> = arrayOf("", "'egh", "chuq")
            var verbType2String: Array<String> = arrayOf("", "nIS", "qang", "rup", "beH", "vIp")
            var verbType3String: Array<String> = arrayOf("", "choH", "qa'")
            var verbType4String: Array<String> = arrayOf("", "moH")
            var verbType5String: Array<String> = arrayOf("", "lu'", "laH", "luH", "la'")
            var verbType6String: Array<String> = arrayOf("", "chu'", "bej", "ba'", "law'")
            var verbType7String: Array<String> = arrayOf("", "pu'", "ta'", "taH", "lI'")
            var verbType8String: Array<String> = arrayOf("", "neS")
            var verbTypeRRefusal: Array<String> = arrayOf( // {-Qo'} always occurs last, unless
                // followed by a type 9 suffix.
                "", "Qo'"
            )
            var verbType9String: Array<String> = arrayOf(
                "", "DI'", "chugh", "pa'", "vIS", "mo'", "bogh", "meH", "'a'", "jaj", "wI'", "ghach"
            )
            var verbSuffixesStrings: Array<Array<String>> = arrayOf(
                verbTypeRUndoString,
                verbType1String,
                verbType2String,
                verbType3String,
                verbType4String,
                verbType5String,
                verbType6String,
                verbType7String,
                verbType8String,
                verbTypeRRefusal,
                verbType9String
            )
            var numberDigitString: Array<String> = arrayOf( // {pagh} is excluded because it should
                // normally not form part of a number with
                // modifiers.
                "", "wa'", "cha'", "wej", "loS", "vagh", "jav", "Soch", "chorgh", "Hut"
            )
            var numberModifierString: Array<String> = arrayOf(
                "",  // Since matching is greedy, compound number-forming elements have to come first.

                "maH'uy'",
                "vatlhbIp",  // 10 000 000
                "vatlh'uy'",
                "SaDbIp",
                "SanIDbIp",  // 100 000 000
                "maHSaghan",  // 10 000 000 000
                "vatlhSaghan",
                "bIp'uy'",  // 100 000 000 000
                "SaDSaghan",
                "SanIDSaghan",  // 1 000 000 000 000
                // Basic number-forming elements.

                "maH",  // 10
                "vatlh",  // 100
                "SaD",
                "SanID",  // 1000
                "netlh",  // 10 000
                "bIp",  // 100 000
                "'uy'",  // 1 000 000
                "Saghan",  // 1 000 000 000
            )
            private const val ROVER_NOT_YET_FOUND = -1
            private const val IGNORE_THIS_ROVER = -2
        }
    }

    companion object {
        private const val TAG = "KlingonContentProvider"

        var AUTHORITY: String = "org.tlhInganHol.android.klingonassistant.KlingonContentProvider"
        @JvmField
        val CONTENT_URI: Uri = Uri.parse("content://" + AUTHORITY)

        // MIME types used for searching entries or looking up a single definition
        const val ENTRIES_MIME_TYPE: String =
            ContentResolver.CURSOR_DIR_BASE_TYPE + "/org.tlhInganHol.android.klingonassistant"
        const val DEFINITION_MIME_TYPE: String =
            ContentResolver.CURSOR_ITEM_BASE_TYPE + "/org.tlhInganHol.android.klingonassistant"

        /**
         * The columns we'll include in our search suggestions. There are others that could be used to
         * further customize the suggestions, see the docs in [SearchManager] for the details on
         * additional columns that are supported.
         */
        private val SUGGESTION_COLUMNS = arrayOf(
            BaseColumns._ID,  // must include this column
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,  // SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID,
        )

        // UriMatcher stuff
        private const val SEARCH_ENTRIES = 0
        private const val GET_ENTRY = 1
        private const val SEARCH_SUGGEST = 2
        private const val REFRESH_SHORTCUT = 3
        private const val GET_ENTRY_BY_ID = 4
        private const val GET_RANDOM_ENTRY = 5
        private val sURIMatcher = buildUriMatcher()

        /** Builds up a UriMatcher for search suggestion and shortcut refresh queries.  */
        private fun buildUriMatcher(): UriMatcher {
            val matcher = UriMatcher(UriMatcher.NO_MATCH)
            // to get definitions...
            matcher.addURI(AUTHORITY, "lookup", SEARCH_ENTRIES)
            // by database row (which is not the same as its id)...
            matcher.addURI(AUTHORITY, "lookup/#", GET_ENTRY)
            // to get suggestions...
            matcher.addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY, SEARCH_SUGGEST)
            matcher.addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY + "/*", SEARCH_SUGGEST)

            // This is needed internally to get an entry by its id.
            matcher.addURI(AUTHORITY, "get_entry_by_id/#", GET_ENTRY_BY_ID)

            // This is needed internally to get a random entry.
            matcher.addURI(AUTHORITY, "get_random_entry", GET_RANDOM_ENTRY)

            /*
     * The following are unused in this implementation, but if we include {@link
     * SearchManager#SUGGEST_COLUMN_SHORTCUT_ID} as a column in our suggestions table, we could
     * expect to receive refresh queries when a shortcutted suggestion is displayed in Quick Search
     * Box, in which case, the following Uris would be provided and we would return a cursor with a
     * single item representing the refreshed suggestion data.
     */
            matcher.addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_SHORTCUT, REFRESH_SHORTCUT)
            matcher.addURI(
                AUTHORITY,
                SearchManager.SUGGEST_URI_PATH_SHORTCUT + "/*",
                REFRESH_SHORTCUT
            )
            return matcher
        }

        fun convertStringToKlingonFont(s: String?): String {
            // Strip anything we don't recognise.
            // This pattern should be kept mostly in sync with ENTRY_PATTERN. Note that "ü" and "+" will
            // never be in an entry name.
            var klingonString =
                s!!.replace("[^A-Za-z0-9 '\\\":;,\\.\\-?!_/()@=%&\\*]".toRegex(), "")

            // This is a hack: change the separators between words and their affixes.
            // TODO: Do this upstream and colour the affixes differently.
            klingonString =
                klingonString
                    .replace(" + -".toRegex(), " ◃ ")
                    .replace("- + ".toRegex(), " ▹ ")
                    .replace("^-".toRegex(), "◃ ")
                    .replace("-$".toRegex(), " ▹")

            // {gh} must come before {ngh} since {ngh} is {n} + {gh} and not {ng} + *{h}.
            // {ng} must come before {n}.
            // {tlh} must come before {t} and {l}.
            // Don't change {-} since it's needed for prefixes and suffixes.
            // Don't change "..." (ellipses), but do change "." (periods).
            klingonString =
                klingonString
                    .replace("gh".toRegex(), "")
                    .replace("ng".toRegex(), "")
                    .replace("tlh".toRegex(), "")
                    .replace("a".toRegex(), "")
                    .replace("b".toRegex(), "")
                    .replace("ch".toRegex(), "")
                    .replace("D".toRegex(), "")
                    .replace("e".toRegex(), "")
                    .replace("H".toRegex(), "")
                    .replace("I".toRegex(), "")
                    .replace("j".toRegex(), "")
                    .replace("l".toRegex(), "")
                    .replace("m".toRegex(), "")
                    .replace("n".toRegex(), "")
                    .replace("o".toRegex(), "")
                    .replace("p".toRegex(), "")
                    .replace("q".toRegex(), "")
                    .replace("Q".toRegex(), "")
                    .replace("r".toRegex(), "")
                    .replace("S".toRegex(), "")
                    .replace("t".toRegex(), "")
                    .replace("u".toRegex(), "")
                    .replace("v".toRegex(), "")
                    .replace("w".toRegex(), "")
                    .replace("y".toRegex(), "")
                    .replace("'".toRegex(), "")
                    .replace("0".toRegex(), "")
                    .replace("1".toRegex(), "")
                    .replace("2".toRegex(), "")
                    .replace("3".toRegex(), "")
                    .replace("4".toRegex(), "")
                    .replace("5".toRegex(), "")
                    .replace("6".toRegex(), "")
                    .replace("7".toRegex(), "")
                    .replace("8".toRegex(), "")
                    .replace("9".toRegex(), "")
                    .replace(",".toRegex(), "")
                    .replace(";".toRegex(), "")
                    .replace("!".toRegex(), "")
                    .replace("\\(".toRegex(), "▹")
                    .replace("\\)".toRegex(), "◃")
                    .replace("-".toRegex(), "◃")
                    .replace("\\?".toRegex(), "")
                    .replace(
                        "\\.".toRegex(),
                        ""
                    ) // Note: The LHS is in Klingon due to previous replacements.
                    // We replace three periods in a row with an ellipsis.
                    .replace("".toRegex(), "⋯")
            return klingonString
        }

        // Attempt to parse this complex word, and if successful, add it to the given set.
        fun parseComplexWord(
            candidate: String, isNounCandidate: Boolean, complexWordsList: ArrayList<ComplexWord>
        ) {
            val complexWord = ComplexWord(candidate, isNounCandidate)
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG, """

* parsing = $candidate (${if (isNounCandidate) "n" else "v"}) *"""
                )
            }
            if (!isNounCandidate) {
                // Check prefix.
                val strippedPrefixComplexWord = complexWord.stripPrefix()
                if (strippedPrefixComplexWord != null) {
                    // Branch off a word with the prefix stripped.
                    stripSuffix(strippedPrefixComplexWord, complexWordsList)
                }
            }
            // Check suffixes.
            stripSuffix(complexWord, complexWordsList)
        }

        // Helper method to strip a level of suffix from a word.
        private fun stripSuffix(
            complexWord: ComplexWord?, complexWordsList: ArrayList<ComplexWord>
        ) {
            var complexWord = complexWord
            if (complexWord!!.hasNoMoreSuffixes()) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "attempting to add to complex words list: " + complexWord.mUnparsedPart
                    )
                }
                complexWord.addSelf(complexWordsList)

                if (complexWord.mIsNounCandidate) {
                    // Attempt to get the verb root of this word if it's a noun.
                    complexWord = complexWord.verbRootIfNoun
                } else if (complexWord.isBareWord) {
                    // Check for type 5 noun suffix on a possibly adjectival verb.
                    complexWord = complexWord.adjectivalVerbWithType5NounSuffix
                    if (complexWord != null) {
                        val adjectivalVerb = complexWord.stem()
                        if (adjectivalVerb.endsWith("be'")
                            || adjectivalVerb.endsWith("qu'")
                            || adjectivalVerb.endsWith("Ha'")
                        ) {
                            val adjectivalVerbWithoutRover =
                                adjectivalVerb.substring(0, adjectivalVerb.length - 3)
                            // Adjectival verbs may end with a rover (except for {-Qo'}), so check for that here.
                            val anotherComplexWord =
                                ComplexWord(adjectivalVerbWithoutRover, complexWord)
                            if (adjectivalVerb.endsWith("be'")) {
                                anotherComplexWord.mVerbTypeRNegation = 0
                            } else if (adjectivalVerb.endsWith("qu'")) {
                                anotherComplexWord.mVerbTypeREmphatic = 0
                            } else if (adjectivalVerb.endsWith("Ha'")) {
                                anotherComplexWord.mVerbSuffixes[0] = 1
                            }
                            stripSuffix(anotherComplexWord, complexWordsList)
                        }
                    }
                } else {
                    // We're done.
                    complexWord = null
                }

                if (complexWord == null) {
                    // Not a noun or the noun has no further verb root, so we're done with this complex word.
                    return
                }
                // Note that at this point we continue with a newly created complex word.
            }

            if (BuildConfig.DEBUG) {
                val suffixType = if (complexWord.mIsNounCandidate) {
                    // Noun suffix level corresponds to the suffix type.
                    "type " + complexWord.mSuffixLevel
                } else {
                    // Verb suffix level doesn't correspond exactly: {-Ha'}, types 1 through 8, {-Qo'}, then 9.
                    if (complexWord.mSuffixLevel == 1) {
                        "-Ha'"
                    } else if (complexWord.mSuffixLevel == 10) {
                        "-Qo'"
                    } else if (complexWord.mSuffixLevel == 11) {
                        "type 9"
                    } else {
                        "type " + (complexWord.mSuffixLevel - 1)
                    }
                }
                Log.d(
                    TAG,
                    "stripSuffix called on {"
                            + complexWord.mUnparsedPart
                            + "} for "
                            + (if (complexWord.mIsNounCandidate) "noun" else "verb")
                            + " suffix: "
                            + suffixType
                )
            }

            // Special check for the suffix {-oy} attached to a noun ending in a vowel. This needs to be
            // done additionally to the regular check, since it may be possible to parse a word either way,
            // e.g., {ghu'oy} could be {ghu} + {-'oy} or {ghu'} + {-oy}.
            val apostropheOyComplexWord = complexWord.maybeStripApostropheOy()
            if (apostropheOyComplexWord != null) {
                // "'oy" was stripped, branch using it as a new candidate.
                stripSuffix(apostropheOyComplexWord, complexWordsList)
            }

            // Attempt to strip one level of suffix.
            val strippedSuffixComplexWord = complexWord.stripSuffixAndBranch()
            if (strippedSuffixComplexWord != null) {
                // A suffix of the current type was found, branch using it as a new candidate.
                stripSuffix(strippedSuffixComplexWord, complexWordsList)
            }
            // Tail recurse to the next level of suffix. Note that the suffix level is decremented in
            // complexWord.stripSuffixAndBranch() above.
            stripSuffix(complexWord, complexWordsList)
        }
    }
}
