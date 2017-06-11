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

import android.util.Log;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The class implements a buffered IPTV media stream.
 */
public class IptvMediaStreamBuffer {

    private ByteBuffer buffer;

    private int[] sequences;
    private long[] timestamps;

    private int[] byteCounts;
    private int[] offsets;

    private int head;
    private int offset;
    private int capacity;

    private long total;

    private final int segmentSize;

    public IptvMediaStreamBuffer(int numMaxSegments, int segmentSize) {

        this.capacity = numMaxSegments;
        this.segmentSize = segmentSize;

        total = head = offset = 0;

        buffer = ByteBuffer.allocate(segmentSize * capacity);

        byteCounts = new int[capacity];
        offsets = new int[capacity];

        sequences = new int[capacity];
        timestamps = new long[capacity];
    }

    public synchronized int get(byte[] data, int offset, int length) throws BufferUnderflowException {
        int size = 0;

        if ((length > 0) && (byteCounts[head] > 0)) {

            size = Math.min(length, byteCounts[head]);

            if (byteCounts[head] >= size) {

                buffer.position((head * segmentSize) + offsets[head]);
                buffer.get(data, offset, size);

                byteCounts[head] -= size;
                offsets[head] += size;

                if (byteCounts[head] == 0) {
                    offsets[head] = 0;
                    sequences[head] = 0;
                    timestamps[head] = 0;
                    head = (head + 1) % capacity;
                }

                total-=size;
            }
        }

        return size;
    }

    public synchronized int put(int sequence, byte[] data, int length) throws BufferUnderflowException {
        return put(sequence, System.currentTimeMillis(), data, length);
    }

    public synchronized int put(int sequence, long timestamp, byte[] data, int length) throws BufferUnderflowException {
        if ((data != null) && (byteCounts[offset] == 0)) {

            offsets[offset] = 0;
            byteCounts[offset] = length;
            sequences[offset] = sequence;
            timestamps[offset] = timestamp;

            buffer.position(offset * segmentSize);
            buffer.put(data);

            total+=length;

            offset = (offset + 1) % capacity;

            return length;
        }

        return -1;
    }

    public synchronized void reset() {
        head = offset = 0;
        total = 0;

        buffer.rewind();

        Arrays.fill(offsets, 0);
        Arrays.fill(sequences, 0);
        Arrays.fill(byteCounts, 0);
        Arrays.fill(timestamps, 0);
    }

    public synchronized boolean hasDataAvailable() { return (total > 0); }

    public synchronized int getFirstSequenceNumber() {
        return (total > 0) ? sequences[head] : -1;
    }

    public synchronized long getFirstTimeStamp() {
        return timestamps[head];
    }
}