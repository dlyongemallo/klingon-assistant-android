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
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteQueryBuilder
import android.preference.PreferenceManager
import android.provider.BaseColumns
import android.util.Log
import android.widget.Toast
import org.tlhInganHol.android.klingonassistant.KlingonContentProvider.ComplexWord
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Random

/**
 * Contains logic to return specific entries from the database, and load the database table when it
 * needs to be created.
 */
class KlingonContentDatabase(context: Context?) {
    // Create a database helper to access the Klingon Database.
    private val mDatabaseOpenHelper = KlingonDatabaseOpenHelper(context)
    private val mContext = context

    /**
     * Constructor
     *
     * @param context The Context within which to work, used to create the DB
     */
    init {
        // Initialise the database, and create it if necessary.
        try {
            // Log.d(TAG, "1. Initialising db.");
            mDatabaseOpenHelper.initDatabase()
        } catch (e: IOException) {
            throw Error("Unable to create database.")
        }

        // Open the database for use.
        try {
            // Log.d(TAG, "2. Opening db.");
            mDatabaseOpenHelper.openDatabase()
        } catch (e: SQLException) {
            // Possibly an attempt to write a readonly database.
            // Do nothing.
        }
    }

    /**
     * Returns a Cursor positioned at the entry specified by rowId
     *
     * @param rowId id of entry to retrieve
     * @param columns The columns to include, if null then all are included
     * @return Cursor positioned to matching entry, or null if not found.
     */
    fun getEntry(rowId: String?, columns: Array<String>): Cursor? {
        // Log.d(TAG, "getEntry called with rowId: " + rowId);

        val selection = "rowid = ?"
        val selectionArgs = arrayOf(rowId)

        /*
     * This builds a query that looks like: SELECT <columns> FROM <table> WHERE rowid = <rowId>
     */
        return query(selection, selectionArgs, columns)
    }

    /**
     * Convert a string written in "xifan hol" shorthand to {tlhIngan Hol}. This is a mapping which
     * makes it easier to type, since shifting is unnecessary.
     *
     *
     * Make the following replacements: d -> D f -> ng h -> H (see note below) i -> I k -> Q s -> S
     * x -> tlh z -> '
     *
     *
     * When replacing "h" with "H", the following must be preserved: ch -> ch gh -> gh tlh -> tlh
     * ngh -> ngh (n + gh) ngH -> ngH (ng + H)
     *
     *
     * TODO: Consider allowing "invisible h". But this probably makes things too "loose". // c ->
     * ch (but ch -/> chh) // g -> gh (but gh -/> ghh and ng -/> ngh)
     */
    private fun expandShorthand(shorthand: String?): String? {
        var shorthand = shorthand
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)
        if (!sharedPrefs.getBoolean(
                Preferences.Companion.KEY_XIFAN_HOL_CHECKBOX_PREFERENCE,  /* default */false
            )
        ) {
            // The user has disabled the "xifan hol" shorthand, so just do nothing and return.
            return shorthand
        }
        if (sharedPrefs.getBoolean(
                Preferences.Companion.KEY_SWAP_QS_CHECKBOX_PREFERENCE,  /* default */
                false
            )
        ) {
            // Map q to Q and k to q.
            shorthand = shorthand!!.replace("q".toRegex(), "Q")
            shorthand = shorthand.replace("k".toRegex(), "q")
        }

        // Note: The order of the replacements is important.
        return shorthand
            .replace("ngH".toRegex(), "NGH") // differentiate "ngh" from "ngH"
            .replace(
                "h".toRegex(),
                "H"
            ) // side effects: ch -> cH, gh -> gH (also ngh -> ngH), tlh -> tlH
            .replace("cH".toRegex(), "ch") // restore "ch"
            .replace("gH".toRegex(), "gh") // restore "gh" (also "ngh")
            .replace("tlH".toRegex(), "tlh") // restore "tlh"
            .replace("g".toRegex(), "gX") // g -> gX, side effects: gh -> gXh, ng -> ngX
            .replace("gXh".toRegex(), "gh") // restore "gh"
            .replace("ngX".toRegex(), "ng") // restore "ng"
            .replace("gX".toRegex(), "gh") // g -> gh
            .replace("NGH".toRegex(), "ngH") // restore "ngH"
            .replace("c".toRegex(), "cX") // c -> cX, side effect: ch -> cXh
            .replace("cXh".toRegex(), "ch") // restore "ch"
            .replace("cX".toRegex(), "ch") // c -> ch
            .replace("d".toRegex(), "D") // do unambiguous replacements
            .replace("f".toRegex(), "ng")
            .replace("i".toRegex(), "I")
            .replace(
                "k".toRegex(),
                "Q"
            ) // If the swap Qs preference was selected, this will have no effect.
            .replace("s".toRegex(), "S")
            .replace("z".toRegex(), "'")
            .replace(
                "x".toRegex(),
                "tlh"
            ) // At this point, "ngH" is definitely {ng} + {H}, but "ngh" might be either {n} + {gh} or
            // {ng} + {H}. Furthermore, "ng" might be {ng} or {n} + {gh}.
            // These are the possible words with {n} + {gh}: {nenghep}, {QIngheb}, {tlhonghaD}
            // These are the possible words with {ng} + {H}: {chungHa'wI'}, {mangHom}, {qengHoD},
            // {tungHa'}, {vengHom}. Instead of checking both, cheat by hardcoding the possibilities.
            // This means this code has to be updated whenever an entry with {ngH} or {ngh} is
            // added to the database.
            .replace("(chung|mang|qeng|tung|veng)h".toRegex(), "$1H")
            .replace("Hanguq".toRegex(), "Hanghuq")
            .replace("nengep".toRegex(), "nenghep")
            .replace("QIngeb".toRegex(), "QIngheb")
            .replace("tlhongaD".toRegex(), "tlhonghaD")
    }

    private fun IsPotentialComplexWordOrSentence(
        queryEntry: KlingonContentProvider.Entry, query: String?
    ): Boolean {
        // If the POS is unknown and the query is greater than 4 characters, try to parse it
        // as a complex word or sentence. Most queries of 4 characters or fewer are not complex,
        // so for efficiency reasons we don't try to parse them, but there are a few exceptional
        // verbs which are two letters long, which need to be handled as a special case.
        if (queryEntry.basePartOfSpeechIsUnknown()) {
            if (query!!.length > 4) {
                return true
            }

            // A shortlist of two-letter verbs. These plus a prefix or suffix might make a 4-character
            // complex word. This check needs to be updated whenever a 2-letter verb is added to the
            // database.
            if (query.length == 4) {
                when (query.substring(2, 4)) {
                    "Da", "lu", "Qa", "Qu", "Sa", "tu", "yo" -> return true
                }
            }
        }
        return false
    }

    /**
     * Returns a Cursor over all entries that match the given query.
     *
     * @param query The query, including entry name and metadata, to search for.
     * @return Cursor over all entries that match, or null if none found.
     */
    fun getEntryMatches(query: String): Cursor? {
        // A query may be preceded by a plus to override (disable) "xifan hol" mode. This is used
        // for internal searches.
        var query = query
        var overrideXifanHol = false
        if (!query.isEmpty() && query[0] == '+') {
            overrideXifanHol = true
            query = query.substring(1)
        }

        // Sanitize input.
        query = sanitizeInput(query)

        // Log.d(TAG, "getEntryMatches called with query: \"" + query + "\"");
        val resultsCursor = MatrixCursor(ALL_KEYS)
        val resultsSet = HashSet<Int>()

        // Parse the query's metadata, and get the base query.
        val queryEntry = KlingonContentProvider.Entry(query, mContext)
        val queryBase = queryEntry.entryName

        // If the query has components specified, then we're in analysis mode, and the solution is
        // already given to us.
        val analysisComponents =
            queryEntry.componentsAsEntries
        if (!analysisComponents!!.isEmpty()) {
            // Add the given list of components to the results.
            addGivenComponentsToResults(analysisComponents, resultsCursor, resultsSet)

            // Finally, add the complete query entry itself.
            addExactMatch(queryBase, queryEntry, resultsCursor,  /* indent */false)

            // Since the components are in the db, do no further analysis.
            return resultsCursor
        }

        val looseQuery: String?
        if (query.indexOf(':') != -1) {
            // If this is a system query, don't use "xifan hol" loosening.
            looseQuery = queryBase
            if (queryBase == "*" && queryEntry.isSentence) {
                // Specifically, if this is a query for a sentence class, search exactly for the matching
                // sentences.
                // We know the query begins with "*:" so strip that to get the sentence class.
                return getMatchingSentences(query.substring(2))
            }
        } else if (overrideXifanHol) {
            looseQuery = queryBase
        } else {
            // Assume the user is searching for an "exact" Klingon word or phrase, subject to
            // "xifan hol" loosening (if enabled).
            looseQuery = expandShorthand(queryBase)
        }

        // TODO: Add option to search English and other-language fields first, followed by Klingon.
        // (Many users are searching for a Klingon word using a non-Klingon search query, rather
        // than the other way around.)
        if (IsPotentialComplexWordOrSentence(queryEntry, looseQuery)) {
            // If the query matches some heuristics, try to parse it as a complex word or sentence.
            parseQueryAsComplexWordOrSentence(looseQuery, resultsCursor, resultsSet)
        } else {
            // Otherwise, assume the base query is a prefix of the desired result.
            val resultsWithGivenPrefixCursor =
                getEntriesContainingQuery(looseQuery,  /* isPrefix */true)
            copyCursorEntries(
                resultsCursor,
                resultsSet,
                resultsWithGivenPrefixCursor,  /* filter */
                true,
                queryEntry
            )
            resultsWithGivenPrefixCursor?.close()
        }

        // If the query was made without a base part of speech, expand the
        // search to include entries not beginning with the query, and also
        // search on the (English) definition and search tags.
        if (queryEntry.basePartOfSpeechIsUnknown()) {
            // Try the entries, but not from the beginning. Limit to at
            // least 2 characters as anything less than that isn't meaningful in
            // Klingon, but 2 characters allow searching from the end for
            // "rhyming" purposes.
            val klingonNonPrefixMinLength = 2
            if (queryEntry.entryName.length >= klingonNonPrefixMinLength) {
                val resultsWithGivenQueryCursor =
                    getEntriesContainingQuery(looseQuery,  /* isPrefix */false)
                copyCursorEntries(
                    resultsCursor, resultsSet, resultsWithGivenQueryCursor,  /* filter */false, null
                )
                resultsWithGivenQueryCursor?.close()
            }

            // Match definitions, from beginning. Since the definition is (almost
            // always) canonical, always search in English. Additionally search in
            // other-language if that option is set.
            matchDefinitionsOrSearchTags(
                queryBase,
                true,  /* isPrefix */
                false,  /* useSearchTags */
                false,  /* searchOtherLanguageDefinitions */
                resultsCursor,
                resultsSet
            )
            matchDefinitionsOrSearchTags(
                queryBase,
                true,  /* isPrefix */
                false,  /* useSearchTags */
                true,  /* searchOtherLanguageDefinitions */
                resultsCursor,
                resultsSet
            )

            // Match definitions, anywhere else. Again, always search in English, and
            // additionally search in other-language if that option is set. Limit to 3
            // characters as there would be too many coincidental hits otherwise, except
            // if other-language is Chinese.
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)
            val otherLang =
                sharedPrefs.getString(
                    Preferences.Companion.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE,  /* default */
                    Preferences.Companion.getSystemPreferredLanguage()
                )
            val englishNonPrefixMinLength = 3
            val otherLanguageNonPrefixMinLength = if (otherLang == "zh-HK") 1 else 3

            if (queryEntry.entryName.length >= englishNonPrefixMinLength) {
                matchDefinitionsOrSearchTags(
                    queryBase,
                    false,  /* isPrefix */
                    false,  /* useSearchTags */
                    false,  /* searchOtherLanguageDefinitions */
                    resultsCursor,
                    resultsSet
                )
            }
            if (queryEntry.entryName.length >= otherLanguageNonPrefixMinLength) {
                matchDefinitionsOrSearchTags(
                    queryBase,
                    false,  /* isPrefix */
                    false,  /* useSearchTags */
                    true,  /* searchOtherLanguageDefinitions */
                    resultsCursor,
                    resultsSet
                )
            }

            // Match search tags, from beginning, then anywhere else.
            if (queryEntry.entryName.length >= englishNonPrefixMinLength) {
                matchDefinitionsOrSearchTags(
                    queryBase,
                    true,  /* isPrefix */
                    true,  /* useSearchTags */
                    false,  /* searchOtherLanguageDefinitions */
                    resultsCursor,
                    resultsSet
                )
            }
            if (queryEntry.entryName.length >= otherLanguageNonPrefixMinLength) {
                matchDefinitionsOrSearchTags(
                    queryBase,
                    true,  /* isPrefix */
                    true,  /* useSearchTags */
                    true,  /* searchOtherLanguageDefinitions */
                    resultsCursor,
                    resultsSet
                )
            }
            if (queryEntry.entryName.length >= englishNonPrefixMinLength) {
                matchDefinitionsOrSearchTags(
                    queryBase,
                    false,  /* isPrefix */
                    true,  /* useSearchTags */
                    false,  /* searchOtherLanguageDefinitions */
                    resultsCursor,
                    resultsSet
                )
            }
            if (queryEntry.entryName.length >= otherLanguageNonPrefixMinLength) {
                matchDefinitionsOrSearchTags(
                    queryBase,
                    false,  /* isPrefix */
                    true,  /* useSearchTags */
                    true,  /* searchOtherLanguageDefinitions */
                    resultsCursor,
                    resultsSet
                )
            }
        }

        return resultsCursor
    }

    // Helper method to add a list of components to the list of search results.
    private fun addGivenComponentsToResults(
        analysisComponents: ArrayList<KlingonContentProvider.Entry?>?,
        resultsCursor: MatrixCursor,
        resultsSet: HashSet<Int>
    ) {
        // Create a list of complex words.
        val complexWordsList =
            ArrayList<ComplexWord>()

        // Keep track of current state. The verb suffix level is required for analysing rovers.
        var currentComplexWord: ComplexWord? = null
        var currentPrefixEntry: KlingonContentProvider.Entry? = null
        var verbSuffixLevel = 0
        for (componentEntry in analysisComponents!!) {
            val componentEntryName = componentEntry.getEntryName()
            val isNoun = componentEntry!!.isNoun
            val isVerb = componentEntry.isVerb
            val isPrefix = componentEntry.isPrefix
            val isSuffix = componentEntry.isSuffix

            if (!isSuffix && (!isVerb || currentPrefixEntry == null)) {
                // A new word is about to begin, so flush a complex word if there is one.
                if (currentComplexWord != null) {
                    // We set a strict match because this is information given explicitly in the db.
                    addComplexWordToResults(
                        currentComplexWord, resultsCursor, resultsSet,  /* isLenient */false
                    )
                    currentComplexWord = null
                }
            }

            if (!isNoun && !isVerb && !isPrefix && !isSuffix) {
                // Add this word directly.
                addExactMatch(componentEntryName, componentEntry, resultsCursor,  /* indent */false)
                continue
            }

            // At this point, we know this is either a suffix, or a prefix, verb, or noun which begins a
            // new word.
            if (isSuffix && (currentComplexWord != null)) {
                // A suffix, attach to the current word.
                // Note that isNoun here indicates whether the suffix is a noun suffix, not
                // whether the stem is a noun or verb. This is important since noun suffixes
                // can be attached to nouns formed from verbs using {-wI'} or {-ghach}.
                verbSuffixLevel =
                    currentComplexWord.attachSuffix(componentEntryName, isNoun, verbSuffixLevel)
            } else if (isPrefix) {
                // A prefix, save to attach to the next verb.
                currentPrefixEntry = componentEntry
            } else if (isNoun || isVerb) {
                // Create a new complex word, so reset suffix level.
                // Note that this can be a noun, a verb, or an unattached suffix (like in the entry {...-Daq
                // qaDor.}.
                currentComplexWord = ComplexWord(componentEntryName!!, isNoun)
                currentComplexWord.setHomophoneNumber(componentEntry.homophoneNumber)
                verbSuffixLevel = 0
                if (isVerb && currentPrefixEntry != null) {
                    currentComplexWord.attachPrefix(currentPrefixEntry.entryName)
                    currentPrefixEntry = null
                }
            }
        }
        if (currentComplexWord != null) {
            // Flush any outstanding word.
            addComplexWordToResults(
                currentComplexWord,
                resultsCursor,
                resultsSet,  /* isLenient */
                false
            )
        }
    }

    // Helper method to copy entries from one cursor to another.
    // If filter is true, queryEntry must be provided.
    private fun copyCursorEntries(
        destCursor: MatrixCursor,
        destSet: HashSet<Int>,
        srcCursor: Cursor?,
        filter: Boolean,
        queryEntry: KlingonContentProvider.Entry?
    ) {
        if (srcCursor != null && srcCursor.count != 0) {
            srcCursor.moveToFirst()
            do {
                val resultEntry =
                    KlingonContentProvider.Entry(srcCursor, mContext)

                // Filter by the query if requested to do so. If filter is
                // true, the entry will be added only if it is a match that
                // satisfies certain requirements.
                if (!filter || queryEntry!!.isSatisfiedBy(resultEntry)) {
                    // Prevent duplicates.
                    val entryObject = convertEntryToCursorRow(resultEntry,  /* indent */false)
                    val intId = resultEntry.id
                    if (!destSet.contains(intId)) {
                        destSet.add(intId)
                        destCursor.addRow(entryObject)
                    }
                }
            } while (srcCursor.moveToNext())
        }

        // Modify cursor to be like query() below.
        destCursor.moveToFirst()
    }

    // Helper method to search for entries whose prefixes match the query.
    private fun getEntriesContainingQuery(queryBase: String?, isPrefix: Boolean): Cursor? {
        // Note: it is important to use the double quote character for quotes
        // because the single quote character is a letter in (transliterated)
        // Klingon. Also, force LIKE to be case-sensitive to distinguish
        // {q} and {Q}.
        val db = mDatabaseOpenHelper.readableDatabase
        db.rawQuery("PRAGMA case_sensitive_like = ON", null)
        // If the query must be a prefix of the entry name, do not precede with wildcard.
        val precedingWildcard = if (isPrefix) "" else "%"
        var cursor: Cursor? = null
        try {
            cursor =
                db.query(
                    true,
                    FTS_VIRTUAL_TABLE,
                    ALL_KEYS,
                    KEY_ENTRY_NAME
                            + " LIKE \""
                            + precedingWildcard
                            + queryBase!!.trim { it <= ' ' }
                            + "%\"",
                    null,
                    null,
                    null,
                    null,
                    null)
        } catch (e: SQLiteException) {
            // Do nothing.
        }
        return cursor
    }

    // Helper method to search for an exact match.
    private fun getExactMatches(entryName: String?): Cursor? {
        val db = mDatabaseOpenHelper.readableDatabase
        db.rawQuery("PRAGMA case_sensitive_like = ON", null)
        var cursor: Cursor? = null
        try {
            cursor =
                db.query(
                    true,
                    FTS_VIRTUAL_TABLE,
                    ALL_KEYS,
                    KEY_ENTRY_NAME + " LIKE \"" + entryName!!.trim { it <= ' ' } + "\"",
                    null,
                    null,
                    null,
                    null,
                    null)
        } catch (e: SQLiteException) {
            // Do nothing.
        }
        return cursor
    }

    // Helper method to search for a sentence class.
    private fun getMatchingSentences(sentenceClass: String): Cursor? {
        val db = mDatabaseOpenHelper.readableDatabase
        db.rawQuery("PRAGMA case_sensitive_like = ON", null)
        var cursor: Cursor? = null
        try {
            cursor =
                db.query(
                    true,
                    FTS_VIRTUAL_TABLE,
                    ALL_KEYS,
                    KEY_PART_OF_SPEECH + " LIKE \"" + sentenceClass + "\"",
                    null,
                    null,
                    null,
                    null,
                    null
                )
        } catch (e: SQLiteException) {
            // Do nothing.
        }
        return cursor
    }

    // Helper method to search for entries whose definitions or search tags match the query.
    // Note that matches are case-insensitive.
    private fun getEntriesMatchingDefinition(
        piece: String?,
        isPrefix: Boolean,
        useSearchTags: Boolean,
        searchOtherLanguageDefinitions: Boolean
    ): Cursor? {
        // The search key is either the definition or the search tags.

        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)
        val otherLang =
            sharedPrefs.getString(
                Preferences.Companion.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE,  /* default */
                Preferences.Companion.getSystemPreferredLanguage()
            )
        var key =
            if (useSearchTags
            ) KEY_SEARCH_TAGS
            else KEY_DEFINITION
        if (searchOtherLanguageDefinitions) {
            when (otherLang) {
                "de" -> key =
                    if (useSearchTags
                    ) KEY_SEARCH_TAGS_DE
                    else KEY_DEFINITION_DE

                "fa" -> key =
                    if (useSearchTags
                    ) KEY_SEARCH_TAGS_FA
                    else KEY_DEFINITION_FA

                "ru" -> key =
                    if (useSearchTags
                    ) KEY_SEARCH_TAGS_RU
                    else KEY_DEFINITION_RU

                "sv" -> key =
                    if (useSearchTags
                    ) KEY_SEARCH_TAGS_SV
                    else KEY_DEFINITION_SV

                "zh-HK" -> key =
                    if (useSearchTags
                    ) KEY_SEARCH_TAGS_ZH_HK
                    else KEY_DEFINITION_ZH_HK

                "pt" -> key =
                    if (useSearchTags
                    ) KEY_SEARCH_TAGS_PT
                    else KEY_DEFINITION_PT

                "fi" -> key =
                    if (useSearchTags
                    ) KEY_SEARCH_TAGS_FI
                    else KEY_DEFINITION_FI

                "fr" -> key =
                    if (useSearchTags
                    ) KEY_SEARCH_TAGS_FR
                    else KEY_DEFINITION_FR
            }
        }

        // If searching for a prefix (here, this means not a verb prefix, but
        // a query which is a prefix of the definition), nothing can precede
        // the query; otherwise, it must be preceded by a space (it begins a word),
        // except in Chinese.
        val nonPrefixPrecedingWildCard = if (otherLang == "zh-HK") "%" else "% "
        val precedingWildcard = if (isPrefix) "" else nonPrefixPrecedingWildCard

        val db = mDatabaseOpenHelper.readableDatabase
        db.rawQuery("PRAGMA case_sensitive_like = OFF", null)

        var cursor: Cursor? = null
        try {
            cursor =
                db.query(
                    true,
                    FTS_VIRTUAL_TABLE,
                    ALL_KEYS,
                    key + " LIKE \"" + precedingWildcard + piece!!.trim { it <= ' ' } + "%\"",
                    null,
                    null,
                    null,
                    null,
                    null)
        } catch (e: SQLiteException) {
            // Do nothing.
        }
        return cursor
    }

    // Helper method to make it easier to search either definitions or search tags, in either English
    // or other-language.
    private fun matchDefinitionsOrSearchTags(
        piece: String?,
        isPrefix: Boolean,
        useSearchTags: Boolean,
        searchOtherLanguageDefinitions: Boolean,
        resultsCursor: MatrixCursor,
        resultsSet: HashSet<Int>
    ) {
        val matchingResults =
            getEntriesMatchingDefinition(
                piece, isPrefix, useSearchTags, searchOtherLanguageDefinitions
            )
        copyCursorEntries(resultsCursor, resultsSet, matchingResults,  /* filter */false, null)
        matchingResults?.close()
    }

    // Helper method to add one exact match to the results cursor.
    private fun addExactMatch(
        query: String?,
        filterEntry: KlingonContentProvider.Entry?,
        resultsCursor: MatrixCursor,
        indent: Boolean
    ) {
        val exactMatchesCursor = getExactMatches(query)
        // There must be a match.
        if (exactMatchesCursor == null || exactMatchesCursor.count == 0) {
            Log.e(TAG, "Exact match error on query: $query")
            return
        }
        // Log.d(TAG, "Exact matches found: " + exactMatchesCursor.getCount());
        exactMatchesCursor.moveToFirst()
        do {
            val resultEntry =
                KlingonContentProvider.Entry(exactMatchesCursor, mContext)
            if (filterEntry!!.isSatisfiedBy(resultEntry)) {
                val exactMatchObject = convertEntryToCursorRow(resultEntry, indent)
                /*
         * if (BuildConfig.DEBUG) { Log.d(TAG, "addExactMatch: " + resultEntry.getEntryName()); }
         */
                resultsCursor.addRow(exactMatchObject)
                // Log.d(TAG, "added exact match to results: " + query);
                // Only add each one once.
                break
            }
        } while (exactMatchesCursor.moveToNext())
        exactMatchesCursor.close()
    }

    // Helper method to parse a complex word or a sentence.
    private fun parseQueryAsComplexWordOrSentence(
        query: String?, resultsCursor: MatrixCursor, resultsSet: HashSet<Int>
    ) {
        // This set stores the complex words.
        val complexWordsList =
            ArrayList<ComplexWord>()

        // Split the query into sentences.
        val sentences =
            query!!.split(";,\\.?!".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (sentence in sentences) {
            // Remove all non-valid characters and split the sentence into words (separated by spaces).
            val words = sentence.replace("[^A-Za-z' ]".toRegex(), "").split("\\s+".toRegex())
                .dropLastWhile { it.isEmpty() }
                .toTypedArray()
            for (i in words.indices) {
                val word = words[i]

                // Try to parse n-tuples of words as complex nouns.
                // Do this from longest to shortest, since we want longest matches first.
                // TODO: Refactor for space and time efficiency.
                for (j in words.size downTo i + 1) {
                    var compoundNoun = words[i]
                    for (k in i + 1 until j) {
                        compoundNoun += " " + words[k]
                    }
                    // Log.d(TAG, "parseQueryAsComplexWordOrSentence: compoundNoun = " + compoundNoun);
                    KlingonContentProvider.Companion.parseComplexWord(
                        compoundNoun,  /* isNounCandidate */true, complexWordsList
                    )
                }

                // Next, try to parse this as a verb.
                // Log.d(TAG, "parseQueryAsComplexWordOrSentence: verb = " + word);
                KlingonContentProvider.Companion.parseComplexWord(
                    word,  /* isNounCandidate */false, complexWordsList
                )
            }
        }
        for (complexWord in complexWordsList) {
            // Be a little lenient and also match non-nouns and non-verbs.
            addComplexWordToResults(complexWord, resultsCursor, resultsSet,  /* isLenient */true)
        }
    }

    private fun addComplexWordToResults(
        complexWord: ComplexWord,
        resultsCursor: MatrixCursor,
        resultsSet: HashSet<Int>,
        isLenient: Boolean
    ) {
        // The isLenient flag is for determining whether we are doing a real analysis (set to true), or
        // whether the correct analysis has already been supplied in the components (set to false). When
        // set to true, a bare word will match any part of speech (not just noun or verb). But for this
        // reason, duplicates are removed (since there may be many of them). However, when set to false,
        // duplicates will be kept (since the given correct analysis contains them).
        var filterEntry =
            KlingonContentProvider.Entry(complexWord.filter(isLenient), mContext)
        val exactMatchesCursor = getExactMatches(complexWord.stem())

        var stemAdded = false
        if (exactMatchesCursor != null && exactMatchesCursor.count != 0) {
            Log.d(TAG, "found stem = " + complexWord.stem())
            val prefix = complexWord.verbPrefix

            // Add all exact matches for stem.
            exactMatchesCursor.moveToFirst()
            var prefixAdded = false
            do {
                val resultEntry =
                    KlingonContentProvider.Entry(exactMatchesCursor, mContext)
                // An archaic or hypothetical word or phrase, even if it's an exact match, will never be
                // part of a complex word. However, allow slang, regional, and extended canon. Also,
                // verbs are satisfied by pronouns, but we exclude a pronoun if there is a prefix.
                if (filterEntry.isSatisfiedBy(resultEntry)
                    && !resultEntry.isArchaic
                    && !resultEntry.isHypothetical
                    && !(resultEntry.isPronoun && prefix != "")
                ) {
                    Log.d(
                        TAG,
                        "adding: " + resultEntry.entryName + " (" + resultEntry.partOfSpeech + ")"
                    )

                    // If this is a bare word, prevent duplicates.
                    val intId = resultEntry.id
                    if (!complexWord.isBareWord || !resultsSet.contains(intId) || !isLenient) {
                        // Add the verb prefix if one exists, before the verb stem itself.
                        if (prefix != "" && !prefixAdded) {
                            Log.d(TAG, "verb prefix = $prefix")
                            val prefixFilterEntry =
                                KlingonContentProvider.Entry("$prefix:v:pref", mContext)
                            addExactMatch(
                                prefix,
                                prefixFilterEntry,
                                resultsCursor,  /* indent */
                                false
                            )
                            prefixAdded = true
                        }
                        val exactMatchObject =
                            complexWordCursorRow(resultEntry, complexWord, prefixAdded)

                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "addComplexWordToResults: " + resultEntry.entryName)
                        }
                        resultsCursor.addRow(exactMatchObject)
                        stemAdded = true
                        if (complexWord.isBareWord) {
                            resultsSet.add(intId)
                        }
                    }
                }
            } while (exactMatchesCursor.moveToNext())
            exactMatchesCursor.close()
        }

        // Whether or not there was an exact match, if the complex word is a number, add its components.
        if (complexWord.isNumberLike) {
            val numberRoot = complexWord.numberRoot
            val numberRootAnnotation = complexWord.numberRootAnnotation
            val numberModifier = complexWord.numberModifier
            var numberSuffix = complexWord.numberSuffix

            // First, add the root as a word. (The annotation is already included.)
            if (numberRoot != "" && (!stemAdded || numberRoot != complexWord.stem())) {
                filterEntry =
                    KlingonContentProvider.Entry("$numberRoot:$numberRootAnnotation", mContext)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "numberRoot: $numberRoot")
                }
                addExactMatch(numberRoot, filterEntry, resultsCursor,  /* indent */false)
                stemAdded = true
            }

            // Next, add the modifier as a word.
            if (numberModifier != "") {
                filterEntry = KlingonContentProvider.Entry(
                    "$numberModifier:n:num", mContext
                )
                addExactMatch(numberModifier, filterEntry, resultsCursor,  /* indent */true)
            }

            // Finally, add the number suffix.
            if (numberSuffix != "") {
                numberSuffix = "-$numberSuffix"
                filterEntry = KlingonContentProvider.Entry(
                    "$numberSuffix:n:num,suff", mContext
                )
                addExactMatch(numberSuffix, filterEntry, resultsCursor,  /* indent */true)
            }
        }

        // Now add all suffixes, but only if one of the corresponding stems was a legitimate entry.
        if (stemAdded) {
            // Add verb suffixes. Verb suffixes must go before noun suffixes since two of them
            // can turn a verb into a noun.
            // For purposes of analysis, pronouns are also verbs, but they cannot have prefixes.
            val verbSuffixes = complexWord.verbSuffixes
            for (j in verbSuffixes!!.indices) {
                // Check verb suffix of the current type.
                if (verbSuffixes[j] != "") {
                    Log.d(TAG, "verb suffix = " + verbSuffixes[j])
                    filterEntry = KlingonContentProvider.Entry(
                        verbSuffixes[j] + ":v:suff", mContext
                    )
                    addExactMatch(verbSuffixes[j], filterEntry, resultsCursor,  /* indent */true)
                }

                // Check for the true rovers.
                val rovers = complexWord.getRovers(j)
                for (rover in rovers!!) {
                    Log.d(TAG, "rover = $rover")
                    filterEntry = KlingonContentProvider.Entry(
                        "$rover:v:suff", mContext
                    )
                    addExactMatch(rover, filterEntry, resultsCursor,  /* indent */true)
                }
            }

            // Add noun suffixes.
            val nounSuffixes = complexWord.nounSuffixes
            for (j in nounSuffixes!!.indices) {
                if (nounSuffixes[j] != "") {
                    Log.d(TAG, "noun suffix = " + nounSuffixes[j])
                    filterEntry = KlingonContentProvider.Entry(
                        nounSuffixes[j] + ":n:suff", mContext
                    )
                    addExactMatch(nounSuffixes[j], filterEntry, resultsCursor,  /* indent */true)
                }
            }
        }
    }

    private fun complexWordCursorRow(
        entry: KlingonContentProvider.Entry,
        complexWord: ComplexWord,
        indent: Boolean
    ): Array<Any?> {
        // TODO: Add warnings for mismatched affixes here.
        return arrayOf(
            entry.id,
            complexWord.verbPrefixString + entry.entryName + complexWord.suffixesString,  // This works only because all verbs are tagged with transitivity information, so we know the
            // POS looks like "v:t" which we turn into "v:t,indent".
            entry.partOfSpeech + (if (indent) ",indent" else ""),
            entry.definition,
            entry.synonyms,
            entry.antonyms,
            entry.seeAlso,
            entry.notes,
            entry.hiddenNotes,
            entry.components,
            entry.examples,
            entry.searchTags,
            entry.source,
            entry.definition_DE,
            entry.notes_DE,
            entry.examples_DE,
            entry.searchTags_DE,
            entry.definition_FA,
            entry.notes_FA,
            entry.examples_FA,
            entry.searchTags_FA,
            entry.definition_SV,
            entry.notes_SV,
            entry.examples_SV,
            entry.searchTags_SV,
            entry.definition_RU,
            entry.notes_RU,
            entry.examples_RU,
            entry.searchTags_RU,
            entry.definition_ZH_HK,
            entry.notes_ZH_HK,
            entry.examples_ZH_HK,
            entry.searchTags_ZH_HK,
            entry.definition_PT,
            entry.notes_PT,
            entry.examples_PT,
            entry.searchTags_PT,
            entry.definition_FI,
            entry.notes_FI,
            entry.examples_FI,
            entry.searchTags_FI,
            entry.definition_FR,
            entry.notes_FR,
            entry.examples_FR,
            entry.searchTags_FR,
        )
    }

    private fun convertEntryToCursorRow(
        entry: KlingonContentProvider.Entry,
        indent: Boolean
    ): Array<Any?> {
        return arrayOf(
            entry.id,
            entry.entryName,
            entry.partOfSpeech + (if (indent) ",indent" else ""),
            entry.definition,
            entry.synonyms,
            entry.antonyms,
            entry.seeAlso,
            entry.notes,
            entry.hiddenNotes,
            entry.components,
            entry.examples,
            entry.searchTags,
            entry.source,
            entry.definition_DE,
            entry.notes_DE,
            entry.examples_DE,
            entry.searchTags_DE,
            entry.definition_FA,
            entry.notes_FA,
            entry.examples_FA,
            entry.searchTags_FA,
            entry.definition_SV,
            entry.notes_SV,
            entry.examples_SV,
            entry.searchTags_SV,
            entry.definition_RU,
            entry.notes_RU,
            entry.examples_RU,
            entry.searchTags_RU,
            entry.definition_ZH_HK,
            entry.notes_ZH_HK,
            entry.examples_ZH_HK,
            entry.searchTags_ZH_HK,
            entry.definition_PT,
            entry.notes_PT,
            entry.examples_PT,
            entry.searchTags_PT,
            entry.definition_FI,
            entry.notes_FI,
            entry.examples_FI,
            entry.searchTags_FI,
            entry.definition_FR,
            entry.notes_FR,
            entry.examples_FR,
            entry.searchTags_FR,
        )
    }

    /**
     * Returns a cursor for one entry given its _id.
     *
     * @param entryId The ID of the entry to search for
     * @param columns The columns to include, if null then all are included
     * @return Cursor over all entries that match, or null if none found.
     */
    fun getEntryById(entryId: String?, columns: Array<String>?): Cursor? {
        // Log.d(TAG, "getEntryById called with entryid: " + entryId);
        val cursor =
            mDatabaseOpenHelper
                .readableDatabase
                .query(
                    true,
                    FTS_VIRTUAL_TABLE,
                    columns,
                    KEY_ID + "=" + entryId + "",
                    null,
                    null,
                    null,
                    null,
                    null
                )
        cursor?.moveToFirst()
        // Log.d(TAG, "cursor.getCount() = " + cursor.getCount());
        return cursor
    }

    /** Returns a cursor containing a random entry.  */
    fun getRandomEntry(columns: Array<String>?): Cursor? {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mContext)
        val onePastLastId =
            sharedPrefs.getInt(KEY_ID_OF_FIRST_EXTRA_ENTRY,  /* default */ID_OF_FIRST_EXTRA_ENTRY)
        val randomId = Random().nextInt(onePastLastId - ID_OF_FIRST_ENTRY) + ID_OF_FIRST_ENTRY
        return getEntryById(randomId.toString(), ALL_KEYS)
    }

    /**
     * Performs a database query.
     *
     * @param selection The selection clause
     * @param selectionArgs Selection arguments for "?" components in the selection
     * @param columns The columns to return
     * @return A Cursor over all rows matching the query
     */
    private fun query(
        selection: String,
        selectionArgs: Array<String?>,
        columns: Array<String>
    ): Cursor? {
        /*
     * The SQLiteBuilder provides a map for all possible columns requested to actual columns in the
     * database, creating a simple column alias mechanism by which the ContentProvider does not need
     * to know the real column names
     */
        val builder = SQLiteQueryBuilder()
        builder.tables = FTS_VIRTUAL_TABLE
        builder.projectionMap = mColumnMap

        // DEBUG
        // Log.d(TAG, "query - columns: " + Arrays.toString(columns));
        // Log.d(TAG, "query - selection: " + selection);
        // Log.d(TAG, "query - selectionArgs: " + Arrays.toString(selectionArgs));
        val cursor =
            builder.query(
                mDatabaseOpenHelper.readableDatabase,
                columns,
                selection,
                selectionArgs,
                null,
                null,
                null
            )

        // DEBUG
        // Log.d(TAG, "query - cursor: " + cursor.toString());
        if (cursor == null) {
            return null
        } else if (!cursor.moveToFirst()) {
            cursor.close()
            return null
        }
        return cursor
    }

    /** This class helps create, open, and upgrade the Klingon database.  */
    private class KlingonDatabaseOpenHelper
    /**
     * Constructor Takes and keeps a reference of the passed context in order to access the
     * application assets and resources.
     *
     * @param context
     */(// For storing the context the helper was called with for use.
        private val mHelperContext: Context?
    ) : SQLiteOpenHelper(context, DATABASE_NAME, null, BUNDLED_DATABASE_VERSION) {
        // The Klingon database.
        private var mDatabase: SQLiteDatabase? = null

        // The system path of the Klingon database.
        private fun getDatabasePath(name: String): String {
            return mHelperContext!!.getDatabasePath(name).absolutePath
        }

        override fun onCreate(db: SQLiteDatabase) {
            // This method is called when the database is created for the first
            // time. It would normally create the database using an SQL
            // command, then load the content. We do nothing here, and leave
            // the work of copying the pre-made database to the constructor of
            // the KlingonContentDatabase class.
            // Log.d(TAG, "onCreate called.");
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            existingBundledVersion: Int,
            newBundledVersion: Int
        ) {
            if (newBundledVersion <= existingBundledVersion) {
                // Bundled version hasn't changed, do nothing.
                return
            }

            // Note that if the previous version of the app was bundled with database version A, and an
            // updated database version B has been downloaded (but not installed), and this is the first
            // run of a new version bundled with database version C , the installedVersion should
            // default to A (not C).
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mHelperContext)
            val installedVersion =
                sharedPrefs.getString(
                    KEY_INSTALLED_DATABASE_VERSION,  /* default */
                    dottedVersion(existingBundledVersion)
                )
            val updatedVersion =
                sharedPrefs.getString(KEY_UPDATED_DATABASE_VERSION,  /* default */installedVersion)
            if (updatedVersion!!.compareTo(
                    dottedVersion(newBundledVersion),
                    ignoreCase = true
                ) >= 0
            ) {
                // Either a new database is already installed, or is about to be, so do nothing.
                return
            }

            // The database needs to be updated from the bundled database, so clear any existing
            // databases.
            mHelperContext!!.deleteDatabase(DATABASE_NAME)
            mHelperContext.deleteDatabase(REPLACEMENT_DATABASE_NAME)

            // Reset to bundled database version.
            val sharedPrefsEd =
                PreferenceManager.getDefaultSharedPreferences(mHelperContext).edit()
            sharedPrefsEd.remove(KEY_INSTALLED_DATABASE_VERSION)
            sharedPrefsEd.remove(KEY_ID_OF_FIRST_EXTRA_ENTRY)
            sharedPrefsEd.remove(KEY_UPDATED_DATABASE_VERSION)
            sharedPrefsEd.remove(KEY_UPDATED_ID_OF_FIRST_EXTRA_ENTRY)
            sharedPrefsEd.apply()

            Toast.makeText(
                mHelperContext,
                String.format(
                    mHelperContext.resources.getString(R.string.database_upgraded),
                    installedVersion,
                    dottedVersion(newBundledVersion)
                ),
                Toast.LENGTH_LONG
            )
                .show()
            mNewDatabaseMessageDisplayed = true

            // Show help after database upgrade.
            setShowHelpFlag()
        }

        private fun setShowHelpFlag() {
            // Set the flag to show the help screen (but not necessarily the tutorial).
            val sharedPrefsEd =
                PreferenceManager.getDefaultSharedPreferences(mHelperContext).edit()
            sharedPrefsEd.putBoolean(KlingonAssistant.Companion.KEY_SHOW_HELP, true)
            sharedPrefsEd.apply()
            // Log.d(TAG, "Flag set to show help.");
        }

        /**
         * Initialises the database by creating an empty database and writing to it from application
         * resource.
         */
        @Throws(IOException::class)
        fun initDatabase() {
            // TODO: Besides checking whether it exists, also check if its data needs to be updated.
            // This may not be necessary due to onUpgrade(...) above.
            if (checkDBExists(DATABASE_NAME)) {
                // Log.d(TAG, "Database exists.");
                // Get a writeable database so that onUpgrade will be called on it if the version number has
                // increased. This will delete the existing datbase.
                try {
                    // Log.d(TAG, "Getting writable database.");
                    val writeDB = this.writableDatabase
                    writeDB.close()
                } catch (e: SQLiteDiskIOException) {
                    // TODO: Log error or do something here and below.
                    // Log.e(TAG, "SQLiteDiskIOException on getWritableDatabase().");
                } catch (e: SQLiteException) {
                    // Possibly unable to get provider because no transaction is active.
                    // Do nothing.
                }
            }

            // Update the database if that's available.
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(mHelperContext)
            val installedVersion =
                sharedPrefs.getString(
                    KEY_INSTALLED_DATABASE_VERSION,  /* default */
                    bundledDatabaseVersion
                )
            val updatedVersion =
                sharedPrefs.getString(KEY_UPDATED_DATABASE_VERSION,  /* default */installedVersion)
            if (updatedVersion!!.compareTo(installedVersion!!, ignoreCase = true) > 0
                && checkDBExists(REPLACEMENT_DATABASE_NAME)
            ) {
                copyDBFromReplacement()

                val firstExtraEntryId =
                    sharedPrefs.getInt(
                        KEY_UPDATED_ID_OF_FIRST_EXTRA_ENTRY,  /* default */ID_OF_FIRST_EXTRA_ENTRY
                    )
                val sharedPrefsEd =
                    PreferenceManager.getDefaultSharedPreferences(mHelperContext).edit()
                sharedPrefsEd.putString(KEY_INSTALLED_DATABASE_VERSION, updatedVersion)
                sharedPrefsEd.putInt(KEY_ID_OF_FIRST_EXTRA_ENTRY, firstExtraEntryId)
                sharedPrefsEd.remove(KEY_UPDATED_DATABASE_VERSION)
                sharedPrefsEd.remove(KEY_UPDATED_ID_OF_FIRST_EXTRA_ENTRY)
                sharedPrefsEd.apply()

                Toast.makeText(
                    mHelperContext,
                    String.format(
                        mHelperContext!!.resources.getString(R.string.database_upgraded),
                        installedVersion,
                        updatedVersion
                    ),
                    Toast.LENGTH_LONG
                )
                    .show()
                mNewDatabaseMessageDisplayed = true

                // Show help after database upgrade.
                setShowHelpFlag()
            }

            // Create the database from included bundle if it doesn't exist.
            if (!checkDBExists(DATABASE_NAME)) {
                // This will create the empty database if it doesn't already exist.
                // Log.d(TAG, "Getting readable database.");
                val readDB = this.readableDatabase
                readDB.close()

                // Try to create the database from the bundled database file.
                try {
                    // Log.d(TAG, "Copying database from resources.");
                    copyDBFromResources()
                } catch (e: IOException) {
                    throw Error("Error copying database from resources.")
                }

                // Inform the user the database has been created.
                if (!mNewDatabaseMessageDisplayed) {
                    Toast.makeText(
                        mHelperContext,
                        String.format(
                            mHelperContext!!.resources.getString(R.string.database_created),
                            bundledDatabaseVersion
                        ),
                        Toast.LENGTH_LONG
                    )
                        .show()
                    mNewDatabaseMessageDisplayed = true
                }

                // Show help after database creation.
                setShowHelpFlag()
            }
        }

        /**
         * Check if the database already exists so that it isn't copied every time the activity is
         * started.
         *
         * @return true if the database exists, false otherwise
         */
        private fun checkDBExists(databaseName: String): Boolean {
            // The commented way below is the proper way of checking for the
            // existence of the database. However, we do it this way to
            // prevent the "sqlite3_open_v2 open failed" error.
            val dbFile = File(getDatabasePath(databaseName))
            return dbFile.exists()

            // TODO: Investigate the below. It may be the reason why there
            // are problems on some devices.
            /*
       * SQLiteDatabase checkDB = null; try { String fullDBPath = getDatabasePath(DATABASE_NAME);
       * checkDB = SQLiteDatabase.openDatabase(fullDBPath, null, SQLiteDatabase.OPEN_READONLY);
       *
       * } catch(SQLiteCantOpenDatabaseException e) { // The database doesn't exist yet. It's fine
       * to do nothing // here, we just want to return false at the end. // Log.d(TAG,
       * "SQLiteCantOpenDatabaseException thrown: " + e);
       *
       * } catch(SQLiteDatabaseLockedException e) { // The database is locked. Also return false. //
       * Log.d(TAG, "SQLiteDatabaseLockedException thrown: " + e); }
       *
       * if( checkDB != null ) { checkDB.close(); }
       *
       * // Log.d(TAG, "checkDB == null: " + (checkDB == null)); return ( checkDB != null );
       */
        }

        /**
         * Copies the database from the application resources' assets folder to the newly created
         * database in the system folder.
         */
        @Throws(IOException::class)
        private fun copyDBFromResources() {
            // Open the file in the assets folder as an input stream.

            val inStream = mHelperContext!!.assets.open(DATABASE_NAME)

            // Path to the newly created empty database.
            val fullDBPath = getDatabasePath(DATABASE_NAME)

            // Open the empty database as the output stream.
            val outStream: OutputStream = FileOutputStream(fullDBPath)

            // Transfer the database from the resources to the system path one block at a time.
            val buffer = ByteArray(MAX_BUFFER_LENGTH)
            var length: Int
            while ((inStream.read(buffer).also { length = it }) > 0) {
                outStream.write(buffer, 0, length)
            }

            // Close the streams.
            outStream.flush()
            outStream.close()
            inStream.close()

            // Log.d(TAG, "Database copy successful.");
        }

        /** Copies the database from the replacement (update) database.  */
        @Throws(IOException::class)
        private fun copyDBFromReplacement() {
            val fullReplacementDBPath = getDatabasePath(REPLACEMENT_DATABASE_NAME)
            val fullDBPath = getDatabasePath(DATABASE_NAME)

            val inStream: InputStream = FileInputStream(fullReplacementDBPath)
            val outStream: OutputStream = FileOutputStream(fullDBPath)

            // Transfer the database from the resources to the system path one block at a time.
            val buffer = ByteArray(MAX_BUFFER_LENGTH)
            var length: Int
            var total = 0
            while ((inStream.read(buffer).also { length = it }) > 0) {
                outStream.write(buffer, 0, length)
                total += length
            }
            Log.d(TAG, "Copied database from replacement, $total bytes copied.")

            // Close the streams.
            outStream.flush()
            outStream.close()
            inStream.close()

            // Delete the replacement database.
            mHelperContext!!.deleteDatabase(REPLACEMENT_DATABASE_NAME)
        }

        /** Opens the database.  */
        @Throws(SQLException::class)
        fun openDatabase() {
            val fullDBPath = getDatabasePath(DATABASE_NAME)
            // Log.d(TAG, "openDatabase() called on path " + fullDBPath + ".");
            mDatabase = SQLiteDatabase.openDatabase(fullDBPath, null, SQLiteDatabase.OPEN_READONLY)
        }

        /** Closes the database.  */
        @Synchronized
        override fun close() {
            // Log.d(TAG, "Closing database.");
            if (mDatabase != null) {
                mDatabase!!.close()
            }
            super.close()
        }
    } // KlingonDatabaseOpenHelper

    companion object {
        private const val TAG = "KlingonContentDatabase"

        // The columns included in the database table.
        const val KEY_ID: String = BaseColumns._ID
        const val KEY_ENTRY_NAME: String = "entry_name"
        const val KEY_PART_OF_SPEECH: String = "part_of_speech"
        const val KEY_DEFINITION: String = "definition"
        const val KEY_SYNONYMS: String = "synonyms"
        const val KEY_ANTONYMS: String = "antonyms"
        const val KEY_SEE_ALSO: String = "see_also"
        const val KEY_NOTES: String = "notes"
        const val KEY_HIDDEN_NOTES: String = "hidden_notes"
        const val KEY_COMPONENTS: String = "components"
        const val KEY_EXAMPLES: String = "examples"
        const val KEY_SEARCH_TAGS: String = "search_tags"
        const val KEY_SOURCE: String = "source"

        // Languages other than English.
        const val KEY_DEFINITION_DE: String = "definition_de"
        const val KEY_NOTES_DE: String = "notes_de"
        const val KEY_EXAMPLES_DE: String = "examples_de"
        const val KEY_SEARCH_TAGS_DE: String = "search_tags_de"
        const val KEY_DEFINITION_FA: String = "definition_fa"
        const val KEY_NOTES_FA: String = "notes_fa"
        const val KEY_EXAMPLES_FA: String = "examples_fa"
        const val KEY_SEARCH_TAGS_FA: String = "search_tags_fa"
        const val KEY_DEFINITION_SV: String = "definition_sv"
        const val KEY_NOTES_SV: String = "notes_sv"
        const val KEY_EXAMPLES_SV: String = "examples_sv"
        const val KEY_SEARCH_TAGS_SV: String = "search_tags_sv"
        const val KEY_DEFINITION_RU: String = "definition_ru"
        const val KEY_NOTES_RU: String = "notes_ru"
        const val KEY_EXAMPLES_RU: String = "examples_ru"
        const val KEY_SEARCH_TAGS_RU: String = "search_tags_ru"
        const val KEY_DEFINITION_ZH_HK: String = "definition_zh_HK"
        const val KEY_NOTES_ZH_HK: String = "notes_zh_HK"
        const val KEY_EXAMPLES_ZH_HK: String = "examples_zh_HK"
        const val KEY_SEARCH_TAGS_ZH_HK: String = "search_tags_zh_HK"
        const val KEY_DEFINITION_PT: String = "definition_pt"
        const val KEY_NOTES_PT: String = "notes_pt"
        const val KEY_EXAMPLES_PT: String = "examples_pt"
        const val KEY_SEARCH_TAGS_PT: String = "search_tags_pt"
        const val KEY_DEFINITION_FI: String = "definition_fi"
        const val KEY_NOTES_FI: String = "notes_fi"
        const val KEY_EXAMPLES_FI: String = "examples_fi"
        const val KEY_SEARCH_TAGS_FI: String = "search_tags_fi"
        const val KEY_DEFINITION_FR: String = "definition_fr"
        const val KEY_NOTES_FR: String = "notes_fr"
        const val KEY_EXAMPLES_FR: String = "examples_fr"
        const val KEY_SEARCH_TAGS_FR: String = "search_tags_fr"

        // The order of the keys to access the columns.
        const val COLUMN_ID: Int = 0
        const val COLUMN_ENTRY_NAME: Int = 1
        const val COLUMN_PART_OF_SPEECH: Int = 2
        const val COLUMN_DEFINITION: Int = 3
        const val COLUMN_SYNONYMS: Int = 4
        const val COLUMN_ANTONYMS: Int = 5
        const val COLUMN_SEE_ALSO: Int = 6
        const val COLUMN_NOTES: Int = 7
        const val COLUMN_HIDDEN_NOTES: Int = 8
        const val COLUMN_COMPONENTS: Int = 9
        const val COLUMN_EXAMPLES: Int = 10
        const val COLUMN_SEARCH_TAGS: Int = 11
        const val COLUMN_SOURCE: Int = 12

        // Languages other than English.
        const val COLUMN_DEFINITION_DE: Int = 13
        const val COLUMN_NOTES_DE: Int = 14
        const val COLUMN_EXAMPLES_DE: Int = 15
        const val COLUMN_SEARCH_TAGS_DE: Int = 16
        const val COLUMN_DEFINITION_FA: Int = 17
        const val COLUMN_NOTES_FA: Int = 18
        const val COLUMN_EXAMPLES_FA: Int = 19
        const val COLUMN_SEARCH_TAGS_FA: Int = 20
        const val COLUMN_DEFINITION_SV: Int = 21
        const val COLUMN_NOTES_SV: Int = 22
        const val COLUMN_EXAMPLES_SV: Int = 23
        const val COLUMN_SEARCH_TAGS_SV: Int = 25
        const val COLUMN_DEFINITION_RU: Int = 25
        const val COLUMN_NOTES_RU: Int = 26
        const val COLUMN_EXAMPLES_RU: Int = 27
        const val COLUMN_SEARCH_TAGS_RU: Int = 28
        const val COLUMN_DEFINITION_ZH_HK: Int = 29
        const val COLUMN_NOTES_ZH_HK: Int = 30
        const val COLUMN_EXAMPLES_ZH_HK: Int = 31
        const val COLUMN_SEARCH_TAGS_ZH_HK: Int = 32
        const val COLUMN_DEFINITION_PT: Int = 33
        const val COLUMN_NOTES_PT: Int = 34
        const val COLUMN_EXAMPLES_PT: Int = 35
        const val COLUMN_SEARCH_TAGS_PT: Int = 36
        const val COLUMN_DEFINITION_FI: Int = 37
        const val COLUMN_NOTES_FI: Int = 38
        const val COLUMN_EXAMPLES_FI: Int = 39
        const val COLUMN_SEARCH_TAGS_FI: Int = 40
        const val COLUMN_DEFINITION_FR: Int = 41
        const val COLUMN_NOTES_FR: Int = 42
        const val COLUMN_EXAMPLES_FR: Int = 43
        const val COLUMN_SEARCH_TAGS_FR: Int = 44

        // All keys.
        val ALL_KEYS: Array<String> = arrayOf(
            KEY_ID,
            KEY_ENTRY_NAME,
            KEY_PART_OF_SPEECH,
            KEY_DEFINITION,
            KEY_SYNONYMS,
            KEY_ANTONYMS,
            KEY_SEE_ALSO,
            KEY_NOTES,
            KEY_HIDDEN_NOTES,
            KEY_COMPONENTS,
            KEY_EXAMPLES,
            KEY_SEARCH_TAGS,
            KEY_SOURCE,
            KEY_DEFINITION_DE,
            KEY_NOTES_DE,
            KEY_EXAMPLES_DE,
            KEY_SEARCH_TAGS_DE,
            KEY_DEFINITION_FA,
            KEY_NOTES_FA,
            KEY_EXAMPLES_FA,
            KEY_SEARCH_TAGS_FA,
            KEY_DEFINITION_SV,
            KEY_NOTES_SV,
            KEY_EXAMPLES_SV,
            KEY_SEARCH_TAGS_SV,
            KEY_DEFINITION_RU,
            KEY_NOTES_RU,
            KEY_EXAMPLES_RU,
            KEY_SEARCH_TAGS_RU,
            KEY_DEFINITION_ZH_HK,
            KEY_NOTES_ZH_HK,
            KEY_EXAMPLES_ZH_HK,
            KEY_SEARCH_TAGS_ZH_HK,
            KEY_DEFINITION_PT,
            KEY_NOTES_PT,
            KEY_EXAMPLES_PT,
            KEY_SEARCH_TAGS_PT,
            KEY_DEFINITION_FI,
            KEY_NOTES_FI,
            KEY_EXAMPLES_FI,
            KEY_SEARCH_TAGS_FI,
            KEY_DEFINITION_FR,
            KEY_NOTES_FR,
            KEY_EXAMPLES_FR,
            KEY_SEARCH_TAGS_FR,
        )

        // The name of the database and the database object for accessing it.
        private const val DATABASE_NAME = "qawHaq.db"
        private const val FTS_VIRTUAL_TABLE = "mem"

        // The name of the database for updates.
        const val REPLACEMENT_DATABASE_NAME: String = "qawHaq_new.db"

        // This should be kept in sync with the version number in the data/VERSION
        // file used to generate the database which is bundled into the app.
        private const val BUNDLED_DATABASE_VERSION = 202311201

        // Metadata about the installed database, and the updated database, if any.
        const val KEY_INSTALLED_DATABASE_VERSION: String = "installed_database_version"
        const val KEY_ID_OF_FIRST_EXTRA_ENTRY: String = "id_of_first_extra_entry"
        const val KEY_UPDATED_DATABASE_VERSION: String = "updated_database_version"
        const val KEY_UPDATED_ID_OF_FIRST_EXTRA_ENTRY: String = "updated_id_of_first_extra_entry"

        // Arbitrary limit on max buffer length to prevent overflows and such.
        private const val MAX_BUFFER_LENGTH = 1024

        // These are automatically updated by renumber.py in the data directory, and correspond to
        // the IDs of the first entry and one past the ID of the last non-hypothetical,
        // non-extended-canon entry in the database, respectively.
        private const val ID_OF_FIRST_ENTRY = 10000
        private const val ID_OF_FIRST_EXTRA_ENTRY = 15346

        private val mColumnMap = buildColumnMap()

        // Keeps track of whether db created/upgraded message has been displayed already.
        private var mNewDatabaseMessageDisplayed = false

        /**
         * Builds a map for all columns that may be requested, which will be given to the
         * SQLiteQueryBuilder. This is a good way to define aliases for column names, but must include all
         * columns, even if the value is the key. This allows the ContentProvider to request columns w/o
         * the need to know real column names and create the alias itself.
         */
        private fun buildColumnMap(): HashMap<String, String> {
            val map = HashMap<String, String>()
            map[KEY_ENTRY_NAME] = KEY_ENTRY_NAME
            map[KEY_DEFINITION] = KEY_DEFINITION
            map[KEY_ID] = "rowid AS " + KEY_ID
            map[SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID] =
                "rowid AS " + SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID
            map[SearchManager.SUGGEST_COLUMN_SHORTCUT_ID] =
                "rowid AS " + SearchManager.SUGGEST_COLUMN_SHORTCUT_ID
            return map
        }

        fun sanitizeInput(s: String): String {
            // Sanitize for SQL. Assume double-quote is a typo for single-quote. Convert {pIqaD} to Latin.
            // Also trim.
            return s.replace("\"".toRegex(), "'")
                .replace("".toRegex(), "gh")
                .replace("".toRegex(), "ng")
                .replace("".toRegex(), "tlh")
                .replace("".toRegex(), "a")
                .replace("".toRegex(), "b")
                .replace("".toRegex(), "ch")
                .replace("".toRegex(), "D")
                .replace("".toRegex(), "e")
                .replace("".toRegex(), "H")
                .replace("".toRegex(), "I")
                .replace("".toRegex(), "j")
                .replace("".toRegex(), "l")
                .replace("".toRegex(), "m")
                .replace("".toRegex(), "n")
                .replace("".toRegex(), "o")
                .replace("".toRegex(), "p")
                .replace("".toRegex(), "q")
                .replace("".toRegex(), "Q")
                .replace("".toRegex(), "r")
                .replace("".toRegex(), "S")
                .replace("".toRegex(), "t")
                .replace("".toRegex(), "u")
                .replace("".toRegex(), "v")
                .replace("".toRegex(), "w")
                .replace("".toRegex(), "y")
                .replace("".toRegex(), "'")
                .replace("".toRegex(), "0")
                .replace("".toRegex(), "1")
                .replace("".toRegex(), "2")
                .replace("".toRegex(), "3")
                .replace("".toRegex(), "4")
                .replace("".toRegex(), "5")
                .replace("".toRegex(), "6")
                .replace("".toRegex(), "7")
                .replace("".toRegex(), "8")
                .replace("".toRegex(), "9")
                .replace("’".toRegex(), "'") // "smart" quote
                .replace("‘".toRegex(), "'") // "smart" left quote
                .replace("\u2011".toRegex(), "-") // non-breaking hyphen
                .trim { it <= ' ' }
        }

        @JvmStatic
        val bundledDatabaseVersion: String
            get() = dottedVersion(BUNDLED_DATABASE_VERSION)

        private fun dottedVersion(version: Int): String {
            val s = version.toString()
            return (s.substring(0, 4)
                    + "."
                    + s.substring(4, 6)
                    + "."
                    + s.substring(6, 8)
                    + Character.toString((s[8].code - '0'.code + 'a'.code).toChar()))
        }
    }
} // KlingonContentDatabase

