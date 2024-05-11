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
package org.tlhInganHol.android.klingonassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.preference.PreferenceManager
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Log
import org.json.JSONObject
import org.tlhInganHol.android.klingonassistant.EntryActivity
import org.tlhInganHol.android.klingonassistant.KlingonAssistant
import org.tlhInganHol.android.klingonassistant.KlingonContentDatabase
import org.tlhInganHol.android.klingonassistant.KlingonContentProvider
import org.tlhInganHol.android.klingonassistant.R
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import java.util.regex.Pattern

class KwotdService : JobService() {
    // Save the parameters of the job.
    private var mParams: JobParameters? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "KwotdService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "KwotdService destroyed")
    }

    override fun onStartJob(params: JobParameters): Boolean {
        mParams = params

        // Start an async task to fetch the KWOTD.
        KwotdTask().execute()

        Log.d(TAG, "on start job: " + params.jobId)

        // Return true to hold the wake lock. This is released by the async task.
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Log.d(TAG, "on stop job: " + params.jobId)

        // Return false to drop the job.
        return false
    }

    private inner class KwotdTask : AsyncTask<Void?, Void?, Void?>() {
        protected override fun doInBackground(vararg params: Void): Void? {
            val resources = this@KwotdService.resources
            val isOneOffJob = mParams!!.extras.getBoolean(KEY_IS_ONE_OFF_JOB)
            var kwotdData: String? = null
            if (!isOneOffJob) {
                // If this is not a one-off job, then retrieve the previously fetched
                // data for comparison to the newly fetched data.
                val sharedPrefs =
                    PreferenceManager.getDefaultSharedPreferences(this@KwotdService)
                kwotdData = sharedPrefs.getString(KEY_KWORD_DATA,  /* default */null)
            }

            // Set to false if job runs successfully to completion.
            var rescheduleJob = true
            val url = if (Companion.USE_JSON) {
                Companion.KWOTD_JSON_URL
            } else {
                Companion.KWOTD_RSS_URL
            }
            try {
                BufferedReader(
                    InputStreamReader(URL(url).openConnection().getInputStream())
                ).use { bufferedReader ->
                    val sb = StringBuffer()
                    var line: String?
                    while ((bufferedReader.readLine()
                            .also { line = it }) != null && sb.length < Companion.MAX_BUFFER_LENGTH
                    ) {
                        sb.append(line)
                        sb.append('\n')
                    }
                    val data = sb.toString()

                    // Strip newlines when comparing and saving the data, to work around a bug in Android:
                    // https://issuetracker.google.com/issues/37032278
                    if (kwotdData != null && kwotdData == data.replace("\n".toRegex(), "")) {
                        // No new data yet. Note that the finally block will run with rescheduleJob set to true.
                        // Log.d(TAG, "No new KWOTD data, existing data is: " + data);
                        Log.d(TAG, "No new KWOTD data.")
                        return null
                    } else {
                        // Save the data.
                        // Log.d(TAG, "Saving KWOTD data: " + data);
                        Log.d(TAG, "Saving KWOTD data.")
                        val sharedPrefsEd =
                            PreferenceManager.getDefaultSharedPreferences(this@KwotdService).edit()
                        sharedPrefsEd.putString(KEY_KWORD_DATA, data.replace("\n".toRegex(), ""))
                        sharedPrefsEd.apply()
                    }

                    // Extract relevant data.
                    val kword: String
                    val type: String
                    val eword: String
                    if (Companion.USE_JSON) {
                        val `object` = JSONObject(data)

                        // Log.d(TAG, object.toString());

                        // Note that JSONException is thrown if a mapping doesn't exist.
                        kword = `object`.getString("kword")
                        type = `object`.getString("type")
                        eword = `object`.getString("eword")
                    } else {
                        // Log.d(TAG, data);
                        val m = KWOTD_RSS_PATTERN.matcher(data)
                        if (m.find()) {
                            kword = m.group(1)
                            type = m.group(2)
                            eword = m.group(3)
                        } else {
                            throw IOException("Failed to extract data from RSS: $sb")
                        }
                    }

                    // Make a query based on the data to pass to the database.
                    // Convert KWOTD part of speech to annotation used by our database.
                    var query = kword
                    if (type == "verb" || type == "v") {
                        query += ":v"
                    } else if (type == "noun" || type == "n") {
                        query += ":n"
                    } else if (type == "name") {
                        query += ":n:name"
                    } else if (type == "num") {
                        query += ":n:num"
                    } else if (type == "pro") {
                        query += ":n:pro"
                    } else if (type == "adv") {
                        query += ":adv"
                    } else if (type == "conj") {
                        query += ":conj"
                    } else if (type == "ques") {
                        query += ":ques"
                    } else if (type == "excl") {
                        query += ":excl"
                    }

                    // Query the database.
                    val cursor =
                        contentResolver
                            .query(
                                Uri.parse(KlingonContentProvider.CONTENT_URI.toString() + "/lookup"),
                                null,  /* all columns */
                                null,
                                arrayOf(query),
                                null
                            )

                    // Multiple matches were returned, pick the best match.
                    if (cursor!!.count > 1) {
                        var matched = false
                        for (i in 0 until cursor.count) {
                            cursor.moveToPosition(i)
                            val entry =
                                KlingonContentProvider.Entry(cursor, this@KwotdService)
                            // Compare the (English) definition to the KWOTD definition. Ideally, should really
                            // compare the smallest edit (Levenshtein) distance or something like that.
                            if (entry.definition == eword) {
                                matched = true
                                break
                            }
                        }
                        if (!matched) {
                            // No match, return the first one.
                            cursor.moveToFirst()
                        }
                    }

                    val entryIntent: Intent
                    val entry: KlingonContentProvider.Entry
                    if (cursor.count != 0) {
                        // Found a match in the database.
                        val uri =
                            Uri.parse(
                                KlingonContentProvider.CONTENT_URI
                                    .toString() + "/get_entry_by_id/"
                                        + cursor.getString(KlingonContentDatabase.COLUMN_ID)
                            )
                        entryIntent = Intent(this@KwotdService, EntryActivity::class.java)
                        entryIntent.setAction(Intent.ACTION_VIEW)
                        entryIntent.setData(uri)

                        entry = KlingonContentProvider.Entry(cursor, this@KwotdService)
                    } else {
                        // No match found in the database. Treat the KWOTD as a search, but make a fake
                        // entry to format the entry name and definition.
                        entryIntent = Intent(this@KwotdService, KlingonAssistant::class.java)
                        entryIntent.setAction(Intent.ACTION_SEARCH)
                        entryIntent.putExtra(SearchManager.QUERY, kword)

                        entry = KlingonContentProvider.Entry(query, eword, this@KwotdService)
                    }
                    formattedEntryName = entry.getFormattedEntryName( /* html */true)
                    val formattedEntryName: String = formattedEntryName
                    formattedDefinition = entry.getFormattedDefinition( /* html */true)
                    val formattedDefinition: String = formattedDefinition

                    // Create a notification.
                    val notificationTitle =
                        SpannableStringBuilder(Html.fromHtml(formattedEntryName))
                    notificationTitle.setSpan(
                        StyleSpan(Typeface.BOLD),
                        0,
                        notificationTitle.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_INTERMEDIATE
                    )
                    notificationTitle.setSpan(
                        TypefaceSpan("serif"),
                        0,
                        notificationTitle.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    val notificationText =
                        SpannableStringBuilder(Html.fromHtml(formattedDefinition))
                    val notificationTextLong =
                        SpannableStringBuilder(
                            Html.fromHtml(
                                formattedDefinition
                                        + "<br/><br/>"
                                        + resources.getString(R.string.kwotd_footer)
                            )
                        )

                    val loc =
                        notificationTextLong.toString().indexOf(Companion.KAG_LANGUAGE_ACADEMY_NAME)
                    if (loc != -1) {
                        // Note that this is already bolded in the xml, so just need to apply the serif.
                        notificationTextLong.setSpan(
                            TypefaceSpan("serif"),
                            loc,
                            loc + Companion.KAG_LANGUAGE_ACADEMY_NAME.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }

                    var builder =
                        Notification.Builder(this@KwotdService)
                            .setSmallIcon(R.drawable.ic_kwotd_notification)
                            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_ka))
                            .setContentTitle(notificationTitle)
                            .setContentText(notificationText)
                            .setStyle(
                                Notification.BigTextStyle().bigText(notificationTextLong)
                            ) // Show on lock screen.
                            .setVisibility(Notification.VISIBILITY_PUBLIC)
                            .setAutoCancel(true)
                    val pendingIntent =
                        PendingIntent.getActivity(
                            this@KwotdService,
                            0,
                            entryIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    builder.setContentIntent(pendingIntent)
                    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

                    // A notification channel is both needed and only supported on Android 8.0 (API 26) and
                    // up.
                    val notificationChannelName =
                        resources.getString(R.string.kwotd_notification_channel_name)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val channel =
                            NotificationChannel(
                                Companion.NOTIFICATION_CHANNEL_ID,
                                notificationChannelName,
                                NotificationManager.IMPORTANCE_LOW
                            )
                        channel.enableLights(true)
                        channel.lightColor = Color.RED
                        builder = builder.setChannelId(Companion.NOTIFICATION_CHANNEL_ID)
                        manager.createNotificationChannel(channel)
                    }
                    manager.notify(Companion.NOTIFICATION_ID, builder.build())

                    // Success, so no need to reschedule.
                    rescheduleJob = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read KWOTD from KAG server.", e)
            } finally {
                // Release the wakelock, and indicate whether rescheduling the job is needed.
                Log.d(TAG, "jobFinished called with rescheduleJob: $rescheduleJob")

                this@KwotdService.jobFinished(mParams, rescheduleJob)
            }

            return null
        }

        companion object {
            // URL from which to fetch KWOTD RSS.
            private const val KWOTD_RSS_URL = "https://hol.kag.org/kwotd.rss"

            // URL from which to fetch KWOTD JSON.
            private const val KWOTD_JSON_URL = "https://hol.kag.org/alexa.php?KWOTD=1"

            // Arbitrary limit on max buffer length to prevent overflows and such.
            private const val MAX_BUFFER_LENGTH = 1024

            // Notification needs a unique ID.
            private const val NOTIFICATION_ID = 0

            // Identifier for the KWOTD notification channel.
            private const val NOTIFICATION_CHANNEL_ID = "kwotd_channel_id"

            // Set to true to use the "Alexa" JSON feed, otherwise use the RSS feed.
            private const val USE_JSON = true

            // The name of {Hol 'ampaS}.
            private const val KAG_LANGUAGE_ACADEMY_NAME = "Hol 'ampaS"
        }
    }

    companion object {
        private const val TAG = "KwotdService"

        // Key for storing the previously retrieved data from hol.kag.org.
        private const val KEY_KWORD_DATA = "kwotd_data"

        // Key for indicating whether this is a "one-off" job (i.e., a job triggered
        // from the menu rather than a scheduled job). If set to true, the saved
        // value of the previously retrieved data will be ignored and the newly
        // fetched data will always be used. (The previous value is used for the
        // scheduled job to prevent showing the same KWOTD twice, if the server
        // sends the same data it sent on the previous scheduled occasion.)
        const val KEY_IS_ONE_OFF_JOB: String = "restart_kwotd_job"

        // Pattern to extract the RSS.
        private val KWOTD_RSS_PATTERN: Pattern =
            Pattern.compile("Klingon word: (.*)\\nPart of speech: (.*)\\nDefinition: (.*)\\n")
    }
}
