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

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.IVersionProvider;
import de.codesourcery.versiontracker.common.Version;
import de.codesourcery.versiontracker.common.VersionInfo;

/**
 * Version provider that retrieves artifact metadata from Maven central.
 * 
 * This class is thread-safe.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class MavenCentralVersionProvider implements IVersionProvider
{
    private static final Logger LOG = LogManager.getLogger(MavenCentralVersionProvider.class);

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter MAVEN_REPO_INDEX_DATE_FORMATTER = 
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("UTC"));

    @FunctionalInterface
    public interface MyStreamHandler<T>
    {
        public T process(InputStream stream) throws IOException;
    }

    private final PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();    
    private String serverBase;
    private final ThreadLocal<MyExpressions> expressions = new ThreadLocal<MyExpressions>() 
    {
        protected MyExpressions initialValue() {
         return new MyExpressions();   
        }
    };

    private static final class MyExpressions // XPathExpression is NOT thread-safe to we use a ThreadLocal + this wrapper 
    {
        private final XPathExpression latestSnapshot;
        private final XPathExpression latestRelease;
        private final XPathExpression lastUpdateDate;
        private final XPathExpression versionsXPath;
        
        public MyExpressions() 
        {
            final XPathFactory factory = XPathFactory.newInstance();
            final XPath xpath = factory.newXPath();

            try {
                latestSnapshot = xpath.compile("/metadata/versioning/latest[text()]");
                latestRelease = xpath.compile("/metadata/versioning/release[text()]");
                lastUpdateDate = xpath.compile("/metadata/versioning/lastUpdated");
                versionsXPath = xpath.compile("/metadata/versioning/versions/version");
            } catch (XPathExpressionException e) {
                throw new RuntimeException(e);
            }      
        }
    }

    public MavenCentralVersionProvider() 
    {
        this("http://repo1.maven.org/maven2");
        connManager.setDefaultMaxPerRoute(10);
        connManager.setMaxTotal(20);
    }
    public MavenCentralVersionProvider(String serverBase) 
    {
        this.serverBase = serverBase+(serverBase.trim().endsWith("/") ? "" : "/" );
    }
    
    private MyExpressions expressions() {
        return expressions.get();
    }

    private String metaDataPath(Artifact artifact) {
        return artifact.groupId.replace('.','/')+"/"+artifact.artifactId+"/maven-metadata.xml";
    }

    private String getPathToFolder(Artifact artifact,String versionNumber) {
        return  artifact.groupId.replace('.','/')+"/"+artifact.artifactId+"/"+versionNumber;
    }

    public static void main(String[] args) throws IOException
    {
        final Artifact test = new Artifact();
        test.groupId = "commons-lang";
        test.artifactId = "commons-lang";
        //        test.groupId = "org.lucee";
        //        test.artifactId = "jta";        

        // org/lucee/jta/1.1.0/
        VersionInfo data = new VersionInfo();
        data.artifact = test;
        long start = System.currentTimeMillis();
        final UpdateResult result = new MavenCentralVersionProvider().update( data, null );
        long end = System.currentTimeMillis();
        System.out.println("TIME: "+(end-start)+" ms");
        System.out.println("RESULT: "+result);
        System.out.println("GOT: "+data);
    }
    
    private final Map<URL,HttpClient> clients = new HashMap<>();
    
    private HttpClient getClient(URL url) 
    {
        synchronized(clients) {
            HttpClient client = clients.get(url);
            if ( client==null ) 
            {
                final DefaultConnectionKeepAliveStrategy defaultKeepAlive = new  DefaultConnectionKeepAliveStrategy();
                client = HttpClients.custom()
                        .setKeepAliveStrategy(defaultKeepAlive)
                        .setConnectionManager(connManager)
                        .setConnectionManagerShared(true).build();
                clients.put(url, client);
            }
            return client;
        }
    }
    
    private <T> T readPage(URL url,MyStreamHandler<T> handler) throws IOException 
    {
        LOG.debug("readPage(): Connecting to "+url);
        
        final long start = System.currentTimeMillis();
        final HttpGet httpget;
        try {
            httpget = new HttpGet( url.toURI() );
        } catch (Exception e1) {
            LOG.debug("readPage(): Should not happen: '"+url+"'",e1);
            throw new RuntimeException(e1);
        }
        final HttpResponse response = getClient(url).execute(httpget);
        final HttpEntity entity = response.getEntity();
        InputStream instream = null;
        try 
        {
            instream = entity.getContent();             
            LOG.debug("readPage(): Got Input Stream after "+(System.currentTimeMillis()-start)+" ms");
            return handler.process( instream );
        } 
        finally 
        {
            LOG.debug("readPage(): Finished processing after "+(System.currentTimeMillis()-start)+" ms");
            if ( instream != null ) {
                try { instream.close(); } catch(IOException e) { /* ok */ }
            }
        }
    }    

    private String readString(XPathExpression expression,Document document) throws IOException 
    {
        try {
            return expression.evaluate( document );
        } 
        catch(Exception e) {
        	if ( LOG.isDebugEnabled() ) {
        		LOG.error("parseXML(): Failed to parse document: "+e.getMessage(),e);
        	} else {
        		LOG.error("parseXML(): Failed to parse document: "+e.getMessage());
        	}
            throw new IOException("Failed to parse document: "+e.getMessage(),e);            
        }
    }

    private List<String> readStrings(XPathExpression expression,Document document) throws IOException 
    {
        try 
        {
            final NodeList nodeList = (NodeList) expression.evaluate( document ,  XPathConstants.NODESET );
            final int len = nodeList.getLength();
            final List<String> result = new ArrayList<>(len);
            for ( int i = 0 ; i < len ; i++ ) 
            {
                final Node n = nodeList.item( i );
                final String versionString = ((Element) n).getTextContent();
                result.add( versionString );
            }
            return result;
        } 
        catch(Exception e) {
            LOG.error("parseXML(): Failed to parse document: "+e.getMessage(),e);
            throw new IOException("Failed to parse document: "+e.getMessage(),e);            
        }
    }    

    @Override
    public UpdateResult update(VersionInfo info,String versionNumber) throws IOException
    {
        final Artifact artifact = info.artifact;
        final URL url = new URL( serverBase+metaDataPath( artifact ) );
        LOG.debug("update(): Retrieving metadata for "+info.artifact+" from "+url);
        
        try 
        {
            return readPage(url, new MyStreamHandler<UpdateResult>() 
            {
                public UpdateResult process(InputStream stream) throws IOException 
                {
                    final Document document = parseXML( stream );

                    final MyExpressions expr = expressions(); // XPath evaluation is not thread-safe to we get a thread-local instance here
                    final Set<String> versions = new HashSet<>( readStrings( expr.versionsXPath , document ) );
                    for ( String version : versions ) {
                        info.maybeAddVersion( new Version(version,null) );
                    }
                    
                    for (Iterator<Version> it = info.versions.iterator(); it.hasNext();) {
						final Version v = it.next();
						if ( ! versions.contains( v.versionString ) ) {
							LOG.warn("process(): Version "+v+" is gone from "+info.artifact);
							it.remove();
						}
					}
                    
                    // always look for release date of specified version
                    // as we'll need to compare against this later anway
                    final Set<String> versionsToRequest = new HashSet<>();
                            
                    if ( StringUtils.isNotBlank(versionNumber) ) 
                    {
                        if ( ! info.getDetails( versionNumber ).isPresent() ) 
                        {
                            LOG.debug("update(): metadata xml contained no version '"+artifact.version+"' for artifact "+info.artifact);
                        }
                        versionsToRequest.add(versionNumber);
                    }
                    
                    // determine latest snapshot version 
                    String snapshot = readString(expr.latestSnapshot, document );
                    String release = readString(expr.latestRelease, document );
                    
                    LOG.debug("latest snapshot = "+snapshot);
                    LOG.debug("update(): latest release = "+release);                    
                    
                    if ( StringUtils.isNotBlank(snapshot) && ! info.hasVersionWithReleaseDate(snapshot) ) {
                        versionsToRequest.add(snapshot);
                    }
                    if ( StringUtils.isNotBlank(release) && ! info.hasVersionWithReleaseDate(release) ) {
                        versionsToRequest.add(release);
                    }                
                    
                    // last repository change date
                    final String lastChangeString = readString(expr.lastUpdateDate, document );
                    LOG.debug("update(): last repository change = "+lastChangeString);
                    
                    final ZonedDateTime lastChangeDate = ZonedDateTime.parse( lastChangeString, DATE_FORMATTER);
                    if ( info.lastRepositoryUpdate != null && info.lastRepositoryUpdate.equals( lastChangeDate ) ) 
                    {
                        info.lastSuccessDate = ZonedDateTime.now();
                        return UpdateResult.NO_CHANGES_ON_SERVER;
                    }
                    LOG.debug("update(): Repository changed on server");
                    LOG.debug("update(): Gathering release dates for versions "+versionsToRequest+"...");
                    
                    if ( ! versionsToRequest.isEmpty() ) 
                    {
                        final Map<String,Version> result = readVersions(artifact,versionsToRequest);
                        for ( Map.Entry<String,Version> entry : result.entrySet() ) 
                        {
                            Optional<Version> existing = info.getDetails( entry.getKey() );
                            if ( existing.isPresent() ) 
                            {
                                existing.get().releaseDate = entry.getValue().releaseDate; 
                                LOG.debug("update(): Updated existing version to "+existing.get());                                
                            } else {
                            	info.versions.add( entry.getValue() );
                            	LOG.debug("update(): Adding NEW version "+entry.getValue());                                
                            }
                        }
                    }
                    info.artifact = artifact;
                    if ( StringUtils.isNotBlank( release ) ) {
                        info.getDetails( release ).ifPresent( x -> info.latestReleaseVersion = x );
                    }
                    if ( StringUtils.isNotBlank( snapshot ) ) {
                        info.getDetails( snapshot ).ifPresent( x -> info.latestSnapshotVersion = x );
                    }
                    info.lastRepositoryUpdate = lastChangeDate;
                    info.lastSuccessDate = ZonedDateTime.now();
                    return UpdateResult.UPDATED;
                }
            });
        } 
        catch(Exception e) 
        {
            info.lastFailureDate = ZonedDateTime.now();
            if ( e instanceof FileNotFoundException) {
                LOG.warn("getLatestVersion(): Failed to find artifact on server: "+info);
                return UpdateResult.ARTIFACT_NOT_FOUND;
            }
            LOG.error("getLatestVersion(): Error while retrieving artifact metadata from server: "+info+": "+e.getMessage(), LOG.isDebugEnabled() ? e : null);
            throw new IOException(e);
        } finally {
        	LOG.debug("Finished retrieving metadata for "+info.artifact);
        }
    }

    /*
     * <a href="junit-4.12-javadoc.jar" title="junit-4.12-javadoc.jar">junit-4.12-javadoc.jar</a>
     *                             2014-12-04 16:17    937942      
     */
    private static final Pattern LINE_PATTERN = Pattern.compile("<a .*?>(.*?)</a>\\s*(\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2})\\s+(\\d+)");

    private int maxConcurrentThreads = 10;
    
    private final Object THREAD_POOL_LOCK=new Object();
    private ThreadPoolExecutor threadPool;
    
    private final ThreadFactory threadFactory = new ThreadFactory() {
        
        private final ThreadGroup threadGroup = new ThreadGroup(Thread.currentThread().getThreadGroup(),"releasedate-request-threads" );
        private final AtomicInteger threadId = new AtomicInteger(0); 
        public Thread newThread(Runnable r) 
        {
            final Thread t = new Thread(threadGroup,r);
            t.setDaemon( true );
            t.setName("releasedate-request-thread-"+threadId.incrementAndGet());
            return t;
        }
    };
    
    private void submit(Runnable r) 
    {
        synchronized( THREAD_POOL_LOCK ) 
        {
            if ( threadPool == null ) 
            {
                LOG.info("setMaxConcurrentThreads(): Using "+maxConcurrentThreads+" threads to retrieve artifact metadata.");                
                final BlockingQueue<Runnable> workingQueue = new ArrayBlockingQueue<Runnable>(200);
                threadPool = new ThreadPoolExecutor( maxConcurrentThreads, maxConcurrentThreads, 60 , TimeUnit.SECONDS,
                        workingQueue,threadFactory, new ThreadPoolExecutor.CallerRunsPolicy() );
            }
            threadPool.submit( r );
        }
    }
    
    private Map<String,Version> readVersions(Artifact artifact,Set<String> versions) throws IOException 
    {
        final Map<String,Version> result = new HashMap<>();
        if ( versions.isEmpty() ) {
            return result;
        }
        final CountDownLatch latch = new CountDownLatch( versions.size() );
        for ( String version : versions ) 
        {
            // to stuff
            final Runnable r = () -> 
            {
                try {
                    final Optional<Version> v = readVersion(artifact,version);
                    if ( v.isPresent() ) {
                        synchronized(result) {
                            result.put(version,v.get());
                        }
                    }
                } catch(Exception e) {
                    LOG.error("readVersion(): Failed to retrieve version '"+version+"' for "+artifact);
                } finally {
                    latch.countDown();
                }
            };
            submit(r);
        }
        
        while ( true ) 
        {
            try {
                if ( latch.await(10,TimeUnit.SECONDS) ) {
                    return result;
                }
            } catch(InterruptedException e) {
            }
            LOG.debug("readVersions(): Still waiting for "+latch.getCount()+" outstanding requests of artifact "+artifact);
        }
    }
    private Optional<Version> readVersion(Artifact artifact,String versionString) throws IOException 
    {
        Validate.notBlank(versionString, "versionString must not be NULL/blank");

        final URL url2 = new URL( serverBase+getPathToFolder( artifact, versionString ) );        
        LOG.debug("readVersion(): Looking for release date of version '"+versionString+"' for "+artifact);
        
        return readPage(url2,new MyStreamHandler<Optional<Version>>() 
        {
            public Optional<Version> process(InputStream stream) throws IOException 
            {
                final String page = IOUtils.readLines(stream,"UTF8").stream().collect(Collectors.joining("\n"));
                Matcher m = LINE_PATTERN.matcher( page );

                ZonedDateTime latest = null;
                final boolean trace = LOG.isTraceEnabled();
                while ( m.find() ) 
                {
                    final String filename = m.group(1);
                    final String uploadDate = m.group(2);
                    final String size = m.group(3);
                    if ( filename.trim().endsWith(".jar" ) ) 
                    {
                        final ZonedDateTime date = ZonedDateTime.parse( uploadDate, MAVEN_REPO_INDEX_DATE_FORMATTER );
                        if( latest == null || latest.compareTo( date ) < 0 ) {
                            latest = date;
                        }
                        if ( trace ) {
                            LOG.trace( "readVersion(): (*) FOUND: "+filename + " | " + uploadDate + " | " + size);
                        }
                    } else {
                        if ( trace ) {
                            LOG.trace( "readVersion(): FOUND: "+filename + " | " + uploadDate + " | " + size);
                        }
                    }
                }
                return latest == null ? Optional.empty() : Optional.of( new Version(versionString,latest) );
            }
        });
    }

    public static Document parseXML(InputStream inputStream) throws IOException
    {
        if ( inputStream == null ) {
            throw new IOException("input stream cannot be NULL");
        }

        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        try {
            final DocumentBuilder builder = factory.newDocumentBuilder();

            // set fake EntityResolver , otherwise parsing is incredibly slow (~1 sec per file on my i7)
            // because the parser will download the DTD from the internets...
            builder.setEntityResolver( new DummyResolver() );
            return builder.parse( inputStream);
        }
        catch(ParserConfigurationException | SAXException e) 
        {
            LOG.error("parseXML(): Failed to parse document: "+e.getMessage(),LOG.isDebugEnabled() ? e : null);
            throw new IOException("Failed to parse document: "+e.getMessage(),e);
        }
    }

    private static final class DummyResolver implements EntityResolver {

        @Override
        public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException
        {
            final ByteArrayInputStream dummy = new ByteArrayInputStream(new byte[0]);
            return new InputSource(dummy);
        }
    }
}
