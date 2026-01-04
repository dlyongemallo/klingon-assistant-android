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

import android.annotation.TargetApi
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.database.Cursor
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.TwoLineListItem
import java.util.*

/**
 * The main activity for the dictionary. Displays search results triggered by the search dialog and
 * handles actions from search suggestions.
 */
class KlingonAssistant : BaseActivity() {

    // Preference key for whether to show help.
    companion object {
        private const val TAG = "KlingonAssistant"
        const val KEY_SHOW_HELP = "show_help"

        // These hold the {pIqaD} typefaces.
        private var mTNGKlingonFontTypeface: Typeface? = null
        private var mDSCKlingonFontTypeface: Typeface? = null
        private var mCoreKlingonFontTypeface: Typeface? = null

        @JvmStatic
        fun getKlingonFontTypeface(context: Context): Typeface {
            val klingonFontCode = Preferences.getKlingonFontCode(context)
            return when (klingonFontCode) {
                "CORE" -> {
                    if (mCoreKlingonFontTypeface == null) {
                        mCoreKlingonFontTypeface =
                            Typeface.createFromAsset(context.assets, "fonts/qolqoS-pIqaD.ttf")
                    }
                    mCoreKlingonFontTypeface!!
                }
                "DSC" -> {
                    if (mDSCKlingonFontTypeface == null) {
                        mDSCKlingonFontTypeface =
                            Typeface.createFromAsset(context.assets, "fonts/DSC-pIqaD.ttf")
                    }
                    mDSCKlingonFontTypeface!!
                }
                else -> {
                    // Return TNG-style as the default as that's how we want to display the app name
                    // when Latin is chosen.
                    if (mTNGKlingonFontTypeface == null) {
                        mTNGKlingonFontTypeface =
                            Typeface.createFromAsset(context.assets, "fonts/TNG-pIqaD.ttf")
                    }
                    mTNGKlingonFontTypeface!!
                }
            }
        }

        @TargetApi(Build.VERSION_CODES.N)
        @JvmStatic
        fun getSystemLocale(): Locale {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Resources.getSystem().configuration.locales[0]
            } else {
                @Suppress("DEPRECATION")
                Resources.getSystem().configuration.locale
            }
        }
    }

    // The two main views in app's main screen.
    private lateinit var mTextView: TextView
    private lateinit var mListView: ListView

    // The query to pre-populate when the user presses the "Search" button.
    private var mPrepopulatedQuery: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setDrawerContentView(R.layout.main)

        mTextView = findViewById(R.id.text)
        mListView = findViewById(R.id.list)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        // Because this activity has set launchMode="singleTop", the system calls this method
        // to deliver the intent if this activity is currently the foreground activity when
        // invoked again (when the user executes a search from this activity, we don't create
        // a new instance of this activity, so the system delivers the search intent here)
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // Helper method to determine if a shared text came from Twitter, and if so, strip it of
    // everything but the actual tweet.
    private fun stripTweet(text: String): String {
        // Log.d(TAG, "Tweet text = $text")
        if (!text.contains("https://twitter.com/download")) {
            // All shared tweets contain the Twitter download link, regardless of the UI language.
            // So if this isn't found, then it's not a tweet.
            return text
        }
        // If it's a tweet, the second line is the actual content.
        val textParts = text.split("\n")
        return if (textParts.size >= 2) {
            textParts[1]
        } else text
    }

    private fun handleIntent(intent: Intent) {
        // Log.d(TAG, "Intent: $intent")
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                // handles a click on a search suggestion; launches activity to show entry
                val entryId = intent.dataString
                // Log.d(TAG, "entryId = $entryId")
                launchEntry(entryId)
            }
            Intent.ACTION_SEARCH -> {
                // handles a search query
                val mQuery = intent.getStringExtra(SearchManager.QUERY)
                Log.d(TAG, "ACTION_SEARCH: $mQuery")
                showResults(mQuery ?: "")
            }
            Intent.ACTION_SEND -> {
                // handles another plain text shared from another app
                if (intent.type == "text/plain") {
                    var sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (sharedText != null) {
                        /* if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Incoming text:\n$sharedText")
                        } */
                        // Sanitise incoming text. Also cap at 140 chars, for reasons of speed and because that's
                        // the limit used by Twitter.
                        sharedText = stripTweet(sharedText)
                        sharedText = sharedText.replace(Regex("[:\\*<>\\n]"), " ").trim().replace(Regex("\\s+"), " ")
                        if (sharedText.length > 140) {
                            sharedText = sharedText.substring(0, 140)
                        }
                        /* if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Shared text:\n$sharedText")
                        } */
                        // Override (disable) "xifan hol" mode for this search, since it doesn't really make sense
                        // here.
                        showResults("+$sharedText")
                    }
                }
            }
            else -> {
                // Show just the help screen.
                displayHelp(BaseActivity.QUERY_FOR_ABOUT)
            }
        }
    }

    // Launch an entry activity with the entry's info.
    private fun launchEntry(entryId: String?) {
        if (entryId == null) {
            return
        }

        val entryIntent = Intent(this, EntryActivity::class.java)

        // Form the URI for the entry.
        val uri = Uri.parse("${KlingonContentProvider.CONTENT_URI}/get_entry_by_id/$entryId")
        entryIntent.data = uri
        startActivity(entryIntent)
    }

    inner class EntryAdapter(private val mCursor: Cursor) : BaseAdapter(), AdapterView.OnItemClickListener {
        private val mInflater: LayoutInflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        override fun getCount(): Int = mCursor.count

        override fun getItem(position: Int): Any = position

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? TwoLineListItem) ?: createView(parent)
            mCursor.moveToPosition(position)
            bindView(view, mCursor)
            return view
        }

        private fun createView(parent: ViewGroup): TwoLineListItem {
            val item = mInflater.inflate(
                android.R.layout.simple_list_item_2,
                parent,
                false
            ) as TwoLineListItem

            // Set single line to true if you want shorter definitions.
            item.text2.setSingleLine(false)
            item.text2.ellipsize = TextUtils.TruncateAt.END

            return item
        }

        private fun bindView(view: TwoLineListItem, cursor: Cursor) {
            val entry = KlingonContentProvider.Entry(cursor, baseContext)

            // Note that we override the typeface and text size here, instead of in
            // the xml, because putting it there would also change the appearance of
            // the Preferences page. We fully indent suffixes, but only half-indent verbs.
            val indent1 = if (entry.isIndented()) {
                if (entry.isVerb()) "&nbsp;&nbsp;" else "&nbsp;&nbsp;&nbsp;&nbsp;"
            } else ""
            val indent2 = if (entry.isIndented()) {
                if (entry.isVerb()) "&nbsp;&nbsp;&nbsp;&nbsp;" else "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
            } else ""

            if (Preferences.useKlingonFont(baseContext)) {
                // Preference is set to display this in {pIqaD}!
                view.text1.text = SpannableStringBuilder(Html.fromHtml(indent1))
                    .append(entry.getFormattedEntryNameInKlingonFont())
            } else {
                // Use serif for the entry, so capital-I and lowercase-l are distinguishable.
                view.text1.typeface = Typeface.SERIF
                view.text1.text = Html.fromHtml(indent1 + entry.getFormattedEntryName(/* isHtml */ true))
            }
            view.text1.textSize = 22f

            // TODO: Colour attached affixes differently from verb.
            view.text1.setTextColor(entry.getTextColor())

            // Use sans serif for the definition.
            view.text2.typeface = Typeface.SANS_SERIF
            view.text2.text = Html.fromHtml(indent2 + entry.getFormattedDefinition(/* isHtml */ true))
            view.text2.textSize = 14f
            view.text2.setTextColor(0xFFC0C0C0.toInt())
        }

        override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (count == 1) {
                // Launch entry the regular way, as there's only one result.
                mCursor.moveToPosition(position)
                launchEntry(mCursor.getString(KlingonContentDatabase.COLUMN_ID))
            } else {
                // There's a list of results, so launch a list of entries. Instead of passing in
                // one ID, we pass in a comma-separated list. We also append the position of the
                // selected entry to the end.
                val entryList = StringBuilder()
                for (i in 0 until count) {
                    mCursor.moveToPosition(i)
                    entryList.append(mCursor.getString(KlingonContentDatabase.COLUMN_ID))
                    entryList.append(",")
                }
                entryList.append(position)
                mCursor.moveToPosition(position)
                launchEntry(entryList.toString())
            }
        }
    }

    /**
     * Searches the dictionary and displays results for the given query. The query may be prepended
     * with a plus to disable "xifan hol" mode.
     *
     * @param query The search query
     */
    private fun showResults(query: String) {
        // Note: managedQuery is deprecated since API 11.
        @Suppress("DEPRECATION")
        val cursor = managedQuery(
            Uri.parse("${KlingonContentProvider.CONTENT_URI}/lookup"),
            null /* all columns */,
            null,
            arrayOf(query),
            null
        )

        // A query may be preceded by a plus to override (disable) "xifan hol" mode. This is used
        // for internal searches. After it is passed to managedQuery (above), it can be removed.
        val overrideXifanHol = query.isNotEmpty() && query[0] == '+'
        val cleanQuery = if (overrideXifanHol) query.substring(1) else query

        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(baseContext)
        val queryEntry = KlingonContentProvider.Entry(cleanQuery, baseContext)
        val qWillBeRemapped = queryEntry.getEntryName().contains('q') &&
                sharedPrefs.getBoolean(Preferences.KEY_XIFAN_HOL_CHECKBOX_PREFERENCE, /* default */ false) &&
                sharedPrefs.getBoolean(Preferences.KEY_SWAP_QS_CHECKBOX_PREFERENCE, /* default */ false)
        var entryNameWithPoS = queryEntry.getEntryName() + queryEntry.getBracketedPartOfSpeech(/* isHtml */ true)
        if (!overrideXifanHol && qWillBeRemapped) {
            // Alert the user to the visual inconsistency of the query containing "q" but the results
            // containing {Q} instead, as some users forget that they have this option activated in their
            // settings. (Note that "k" would be mapped to {q} in that case, and "Q" would still be mapped
            // to {Q}. It's only "q" which isn't obvious.)
            entryNameWithPoS += " [q=Q]"
        }

        if (cursor == null || cursor.count == 0) {
            // There are no results.
            mTextView.text = Html.fromHtml(getString(R.string.no_results, entryNameWithPoS))
            // The user probably made a typo, so allow them to edit the query.
            mPrepopulatedQuery = queryEntry.getEntryName()
        } else {
            // Display the number of results.
            val count = cursor.count
            val countString: String
            if (queryEntry.getEntryName() == "*") {
                // Searching for a class of phrases.
                countString = queryEntry.getSentenceType().let {
                    if (it.isEmpty()) {
                        // The sentence type was indeterminate.
                        // This only ever happens if the user enters "*:sen" as a search string.
                        "Sentences:"
                    } else {
                        // Display, e.g., "Lyrics:".
                        "$it:"
                    }
                }
            } else {
                countString = resources.getQuantityString(
                    R.plurals.search_results,
                    count,
                    count,
                    entryNameWithPoS
                )
                // Allow the user to edit the query by pressing the search button.
                mPrepopulatedQuery = queryEntry.getEntryName()
                // If "xifan hol" mode was overridden (disabled) to get this set of search results, but it
                // is currently enabled by the user with q mapped to Q, then we ensure that if the user
                // edits the search query, that it performs a search with "xifan hol" overridden again.
                if (overrideXifanHol && qWillBeRemapped) {
                    mPrepopulatedQuery = "+$mPrepopulatedQuery"
                }
            }
            mTextView.text = Html.fromHtml(countString)

            // TODO: Allow TTS to speak queryEntry.getEntryName().

            // Create a cursor adapter for the entries and apply them to the ListView.
            val entryAdapter = EntryAdapter(cursor)
            mListView.adapter = entryAdapter
            mListView.onItemClickListener = entryAdapter

            // Launch the entry automatically.
            // TODO: See if list view above can be skipped entirely.
            if (count == 1) {
                launchEntry(cursor.getString(KlingonContentDatabase.COLUMN_ID))
            }
        }
    }

    override fun onSearchRequested(): Boolean {
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as? SearchManager
        return if (searchManager != null) {
            searchManager.startSearch(
                mPrepopulatedQuery,
                true,
                ComponentName(this, KlingonAssistant::class.java),
                null,
                false
            )
            true
        } else {
            false
        }
    }
}
