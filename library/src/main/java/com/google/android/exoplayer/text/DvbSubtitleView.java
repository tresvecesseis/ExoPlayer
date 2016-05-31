package com.google.android.exoplayer.text;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;

import com.google.android.exoplayer.util.Util;

public class DvbSubtitleView extends View {

  private Paint paint;
  private Bitmap dvbSubtitlesBitmap;
  private int width, height;
  private float videoAspectRatio;

  private static final float MAX_ASPECT_RATIO_DEFORMATION_PERCENT = 0.01f;

  public DvbSubtitleView(Context context, AttributeSet attrs, Bitmap bitmap) {
    super(context, attrs);
    paint = new Paint();
    paint.setAntiAlias(true);
    dvbSubtitlesBitmap = bitmap;
  }

  public DvbSubtitleView(Context context) {
    this(context, null);
  }

  public DvbSubtitleView(Context context, AttributeSet attrs) {
    this(context, attrs, null);
  }

  public void setDvbSubtitlesBitmap(Bitmap bitmap) {
    dvbSubtitlesBitmap = bitmap;
  }

  @Override
  protected void onDraw(Canvas c) {
    width = getWidth();
    height = getHeight();
    int h = (int)((float)width*9/16 + 0.5);

    if (dvbSubtitlesBitmap != null) {
      Rect src = new Rect(0, 0, dvbSubtitlesBitmap.getWidth(), dvbSubtitlesBitmap.getHeight());
      RectF dst = new RectF(0, (height -h)/2, width, (height -h)/2 + h);
      c.drawBitmap(dvbSubtitlesBitmap, src, dst, paint);
    }
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    int width = getMeasuredWidth();
    int height = getMeasuredHeight();
    if (videoAspectRatio != 0) {
      float viewAspectRatio = (float) width / height;
      float aspectDeformation = videoAspectRatio / viewAspectRatio - 1;
      if (aspectDeformation > MAX_ASPECT_RATIO_DEFORMATION_PERCENT) {
        height = (int) (width / videoAspectRatio);
      } else if (aspectDeformation < -MAX_ASPECT_RATIO_DEFORMATION_PERCENT) {
        width = (int) (height * videoAspectRatio);
      }
    }
    setMeasuredDimension(width, height);
  }
}