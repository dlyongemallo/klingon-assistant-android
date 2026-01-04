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

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import java.util.*

class Preferences : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Restore system (non-Klingon) locale.
        restoreLocaleConfiguration()

        setContentView(R.layout.activity_preferences)

        // Set up the toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Load the preferences fragment
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.preferences_container, PreferencesFragment())
                .commit()
        }
    }

    private fun restoreLocaleConfiguration() {
        // Always restore system (non-Klingon) locale here.
        val locale = KlingonAssistant.getSystemLocale()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val configuration = android.content.res.Configuration(baseContext.resources.configuration)
            configuration.setLocale(locale)
            baseContext.createConfigurationContext(configuration)
        } else {
            val configuration = baseContext.resources.configuration
            @Suppress("DEPRECATION")
            configuration.locale = locale
            @Suppress("DEPRECATION")
            baseContext.resources.updateConfiguration(
                configuration,
                baseContext.resources.displayMetrics
            )
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    class PreferencesFragment : PreferenceFragmentCompat(), OnSharedPreferenceChangeListener {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Load the preferences from an XML resource.
            setPreferencesFromResourceId(R.xml.preferences, rootKey)

            // Get a reference to the {pIqaD} list preference, and apply the display option to it.
            val klingonFontListPreference =
                findPreference<ListPreference>(KEY_KLINGON_FONT_LIST_PREFERENCE)
            klingonFontListPreference?.let { preference ->
                val title = preference.title.toString()
                val ssb: SpannableString
                if (!useKlingonFont(requireContext())) {
                    // Display in bold serif.
                    ssb = SpannableString(title)
                    ssb.setSpan(
                        StyleSpan(Typeface.BOLD),
                        0,
                        ssb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_INTERMEDIATE
                    )
                    ssb.setSpan(TypefaceSpan("serif"), 0, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    val klingonTitle = KlingonContentProvider.convertStringToKlingonFont(title)
                    ssb = SpannableString(klingonTitle)
                    val klingonTypeface = KlingonAssistant.getKlingonFontTypeface(requireContext())
                    ssb.setSpan(
                        KlingonTypefaceSpan("", klingonTypeface),
                        0,
                        ssb.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                preference.title = ssb
            }

            // TODO: Expand the language list to include incomplete languages if unsupported features is
            // selected. Switch to English if unsupported features has been deselected and an incomplete
            // language has been selected. Enable or disable the search in secondary language checkbox.
            val sharedPrefs = requireContext().getSharedPreferences("org.tlhInganHol.android.klingonassistant_preferences", Context.MODE_PRIVATE)
            val sharedPrefsEd = sharedPrefs.edit()

            // Support the legacy German options.
            val showGerman = sharedPrefs.getBoolean(
                KEY_SHOW_GERMAN_DEFINITIONS_CHECKBOX_PREFERENCE, /* default */ false
            )
            if (showGerman) {
                // Copy to the new settings.
                sharedPrefsEd.putString(KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE, "de")

                // Clear the legacy settings.
                sharedPrefsEd.putBoolean(KEY_SHOW_GERMAN_DEFINITIONS_CHECKBOX_PREFERENCE, false)
                sharedPrefsEd.putBoolean(KEY_SEARCH_GERMAN_DEFINITIONS_CHECKBOX_PREFERENCE, false)

                sharedPrefsEd.putBoolean(KEY_LANGUAGE_DEFAULT_ALREADY_SET, true)
                sharedPrefsEd.apply()
            }

            // Set the defaults for the other-language options based on the user's language, if it hasn't
            // been already set.
            if (!sharedPrefs.getBoolean(KEY_LANGUAGE_DEFAULT_ALREADY_SET, /* default */ false)) {
                val mShowOtherLanguageListPreference =
                    findPreference<ListPreference>(KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE)
                mShowOtherLanguageListPreference?.value = getSystemPreferredLanguage()

                sharedPrefsEd.putBoolean(KEY_LANGUAGE_DEFAULT_ALREADY_SET, true)
                sharedPrefsEd.apply()
            }

            val dataChangelogButtonPreference =
                findPreference<Preference>(KEY_DATA_CHANGELOG_BUTTON_PREFERENCE)
            dataChangelogButtonPreference?.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    val installedVersion = sharedPrefs.getString(
                        KlingonContentDatabase.KEY_INSTALLED_DATABASE_VERSION,
                        /* default */ KlingonContentDatabase.getBundledDatabaseVersion()
                    )
                    launchExternal(
                        "https://github.com/De7vID/klingon-assistant-data/commits/master@{$installedVersion}"
                    )
                    true
                }

            val codeChangelogButtonPreference =
                findPreference<Preference>(KEY_CODE_CHANGELOG_BUTTON_PREFERENCE)
            codeChangelogButtonPreference?.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    // The bundled database version is the app's built date.
                    launchExternal(
                        "https://github.com/De7vID/klingon-assistant-android/commits/master@{${KlingonContentDatabase.getBundledDatabaseVersion()}}"
                    )
                    true
                }
        }

        override fun onResume() {
            super.onResume()

            // Restore system (non-Klingon) locale.
            (activity as? Preferences)?.restoreLocaleConfiguration()

            // Set up a listener whenever a key changes.
            preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            super.onPause()

            // Unregister the listener whenever a key changes.
            preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(sharedPrefs: SharedPreferences?, key: String?) {
            if (key != null && (key == KEY_KLINGON_FONT_LIST_PREFERENCE || key == KEY_KLINGON_UI_CHECKBOX_PREFERENCE)) {
                // User has changed the Klingon font option or UI language, display a warning.
                AlertDialog.Builder(requireContext())
                    .setIcon(R.drawable.alert_dialog_icon)
                    .setTitle(R.string.warning)
                    .setMessage(R.string.change_ui_language_warning)
                    .setCancelable(false) // Can't be canceled with the BACK key.
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        // Since the display options have changed, everything needs to be redrawn.
                        activity?.recreate()
                    }
                    .show()
            }
            // TODO: React to unsupported features and secondary language options changes here.
        }

        // Method to launch an external app or web site.
        // See identical method in BaseActivity.
        private fun launchExternal(externalUrl: String) {
            val intent = Intent(Intent.ACTION_VIEW)
            // Set NEW_TASK so the external app or web site is independent.
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.data = Uri.parse(externalUrl)
            startActivity(intent)
        }

        // Helper method to set preferences from resource with proper type handling
        private fun setPreferencesFromResourceId(preferencesResId: Int, key: String?) {
            setPreferencesFromResource(preferencesResId, key)
        }
    }

    companion object {
        // Language preferences.
        private const val KEY_KLINGON_UI_CHECKBOX_PREFERENCE = "klingon_ui_checkbox_preference"
        private const val KEY_KLINGON_FONT_LIST_PREFERENCE = "klingon_font_list_preference"
        private const val KEY_LANGUAGE_DEFAULT_ALREADY_SET = "language_default_already_set"
        const val KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE =
            "show_secondary_language_list_preference"

        // Legacy support for German, will eventually be deprecated and replaced by secondary language
        // support.
        const val KEY_SHOW_GERMAN_DEFINITIONS_CHECKBOX_PREFERENCE =
            "show_german_definitions_checkbox_preference"
        const val KEY_SEARCH_GERMAN_DEFINITIONS_CHECKBOX_PREFERENCE =
            "search_german_definitions_checkbox_preference"

        // Input preferences.
        const val KEY_XIFAN_HOL_CHECKBOX_PREFERENCE = "xifan_hol_checkbox_preference"
        const val KEY_SWAP_QS_CHECKBOX_PREFERENCE = "swap_qs_checkbox_preference"

        // Social preferences.
        const val KEY_SOCIAL_NETWORK_LIST_PREFERENCE = "social_network_list_preference"

        // Informational preferences.
        const val KEY_SHOW_TRANSITIVITY_CHECKBOX_PREFERENCE =
            "show_transitivity_checkbox_preference"
        const val KEY_SHOW_ADDITIONAL_INFORMATION_CHECKBOX_PREFERENCE =
            "show_additional_information_checkbox_preference"
        const val KEY_KWOTD_CHECKBOX_PREFERENCE = "kwotd_checkbox_preference"
        const val KEY_UPDATE_DB_CHECKBOX_PREFERENCE = "update_db_checkbox_preference"

        // Under construction.
        const val KEY_SHOW_UNSUPPORTED_FEATURES_CHECKBOX_PREFERENCE =
            "show_unsupported_features_checkbox_preference"

        // Changelogs.
        const val KEY_DATA_CHANGELOG_BUTTON_PREFERENCE = "data_changelog_button_preference"
        const val KEY_CODE_CHANGELOG_BUTTON_PREFERENCE = "code_changelog_button_preference"

        // Detect if the system language is a supported language.
        @JvmStatic
        fun getSystemPreferredLanguage(): String {
            val language = KlingonAssistant.getSystemLocale().language
            return when {
                language == Locale.GERMAN.language -> "de"
                language == Locale("fa").language -> "fa"
                language == Locale("ru").language -> "ru"
                language == Locale("sv").language -> "sv"
                language == Locale.CHINESE.language -> "zh-HK" // TODO: Distinguish different topolects
                language == Locale("pt").language -> "pt"
                language == Locale("fi").language -> "fi"
                language == Locale.FRENCH.language -> "fr"
                else -> "NONE"
            }
        }

        @JvmStatic
        fun setDefaultSecondaryLanguage(context: Context) {
            val sharedPrefs = context.getSharedPreferences("org.tlhInganHol.android.klingonassistant_preferences", Context.MODE_PRIVATE)
            if (!sharedPrefs.getBoolean(KEY_LANGUAGE_DEFAULT_ALREADY_SET, /* default */ false)) {
                val sharedPrefsEd = sharedPrefs.edit()
                sharedPrefsEd.putString(
                    KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE,
                    getSystemPreferredLanguage()
                )
                sharedPrefsEd.putBoolean(KEY_LANGUAGE_DEFAULT_ALREADY_SET, true)
                sharedPrefsEd.apply()
            }
        }

        // Whether the UI (menus, hints, etc.) should be displayed in Klingon.
        @JvmStatic
        fun useKlingonUI(context: Context): Boolean {
            val sharedPrefs = context.getSharedPreferences("org.tlhInganHol.android.klingonassistant_preferences", Context.MODE_PRIVATE)
            return sharedPrefs.getBoolean(
                KEY_KLINGON_UI_CHECKBOX_PREFERENCE, /* default */ false
            )
        }

        // Whether a Klingon font should be used when display Klingon text.
        @JvmStatic
        fun useKlingonFont(context: Context): Boolean {
            val sharedPrefs = context.getSharedPreferences("org.tlhInganHol.android.klingonassistant_preferences", Context.MODE_PRIVATE)
            val value = sharedPrefs.getString(KEY_KLINGON_FONT_LIST_PREFERENCE, /* default */ "LATIN")
            return value == "TNG" || value == "DSC" || value == "CORE"
        }

        // Returns which font should be used for Klingon: returns one of "LATIN", "TNG", "DSC", or "CORE".
        @JvmStatic
        fun getKlingonFontCode(context: Context): String {
            val sharedPrefs = context.getSharedPreferences("org.tlhInganHol.android.klingonassistant_preferences", Context.MODE_PRIVATE)
            return sharedPrefs.getString(KEY_KLINGON_FONT_LIST_PREFERENCE, /* default */ "LATIN")
                ?: "LATIN"
        }
    }
}
