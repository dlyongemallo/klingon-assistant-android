@file:Suppress("DEPRECATION")

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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.Locale

/** Displays an entry and its definition. */
class EntryActivity : BaseActivity(),
    // TTS:
    TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "EntryActivity"

        // Form submission constants
        private const val CORRECTION_FORM_URL =
            "https://docs.google.com/forms/d/e/1FAIpQLSdubRpIpbPFHAclzNx3jrOT85nQLGYCgWPOjIHxPocrecZUzw/viewform"
        private const val CORRECTION_ENTRY_NAME_KEY = "entry.1852970057"
        private const val CORRECTION_PART_OF_SPEECH_KEY = "entry.1015346696"
        private const val CORRECTION_DEFINITION_KEY = "entry.166391661"
        private const val CORRECTION_LANGUAGE_KEY = "entry.2030201514"
        private const val CORRECTION_DEFINITION_TRANSLATION_KEY = "entry.1343345"

        // Intents for the bottom navigation buttons.
        // Note that the renumber.py script ensures that the IDs of adjacent entries
        // are consecutive across the entire database.
        private const val MAX_ENTRY_ID_DIFF = 5
    }

    // The currently-displayed entry (which can change due to page selection).
    private var mEntry: KlingonContentProvider.Entry? = null

    // The parent query that this entry is a part of.
    // private String mParentQuery = null;

    // The intent holding the data to be shared, and the associated UI.
    private var mShareEntryIntent: Intent? = null
    private var mShareButton: MenuItem? = null

    private var mPreviousEntryIntent: Intent? = null
    private var mNextEntryIntent: Intent? = null

    // TTS:
    /** The [TextToSpeech] used for speaking. */
    private var mTts: TextToSpeech? = null

    private var mSpeakButton: MenuItem? = null
    private var ttsInitialized = false

    // Handle swipe. The pager widget handles animation and allows swiping
    // horizontally. The pager adapter provides the pages to the pager widget.
    private lateinit var mPager: ViewPager
    private lateinit var mPagerAdapter: PagerAdapter
    private var mEntryIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TTS:
        // Log.d(TAG, "Initialising TTS")
        clearTTS()
        mTts = initTTS()

        setDrawerContentView(R.layout.entry_swipe)

        val inputUri = intent.data
        // Log.d(TAG, "EntryActivity - inputUri: " + inputUri.toString())
        // TODO: Disable the "About" menu item if this is the "About" entry.
        // mParentQuery = getIntent().getStringExtra(SearchManager.QUERY)

        // Determine whether we're launching a single entry, or a list of entries.
        // If it's a single entry, the URI will end in "get_entry_by_id/" followed
        // by the one ID. In the case of a list, the URI will end in "get_entry_by_id/"
        // follwoed by a list of comma-separated IDs, with one additional item at the
        // end for the position of the current entry. For a random entry, the URI will
        // end in "get_random_entry", with no ID at all.
        val ids = inputUri?.lastPathSegment?.split(",") ?: emptyList()
        val queryUri: Uri
        val entryIdsList = ids.toMutableList()
        if (entryIdsList.size == 1) {
            // There is only one entry to display. Either its ID was explicitly
            // given, or we want a random entry.
            queryUri = inputUri!!
            mEntryIndex = 0
        } else {
            // Parse the comma-separated list, the last entry of which is the
            // position index. We nees to construct the queryUri based on the
            // intended current entry.
            mEntryIndex = entryIdsList[ids.size - 1].toInt()
            entryIdsList.removeAt(ids.size - 1)
            queryUri = Uri.parse(
                "${KlingonContentProvider.CONTENT_URI}/get_entry_by_id/${entryIdsList[mEntryIndex]}"
            )
        }

        // Retrieve the entry's data.
        val cursor = contentResolver.query(queryUri, KlingonContentDatabase.ALL_KEYS, null, null, null)!!
        val entry = KlingonContentProvider.Entry(cursor, baseContext)
        val entryId = entry.getId()

        // Update the entry, which is used for TTS output. This is also updated in onPageSelected.
        mEntry = entry

        if (entryIdsList.size == 1 && entryIdsList[0] == "get_random_entry") {
            // For a random entry, replace "get_random_entry" with the ID of randomly
            // chosen entry.
            entryIdsList.clear()
            entryIdsList.add(entryId.toString())
        }

        // Set the share intent. This is also done in onPageSelected.
        setShareEntryIntent(entry)

        // Update the bottom navigation buttons. This is also done in onPageSelected.
        updateBottomNavigationButtons(entryId)

        // Update the edit button. This is also done in onPageSelected.
        updateEditButton()

        // Instantiate a ViewPager and a PagerAdapter.
        mPager = findViewById(R.id.entry_pager)
        mPagerAdapter = SwipeAdapter(supportFragmentManager, entryIdsList)
        mPager.adapter = mPagerAdapter
        mPager.setCurrentItem(mEntryIndex, /* smoothScroll */ false)
        mPager.addOnPageChangeListener(SwipePageChangeListener(entryIdsList))

        // Don't display the tab dots if there's only one entry, or if there are 25
        // or more (at which point the dots become not that useful). Note that the
        // entry with the most components at the moment ({cheqotlhchugh...}) has
        // 22 components. The Beginner's Conversation category has over 30 entries,
        // but being able to quickly go between them isn't that useful.
        if (entryIdsList.size > 1 && entryIdsList.size < 25) {
            val tabLayout = findViewById<TabLayout>(R.id.entry_tab_dots)
            tabLayout.setupWithViewPager(mPager, true)
        }
    }

    override fun onResume() {
        super.onResume()

        // TTS:
        // This is needed in onResume because we send the user to the Google Play Store to
        // install the TTS engine if it isn't already installed, so the status of the TTS
        // engine may change when this app resumes.
        // Log.d(TAG, "Initialising TTS")
        clearTTS()
        mTts = initTTS()
    }

    private fun initTTS(): TextToSpeech {
        // TTS:
        // Initialize text-to-speech. This is an asynchronous operation.
        // The OnInitListener (second argument) is called after initialization completes.
        return TextToSpeech(
            this,
            this, // TextToSpeech.OnInitListener
            "org.tlhInganHol.android.klingonttsengine"
        ) // Requires API 14.
    }

    private fun clearTTS() {
        mTts?.let {
            it.stop()
            it.shutdown()
        }
    }

    override fun onDestroy() {
        // TTS:
        // Don't forget to shutdown!
        // Log.d(TAG, "Shutting down TTS")
        clearTTS()
        super.onDestroy()
    }

    /*
     * TODO: Override onSave/RestoreInstanceState, onPause/Resume/Stop, to re-create links.
     *
     * public onSaveInstanceState() { // Save the text and views here. super.onSaveInstanceState(); }
     * public onRestoreInstanceState() { // Restore the text and views here.
     * super.onRestoreInstanceState(); }
     */

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        mShareButton = menu.findItem(R.id.action_share)

        // This is also updated in onPageSelected.
        if (mShareEntryIntent != null) {
            // Enable "Share" button.
            mShareButton?.isVisible = true
        }

        // TTS:
        // The button is disabled in the layout. It should only be enabled in EntryActivity.
        mSpeakButton = menu.findItem(R.id.action_speak)
        // if (ttsInitialized) {
        //   // Log.d(TAG, "enabling TTS button in onCreateOptionsMenu")
        mSpeakButton?.isVisible = true
        // }

        return true
    }

    // Set the share intent for this entry.
    private fun setShareEntryIntent(entry: KlingonContentProvider.Entry) {
        if (entry.isAlternativeSpelling()) {
            // Disable sharing alternative spelling entries.
            mShareEntryIntent = null
            return
        }

        val resources = resources
        mShareEntryIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TITLE, resources.getString(R.string.share_popup_title))
            type = "text/plain"
            val subject = "{${entry.getFormattedEntryName(/* isHtml */ false)}}"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            val snippet = "$subject\n${entry.getFormattedDefinition(/* isHtml */ false)}"
            putExtra(
                Intent.EXTRA_TEXT,
                "$snippet\n\n${resources.getString(R.string.shared_from)}"
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_speak -> { // TTS:
                if (!ttsInitialized) {
                    // The TTS engine is not installed (or disabled). Send user to Google Play Store or other
                    // market.
                    try {
                        launchExternal("market://details?id=org.tlhInganHol.android.klingonttsengine")
                    } catch (e: ActivityNotFoundException) {
                        // Fall back to browser.
                        launchExternal(
                            "https://play.google.com/store/apps/details?id=org.tlhInganHol.android.klingonttsengine"
                        )
                    }
                } else {
                    mEntry?.let {
                        // The TTS engine is working, and there's something to say, say it.
                        // Log.d(TAG, "Speaking")
                        // Toast.makeText(getBaseContext(), mEntry.getEntryName(), Toast.LENGTH_LONG).show()
                        mTts?.speak(it.getEntryName(), TextToSpeech.QUEUE_FLUSH, null, null)
                    }
                }
                return true
            }
            R.id.action_share -> { // Share using the Android Sharesheet.
                val shareIntent = Intent.createChooser(
                    mShareEntryIntent,
                    resources.getString(R.string.share_popup_title)
                )
                startActivity(shareIntent)
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    // TTS:
    // Implements TextToSpeech.OnInitListener.
    override fun onInit(status: Int) {
        // status can be either TextToSpeech.SUCCESS or TextToSpeech.ERROR.
        if (status == TextToSpeech.SUCCESS) {
            // Set preferred language to Canadian Klingon.
            // Note that a language may not be available, and the result will indicate this.
            val result = mTts?.setLanguage(Locale("tlh", "", ""))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Lanuage data is missing or the language is not supported.
                Log.e(TAG, "Language is not available.")
            } else {
                // Check the documentation for other possible result codes.
                // For example, the language may be available for the locale,
                // but not for the specified country and variant.

                // The TTS engine has been successfully initialized.
                ttsInitialized = true
                // if (mSpeakButton != null) {
                //   // Log.d(TAG, "enabling TTS button in onInit")
                //   mSpeakButton.setVisible(true)
                // }
            }
        } else {
            // Initialization failed.
            Log.e(TAG, "Could not initialize TextToSpeech.")
        }
    }

    // Swipe
    private inner class SwipeAdapter(
        fm: FragmentManager,
        entryIdsList: List<String>
    ) : FragmentStatePagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
        private val entryFragments: List<EntryFragment>

        init {
            // Set up all of the entry fragments.
            entryFragments = entryIdsList.map { id ->
                val uri = Uri.parse(
                    "${KlingonContentProvider.CONTENT_URI}/get_entry_by_id/$id"
                )
                EntryFragment.newInstance(uri)
            }
        }

        override fun getItem(position: Int): Fragment {
            return entryFragments[position]
        }

        override fun getCount(): Int {
            return entryFragments.size
        }
    }

    private inner class SwipePageChangeListener(
        private val mEntryIdsList: List<String>
    ) : ViewPager.OnPageChangeListener {

        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

        override fun onPageSelected(position: Int) {
            val uri = Uri.parse(
                "${KlingonContentProvider.CONTENT_URI}/get_entry_by_id/${mEntryIdsList[position]}"
            )

            val cursor = contentResolver.query(uri, KlingonContentDatabase.ALL_KEYS, null, null, null)!!
            val entry = KlingonContentProvider.Entry(cursor, baseContext)
            val entryId = entry.getId()

            // Update the entry (used for TTS output). This is also set in onCreate.
            mEntry = entry

            // Update share menu and set the visibility of the share button. The intent is also set in
            // onCreate, while the visibility is also set in onCreateOptionsMenu.
            setShareEntryIntent(entry)
            if (mShareEntryIntent != null) {
                // Enable "Share" button. Note that mShareButton can be null if the device has been rotated.
                mShareButton?.isVisible = true
            } else {
                // Disable "Share" button.
                mShareButton?.isVisible = false
            }

            // Update the bottom navigation buttons. This is also done in onCreate.
            updateBottomNavigationButtons(entryId)

            // Update the edit button. This is also done in onCreate.
            updateEditButton()
        }

        override fun onPageScrollStateChanged(state: Int) {}
    }

    private fun updateBottomNavigationButtons(entryId: Int) {
        val bottomNavView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val bottomNavMenu = bottomNavView.menu

        // Check for a previous entry.
        mPreviousEntryIntent = null
        for (i in 1..MAX_ENTRY_ID_DIFF) {
            val entryIntent = getEntryByIdIntent(entryId - i)
            if (entryIntent != null) {
                mPreviousEntryIntent = entryIntent
                break
            }
        }

        // Update the state of the "Previous" button.
        val previousButton = bottomNavMenu.findItem(R.id.action_previous)
        if (mPreviousEntryIntent == null) {
            previousButton.isEnabled = false
            bottomNavView.findViewById<View>(R.id.action_previous).visibility = View.INVISIBLE
        } else {
            previousButton.isEnabled = true
            bottomNavView.findViewById<View>(R.id.action_previous).visibility = View.VISIBLE
        }

        // Check for a next entry.
        mNextEntryIntent = null
        for (i in 1..MAX_ENTRY_ID_DIFF) {
            val entryIntent = getEntryByIdIntent(entryId + i)
            if (entryIntent != null) {
                mNextEntryIntent = entryIntent
                break
            }
        }

        // Update the state of the "Next" button.
        val nextButton = bottomNavMenu.findItem(R.id.action_next)
        if (mNextEntryIntent == null) {
            nextButton.isEnabled = false
            bottomNavView.findViewById<View>(R.id.action_next).visibility = View.INVISIBLE
        } else {
            nextButton.isEnabled = true
            bottomNavView.findViewById<View>(R.id.action_next).visibility = View.VISIBLE
        }

        bottomNavView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.action_previous -> goToPreviousEntry()
                R.id.action_random -> goToRandomEntry()
                R.id.action_next -> goToNextEntry()
            }
            false
        }
    }

    private fun updateEditButton() {
        // Enable FAB if conditions are met:
        val sharedPrefs = baseContext.getSharedPreferences("org.tlhInganHol.android.klingonassistant_preferences", android.content.Context.MODE_PRIVATE)
        val editLang = sharedPrefs.getString(
            Preferences.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE, /* default */
            Preferences.getSystemPreferredLanguage()
        )
        val showUnsupportedFeatures = sharedPrefs.getBoolean(
            Preferences.KEY_SHOW_UNSUPPORTED_FEATURES_CHECKBOX_PREFERENCE, /* default */ false
        )
        val fab = findViewById<FloatingActionButton>(R.id.fab)
        // Show the "edit" button for secondary languages other than German, Portuguese, and Finnish
        // (as these are complete except for the addition of new words), but also show for these
        // languages if unsupported features are enabled.
        if (mEntry != null &&
            editLang != "NONE" &&
            (showUnsupportedFeatures ||
                    (editLang != "de" && editLang != "pt" && editLang != "fi"))
        ) {
            fab.visibility = View.VISIBLE
            fab.setOnClickListener {
                val definitionTranslation = when (editLang) {
                    "de" -> mEntry?.getDefinition_DE()
                    "fa" -> mEntry?.getDefinition_FA()
                    "ru" -> mEntry?.getDefinition_RU()
                    "sv" -> mEntry?.getDefinition_SV()
                    "zh-HK" -> mEntry?.getDefinition_ZH_HK()
                    "pt" -> mEntry?.getDefinition_PT()
                    "fi" -> mEntry?.getDefinition_FI()
                    "fr" -> mEntry?.getDefinition_FR()
                    else -> null
                }
                // Open a form with fields filled in.
                val submitCorrectionTask = SubmitCorrectionTask()
                @Suppress("DEPRECATION")
                submitCorrectionTask.execute(
                    mEntry?.getEntryName(),
                    mEntry?.getPartOfSpeech(),
                    mEntry?.getDefinition(),
                    editLang,
                    definitionTranslation?.replace(" [AUTOTRANSLATED]", "")
                )
            }
        } else {
            fab.visibility = View.INVISIBLE
        }
    }

    private fun getEntryByIdIntent(entryId: Int): Intent? {
        contentResolver.query(
            Uri.parse("${KlingonContentProvider.CONTENT_URI}/get_entry_by_id/$entryId"),
            null /* all columns */,
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.count == 1) {
                val uri = Uri.parse(
                    "${KlingonContentProvider.CONTENT_URI}/get_entry_by_id/${cursor.getString(KlingonContentDatabase.COLUMN_ID)}"
                )

                val entryIntent = Intent(this, EntryActivity::class.java)

                // Form the URI for the entry.
                entryIntent.action = Intent.ACTION_VIEW
                entryIntent.data = uri

                return entryIntent
            }
        }
        return null
    }

    private fun goToPreviousEntry() {
        mPreviousEntryIntent?.let {
            startActivity(it)
        }
    }

    private fun goToRandomEntry() {
        val uri = Uri.parse("${KlingonContentProvider.CONTENT_URI}/get_random_entry")
        val randomEntryIntent = Intent(this, EntryActivity::class.java)
        randomEntryIntent.action = Intent.ACTION_VIEW
        randomEntryIntent.data = uri
        startActivity(randomEntryIntent)
    }

    private fun goToNextEntry() {
        mNextEntryIntent?.let {
            startActivity(it)
        }
    }

    // Generate a Google Forms form for submitting corrections to non-English definitions.
    @Suppress("DEPRECATION")
    private inner class SubmitCorrectionTask : AsyncTask<String, Void, Boolean>() {

        override fun doInBackground(vararg correction: String): Boolean {
            val entryName = correction[0]
            val partOfSpeech = correction[1]
            val definition = correction[2]
            val language = correction[3]
            val definitionTranslation = correction[4]
            val params: String
            try {
                params = "$CORRECTION_ENTRY_NAME_KEY=" +
                        URLEncoder.encode(entryName, "UTF-8") +
                        "&" + CORRECTION_PART_OF_SPEECH_KEY + "=" +
                        URLEncoder.encode(partOfSpeech, "UTF-8") +
                        "&" + CORRECTION_DEFINITION_KEY + "=" +
                        URLEncoder.encode(definition, "UTF-8") +
                        "&" + CORRECTION_LANGUAGE_KEY + "=" +
                        URLEncoder.encode(language, "UTF-8") +
                        "&" + CORRECTION_DEFINITION_TRANSLATION_KEY + "=" +
                        URLEncoder.encode(definitionTranslation, "UTF-8")
            } catch (e: UnsupportedEncodingException) {
                Log.e(TAG, "Failed to encode params.")
                return false
            }
            launchExternal("$CORRECTION_FORM_URL?$params")
            return true
        }
    }
}
