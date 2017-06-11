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
import android.util.SparseIntArray;
import android.util.SparseLongArray;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.source.iptv.IptvMediaStreamBuffer;
import com.google.android.exoplayer2.source.iptv.IptvLostPacketRecoverySource;
import com.google.android.exoplayer2.upstream.DataSinkSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.upstream.RtpDataSource;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.util.rtp.RtcpPacketBuilder;
import com.google.android.exoplayer2.util.rtp.RtcpSessionUtils;
import com.google.android.exoplayer2.util.rtp.RtpEventListener;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.BufferUnderflowException;

public class NokiaLostPacketRecoverySource implements IptvLostPacketRecoverySource,
        Loader.Callback<NokiaLostPacketRecoverySource.IptvStreamSourceLoadable>,
        Handler.Callback {

    private final Handler mediaHandler;
    private final HandlerThread mediaThread;

    private final Loader loader;
    private IptvStreamSourceLoadable loadable;
    private IptvMediaStreamBuffer mediaStreamBuffer;

    private final ConditionVariable loadCondition;
    private final IptvLostPacketRecoverySource.EventListener eventListener;

    private final SparseLongArray timestamps;

    private boolean opened = false;

    private long ssrc;
    private long ssrcSource;
    private String cname;

    private final int MAX_PACKET_SEQUENCE = 65536;
    private final int BITMASK_LENGTH = 16;

    private static final int PACKET_LOSS_CAPACITY = 512;
    private static final double PACKET_LOSS_PERCENT = 0.7;

    private static final double PACKET_LOSS_ACCEPTABLE = PACKET_LOSS_CAPACITY * PACKET_LOSS_PERCENT;

    public NokiaLostPacketRecoverySource(IptvLostPacketRecoverySource.EventListener eventListener) {

        this.eventListener = eventListener;

        loadCondition = new ConditionVariable();

        mediaThread = new HandlerThread("IptvRetryChannelSource:Handler",
                Process.THREAD_PRIORITY_AUDIO);
        mediaThread.start();

        mediaHandler = new Handler(mediaThread.getLooper(), this);

        loader = new Loader("Loader:IptvRetryChannelSource");
        mediaStreamBuffer = new IptvMediaStreamBuffer(PACKET_LOSS_CAPACITY, RtpDataSource.MTU_SIZE);

        ssrc = RtcpSessionUtils.SSRC();
        cname = RtcpSessionUtils.CNAME();

        timestamps = new SparseLongArray(PACKET_LOSS_CAPACITY);
    }

    @Override
    public void open(Uri uri) throws IOException {

        if (!opened) {

            loadable = new IptvStreamSourceLoadable(loadCondition);

            loadable.open(uri);

            Runnable currentThreadTask = new Runnable() {
                @Override
                public void run() {
                    loader.startLoading(loadable, NokiaLostPacketRecoverySource.this, 0);
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

            resetRecovery();

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


    @SuppressWarnings("unchecked")
    @Override
    public boolean handleMessage(Message msg) {
        return true;
    }

    // Loader.Callback implementation.

    @Override
    public void onLoadCompleted(IptvStreamSourceLoadable loadable, long elapsedRealtimeMs, long loadDurationMs) {
        eventListener.onLostPacketRecoveryLoadError();
        loadCondition.open();
    }

    @Override
    public void onLoadCanceled(IptvStreamSourceLoadable loadable, long elapsedRealtimeMs, long loadDurationMs, boolean released) {
        eventListener.onLostPacketRecoveryLoadCanceled();
        loadCondition.open();
    }

    @Override
    public int onLoadError(IptvStreamSourceLoadable loadable, long elapsedRealtimeMs, long loadDurationMs, IOException error) {
        //eventListener.onLostPacketRecoveryLoadError();
        loadCondition.open();
        return Loader.RETRY;
    }

    @Override
    public void resetRecovery() {
        timestamps.clear();
        mediaStreamBuffer.reset();
    }

    @Override
    public int getCurrentSequenceNumber() {
        return mediaStreamBuffer.getFirstSequenceNumber();
    }

    @Override
    public long getCurrentTimeStamp() {
        return mediaStreamBuffer.getFirstTimeStamp();
    }

    @Override
    public int getMaxPacketLossAcceptable() {
        return (int) PACKET_LOSS_ACCEPTABLE;
    }

    @Override
    public void setSsrcSource(long ssrcSource) {
        this.ssrcSource = ssrcSource;
    }

    @Override
    public void notifyLostPacket(int lastSequenceReceived, int numLostPackets) throws IOException {
        int maskNextSequences, bitmaskShift;
        int firstSequence, numPackets = 0;
        SparseIntArray feedbackControlInfo = new SparseIntArray();

        long currentTime = System.currentTimeMillis();

        while (numLostPackets > 0) {
            numPackets++;

            firstSequence = ((lastSequenceReceived + numPackets) < MAX_PACKET_SEQUENCE) ?
                    (lastSequenceReceived + numPackets) :
                    ((lastSequenceReceived + numPackets) - MAX_PACKET_SEQUENCE);

            --numLostPackets;

            timestamps.put(firstSequence, currentTime);

            for (bitmaskShift = 0, maskNextSequences = 0;
                 (bitmaskShift < BITMASK_LENGTH) && (numLostPackets > 0);
                 ++bitmaskShift, ++numPackets, --numLostPackets) {

                maskNextSequences |= ((0xffff) & (1 << bitmaskShift));

                int sequence = ((firstSequence + bitmaskShift + 1) < MAX_PACKET_SEQUENCE) ?
                        (firstSequence + bitmaskShift + 1) :
                        ((firstSequence + bitmaskShift + 1) - MAX_PACKET_SEQUENCE);

                timestamps.put(sequence, currentTime);
            }

            feedbackControlInfo.append(firstSequence, maskNextSequences);
        }

        if (feedbackControlInfo.size() > 0) {
            byte[] rtcpByePacket = RtcpPacketBuilder.buildNackPacket(ssrc, cname, ssrcSource, feedbackControlInfo);
            loadable.write(rtcpByePacket, 0, rtcpByePacket.length);
        }
    }

    public class IptvStreamSourceLoadable
            implements Loader.Loadable, RtpEventListener {

        private final DataSinkSource dataSource;
        private final ConditionVariable loadCondition;

        private int sequenceNumber;
        private byte[] buffer;

        private volatile boolean loadCanceled = false;

        public IptvStreamSourceLoadable(ConditionVariable loadCondition) {
            this.loadCondition = loadCondition;

            this.dataSource = new RtpDataSource(null, 0, this);
            this.buffer = new byte[RtpDataSource.MTU_SIZE];
        }

        public void open(Uri uri) throws IOException {

            dataSource.open(new DataSpec(Uri.parse("rtp://" +
                    uri.getQueryParameter(URI_LOST_PACKET_RECOVERY_SERVER_PARAMETER_ID)), 0, C.LENGTH_UNSET, null));
        }

        public void write(byte[] data, int offset, int length) throws IOException {
            dataSource.write(data, offset, length);
        }

        @Override
        public void onRtpMediaStreamInfoRefresh(int payloadType, int sequenceNumber, long timestamp,
                                                long ssrc, byte[] extension) {
            this.sequenceNumber = sequenceNumber;
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

                while (!loadCanceled) {

                    try {

                        if ((length = dataSource.read(buffer, 0, RtpDataSource.MTU_SIZE))
                                == C.RESULT_END_OF_INPUT) {
                            loadCondition.close();
                            loadCanceled = true;
                            break;
                        }

                        long timestamp = timestamps.get(sequenceNumber);

                        if (timestamp > 0) {
                            mediaStreamBuffer.put(sequenceNumber, timestamp, buffer, length);
                            timestamps.delete(sequenceNumber);
                        }

                        loadCondition.open();

                    } catch (RtpDataSource.RtpDataSourceException ex) {
                        if (!(ex.getCause() instanceof SocketTimeoutException))
                            throw new IOException();

                    } catch (BufferUnderflowException ex) {
                        throw new IOException();
                    }
                }

            } finally {

                Util.closeQuietly(dataSource);
            }
        }
    }
}
