package com.google.android.exoplayer2.upstream;


import java.io.IOException;

public interface DataSinkSource extends DataSource {
    /**
     * Consumes the provided data.
     *
     * @param buffer The buffer from which data should be consumed.
     * @param offset The offset of the data to consume in {@code buffer}.
     * @param length The length of the data to consume, in bytes.
     * @throws IOException If an error occurs writing to the sink.
     */
    void write(byte[] buffer, int offset, int length) throws IOException;
}
