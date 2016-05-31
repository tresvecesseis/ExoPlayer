package com.google.android.exoplayer.text.DVBSubs;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.MediaCodecUtil;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.MediaFormatHolder;
import com.google.android.exoplayer.SampleHolder;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.SampleSource.SampleSourceReader;
import com.google.android.exoplayer.SampleSourceTrackRenderer;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.text.DvbSubsRendererState;
import com.google.android.exoplayer.text.DvbTextRenderer;
import com.google.android.exoplayer.util.Assertions;
import com.google.android.exoplayer.util.MimeTypes;

import java.io.IOException;
import java.util.HashMap;

public class DvbSubtitlesTrackRenderer extends SampleSourceTrackRenderer implements Handler.Callback {
  private static final int MAX_SAMPLE_READAHEAD_US = 3000000;
  public static final int MSG_SET_DVB_SUBS_BITMAP = 1;
  private final int BITMAP_POOL = 4;

  private final String TAG = "DVBSubs";

  private final Handler textRendererHandler;
  private final SampleHolder sampleHolder;
  private final MediaFormatHolder formatHolder;
  private final DvbSubtitlesParser dvbSubtitlesParser;
  private DvbSubsRendererState state;
  private Paint paint;
  private Bitmap[] bitmap;
  private Canvas[] canvas;
  private long currentPositionUs;
  private DvbTextRenderer dvbTextRenderer;
  private int trackIndex;
  private boolean inputStreamEnded;
  private boolean newDvbBitmap;
  private int previousBitmap;
  private long subtitleCurrentPts;
  private long subtitlePtsTimeout;
  private int bitmapCleared;
  private int videoWidth, videoHeight;
  private HashMap<String, Bitmap> bitmapHashMap;
  private long[] ptsArray;
  private int ptsArrayIndex;
  private int ptsOldest; // index of the oldest pending subtitle
  private int currentBitmap;
  private boolean sourceIsReady;

  public DvbSubtitlesTrackRenderer(SampleSource source, DvbTextRenderer dvbTextRenderer, Looper looper) {
    super(source);
    this.dvbTextRenderer = Assertions.checkNotNull(dvbTextRenderer);
    textRendererHandler = looper == null ? null : new Handler(looper, this);
    //this.source = Assertions.checkNotNull(source);
    //this.renderer = Assertions.checkNotNull(renderer);
    sampleHolder = new SampleHolder(SampleHolder.BUFFER_REPLACEMENT_MODE_NORMAL);
    formatHolder = new MediaFormatHolder();
    paint = new Paint();
    videoWidth = 720;
    videoHeight = 576;
    bitmap = new Bitmap[BITMAP_POOL];
    canvas = new Canvas[BITMAP_POOL];
    for (int i = 0; i < BITMAP_POOL; i++) {
      bitmap[i] = null;
      bitmap[i] = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888);
      canvas[i] = null;
      canvas[i] = new Canvas(bitmap[i]);
    }
    dvbSubtitlesParser = new DvbSubtitlesParser(paint, bitmap[0]);
    newDvbBitmap = false;
    subtitlePtsTimeout = 5000000;
    bitmapCleared = 1;
    ptsArray = new long[BITMAP_POOL];
    ptsArrayIndex = 0;
    ptsOldest = 0;
    bitmapHashMap = new HashMap<String, Bitmap>(BITMAP_POOL);
    currentBitmap = 0;
  }

  @Override
  protected boolean handlesTrack(MediaFormat mediaFormat) {
    return dvbSubtitlesParser.canParse(mediaFormat.mimeType);
  }

  @Override
  protected void onEnabled(int track, long positionUs, boolean joining)
          throws ExoPlaybackException {
    super.onEnabled(track, positionUs, joining);
  }

  @Override
  protected void doSomeWork(long positionUs, long elapsedRealtimeUs, boolean sourceIsReady)
    throws ExoPlaybackException {
    currentPositionUs = positionUs;
    if (currentPositionUs - subtitleCurrentPts >= subtitlePtsTimeout && bitmapCleared == 0) {
      canvas[currentBitmap].drawColor(0, PorterDuff.Mode.CLEAR);
      bitmapCleared = 1;
      updateDvbSubs(bitmap[currentBitmap]);
    }
    if (isSamplePending()) {
      maybeParsePendingSample();
    }

    int result = inputStreamEnded ? SampleSource.END_OF_STREAM : SampleSource.SAMPLE_READ;
    while (!isSamplePending() && result == SampleSource.SAMPLE_READ) {
      result = readSource(positionUs, formatHolder, sampleHolder);
      if (result == SampleSource.SAMPLE_READ) {
        maybeParsePendingSample();
      } else if (result == SampleSource.END_OF_STREAM) {
        inputStreamEnded = true;
      }
    }
    if (newDvbBitmap || (ptsArray[ptsOldest] <= currentPositionUs &&
          bitmapHashMap.get(String.valueOf((ptsArray[ptsOldest]))) != null)) {
      //Log.d(TAG, "oldest " + ptsArray[ptsOldest] + " current " + currentPositionUs);
      if (ptsArray[ptsOldest] <= currentPositionUs) {
        updateDvbSubs(bitmapHashMap.get(String.valueOf(ptsArray[ptsOldest])));
        //Log.d(TAG, "painting bitmap " + bitmapHashMap.get(String.valueOf(ptsArray[ptsOldest])));
        subtitleCurrentPts = ptsArray[ptsOldest];
        bitmapCleared = 0;
        bitmapHashMap.remove(ptsArray[ptsOldest]); // free memory
        ptsArray[ptsOldest] = -1;
        ptsOldest = (ptsOldest + 1) % BITMAP_POOL;
        if (ptsArray[ptsOldest] == -1) {
          newDvbBitmap = false;
        }
      }
    }
    return;
  }

  @Override
  protected long getBufferedPositionUs() {
        return TrackRenderer.END_OF_TRACK_US;
    }

  @Override
  protected boolean isEnded() {
        return inputStreamEnded;
    }

  @Override
  protected boolean isReady() {
        return true;
    }

  private void updateDvbSubs(Bitmap bitmap) {
    if (textRendererHandler != null) {
      textRendererHandler.obtainMessage(MSG_SET_DVB_SUBS_BITMAP, bitmap).sendToTarget();
    } else {
      invokeRendererInternal(bitmap);
    }
  }

  private void invokeRendererInternal(Bitmap bitmap) {
    dvbTextRenderer.onDvbText(bitmap);
  }

  @Override
  public boolean handleMessage(Message msg) {
    switch (msg.what) {
      case MSG_SET_DVB_SUBS_BITMAP:
        invokeRendererInternal((Bitmap) msg.obj);
        return true;
      }
      return false;
  }

  private void clearPendingSample() {
    sampleHolder.timeUs = C.UNKNOWN_TIME_US;
    sampleHolder.clearData();
  }

  private boolean isSamplePending() {
    // wait until a complete subtitle (PES) has arrived
    int pesComplete = (sampleHolder.flags & C.SAMPLE_FLAG_SYNC);
    return ((pesComplete != 0) && (sampleHolder.timeUs != C.UNKNOWN_TIME_US));
  }

  private void maybeParsePendingSample() {
    int result = 0;
    //Log.d(TAG, "system time = " + currentPositionUs + " PES PTS = " + sampleHolder.timeUs);
    if (sampleHolder.timeUs > currentPositionUs + MAX_SAMPLE_READAHEAD_US) {
      // We're too early to parse the sample.
      newDvbBitmap = false;
      return;
    }
    // clear bitmap before painting a new subtitle chunk
    canvas[currentBitmap].drawColor(0, PorterDuff.Mode.CLEAR);
    state = dvbSubtitlesParser.parse(sampleHolder, bitmap[currentBitmap], canvas[currentBitmap]);
    result = state.bitmapPainted;
    if (result == DvbSubtitlesParser.BITMAP_PAINTED && sampleHolder.timeUs > ptsArray[previousBitmap ]
        && sampleHolder.timeUs != 0) {
      newDvbBitmap = true;
      bitmapCleared = 0;
      ptsArray[ptsArrayIndex] = sampleHolder.timeUs; // save pts
      //Log.d(TAG, "saving painted bitmap " + bitmap);
      bitmapHashMap.put(String.valueOf(ptsArray[ptsArrayIndex]), bitmap[currentBitmap]); // associate bitmap and pts
      previousBitmap = (BITMAP_POOL + currentBitmap -1)%BITMAP_POOL;
      currentBitmap = (currentBitmap + 1) % BITMAP_POOL; // next bitmap
      ptsArrayIndex = (ptsArrayIndex + 1) % BITMAP_POOL;  // save index for pts' array.
    } else {
      newDvbBitmap = false;
    }
    clearPendingSample();
  }

  public void onVideoSizeChanged(int width, int height, float pixelWidthHeightRatio) {
    videoWidth = width;
    videoHeight = height;
  }

  @Override
  protected void onDiscontinuity(long positionUs) {
    inputStreamEnded = false;
  }

  public void resetBitmapsSize() {
    for (int i = 0; i < BITMAP_POOL; i++) {
      bitmap[i] = null;
      bitmap[i] = Bitmap.createBitmap(720, 576, Bitmap.Config.ARGB_8888);
      canvas[i] = null;
      canvas[i] = new Canvas(bitmap[i]);
    }
  }
}
