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

import android.util.Log;

public class RtpHelper {

    private static final String LOG_TAG = RtpHelper.class.getSimpleName();

    private static final int MPEG_TS_SIG = 0x47;
    private static final int RTP_MIN_SIZE = 4;
    private static final int RTP_HDR_SIZE = 12; /* RFC 3550 */
    private static final int RTP_VERSION_2 = 0x02;

    /* offset to header extension and extension length,
    * as per RFC 3550 5.3.1 */
    private static final int XTLEN_OFFSET = 14;
    private static final int XTSIZE = 4;

    private static final int RTP_XTHDRLEN = XTLEN_OFFSET + XTSIZE;

    private static final int CSRC_SIZE = 4;

    private static boolean verify(byte[] buf) {
        int rtpPayloadType, rtpVersion;

        if( (buf.length < RTP_MIN_SIZE) || (buf.length < RTP_HDR_SIZE) ) {
            Log.e(LOG_TAG, "Inappropriate length=[" + buf.length + "] of RTP packet");
            return false;
        }

        rtpVersion = ( buf[0] & 0xC0 ) >> 6;
        rtpPayloadType = buf[1] & 0x7F;

        if( RTP_VERSION_2 != rtpVersion ) {
            Log.e(LOG_TAG, "Wrong RTP version " + rtpVersion + ", must be " + RTP_VERSION_2);
            return false;
        }

        switch( rtpPayloadType ) {
            case RtpPacket.RTP_MPA_TYPE:
            case RtpPacket.RTP_MPV_TYPE:
            case RtpPacket.RTP_MP2TS_TYPE:
            case RtpPacket.RTP_DYN_TYPE:
                break;
            default:
                Log.e(LOG_TAG, "Unsupported RTP payload type " + rtpPayloadType);
                return false;
        }

        return true;
    }

    /* calculate length of an RTP header extension */
    private static int headerExtLen(byte[] buf) {
        int rtpExt = buf[0] & 0x10;
        int extLen = 0;

        if (rtpExt != 0) {
            //    	Log.v(LOG_TAG, "RTP x-header detected, CSRC=" + rtpCSRC);

            if( buf.length < RTP_XTHDRLEN ) {
                Log.e(LOG_TAG, "RTP x-header requires " + (XTLEN_OFFSET + 1) +
                        " bytes, only " + buf.length + " provided");
                return -1;
            }

            extLen = XTSIZE +
                    (Integer.SIZE/Byte.SIZE) * ((buf[ XTLEN_OFFSET ] << 8) + buf[ XTLEN_OFFSET + 1 ]);
        }

        return extLen;
    }

    /* calculate length of an RTP header */
    private static int headerLen(byte[] buf) {
        int payloadType, csrc;
        int headLen = 0;

        payloadType = buf[1] & 0x7F;
        csrc   = buf[0] & 0x0F;

        /* profile-based skip: adopted from vlc 0.8.6 code */
        if ((RtpPacket.RTP_MPA_TYPE == payloadType) || (RtpPacket.RTP_MPV_TYPE == payloadType)) {
            headLen = 4;
        } else if ((RtpPacket.RTP_MP2TS_TYPE != payloadType) && (RtpPacket.RTP_DYN_TYPE != payloadType)) {
            Log.e(LOG_TAG, "Unsupported payload type " + payloadType);
            return -1;
        }

        headLen += RTP_HDR_SIZE + (CSRC_SIZE * csrc);

        return headLen;
    }

    public static RtpPacket decode(byte[] buf, int length) {
        int sequence;
        int padding, padLen = 0, headLen, extLen;
        int frontSkip = 0, backSkip = 0;

        if (!verify(buf)) {
            return null;
        }

        if (-1 == (headLen = headerLen(buf))) {
            return null;
        }

        frontSkip += headLen;

        if (-1 == (extLen = headerExtLen(buf))) {
            return null;
        }

        frontSkip += extLen;

        padding = buf[0] & 0x20;

        if (padding != 0) {
            padLen = buf[length - 1];
        }

        backSkip += padLen;

        if (length < (frontSkip + backSkip)) {
            Log.e (LOG_TAG, "RTP_process: invalid header (skip "
                    + (frontSkip + backSkip) + " exceeds packet length " + length);
            return null;
        }

        int payloadType = buf[1] & 0x7F;

        //Payload Type: 33 MPEG 2 TS
        if (payloadType == RtpPacket.RTP_MP2TS_TYPE) {
            sequence = (buf[2] & 0xff) * 256 + (buf[3] & 0xff);
        } else {
            //Payload Type: DynamicRTP-Type 99 MPEG2TS with sequence number preceded.
            sequence = (buf[frontSkip]&0xff)*256+(buf[frontSkip+1]&0xff);
            frontSkip+=2;
        }

        byte[] extension = new byte[extLen];
        System.arraycopy(buf, headLen, extension, 0, extLen);

        byte[] payload = new byte[length-frontSkip];
        System.arraycopy(buf, frontSkip, payload, 0, length-frontSkip);

        long timestamp = ((buf[4] & 0xff) << 24) | ((buf[5] & 0xff) << 16) |
                ((buf[6] & 0xff) << 8) | (buf[7] & 0xff);

        long ssrc = ((buf[8] & 0xff) << 24) | ((buf[9] & 0xff) << 16) |
                ((buf[10] & 0xff) << 8) | (buf[11] & 0xff);

        return new RtpPacket(payloadType, sequence, timestamp, ssrc, extension,
                payload);
    }
}