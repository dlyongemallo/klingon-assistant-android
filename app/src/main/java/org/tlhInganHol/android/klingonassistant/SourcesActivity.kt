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

import android.os.Bundle
import android.preference.PreferenceManager
import android.view.View
import android.widget.TextView

/** Displays the sources page.  */
class SourcesActivity : BaseActivity() {
    // private static final String TAG = "SourcesActivity";
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setDrawerContentView(R.layout.sources)
        val resources = resources
        val entryTitle = findViewById<View>(R.id.entry_title) as TextView

        // Get the shared preferences.
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(baseContext)

        // Set the title.
        entryTitle.invalidate()
        if (Preferences.Companion.useKlingonUI(baseContext)
            && Preferences.Companion.useKlingonFont(
                baseContext
            )
        ) {
            // Klingon (in {pIqaD}).
            entryTitle.setTypeface(KlingonAssistant.Companion.getKlingonFontTypeface(baseContext))
            entryTitle.setText(
                KlingonContentProvider.Companion.convertStringToKlingonFont(
                    resources.getString(R.string.menu_sources)
                )
            )
        } else {
            entryTitle.text = resources.getString(R.string.menu_sources)
        }

        // TODO: Bold the names of sources.
    }
}
