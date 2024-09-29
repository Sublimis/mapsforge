/*
 * Copyright 2024 Sublimis
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.mapsforge.map.layer.hills;

import org.mapsforge.core.util.IOUtils;
import org.mapsforge.map.layer.hills.HgtCache.HgtFileInfo;
import org.mapsforge.map.layer.hills.HillShadingUtils.Awaiter;
import org.mapsforge.map.layer.hills.HillShadingUtils.HillShadingThreadPool;
import org.mapsforge.map.layer.hills.HillShadingUtils.ShortArraysPool;
import org.mapsforge.map.layer.hills.HillShadingUtils.SilentFutureTask;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * <p>
 * Special abstract implementation of hill shading algorithm where input data can be divided into parts
 * that are processed in parallel by other threads.
 * </p>
 * <p>
 * The implementation is such that there are 1 + N (N>=0) "producer" threads (ie. the caller thread and N additional threads)
 * that read from the input and do the synchronization, and M (>=0) "consumer" threads that do the computations.
 * It should be emphasized that every caller thread has its own thread pool: Thread pools are not shared.
 * </p>
 * <p>
 * Special attention is paid to reducing memory consumption.
 * The producer thread will throttle itself and stop reading until the computation catches up.
 * If this happens, a {@link #notifyReadingPaced(int)} will be called.
 * </p>
 * <p>
 * Rough estimate of the <em>maximum</em> memory used per caller is as follows:
 * <br />
 * <br />
 * max_bytes_used = {@link #ElementsPerComputingTask} * (1 + 2 * M) * (1 + N) * {@link Short#BYTES}
 * <br />
 * <br />
 * By default, with two reading threads and one additional computing thread used, this is around 2 * 96000 bytes (cca 200 kB) max memory usage per caller.
 * If the computations are fast enough, the real memory usage is usually going to be several times smaller.
 * </p>
 */
public abstract class AThreadedHillShading extends AbsShadingAlgorithmDefaults {

    /**
     * Default number of additional reading threads ("producer" threads) per caller thread.
     * Number N (>0) means there will be N additional threads (per caller thread) that will do the reading,
     * while 0 means that only the caller thread will do the reading.
     */
    public static final int ReadingThreadsCountDefault = 1;

    /**
     * Default number of additional computing threads ("consumer" threads) per caller thread.
     * Number N (>0) means there will be N additional threads (per caller thread) that will do the computing,
     * while 0 means that producer thread(s) will also do the computing.
     */
    public static final int ComputingThreadsCountDefault = 1;

    /**
     * Default name prefix for additional threads created and used by hill shading. A numbered suffix will be appended.
     */
    public final String ThreadPoolName = "MapsforgeHillShading";

    /**
     * Approximate number of unit elements that each computing task will process.
     * The actual number is calculated during execution and can be slightly different.
     */
    protected final int ElementsPerComputingTask = 16000;

    /**
     * Number of additional "producer" threads that will do the reading (per caller thread), >= 0.
     */
    protected final int mReadingThreadsCount;

    /**
     * Number of additional "consumer" threads that will do the computations (per caller thread), >= 0.
     */
    protected final int mComputingThreadsCount;

    /**
     * Max number of active computing tasks per caller; if this limit is exceeded the reading will be throttled.
     * It is computed as (1 + 2 * {@link #mComputingThreadsCount}) * (1 + {@link #mReadingThreadsCount}) by default.
     * An active task is a task that is currently being processed or has been prepared and is waiting to be processed.
     */
    protected final int mActiveTasksCountMax;

    protected static final ThreadLocal<AtomicReference<HillShadingThreadPool>> mThreadPool = new ThreadLocal<AtomicReference<HillShadingThreadPool>>() {
        @Override
        protected AtomicReference<HillShadingThreadPool> initialValue() {
            return new AtomicReference<>(null);
        }
    };

    protected volatile boolean mStopSignal = false;

    /**
     * @param readingThreadsCount   Number of "producer" threads that will do the reading, >= 0.
     *                              Number N (>0) means there will be N additional threads (per caller thread) that will do the reading,
     *                              while 0 means that only the caller thread will do the reading.
     *                              The only time you'd want to set this to zero is when your data source does not support skipping,
     *                              ie. the data source is not a file and/or its {@link InputStream#skip(long)} is inefficient.
     *                              The default is 1.
     * @param computingThreadsCount Number of "consumer" threads that will do the computations, >= 0.
     *                              Number M (>0) means there will be M additional threads (per caller thread) that will do the computing,
     *                              while 0 means that producer thread(s) will also do the computing.
     *                              The only times you'd want to set this to zero are when memory conservation is a top priority
     *                              or when you're running on a single-threaded system.
     *                              The default is 1.
     */
    public AThreadedHillShading(final int readingThreadsCount, final int computingThreadsCount) {
        super();

        mReadingThreadsCount = Math.max(0, readingThreadsCount);
        mComputingThreadsCount = Math.max(0, computingThreadsCount);
        mActiveTasksCountMax = (1 + 2 * mComputingThreadsCount) * (1 + mReadingThreadsCount);
    }

    /**
     * Uses one separate computing thread by default.
     */
    public AThreadedHillShading() {
        this(ReadingThreadsCountDefault, ComputingThreadsCountDefault);
    }

    /**
     * Process one unit element, a smallest subdivision of the input, which consists of four points on a "square"
     * with vertices in NW-SW-SE-NE directions from the center.
     *
     * @param nw              North-west value. [meters]
     * @param sw              South-west value. [meters]
     * @param se              South-east value. [meters]
     * @param ne              North-east value. [meters]
     * @param mpe             Meters per unit element, ie. the length of one side of the unit element. [meters]
     * @param outputIx        Output array index, ie. index on the output array where.
     * @param computingParams Various parameters that are to be used during computations.
     * @return Updated {@code outputIx}, to be used for the next iteration.
     * @see ComputingParams
     */
    protected abstract int processOneUnitElement(double nw, double sw, double se, double ne, double mpe, int outputIx, ComputingParams computingParams);

    /**
     * {@inheritDoc}
     */
    @Override
    protected byte[] convert(InputStream inputStream, int dummyAxisLen, int dummyRowLen, final int padding, HgtFileInfo fileInfo) throws IOException {
        return doTheWork(padding, fileInfo);
    }

    protected byte[] doTheWork(final int padding, final HgtFileInfo fileInfo) throws IOException {
        final int outputAxisLen = getOutputAxisLen(fileInfo);
        final int inputAxisLen = getInputAxisLen(fileInfo);
        final int outputWidth = outputAxisLen + 2 * padding;
        final int lineBufferSize = inputAxisLen + 1;
        final double northUnitDistancePerLine = getLatUnitDistance(fileInfo.northLat(), inputAxisLen) / inputAxisLen;
        final double southUnitDistancePerLine = getLatUnitDistance(fileInfo.southLat(), inputAxisLen) / inputAxisLen;

        final byte[] output = new byte[outputWidth * outputWidth];

        if (isNotStopped()) {
            final AtomicInteger activeTasksCount = new AtomicInteger(0);
            final ShortArraysPool inputArraysPool = new ShortArraysPool(1 + mActiveTasksCountMax), lineBuffersPool = new ShortArraysPool(1 + mActiveTasksCountMax);

            final int readingTasksCount = 1 + mReadingThreadsCount;

            final int computingTasksCount = Math.max(readingTasksCount, determineComputingTasksCount(inputAxisLen));
            final int linesPerComputeTask = inputAxisLen / computingTasksCount;

            final int computeTasksPerReadingTask = computingTasksCount / readingTasksCount;
//            final int linesPerReadingTask = linesPerComputeTask * computeTasksPerReadingTask;
            final SilentFutureTask[] readingTasks = new SilentFutureTask[readingTasksCount];

            for (int readingTaskIndex = 0; readingTaskIndex < readingTasksCount; readingTaskIndex++) {

                final int computingTaskFrom, computingTaskTo;
                {
                    computingTaskFrom = computeTasksPerReadingTask * readingTaskIndex;

                    if (readingTaskIndex < readingTasksCount - 1) {
                        computingTaskTo = computingTaskFrom + computeTasksPerReadingTask;
                    }
                    else {
                        computingTaskTo = computingTasksCount;
                    }
                }

                InputStream readStream = null;
                try {
                    readStream = fileInfo
                            .getFile()
                            .asStream();
                }
                catch (IOException e) {
                    LOGGER.log(Level.SEVERE, e.toString(), e);
                }

                if (readingTaskIndex > 0) {
                    final long skipAmount = (long) lineBufferSize * linesPerComputeTask * computingTaskFrom - lineBufferSize;

                    try {
                        HillShadingUtils.skipNBytes(readStream, skipAmount * Short.SIZE / Byte.SIZE);
                    }
                    catch (IOException e) {
                        LOGGER.log(Level.SEVERE, e.toString(), e);
                    }
                }

                final ComputingParams computingParams = new ComputingParams.Builder()
                        .setInputStream(readStream)
                        .setOutput(output)
                        .setAwaiter(new Awaiter())
                        .setInputAxisLen(inputAxisLen)
                        .setOutputAxisLen(outputAxisLen)
                        .setOutputWidth(outputWidth)
                        .setLineBufferSize(lineBufferSize)
                        .setPadding(padding)
                        .setNorthUnitDistancePerLine(northUnitDistancePerLine)
                        .setSouthUnitDistancePerLine(southUnitDistancePerLine)
                        .setActiveTasksCount(activeTasksCount)
                        .setInputArraysPool(inputArraysPool)
                        .setLineBuffersPool(lineBuffersPool)
                        .build();

                final SilentFutureTask readingTask = getReadingTask(readStream, computingTasksCount, computingTaskFrom, computingTaskTo, linesPerComputeTask, computingParams);
                readingTasks[readingTaskIndex] = readingTask;

                if (readingTaskIndex < readingTasksCount - 1) {
                    postToThreadPoolOrRun(readingTask);
                }
                else {
                    readingTask.run();
                }
            }

            if (readingTasksCount > 1) {
                for (final SilentFutureTask readingTask : readingTasks) {
                    readingTask.get();
                }
            }
        }

        return output;
    }

    /**
     * If there are already too many active computing tasks, this will cause the caller to wait
     * until at least one computing task completes, to conserve memory.
     */
    protected void paceReading(final Awaiter awaiter, final AtomicInteger activeTasksCount, final int activeTasksCountMax) {
        if (mComputingThreadsCount > 0) {
            if (false == HillShadingUtils.atomicIncreaseIfLess(activeTasksCount, activeTasksCountMax)) {
                notifyReadingPaced(activeTasksCount.get());

                awaiter.doWait(new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return HillShadingUtils.atomicIncreaseIfLess(activeTasksCount, activeTasksCountMax);
                    }
                });
            }
        }
    }

    /**
     * Called when there are already too many active computing tasks (being processed or waiting in queue), so the reading must be slowed down to conserve memory.
     * If you get this notification, you may consider increasing the number of computing threads.
     *
     * @param activeComputingTasksCount How many computing tasks are waiting for completion at the time this is called.
     */
    protected void notifyReadingPaced(final int activeComputingTasksCount) {
    }

    /**
     * Decides a total number of computing tasks will be used for the given input parameters.
     */
    protected int determineComputingTasksCount(final int inputAxisLen) {
        final int retVal;

        if (mComputingThreadsCount > 0) {
            final long computingTasksCount = Math.max(1L, Math.min(inputAxisLen / 2, (long) inputAxisLen * inputAxisLen / ElementsPerComputingTask));

            retVal = (int) computingTasksCount;
        }
        else {
            // If multi threading is not used, return a single task for the whole job
            retVal = 1;
        }

        return retVal;
    }

    protected int threadCount() {
        return mComputingThreadsCount + mReadingThreadsCount;
    }

    /**
     * The {@code Runnable} provided will be sent to the thread pool, or run on the calling thread if the thread pool rejects it (an unlikely event).
     *
     * @param code A code to run.
     */
    protected void postToThreadPoolOrRun(final Runnable code) {
        boolean status = false;

        final AtomicReference<HillShadingThreadPool> threadPoolReference = mThreadPool.get();

        if (threadPoolReference != null) {
            if (threadPoolReference.get() == null && threadCount() > 0) {
                synchronized (threadPoolReference) {
                    if (threadPoolReference.get() == null) {
                        threadPoolReference.set(createThreadPool());
                    }
                }
            }

            final HillShadingThreadPool threadPool = threadPoolReference.get();

            if (threadPool != null) {
                status = threadPool.execute(code);
            }
        }

        if (false == status) {
            if (code != null) {
                code.run();
            }
        }
    }

    protected HillShadingThreadPool createThreadPool() {
        final int threadCount = threadCount();
        return new HillShadingThreadPool(threadCount, threadCount, Integer.MAX_VALUE, 1, ThreadPoolName).start();
    }

    public short readNextFromStream(final InputStream is, final short[] input, final int inputIx, final int fallbackIxDelta) throws IOException {
        final int read1 = is.read();
        final int read2 = is.read();

        if (read1 != -1 && read2 != -1) {
            short read = (short) ((read1 << 8) | read2);

            if (read == Short.MIN_VALUE) {
                return input[inputIx - fallbackIxDelta];
            }

            return read;
        }
        else {
            return input[inputIx - fallbackIxDelta];
        }
    }

    /**
     * Default implementation always returns {@code true}.
     * Override to return a more meaningful value if needed, e.g. {@code return }!{@link #isStopped()}.
     *
     * @return {@code false} to stop processing. Default implementation always returns {@code true}.
     */
    protected boolean isNotStopped() {
        return true;
    }

    /**
     * Send a "stop" signal: Any active task will finish as soon as possible (possibly without completing),
     * and no new work will be done until a "continue" signal arrives.
     * Note: You should override {@link #isNotStopped()} if you need the stopping functionality.
     * Calling this without overriding {@link #isNotStopped()} will have no effect.
     */
    public void stopSignal() {
        mStopSignal = true;
    }

    /**
     * Send a "continue" signal: Allow new work to be done.
     * Note: You should override {@link #isNotStopped()} if you need the stopping functionality.
     * Calling this without overriding {@link #isNotStopped()} will have no effect.
     */
    public void continueSignal() {
        mStopSignal = false;
    }

    /**
     * Note: You should override {@link #isNotStopped()} if you need the stopping functionality.
     */
    public boolean isStopped() {
        return mStopSignal;
    }

    public SilentFutureTask getReadingTask(InputStream readStream, int computingTasksCount, int computingTaskFrom, int computingTaskTo, int linesPerComputeTask, ComputingParams computingParams) {
        return new SilentFutureTask(new ReadingTask(readStream, computingTasksCount, computingTaskFrom, computingTaskTo, linesPerComputeTask, computingParams));
    }

    public SilentFutureTask getComputingTask(int lineFrom, int lineTo, short[] input, short[] lineBuffer, ComputingParams computingParams) {
        return new SilentFutureTask(new ComputingTask(lineFrom, lineTo, input, lineBuffer, computingParams));
    }

    /**
     * A reading task which reads part of the input.
     */
    protected class ReadingTask implements Callable<Boolean> {
        protected final InputStream mInputStream;
        protected final int mComputingTasksCount, mComputingTaskFrom, mComputingTaskTo, mLinesPerCompTask;
        protected final ComputingParams mComputingParams;

        public ReadingTask(InputStream inputStream, int computingTasksCount, int taskFrom, int taskTo, int linesPerTask, ComputingParams computingParams) {
            mInputStream = inputStream;
            mComputingTasksCount = computingTasksCount;
            mComputingTaskFrom = taskFrom;
            mComputingTaskTo = taskTo;
            mLinesPerCompTask = linesPerTask;
            mComputingParams = computingParams;
        }

        @Override
        public Boolean call() {
            boolean retVal = false;

            try {
                if (mInputStream != null) {
                    final SilentFutureTask[] computingTasks = new SilentFutureTask[mComputingTaskTo - mComputingTaskFrom];

                    final int inputAxisLen = mComputingParams.mInputAxisLen;
                    final int lineBufferSize = mComputingParams.mLineBufferSize;
                    final AtomicInteger activeTasksCount = mComputingParams.mActiveTasksCount;
                    final Awaiter awaiter = mComputingParams.mAwaiter;
                    final ShortArraysPool inputArraysPool = mComputingParams.mInputArraysPool, lineBuffersPool = mComputingParams.mLineBuffersPool;

                    short[] lineBuffer = new short[lineBufferSize], lineBufferTmp = null;

                    for (int compTaskIndex = mComputingTaskFrom; compTaskIndex < mComputingTaskTo; compTaskIndex++) {
                        paceReading(awaiter, activeTasksCount, mActiveTasksCountMax);

                        if (compTaskIndex > mComputingTaskFrom) {
                            lineBuffer = lineBufferTmp;
                            lineBufferTmp = null;
                        }
                        else {
                            short last = 0;

                            // First line for the first task is done separately
                            for (int col = 0; col < lineBufferSize; col++) {
                                last = readNext(mInputStream, last);

                                lineBuffer[col] = last;
                            }
                        }

                        final int lineFrom, lineTo;
                        {
                            lineFrom = 1 + mLinesPerCompTask * compTaskIndex;

                            if (compTaskIndex < mComputingTasksCount - 1) {
                                lineTo = lineFrom + mLinesPerCompTask - 1;
                            }
                            else {
                                lineTo = inputAxisLen;
                            }
                        }

                        final short[] input;
                        {
                            if (compTaskIndex < mComputingTaskTo - 1) {
                                input = inputArraysPool.getArray(lineBufferSize * (lineTo - lineFrom + 1));
                                lineBufferTmp = lineBuffersPool.getArray(lineBufferSize);

                                int inputIx = 0;

                                // First line is done separately
                                for (; inputIx <= inputAxisLen && isNotStopped(); inputIx++) {
                                    input[inputIx] = readNextFromStream(mInputStream, lineBuffer, inputIx, 0);
                                }

                                for (int line = lineFrom + 1; line <= lineTo - 1 && isNotStopped(); line++) {
                                    // Inner loop, critical for performance
                                    for (int col = 0; col <= inputAxisLen; col++, inputIx++) {
                                        input[inputIx] = readNextFromStream(mInputStream, input, inputIx, lineBufferSize);
                                    }
                                }

                                // Last line is done separately
                                for (int col = 0; col <= inputAxisLen && isNotStopped(); col++, inputIx++) {
                                    final short point = readNextFromStream(mInputStream, input, inputIx, lineBufferSize);
                                    input[inputIx] = point;
                                    lineBufferTmp[col] = point;
                                }
                            }
                            else {
                                input = null;
                            }
                        }

                        final SilentFutureTask computingTask = getComputingTask(lineFrom, lineTo, input, lineBuffer, mComputingParams);
                        computingTasks[compTaskIndex - mComputingTaskFrom] = computingTask;

                        if (compTaskIndex < mComputingTaskTo - 1) {
                            postToThreadPoolOrRun(computingTask);
                        }
                        else {
                            computingTask.run();
                        }
                    }

                    if (computingTasks.length > 1) {
                        for (final SilentFutureTask computingTask : computingTasks) {
                            computingTask.get();
                        }
                    }
                }

                retVal = true;

            }
            catch (Exception e) {
                LOGGER.log(Level.WARNING, e.toString());
            }
            finally {
                IOUtils.closeQuietly(mInputStream);
            }

            return retVal;
        }
    }

    /**
     * A computing task which converts part of the input to part of the output, by calling
     * {@link #processOneUnitElement(double, double, double, double, double, int, ComputingParams)}
     * on all input unit elements from the given part.
     * The part will be the whole input and output, if multi threading is not used.
     */
    protected class ComputingTask implements Callable<Boolean> {
        protected final short[] mInput, mLineBuffer;
        protected final int mLineFrom, mLineTo;
        protected final ComputingParams mComputingParams;

        public ComputingTask(int lineFrom, int lineTo, short[] input, short[] lineBuffer, ComputingParams computingParams) {
            mInput = input;
            mLineBuffer = lineBuffer;
            mLineFrom = lineFrom;
            mLineTo = lineTo;
            mComputingParams = computingParams;
        }

        @Override
        public Boolean call() {
            boolean retVal = false;

            try {
                final int resolutionFactor = mComputingParams.mOutputAxisLen / mComputingParams.mInputAxisLen;

                // Must add two additional paddings (after possibly skipping a line) to get to a starting position of the next line
                final int outputIxIncrement = (resolutionFactor - 1) * mComputingParams.mOutputWidth + 2 * mComputingParams.mPadding;

                int outputIx = mComputingParams.mOutputWidth * mComputingParams.mPadding + mComputingParams.mPadding;
                outputIx += resolutionFactor * (mLineFrom - 1) * mComputingParams.mOutputWidth;

                if (mInput != null) {
                    int inputIx = 0;

                    // First line done separately, using the line buffer
                    {
                        short nw = mLineBuffer[inputIx];
                        short sw = mInput[inputIx++];

                        final double metersPerElement = mComputingParams.mSouthUnitDistancePerLine * mLineFrom + mComputingParams.mNorthUnitDistancePerLine * (mComputingParams.mInputAxisLen - mLineFrom);

                        for (int col = 1; col <= mComputingParams.mInputAxisLen && isNotStopped(); col++) {
                            final short ne = mLineBuffer[inputIx];
                            final short se = mInput[inputIx++];

                            outputIx = processOneUnitElement(nw, sw, se, ne, metersPerElement, outputIx, mComputingParams);

                            nw = ne;
                            sw = se;
                        }

                        outputIx += outputIxIncrement;
                    }

                    mComputingParams.mLineBuffersPool.recycleArray(mLineBuffer);

                    int offsetInputIx = inputIx - mComputingParams.mLineBufferSize;

                    for (int line = mLineFrom + 1; line <= mLineTo && isNotStopped(); line++) {
                        short nw = mInput[offsetInputIx++];
                        short sw = mInput[inputIx++];

                        final double metersPerElement = mComputingParams.mSouthUnitDistancePerLine * line + mComputingParams.mNorthUnitDistancePerLine * (mComputingParams.mInputAxisLen - line);

                        // Inner loop, critical for performance
                        for (int col = 1; col <= mComputingParams.mInputAxisLen; col++) {
                            final short ne = mInput[offsetInputIx++];
                            final short se = mInput[inputIx++];

                            outputIx = processOneUnitElement(nw, sw, se, ne, metersPerElement, outputIx, mComputingParams);

                            nw = ne;
                            sw = se;
                        }

                        outputIx += outputIxIncrement;
                    }

                    mComputingParams.mInputArraysPool.recycleArray(mInput);
                }
                else {
                    int lineBufferIx = 0;

                    for (int line = mLineFrom; line <= mLineTo && isNotStopped(); line++) {
                        if (lineBufferIx >= mComputingParams.mLineBufferSize) {
                            lineBufferIx = 0;
                        }

                        short nw = mLineBuffer[lineBufferIx];
                        short sw = readNextFromStream(nw);
                        mLineBuffer[lineBufferIx++] = sw;

                        final double metersPerElement = mComputingParams.mSouthUnitDistancePerLine * line + mComputingParams.mNorthUnitDistancePerLine * (mComputingParams.mInputAxisLen - line);

                        // Inner loop, critical for performance
                        for (int col = 1; col <= mComputingParams.mInputAxisLen; col++) {
                            final short ne = mLineBuffer[lineBufferIx];
                            final short se = readNextFromStream(ne);
                            mLineBuffer[lineBufferIx++] = se;

                            outputIx = processOneUnitElement(nw, sw, se, ne, metersPerElement, outputIx, mComputingParams);

                            nw = ne;
                            sw = se;
                        }

                        outputIx += outputIxIncrement;
                    }

                    mComputingParams.mLineBuffersPool.recycleArray(mLineBuffer);
                }

                retVal = true;

            }
            catch (Exception e) {
                LOGGER.log(Level.WARNING, e.toString());
            }
            finally {
                mComputingParams.mActiveTasksCount.decrementAndGet();
                mComputingParams.mAwaiter.doNotify();
            }

            return retVal;
        }

        protected short readNextFromStream(final short fallback) throws IOException {
            return readNext(mComputingParams.mInputStream, fallback);
        }
    }

    /**
     * Parameters that are used by a {@link AThreadedHillShading}.
     * An instance should be created using the provided builder, {@link Builder}.
     */
    public static class ComputingParams {
        public final InputStream mInputStream;
        public final byte[] mOutput;
        public final int mInputAxisLen;
        public final int mOutputAxisLen;
        public final int mOutputWidth;
        public final int mLineBufferSize;
        public final int mPadding;
        public final double mNorthUnitDistancePerLine;
        public final double mSouthUnitDistancePerLine;
        public final Awaiter mAwaiter;
        public final AtomicInteger mActiveTasksCount;
        public final ShortArraysPool mInputArraysPool, mLineBuffersPool;

        protected ComputingParams(final Builder builder) {
            mInputStream = builder.mInputStream;
            mOutput = builder.mOutput;
            mInputAxisLen = builder.mInputAxisLen;
            mOutputAxisLen = builder.mOutputAxisLen;
            mOutputWidth = builder.mOutputWidth;
            mLineBufferSize = builder.mLineBufferSize;
            mPadding = builder.mPadding;
            mNorthUnitDistancePerLine = builder.mNorthUnitDistancePerLine;
            mSouthUnitDistancePerLine = builder.mSouthUnitDistancePerLine;
            mAwaiter = builder.mAwaiter;
            mActiveTasksCount = builder.mActiveTasksCount;
            mInputArraysPool = builder.mInputArraysPool;
            mLineBuffersPool = builder.mLineBuffersPool;
        }

        public static class Builder {
            protected volatile InputStream mInputStream;
            protected volatile byte[] mOutput;
            protected volatile int mInputAxisLen;
            protected volatile int mOutputAxisLen;
            protected volatile int mOutputWidth;
            protected volatile int mLineBufferSize;
            protected volatile int mPadding;
            protected volatile double mNorthUnitDistancePerLine;
            protected volatile double mSouthUnitDistancePerLine;
            protected volatile Awaiter mAwaiter;
            protected volatile AtomicInteger mActiveTasksCount;
            protected volatile ShortArraysPool mInputArraysPool, mLineBuffersPool;

            public Builder() {
            }

            /**
             * Create the {@link ComputingParams} instance using parameter values from this builder.
             * All parameters used in computations should be explicitly set.
             *
             * @return New {@link ComputingParams} instance built using parameter values from this {@link Builder}
             */
            public ComputingParams build() {
                return new ComputingParams(this);
            }

            public Builder setInputStream(InputStream inputStream) {
                this.mInputStream = inputStream;
                return this;
            }

            public Builder setOutput(byte[] output) {
                this.mOutput = output;
                return this;
            }

            public Builder setInputAxisLen(int inputAxisLen) {
                this.mInputAxisLen = inputAxisLen;
                return this;
            }

            public Builder setOutputAxisLen(int outputAxisLen) {
                this.mOutputAxisLen = outputAxisLen;
                return this;
            }

            public Builder setOutputWidth(int outputWidth) {
                this.mOutputWidth = outputWidth;
                return this;
            }

            public Builder setLineBufferSize(int lineBufferSize) {
                this.mLineBufferSize = lineBufferSize;
                return this;
            }

            public Builder setPadding(int padding) {
                this.mPadding = padding;
                return this;
            }

            public Builder setNorthUnitDistancePerLine(double northUnitDistancePerLine) {
                this.mNorthUnitDistancePerLine = northUnitDistancePerLine;
                return this;
            }

            public Builder setSouthUnitDistancePerLine(double southUnitDistancePerLine) {
                this.mSouthUnitDistancePerLine = southUnitDistancePerLine;
                return this;
            }

            public Builder setAwaiter(Awaiter awaiter) {
                this.mAwaiter = awaiter;
                return this;
            }

            public Builder setActiveTasksCount(AtomicInteger activeTasksCount) {
                this.mActiveTasksCount = activeTasksCount;
                return this;
            }

            public Builder setInputArraysPool(ShortArraysPool inputArraysPool) {
                this.mInputArraysPool = inputArraysPool;
                return this;
            }

            public Builder setLineBuffersPool(ShortArraysPool lineBuffersPool) {
                this.mLineBuffersPool = lineBuffersPool;
                return this;
            }
        }
    }
}
