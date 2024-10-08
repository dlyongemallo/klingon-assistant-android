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

package org.tlhInganHol.android.klingonassistant.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.preference.PreferenceManager;
import android.app.Notification;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;
import org.tlhInganHol.android.klingonassistant.EntryActivity;
import org.tlhInganHol.android.klingonassistant.KlingonAssistant;
import org.tlhInganHol.android.klingonassistant.KlingonContentDatabase;
import org.tlhInganHol.android.klingonassistant.KlingonContentProvider;
import org.tlhInganHol.android.klingonassistant.R;

public class KwotdService extends JobService {
  private static final String TAG = "KwotdService";

  // Key for storing the previously retrieved data from hol.kag.org.
  private static final String KEY_KWORD_DATA = "kwotd_data";

  // Key for indicating whether this is a "one-off" job (i.e., a job triggered
  // from the menu rather than a scheduled job). If set to true, the saved
  // value of the previously retrieved data will be ignored and the newly
  // fetched data will always be used. (The previous value is used for the
  // scheduled job to prevent showing the same KWOTD twice, if the server
  // sends the same data it sent on the previous scheduled occasion.)
  public static final String KEY_IS_ONE_OFF_JOB = "restart_kwotd_job";

  // Pattern to extract the RSS.
  private static final Pattern KWOTD_RSS_PATTERN =
      Pattern.compile("Klingon word: (.*)\\nPart of speech: (.*)\\nDefinition: (.*)\\n");

  // Save the parameters of the job.
  private JobParameters mParams = null;

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(TAG, "KwotdService created");
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.d(TAG, "KwotdService destroyed");
  }

  @Override
  public boolean onStartJob(final JobParameters params) {
    mParams = params;

    // Start an async task to fetch the KWOTD.
    new KwotdTask().execute();

    Log.d(TAG, "on start job: " + params.getJobId());

    // Return true to hold the wake lock. This is released by the async task.
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    Log.d(TAG, "on stop job: " + params.getJobId());

    // Return false to drop the job.
    return false;
  }

  private class KwotdTask extends AsyncTask<Void, Void, Void> {
    // URL from which to fetch KWOTD RSS.
    private static final String KWOTD_RSS_URL = "https://hol.kag.org/kwotd.rss";

    // URL from which to fetch KWOTD JSON.
    private static final String KWOTD_JSON_URL = "https://hol.kag.org/alexa.php?KWOTD=1";

    // Arbitrary limit on max buffer length to prevent overflows and such.
    private static final int MAX_BUFFER_LENGTH = 1024;

    // Notification needs a unique ID.
    private static final int NOTIFICATION_ID = 0;

    // Identifier for the KWOTD notification channel.
    private static final String NOTIFICATION_CHANNEL_ID = "kwotd_channel_id";

    // Set to true to use the "Alexa" JSON feed, otherwise use the RSS feed.
    private static final boolean USE_JSON = true;

    // The name of {Hol 'ampaS}.
    private static final String KAG_LANGUAGE_ACADEMY_NAME = "Hol 'ampaS";

    @Override
    protected Void doInBackground(Void... params) {
      Resources resources = KwotdService.this.getResources();
      boolean isOneOffJob = mParams.getExtras().getBoolean(KEY_IS_ONE_OFF_JOB);
      String kwotdData = null;
      if (!isOneOffJob) {
        // If this is not a one-off job, then retrieve the previously fetched
        // data for comparison to the newly fetched data.
        SharedPreferences sharedPrefs =
            PreferenceManager.getDefaultSharedPreferences(KwotdService.this);
        kwotdData = sharedPrefs.getString(KEY_KWORD_DATA, /* default */ null);
      }

      // Set to false if job runs successfully to completion.
      boolean rescheduleJob = true;

      String url;
      if (USE_JSON) {
        url = KWOTD_JSON_URL;
      } else {
        url = KWOTD_RSS_URL;
      }
      try (BufferedReader bufferedReader =
          new BufferedReader(
              new InputStreamReader(new URL(url).openConnection().getInputStream())); ) {
        StringBuffer sb = new StringBuffer();
        String line;
        while ((line = bufferedReader.readLine()) != null && sb.length() < MAX_BUFFER_LENGTH) {
          sb.append(line);
          sb.append('\n');
        }
        String data = sb.toString();

        // Strip newlines when comparing and saving the data, to work around a bug in Android:
        // https://issuetracker.google.com/issues/37032278
        if (kwotdData != null && kwotdData.equals(data.replaceAll("\n", ""))) {
          // No new data yet. Note that the finally block will run with rescheduleJob set to true.
          // Log.d(TAG, "No new KWOTD data, existing data is: " + data);
          Log.d(TAG, "No new KWOTD data.");
          return null;
        } else {
          // Save the data.
          // Log.d(TAG, "Saving KWOTD data: " + data);
          Log.d(TAG, "Saving KWOTD data.");
          SharedPreferences.Editor sharedPrefsEd =
              PreferenceManager.getDefaultSharedPreferences(KwotdService.this).edit();
          sharedPrefsEd.putString(KEY_KWORD_DATA, data.replaceAll("\n", ""));
          sharedPrefsEd.apply();
        }

        // Extract relevant data.
        String kword, type, eword;
        if (USE_JSON) {
          JSONObject object = new JSONObject(data);
          // Log.d(TAG, object.toString());

          // Note that JSONException is thrown if a mapping doesn't exist.
          kword = object.getString("kword").trim();
          type = object.getString("type").trim();
          eword = object.getString("eword").trim();
        } else {
          // Log.d(TAG, data);
          Matcher m = KWOTD_RSS_PATTERN.matcher(data);
          if (m.find()) {
            kword = m.group(1).trim();
            type = m.group(2).trim();
            eword = m.group(3).trim();
          } else {
            throw new IOException("Failed to extract data from RSS: " + sb.toString());
          }
        }

        // Make a query based on the data to pass to the database.
        // Convert KWOTD part of speech to annotation used by our database.
        String query = kword;
        if (type.equals("verb") || type.equals("v")) {
          query += ":v";
        } else if (type.equals("noun") || type.equals("n")) {
          query += ":n";
        } else if (type.equals("name")) {
          query += ":n:name";
        } else if (type.equals("num")) {
          query += ":n:num";
        } else if (type.equals("pro")) {
          query += ":n:pro";
        } else if (type.equals("adv")) {
          query += ":adv";
        } else if (type.equals("conj")) {
          query += ":conj";
        } else if (type.equals("ques")) {
          query += ":ques";
        } else if (type.equals("excl")) {
          query += ":excl";
        }

        // Query the database.
        Cursor cursor =
            getContentResolver()
                .query(
                    Uri.parse(KlingonContentProvider.CONTENT_URI + "/lookup"),
                    null /* all columns */,
                    null,
                    new String[] {query},
                    null);

        // Multiple matches were returned, pick the best match.
        if (cursor.getCount() > 1) {
          boolean matched = false;
          for (int i = 0; i < cursor.getCount(); i++) {
            cursor.moveToPosition(i);
            KlingonContentProvider.Entry entry =
                new KlingonContentProvider.Entry(cursor, KwotdService.this);
            // Compare the (English) definition to the KWOTD definition. Ideally, should really
            // compare the smallest edit (Levenshtein) distance or something like that.
            if (entry.getDefinition().equals(eword)) {
              matched = true;
              break;
            }
          }
          if (!matched) {
            // No match, return the first one.
            cursor.moveToFirst();
          }
        }

        Intent entryIntent;
        KlingonContentProvider.Entry entry;
        if (cursor.getCount() != 0) {
          // Found a match in the database.
          Uri uri =
              Uri.parse(
                  KlingonContentProvider.CONTENT_URI
                      + "/get_entry_by_id/"
                      + cursor.getString(KlingonContentDatabase.COLUMN_ID));
          entryIntent = new Intent(KwotdService.this, EntryActivity.class);
          entryIntent.setAction(Intent.ACTION_VIEW);
          entryIntent.setData(uri);

          entry = new KlingonContentProvider.Entry(cursor, KwotdService.this);
        } else {
          // No match found in the database. Treat the KWOTD as a search, but make a fake
          // entry to format the entry name and definition.
          entryIntent = new Intent(KwotdService.this, KlingonAssistant.class);
          entryIntent.setAction(Intent.ACTION_SEARCH);
          entryIntent.putExtra(SearchManager.QUERY, kword);

          entry = new KlingonContentProvider.Entry(query, eword, KwotdService.this);
        }
        String formattedEntryName =
            formattedEntryName = entry.getFormattedEntryName(/* html */ true);
        String formattedDefinition =
            formattedDefinition = entry.getFormattedDefinition(/* html */ true);

        // Create a notification.
        SpannableStringBuilder notificationTitle =
            new SpannableStringBuilder(Html.fromHtml(formattedEntryName));
        notificationTitle.setSpan(
            new StyleSpan(android.graphics.Typeface.BOLD),
            0,
            notificationTitle.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | Spanned.SPAN_INTERMEDIATE);
        notificationTitle.setSpan(
            new TypefaceSpan("serif"),
            0,
            notificationTitle.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        SpannableStringBuilder notificationText =
            new SpannableStringBuilder(Html.fromHtml(formattedDefinition));
        SpannableStringBuilder notificationTextLong =
            new SpannableStringBuilder(
                Html.fromHtml(
                    formattedDefinition
                        + "<br/><br/>"
                        + resources.getString(R.string.kwotd_footer)));

        int loc = notificationTextLong.toString().indexOf(KAG_LANGUAGE_ACADEMY_NAME);
        if (loc != -1) {
          // Note that this is already bolded in the xml, so just need to apply the serif.
          notificationTextLong.setSpan(
              new TypefaceSpan("serif"),
              loc,
              loc + KAG_LANGUAGE_ACADEMY_NAME.length(),
              Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        Notification.Builder builder =
            new Notification.Builder(KwotdService.this)
                .setSmallIcon(R.drawable.ic_kwotd_notification)
                .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_ka))
                .setContentTitle(notificationTitle)
                .setContentText(notificationText)
                .setStyle(new Notification.BigTextStyle().bigText(notificationTextLong))
                // Show on lock screen.
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true);
        PendingIntent pendingIntent =
            PendingIntent.getActivity(
                KwotdService.this,
                0,
                entryIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setContentIntent(pendingIntent);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // A notification channel is both needed and only supported on Android 8.0 (API 26) and
        // up.
        String notificationChannelName =
            resources.getString(R.string.kwotd_notification_channel_name);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          NotificationChannel channel =
              new NotificationChannel(
                  NOTIFICATION_CHANNEL_ID,
                  notificationChannelName,
                  NotificationManager.IMPORTANCE_LOW);
          channel.enableLights(true);
          channel.setLightColor(Color.RED);
          builder = builder.setChannelId(NOTIFICATION_CHANNEL_ID);
          manager.createNotificationChannel(channel);
        }
        manager.notify(NOTIFICATION_ID, builder.build());

        // Success, so no need to reschedule.
        rescheduleJob = false;
      } catch (Exception e) {
        Log.e(TAG, "Failed to read KWOTD from KAG server.", e);
      } finally {
        // Release the wakelock, and indicate whether rescheduling the job is needed.
        Log.d(TAG, "jobFinished called with rescheduleJob: " + rescheduleJob);

        KwotdService.this.jobFinished(mParams, rescheduleJob);
      }

      return null;
    }
  }
}
