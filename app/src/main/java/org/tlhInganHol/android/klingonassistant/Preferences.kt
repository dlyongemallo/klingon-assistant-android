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
import android.preference.ListPreference
import android.preference.Preference.OnPreferenceClickListener
import android.preference.PreferenceManager
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import java.util.Locale

class Preferences : AppCompatPreferenceActivity(), OnSharedPreferenceChangeListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Restore system (non-Klingon) locale.
        restoreLocaleConfiguration()

        // Set up the toolbar for an AppCompatPreferenceActivity.
        setupActionBar()

        // Load the preferences from an XML resource.
        addPreferencesFromResource(R.xml.preferences)

        // Get a reference to the {pIqaD} list preference, and apply the display option to it.
        val klingonFontListPreference =
            preferenceScreen.findPreference(KEY_KLINGON_FONT_LIST_PREFERENCE) as ListPreference
        val title = klingonFontListPreference.title.toString()
        val ssb: SpannableString
        if (!useKlingonFont(
                baseContext
            )
        ) {
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
            val klingonTitle: String =
                KlingonContentProvider.Companion.convertStringToKlingonFont(title)
            ssb = SpannableString(klingonTitle)
            val klingonTypeface: Typeface? = KlingonAssistant.getKlingonFontTypeface(
                baseContext
            )
            ssb.setSpan(
                KlingonTypefaceSpan("", klingonTypeface),
                0,
                ssb.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        klingonFontListPreference.title = ssb

        // TODO: Expand the language list to include incomplete languages if unsupported features is
        // selected. Switch to English if unsupported features has been deselected and an incomplete
        // language has been selected. Enable or disable the search in secondary language checkbox.
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(baseContext)
        val sharedPrefsEd = sharedPrefs.edit()

        // Support the legacy German options.
        val showGerman =
            sharedPrefs.getBoolean(
                KEY_SHOW_GERMAN_DEFINITIONS_CHECKBOX_PREFERENCE,  /* default */false
            )
        if (showGerman) {
            val searchGerman =
                sharedPrefs.getBoolean(
                    KEY_SEARCH_GERMAN_DEFINITIONS_CHECKBOX_PREFERENCE,  /* default */false
                )

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
        if (!sharedPrefs.getBoolean(KEY_LANGUAGE_DEFAULT_ALREADY_SET,  /* default */false)) {
            val mShowOtherLanguageListPreference =
                preferenceScreen.findPreference(KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE) as ListPreference
            mShowOtherLanguageListPreference.value =
                systemPreferredLanguage

            sharedPrefsEd.putBoolean(KEY_LANGUAGE_DEFAULT_ALREADY_SET, true)
            sharedPrefsEd.apply()
        }

        val dataChangelogButtonPreference =
            preferenceScreen.findPreference(KEY_DATA_CHANGELOG_BUTTON_PREFERENCE)
        dataChangelogButtonPreference.onPreferenceClickListener = OnPreferenceClickListener {
            val sharedPrefs =
                PreferenceManager.getDefaultSharedPreferences(baseContext)
            val installedVersion =
                sharedPrefs.getString(
                    KlingonContentDatabase.KEY_INSTALLED_DATABASE_VERSION,  /* default */
                    KlingonContentDatabase.bundledDatabaseVersion
                )
            launchExternal(
                "https://github.com/De7vID/klingon-assistant-data/commits/master@{"
                        + installedVersion
                        + "}"
            )
            true
        }
        val codeChangelogButtonPreference =
            preferenceScreen.findPreference(KEY_CODE_CHANGELOG_BUTTON_PREFERENCE)
        codeChangelogButtonPreference.onPreferenceClickListener =
            OnPreferenceClickListener { // The bundled database version is the app's built date.
                launchExternal(
                    "https://github.com/De7vID/klingon-assistant-android/commits/master@{"
                            + KlingonContentDatabase.bundledDatabaseVersion
                            + "}"
                )
                true
            }
    }

    private fun restoreLocaleConfiguration() {
        // Always restore system (non-Klingon) locale here.
        val locale: Locale = KlingonAssistant.systemLocale
        val configuration = baseContext.resources.configuration
        configuration.locale = locale
        baseContext
            .resources
            .updateConfiguration(configuration, baseContext.resources.displayMetrics)
    }

    private fun setupActionBar() {
        // This only works in ICS (API 14) and up.
        val root =
            findViewById<View>(android.R.id.list).parent.parent.parent as ViewGroup
        val toolbar =
            LayoutInflater.from(this).inflate(R.layout.view_toolbar, root, false) as Toolbar
        root.addView(toolbar, 0)
        setSupportActionBar(toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }

            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()

        // Restore system (non-Klingon) locale.
        restoreLocaleConfiguration()

        // Set up a listener whenever a key changes.
        preferenceScreen.sharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()

        // Unregister the listener whenever a key changes.
        preferenceScreen.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPrefs: SharedPreferences, key: String) {
        if (key == KEY_KLINGON_FONT_LIST_PREFERENCE || key == KEY_KLINGON_UI_CHECKBOX_PREFERENCE) {
            // User has changed the Klingon font option or UI language, display a warning.
            AlertDialog.Builder(this)
                .setIcon(R.drawable.alert_dialog_icon)
                .setTitle(R.string.warning)
                .setMessage(R.string.change_ui_language_warning)
                .setCancelable(false) // Can't be canceled with the BACK key.
                .setPositiveButton(
                    android.R.string.yes
                ) { dialog, whichButton -> // Since the display options have changed, everything needs to be redrawn.
                    recreate()
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
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.setData(Uri.parse(externalUrl))
        startActivity(intent)
    }

    companion object {
        // Language preferences.
        private const val KEY_KLINGON_UI_CHECKBOX_PREFERENCE = "klingon_ui_checkbox_preference"
        private const val KEY_KLINGON_FONT_LIST_PREFERENCE = "klingon_font_list_preference"
        private const val KEY_LANGUAGE_DEFAULT_ALREADY_SET = "language_default_already_set"
        const val KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE: String =
            "show_secondary_language_list_preference"

        // Legacy support for German, will eventually be deprecated and replaced by secondary language
        // support.
        const val KEY_SHOW_GERMAN_DEFINITIONS_CHECKBOX_PREFERENCE: String =
            "show_german_definitions_checkbox_preference"
        const val KEY_SEARCH_GERMAN_DEFINITIONS_CHECKBOX_PREFERENCE: String =
            "search_german_definitions_checkbox_preference"

        // Input preferences.
        const val KEY_XIFAN_HOL_CHECKBOX_PREFERENCE: String = "xifan_hol_checkbox_preference"
        const val KEY_SWAP_QS_CHECKBOX_PREFERENCE: String = "swap_qs_checkbox_preference"

        // Social preferences.
        const val KEY_SOCIAL_NETWORK_LIST_PREFERENCE: String = "social_network_list_preference"

        // Informational preferences.
        const val KEY_SHOW_TRANSITIVITY_CHECKBOX_PREFERENCE: String =
            "show_transitivity_checkbox_preference"
        const val KEY_SHOW_ADDITIONAL_INFORMATION_CHECKBOX_PREFERENCE: String =
            "show_additional_information_checkbox_preference"
        const val KEY_KWOTD_CHECKBOX_PREFERENCE: String = "kwotd_checkbox_preference"
        const val KEY_UPDATE_DB_CHECKBOX_PREFERENCE: String = "update_db_checkbox_preference"

        // Under construction.
        const val KEY_SHOW_UNSUPPORTED_FEATURES_CHECKBOX_PREFERENCE: String =
            "show_unsupported_features_checkbox_preference"

        // Changelogs.
        const val KEY_DATA_CHANGELOG_BUTTON_PREFERENCE: String = "data_changelog_button_preference"
        const val KEY_CODE_CHANGELOG_BUTTON_PREFERENCE: String = "code_changelog_button_preference"

        val systemPreferredLanguage: String
            // Detect if the system language is a supported language.
            get() {
                val language: String = KlingonAssistant.systemLocale.language
                if (language === Locale.GERMAN.language) {
                    return "de"
                } else if (language === Locale("fa").language) {
                    return "fa"
                } else if (language === Locale("ru").language) {
                    return "ru"
                } else if (language === Locale("sv").language) {
                    return "sv"
                } else if (language === Locale.CHINESE.language) {
                    // TODO: Distinguish different topolects of Chinese. For now, prefer Hong Kong Chinese if the
                    // system locale is any topolect of Chinese.
                    return "zh-HK"
                } else if (language === Locale("pt").language) {
                    // Note: The locale code "pt" is Brazilian Portuguese. (European Portuguese is "pt-PT".)
                    return "pt"
                } else if (language === Locale("fi").language) {
                    return "fi"
                } else if (language === Locale.FRENCH.language) {
                    return "fr"
                }
                return "NONE"
            }

        fun setDefaultSecondaryLanguage(context: Context?) {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (!sharedPrefs.getBoolean(KEY_LANGUAGE_DEFAULT_ALREADY_SET,  /* default */false)) {
                val sharedPrefsEd = sharedPrefs.edit()
                sharedPrefsEd.putString(
                    KEY_SHOW_SECONDARY_LANGUAGE_LIST_PREFERENCE, systemPreferredLanguage
                )
                sharedPrefsEd.putBoolean(KEY_LANGUAGE_DEFAULT_ALREADY_SET, true)
                sharedPrefsEd.apply()
            }
        }

        // Whether the UI (menus, hints, etc.) should be displayed in Klingon.
        fun useKlingonUI(context: Context?): Boolean {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            return sharedPrefs.getBoolean(
                KEY_KLINGON_UI_CHECKBOX_PREFERENCE,  /* default */false
            )
        }

        // Whether a Klingon font should be used when display Klingon text.
        fun useKlingonFont(context: Context?): Boolean {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val value =
                sharedPrefs.getString(KEY_KLINGON_FONT_LIST_PREFERENCE,  /* default */"LATIN")
            return value == "TNG" || value == "DSC" || value == "CORE"
        }

        // Returns which font should be used for Klingon: returns one of "LATIN", "TNG", "DSC", or "CORE".
        fun getKlingonFontCode(context: Context?): String? {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            return sharedPrefs.getString(KEY_KLINGON_FONT_LIST_PREFERENCE,  /* default */"LATIN")
        }
    }
}
