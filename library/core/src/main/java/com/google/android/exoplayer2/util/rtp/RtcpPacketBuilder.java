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

import android.util.SparseIntArray;

/**
 * This class provides generic packet assembly and building functions for
 * RTCP packets
 */
public class RtcpPacketBuilder {

    private static final byte VERSION = 2;
    private static final byte PADDING = 0;

    // Payload Types
    private static final int RTCP_SR = (int) 200;
    private static final int RTCP_RR = (int) 201;
    private static final int RTCP_SDES =	(int) 202;
    private static final int RTCP_BYE = (int) 203;
    private static final int RTCP_APP = (int) 204;

    // Extended RTP for Real-time Transport Control Protocol Based Feedback
    private static final int RTCP_RTPFB = (int) 205;
    private static final int RTCP_PSFB = (int) 206;

    private static final byte RTCP_SDES_END = (byte) 0;
    private static final byte RTCP_SDES_CNAME =	(byte) 1;
    private static final byte RTCP_SDES_NAME =	(byte) 2;
    private static final byte RTCP_SDES_EMAIL =	(byte) 3;
    private static final byte RTCP_SDES_PHONE =	(byte) 4;
    private static final byte RTCP_SDES_LOC =	(byte) 5;
    private static final byte RTCP_SDES_TOOL =	(byte) 6;
    private static final byte RTCP_SDES_NOTE =	(byte) 7;
    private static final byte RTCP_SDES_PRIV =	(byte) 8;

    /**
     *   Assembly a Receiver Report RTCP Packet.
     *
     *   @param   ssrc
     *   @return  byte[] The Receiver Report Packet
     */
    private static byte[] assembleRTCPReceiverReportPacket(long ssrc) {
        /*
          0                   1                   2                   3
          0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
          +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
          |V=2|P|    RC   |   PT=RR=201   |             length            | header
          +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
          |                         SSRC of sender                        |
          +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
          |                 SSRC_1 (SSRC of first source)                 | report
          +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+ block
          | fraction lost |       cumulative number of packets lost       |   1
          +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
          |           extended highest sequence number received           |
          +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
          |                      interarrival jitter                      |
          +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
          |                         last SR (LSR)                         |
          +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
          |                   delay since last SR (DLSR)                  |
          +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
          |                 SSRC_2 (SSRC of second source)                | report
          +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+ block
          :                               ...                             :   2
          +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
          |                  profile-specific extensions                  |
          +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        */

        final int FIXED_HEADER_SIZE = 4; // 4 bytes

        // construct the first byte containing V, P and RC
        byte V_P_RC;
        V_P_RC = (byte) ((VERSION << 6) |
                (PADDING << 5) |
                (0x00)
                // take only the right most 5 bytes i.e.
                // 00011111 = 0x1F
        );

        // SSRC of sender
        byte[] ss = RtcpPacketUtils.longToBytes(ssrc, 4);

        // Payload Type = RR
        byte[] pt =
                RtcpPacketUtils.longToBytes((long) RTCP_RR, 1);

        byte[] receptionReportBlocks =
                new byte [0];

        /* TODO
           receptionReportBlocks =
                RtcpPacketUtils.append(receptionReportBlocks,
                        assembleRTCPReceptionReport());*/

        // Each reception report is 24 bytes, so calculate the number of
        // sources in the reception report block and update the reception
        // block count in the header
        byte receptionReports = (byte) (receptionReportBlocks.length / 24);

        // Reset the RC to reflect the number of reception report blocks
        V_P_RC = (byte) (V_P_RC | (byte) (receptionReports & 0x1F));

        byte[] length =
                RtcpPacketUtils.longToBytes(((FIXED_HEADER_SIZE + ss.length +
                        receptionReportBlocks.length)/4)-1, 2);

        byte[] packet = new byte [1];
        packet[0] = V_P_RC;
        packet = RtcpPacketUtils.append(packet, pt);
        packet = RtcpPacketUtils.append(packet, length);
        packet = RtcpPacketUtils.append(packet, ss);

        /*
            TODO
            packet = RtcpPacketUtils.append(packet, receptionReportBlocks);
        */

        return packet;
    }

    /**
     *   Assembly an Source Description SDES RTCP Packet.
     *
     *   @param   ssrc The sincronization source
     *   @param   cname The canonical name
     *   @return  The SDES Packet
     */
    private static byte[] assembleRTCPSourceDescriptionPacket(long ssrc, String cname) {
        /*
           0                   1                   2                   3
           0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
           +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
           |V=2|P|    SC   |  PT=SDES=202  |             length            |
           +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
           |                          SSRC/CSRC_1                          |
           +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
           |                           SDES items                          |
           |                              ...                              |
           +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
           |                          SSRC/CSRC_2                          |
           +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
           |                           SDES items                          |
           |                              ...                              |
           +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
        */

        final int FIXED_HEADER_SIZE = 4; // 4 bytes
        // construct the first byte containing V, P and SC
        byte v_p_sc;
        v_p_sc =    (byte) ((VERSION << 6) |
                (PADDING << 5) |
                (0x01));

        byte[] pt =
                RtcpPacketUtils.longToBytes ((long) RTCP_SDES, 1);

        /////////////////////// Chunk 1 ///////////////////////////////
        byte[] ss =
                RtcpPacketUtils.longToBytes ((long) ssrc, 4);


        ////////////////////////////////////////////////
        // SDES Item #1 :CNAME
        /* 0                   1                   2                   3
           0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
	       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	       |    CNAME=1    |     length    | user and domain name         ...
	       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	    */

        byte item = RTCP_SDES_CNAME;
        byte[] user_and_domain = new byte [cname.length()];
        user_and_domain = cname.getBytes();


        // Copy the CName item related fields
        byte[] cnameHeader = { item, (byte) user_and_domain.length };

        // Append the header and CName Information in the SDES Item Array
        byte[] sdesItem = new byte[0] ;
        sdesItem = RtcpPacketUtils.append (sdesItem, cnameHeader);
        sdesItem = RtcpPacketUtils.append (sdesItem, user_and_domain);

        int padLen = RtcpPacketUtils.calculatePadLength(sdesItem.length);

        // Determine the length of the packet (section 6.4.1 "The length of
        // the RTCP packet in 32 bit words minus one, including the header and
        // any padding")
        byte[] sdesLength = RtcpPacketUtils.longToBytes (((FIXED_HEADER_SIZE +
                ss.length + sdesItem.length + padLen + 4)/4)-1, 2);

        // Assemble all the info into a packet
        byte[] packet = new byte[2];

        packet[0] = v_p_sc;
        packet[1] = pt[0];
        packet = RtcpPacketUtils.append(packet, sdesLength);
        packet = RtcpPacketUtils.append(packet, ss);
        packet = RtcpPacketUtils.append(packet, sdesItem);

        // Append necessary padding fields
        byte[] padBytes = new byte [padLen];
        packet = RtcpPacketUtils.append (packet, padBytes);

        // Append SDES Item end field (32 bit boundary)
        byte[] sdesItemEnd = new byte [4];
        packet = RtcpPacketUtils.append (packet, sdesItemEnd);

        return packet;
    }

    /**
     *
     *   Assembly a "BYE" packet (PT=BYE=203)
     *
     *   @param   ssrc The sincronization source
     *   @return  The BYE Packet
     *
     */
    private static byte[] assembleRTCPByePacket(long ssrc) {
        /*
        0                   1                   2                   3
        0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
       |V=2|P|    SC   |   PT=BYE=203  |             length            |
       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
       |                           SSRC/CSRC                           |
       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
       :                              ...                              :
       +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
       |     length    |               reason for leaving             ... (opt)
       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
       */

        final int FIXED_HEADER_SIZE = 4; // 4 bytes
        // construct the first byte containing V, P and SC
        byte V_P_SC;
        V_P_SC =    (byte) ((VERSION << 6) |
                (PADDING << 5) |
                (0x01)
        );

        // Generate the payload type byte
        byte PT[] = RtcpPacketUtils.longToBytes((long) RTCP_BYE, 1);

        // Generate the SSRC
        byte ss[] = RtcpPacketUtils.longToBytes((long) ssrc, 4);

        byte textLength [] = RtcpPacketUtils.longToBytes(0 , 1);

        // Length of the packet is number of 32 byte words - 1
        byte[] length =
                RtcpPacketUtils.longToBytes(((FIXED_HEADER_SIZE + ss.length)/4)-1, 2);

        ///////////////////////// Packet Construction ///////////////////////////////
        byte packet[] = new byte [1];

        packet[0] = V_P_SC;
        packet = RtcpPacketUtils.append(packet, PT);
        packet = RtcpPacketUtils.append(packet, length);
        packet = RtcpPacketUtils.append(packet, ss);
        packet = RtcpPacketUtils.append(packet, textLength);

        return packet;
    }

    /**
     *
     *   Assembly a "APP" packet (PT=BYE=203)
     *
     *   @param   ssrc The sincronization source
     *   @return  The APP Packet
     *
     */
    private static byte[] assembleRTCPAppPacket(long ssrc, String appName,
                                                byte[] appData) {
        /*
            0                   1                   2                   3
            0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            |V=2|P| subtype |   PT=APP=204  |             length            |
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            |                           SSRC/CSRC                           |
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            |                          name (ASCII)                         |
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            |                   application-dependent data                 ...
	        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
       */

        final int FIXED_HEADER_SIZE = 4; // 4 bytes
        // construct the first byte containing V, P and SC
        byte V_P_SC;
        V_P_SC =    (byte) ((VERSION << 6) |
                (PADDING << 5) |
                (0x00));

        // Generate the payload type byte
        byte[] PT = RtcpPacketUtils.longToBytes((long) RTCP_APP, 1);

        // Generate the SSRC
        byte[] ss = RtcpPacketUtils.longToBytes((long) ssrc, 4);

        // Generate the APP
        byte[] name = appName.getBytes();

        int dataLen = name.length + appData.length;

        byte[] length = RtcpPacketUtils.longToBytes (((FIXED_HEADER_SIZE +
                ss.length + dataLen + 2)/4)-1, 2);

        ///////////////////////// Packet Construction ///////////////////////////////
        byte packet[] = new byte [1];

        packet[0] = V_P_SC;
        packet = RtcpPacketUtils.append(packet, PT);
        packet = RtcpPacketUtils.append(packet, length);
        packet = RtcpPacketUtils.append(packet, ss);

        packet = RtcpPacketUtils.append(packet, name);
        packet = RtcpPacketUtils.append(packet, appData);

        return packet;
    }


    /**
     *
     *   Assembly a Transport layer Feedback (Generic NACK) "RTPFB" packet (PT=BYE=205)
     *
     *   @param   ssrcSender The sincronization source of sender
     *   @param   ssrcSource The sincronization source
     *   @param   feedbackControlInfo The feedback control information
     *   @return  The RTPFB Packet
     *
     */
    private static byte[] assembleRTCPNackPacket(long ssrcSender, long ssrcSource,
                                                 SparseIntArray feedbackControlInfo) {
        /*
            0                   1                   2                   3
            0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            |V=2|P|  FMT=1  |     PT=205    |          length               |
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            |                  SSRC of packet sender                        |
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            |                  SSRC of media source                         |
            +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
            |            PID                |             BLP               |
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
       */

        final int FIXED_HEADER_SIZE = 4; // 4 bytes
        // construct the first byte containing V, P and FMT
        byte V_P_FMT;
        V_P_FMT = (byte) ((VERSION << 6) |
                (PADDING << 5) |
                (0x01));

        // Generate the payload type byte
        byte[] PT = RtcpPacketUtils.longToBytes((long) RTCP_RTPFB, 1);

        // Generate the SSRC packet sender
        byte[] ssps = RtcpPacketUtils.longToBytes((long) ssrcSender, 4);

        // Generate the SSRC media source
        byte[] ssms = RtcpPacketUtils.longToBytes((long) ssrcSource, 4);

        byte[] length = RtcpPacketUtils.longToBytes (((FIXED_HEADER_SIZE +
                ssps.length + ssms.length + (feedbackControlInfo.size()*4) + 2)/4)-1, 2);


        ///////////////////////// Packet Construction ///////////////////////////////
        byte packet[] = new byte [1];

        packet[0] = V_P_FMT;
        packet = RtcpPacketUtils.append(packet, PT);
        packet = RtcpPacketUtils.append(packet, length);
        packet = RtcpPacketUtils.append(packet, ssps);
        packet = RtcpPacketUtils.append(packet, ssms);

        // Generate the feedback control information (FCI)
        for (int index = 0; index < feedbackControlInfo.size(); index++) {

            // Generate the PID
            byte[] pid = RtcpPacketUtils.longToBytes((long) feedbackControlInfo.keyAt(index), 2);

            // Generate the BLP
            byte[] blp = RtcpPacketUtils.longToBytes((long) feedbackControlInfo.valueAt(index), 2);

            packet = RtcpPacketUtils.append(packet, pid);
            packet = RtcpPacketUtils.append(packet, blp);
        }

        return packet;
    }

    /**
     *
     *   Constructs a "APP" packet (PT=BYE=203)
     *
     *   @param   ssrc The sincronization source
     *   @param   cname The canonical name
     *   @return  The APP Packet
     *
     */

    public static byte[] buildAppPacket(long ssrc, String cname,
                                        String appName, byte[] appData) {
        byte packet[] = new byte [0];

        packet = RtcpPacketUtils.append(packet, assembleRTCPReceiverReportPacket(ssrc));
        packet = RtcpPacketUtils.append( packet, assembleRTCPSourceDescriptionPacket(ssrc, cname));
        packet = RtcpPacketUtils.append(packet, assembleRTCPAppPacket(ssrc, appName, appData));

        return packet;
    }

    /**
     *
     *   Constructs a "BYE" packet (PT=BYE=203)
     *
     *   @param   ssrc The sincronization source
     *   @param   cname The canonical name
     *   @return  The BYE Packet
     *
     */

    public static byte[] buildByePacket(long ssrc, String cname) {
        byte packet[] = new byte [0];

        packet = RtcpPacketUtils.append(packet, assembleRTCPReceiverReportPacket(ssrc));
        packet = RtcpPacketUtils.append(packet, assembleRTCPSourceDescriptionPacket(ssrc, cname));
        packet = RtcpPacketUtils.append(packet, assembleRTCPByePacket(ssrc));

        return packet;
    }

    /**
     *
     *   Constructs a Transport layer Feedback (Generic NACK) "RTPFB" packet (PT=BYE=205)
     *
     *   @param   ssrc The sincronization source
     *   @param   cname The canonical name
     *   @param   ssrcSource The sincronization source of sender
     *   @param   feedbackControlInfo The feedback control information
     *   @return  The RTPFB Packet
     *
     */

    public static byte[] buildNackPacket(long ssrc, String cname, long ssrcSource,
                                         SparseIntArray feedbackControlInfo) {
        byte packet[] = new byte [0];

        packet = RtcpPacketUtils.append(packet, assembleRTCPReceiverReportPacket(ssrc));
        packet = RtcpPacketUtils.append(packet, assembleRTCPSourceDescriptionPacket(ssrc, cname));
        packet = RtcpPacketUtils.append(packet, assembleRTCPNackPacket(ssrc, ssrcSource, feedbackControlInfo));

        return packet;
    }
}
