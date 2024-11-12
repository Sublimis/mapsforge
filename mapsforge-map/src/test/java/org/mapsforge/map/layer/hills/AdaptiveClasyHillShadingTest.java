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

import junit.framework.TestCase;

import org.junit.Assert;
import org.mapsforge.map.layer.hills.AdaptiveClasyHillShading.Bin;

import java.io.File;

public class AdaptiveClasyHillShadingTest extends TestCase {

    final AdaptiveClasyHillShading algorithm = new AdaptiveClasyHillShading();
    final long hgtFileSize = (long) 2 * AdaptiveClasyHillShading.HGTFILE_WIDTH_BASE * AdaptiveClasyHillShading.HGTFILE_WIDTH_BASE;
    final HgtFileInfo hgtFileInfo = new HgtFileInfo(new DemFileFS(new File("dummy")), 0, 0, 1, 1, hgtFileSize);

    public void testBins() {
        for (Bin bin : Bin.values()) {
            if (false == bin.isMultiplier()) {
                Assert.assertEquals(0, getDivScaleRemainder(AdaptiveClasyHillShading.HGTFILE_WIDTH_BASE, bin));
            }
        }
    }

    public void testGetQualityBin() {
        final int tileSizePerLat = AdaptiveClasyHillShading.HGTFILE_WIDTH_BASE;

        Assert.assertEquals(Bin.HQ2, algorithm.getQualityBin(hgtFileInfo, 12, tileSizePerLat, tileSizePerLat));
        Assert.assertEquals(Bin.MQ, algorithm.getQualityBin(hgtFileInfo, 12, (double) tileSizePerLat / 2, (double) tileSizePerLat / 2));
    }

    public void testScaleByBin() {
        final int value = AdaptiveClasyHillShading.HGTFILE_WIDTH_BASE;

        for (Bin bin : Bin.values()) {
            if (bin.isMultiplier()) {
                Assert.assertEquals(value * bin.getFactor(), bin.scale(value));
                Assert.assertEquals(bin.scale(value), AdaptiveClasyHillShading.scaleByBin(value, bin));
            } else {
                Assert.assertEquals(value / bin.getFactor(), bin.scale(value));
                Assert.assertEquals(bin.scale(value), AdaptiveClasyHillShading.scaleByBin(value, bin));
            }
        }
    }

    public void testIsHighQuality() {
        final int tileSizePerLat = AdaptiveClasyHillShading.HGTFILE_WIDTH_BASE;

        Assert.assertTrue(algorithm.isHighQuality(hgtFileInfo, 12, tileSizePerLat, tileSizePerLat));
    }

    private int getDivScaleRemainder(int hgtFileWidth, Bin bin) {
        return hgtFileWidth - bin.scale(hgtFileWidth) * bin.getFactor();
    }
}
