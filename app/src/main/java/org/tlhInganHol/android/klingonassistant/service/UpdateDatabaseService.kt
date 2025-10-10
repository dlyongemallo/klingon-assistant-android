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

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.AsyncTask
import android.preference.PreferenceManager
import android.util.Log
import org.json.JSONObject
import org.tlhInganHol.android.klingonassistant.KlingonContentDatabase
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

class UpdateDatabaseService : JobService() {

    // Save the parameters of the job.
    private var mParams: JobParameters? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "UpdateDatabaseService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "UpdateDatabaseService destroyed")
    }

    override fun onStartJob(params: JobParameters): Boolean {
        mParams = params

        // Start an async task to fetch the KWOTD.
        // TODO: Replace AsyncTask with coroutines in future refactoring
        @Suppress("DEPRECATION")
        UpdateDatabaseTask().execute()

        Log.d(TAG, "on start job: ${params.jobId}")

        // Return true to hold the wake lock. This is released by the async task.
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Log.d(TAG, "on stop job: ${params.jobId}")

        // Return false to drop the job.
        return false
    }

    @Suppress("DEPRECATION")
    private inner class UpdateDatabaseTask : AsyncTask<Void, Void, Void>() {

        override fun doInBackground(vararg params: Void?): Void? {
            val resources = this@UpdateDatabaseService.resources
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this@UpdateDatabaseService)

            // Set to false if job runs successfully to completion.
            var rescheduleJob = true

            try {
                BufferedReader(
                    InputStreamReader(URL(MANIFEST_JSON_URL).openConnection().getInputStream())
                ).use { bufferedReader ->
                    val sb = StringBuffer()
                    var line: String?
                    while (bufferedReader.readLine().also { line = it } != null && sb.length < MAX_BUFFER_LENGTH) {
                        sb.append(line)
                        sb.append('\n')
                    }
                    val data = sb.toString()
                    val manifestObject = JSONObject(data)
                    val androidObject = manifestObject.getJSONObject("Android-5")
                    val latest = androidObject.getString("latest")
                    Log.d(TAG, "Latest database version: $latest")

                    val installedVersion = sharedPrefs.getString(
                        KlingonContentDatabase.KEY_INSTALLED_DATABASE_VERSION,
                        /* default */ KlingonContentDatabase.getBundledDatabaseVersion()
                    ) ?: KlingonContentDatabase.getBundledDatabaseVersion()

                    val updatedVersion = sharedPrefs.getString(
                        KlingonContentDatabase.KEY_UPDATED_DATABASE_VERSION,
                        /* default */ installedVersion
                    ) ?: installedVersion

                    // Only download the database if the latest version is lexicographically greater than the
                    // installed one and it hasn't already been downloaded.
                    if (latest.compareTo(updatedVersion, ignoreCase = true) > 0) {
                        val latestObject = androidObject.getJSONObject(latest)

                        // Get the metadata for the latest database for Android.
                        val databaseZipUrl = ONLINE_UPGRADE_PATH + latestObject.getString("path")
                        val firstExtraEntryId = latestObject.getInt("extra")
                        Log.d(TAG, "Database zip URL: $databaseZipUrl")
                        Log.d(TAG, "Id of first extra entry: $firstExtraEntryId")
                        copyDBFromZipUrl(databaseZipUrl)

                        // Save the new version and first extra entry ID.
                        val sharedPrefsEd = PreferenceManager.getDefaultSharedPreferences(this@UpdateDatabaseService).edit()
                        sharedPrefsEd.putString(KlingonContentDatabase.KEY_UPDATED_DATABASE_VERSION, latest)
                        sharedPrefsEd.putInt(
                            KlingonContentDatabase.KEY_UPDATED_ID_OF_FIRST_EXTRA_ENTRY,
                            firstExtraEntryId
                        )
                        sharedPrefsEd.apply()
                    }

                    // Success, so no need to reschedule.
                    rescheduleJob = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update database from server.", e)
            } finally {
                // Release the wakelock, and indicate whether rescheduling the job is needed.
                Log.d(TAG, "jobFinished called with rescheduleJob: $rescheduleJob")
                mParams?.let { this@UpdateDatabaseService.jobFinished(it, rescheduleJob) }
            }

            return null
        }

        @Throws(IOException::class)
        private fun copyDBFromZipUrl(databaseZipUrl: String) {
            // Read the database from a zip file online.
            val urlConnection = URL(databaseZipUrl).openConnection()
            urlConnection.setRequestProperty("Accept-Encoding", "gzip")
            val inStream = if (urlConnection.contentEncoding == "gzip") {
                ZipInputStream(GZIPInputStream(urlConnection.getInputStream()))
            } else {
                ZipInputStream(urlConnection.getInputStream())
            }

            // Write to the replacement database.
            val fullReplacementDBPath = this@UpdateDatabaseService
                .getDatabasePath(KlingonContentDatabase.REPLACEMENT_DATABASE_NAME)
                .absolutePath
            Log.d(TAG, "fullReplacementDBPath: $fullReplacementDBPath")
            val outStream = FileOutputStream(fullReplacementDBPath)

            // Transfer the database from the resources to the system path one block at a time.
            val buffer = ByteArray(MAX_BUFFER_LENGTH)
            var length: Int
            var total = 0
            inStream.nextEntry
            while (inStream.read(buffer).also { length = it } > 0) {
                outStream.write(buffer, 0, length)
                total += length
            }
            Log.d(TAG, "Copied database from $databaseZipUrl, $total bytes written.")

            // Close the streams.
            outStream.flush()
            outStream.close()
            inStream.closeEntry()
            inStream.close()
        }

    }

    companion object {
        private const val TAG = "UpdateDatabaseService"

        // Online database upgrade URL.
        private const val ONLINE_UPGRADE_PATH = "https://De7vID.github.io/qawHaq/"
        private const val MANIFEST_JSON_URL = "$ONLINE_UPGRADE_PATH/manifest.json"

        // Arbitrary limit on max buffer length to prevent overflows and such.
        private const val MAX_BUFFER_LENGTH = 1024
    }
}
