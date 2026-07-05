/*
 * Copyright 2019 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.preference;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import com.hippo.ehviewer.BackgroundTaskManager;
import com.hippo.ehviewer.R;

public class ExportDataPreference extends Preference {

  public ExportDataPreference(Context context) {
    super(context);
  }

  public ExportDataPreference(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public ExportDataPreference(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @Override
  protected void onClick() {
    Context context = getContext();
    new AlertDialog.Builder(context)
        .setTitle(R.string.settings_advanced_export_data_dialog_title)
        .setItems(new String[] {
            context.getString(R.string.settings_advanced_export_db_option),
            context.getString(R.string.settings_advanced_export_csv_option),
            context.getString(R.string.settings_advanced_export_legacy_option)
        }, (dialog, which) -> {
          dialog.dismiss();
          switch (which) {
            case 0:
              exportDatabase(context);
              break;
            case 1:
              exportDownloadList(context);
              break;
            case 2:
              exportLegacyDataList(context);
              break;
          }
        })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void exportDatabase(Context context) {
    com.hippo.ehviewer.task.ExportDataTask task = new com.hippo.ehviewer.task.ExportDataTask(context);
    BackgroundTaskManager.getInstance().submitBackgroundTask(task);
    Toast.makeText(context, R.string.settings_advanced_export_data_started, Toast.LENGTH_SHORT).show();
  }

  private void exportDownloadList(Context context) {
    com.hippo.ehviewer.task.ExportDownloadItemsTask task = new com.hippo.ehviewer.task.ExportDownloadItemsTask(context);
    BackgroundTaskManager.getInstance().submitBackgroundTask(task);
    Toast.makeText(context, R.string.settings_advanced_export_data_started, Toast.LENGTH_SHORT).show();
  }

  private void exportLegacyDataList(Context context) {
    com.hippo.ehviewer.task.ExportLegacyDataTask task = new com.hippo.ehviewer.task.ExportLegacyDataTask(context);
    BackgroundTaskManager.getInstance().submitBackgroundTask(task);
    Toast.makeText(context, R.string.settings_advanced_export_legacy_started, Toast.LENGTH_SHORT).show();
  }
}
