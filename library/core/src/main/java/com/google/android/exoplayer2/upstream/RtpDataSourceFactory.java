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

import com.google.android.exoplayer2.util.rtp.RtpEventListener;

/**
 * A {@link DataSource.Factory} that produces {@link RtpDataSource}.
 */
public final class RtpDataSourceFactory implements DataSource.Factory {

    private final RtpEventListener rtpEventListener;
    private final TransferListener<? super RtpDataSource> listener;

    public RtpDataSourceFactory() { this(null, null); }

    public RtpDataSourceFactory(TransferListener<? super RtpDataSource> listener) {
        this(listener, null);
    }

    public RtpDataSourceFactory(TransferListener<? super RtpDataSource> listener,
                                RtpEventListener rtpEventListener) {
        this.listener = listener;
        this.rtpEventListener = rtpEventListener;
    }

    @Override
    public DataSource createDataSource() {
        return new RtpDataSource(listener, rtpEventListener);
    }

}
