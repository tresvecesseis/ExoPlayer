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

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.source.iptv.IptvFastChannelChangeSource;
import com.google.android.exoplayer2.source.iptv.IptvMediaStreamBuffer;
import com.google.android.exoplayer2.upstream.DataSinkSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.upstream.RtpDataSource;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.util.net.NetworkUtils;
import com.google.android.exoplayer2.util.rtp.RtcpPacketBuilder;
import com.google.android.exoplayer2.util.rtp.RtcpPacketUtils;
import com.google.android.exoplayer2.util.rtp.RtcpSessionUtils;
import com.google.android.exoplayer2.util.rtp.RtpEventListener;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static com.google.android.exoplayer2.source.iptv.nokia.NokiaMediaService.STREAM_AUDIO_TYPE;
import static com.google.android.exoplayer2.source.iptv.nokia.NokiaMediaService.STREAM_VIDEO_TYPE;

public class NokiaFastChannelChangeSource implements IptvFastChannelChangeSource,
        Loader.Callback<NokiaFastChannelChangeSource.IptvStreamSourceLoadable>,
        Handler.Callback  {

    private final Handler mediaHandler;
    private final HandlerThread mediaThread;

    private final Loader loader;
    private IptvStreamSourceLoadable loadable;
    private IptvMediaStreamBuffer mediaStreamBuffer;

    private final ConditionVariable loadCondition;
    private final IptvFastChannelChangeSource.EventListener eventListener;

    private final int DEAFULT_SOCKET_TIMEOUT_MILLIS = 200;

    private boolean opened = false;

    private static final int FCC_PACKET_CAPACITY = 1024;

    public NokiaFastChannelChangeSource(IptvFastChannelChangeSource.EventListener eventListener) {

        this.eventListener = eventListener;

        loadCondition = new ConditionVariable();

        mediaThread = new HandlerThread("NokiaFastChannelChangeSource:Handler",
                Process.THREAD_PRIORITY_AUDIO);
        mediaThread.start();

        mediaHandler = new Handler(mediaThread.getLooper(), this);

        loader = new Loader("Loader:NokiaLiveChannelSource");
        mediaStreamBuffer = new IptvMediaStreamBuffer(FCC_PACKET_CAPACITY, RtpDataSource.MTU_SIZE);
    }

    @Override
    public void open(Uri uri) throws IOException {

        if (!opened) {

            loadable = new IptvStreamSourceLoadable(Assertions.checkNotNull(uri),
                    loadCondition, eventListener);

            loadable.open(uri);

            Runnable currentThreadTask = new Runnable() {
                @Override
                public void run() {
                    loader.startLoading(loadable, NokiaFastChannelChangeSource.this, 0);
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

    @Override
    public boolean hasDataAvailable() {
        return mediaStreamBuffer.hasDataAvailable();
    }

    @Override
    public void disableAudioStream() {
        loadable.disableAudio();
    }

    @Override
    public void disableVideoStream() {
        loadable.disableVideo();
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean handleMessage(Message msg) {
        return true;
    }

    // Loader.Callback implementation.

    @Override
    public void onLoadCompleted(IptvStreamSourceLoadable loadable, long elapsedRealtimeMs, long loadDurationMs) {
        eventListener.onFastChannelChangeLoadCompleted();
        loadCondition.open();
    }

    @Override
    public void onLoadCanceled(IptvStreamSourceLoadable loadable, long elapsedRealtimeMs, long loadDurationMs, boolean released) {
        eventListener.onFastChannelChangeLoadCanceled();
        loadCondition.open();
    }

    @Override
    public int onLoadError(IptvStreamSourceLoadable loadable, long elapsedRealtimeMs, long loadDurationMs, IOException error) {
        eventListener.onFastChannelChangeLoadError();
        loadCondition.open();
        return Loader.DONT_RETRY;
    }


    public class IptvStreamSourceLoadable
            implements Loader.Loadable, RtpEventListener {

        private final Uri uri;
        private final DataSinkSource dataSource;
        private final ConditionVariable loadCondition;
        private final IptvFastChannelChangeSource.EventListener eventListener;

        private int streamType;
        private int sequenceNumber;
        private byte[] packet;

        private long ssrc;
        private String cname;
        private boolean bitSwitch;

        private volatile boolean loadCanceled = false;

        private boolean audioDisabled = false;
        private boolean videoDisabled = false;

        public IptvStreamSourceLoadable(Uri uri, ConditionVariable loadCondition,
                                        IptvFastChannelChangeSource.EventListener eventListener) {
            this.uri = Assertions.checkNotNull(uri);
            this.loadCondition = loadCondition;
            this.eventListener = eventListener;

            this.dataSource = new RtpDataSource(null, DEAFULT_SOCKET_TIMEOUT_MILLIS, this);
            this.packet = new byte[RtpDataSource.MTU_SIZE];
            this.ssrc = -1;
            this.bitSwitch = false;
        }

        public void open(Uri uri) throws IOException {

            dataSource.open(new DataSpec(Uri.parse("rtp://" +
                    uri.getQueryParameter(URI_FAST_CHANNEL_SERVER_PARAMETER_ID)), 0, C.LENGTH_UNSET, null));

            ssrc = RtcpSessionUtils.SSRC();
            cname = RtcpSessionUtils.CNAME();

            byte packet[] = new byte [0];
            InetAddress srcAddr, hostAddr;

            try {

                srcAddr = InetAddress.getByName(uri.getHost());
                hostAddr = InetAddress.getByName(NetworkUtils.getLocalAddress());

            } catch (UnknownHostException ex) {
                throw new IOException(ex);
            }

            byte[] start = RtcpPacketUtils.longToBytes((long)300, 2);

            byte[] sAddr = srcAddr.getAddress();

            byte[] sPort = RtcpPacketUtils.longToBytes((long)uri.getPort(), 2);

            byte[] hAddr = hostAddr.getAddress();

            byte[] hPort = RtcpPacketUtils.longToBytes((long)0, 2);

            packet = RtcpPacketUtils.append(packet, start);

            packet = RtcpPacketUtils.append(packet, sPort);
            packet = RtcpPacketUtils.append(packet, sAddr);

            byte[] bounded = new byte [2];
            packet = RtcpPacketUtils.append (packet, bounded);

            packet = RtcpPacketUtils.append(packet,  RtcpPacketUtils.swapBytes(hPort));
            packet = RtcpPacketUtils.append(packet,  RtcpPacketUtils.swapBytes(hAddr));

            byte[] rtcpAppPacket = RtcpPacketBuilder.buildAppPacket(ssrc, cname,
                    "FCCR", packet);

            dataSource.write(rtcpAppPacket, 0, rtcpAppPacket.length);
        }

        public void disableAudio() {
            audioDisabled = true;
        }

        public void disableVideo() {
            videoDisabled = true;
        }

        @Override
        public void onRtpMediaStreamInfoRefresh(int payloadType, int sequenceNumber, long timestamp,
                                                long ssrc, byte[] extension) {
            this.sequenceNumber = sequenceNumber;
            this.streamType = ((extension[5] & 0x3f) >> 4) == 1 ? STREAM_AUDIO_TYPE :
                    STREAM_VIDEO_TYPE;

            if (!bitSwitch && (((extension[5] & 0x0f) >> 3) == 1)) {
                eventListener.onFastChannelChangeSwitchingReady();
                bitSwitch = true;
            }

            if (this.ssrc == -1) {
                eventListener.onFastChannelChangeSynchronizationSource(ssrc);
                this.ssrc = ssrc;
            }

            eventListener.onFastChannelChangeStreamInfoRefresh(streamType, sequenceNumber);
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

                while (!loadCanceled && ((length = dataSource.read(packet, 0, RtpDataSource.MTU_SIZE))
                        != C.RESULT_END_OF_INPUT)) {

                    try {

                        switch (streamType) {
                            case STREAM_AUDIO_TYPE:
                                if (!audioDisabled) {
                                    mediaStreamBuffer.put(sequenceNumber, packet, length);
                                }

                                break;

                            case STREAM_VIDEO_TYPE:
                                if (!videoDisabled) {
                                    mediaStreamBuffer.put(sequenceNumber, packet, length);
                                }
                        }

                        loadCondition.open();

                    } catch (Exception ex) {
                        throw new IOException();
                    }
                }

            } finally {

                byte[] rtcpByePacket = RtcpPacketBuilder.buildByePacket(ssrc, cname);
                dataSource.write(rtcpByePacket, 0, rtcpByePacket.length);

                Util.closeQuietly(dataSource);
            }
        }
    }
}
