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
package com.google.android.exoplayer2.source.iptv.nokia;


import android.net.Uri;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.source.iptv.IptvLiveChannelSource;
import com.google.android.exoplayer2.source.iptv.IptvMediaStreamBuffer;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.upstream.RtpDataSource;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.util.rtp.RtpEventListener;

import java.io.IOException;

import static com.google.android.exoplayer2.source.iptv.nokia.NokiaMediaService.STREAM_AUDIO_TYPE;
import static com.google.android.exoplayer2.source.iptv.nokia.NokiaMediaService.STREAM_VIDEO_TYPE;

public class NokiaLiveChannelSource implements IptvLiveChannelSource,
        Loader.Callback<NokiaLiveChannelSource.IptvStreamSourceLoadable>,
        Handler.Callback  {

    private final Handler mediaHandler;
    private final HandlerThread mediaThread;

    private final Loader loader;
    private IptvStreamSourceLoadable loadable;
    private IptvMediaStreamBuffer mediaStreamBuffer;

    private final ConditionVariable loadCondition;
    private final IptvLiveChannelSource.EventListener eventListener;

    private static final int LIVE_PACKET_CAPACITY = 2048;

    private boolean opened = false;

    public NokiaLiveChannelSource(IptvLiveChannelSource.EventListener eventListener) {

        this.eventListener = eventListener;

        loadCondition = new ConditionVariable();

        mediaThread = new HandlerThread("NokiaLiveChannelSource:Handler",
                Process.THREAD_PRIORITY_AUDIO);
        mediaThread.start();

        mediaHandler = new Handler(mediaThread.getLooper(), this);

        loader = new Loader("Loader:NokiaLiveChannelSource");
        mediaStreamBuffer = new IptvMediaStreamBuffer(LIVE_PACKET_CAPACITY, RtpDataSource.MTU_SIZE);
    }

    @Override
    public void open(Uri uri) throws IOException {

        if (!opened) {

            loadable = new IptvStreamSourceLoadable(Assertions.checkNotNull(uri),
                    loadCondition, eventListener);

            Runnable currentThreadTask = new Runnable() {
                @Override
                public void run() {
                    loader.startLoading(loadable, NokiaLiveChannelSource.this, 0);
                }
            };

            mediaHandler.post(currentThreadTask);

            opened = true;
        }
    }

    @Override
    public int read(byte[] data, int offset, int length) throws IOException {

        if (!mediaStreamBuffer.hasDataAvailable()) {
            loadCondition.block();
        }

        int size = mediaStreamBuffer.get(data, offset, length);

        loadCondition.close();

        return size;
    }

    @Override
    public void close() throws IOException {

        if (opened) {
            Runnable currentThreadTask = new Runnable() {
                @Override
                public void run() {
                    loader.release();
                }
            };

            mediaHandler.post(currentThreadTask);

            loadCondition.close();
            loadable.cancelLoad();

            opened = false;
        }
    }

    @Override
    public boolean isOpened() {
        return opened;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean handleMessage(Message msg) {
        return true;
    }

    // Loader.Callback implementation.

    @Override
    public void onLoadCompleted(IptvStreamSourceLoadable loadable, long elapsedRealtimeMs, long loadDurationMs) {
        // Do nothing
        loadCondition.open();
    }

    @Override
    public void onLoadCanceled(IptvStreamSourceLoadable loadable, long elapsedRealtimeMs, long loadDurationMs, boolean released) {
        eventListener.onLiveChannelLoadCanceled();
        loadCondition.open();
    }

    @Override
    public int onLoadError(IptvStreamSourceLoadable loadable, long elapsedRealtimeMs, long loadDurationMs, IOException error) {
        loadCondition.open();
        return Loader.RETRY;
    }

    @Override
    public boolean hasDataAvailable() {
        return mediaStreamBuffer.hasDataAvailable();
    }

    @Override
    public int getCurrentSequenceNumber() {

        /*if (!mediaStreamBuffer.hasDataAvailable()) {
            loadCondition.block();
        }*/

        return mediaStreamBuffer.getFirstSequenceNumber();
    }

    @Override
    public long getCurrentTimeStamp() {

        /*if (!mediaStreamBuffer.hasDataAvailable()) {
            loadCondition.block();
        }*/

        return mediaStreamBuffer.getFirstTimeStamp();
    }

    public class IptvStreamSourceLoadable
            implements Loader.Loadable, RtpEventListener {

        private final Uri uri;
        private final DataSource dataSource;
        private final ConditionVariable loadCondition;
        private final IptvLiveChannelSource.EventListener eventListener;

        private int streamType;
        private int sequenceNumber;
        private byte[] buffer;

        private long ssrc;
        private volatile boolean loadCanceled = false;

        public IptvStreamSourceLoadable(Uri uri, ConditionVariable loadCondition,
                                        IptvLiveChannelSource.EventListener eventListener) {
            this.uri = Assertions.checkNotNull(uri);
            this.loadCondition = loadCondition;
            this.eventListener = eventListener;

            this.dataSource = new RtpDataSource(null, this);
            this.buffer = new byte[RtpDataSource.MTU_SIZE];
            this.ssrc = -1;
        }

        @Override
        public void onRtpMediaStreamInfoRefresh(int payloadType, int sequenceNumber, long timestamp,
                                                long ssrc, byte[] extension) {
            this.sequenceNumber = sequenceNumber;
            this.streamType = ((extension[5] & 0x3f) >> 4) == 1 ? STREAM_AUDIO_TYPE :
                    STREAM_VIDEO_TYPE;

            if (this.ssrc == -1) {
                eventListener.onLiveChannelSynchronizationSource(ssrc);
                this.ssrc = ssrc;
            }

            eventListener.onLiveChannelStreamInfoRefresh(streamType, sequenceNumber);
        }

        @Override
        public final void cancelLoad() {
            loadCanceled = true;
        }

        @Override
        public final boolean isLoadCanceled() {
            return loadCanceled;
        }

        @Override
        public void load() throws IOException, InterruptedException {

            int length;

            try {

                dataSource.open(new DataSpec(uri, 0, C.LENGTH_UNSET, null));

                while (!loadCanceled && ((length = dataSource.read(buffer, 0, RtpDataSource.MTU_SIZE))
                        != C.RESULT_END_OF_INPUT)) {

                    try {

                        mediaStreamBuffer.put(sequenceNumber, buffer, length);

                        loadCondition.open();

                    } catch (Exception ex) {
                        throw new IOException();
                    }
                }

            } finally {

                Util.closeQuietly(dataSource);
            }
        }
    }
}
