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

import org.mapsforge.core.graphics.HillshadingBitmap;

import java.util.concurrent.ExecutionException;

/**
 * Mutable frontend for the hill shading cache/processing in {@link HgtCache}
 * <p>All changes are lazily applied when a tile is requested with {@link #getShadingTile}, which includes a full reindex of the .hgt files.
 * Eager indexing on a dedicated thread can be triggered with {@link #indexOnThread} (e.g. after a configuration change or during setup)</p>
 */
public class HillsRenderConfig {
    private final ShadeTileSource tileSource;

    private volatile float magnitudeScaleFactor = 1f;


    public HillsRenderConfig(ShadeTileSource tileSource) {
        this.tileSource = tileSource;
    }


    /**
     * call after initialization, after a set of changes to the settable properties or after forceReindex to initiate background indexing
     */
    public HillsRenderConfig indexOnThread() {
        ShadeTileSource cache = tileSource;
        if (cache != null) cache.applyConfiguration(true);
        return this;
    }

    /**
     *
     * @param latitudeOfSouthWestCorner  tile ID latitude (southwest corner, as customary in .hgt)
     * @param longitudeOfSouthWestCorner tile ID longitude (southwest corner, as customary in .hgt)
     * @param zoomLevel                  Zoom level
     * @param pxPerLat                   Tile pixels per degree of latitude (to determine shading quality requirements)
     * @param pxPerLon                   Tile pixels per degree of longitude (to determine shading quality requirements)
     * @return
     * @throws ExecutionException
     * @throws InterruptedException
     */
    public HillshadingBitmap getShadingTile(int latitudeOfSouthWestCorner, int longitudeOfSouthWestCorner, int zoomLevel, double pxPerLat, double pxPerLon) throws ExecutionException, InterruptedException {
        ShadeTileSource tileSource = this.tileSource;
        if (tileSource == null) return null;

        HillshadingBitmap ret = tileSource.getHillshadingBitmap(latitudeOfSouthWestCorner, longitudeOfSouthWestCorner, zoomLevel, pxPerLat, pxPerLon);
        if (ret == null && Math.abs(longitudeOfSouthWestCorner) > 178) { // don't think too hard about where exactly the border is (not much height data there anyway)
            int eastInt = longitudeOfSouthWestCorner > 0 ? longitudeOfSouthWestCorner - 180 : longitudeOfSouthWestCorner + 180;
            ret = tileSource.getHillshadingBitmap(latitudeOfSouthWestCorner, eastInt, zoomLevel, pxPerLat, pxPerLon);
        }

        return ret;
    }

    public float getMagnitudeScaleFactor() {
        return magnitudeScaleFactor;
    }

    /**
     * Increase (&gt;1) or decrease (&lt;1) the hill shading magnitude relative to the value set in themes
     * <p>When designing a theme, this should be one</p>
     */
    public void setMagnitudeScaleFactor(float magnitudeScaleFactor) {
        this.magnitudeScaleFactor = magnitudeScaleFactor;
    }

    public boolean isWideZoomRange() {
        final ShadingAlgorithm algorithm = this.tileSource.getAlgorithm();

        return algorithm instanceof IAdaptiveHillShading;
    }
}
