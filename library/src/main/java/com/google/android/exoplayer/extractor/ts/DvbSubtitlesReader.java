package com.google.android.exoplayer.extractor.ts;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.extractor.TrackOutput;
import com.google.android.exoplayer.util.MimeTypes;
import com.google.android.exoplayer.util.ParsableByteArray;
import android.util.Log;


public class DvbSubtitlesReader extends ElementaryStreamReader {

    private static final String TAG= "DVBSubs";

    private long sampleTimeUs;
    private int totalBytesWritten;
    private boolean writingSample;
    private long pesTimeUs;

    public DvbSubtitlesReader(TrackOutput output, String language) {
        super(output);
        output.format(MediaFormat.createDvbSubsFormat(MimeTypes.APPLICATION_DVBSUBS, MediaFormat.NO_VALUE,
                C.UNKNOWN_TIME_US, language.toUpperCase())); //FIXME primer language debe sustituirse por trackId
    }

    @Override
    public void seek() {
        writingSample = false;
    }

    @Override
    public void packetFinished() {
        //Log.d(TAG, "dvb subs packet finished");
        output.sampleMetadata(sampleTimeUs, C.SAMPLE_FLAG_SYNC, totalBytesWritten, 0, null);
        writingSample = false;
    }

    @Override
    public void packetStarted(long pesTimeUs, boolean dataAlignmentIndicator) {
        writingSample = true;
        sampleTimeUs = pesTimeUs;
        totalBytesWritten = 0;
    }

    @Override
    public void consume(ParsableByteArray data) {
        if (writingSample) {
            totalBytesWritten += data.bytesLeft();
            output.sampleData(data, data.bytesLeft());
            //Log.d(TAG, "bytesWritten=" + totalBytesWritten);
        }
    }
}
