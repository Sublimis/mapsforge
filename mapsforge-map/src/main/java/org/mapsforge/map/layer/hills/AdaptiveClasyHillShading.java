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

import java.io.IOException;
import java.io.InputStream;

/**
 * <p>
 * Adaptive implementation of {@link StandardClasyHillShading}.
 * It will dynamically decide on the resolution and quality of the output depending on the display parameters, to maximize efficiency.
 * </p>
 * <p>
 * It conserves memory and CPU at lower zoom levels without significant quality degradation, yet it switches to high quality
 * when details are needed at larger zoom levels.
 * Switching to high quality only at larger zoom levels is also a resource-saving tactic, since less hill shading data needs to be processed the more you zoom in.
 * </p>
 * <p>
 * This is currently the algorithm of choice, as it provides the best results with excellent performance throughout the zoom level range.
 * </p>
 *
 * @see StandardClasyHillShading
 * @see HiResClasyHillShading
 * @see HalfResClasyHillShading
 * @see QuarterResClasyHillShading
 */
public class AdaptiveClasyHillShading extends HiResClasyHillShading implements IAdaptiveHillShading {

    /**
     * This is the length of one side of a 1" HGT file.
     */
    public static final int HGTFILE_WIDTH_BASE = 3600;

    /**
     * Default max zoom level when using a 1" HGT file and high quality (bicubic) algorithm is enabled.
     */
    public static final int ZoomLevelMaxBaseDefault = 17;

    public static final boolean IsHqEnabledDefault = true;

    public static final int HQ2_MUL = 2;
    // Values below must be positive divisors of both 3600 and 1200 (dimensions of 1" and 3" HGT files)
    public static final int LQ2_DIV = 2;
    public static final int LQ4_DIV = 4;
    public static final int LQ8_DIV = 8;
    public static final int LQ16_DIV = 16;
    public static final int LQ32_DIV = 30;
    public static final int LQ64_DIV = 60;

    protected final boolean mIsHqEnabled;

    public enum Bin {
        HQ2(1, HQ2_MUL, true), MQ(2, 1, true), LQ2(3, LQ2_DIV), LQ4(4, LQ4_DIV), LQ8(5, LQ8_DIV), LQ16(6, LQ16_DIV), LQ32(7, LQ32_DIV), LQ64(8, LQ64_DIV);

        private final int mValue;
        private final int mFactor;
        private final boolean mIsMultiplier;

        Bin(int value, int factor, boolean iMultiplier) {
            mValue = value;
            mFactor = factor;
            mIsMultiplier = iMultiplier;
        }

        Bin(int value, int factor) {
            mValue = value;
            mFactor = factor;
            mIsMultiplier = false;
        }

        public int getValue() {
            return mValue;
        }

        public int getFactor() {
            return mFactor;
        }

        public boolean isMultiplier() {
            return mIsMultiplier;
        }

        public int scale(int value) {
            if (isMultiplier()) {
                return value * getFactor();
            } else {
                return value / getFactor();
            }
        }

        public boolean isHighQuality() {
            return this == HQ2;
        }
    }

    /**
     * Construct this using the parameters provided.
     *
     * @param clasyParams        Parameters to use while constructing this.
     * @param isHqEnabled        Whether to enable the use of high-quality (bicubic) algorithm for larger zoom levels. Disabling will reduce memory usage at high zoom levels.
     * @see AClasyHillShading#AClasyHillShading(ClasyParams)
     * @see ClasyParams
     * @see HiResClasyHillShading
     */
    public AdaptiveClasyHillShading(final ClasyParams clasyParams, boolean isHqEnabled) {
        super(clasyParams);
        this.mIsHqEnabled = isHqEnabled;
    }

    /**
     * Uses default values for all parameters.
     *
     * @param isHqEnabled        Whether to enable the use of high-quality (bicubic) algorithm for larger zoom levels. Disabling will reduce memory usage at high zoom levels.
     * @see AClasyHillShading#AClasyHillShading()
     * @see HiResClasyHillShading
     */
    public AdaptiveClasyHillShading(boolean isHqEnabled) {
        super();
        this.mIsHqEnabled = isHqEnabled;
    }

    /**
     * Uses default values for all parameters, and enables the high-quality (bicubic) algorithm for higher zoom levels.
     *
     * @see AClasyHillShading#AClasyHillShading()
     * @see HiResClasyHillShading
     */
    public AdaptiveClasyHillShading() {
        this(IsHqEnabledDefault);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getCacheTagBin(HgtFileInfo hgtFileInfo, int zoomLevel, double pxPerLat, double pxPerLon) {
        return getQualityBin(hgtFileInfo, zoomLevel, pxPerLat, pxPerLon).getValue();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getOutputAxisLen(final HgtFileInfo hgtFileInfo, int zoomLevel, double pxPerLat, double pxPerLon) {
        final int inputAxisLen = getInputAxisLen(hgtFileInfo);

        return scaleByBin(inputAxisLen, getQualityBin(hgtFileInfo, zoomLevel, pxPerLat, pxPerLon));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected byte[] convert(InputStream inputStream, int dummyAxisLen, int dummyRowLen, int padding, int zoomLevel, double pxPerLat, double pxPerLon, HgtFileInfo hgtFileInfo) throws IOException {
        final boolean isHighQuality = isHighQuality(hgtFileInfo, zoomLevel, pxPerLat, pxPerLon);

        return doTheWork(hgtFileInfo, isHighQuality, padding, zoomLevel, pxPerLat, pxPerLon);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isHqEnabled() {
        return mIsHqEnabled;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getZoomMin(HgtFileInfo hgtFileInfo) {
        return ZoomLevelMinDefault;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getZoomMax(HgtFileInfo hgtFileInfo) {
        int retVal = ZoomLevelMaxBaseDefault;

        if (false == isHqEnabled()) {
            retVal -= 1;
        }

        final int inputAxisLen = getInputAxisLen(hgtFileInfo);

        if (inputAxisLen < HGTFILE_WIDTH_BASE) {
            for (int len = HGTFILE_WIDTH_BASE; inputAxisLen < len; len /= 2) {
                retVal -= 1;
            }
        } else if (inputAxisLen > HGTFILE_WIDTH_BASE) {
            for (int len = HGTFILE_WIDTH_BASE; inputAxisLen > len; len *= 2) {
                retVal += 1;
            }
        }

        return retVal;
    }

    /**
     * @param hgtFileInfo HGT file info
     * @param zoomLevel   Zoom level (to determine shading quality requirements)
     * @param pxPerLat    Tile pixels per degree of latitude (to determine shading quality requirements)
     * @param pxPerLon    Tile pixels per degree of longitude (to determine shading quality requirements)
     * @return {@code true} if the parameters provided result in a high quality (bicubic) algorithm being applied, {@code false} otherwise.
     */
    protected boolean isHighQuality(HgtFileInfo hgtFileInfo, int zoomLevel, double pxPerLat, double pxPerLon) {
        return getQualityBin(hgtFileInfo, zoomLevel, pxPerLat, pxPerLon).isHighQuality();
    }

    public Bin getQualityBin(HgtFileInfo hgtFileInfo, int zoomLevel, double pxPerLat, double pxPerLon) {
        final int inputAxisLenPerLat = getInputAxisLen(hgtFileInfo);

        if (scaleByBin(inputAxisLenPerLat, Bin.LQ64) >= pxPerLat) {
            return Bin.LQ64;
        } else if (scaleByBin(inputAxisLenPerLat, Bin.LQ32) >= pxPerLat) {
            return Bin.LQ32;
        } else if (scaleByBin(inputAxisLenPerLat, Bin.LQ16) >= pxPerLat) {
            return Bin.LQ16;
        } else if (scaleByBin(inputAxisLenPerLat, Bin.LQ8) >= pxPerLat) {
            return Bin.LQ8;
        } else if (scaleByBin(inputAxisLenPerLat, Bin.LQ4) >= pxPerLat) {
            return Bin.LQ4;
        } else if (scaleByBin(inputAxisLenPerLat, Bin.LQ2) >= pxPerLat) {
            return Bin.LQ2;
        } else {
            if (false == isHqEnabled() || scaleByBin(inputAxisLenPerLat, Bin.MQ) >= pxPerLat) {
                return Bin.MQ;
            } else {
                return Bin.HQ2;
            }
        }
    }

    /**
     * @param value Value to scale.
     * @param bin   A bin to scale with.
     * @return Scaled value.
     */
    public static int scaleByBin(int value, Bin bin) {
        return bin.scale(value);
    }
}
