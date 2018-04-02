/**
 * Copyright 2018 Tobias Gierke <tobias.gierke@code-sourcery.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.codesourcery.versiontracker.common.server;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;

import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.VersionInfo;
import de.codesourcery.versiontracker.common.server.SharedLockCache.ThrowingRunnable;

/**
 * Background process that periodically wakes up and initiates 
 * a metadata update for each stale {@link VersionInfo}. 
 *
 * @author tobias.gierke@code-sourcery.de
 * 
 * @see IVersionStorage#isStaleVersion(VersionInfo, Duration, Duration, ZonedDateTime)
 */
public class BackgroundUpdater implements AutoCloseable {

    private static final org.apache.logging.log4j.Logger LOG = LogManager.getLogger( BackgroundUpdater.class );
    
    private final SharedLockCache artifactLocks;
    
    private final Object THREAD_LOCK = new Object();
    
    // GuardedBy( THREAD_LOCK )    
    private BGThread thread;
    
    private volatile boolean shutdown;
    
    /**
     * Time to wait before retrying artifact metadata retrieval if the last
     * attempt FAILED.
     */
    private volatile Duration lastFailureDuration = Duration.ofDays( 1 );
    
    /**
     * Time to wait before retrying artifact metadata retrieval if the last
     * attempt was a SUCCESS.
     */
    private volatile Duration lastSuccessDuration = Duration.ofDays( 1 );
    
    /**
     * Time the background thread will sleep() before checking the backing storage 
     * for stale artifact metadata.  
     */
    public volatile Duration pollingInterval = Duration.ofMinutes( 1 );
    
    private final IVersionStorage storage;
    private final IVersionProvider provider;
    
    private final ThreadPoolExecutor threadPool;    
    
    protected final class BGThread extends Thread 
    {
        private final Object SLEEP_LOCK = new Object();        
        private final CountDownLatch stopLatch = new CountDownLatch(1);
        
        public BGThread() 
        {
            setDaemon(true);
            setName("background-update-thread");
        }
        
        public void run() 
        {
            LOG.info("run(): Background thread started.");
            boolean regularShutdown = false;
            try {
                while ( ! shutdown ) 
                {
                    doUpdate();
                    synchronized( SLEEP_LOCK ) 
                    {
                        SLEEP_LOCK.wait( pollingInterval.toMillis() );
                    }
                }
                regularShutdown = true; 
            } 
            catch (Exception e) 
            {
                LOG.error("run(): Caught unexpected exception "+e.getMessage(),e);
            } 
            finally 
            {
                stopLatch.countDown();
                LOG.info("run(): Background thread about to stop (regular shutdown="+regularShutdown+")");                      
                if ( ! regularShutdown ) {
                  final Thread t = new Thread( () -> 
                  {
                      LOG.warn("run(): Thread died unexpectedly, restarting in 60 seconds");
                      try {
                          Thread.sleep( 60*1000 );
                      } catch(Exception e) {
                          Thread.currentThread().interrupt();
                      }
                      LOG.warn("run(): Restarting thread that died unexpectedly...");
                      startThread();
                  });
                  t.setDaemon( true );
                  t.setName("bg-restarter-thread");
                  t.start();
                }
            }
        }
        
        public void shutdown() throws InterruptedException 
        {
            if ( isAlive() ) 
            {
                shutdown = true;
                synchronized(SLEEP_LOCK) {
                    SLEEP_LOCK.notifyAll();
                }
                stopLatch.await();
            }
        }
    }

    public BackgroundUpdater(IVersionStorage storage, IVersionProvider provider,SharedLockCache artifactLocks) 
    {
        Validate.notNull(storage,"storage must not be NULL");
        Validate.notNull(provider,"provider must not be NULL");
        Validate.notNull(artifactLocks,"artifactLocks must not be NULL");
        this.storage = storage;
        this.provider = provider;
        this.artifactLocks = artifactLocks;
        
        final int threadCount = Runtime.getRuntime().availableProcessors();
        final ThreadFactory threadFactory = new ThreadFactory() 
        {
            private final AtomicInteger THREAD_ID = new AtomicInteger(0);
            
            @Override
            public Thread newThread(Runnable r) 
            {
                final Thread t = new Thread(r);
                t.setName("bg-updater-thread-"+THREAD_ID.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        final BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>( 100 );
        this.threadPool = new ThreadPoolExecutor( threadCount, threadCount, 60, TimeUnit.SECONDS, workQueue, threadFactory, new ThreadPoolExecutor.CallerRunsPolicy() );
    }
    
    private void doUpdate() throws Exception
    {
        final List<VersionInfo> infos = storage.getAllStaleVersions( lastSuccessDuration , lastFailureDuration, ZonedDateTime.now() );
        for (VersionInfo info : infos) 
        {
            doUpdate(info);
        }
    }
    
    public boolean requiresUpdate(Optional<VersionInfo> info) 
    {
        return info.isPresent() && IVersionStorage.isStaleVersion(
                info.get(),
                lastSuccessDuration,
                lastFailureDuration,
                ZonedDateTime.now() ); 
    }
    
    public void doUpdate(VersionInfo info) throws IOException 
    {
        submit( () -> 
        {
            artifactLocks.doWhileLocked( info.artifact, () -> 
            {
                final Optional<VersionInfo> existing = storage.getVersionInfo( info.artifact );
                if ( requiresUpdate(existing) )
                {
                    LOG.debug("doUpdate(): Refreshing "+info.artifact);
                    provider.update( info, null );
                    storage.saveOrUpdate( info );
                } else {
                    LOG.debug("doUpdate(): Doing nothing, concurrent update to "+info.artifact);
                }                
            }); 
        });
    }
    
    private void submit(ThrowingRunnable job) 
    {
        threadPool.submit( () -> 
        {
            try 
            {
                job.run();
            } catch(Exception e) {
                LOG.error("submit(): Caught "+e.getMessage(),e);
            }
        });
    }
    
    
    public void startThread() 
    {
        synchronized( THREAD_LOCK ) 
        {
            if ( ! shutdown ) 
            {
                thread = new BGThread();
                thread.start();
            }
        }
    }
    
    @Override
    public void close() throws Exception 
    {
        shutdown = true;
        synchronized( THREAD_LOCK ) 
        {
            if ( thread != null ) 
            {
                try {
                    thread.shutdown();
                } finally {
                    thread = null;
                }
            }
        }
        threadPool.shutdownNow();
    }

    public void setLastFailureDuration(Duration lastFailureDuration) {
        Validate.notNull(lastFailureDuration,"lastFailureDuration must not be NULL");
        Validate.isTrue(lastFailureDuration.compareTo( Duration.ofSeconds(1) ) >= 0 , "lastFailureDuration must be >= 1 second" );
        this.lastFailureDuration = lastFailureDuration;
    }
    
    public void setLastSuccessDuration(Duration lastSuccessDuration) {
        Validate.notNull(lastSuccessDuration,"lastSuccessDuration must not be NULL");
        Validate.isTrue(lastSuccessDuration.compareTo( Duration.ofSeconds(1) ) >= 0 , "lastSuccessDuration must be >= 1 second" );
        this.lastSuccessDuration = lastSuccessDuration;
    }
    
    public void setPollingInterval(Duration pollingInterval) {
        Validate.notNull(pollingInterval,"pollingInterval must not be NULL");
        Validate.isTrue(pollingInterval.compareTo( Duration.ofSeconds(1) ) >= 0 , "pollingInterval must be >= 1 second" );
        this.pollingInterval = pollingInterval;
    }
}