/*
 * Copyright 2010, 2011, 2012, 2013 mapsforge.org
 * Copyright 2014-2015 Ludwig M Brinckmann
 * Copyright 2015-2022 devemux86
 * Copyright 2025 Sublimis
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
package org.mapsforge.map.datastore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An immutable container for the data returned from a MapDataStore.
 */
public class MapReadResult {

    /**
     * True if the read area is completely covered by water, false otherwise.
     */
    public boolean isWater;

    /**
     * The read ways.
     * LinkedHashSet is used to: 1) maintain element order, 2) maximize element removal performance when deduplicating.
     */
    public final Set<Way> ways;

    /**
     * The read POIs.
     * LinkedHashSet is used to: 1) maintain element order, 2) maximize element removal performance when deduplicating.
     */
    public final Set<PointOfInterest> pois;

    public MapReadResult() {
        // LinkedHashSet is used to: 1) maintain element order, 2) maximize element removal performance when deduplicating.
        this.ways = new LinkedHashSet<>();
        this.pois = new LinkedHashSet<>();
    }

    public void add(PoiWayBundle poiWayBundle) {
        this.ways.addAll(poiWayBundle.ways);
        this.pois.addAll(poiWayBundle.pois);
    }

    /**
     * Adds other MapReadResult by combining pois and ways.
     *
     * @param other the MapReadResult to add to this.
     */
    public void add(MapReadResult other) {
        this.ways.addAll(other.ways);
        this.pois.addAll(other.pois);
    }

    public MapReadResult deduplicate() {
        deduplicate(this.ways);
        deduplicate(this.pois);

        return this;
    }

    public static <T extends Comparable<T>> void deduplicate(Collection<T> collection) {
        if (!collection.isEmpty()) {

            final List<T> sorted = new ArrayList<>(collection);

            Collections.sort(sorted);

            T pivot = sorted.get(0);

            for (int i = 1; i < sorted.size(); i++) {
                T item = sorted.get(i);
                if (pivot.compareTo(item) == 0) {
                    // We're removing duplicates instead of building a new list from non-duplicates
                    // simply because the expected number of duplicates is small (much less than 50%),
                    // and we made sure the removals are cheap.
                    collection.remove(item);
                    continue;
                }

                pivot = item;
            }
        }
    }
}
