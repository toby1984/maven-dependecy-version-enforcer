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

import java.io.File;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

import de.codesourcery.versiontracker.client.IAPIClient.Protocol;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.ArtifactResponse.UpdateAvailable;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.IVersionStorage;
import de.codesourcery.versiontracker.common.QueryRequest;
import de.codesourcery.versiontracker.common.QueryResponse;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;

public class APIImpl implements AutoCloseable
{
    private static final Logger LOG = LogManager.getLogger(APIImpl.class);

    /**
     * Environment variable that points to the location where
     * artifact metadata should be stored when using the simple flat-file storage implementation.
     */
    public static final String SYSTEM_PROPERTY_ARTIFACT_FILE = "versiontracker.artifact.file";    

    private VersionTracker versionTracker;
    private IVersionStorage versionStorage;
    private IVersionProvider versionProvider;

    private BackgroundUpdater updater;
    
    private boolean registerShutdownHook = true;

    private String mavenRepository = "http://repo1.maven.org/maven2/";

    public static enum Mode 
    {
        /**
         * Client mode - does not use a background thread to periodically check registered artifacts for newer releases.
         * 
         * If artifact metadata is deemed too old it will be re-fetched again while the client is blocked waiting.
         */
        CLIENT,
        /**
         * Server mode - uses a background thread to periodically check all registered artifacts for newer releases.
         * 
         * Artifact metadata should never be outdated as the background thread should've updated it already.   
         */
        SERVER
    }

    private final Mode mode;

    private boolean initialized;

    public APIImpl(Mode mode) {
        if ( mode == null ) {
            throw new IllegalArgumentException("Mode cannot be NULL");
        }
        this.mode = mode;
    }
    
    private void setLogLevel(Level level) 
    {
        // shamelessly taken from https://stackoverflow.com/questions/23434252/programmatically-change-log-level-in-log4j2 
        final LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        final Configuration config = ctx.getConfiguration();
//        final LoggerConfig loggerConfig = config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME); 
        final LoggerConfig loggerConfig = config.getLoggerConfig("de.codesourcery");
        loggerConfig.setLevel(level);
        ctx.updateLoggers();
        System.out.println("Log level is now "+level);
    }    

    public synchronized void init(boolean debugMode,boolean verboseMode)
    {
        if ( initialized ) {
            return;
        }

        initialized = true;

        final File versionFile = getArtifactFileLocation();
        
        final IVersionStorage fileStorage;
        if ( versionFile.getName().endsWith(".json") ) 
        {
        	final File binaryFile = new File( versionFile.getAbsolutePath()+".binary");
        	boolean useBinaryFile = binaryFile.exists();
        	if ( ! binaryFile.exists() ) 
        	{
        		if ( versionFile.exists() ) 
        		{
        			try {
						FlatFileStorage.convert(versionFile, Protocol.JSON, binaryFile, Protocol.BINARY);
						LOG.info("init(): Converted "+versionFile.getAbsolutePath()+" -> "+binaryFile.getAbsolutePath());
						useBinaryFile = true;
					} catch (Exception e) {
						LOG.error("init(): Using JSON file , failed to convert "+versionFile.getAbsolutePath()+" -> "
								+ binaryFile.getAbsolutePath(),e);
					}
        		}
        	}
        	if ( useBinaryFile ) {
        		LOG.info("init(): Using binary file "+binaryFile.getAbsolutePath());
        		fileStorage = new FlatFileStorage( binaryFile,Protocol.BINARY );
        	} else {
        		fileStorage = new FlatFileStorage( versionFile );
        	}
        } else {
        	LOG.info("init(): Using JSON file "+versionFile.getAbsolutePath());
        	fileStorage = new FlatFileStorage( versionFile );
        }
        versionStorage  = new CachingStorageDecorator(fileStorage);
        versionProvider = new MavenCentralVersionProvider(mavenRepository);
        final SharedLockCache lockCache = new SharedLockCache();
        
        if ( debugMode || verboseMode ) 
        {
            if ( debugMode ) {
                setLogLevel( Level.DEBUG );
            } else if ( verboseMode ) {
                setLogLevel( Level.INFO);
            }
        }

        LOG.info("init(): ====================");
        LOG.info("init(): Running in "+mode+" mode.");

        if ( registerShutdownHook ) 
        {
            LOG.info("init(): Registering shutdown hook");
            Runtime.getRuntime().addShutdownHook( new Thread( () -> 
            {
                try {
                    LOG.info("init(): Shutdown hook triggered");
                    close();
                } catch (Exception e) {
                    LOG.error("Exception during shutdown: "+e.getMessage(),e);
                }
            }));
        }

        updater = new BackgroundUpdater(versionStorage,versionProvider,lockCache);         
        if ( mode == Mode.SERVER ) 
        {
            LOG.info("init(): Starting background update thread.");        
            updater.startThread();
        } 

        boolean success = false;
        try 
        {
            final int threadCount = Runtime.getRuntime().availableProcessors()*2;
            versionTracker = new VersionTracker(versionStorage,versionProvider,lockCache);
            versionTracker.setMaxConcurrentThreads( threadCount );

            LOG.info("init(): Servlet initialized.");
            LOG.info("init(): ");
            LOG.info("init(): Version file storage: "+versionFile.getAbsolutePath());
            LOG.info("init(): Maven repository enpoint: "+mavenRepository);
            LOG.info("init(): Thread count: "+versionTracker.getMaxConcurrentThreads());
            LOG.info("init(): ====================");
            success = true;
        } 
        finally 
        {
            if ( ! success ) 
            {
                LOG.error("init(): Servlet failed to initialize");
                try 
                {
                    updater.close();
                }
                catch (Exception e) 
                {
                    LOG.error("init(): Caught "+e.getMessage(),e);
                }
            }
        }
    }    

    private File getArtifactFileLocation() 
    {
        String location = System.getProperty( SYSTEM_PROPERTY_ARTIFACT_FILE );
        if ( StringUtils.isNotBlank( location ) ) 
        {
            LOG.info("getArtifactFileLocation(): Using artifacts file location from '"+SYSTEM_PROPERTY_ARTIFACT_FILE+"' JVM property");
            return new File( location );
        }
        location = System.getProperty("user.home");
        if ( StringUtils.isNotBlank( location) ) 
        {
            LOG.info("getArtifactFileLocation(): Storing artifacts file relative to 'user.home' JVM property");
            final File fallback = new File( location , "artifacts.json");
            final File m2Dir = new File( location , ".m2");
            if ( m2Dir.exists() ) 
            {
                if ( ! m2Dir.isDirectory() ) {
                    return fallback;
                }
                return new File( m2Dir , "artifacts.json" );
            } 
            if ( m2Dir.mkdirs() ) {
                LOG.info("getArtifactFileLocation(): Created directory "+m2Dir.getAbsolutePath());
                return new File( m2Dir , "artifacts.json" );
            }
            return fallback;
        }
        final String msg = "Neither 'user.home' nor '"+SYSTEM_PROPERTY_ARTIFACT_FILE+"' JVM properties are set, don't know where to store artifact metadata";
        LOG.error("getArtifactFileLocation(): "+msg);
        throw new RuntimeException(msg);
    }      

    public QueryResponse processQuery(QueryRequest request) throws InterruptedException
    {
        QueryResponse result = new QueryResponse();
        result.serverVersion = "1.0";

        final Map<Artifact,VersionInfo> results = versionTracker.getVersionInfo( request.artifacts, updater::requiresUpdate );        
        for ( Artifact artifact : request.artifacts ) 
        {
            final VersionInfo info = results.get( artifact );
            if ( mode == Mode.CLIENT && updater.requiresUpdate( Optional.of(info) )) 
            {
                try 
                {
                    LOG.info("processQuery(): Fetching information for outdated artifact "+info.artifact+" from repository...");
                    updater.doUpdate( info );
                } 
                catch (Exception e) 
                {
                    if ( LOG.isDebugEnabled() ) {
                        LOG.error("processQuery(): Failed to update "+info+": "+e.getMessage());
                    } else {
                        LOG.error("processQuery(): Failed to update "+info+": "+e.getMessage(),e);
                    }
                }
            }

            final ArtifactResponse x = new ArtifactResponse();
            result.artifacts.add(x);
            x.artifact = artifact;
            x.updateAvailable = UpdateAvailable.NOT_FOUND; 

            if ( info != null && info.hasVersions() ) 
            {
                if ( artifact.hasReleaseVersion() ) {
                    x.latestVersion = info.findLatestReleaseVersion( request.blacklist ).orElse( null );
                    if ( LOG.isDebugEnabled() ) {
                        LOG.debug("processQuery(): latest release version from metadata: "+info.latestReleaseVersion);
                        LOG.debug("processQuery(): Calculated latest release version: "+x.latestVersion);
                    }
                } else {
                    x.latestVersion = info.findLatestSnapshotVersion( request.blacklist ).orElse( null );
                    if ( LOG.isDebugEnabled() ) {
                        LOG.debug("processQuery(): latest release version from metadata: "+info.latestSnapshotVersion);
                        LOG.debug("processQuery(): Calculated latest snapshot version: "+x.latestVersion);
                    }
                }

                if ( artifact.version == null || x.latestVersion == null ) 
                {
                    x.updateAvailable = UpdateAvailable.MAYBE;
                } 
                else 
                {
                    final Optional<Version> currentVersion = info.getDetails( artifact.version );
                    if ( currentVersion.isPresent() ) 
                    {
                        x.currentVersion = currentVersion.get();
                    }

                    int cmp = Artifact.VERSION_COMPARATOR.compare( artifact.version, x.latestVersion.versionString);
                    if ( cmp >= 0 ) {                       
                        x.updateAvailable = UpdateAvailable.NO;
                    } else if ( cmp < 0 ) {
                        x.updateAvailable = UpdateAvailable.YES;                        
                    }                     
                }
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug("processQuery(): "+artifact+" <-> "+x.latestVersion+" => "+x.updateAvailable);
                }
            }
        }
        return result;
    }

    public Mode getMode()
    {
        return mode;
    }

    public BackgroundUpdater getBackgroundUpdater()
    {
        return updater;
    }

    public VersionTracker getVersionTracker()
    {
        return versionTracker;
    }

    @Override
    public void close() throws Exception
    {
        try 
        {
            if ( updater != null ) 
            {
                updater.close();
            }
        } 
        finally 
        {
            try 
            {
                if ( versionTracker != null ) 
                {
                    versionTracker.close();
                }
            } 
            finally 
            {
                if ( this.versionStorage != null ) {
                    this.versionStorage.close();
                }
            }
        } 

    }

    public void setRegisterShutdownHook(boolean registerShutdownHook)
    {
        this.registerShutdownHook = registerShutdownHook;
    }    
}