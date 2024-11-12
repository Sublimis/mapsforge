/*
 * Copyright 2017-2022 usrusr
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

import org.mapsforge.core.graphics.GraphicFactory;
import org.mapsforge.core.graphics.HillshadingBitmap;
import org.mapsforge.core.util.Parameters;

import java.util.concurrent.ExecutionException;

/**
 * Mutable configuration frontend for an underlying {@link HgtCache} (that will be replaced in one piece when parameters change)
 */
public class MemoryCachingHgtReaderTileSource implements ShadeTileSource {
    /**
     * No need for this to ever be greater than 1, as bitmap filtering uses at most a bicubic interpolation.
     */
    public static final int PaddingSizeDefault = 1;

    // To prevent cache starvation in high-quality mode
    protected final int CacheMinCount = 2;
    // Number of HGT files in a typical DEM ZIP archive is 24
    protected final int CacheMaxCount = 100;
    // One 1" HGT file converted to a same-sized bitmap is about 13 MB, for high-quality this is 52 MB.
    // For ultra-low-quality while rendering wide zoom in adaptive mode, bitmap size per 1" HGT file can be as low as several kilobytes.
    protected final long CacheMaxBytes = Parameters.MAX_MEMORY_MB * 1000 * 1000 / 8;

    private final GraphicFactory graphicsFactory;
    private HgtCache currentCache;
    private DemFolder demFolder;
    private ShadingAlgorithm algorithm;

    /**
     * 2024-10: This no longer affects performance as much as before, so it simply should be set to {@code true}.
     * Performance is not a big issue any more because no excess shading tiles are loaded beyond the required tiles used for display.
     */
    protected final boolean mIsEnableInterpolationOverlap;
    protected final int mPadding;

    public MemoryCachingHgtReaderTileSource(DemFolder demFolder, ShadingAlgorithm algorithm, GraphicFactory graphicsFactory, boolean isEnableInterpolationOverlap) {
        this.graphicsFactory = graphicsFactory;
        this.demFolder = demFolder;
        this.algorithm = algorithm;
        this.mIsEnableInterpolationOverlap = isEnableInterpolationOverlap;
        this.mPadding = mIsEnableInterpolationOverlap ? PaddingSizeDefault : 0;
    }

    public MemoryCachingHgtReaderTileSource(DemFolder demFolder, ShadingAlgorithm algorithm, GraphicFactory graphicsFactory) {
        this(demFolder, algorithm, graphicsFactory, true);
    }

    @Override
    public void applyConfiguration(boolean allowParallel) {
        HgtCache before = currentCache;
        HgtCache latest = latestCache();
        if (allowParallel && latest != null && latest != before) latest.indexOnThread();
    }

    protected HgtCache latestCache() {
        if (demFolder == null || algorithm == null) {
            this.currentCache = null;
            return null;
        }

        if (isNewCacheNeeded()) {
            synchronized (graphicsFactory) {
                if (isNewCacheNeeded()) {
                    this.currentCache = new HgtCache(demFolder, graphicsFactory, mPadding, algorithm, CacheMinCount, CacheMaxCount, CacheMaxBytes);
                }
            }
        }

        return this.currentCache;
    }

    protected boolean isNewCacheNeeded() {
        return (this.currentCache == null
                || !demFolder.equals(this.currentCache.mDemFolder)
                || !algorithm.equals(this.currentCache.mShadingAlgorithm));
    }

    @Override
    public void prepareOnThread() {
        if (currentCache != null) currentCache.indexOnThread();
    }

    @Override
    public HillshadingBitmap getHillshadingBitmap(int latitudeOfSouthWestCorner, int longitudeOfSouthWestCorner, int zoomLevel, double pxPerLat, double pxPerLon) throws ExecutionException, InterruptedException {

        if (latestCache() == null) {

            return null;
        }

        return currentCache.getHillshadingBitmap(latitudeOfSouthWestCorner, longitudeOfSouthWestCorner, zoomLevel, pxPerLat, pxPerLon);
    }

    @Override
    public ShadingAlgorithm getAlgorithm() {
        return algorithm;
    }

    @Override
    public void setAlgorithm(ShadingAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public void setDemFolder(DemFolder demFolder) {
        this.demFolder = demFolder;
    }

    public int getCacheMaxCount() {
        return CacheMaxCount;
    }

    public int getCacheMinCount() {
        return CacheMinCount;
    }

    public long getCacheMaxBytes() {
        return CacheMaxBytes;
    }

    public boolean isEnableInterpolationOverlap() {
        return mIsEnableInterpolationOverlap;
    }
}
