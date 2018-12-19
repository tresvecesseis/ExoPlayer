/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.google.android.exoplayer2.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.Nullable;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.offline.ActionFile;
import com.google.android.exoplayer2.offline.DownloadAction;
import com.google.android.exoplayer2.offline.DownloadHelper;
import com.google.android.exoplayer2.offline.DownloadManager;
import com.google.android.exoplayer2.offline.DownloadManager.DownloadState;
import com.google.android.exoplayer2.offline.DownloadService;
import com.google.android.exoplayer2.offline.ProgressiveDownloadHelper;
import com.google.android.exoplayer2.offline.StreamKey;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.offline.DashDownloadHelper;
import com.google.android.exoplayer2.source.hls.offline.HlsDownloadHelper;
import com.google.android.exoplayer2.source.smoothstreaming.offline.SsDownloadHelper;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.ui.DefaultTrackNameProvider;
import com.google.android.exoplayer2.ui.TrackNameProvider;
import com.google.android.exoplayer2.ui.TrackSelectionView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.Util;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Tracks media that has been downloaded.
 *
 * <p>Tracked downloads are persisted using an {@link ActionFile}, however in a real application
 * it's expected that state will be stored directly in the application's media database, so that it
 * can be queried efficiently together with other information about the media.
 */
public class DownloadTracker implements DownloadManager.Listener {

  /** Listens for changes in the tracked downloads. */
  public interface Listener {

    /** Called when the tracked downloads changed. */
    void onDownloadsChanged();
  }

  private static final String TAG = "DownloadTracker";

  private final Context context;
  private final DataSource.Factory dataSourceFactory;
  private final TrackNameProvider trackNameProvider;
  private final CopyOnWriteArraySet<Listener> listeners;
  private final HashMap<Uri, DownloadAction> trackedDownloadStates;
  private final ActionFile actionFile;
  private final Handler actionFileWriteHandler;

  public DownloadTracker(Context context, DataSource.Factory dataSourceFactory, File actionFile) {
    this.context = context.getApplicationContext();
    this.dataSourceFactory = dataSourceFactory;
    this.actionFile = new ActionFile(actionFile);
    trackNameProvider = new DefaultTrackNameProvider(context.getResources());
    listeners = new CopyOnWriteArraySet<>();
    trackedDownloadStates = new HashMap<>();
    HandlerThread actionFileWriteThread = new HandlerThread("DownloadTracker");
    actionFileWriteThread.start();
    actionFileWriteHandler = new Handler(actionFileWriteThread.getLooper());
    loadTrackedActions();
  }

  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  public boolean isDownloaded(Uri uri) {
    return trackedDownloadStates.containsKey(uri);
  }

  @SuppressWarnings("unchecked")
  public List<StreamKey> getOfflineStreamKeys(Uri uri) {
    if (!trackedDownloadStates.containsKey(uri)) {
      return Collections.emptyList();
    }
    return trackedDownloadStates.get(uri).getKeys();
  }

  public void toggleDownload(
      Activity activity,
      String name,
      Uri uri,
      String extension,
      RenderersFactory renderersFactory) {
    if (isDownloaded(uri)) {
      DownloadAction removeAction =
          getDownloadHelper(uri, extension, renderersFactory).getRemoveAction();
      startServiceWithAction(removeAction);
    } else {
      new StartDownloadDialogHelper(
          activity, getDownloadHelper(uri, extension, renderersFactory), name);
    }
  }

  // DownloadManager.Listener

  @Override
  public void onInitialized(DownloadManager downloadManager) {
    // Do nothing.
  }

  @Override
  public void onDownloadStateChanged(DownloadManager downloadManager, DownloadState downloadState) {
    DownloadAction action = downloadState.action;
    Uri uri = action.uri;
    if ((action.isRemoveAction && downloadState.state == DownloadState.STATE_COMPLETED)
        || (!action.isRemoveAction && downloadState.state == DownloadState.STATE_FAILED)) {
      // A download has been removed, or has failed. Stop tracking it.
      if (trackedDownloadStates.remove(uri) != null) {
        handleTrackedDownloadStatesChanged();
      }
    }
  }

  @Override
  public void onIdle(DownloadManager downloadManager) {
    // Do nothing.
  }

  // Internal methods

  private void loadTrackedActions() {
    try {
      DownloadAction[] allActions = actionFile.load();
      for (DownloadAction action : allActions) {
        trackedDownloadStates.put(action.uri, action);
      }
    } catch (IOException e) {
      Log.e(TAG, "Failed to load tracked actions", e);
    }
  }

  private void handleTrackedDownloadStatesChanged() {
    for (Listener listener : listeners) {
      listener.onDownloadsChanged();
    }
    final DownloadAction[] actions = trackedDownloadStates.values().toArray(new DownloadAction[0]);
    actionFileWriteHandler.post(
        () -> {
          try {
            actionFile.store(actions);
          } catch (IOException e) {
            Log.e(TAG, "Failed to store tracked actions", e);
          }
        });
  }

  private void startDownload(DownloadAction action) {
    if (trackedDownloadStates.containsKey(action.uri)) {
      // This content is already being downloaded. Do nothing.
      return;
    }
    trackedDownloadStates.put(action.uri, action);
    handleTrackedDownloadStatesChanged();
    startServiceWithAction(action);
  }

  private void startServiceWithAction(DownloadAction action) {
    DownloadService.startWithAction(context, DemoDownloadService.class, action, false);
  }

  private DownloadHelper<?> getDownloadHelper(
      Uri uri, String extension, RenderersFactory renderersFactory) {
    int type = Util.inferContentType(uri, extension);
    switch (type) {
      case C.TYPE_DASH:
        return new DashDownloadHelper(uri, dataSourceFactory, renderersFactory);
      case C.TYPE_SS:
        return new SsDownloadHelper(uri, dataSourceFactory, renderersFactory);
      case C.TYPE_HLS:
        return new HlsDownloadHelper(uri, dataSourceFactory, renderersFactory);
      case C.TYPE_OTHER:
        return new ProgressiveDownloadHelper(uri);
      default:
        throw new IllegalStateException("Unsupported type: " + type);
    }
  }

  @SuppressWarnings("UngroupedOverloads")
  private final class StartDownloadDialogHelper
      implements DownloadHelper.Callback,
          DialogInterface.OnClickListener,
          View.OnClickListener,
          TrackSelectionView.DialogCallback {

    private final DownloadHelper<?> downloadHelper;
    private final String name;
    private final LayoutInflater dialogInflater;
    private final AlertDialog dialog;
    private final LinearLayout selectionList;

    private MappedTrackInfo mappedTrackInfo;
    private DefaultTrackSelector.Parameters parameters;

    private StartDownloadDialogHelper(
        Activity activity, DownloadHelper<?> downloadHelper, String name) {
      this.downloadHelper = downloadHelper;
      this.name = name;
      AlertDialog.Builder builder =
          new AlertDialog.Builder(activity)
              .setTitle(R.string.download_preparing)
              .setPositiveButton(android.R.string.ok, this)
              .setNegativeButton(android.R.string.cancel, null);

      // Inflate with the builder's context to ensure the correct style is used.
      dialogInflater = LayoutInflater.from(builder.getContext());
      selectionList = (LinearLayout) dialogInflater.inflate(R.layout.start_download_dialog, null);
      builder.setView(selectionList);
      dialog = builder.create();
      dialog.show();
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);

      parameters = DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS;
      downloadHelper.prepare(this);
    }

    // DownloadHelper.Callback implementation.

    @Override
    public void onPrepared(DownloadHelper<?> helper) {
      if (helper.getPeriodCount() < 1) {
        onPrepareError(downloadHelper, new IOException("Content is empty."));
        return;
      }
      mappedTrackInfo = downloadHelper.getMappedTrackInfo(/* periodIndex= */ 0);
      updateSelectionList();
      dialog.setTitle(R.string.exo_download_description);
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
    }

    @Override
    public void onPrepareError(DownloadHelper<?> helper, IOException e) {
      Toast.makeText(
              context.getApplicationContext(), R.string.download_start_error, Toast.LENGTH_LONG)
          .show();
      Log.e(TAG, "Failed to start download", e);
      dialog.cancel();
    }

    // View.OnClickListener implementation.

    @Override
    public void onClick(View v) {
      Integer rendererIndex = (Integer) v.getTag();
      String dialogTitle = getTrackTypeString(mappedTrackInfo.getRendererType(rendererIndex));
      Pair<AlertDialog, TrackSelectionView> dialogPair =
          TrackSelectionView.getDialog(
              dialog.getContext(),
              dialogTitle,
              mappedTrackInfo,
              rendererIndex,
              parameters,
              /* callback= */ this);
      dialogPair.second.setShowDisableOption(true);
      dialogPair.second.setAllowAdaptiveSelections(false);
      dialogPair.first.show();
    }

    // TrackSelectionView.DialogCallback implementation.

    @Override
    public void onTracksSelected(DefaultTrackSelector.Parameters parameters) {
      for (int i = 0; i < downloadHelper.getPeriodCount(); i++) {
        downloadHelper.replaceTrackSelections(/* periodIndex= */ i, parameters);
      }
      this.parameters = parameters;
      updateSelectionList();
    }

    // DialogInterface.OnClickListener implementation.

    @Override
    public void onClick(DialogInterface dialog, int which) {
      DownloadAction downloadAction = downloadHelper.getDownloadAction(Util.getUtf8Bytes(name));
      startDownload(downloadAction);
    }

    // Internal methods.

    private void updateSelectionList() {
      selectionList.removeAllViews();
      for (int i = 0; i < mappedTrackInfo.getRendererCount(); i++) {
        TrackGroupArray trackGroupArray = mappedTrackInfo.getTrackGroups(i);
        if (trackGroupArray.length == 0) {
          continue;
        }
        String trackTypeString =
            getTrackTypeString(mappedTrackInfo.getRendererType(/* rendererIndex= */ i));
        if (trackTypeString == null) {
          return;
        }
        String trackSelectionsString = getTrackSelectionString(/* rendererIndex= */ i);
        View view = dialogInflater.inflate(R.layout.download_track_item, selectionList, false);
        TextView trackTitleView = view.findViewById(R.id.track_title);
        TextView trackDescView = view.findViewById(R.id.track_desc);
        ImageButton editButton = view.findViewById(R.id.edit_button);
        trackTitleView.setText(trackTypeString);
        trackDescView.setText(trackSelectionsString);
        editButton.setTag(i);
        editButton.setOnClickListener(this);
        selectionList.addView(view);
      }
    }

    private String getTrackSelectionString(int rendererIndex) {
      List<TrackSelection> trackSelections =
          downloadHelper.getTrackSelections(/* periodIndex= */ 0, rendererIndex);
      String selectedTracks = "";
      Resources resources = selectionList.getResources();
      for (int i = 0; i < trackSelections.size(); i++) {
        TrackSelection selection = trackSelections.get(i);
        for (int j = 0; j < selection.length(); j++) {
          String trackName = trackNameProvider.getTrackName(selection.getFormat(j));
          if (i == 0 && j == 0) {
            selectedTracks = trackName;
          } else {
            selectedTracks = resources.getString(R.string.exo_item_list, selectedTracks, trackName);
          }
        }
      }
      return selectedTracks.isEmpty()
          ? resources.getString(R.string.exo_track_selection_none)
          : selectedTracks;
    }

    @Nullable
    private String getTrackTypeString(int trackType) {
      Resources resources = selectionList.getResources();
      switch (trackType) {
        case C.TRACK_TYPE_VIDEO:
          return resources.getString(R.string.exo_track_selection_title_video);
        case C.TRACK_TYPE_AUDIO:
          return resources.getString(R.string.exo_track_selection_title_audio);
        case C.TRACK_TYPE_TEXT:
          return resources.getString(R.string.exo_track_selection_title_text);
        default:
          return null;
      }
    }
  }
}
