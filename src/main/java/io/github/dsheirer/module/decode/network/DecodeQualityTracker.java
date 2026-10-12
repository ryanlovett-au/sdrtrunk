/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.network;

import java.util.function.LongSupplier;

/**
 * Tracks the decode quality of a channel as the share of the received bit stream (airtime) that decoded into valid
 * (CRC/FEC passing) messages over a sliding window (default 10 seconds).
 *
 * The message framers account for the whole bit stream: every framed message (valid or not) occupies a known amount
 * of airtime, and the stretches where no sync was detected are reported as sync loss messages that carry the number of
 * bits processed without a message.  The quality is therefore:
 *
 * <pre>
 *     quality = valid bits / (valid bits + invalid bits + sync loss bits)
 * </pre>
 *
 * where the bits for a message are the airtime of the message on the channel (including its sync and framing), as
 * supplied by the protocol decoder state.  Unlike a simple valid/total message ratio, this also counts the messages
 * that were missed entirely (no sync), so a fading control channel reads low rather than 100%.  On a continuous control
 * channel with a perfect signal the quality is 100%; with no decodable signal it is 0%.
 *
 * Messages are bucketed (1 second buckets) by their own timestamps, so that the measure also works for recordings
 * replayed faster than real time.  The quality is UNKNOWN when nothing has been received in the window, or when no
 * message has been received for the stale period (by the wall clock), e.g. after the channel stops.
 *
 * Thread safe: updated by the decoder thread and read by the user interface.
 */
public class DecodeQualityTracker
{
    /**
     * Quality value when the quality is not known.
     */
    public static final int UNKNOWN = -1;
    /**
     * Default sliding window duration.
     */
    public static final long DEFAULT_WINDOW_MS = 10_000;
    /**
     * Default wall clock time without any message after which the quality is unknown.
     */
    public static final long DEFAULT_STALE_MS = 3_000;
    private static final int BUCKET_COUNT = 10;

    private final long mBucketMs;
    private final long mStaleMs;
    private final LongSupplier mClock;
    private final long[] mBucketIndex = new long[BUCKET_COUNT];
    private final long[] mValidBits = new long[BUCKET_COUNT];
    private final long[] mFailedBits = new long[BUCKET_COUNT];
    private boolean mStarted;
    private long mNewestBucket;
    private long mLastUpdate;

    /**
     * Constructs an instance with the default window and stale period, using the system clock.
     */
    public DecodeQualityTracker()
    {
        this(DEFAULT_WINDOW_MS, DEFAULT_STALE_MS, System::currentTimeMillis);
    }

    /**
     * Constructs an instance
     * @param windowMs duration of the sliding window (divided into 10 buckets)
     * @param staleMs wall clock time without any message after which the quality is unknown
     * @param clock providing the current wall clock time in milliseconds
     */
    public DecodeQualityTracker(long windowMs, long staleMs, LongSupplier clock)
    {
        mBucketMs = Math.max(1, windowMs / BUCKET_COUNT);
        mStaleMs = staleMs;
        mClock = clock;
        clearBuckets();
    }

    /**
     * Records a framed message.
     * @param timestamp of the message
     * @param bits of airtime occupied by the message (ignored when zero or negative)
     * @param valid true if the message passed its CRC/FEC checks
     */
    public synchronized void message(long timestamp, int bits, boolean valid)
    {
        if(bits <= 0)
        {
            return;
        }

        int slot = getSlot(timestamp);

        if(valid)
        {
            mValidBits[slot] += bits;
        }
        else
        {
            mFailedBits[slot] += bits;
        }

        mLastUpdate = mClock.getAsLong();
    }

    /**
     * Records bits that were processed without detecting a message (sync loss).
     * @param timestamp of the sync loss
     * @param bits processed without a message
     */
    public void syncLoss(long timestamp, int bits)
    {
        message(timestamp, bits, false);
    }

    /**
     * Bucket slot for the timestamp, advancing the window or restarting it when the timestamp jumps backwards beyond
     * the window (e.g. a different recording).
     */
    private int getSlot(long timestamp)
    {
        long bucket = Math.floorDiv(timestamp, mBucketMs);

        if(!mStarted || bucket > mNewestBucket)
        {
            mNewestBucket = bucket;
            mStarted = true;
        }
        else if(bucket <= mNewestBucket - BUCKET_COUNT)
        {
            clearBuckets();
            mNewestBucket = bucket;
        }

        int slot = (int)Math.floorMod(bucket, BUCKET_COUNT);

        if(mBucketIndex[slot] != bucket)
        {
            mBucketIndex[slot] = bucket;
            mValidBits[slot] = 0;
            mFailedBits[slot] = 0;
        }

        return slot;
    }

    private void clearBuckets()
    {
        for(int x = 0; x < BUCKET_COUNT; x++)
        {
            mBucketIndex[x] = Long.MIN_VALUE;
            mValidBits[x] = 0;
            mFailedBits[x] = 0;
        }
    }

    /**
     * Decode quality over the sliding window.
     * @return 0-100 percent, or UNKNOWN.
     */
    public synchronized int getQuality()
    {
        if(!mStarted || mClock.getAsLong() - mLastUpdate > mStaleMs)
        {
            return UNKNOWN;
        }

        long valid = 0;
        long failed = 0;

        for(int x = 0; x < BUCKET_COUNT; x++)
        {
            if(mBucketIndex[x] > mNewestBucket - BUCKET_COUNT && mBucketIndex[x] <= mNewestBucket)
            {
                valid += mValidBits[x];
                failed += mFailedBits[x];
            }
        }

        long total = valid + failed;

        if(total == 0)
        {
            return UNKNOWN;
        }

        return (int)Math.round(100.0 * valid / total);
    }

    /**
     * Clears the window (e.g. when the channel is reset or retuned).
     */
    public synchronized void reset()
    {
        clearBuckets();
        mStarted = false;
    }
}
