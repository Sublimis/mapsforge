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

public interface ShadingAlgorithm {

    int ZoomLevelMinDefault = 0;
    int ZoomLevelMaxDefault = Integer.MAX_VALUE;

    /**
     * @return Length of a side of a (square) input array minus one (to account for HGT overlap).
     */
    int getInputAxisLen(HgtFileInfo hgtFileInfo);

    /**
     * @param zoomLevel Zoom level (to determine shading quality requirements)
     * @param pxPerLat  Tile pixels per degree of latitude (to determine shading quality requirements)
     * @param pxPerLon  Tile pixels per degree of longitude (to determine shading quality requirements)
     * @return Length of a side of a (square) output array, not including padding.
     */
    int getOutputAxisLen(HgtFileInfo hgtFileInfo, int zoomLevel, double pxPerLat, double pxPerLon);

    /**
     * @param padding   Padding of the output, useful to minimize border interpolation artifacts (no need to be larger than 1)
     * @param zoomLevel Zoom level (to determine shading quality requirements)
     * @param pxPerLat  Tile pixels per degree of latitude (to determine shading quality requirements)
     * @param pxPerLon  Tile pixels per degree of longitude (to determine shading quality requirements)
     * @return Length of a side of a (square) output array, including padding.
     */
    int getOutputWidth(HgtFileInfo hgtFileInfo, int padding, int zoomLevel, double pxPerLat, double pxPerLon);

    /**
     * @param padding   Padding of the output, useful to minimize border interpolation artifacts (no need to be larger than 1)
     * @param zoomLevel Zoom level (to determine shading quality requirements)
     * @param pxPerLat  Tile pixels per degree of latitude (to determine shading quality requirements)
     * @param pxPerLon  Tile pixels per degree of longitude (to determine shading quality requirements)
     * @return Estimated size of the output array, in bytes, padding included.
     */
    long getOutputSizeBytes(HgtFileInfo hgtFileInfo, int padding, int zoomLevel, double pxPerLat, double pxPerLon);

    /**
     * @param padding   Padding of the output, useful to minimize border interpolation artifacts (no need to be larger than 1)
     * @param zoomLevel Zoom level (to determine shading quality requirements)
     * @param pxPerLat  Tile pixels per degree of latitude (to determine shading quality requirements)
     * @param pxPerLon  Tile pixels per degree of longitude (to determine shading quality requirements)
     */
    RawShadingResult transformToByteBuffer(HgtFileInfo hgtFileInfo, int padding, int zoomLevel, double pxPerLat, double pxPerLon);

    /**
     * This is used when deciding whether a cached hill shading tile should be refreshed.
     *
     * @param hgtFileInfo HGT file info
     * @param padding     Padding in the output bitmap
     * @param zoomLevel   Zoom level (to determine shading quality requirements)
     * @param pxPerLat    Tile pixels per degree of latitude (to determine shading quality requirements)
     * @param pxPerLon    Tile pixels per degree of longitude (to determine shading quality requirements)
     * @return Cache tag
     */
    default long getCacheTag(HgtFileInfo hgtFileInfo, int padding, int zoomLevel, double pxPerLat, double pxPerLon) {
        long output = hgtFileInfo.hashCode();
        output = 31 * output + padding;
        output = 31 * output + getCacheTagBin(hgtFileInfo, zoomLevel, pxPerLat, pxPerLon);

        return output;
    }

    /**
     * Convert the display parameters to a number whose semantics depends on shading algorithm implementation.
     * This is used in {@link #getCacheTag(HgtFileInfo, int, int, double, double)}.
     *
     * @param hgtFileInfo HGT file info
     * @param zoomLevel   Zoom level intended for the hill shading tile
     * @param pxPerLat    Tile pixels per degree of latitude (to determine shading quality requirements)
     * @param pxPerLon    Tile pixels per degree of longitude (to determine shading quality requirements)
     * @return Converted number
     */
    default int getCacheTagBin(HgtFileInfo hgtFileInfo, int zoomLevel, double pxPerLat, double pxPerLon) {
        return 0;
    }

    /**
     * @return Minimum supported zoom level (default is 0).
     */
    default int getZoomMin(HgtFileInfo hgtFileInfo) {
        return ZoomLevelMinDefault;
    }

    /**
     * @return Maximum supported zoom level (default is {@link Integer#MAX_VALUE}).
     */
    default int getZoomMax(HgtFileInfo hgtFileInfo) {
        return ZoomLevelMaxDefault;
    }

    class RawShadingResult {
        public final byte[] bytes;
        public final int width;
        public final int height;
        public final int padding;

        public RawShadingResult(byte[] bytes, int width, int height, int padding) {
            this.bytes = bytes;
            this.width = width;
            this.height = height;
            this.padding = padding;
        }
    }

    /**
     * Abstracts the file handling and access so that ShadingAlgorithm implementations
     * could run on any height model source (e.g. on an android content provider for
     * data sharing between apps) as long as they understand the format of the stream
     */
    interface RawHillTileSource {
        long getSize();

        DemFile getFile();

        /**
         * A ShadingAlgorithm might want to determine the projected dimensions of the tile
         */
        double northLat();

        double southLat();

        double westLng();

        double eastLng();
    }
}
