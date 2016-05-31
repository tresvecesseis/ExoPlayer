package com.google.android.exoplayer.text;

public class DvbSubsRendererState {
  public int displayWidth;
  public int displayHeight;
  public int displayHorizontal;
  public int displayVertical;
  public long maxPersistenceTime;
  public int bitmapPainted;

  public DvbSubsRendererState () {
    displayWidth = -1;
    displayHeight = -1;
    displayHorizontal = -1;
    displayVertical = -1;
    maxPersistenceTime = 5000000;
    bitmapPainted = -1;
  }
}
