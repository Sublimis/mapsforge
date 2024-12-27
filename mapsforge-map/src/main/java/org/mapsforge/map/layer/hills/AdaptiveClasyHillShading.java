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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>
 * Adaptive implementation of {@link StandardClasyHillShading}.
 * It will dynamically decide on the resolution and quality of the output depending on the display parameters, to maximize efficiency.
 * <p>
 * It conserves memory and CPU at lower zoom levels without significant quality degradation, yet it switches to high quality
 * when details are needed at larger zoom levels.
 * Switching to high quality only at larger zoom levels is also a resource-saving tactic, since less hill shading data needs to be processed the more you zoom in.
 * <p>
 * This is currently the algorithm of choice, as it provides the best results with excellent performance throughout the zoom level range.
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
    public static final boolean IsAdaptiveZoomEnabledDefault = true;

    protected final boolean mIsHqEnabled;
    protected volatile boolean mIsAdaptiveZoomEnabled = IsAdaptiveZoomEnabledDefault;
    protected volatile double mCustomQualityScale = 1;

    protected final Map<Integer, Map<Long, Integer>> mStrides = new ConcurrentHashMap<>();

    /**
     * Construct this using the parameters provided.
     *
     * @param clasyParams Parameters to use while constructing this.
     * @param isHqEnabled Whether to enable the use of high-quality (bicubic) algorithm for larger zoom levels. Disabling will reduce memory usage at high zoom levels.
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
     * @param isHqEnabled Whether to enable the use of high-quality (bicubic) algorithm for larger zoom levels. Disabling will reduce memory usage at high zoom levels.
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

    @Override
    public long getCacheTagBin(HgtFileInfo hgtFileInfo, int zoomLevel, double pxPerLat, double pxPerLon) {
        return getQualityFactor(hgtFileInfo, zoomLevel, pxPerLat, pxPerLon);
    }

    @Override
    public int getOutputAxisLen(final HgtFileInfo hgtFileInfo, int zoomLevel, double pxPerLat, double pxPerLon) {
        final int inputAxisLen = getInputAxisLen(hgtFileInfo);

        return scaleByQualityFactor(inputAxisLen, getQualityFactor(hgtFileInfo, zoomLevel, pxPerLat, pxPerLon));
    }

    @Override
    protected byte[] convert(InputStream inputStream, int dummyAxisLen, int dummyRowLen, int padding, int zoomLevel, double pxPerLat, double pxPerLon, HgtFileInfo hgtFileInfo) throws IOException {
        final boolean isHighQuality = isHighQuality(hgtFileInfo, zoomLevel, pxPerLat, pxPerLon);

        return convert(hgtFileInfo, isHighQuality, padding, zoomLevel, pxPerLat, pxPerLon);
    }

    @Override
    public boolean isHqEnabled() {
        return mIsHqEnabled;
    }

    @Override
    public boolean isAdaptiveZoomEnabled() {
        return mIsAdaptiveZoomEnabled;
    }

    @Override
    public AdaptiveClasyHillShading setAdaptiveZoomEnabled(boolean isEnabled) {
        mIsAdaptiveZoomEnabled = isEnabled;
        return this;
    }

    @Override
    public int getZoomMin(HgtFileInfo hgtFileInfo) {
        int retVal = ZoomLevelMinDefault;

//        if (isInputFastSkip(hgtFileInfo)) {
//            retVal = ZoomLevelMinDefault;
//        } else {
//            retVal = 7;
//        }

        return retVal;
    }

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
     * Lower number means lower quality.
     * Sometimes this can be useful to improve performance of hill shading on high dpi devices.
     *
     * @return A custom quality scale value. The default is 1.
     * @see #setCustomQualityScale(double)
     */
    public double getCustomQualityScale() {
        return mCustomQualityScale;
    }

    /**
     * Set a new custom quality scale value for hill shading rendering.
     * Lower number means lower quality.
     * Sometimes this can be useful to improve performance of hill shading on high dpi devices.
     * <p>
     * There's usually no reason to set this to a number larger than 1 (the default), even though it's permitted,
     * as there is no point in having hill shading rendered to a higher resolution than the device screen.
     * <p>
     * Let's have an example.
     * By default, the algorithm will try to match the hill shading resolution to that of the device screen.
     * If the device screen has 480 dpi and you don't want hill shading to be rendered in a resolution higher than 240 dpi,
     * you should set the custom quality scale value to {@code 0.5 = 240 / 480}.
     * <p>
     * Note: The algorithm tries its best to match the required custom quality scale and resolution, but the result might
     * not be exact sometimes, as the final hill shading resolution must be a divisor of the input digital elevation data resolution.
     *
     * @param customQualityScale A new custom quality scale value. The value must be larger than 0, and should not be larger than 1. The default is 1.
     * @return {@code this}
     * @see #getCustomQualityScale()
     */
    public AdaptiveClasyHillShading setCustomQualityScale(double customQualityScale) {
        mCustomQualityScale = customQualityScale;
        return this;
    }

    /**
     * @param hgtFileInfo HGT file info
     * @param zoomLevel   Zoom level (to determine shading quality requirements)
     * @param pxPerLat    Tile pixels per degree of latitude (to determine shading quality requirements)
     * @param pxPerLon    Tile pixels per degree of longitude (to determine shading quality requirements)
     * @return {@code true} if the parameters provided result in a high quality (bicubic) algorithm being applied, {@code false} otherwise.
     */
    protected boolean isHighQuality(HgtFileInfo hgtFileInfo, int zoomLevel, double pxPerLat, double pxPerLon) {
        return getQualityFactor(hgtFileInfo, zoomLevel, pxPerLat, pxPerLon) > 1;
    }

    /**
     * Our quality factors can be positive or negative.
     * If positive, that number is a multiplier for scaling purposes.
     * If negative, its absolute value is a divisor for scaling purposes.
     *
     * @param hgtFileInfo HGT file info
     * @param zoomLevel   Zoom level (to determine shading quality requirements)
     * @param pxPerLat    Tile pixels per degree of latitude (to determine shading quality requirements)
     * @param pxPerLon    Tile pixels per degree of longitude (to determine shading quality requirements)
     * @return The quality factor: If >0 that number is a multiplier, if <0 its absolute value is a divisor for scaling purposes.
     * @see #scaleByQualityFactor(int, int)
     */
    @SuppressWarnings("unused")
    public int getQualityFactor(HgtFileInfo hgtFileInfo, int zoomLevel, double pxPerLat, double pxPerLon) {
        final int output;

        final int inputPxPerDeg = getInputAxisLen(hgtFileInfo);
        final double pxPerLatClamped = Math.max(4, pxPerLat * getCustomQualityScale());
        final double scale = (double) inputPxPerDeg / pxPerLatClamped;

        if (scale >= 2.0) {
            // Rounding
            final int strideDivisor = (int) ((double) inputPxPerDeg / scale + 0.5);

            // Note, integer arithmetic and truncations are deliberate here.
            int stride = inputPxPerDeg / strideDivisor;
            int target = inputPxPerDeg / stride * stride;

            if (target != inputPxPerDeg) {
                Map<Long, Integer> inputPxMap = mStrides.get(inputPxPerDeg);

                if (inputPxMap == null) {
                    inputPxMap = new ConcurrentHashMap<>();
                    mStrides.put(inputPxPerDeg, inputPxMap);
                }

                final Integer savedStride = inputPxMap.get((long) pxPerLatClamped);

                if (savedStride != null) {
                    stride = savedStride;
                } else {
                    stride = getStrideAsDivisor(stride, inputPxPerDeg);

                    inputPxMap.put((long) pxPerLatClamped, stride);
//                    System.out.println("COUNT  " + mStrides.get(inputPxPerDeg).size());
                }
            }

            if (stride > 1) {
                output = -stride;
            } else {
                output = 1;
            }
        } else if (scale > 1. / 1.25 || false == isHqEnabled()) {
            output = 1;
        } else {
            output = 2;
        }

        return output;
    }

    protected boolean isInputFastSkip(HgtFileInfo hgtFileInfo) {
        return hgtFileInfo.getFile() instanceof DemFileFS;
    }

    protected int getStrideAsDivisor(int stride, int inputPxPerDeg) {
        int target = inputPxPerDeg / stride * stride;

        while (target != inputPxPerDeg && stride > 1) {
            // We go down the ladder to get slightly better quality if the exact cannot be achieved
            --stride;
            target = inputPxPerDeg / stride * stride;
        }

        return stride;
    }

    /**
     * Our quality factors can be positive or negative.
     * If positive, that number is a multiplier for scaling purposes.
     * If negative, its absolute value is a divisor for scaling purposes.
     *
     * @param value         Value to scale.
     * @param qualityFactor A quality factor to scale with.
     * @return Scaled value.
     * @see #getQualityFactor(HgtFileInfo, int, double, double)
     */
    public static int scaleByQualityFactor(int value, int qualityFactor) {
        if (qualityFactor < 0) {
            return value / -qualityFactor;
        } else {
            return value * qualityFactor;
        }
    }
}
