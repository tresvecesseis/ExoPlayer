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
package com.google.android.exoplayer2.upstream;

import android.net.Uri;
import android.support.annotation.IntDef;

import com.google.android.exoplayer2.util.rtp.RtpEventListener;
import com.google.android.exoplayer2.util.rtp.RtpHelper;
import com.google.android.exoplayer2.util.rtp.RtpPacket;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.SocketTimeoutException;

/**
 * A RTP {@link DataSource}.
 */
public class RtpDataSource implements DataSinkSource, TransferListener<UdpDataSource> {

   /**
     * Thrown when an error is encountered when trying to read from a {@link RtpDataSource}.
     */
    public static class RtpDataSourceException extends IOException {

        @Retention(RetentionPolicy.SOURCE)
        @IntDef({TYPE_OPEN, TYPE_READ, TYPE_WRITE, TYPE_CLOSE})
        public @interface Type {}
        public static final int TYPE_OPEN = 1;
        public static final int TYPE_READ = 2;
        public static final int TYPE_WRITE = 3;
        public static final int TYPE_CLOSE = 4;

        @Type
        public final int type;

        /**
         * The {@link DataSpec} associated with the current connection.
         */
        public final DataSpec dataSpec;

        public RtpDataSourceException(DataSpec dataSpec, @Type int type) {
            super();
            this.dataSpec = dataSpec;
            this.type = type;
        }

        public RtpDataSourceException(String message, DataSpec dataSpec, @Type int type) {
            super(message);
            this.dataSpec = dataSpec;
            this.type = type;
        }

        public RtpDataSourceException(IOException cause, DataSpec dataSpec, @Type int type) {
            super(cause);
            this.dataSpec = dataSpec;
            this.type = type;
        }

        public RtpDataSourceException(String message, IOException cause, DataSpec dataSpec,
                                       @Type int type) {
            super(message, cause);
            this.dataSpec = dataSpec;
            this.type = type;
        }
    }

    /**
     * The maximum transfer unit, in bytes.
     */
    public static final int MTU_SIZE = 1500;

    private final TransferListener<? super RtpDataSource> listener;

    private DataSpec dataSpec;

    private UdpDataSource udpDataSource;
    private RtpEventListener rtpListener;

    private RtpPacket rtpPacket;
    private final byte[] packetBuffer;

    private int packetRemaining;

    public RtpDataSource() {
        this(null);
    }

    /**
     * @param listener An optional listener.
     */
    public RtpDataSource(TransferListener<? super RtpDataSource> listener) {
        this(listener, UdpDataSource.DEAFULT_SOCKET_TIMEOUT_MILLIS, null);
    }

    /**
     * @param listener An optional listener.
     * @param socketTimeoutMillis The socket timeout in milliseconds. A timeout of zero is interpreted
     *     as an infinite timeout.
     */
    public RtpDataSource(TransferListener<? super RtpDataSource> listener, int socketTimeoutMillis) {
        this(listener, socketTimeoutMillis, null);
    }

    /**
     * @param listener An optional listener.
     * @param rtpListener An optional RTP listener.
     */
    public RtpDataSource(TransferListener<? super RtpDataSource> listener,
                         RtpEventListener rtpListener) {
        this(listener, UdpDataSource.DEAFULT_SOCKET_TIMEOUT_MILLIS, rtpListener);
    }

    /**
     * @param listener An optional listener.
     * @param socketTimeoutMillis The socket timeout in milliseconds. A timeout of zero is interpreted
     *     as an infinite timeout.
     * @param rtpListener An optional RTP listener.
     */
    public RtpDataSource(TransferListener<? super RtpDataSource> listener,
                         int socketTimeoutMillis,
                         RtpEventListener rtpListener) {
        this.listener = listener;
        this.rtpListener = rtpListener;

        packetBuffer = new byte[MTU_SIZE];
        udpDataSource = new UdpDataSource(new DefaultBandwidthMeter(), MTU_SIZE,
                socketTimeoutMillis, false);
    }

    @Override
    public Uri getUri() {
        return udpDataSource.getUri();
    }

    @Override
    public long open(DataSpec dataSpec) throws RtpDataSourceException {
        this.dataSpec = dataSpec;

        try {

            return udpDataSource.open(dataSpec);

        } catch (IOException e) {
            throw new RtpDataSourceException(e, dataSpec, RtpDataSourceException.TYPE_OPEN);
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws RtpDataSourceException {

        if (readLength == 0) {
            return 0;
        }

        try {

            if (packetRemaining == 0) {
                int bytesToReaded = udpDataSource.read(packetBuffer, 0, MTU_SIZE);

                rtpPacket = RtpHelper.decode(packetBuffer, bytesToReaded);
                packetRemaining = rtpPacket.getPayload().length;

                if (rtpListener != null) {
                    rtpListener.onRtpMediaStreamInfoRefresh(rtpPacket.getPayloadType(),
                            rtpPacket.getSequenceNumber(),
                            rtpPacket.getTimeStamp(),
                            rtpPacket.getSsrc(),
                            rtpPacket.getExtension());
                }

                if (listener != null) {
                    listener.onBytesTransferred(this, packetRemaining);
                }
            }

            int packetOffset = rtpPacket.getPayload().length - packetRemaining;
            int bytesToRead = Math.min(packetRemaining, readLength);
            System.arraycopy(rtpPacket.getPayload(), packetOffset, buffer, offset, bytesToRead);
            packetRemaining -= bytesToRead;
            return bytesToRead;

        } catch (UdpDataSource.UdpDataSourceException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                throw new RtpDataSourceException(
                        new SocketTimeoutException(), dataSpec, RtpDataSourceException.TYPE_READ);
            } else {
                throw new RtpDataSourceException(e, dataSpec, RtpDataSourceException.TYPE_READ);
            }
        }
    }

    @Override
    public void write(byte[] buffer, int offset, int readLength) throws RtpDataSourceException {
        // We've write all of the data from the buffer.
        try {
            udpDataSource.write(buffer, offset, readLength);

        } catch (IOException e) {
            throw new RtpDataSourceException(e, dataSpec, RtpDataSourceException.TYPE_WRITE);
        }
    }

    @Override
    public void close() {
        udpDataSource.close();
    }


    @Override
    public void onTransferStart(UdpDataSource source, DataSpec dataSpec) {
        listener.onTransferStart(this, dataSpec);
    }

    @Override
    public void onBytesTransferred(UdpDataSource source, int bytesTransferred) {
        //do nothing
    }

    @Override
    public void onTransferEnd(UdpDataSource source) {
        listener.onTransferEnd(this);
    }

}
