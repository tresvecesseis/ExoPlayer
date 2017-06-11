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
import android.util.Log;

import com.google.android.exoplayer2.source.iptv.IptvFastChannelChangeSource;
import com.google.android.exoplayer2.source.iptv.IptvLiveChannelSource;
import com.google.android.exoplayer2.source.iptv.IptvLostPacketRecoverySource;
import com.google.android.exoplayer2.source.iptv.IptvMediaService;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class NokiaMediaService implements IptvMediaService,
        IptvLiveChannelSource.EventListener,
        IptvFastChannelChangeSource.EventListener, IptvLostPacketRecoverySource.EventListener {

    private final IptvMediaService.EventListener listener;

    private IptvLiveChannelSource liveChannelSource;
    private IptvFastChannelChangeSource fastChannelChangeSource;
    private IptvLostPacketRecoverySource lostPacketRecoverySource;

    private int firstVideoSequence = -1;
    private int firstAudioSequence = -1;

    private int lastSequenceReaded = -1;
    private int lastSequenceReceived = -1;

    private boolean audioSynchronized = false;
    private boolean videoSynchronized = false;
    private boolean channelChangeSwitched = false;

    private boolean lostPacketRecovery = true;
    private int lostPacketPending = 0;

    public static final int STREAM_AUDIO_TYPE = 0;
    public static final int STREAM_VIDEO_TYPE = 1;

    private long totalPacketReaded = 0;
    private long totalLossPacketPermanent = 0;
    private long totalLossPacketRequested = 0;
    private long totalLossPacketRecovered = 0;
    private long totalLossPacketDiscarded = 0;

    private SimpleDateFormat s;

    public NokiaMediaService(IptvMediaService.EventListener listener) {
        this.listener = listener;

        liveChannelSource = new NokiaLiveChannelSource(this);
        fastChannelChangeSource = new NokiaFastChannelChangeSource(this);
        lostPacketRecoverySource = new NokiaLostPacketRecoverySource(this);

        s = new SimpleDateFormat("hh:mm:ss");

    //    Log.v("IptvNokiaMediaService", "[" + s.format(new Date()) + "] starting stats..");
    }

    @Override
    public void openLiveChannelSource(Uri uri) throws IOException {
        liveChannelSource.open(uri);
    }

    @Override
    public void openFastChannelSource(Uri uri) throws IOException {
        fastChannelChangeSource.open(uri);
    }

    @Override
    public void openRecoveryChannelSource(Uri uri) throws IOException {
        lostPacketRecoverySource.open(uri);
    }

    @Override
    public void closeLiveChannelSource() throws IOException {
        liveChannelSource.close();

    //    Log.v("IptvNokiaMediaService", "[" + s.format(new Date()) + "] Lost packet recovery stats: permanently=[" + totalLossPacketPermanent + "], " +
      //          "requested=[" + totalLossPacketRequested + "], recovered=[" + totalLossPacketRecovered + "], " +
        //        "discarded=["+totalLossPacketDiscarded + "], total packets=["+totalPacketReaded+"]");
    }

    @Override
    public void closeFastChannelSource() throws IOException {
        fastChannelChangeSource.close();
    }

    @Override
    public void closeRecoveryChannelSource() throws IOException {
        lostPacketRecoverySource.close();
    }

    @Override
    public int readFromLiveChannelSource(byte[] data, int offset, int length) throws IOException {
       int sequenceNumber = liveChannelSource.getCurrentSequenceNumber();

        if (sequenceNumber == -1)
            return 0;

       int bytes =  liveChannelSource.read(data, offset, length);

        if (sequenceNumber != liveChannelSource.getCurrentSequenceNumber()) {
            if (sequenceNumber != -1) {

                if (lastSequenceReaded != -1) {
                    int nextSequence = ((lastSequenceReaded + 1) < 65536) ? (lastSequenceReaded + 1) :
                            (lastSequenceReaded + 1) - 65536;

                    if (sequenceNumber != nextSequence) {
                        //Log.v("IptvNokiaMediaService", "[mcast] lost packet permanently from sequence " + nextSequence
                        //+ " to sequence " + sequenceNumber);

                        totalLossPacketPermanent += (nextSequence < sequenceNumber) ? (sequenceNumber - nextSequence) :
                                (65535 - nextSequence) + sequenceNumber;

               //         Log.v("IptvNokiaMediaService", "[" + s.format(new Date()) + "] Lost packet recovery stats: permanently=[" + totalLossPacketPermanent + "], " +
                 //               "requested=[" + totalLossPacketRequested + "], recovered=[" + totalLossPacketRecovered + "], " +
                   //             "discarded=["+totalLossPacketDiscarded + "]");
                    }
                }

                lastSequenceReaded = sequenceNumber;
            }

            totalPacketReaded++;

            //Log.v("IptvNokiaMediaService", "total=["+totalPacketReaded+"]");
        }

        return bytes;
    }

    @Override
    public int readFromFastChannelSource(byte[] data, int offset, int length) throws IOException {
        return fastChannelChangeSource.read(data, offset, length);
    }

    @Override
    public int readFromRecoveryChannelSource(byte[] data, int offset, int length) throws IOException {
        int retryFirstSequenceNumber = lostPacketRecoverySource.getCurrentSequenceNumber();

        if (retryFirstSequenceNumber == -1)
            return 0;

        int bytes = lostPacketRecoverySource.read(data, offset, length);

        if (retryFirstSequenceNumber != lostPacketRecoverySource.getCurrentSequenceNumber()) {
            lostPacketPending--;

            totalLossPacketRecovered++;

            if (lastSequenceReaded != -1) {
                int nextSequence = (lastSequenceReaded + 1) < 65536 ? (lastSequenceReaded + 1) :
                        (lastSequenceReaded + 1) - 65536;

                if (retryFirstSequenceNumber != nextSequence) {
                    //Log.v("IptvNokiaMediaService", "[retry] lost packet permanently from sequence " + nextSequence
                      //      + " to sequence " + retryFirstSequenceNumber);

                    totalLossPacketPermanent += (nextSequence < retryFirstSequenceNumber) ? (retryFirstSequenceNumber - nextSequence) :
                            (65535 - nextSequence) + retryFirstSequenceNumber;
                }
            }

            //Log.v("IptvNokiaMediaService", "[" + s.format(new Date()) + "] Lost packet recovery stats: permanently=[" + totalLossPacketPermanent + "], " +
              //      "requested=[" + totalLossPacketRequested + "], recovered=[" + totalLossPacketRecovered + "], " +
                //    "discarded=["+totalLossPacketDiscarded + "]");

            lastSequenceReaded = retryFirstSequenceNumber;

            totalPacketReaded++;

            //Log.v("IptvNokiaMediaService", "total=["+totalPacketReaded+"]");
        }

        return bytes;
    }

    @Override
    public boolean isFastChannelChangeSupported() {
        return true;
    }

    @Override
    public boolean isLostPacketRecoverySupported() {
        return true;
    }

    @Override
    public boolean dataOnLiveChannelSource() {
        return liveChannelSource.hasDataAvailable();
    }

    // IptvLiveChannelSource.EventListener implementation.

    @Override
    public void onLiveChannelLoadCanceled() {
        // Do nothing
    }

    @Override
    public void onLiveChannelLoadError() {
        // Do nothing
    }

    @Override
    public void onLiveChannelSynchronizationSource(long ssrc) {
        if (lostPacketRecovery) {
            lostPacketRecoverySource.setSsrcSource(ssrc);
        }
    }

    @Override
    public void onLiveChannelStreamInfoRefresh(int streamType, int sequenceNumber) {

        if (lastSequenceReceived != -1) {
            int nextSequence = ((lastSequenceReceived + 1) < 65536) ? (lastSequenceReceived + 1) :
                    (lastSequenceReceived + 1) - 65536;

            if (sequenceNumber != nextSequence) {
                try {

                    int numSequences = (sequenceNumber > lastSequenceReceived) ? (sequenceNumber - lastSequenceReceived) :
                            (65535 - lastSequenceReceived) + sequenceNumber + 1;

                    if ((lostPacketPending + numSequences) < lostPacketRecoverySource.getMaxPacketLossAcceptable()) {

                        lostPacketRecoverySource.notifyLostPacket(lastSequenceReceived, numSequences - 1);
                        lostPacketPending += (numSequences - 1);

                        totalLossPacketRequested += numSequences;

                        //Log.v("IptvNokiaMediaService", "Lost packet detected: " + numSequences + " from " + lastSequenceReceived);

                    } else {

                        //Log.v("IptvNokiaMediaService", "Lost packet detected: " + numSequences + " from " + lastSequenceReceived);
                        //Log.v("IptvNokiaMediaService", "Lost packet permanently: " + lostPacketPending);

                        totalLossPacketDiscarded += lostPacketPending;

                        lostPacketRecoverySource.resetRecovery();
                        lostPacketPending = 0;
                    }

   //                 Log.v("IptvNokiaMediaService", "[" + s.format(new Date()) + "] Lost packet recovery stats: permanently=[" + totalLossPacketPermanent + "], " +
     //                       "requested=[" + totalLossPacketRequested + "], recovered=[" + totalLossPacketRecovered + "], " +
       //                     "discarded=["+totalLossPacketDiscarded + "]");

                } catch (IOException ex) {
                }
            //} else {
                //Log.v("IptvNokiaMediaService", "[mcast] packet received: " + sequenceNumber);
            }
        }

        if (!channelChangeSwitched) {
            switch (streamType) {
                case STREAM_AUDIO_TYPE:
                    if (firstAudioSequence == -1) {
                        firstAudioSequence = sequenceNumber;
                    }

                    break;

                case STREAM_VIDEO_TYPE:
                    if (firstVideoSequence == -1) {
                        firstVideoSequence = sequenceNumber;
                    }

                    break;
            }
        }

        lastSequenceReceived = sequenceNumber;
    }


    // IptvFastChannelChangeSource.EventListener implementation.

    @Override
    public boolean dataOnFastChannelSource() {
        return fastChannelChangeSource.hasDataAvailable();
    }

    @Override
    public void onFastChannelChangeLoadCompleted() {
        //listener.onLiveChannelSourceError();
    }

    @Override
    public void onFastChannelChangeLoadCanceled() {
        listener.onFastChannelSourceError();
    }

    @Override
    public void onFastChannelChangeLoadError() {
        listener.onFastChannelSourceError();
    }

    @Override
    public void onFastChannelChangeSynchronizationSource(long ssrc) {
        if (lostPacketRecovery) {
            lostPacketRecoverySource.setSsrcSource(ssrc);
        }
    }

    @Override
    public void onFastChannelChangeStreamInfoRefresh(int streamType, int sequenceNumber) {
        try {

            switch (streamType) {
                case STREAM_AUDIO_TYPE:

                    if (firstAudioSequence == sequenceNumber) {
                        fastChannelChangeSource.disableAudioStream();
                        audioSynchronized = true;

                        if (videoSynchronized) {
                            channelChangeSwitched = true;
                            fastChannelChangeSource.close();
                            listener.onChannelChangeSwitched();
                        }

                    } else if (videoSynchronized) {
                        audioSynchronized = true;
                        channelChangeSwitched = true;
                        fastChannelChangeSource.disableAudioStream();
                        fastChannelChangeSource.close();
                        listener.onChannelChangeSwitched();
                    }

                    break;

                case STREAM_VIDEO_TYPE:

                    if (firstVideoSequence == sequenceNumber) {
                        fastChannelChangeSource.disableVideoStream();
                        videoSynchronized = true;

                        if (audioSynchronized) {
                            channelChangeSwitched = true;
                            fastChannelChangeSource.close();
                            listener.onChannelChangeSwitched();
                        }
                    }

                    break;
            }
        } catch (IOException ex) {}
    }

    @Override
    public void onFastChannelChangeSwitchingReady() {
        if (!liveChannelSource.isOpened()) {
            listener.onChannelChangeSwitchingReady();
        }
    }

    // IptvLostPacketRecoverySource.EventListener implementation.

    @Override
    public void onLostPacketRecoveryStreamInfoRefresh(int sequenceNumber) {

    }

    @Override
    public void onLostPacketRecoveryLoadCanceled() {
        listener.onRecoveryChannelSourceError();
    }

    @Override
    public void onLostPacketRecoveryLoadError() {
        listener.onRecoveryChannelSourceError();
    }


    // IptvMediaService implementation.

    @Override
    public boolean dataLossDetected() {
        return (lostPacketPending > 0);
    }

    @Override
    public boolean dataOnRecoveryChannelSource() {
        return lostPacketRecoverySource.hasDataAvailable();
    }

    @Override
    public boolean earlierLossRecovered() {
        int retryFirstSequenceNumber = lostPacketRecoverySource.getCurrentSequenceNumber();
        int liveFirstSequenceNumber = liveChannelSource.getCurrentSequenceNumber();

        long retryTimeStamp = lostPacketRecoverySource.getCurrentTimeStamp();
        long liveTimeStamp = liveChannelSource.getCurrentTimeStamp();

        if (retryFirstSequenceNumber < liveFirstSequenceNumber) {
            if (retryTimeStamp <= liveTimeStamp) {
                return true;
            }
        } else if (retryTimeStamp < liveTimeStamp) {
            return true;
        }

        return false;
    }
}
