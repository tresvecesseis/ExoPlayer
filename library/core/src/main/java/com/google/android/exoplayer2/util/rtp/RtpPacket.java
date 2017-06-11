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
package com.google.android.exoplayer2.util.rtp;

public class RtpPacket {

    //((buf[17] & 0x3f) >> 4) == 1 ? RtpPacket.AUDIO_TYPE : RtpPacket.VIDEO_TYPE

    /* MPEG payload-type constants */
    public static final int RTP_MPA_TYPE = 0x0E;     // MPEG-1 and MPEG-2 audio
    public static final int RTP_MPV_TYPE = 0x20;     // MPEG-1 and MPEG-2 video
    public static final int RTP_MP2TS_TYPE = 0x21;   // MPEG TS
    public static final int RTP_DYN_TYPE = 0x63;    // MPEG TS

    private int sequenceNumber;
    private int payloadType;

    private long timestamp;
    private long ssrc;

    private byte[] extension;

    private byte[] payload;

    public RtpPacket (int payloadType, int sequenceNumber, long timestamp,
                      long ssrc, byte[] extension, byte[] payload) {
        this.payloadType = payloadType;
        this.sequenceNumber = sequenceNumber;
        this.ssrc = ssrc;
        this.extension = extension;
        this.payload = payload;
    }

    public int getPayloadType() {
        return payloadType;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public long getTimeStamp() {return timestamp; }

    public byte[] getExtension() { return extension; }

    public long getSsrc() {return ssrc; }

    public byte[] getPayload() { return payload; }
}