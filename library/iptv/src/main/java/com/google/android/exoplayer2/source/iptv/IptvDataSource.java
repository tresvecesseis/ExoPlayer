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
package com.google.android.exoplayer2.source.iptv;

import android.net.Uri;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.IOException;

/**
 * An IPTV {@link DataSource} that supports a custom URI scheme.
 * (e.g. iptv://239.0.0.1:1111?vendor=nokia&fcc_server_addr=172.24.0.0:2222&lpr_server_addr=172.26.0.0:3333).
 */
public class IptvDataSource implements DataSource, IptvMediaService.EventListener {

    private final TransferListener<? super IptvDataSource> listener;

    private final static String URI_VENDOR_PARAMETER_ID = "vendor";

    private DataSpec dataSpec;
    private IptvMediaService iptvMediaService;

    private boolean liveChannelOpened = false;
    private boolean recoveryChannelOpened = false;
    private boolean channelChangeSwitched = true;

    private final IptvMediaServiceFactory iptvMediaServiceFactory;

    public IptvDataSource(TransferListener<? super IptvDataSource> listener) {
        this.listener = listener;
        iptvMediaServiceFactory = new IptvMediaServiceFactory(this);
    }

    @Override
    public Uri getUri() {
        return null;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {

        this.dataSpec = dataSpec;

        iptvMediaService = iptvMediaServiceFactory.createMediaService(
                dataSpec.uri.getQueryParameter(URI_VENDOR_PARAMETER_ID));

        if (iptvMediaService == null) {
            throw new IOException("IPTV vendor identifier wrong");
        }

        if (iptvMediaService.isFastChannelChangeSupported()) {

            try {
                iptvMediaService.openFastChannelSource(dataSpec.uri);
                channelChangeSwitched = false;

            } catch (IOException e) {
                /* Do nothing */
            }
        }

        if (channelChangeSwitched) {
            iptvMediaService.openLiveChannelSource(dataSpec.uri);
            liveChannelOpened = true;
        }

        if (iptvMediaService.isLostPacketRecoverySupported()) {

            try {
                iptvMediaService.openRecoveryChannelSource(dataSpec.uri);
                recoveryChannelOpened = true;

            } catch (IOException e) {
                /* Do nothing */
            }
        }

        if (listener != null) {
            listener.onTransferStart(this, dataSpec);
        }

        return C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] data, int offset, int length) throws IOException {
        int bytes = 0;

        if (channelChangeSwitched) {
            if (iptvMediaService.dataOnFastChannelSource()) {
                bytes = iptvMediaService.readFromFastChannelSource(data, offset, length);
            } else {

                if (recoveryChannelOpened && iptvMediaService.dataLossDetected()) {
                    if (iptvMediaService.dataOnRecoveryChannelSource()) {
                        if (iptvMediaService.earlierLossRecovered()) {
                            bytes = iptvMediaService.readFromRecoveryChannelSource(data, offset, length);
                        } else {
                            bytes = iptvMediaService.readFromLiveChannelSource(data, offset, length);
                        }
                    }

                } else {
                    bytes = iptvMediaService.readFromLiveChannelSource(data, offset, length);
                }
            }
        } else {
            bytes = iptvMediaService.readFromFastChannelSource(data, offset, length);
        }

        if (listener != null) {
            listener.onBytesTransferred(this, bytes);
        }

        return bytes;
    }

    @Override
    public void close() throws IOException {

        iptvMediaService.closeFastChannelSource();
        iptvMediaService.closeRecoveryChannelSource();
        iptvMediaService.closeLiveChannelSource();

        if (listener != null) {
            listener.onTransferEnd(this);
        }
    }

    @Override
    public void onFastChannelSourceError() {

        try {
            iptvMediaService.closeFastChannelSource();
        } catch (IOException e) {}

        channelChangeSwitched = true;

        if (!liveChannelOpened) {
            try {
                iptvMediaService.openLiveChannelSource(dataSpec.uri);

            } catch (IOException e) {
                 /* Do nothing */
            }
        }
    }

    @Override
    public void onRecoveryChannelSourceError() {

        try {
            iptvMediaService.closeRecoveryChannelSource();
        } catch (IOException e) {
             /* Do nothing */
        }

        recoveryChannelOpened = false;
    }

    @Override
    public void onChannelChangeSwitchingReady() {
        if (!liveChannelOpened) {
            try {
                iptvMediaService.openLiveChannelSource(dataSpec.uri);
            } catch (IOException e) {
                 /* Do nothing */
            }
        }
    }

    @Override
    public void onChannelChangeSwitched() {
        try {
            iptvMediaService.closeFastChannelSource();
            channelChangeSwitched = true;
        } catch (IOException e) {
             /* Do nothing */
        }
    }
}
