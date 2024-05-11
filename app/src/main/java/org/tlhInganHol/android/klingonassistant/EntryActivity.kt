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
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.preference.PreferenceManager
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.OnInitListener
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import androidx.viewpager.widget.ViewPager.OnPageChangeListener
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.Arrays
import java.util.Locale

/** Displays an entry and its definition.  */
class EntryActivity // TTS:
    : BaseActivity(), OnInitListener {
    // The currently-displayed entry (which can change due to page selection).
    private var mEntry: KlingonContentProvider.Entry? = null

    // The parent query that this entry is a part of.
    // private String mParentQuery = null;
    // The intent holding the data to be shared, and the associated UI.
    private var mShareEntryIntent: Intent? = null
    var mShareButton: MenuItem? = null

    // Intents for the bottom navigation buttons.
    // Note that the renumber.py script ensures that the IDs of adjacent entries
    // are consecutive across the entire database.
    private var mPreviousEntryIntent: Intent? = null
    private var mNextEntryIntent: Intent? = null
    // TTS:
    /** The [TextToSpeech] used for speaking.  */
    private var mTts: TextToSpeech? = null

    private var mSpeakButton: MenuItem? = null
    private var ttsInitialized = false

    // Handle swipe. The pager widget handles animation and allows swiping
    // horizontally. The pager adapter provides the pages to the pager widget.
    private var mPager: ViewPager? = null
    private var mPagerAdapter: PagerAdapter? = null
    private var mEntryIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TTS:
        // Log.d(TAG, "Initialising TTS");
        clearTTS()
        mTts = initTTS()

        setDrawerContentView(R.layout.entry_swipe)

        val inputUri = intent.data

        // Log.d(TAG, "EntryActivity - inputUri: " + inputUri.toString());
        // TODO: Disable the "About" menu item if this is the "About" entry.
        // mParentQuery = getIntent().getStringExtra(SearchManager.QUERY);

        // Determine whether we're launching a single entry, or a list of entries.
        // If it's a single entry, the URI will end in "get_entry_by_id/" followed
        // by the one ID. In the case of a list, the URI will end in "get_entry_by_id/"
        // follwoed by a list of comma-separated IDs, with one additional item at the
        // end for the position of the current entry. For a random entry, the URI will
        // end in "get_random_entry", with no ID at all.
        val ids = inputUri!!.lastPathSegment!!.split(",".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray()
        var queryUri: Uri? = null
        val entryIdsList: MutableList<String> = ArrayList(Arrays.asList(*ids))
        if (entryIdsList.size == 1) {
            // There is only one entry to display. Either its ID was explicitly
            // given, or we want a random entry.
            queryUri = inputUri
            mEntryIndex = 0
        } else {
            // Parse the comma-separated list, the last entry of which is the
            // position index. We nees to construct the queryUri based on the
            // intended current entry.
            mEntryIndex = entryIdsList[ids.size - 1].toInt()
            entryIdsList.removeAt(ids.size - 1)
            queryUri =
                Uri.parse(
                    KlingonContentProvider.CONTENT_URI
                        .toString() + "/get_entry_by_id/"
                            + entryIdsList[mEntryIndex]
                )
        }

        // Retrieve the entry's data.
        // Note: managedQuery is deprecated since API 11.
        val cursor = managedQuery(queryUri, KlingonContentDatabase.ALL_KEYS, null, null, null)
        val entry =
            KlingonContentProvider.Entry(cursor, baseContext)
        val entryId = entry.id

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
        mPager = findViewById<View>(R.id.entry_pager) as ViewPager
        mPagerAdapter = SwipeAdapter(supportFragmentManager, entryIdsList)
        mPager!!.adapter = mPagerAdapter
        mPager!!.setCurrentItem(mEntryIndex,  /* smoothScroll */false)
        mPager!!.setOnPageChangeListener(SwipePageChangeListener(entryIdsList))

        // Don't display the tab dots if there's only one entry, or if there are 25
        // or more (at which point the dots become not that useful). Note that the
        // entry with the most components at the moment ({cheqotlhchugh...}) has
        // 22 components. The Beginner's Conversation category has over 30 entries,
        // but being able to quickly go between them isn't that useful.
        if (entryIdsList.size > 1 && entryIdsList.size < 25) {
            val tabLayout = findViewById<View>(R.id.entry_tab_dots) as TabLayout
            tabLayout.setupWithViewPager(mPager, true)
        }
    }

    override fun onResume() {
        super.onResume()

        // TTS:
        // This is needed in onResume because we send the user to the Google Play Store to
        // install the TTS engine if it isn't already installed, so the status of the TTS
        // engine may change when this app resumes.
        // Log.d(TAG, "Initialising TTS");
        clearTTS()
        mTts = initTTS()
    }

    private fun initTTS(): TextToSpeech {
        // TTS:
        // Initialize text-to-speech. This is an asynchronous operation.
        // The OnInitListener (second argument) is called after initialization completes.
        return TextToSpeech(
            this,
            this,  // TextToSpeech.OnInitListener
            "org.tlhInganHol.android.klingonttsengine"
        ) // Requires API 14.
    }

    private fun clearTTS() {
        if (mTts != null) {
            mTts!!.stop()
            mTts!!.shutdown()
        }
    }

    override fun onDestroy() {
        // TTS:
        // Don't forget to shutdown!
        // Log.d(TAG, "Shutting down TTS");
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
            mShareButton.setVisible(true)
        }

        // TTS:
        // The button is disabled in the layout. It should only be enabled in EntryActivity.
        mSpeakButton = menu.findItem(R.id.action_speak)
        // if (ttsInitialized) {
        //   // Log.d(TAG, "enabling TTS button in onCreateOptionsMenu");
        mSpeakButton.setVisible(true)

        // }
        return true
    }

    // Set the share intent for this entry.
    private fun setShareEntryIntent(entry: KlingonContentProvider.Entry) {
        if (entry.isAlternativeSpelling) {
            // Disable sharing alternative spelling entries.
            mShareEntryIntent = null
            return
        }

        val resources = resources
        mShareEntryIntent = Intent(Intent.ACTION_SEND)
        mShareEntryIntent!!.putExtra(
            Intent.EXTRA_TITLE,
            resources.getString(R.string.share_popup_title)
        )
        mShareEntryIntent!!.setType("text/plain")
        val subject = "{" + entry.getFormattedEntryName( /* isHtml */false) + "}"
        mShareEntryIntent!!.putExtra(Intent.EXTRA_SUBJECT, subject)
        val snippet = """
             $subject
             ${entry.getFormattedDefinition( /* isHtml */false)}
             """.trimIndent()
        mShareEntryIntent!!.putExtra(
            Intent.EXTRA_TEXT, """
     $snippet
     
     ${resources.getString(R.string.shared_from)}
     """.trimIndent()
        )
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val itemId = item.itemId
        if (itemId == R.id.action_speak) { // TTS:
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
            } else if (mEntry != null) {
                // The TTS engine is working, and there's something to say, say it.
                // Log.d(TAG, "Speaking");
                // Toast.makeText(getBaseContext(), mEntry.getEntryName(), Toast.LENGTH_LONG).show();
                mTts!!.speak(mEntry!!.entryName, TextToSpeech.QUEUE_FLUSH, null)
            }
            return true
        } else if (itemId == R.id.action_share) { // Share using the Android Sharesheet.
            val shareIntent =
                Intent.createChooser(
                    mShareEntryIntent, resources.getString(R.string.share_popup_title)
                )
            startActivity(shareIntent)
            return true
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
            val result = mTts!!.setLanguage(Locale("tlh", "", ""))
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
                //   // Log.d(TAG, "enabling TTS button in onInit");
                //   mSpeakButton.setVisible(true);
                // }
            }
        } else {
            // Initialization failed.
            Log.e(TAG, "Could not initialize TextToSpeech.")
        }
    }

    // Swipe
    private inner class SwipeAdapter(fm: FragmentManager?, entryIdsList: List<String>) :
        FragmentStatePagerAdapter(
            fm!!
        ) {
        private var entryFragments: MutableList<EntryFragment>? = null

        init {
            // Set up all of the entry fragments.
            entryFragments = ArrayList()
            for (i in entryIdsList.indices) {
                val uri =
                    Uri.parse(
                        KlingonContentProvider.CONTENT_URI.toString() + "/get_entry_by_id/" + entryIdsList[i]
                    )
                entryFragments.add(EntryFragment.newInstance(uri))
            }
        }

        override fun getItem(position: Int): Fragment {
            return entryFragments!![position]
        }

        override fun getCount(): Int {
            return entryFragments!!.size
        }
    }

    private inner class SwipePageChangeListener(entryIdsList: List<String>?) :
        OnPageChangeListener {
        var mEntryIdsList: List<String>? = null

        init {
            mEntryIdsList = entryIdsList
        }

        override fun onPageScrolled(
            position: Int,
            positionOffset: Float,
            positionOffsetPixels: Int
        ) {
        }

        override fun onPageSelected(position: Int) {
            val uri =
                Uri.parse(
                    KlingonContentProvider.CONTENT_URI
                        .toString() + "/get_entry_by_id/"
                            + mEntryIdsList!![position]
                )

            // Note: managedQuery is deprecated since API 11.
            val cursor = managedQuery(uri, KlingonContentDatabase.ALL_KEYS, null, null, null)
            val entry =
                KlingonContentProvider.Entry(cursor, baseContext)
            val entryId = entry.id

            // Update the entry (used for TTS output). This is also set in onCreate.
            mEntry = entry

            // Update share menu and set the visibility of the share button. The intent is also set in
            // onCreate, while the visibility is also set in onCreateOptionsMenu.
            setShareEntryIntent(entry)
            if (mShareEntryIntent != null) {
                // Enable "Share" button. Note that mShareButton can be null if the device has been rotated.
                if (mShareButton != null) {
                    mShareButton!!.setVisible(true)
                }
            } else {
                // Disable "Share" button.
                if (mShareButton != null) {
                    mShareButton!!.setVisible(false)
                }
            }

            // Update the bottom navigation buttons. This is also done in onCreate.
            updateBottomNavigationButtons(entryId)

            // Update the edit button. This is also done in onCreate.
            updateEditButton()
        }

        override fun onPageScrollStateChanged(state: Int) {}
    }

    private fun updateBottomNavigationButtons(entryId: Int) {
        val bottomNavView =
            findViewById<View>(R.id.bottom_navigation) as BottomNavigationView
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
        val previousButton = bottomNavMenu.findItem(R.id.action_previous) as MenuItem
        if (mPreviousEntryIntent == null) {
            previousButton.setEnabled(false)
            bottomNavView.findViewById<View>(R.id.action_previous).visibility = View.INVISIBLE
        } else {
            previousButton.setEnabled(true)
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
        val nextButton = bottomNavMenu.findItem(R.id.action_next) as MenuItem
        if (mNextEntryIntent == null) {
            nextButton.setEnabled(false)
            bottomNavView.findViewById<View>(R.id.action_next).visibility = View.INVISIBLE
        } else {
            nextButton.setEnabled(true)
            bottomNavView.findViewById<View>(R.id.action_next).visibility = View.VISIBLE
        }

        bottomNavView.setOnNavigationItemSelectedListener { item ->
            val itemId = item.itemId
            if (itemId == R.id.action_previous) {
                goToPreviousEntry()
            } else if (itemId == R.id.action_random) {
                goToRandomEntry()
            } else if (itemId == R.id.action_next) {
                goToNextEntry()
            }
            false
        }
    }

    private fun updateEditButton() {
        // Enable FAB if conditions are met:
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(baseContext)
        val editLang =
            sharedPrefs.getString(
                Preferences.KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE,  /* default */
                Preferences.getSystemPreferredLanguage()
            )
        val showUnsupportedFeatures =
            sharedPrefs.getBoolean(
                Preferences.KEY_SHOW_UNSUPPORTED_FEATURES_CHECKBOX_PREFERENCE,  /* default */false
            )
        val fab = findViewById<View>(R.id.fab) as FloatingActionButton
        // Show the "edit" button for secondary languages other than German, Portuguese, and Finnish
        // (as these are complete except for the addition of new words), but also show for these
        // languages if unsupported features are enabled.
        if (mEntry != null && editLang != "NONE"
            && (showUnsupportedFeatures
                    || (editLang != "de" && editLang != "pt" && editLang != "fi"))
        ) {
            fab.visibility = View.VISIBLE
            fab.setOnClickListener {
                var definitionTranslation: String? = null
                when (editLang) {
                    "de" -> definitionTranslation = mEntry!!.definition_DE
                    "fa" -> definitionTranslation = mEntry!!.definition_FA
                    "ru" -> definitionTranslation = mEntry!!.definition_RU
                    "sv" -> definitionTranslation = mEntry!!.definition_SV
                    "zh-HK" -> definitionTranslation = mEntry!!.definition_ZH_HK
                    "pt" -> definitionTranslation = mEntry!!.definition_PT
                    "fi" -> definitionTranslation = mEntry!!.definition_FI
                    "fr" -> definitionTranslation = mEntry!!.definition_FR
                }
                // Open a form with fields filled in.
                val submitCorrectionTask = SubmitCorrectionTask()
                submitCorrectionTask.execute(
                    mEntry!!.entryName,
                    mEntry!!.partOfSpeech,
                    mEntry!!.definition,
                    editLang,
                    definitionTranslation!!.replace(" [AUTOTRANSLATED]", "")
                )
            }
        } else {
            fab.visibility = View.INVISIBLE
        }
    }

    private fun getEntryByIdIntent(entryId: Int): Intent? {
        val cursor = managedQuery(
            Uri.parse(KlingonContentProvider.CONTENT_URI.toString() + "/get_entry_by_id/" + entryId),
            null,  /* all columns */
            null,
            null,
            null
        )
        if (cursor.count == 1) {
            val uri =
                Uri.parse(
                    KlingonContentProvider.CONTENT_URI
                        .toString() + "/get_entry_by_id/"
                            + cursor.getString(KlingonContentDatabase.COLUMN_ID)
                )

            val entryIntent = Intent(this, EntryActivity::class.java)

            // Form the URI for the entry.
            entryIntent.setAction(Intent.ACTION_VIEW)
            entryIntent.setData(uri)

            return entryIntent
        }
        return null
    }

    private fun goToPreviousEntry() {
        if (mPreviousEntryIntent != null) {
            startActivity(mPreviousEntryIntent)
        }
    }

    private fun goToRandomEntry() {
        val uri = Uri.parse(KlingonContentProvider.CONTENT_URI.toString() + "/get_random_entry")
        val randomEntryIntent = Intent(this, EntryActivity::class.java)
        randomEntryIntent.setAction(Intent.ACTION_VIEW)
        randomEntryIntent.setData(uri)
        startActivity(randomEntryIntent)
    }

    private fun goToNextEntry() {
        if (mNextEntryIntent != null) {
            startActivity(mNextEntryIntent)
        }
    }

    // Generate a Google Forms form for submitting corrections to non-English definitions.
    private inner class SubmitCorrectionTask : AsyncTask<String?, Void?, Boolean>() {
        protected override fun doInBackground(vararg correction: String): Boolean {
            val result = true
            val entry_name = correction[0]
            val part_of_speech = correction[1]
            val definition = correction[2]
            val language = correction[3]
            val definition_translation = correction[4]
            var params = ""
            try {
                params =
                    (Companion.CORRECTION_ENTRY_NAME_KEY
                            + "="
                            + URLEncoder.encode(entry_name, "UTF-8")
                            + "&"
                            + Companion.CORRECTION_PART_OF_SPEECH_KEY
                            + "="
                            + URLEncoder.encode(part_of_speech, "UTF-8")
                            + "&"
                            + Companion.CORRECTION_DEFINITION_KEY
                            + "="
                            + URLEncoder.encode(definition, "UTF-8")
                            + "&"
                            + Companion.CORRECTION_LANGUAGE_KEY
                            + "="
                            + URLEncoder.encode(language, "UTF-8")
                            + "&"
                            + Companion.CORRECTION_DEFINITION_TRANSLATION_KEY
                            + "="
                            + URLEncoder.encode(definition_translation, "UTF-8"))
            } catch (e: UnsupportedEncodingException) {
                Log.e(TAG, "Failed to encode params.")
                return false
            }
            launchExternal(Companion.CORRECTION_FORM_URL + "?" + params)
            return true
        }

        companion object {
            private const val CORRECTION_FORM_URL =
                "https://docs.google.com/forms/d/e/1FAIpQLSdubRpIpbPFHAclzNx3jrOT85nQLGYCgWPOjIHxPocrecZUzw/viewform"
            private const val CORRECTION_ENTRY_NAME_KEY = "entry.1852970057"
            private const val CORRECTION_PART_OF_SPEECH_KEY = "entry.1015346696"
            private const val CORRECTION_DEFINITION_KEY = "entry.166391661"
            private const val CORRECTION_LANGUAGE_KEY = "entry.2030201514"
            private const val CORRECTION_DEFINITION_TRANSLATION_KEY = "entry.1343345"
        }
    }

    companion object {
        private const val TAG = "EntryActivity"

        private const val MAX_ENTRY_ID_DIFF = 5
    }
}
