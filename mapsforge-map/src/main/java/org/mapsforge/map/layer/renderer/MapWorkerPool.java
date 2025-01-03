/*
 * Copyright 2010, 2011, 2012, 2013 mapsforge.org
 * Copyright 2014 Ludwig M Brinckmann
 * Copyright 2015-2017 devemux86
 * Copyright 2016 ksaihtam
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
package org.mapsforge.map.layer.renderer;

import org.mapsforge.core.graphics.TileBitmap;
import org.mapsforge.core.util.Parameters;
import org.mapsforge.map.layer.Layer;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.queue.JobQueue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MapWorkerPool implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(MapWorkerPool.class.getName());

    public static boolean DEBUG_TIMING = false;
    private final AtomicLong debugStart = new AtomicLong(0), debugPrevStart = new AtomicLong(0);
    private final AtomicInteger concurrentJobs = new AtomicInteger();

    private final DatabaseRenderer databaseRenderer;
    private boolean inShutdown, isRunning;
    private final JobQueue<RendererJob> jobQueue;
    private final Layer layer;
    private ExecutorService self, workers;
    private final TileCache tileCache;

    public MapWorkerPool(TileCache tileCache, JobQueue<RendererJob> jobQueue, DatabaseRenderer databaseRenderer, Layer layer) {
        super();
        this.tileCache = tileCache;
        this.jobQueue = jobQueue;
        this.databaseRenderer = databaseRenderer;
        this.layer = layer;
        this.inShutdown = false;
        this.isRunning = false;
    }

    @Override
    public void run() {
        try {
            while (!inShutdown) {
                RendererJob rendererJob = this.jobQueue.get(Parameters.NUMBER_OF_THREADS);
                if (rendererJob == null) {
                    continue;
                }
                if (!this.tileCache.containsKey(rendererJob) || rendererJob.labelsOnly) {
                    workers.execute(new MapWorker(rendererJob));
                } else {
                    jobQueue.remove(rendererJob);
                }
            }
        } catch (InterruptedException e) {
            LOGGER.log(Level.SEVERE, "MapWorkerPool interrupted", e);
        } catch (RejectedExecutionException e) {
            LOGGER.log(Level.SEVERE, "MapWorkerPool rejected", e);
        }
    }

    public synchronized void start() {
        if (this.isRunning) {
            return;
        }
        this.inShutdown = false;
        this.self = Executors.newSingleThreadExecutor();
        this.workers = Executors.newFixedThreadPool(Parameters.NUMBER_OF_THREADS);
        this.self.execute(this);
        this.isRunning = true;
    }

    public synchronized void stop() {
        if (!this.isRunning) {
            return;
        }
        this.inShutdown = true;
        this.jobQueue.interrupt();

        // Shutdown executors
        this.self.shutdown();
        this.workers.shutdown();
        this.databaseRenderer.interruptAndDestroy();

        try {
            if (!this.self.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                this.self.shutdownNow();
                if (!this.self.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                    LOGGER.fine("Shutdown self executor failed");
                }
            }
        } catch (InterruptedException e) {
            LOGGER.log(Level.SEVERE, "Shutdown self executor interrupted", e);
        }

        try {
            if (!this.workers.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                this.workers.shutdownNow();
                if (!this.workers.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                    LOGGER.fine("Shutdown workers executor failed");
                }
            }
        } catch (InterruptedException e) {
            LOGGER.log(Level.SEVERE, "Shutdown workers executor interrupted", e);
        }

        this.isRunning = false;
    }

    class MapWorker implements Runnable {
        private final RendererJob rendererJob;

        MapWorker(RendererJob rendererJob) {
            this.rendererJob = rendererJob;
            this.rendererJob.renderThemeFuture.incrementRefCount();
        }

        @Override
        public void run() {
            TileBitmap bitmap = null;
            try {
                if (inShutdown) {
                    return;
                }
                if (DEBUG_TIMING) {
                    final int jobs = concurrentJobs.incrementAndGet();

                    final long start, end;

                    start = System.nanoTime();

                    bitmap = MapWorkerPool.this.databaseRenderer.executeJob(rendererJob);

                    if (inShutdown) {
                        concurrentJobs.decrementAndGet();
                        return;
                    }

                    MapWorkerPool.this.layer.requestRedraw();

                    end = System.nanoTime();

                    synchronized (debugStart) {
                        if (start - debugPrevStart.getAndSet(start) > 1e9) {
                            debugStart.set(start);
                        }
                    }

                    System.out.println("RENDER TIME: " + Math.round((end - debugStart.get()) / 1e6) + " ms  JOBS: " + jobs);

                    concurrentJobs.decrementAndGet();
                } else {
                    bitmap = MapWorkerPool.this.databaseRenderer.executeJob(rendererJob);

                    if (inShutdown) {
                        return;
                    }

                    MapWorkerPool.this.layer.requestRedraw();
                }
            } finally {
                this.rendererJob.renderThemeFuture.decrementRefCount();
                jobQueue.remove(rendererJob);
                if (bitmap != null) {
                    bitmap.decrementRefCount();
                }
            }
        }
    }
}
