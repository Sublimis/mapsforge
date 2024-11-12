/*
 * Copyright 2017-2022 usrusr
 * Copyright 2019 devemux86
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

import org.mapsforge.core.graphics.Canvas;
import org.mapsforge.core.graphics.GraphicFactory;
import org.mapsforge.core.graphics.HillshadingBitmap;
import org.mapsforge.map.layer.hills.HillShadingUtils.BlockingSumLimiter;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * immutably configured, does the work for {@link MemoryCachingHgtReaderTileSource}
 */
public class HgtCache {
    private static final Logger LOGGER = Logger.getLogger(HgtCache.class.getName());

    // Should be lower-case
    public static final String ZipFileExtension = "zip";
    public static final String HgtFileExtension = "hgt";
    public static final String DotZipFileExtension = "." + ZipFileExtension;
    public static final String DotHgtFileExtension = "." + HgtFileExtension;

    protected final DemFolder mDemFolder;
    protected final ShadingAlgorithm mShadingAlgorithm;
    protected final int mPadding;

    protected final GraphicFactory mGraphicsFactory;
    protected final Lru mLruCache;
    protected final LazyFuture<Map<TileKey, HgtFileInfo>> mHgtFiles;
    protected final BlockingSumLimiter mBlockingSumLimiter = new BlockingSumLimiter();

    protected final List<String> problems = new ArrayList<>();

    public HgtCache(DemFolder demFolder, GraphicFactory graphicsFactory, int padding, ShadingAlgorithm algorithm, int cacheMinCount, int cacheMaxCount, long cacheMaxBytes) {
        mDemFolder = demFolder;
        mGraphicsFactory = graphicsFactory;
        mShadingAlgorithm = algorithm;
        mPadding = padding;

        mLruCache = new Lru(cacheMinCount, cacheMaxCount, cacheMaxBytes);

        mHgtFiles = new LazyFuture<Map<TileKey, HgtFileInfo>>() {
            @Override
            protected Map<TileKey, HgtFileInfo> calculate() {
                final Map<TileKey, HgtFileInfo> map = new HashMap<>();
                final String regex = ".*([ns])(\\d{1,2})([ew])(\\d{1,3})\\.(?:(" + HgtFileExtension + ")|(" + ZipFileExtension + "))";
                final Matcher matcher = Pattern
                        .compile(regex, Pattern.CASE_INSENSITIVE)
                        .matcher("");
                indexFolder(HgtCache.this.mDemFolder, matcher, map, problems);
                return map;
            }

            void indexFile(DemFile file, Matcher matcher, Map<TileKey, HgtFileInfo> map, List<String> problems) {
                final String name = file.getName();
                if (matcher
                        .reset(name)
                        .matches()) {
                    final int northsouth = Integer.parseInt(matcher.group(2));
                    final int eastwest = Integer.parseInt(matcher.group(4));

                    final int north = "n".equalsIgnoreCase(matcher.group(1)) ? northsouth : -northsouth;
                    final int east = "e".equalsIgnoreCase(matcher.group(3)) ? eastwest : -eastwest;

                    final long length = file.getSize();
                    final long heights = length / 2;
                    final long sqrt = (long) Math.sqrt(heights);
                    if (heights == 0 || sqrt * sqrt != heights) {
                        if (problems != null)
                            problems.add(file + " length in shorts (" + heights + ") is not a square number");
                        return;
                    }

                    final TileKey tileKey = new TileKey(north, east);
                    final HgtFileInfo existing = map.get(tileKey);
                    if (existing == null || existing.mSize < length) {
                        map.put(tileKey, new HgtFileInfo(file, north - 1, east, north, east + 1, length));
                    }
                }
            }

            void indexFolder(DemFolder folder, Matcher matcher, Map<TileKey, HgtFileInfo> map, List<String> problems) {
                for (DemFile demFile : folder.files()) {
                    indexFile(demFile, matcher, map, problems);
                }
                for (DemFolder sub : folder.subs()) {
                    indexFolder(sub, matcher, map, problems);
                }
            }
        };
    }

    public HillshadingBitmap getHillshadingBitmap(int northInt, int eastInt, int zoomLevel, double pxPerLat, double pxPerLon) throws InterruptedException, ExecutionException {
        final HgtFileInfo hgtFileInfo = mHgtFiles
                .get()
                .get(new TileKey(northInt, eastInt));

        if (hgtFileInfo == null) {
            return null;
        }

        HillshadingBitmap hillshadingBitmap = null;

        final long outputSizeEstimate = mShadingAlgorithm.getOutputSizeBytes(hgtFileInfo, mPadding, zoomLevel, pxPerLat, pxPerLon);

        // Blocking sum limiter is used to prevent cache hammering, and resulting excess memory usage and possible OOM exceptions in extreme situations.
        // This can happen when many future.get() calls (like the one below) are made concurrently without any limits.
        mBlockingSumLimiter.add(outputSizeEstimate, mLruCache.mMaxBytes);
        try {
            final HgtFileLoadFuture future = hgtFileInfo.getBitmapFuture(HgtCache.this, mShadingAlgorithm, mPadding, zoomLevel, pxPerLat, pxPerLon);

            if (false == future.isDone()) {
                mLruCache.ensureEnoughSpace(outputSizeEstimate);
            }

            // This must be called before...
            hillshadingBitmap = future.get();

            // ...before this.
            mLruCache.markUsed(future);
        } finally {
            mBlockingSumLimiter.subtract(outputSizeEstimate);
        }

        return hillshadingBitmap;
    }

    protected HgtFileLoadFuture createHgtFileLoadFuture(HgtFileInfo hgtFileInfo, int padding, int zoomLevel, double pxPerLat, double pxPerLon) {
        return new HgtFileLoadFuture(hgtFileInfo, padding, zoomLevel, pxPerLat, pxPerLon);
    }

    public void indexOnThread() {
        mHgtFiles.withRunningThread();
    }

    public static void mergeSameSized(HillshadingBitmap center, HillshadingBitmap neighbor, HillshadingBitmap.Border border, int padding, Canvas copyCanvas) {
        final HillshadingBitmap source = neighbor;
        final HillshadingBitmap sink = center;

        // Synchronized to prevent using the bitmap while we write to it (see CanvasRasterer)
        synchronized (sink.getMutex()) {
            copyCanvas.setBitmap(sink);

            switch (border) {
                case WEST:
                    copyCanvas.setClip(0, padding, padding, sink.getHeight() - 2 * padding, true);
                    copyCanvas.drawBitmap(source, -sink.getWidth() + 2 * padding, 0);
                    break;
                case EAST:
                    copyCanvas.setClip(sink.getWidth() - padding, padding, padding, sink.getHeight() - 2 * padding, true);
                    copyCanvas.drawBitmap(source, sink.getWidth() - 2 * padding, 0);
                    break;
                case NORTH:
                    copyCanvas.setClip(padding, 0, sink.getWidth() - 2 * padding, padding, true);
                    copyCanvas.drawBitmap(source, 0, -sink.getHeight() + 2 * padding);
                    break;
                case SOUTH:
                    copyCanvas.setClip(padding, sink.getHeight() - padding, sink.getWidth() - 2 * padding, padding, true);
                    copyCanvas.drawBitmap(source, 0, sink.getHeight() - 2 * padding);
                    break;
            }
        }
    }

    public static boolean isFileNameZip(final String fileName) {
        boolean retVal = false;

        if (fileName != null) {
            retVal = fileName
                    .toLowerCase()
                    .endsWith(HgtCache.DotZipFileExtension);
        }

        return retVal;
    }

    public static boolean isFileNameHgt(final String fileName) {
        boolean retVal = false;

        if (fileName != null) {
            retVal = fileName
                    .toLowerCase()
                    .endsWith(HgtCache.DotHgtFileExtension);
        }

        return retVal;
    }

    public static boolean isFileZip(final File file) {
        return isFileNameZip(file.getName());
    }

    public static boolean isFileHgt(final File file) {
        return isFileNameHgt(file.getName());
    }

    public static boolean isFileZip(final DemFile file) {
        return isFileNameZip(file.getName());
    }

    protected class HgtFileLoadFuture extends LazyFuture<HillshadingBitmap> {
        protected final HgtFileInfo mHgtFileInfo;
        protected final int mPadding;
        protected final int mZoomLevel;
        protected final double mPxPerLat, mPxPerLon;
        protected volatile long mSizeBytes = 0;

        HgtFileLoadFuture(HgtFileInfo hgtFileInfo, int padding, int zoomLevel, double pxPerLat, double pxPerLon) {
            this.mHgtFileInfo = hgtFileInfo;
            this.mPadding = padding;
            this.mZoomLevel = zoomLevel;
            this.mPxPerLat = pxPerLat;
            this.mPxPerLon = pxPerLon;
        }

        public HillshadingBitmap calculate() {
            HillshadingBitmap output = null;

            final ShadingAlgorithm.RawShadingResult raw = mShadingAlgorithm.transformToByteBuffer(mHgtFileInfo, mPadding, mZoomLevel, mPxPerLat, mPxPerLon);

            if (raw != null) {
                output = mGraphicsFactory.createMonoBitmap(raw.width, raw.height, raw.bytes, raw.padding, mHgtFileInfo);

                if (output != null) {
                    mSizeBytes = output.getSizeBytes();
                } else {
                    mSizeBytes = 0;
                }
            } else {
                mSizeBytes = 0;
            }

            return output;
        }

        public long getCacheTag() {
            return mShadingAlgorithm.getCacheTag(this.mHgtFileInfo, mPadding, this.mZoomLevel, this.mPxPerLat, this.mPxPerLon);
        }

        public long getSizeBytes() {
            return mSizeBytes;
        }
    }

    protected static final class TileKey {
        final int north;
        final int east;

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            TileKey tileKey = (TileKey) o;

            return north == tileKey.north && east == tileKey.east;
        }

        @Override
        public int hashCode() {
            int result = north;
            result = 31 * result + east;
            return result;
        }

        TileKey(int north, int east) {
            this.east = east;
            this.north = north;
        }
    }

    protected static class Lru {
        protected final int mMinCount, mMaxCount;
        protected final long mMaxBytes;
        protected final Deque<HgtFileLoadFuture> mLruSet = new ArrayDeque<>();
        protected final AtomicLong mSizeBytes = new AtomicLong(0);

        protected Lru(int minCount, int maxCount, long maxBytes) {
            mMinCount = minCount;
            mMaxCount = maxCount;
            mMaxBytes = maxBytes;
        }

        /**
         * Note: The Future should be completed by the time this is called.
         * This can be ensured by calling this method AFTER at least one call to future.get() elsewhere in the same thread.
         *
         * @param freshlyUsed the entry that should be marked as freshly used
         */
        public void markUsed(HgtFileLoadFuture freshlyUsed) {
            if (mMaxBytes > 0 && freshlyUsed != null) {

                final long sizeBytes = freshlyUsed.getSizeBytes();

                synchronized (mLruSet) {
                    if (mLruSet.remove(freshlyUsed)) {
                        mSizeBytes.addAndGet(-sizeBytes);
                    }

                    if (mLruSet.add(freshlyUsed)) {
                        mSizeBytes.addAndGet(sizeBytes);
                    }
                }

                manageSize();
            }
        }

        protected void manageSize() {
            synchronized (mLruSet) {
                while (mLruSet.size() > mMaxCount || (mLruSet.size() > mMinCount && mSizeBytes.get() > mMaxBytes)) {
                    removeFirst();
                }
            }
        }

        public void removeFirst() {
            synchronized (mLruSet) {
                final HgtFileLoadFuture future = mLruSet.pollFirst();

                if (future != null) {
                    final long sizeBytes = future.getSizeBytes();
                    mSizeBytes.addAndGet(-sizeBytes);
                }
            }
        }

        public void ensureEnoughSpace(long bytes) {
            synchronized (mLruSet) {
                while (false == mLruSet.isEmpty() && bytes + mSizeBytes.get() > mMaxBytes) {
                    removeFirst();
                }
            }
        }
    }
}
