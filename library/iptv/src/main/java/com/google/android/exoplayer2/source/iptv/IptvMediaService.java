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

import java.io.IOException;

public interface IptvMediaService {

    interface Factory {
        IptvMediaService createMediaService(String providerId);
    }

    interface EventListener {
        void onFastChannelSourceError();
        void onRecoveryChannelSourceError();

        void onChannelChangeSwitchingReady();
        void onChannelChangeSwitched();
    }

    void openFastChannelSource(Uri uri) throws IOException;
    void openRecoveryChannelSource(Uri uri) throws IOException;
    void openLiveChannelSource(Uri uri) throws IOException;

    void closeLiveChannelSource() throws IOException;
    void closeFastChannelSource() throws IOException;
    void closeRecoveryChannelSource() throws IOException;

    boolean dataLossDetected();
    boolean earlierLossRecovered();

    boolean dataOnLiveChannelSource();
    boolean dataOnFastChannelSource();
    boolean dataOnRecoveryChannelSource();

    int readFromLiveChannelSource(byte[] data, int offset, int length) throws IOException;
    int readFromFastChannelSource(byte[] data, int offset, int length) throws IOException;
    int readFromRecoveryChannelSource(byte[] data, int offset, int length) throws IOException;

    boolean isFastChannelChangeSupported();
    boolean isLostPacketRecoverySupported();
}
