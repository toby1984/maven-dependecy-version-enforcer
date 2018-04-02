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
package de.codesourcery.versiontracker.enforcerrule;

import java.io.File;
import java.text.MessageFormat;
import java.text.ParseException;
import java.time.Duration;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;

import de.codesourcery.versiontracker.client.IAPIClient;
import de.codesourcery.versiontracker.client.IAPIClient.Protocol;
import de.codesourcery.versiontracker.client.LocalAPIClient;
import de.codesourcery.versiontracker.client.RemoteApiClient;
import de.codesourcery.versiontracker.common.Artifact;
import de.codesourcery.versiontracker.common.ArtifactResponse;
import de.codesourcery.versiontracker.common.ArtifactResponse.UpdateAvailable;
import de.codesourcery.versiontracker.common.Blacklist;
import de.codesourcery.versiontracker.common.Blacklist.VersionMatcher;
import de.codesourcery.versiontracker.xsd.IgnoreVersion;
import de.codesourcery.versiontracker.xsd.Rule;
import de.codesourcery.versiontracker.xsd.Ruleset;

/**
 * A custom rule for the 
 * <a href="https://maven.apache.org/enforcer/maven-enforcer-plugin/ maven-enforcer-plugin">Maven Enforcer Plugin</a> 
 * that supports warning and failing the build when project dependencies are outdated by a configurable amount of time.
 * 
 * @author tobias.gierke@code-sourcery.de
 */
public class DependencyAgeRule implements EnforcerRule
{
	private static final AtomicLong ID = new AtomicLong(0);
	
    private static final String MAX_AGE_PATTERN_STRING = "(\\d+)\\s*([dwmy]|(day|days|week|weeks|month|months|year|years))";

    public static final Pattern MAX_AGE_PATTERN = Pattern.compile(MAX_AGE_PATTERN_STRING,Pattern.CASE_INSENSITIVE);

    // When running in client mode (=without the proxy servlet doing the actual work)
    // we need to use a global lock here to prevent concurrent Maven plugin executions (using the -T option) 
    // from writing to the metadata backing store file concurrently.
    
    private static final Object GLOBAL_LOCK = new Object();
    
    // @GuardedBy(CLIENTS)
    private static final Map<String,RemoteApiClient> CLIENTS = new HashMap<>();
    
    private static Object LOCAL_API_CLIENT_LOCK = new Object();
    
    // @GuardedBy(LOCAL_API_CLIENT_LOCK)
    private static LocalAPIClient LOCAL_API_CLIENT;
    
    private static final JAXBContext jaxbContext;
    
    static 
    {
        try {
			jaxbContext = JAXBContext.newInstance(Ruleset.class);
		} catch (JAXBException e) {
			throw new RuntimeException(e);
		}
    }

    private final long id = ID.incrementAndGet();
    private Log log;
    private MavenProject project;

    private Age parsedMaxAge;
    private Age parsedWarnAge;

    // rule configuration properties

    /**
     * Dependency age at which to start printing warnings.
     * 
     * Executing this rule will print warnings for all project dependencies
     * whose version is older than <code>warnAge</code>.
     * 
     * Supported syntax is "1d", "1 day", "3 days", "2 weeks", "1 month", "1m", "1 year", "1y".
     */
    private String warnAge;

    /**
     * Dependency age at which to fail the build.
     * 
     * Executing this rule will fail the build when at least one project dependency
     * is older than <code>maxAge</code>.
     * 
     * Supported syntax is "1d", "1 day", "3 days", "2 weeks", "1 month", "1m", "1 year", "1y".
     */    
    private String maxAge;

    /**
     * API endpoint to contact for fetching artifact version metadata.  
     */
    private String apiEndpoint;

    /**
     * Enable verbose output.
     */
    private boolean verbose;

    /**
     * Enable debug (very verbose) output.
     */
    private boolean debug;

    /**
     * Optional path to XML file that describes what artifact versions to ignore. 
     */
    private File rulesFile;

    /**
     * Whether to fail when an artifact could not be found in the repository.
     */
    private boolean failOnMissingArtifacts=true;
    
    private boolean binaryProtocol=true;

    /**
     * Whether to look for the rules XML file in parent directories
     * if the file is not found in the specified location.
     */
    private boolean searchRulesInParentDirectories;

    private static enum AgeUnit 
    {
        DAYS,WEEKS,MONTHS,YEARS;

        public static AgeUnit parse(String unit) {
            if ( StringUtils.isBlank( unit ) ) {
                throw new IllegalArgumentException("age unit must not be NULL/blank");
            }
            final String s = unit.trim().toLowerCase();
            if ( "d".equals(s) || "day".equals(s) || "days".equals(s) ) {
                return DAYS;
            }
            if ( "w".equals(s) || "week".equals(s) || "weeks".equals(s) ) {
                return WEEKS;
            }
            if ( "m".equals(s) || "month".equals(s) || "months".equals(s) ) {
                return MONTHS;
            }   
            if ( "y".equals(s) || "year".equals(s) || "years".equals(s) ) {
                return YEARS;
            }               
            throw new IllegalArgumentException("Unknown age unit '"+unit+"'");
        }
    }

    private static final class Age {

        public final int value;
        public final AgeUnit unit;

        public Age(int value, AgeUnit unit)
        {
            this.value = value;
            this.unit = unit;
        }

        public Period toPeriod() 
        {
            switch(unit) {
                case DAYS:
                    return Period.ofDays( value );
                case MONTHS:
                    return Period.ofMonths( value );
                case WEEKS:
                    return Period.ofWeeks( value );
                case YEARS:
                    return Period.ofYears( value );
            }
            throw new RuntimeException("Unreachable code reached");
        }

        @Override
        public String toString()
        {
            final boolean plural = value > 1;
            switch( unit ) {
                case DAYS:
                    return value+" "+(plural?"days":"day");
                case MONTHS:
                    return value+" "+(plural?"months":"month");
                case WEEKS:
                    return value+" "+(plural?"weeks":"week");
                case YEARS:
                    return value+" "+(plural?"years":"year");                    
                default:
                    throw new RuntimeException("Internal error, unhandled switch/case: "+unit);                    
            }
        }
        
        public int compareTo(Duration duration,ZonedDateTime referencePoint) {
            
            Duration thisDuration = Duration.between( referencePoint , referencePoint.plus( toPeriod() ) );
            return thisDuration.compareTo( duration );
        }
    }
    
    private static <T> T eval(String expression,EnforcerRuleHelper helper,Log log) throws EnforcerRuleException {

        try {
            return (T) helper.evaluate(expression);
        }
        catch (ExpressionEvaluationException e) 
        {
            final String msg = "Failed to evaluate expression '"+expression+"' : "+e.getMessage();
            log.error(msg,e);
            throw new EnforcerRuleException( msg ,e );
        }        
    }

    private boolean isTooOld(ArtifactResponse response,Age threshold)
    {
        if ( response.hasCurrentVersion() &&
                response.hasLatestVersion() )
        {
            if ( response.currentVersion.hasReleaseDate() &&
                 response.latestVersion.hasReleaseDate() &&
                 response.currentVersion.releaseDate.compareTo( response.latestVersion.releaseDate ) <= 0 )
            {
                final Duration elapsedTime = Duration.between( response.currentVersion.releaseDate , response.latestVersion.releaseDate );
                if ( threshold.compareTo( elapsedTime, ZonedDateTime.now() ) < 0 ) 
                {
                    final Duration age = Duration.between(response.currentVersion.releaseDate , response.latestVersion.releaseDate);
                    log.debug("Age threshold exceeded for "+response.artifact+", age is "+age+" but threshold is "+threshold);
                    return true;
                }
            } 
            else 
            {
                if( ! response.currentVersion.hasReleaseDate() ) {
                    log.warn("Unable to determine current release date for version '"+response.currentVersion.versionString+"' of "+response.artifact);
                }
                if( ! response.latestVersion.hasReleaseDate() ) {
                    log.warn("Unable to determine latest release date for version '"+response.latestVersion.versionString+"' of "+response.artifact);
                }                
            }
        }
        return false;
    }
    
    @Override
    public void execute(EnforcerRuleHelper helper) throws EnforcerRuleException {
    	long start = System.currentTimeMillis();
    	try {
    		executeInternal(helper);
    	} finally  {
    		final long elapsed = System.currentTimeMillis() - start;
    		log.warn("RULE TIME: "+elapsed+" ms");
    	}
    }
    
    public void executeInternal(EnforcerRuleHelper helper) throws EnforcerRuleException
    {
        setup(helper);
        log.warn("Now executing... rule #"+id);
        
        if ( StringUtils.isBlank( apiEndpoint ) ) 
        {
            synchronized( GLOBAL_LOCK ) 
            {
                doExecute(helper);
            }
        } else {
            doExecute(helper);
        }
    }
    
    private void setup(EnforcerRuleHelper helper) throws EnforcerRuleException 
    {
        log = helper.getLog();

        project = eval("${project}",helper,log);

        if ( StringUtils.isNotBlank( maxAge ) ) {
            parsedMaxAge = parseAge( "maxAge", maxAge );
        }
        if ( StringUtils.isNotBlank( warnAge ) ) {
            parsedWarnAge = parseAge( "warnAge", warnAge );
        }

        if ( parsedWarnAge == null && parsedMaxAge == null ) {
            fail("Configuration error - either 'maxAge' or 'warnAge' need to be set");
        }

        if ( parsedWarnAge != null && parsedMaxAge != null ) {
            final ZonedDateTime now = ZonedDateTime.now();
            if ( now.plus( parsedWarnAge.toPeriod() ).isAfter( now.plus( parsedMaxAge.toPeriod() ) ) ) {
                fail("Configuration error - 'warnAge' needs to be less than 'maxAge'");
            }
        }
        log.debug("==== Rule executing with API endpoint = "+apiEndpoint);
        if ( parsedWarnAge != null )  {
            log.debug("==== Rule executing with warnAge = "+parsedWarnAge);
        }
        if ( parsedMaxAge != null ) {
            log.debug("==== Rule executing with maxAge = "+parsedMaxAge);
        }        
    }    

    private void doExecute(EnforcerRuleHelper helper) throws EnforcerRuleException
    {
        final Set<org.apache.maven.artifact.Artifact> mavenArtifacts = project.getDependencyArtifacts();

        if ( ! mavenArtifacts.isEmpty() ) 
        {
            final Blacklist bl;
            try {
                bl = loadBlacklist();
            } catch (JAXBException | ParseException e1) {
                fail("Failed to parse rules.xml ("+e1.getMessage()+")",e1);
                throw new RuntimeException("Unreachable code reached"); // fail() never returns
            }

            final List<Artifact> artifacts = new ArrayList<>();
            for ( org.apache.maven.artifact.Artifact ma : mavenArtifacts ) {
                Artifact a = new Artifact();
                a.groupId = ma.getGroupId();
                a.artifactId = ma.getArtifactId();
                a.version = ma.getVersion();
                a.type = ma.getType();
                a.setClassifier(ma.getClassifier());
                if ( verbose ) {
                    log.info("Project depends on "+a);
                } else {
                    log.debug("Project depends on "+a);
                }
                if ( bl == null || ! bl.isAllVersionsBlacklisted( a.groupId, a.artifactId) ) {
                    artifacts.add( a );
                } else {
                    if ( verbose ) {
                        log.warn("All artifact versions ignored by blacklist: "+a);
                    }
                }
            }

            final List<ArtifactResponse> result;
            final IAPIClient client; 
            if ( StringUtils.isBlank( apiEndpoint ) ) 
            {
                log.warn("No API endpoint configured, running locally");
                client = getLocalAPIClient();
            } 
            else 
            {
            	final Protocol protocol = binaryProtocol ? Protocol.BINARY : Protocol.JSON;
            	if ( verbose ) {
            		log.info("Using "+protocol+" protocol");
            	}
                client = getRemoteAPIClient(apiEndpoint,protocol,debug);
            }
            try 
            {
                if ( StringUtils.isBlank( apiEndpoint ) ) {
                    log.info("Querying metadata for "+artifacts.size()+" artifacts");
                } else {
                    log.info("Querying metadata for "+artifacts.size()+" artifacts from "+apiEndpoint);
                }
                result = client.query(artifacts,bl);
            } 
            catch (Exception e) 
            {
                fail("Failed to query version information from '"+apiEndpoint+"': "+e.getMessage(),e);
                throw new RuntimeException("Unreachable code reached");
            } 
            boolean failBecauseAgeExceeded = false;
            boolean artifactsNotFound = false;
            ZonedDateTime oldestOffendingRelease = null;
            for ( ArtifactResponse resp : result ) 
            {
                if ( resp.updateAvailable == UpdateAvailable.NOT_FOUND ) {
                    artifactsNotFound = true;
                    log.warn( "Failed to find metadata for artifact "+resp.artifact);
                    continue;
                }
                final boolean maxAgeExceeded = parsedMaxAge != null && isTooOld( resp, parsedMaxAge );
                final boolean warnAgeExceeded = parsedWarnAge != null && isTooOld( resp, parsedWarnAge );
                failBecauseAgeExceeded |= maxAgeExceeded;
                if ( warnAgeExceeded && ! maxAgeExceeded ) {
                    printMessage(resp,false); // log warning
                    if ( oldestOffendingRelease == null || resp.latestVersion.releaseDate.isBefore( oldestOffendingRelease ) ) {
                        oldestOffendingRelease = resp.latestVersion.releaseDate;
                    }
                }
                if ( maxAgeExceeded) 
                {
                    printMessage(resp,true); // log error
                }
            }
            if ( failBecauseAgeExceeded ) {
                fail("One or more dependencies of this project are older than the allowed maximum age ("+parsedMaxAge+")");
            }
            if ( artifactsNotFound && failOnMissingArtifacts ) {
                fail("Failed to find metadata for one or more dependencies of this project");
            }
            if ( oldestOffendingRelease != null && parsedMaxAge != null ) 
            {
                final ZonedDateTime now = ZonedDateTime.now();
                final Duration age = Duration.between(oldestOffendingRelease, now );
                final Duration timeRemaining = Duration.between( now , now.plus( parsedMaxAge.toPeriod() ) ).minus(age);
                final ZonedDateTime failureTime = now.plus( timeRemaining );
                final Duration remainingTime = Duration.between(now,failureTime);
                final long millis = remainingTime.toMillis();
                final DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDateTime( FormatStyle.LONG, FormatStyle.LONG  );
                log.warn("===========================================");
                log.warn("= Your build will start FAILING on "+formatter.format( failureTime )+" which is in "+
                        DurationFormatUtils.formatDurationWords(millis,true,true));
                log.warn("===========================================");
            }
        }
    }

    private void printMessage(ArtifactResponse resp,boolean printAsError) {
        final String fmt = "Artifact {0} is too old, current version {1} was released on {2} but latest version "+
                " is {3} which was released on {4}";
        final String msg = MessageFormat.format(fmt,
                resp.artifact.toString(),
                resp.currentVersion.versionString,
                prettyPrint( resp.currentVersion.releaseDate ),
                resp.latestVersion.versionString,
                prettyPrint( resp.latestVersion.releaseDate )
                );

        if ( printAsError ) {
            log.error( msg );
        } else {
            log.warn( msg );
        }
    }

    private void fail(String msg,Throwable t) throws EnforcerRuleException {
        log.error(msg,t);
        throw new EnforcerRuleException(msg,t);		
    }

    private void fail(String msg) throws EnforcerRuleException {
        log.error(msg);
        throw new EnforcerRuleException(msg);		
    }

    private static String prettyPrint(ZonedDateTime dt) 
    {
        if ( dt == null ) {
            return "n/a";
        }
        ZonedDateTime converted = dt.withZoneSameInstant( ZoneId.systemDefault() ); 
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG,FormatStyle.LONG).format( converted );
    }

    private Age parseAge(String configKey, String configValue) throws EnforcerRuleException 
    {
        try 
        {
            final String trimmed= configValue != null ? configValue.trim() : configValue;
            final Matcher m = MAX_AGE_PATTERN.matcher( trimmed );
            if ( m.matches() ) 
            {
                final int value = Integer.parseInt( m.group(1) );
                if ( value < 0 ) {
                    final String message = "'"+configKey+"' must be >= 0 but was "+value;
                    log.error( message );
                    throw new EnforcerRuleException(message);                      
                }
                final AgeUnit unit = AgeUnit.parse( m.group(2) );
                return new Age(value,unit);
            } 
            else 
            {
                final String message = "Configuration error - not a valid '"+configKey+"' pattern: '"+configValue+"', must match regex '"+MAX_AGE_PATTERN_STRING+"'";
                log.error( message );
                throw new EnforcerRuleException(message);                
            }
        } 
        catch(EnforcerRuleException e) {
            throw e;
        }
        catch(Exception e) 
        {
            final String message = "Configuration error - not a valid '"+configKey+"' pattern: '"+configValue+"', must match regex '"+MAX_AGE_PATTERN_STRING+"'";
            e.printStackTrace();
            log.error( message );
            throw new EnforcerRuleException(message,e);
        }
    }

    @Override
    public boolean isCacheable()
    {
        return true;
    }

    @Override
    public boolean isResultValid(EnforcerRule cachedRule)
    {
        return true;
    }

    @Override
    public String getCacheId()
    {
        return warnAge+apiEndpoint+verbose+debug+rulesFile+maxAge;
    }

    private static File getParent(File file) 
    {
        return file.getParentFile();
    }

    private Blacklist loadBlacklist() throws JAXBException,ParseException, EnforcerRuleException {

        if ( rulesFile == null ) {
            return null;
        }

        if ( ! rulesFile.exists() && searchRulesInParentDirectories ) 
        {
            if ( verbose ) {
                log.info("Rules file "+rulesFile.getAbsolutePath()+" does not exist, searching parent folders");
            }
            String fileName = rulesFile.getName();
            File folder = getParent( rulesFile.getParentFile() );
            do 
            {
                folder = getParent( rulesFile.getParentFile() );                    
                rulesFile = new File(folder,fileName);
                if ( debug ) {
                    log.info("Trying "+rulesFile.getAbsolutePath());
                }                 
                if ( rulesFile.exists() && rulesFile.isFile() ) 
                {
                    break;
                }
            } while ( folder != null && folder.toPath().getNameCount() != 0 );
        }
        if ( ! rulesFile.exists() || ! rulesFile.isFile() ) {
            fail(rulesFile.getAbsolutePath()+" does not exist or is no regular file");
        }     

        if ( verbose ) {
            log.info("Using XML rules file "+rulesFile.getAbsolutePath());
        }        

        final Blacklist blacklist = new Blacklist();
        final Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
        final Ruleset result = (Ruleset) jaxbUnmarshaller.unmarshal(rulesFile);
        assertSupportedComparisonMethod(result.getComparisonMethod());
        List<Rule> rules = null;
        rules = result.getRules() != null ? result.getRules().getRule() : null;
        if ( rules != null ) {
            for ( Rule r : rules ) 
            {
                assertSupportedComparisonMethod(r.getComparisonMethod());
                final boolean hasIgnoredVersions = r.getIgnoreVersions() != null && r.getIgnoreVersions().getIgnoreVersion() != null;
                if ( hasIgnoredVersions ) {
                    assertMatcherTypeSupported(r.getIgnoreVersions().getIgnoreVersion());
                }
                if ( hasIgnoredVersions && StringUtils.isBlank( r.getArtifactId() ) ) 
                {
                    for ( IgnoreVersion v : r.getIgnoreVersions().getIgnoreVersion() ) {
                        blacklist.addIgnoredVersion(r.getGroupId(),v.getValue(),VersionMatcher.fromString( v.getType() ) );
                    }
                } 
                else if ( hasIgnoredVersions ) 
                {
                    for ( IgnoreVersion v : r.getIgnoreVersions().getIgnoreVersion() ) {
                        blacklist.addIgnoredVersion(r.getGroupId(),r.getArtifactId(),v.getValue(),VersionMatcher.fromString( v.getType() ) );
                    }
                }                    
            }
        }
        if ( result.getIgnoreVersions() != null ) {
            assertMatcherTypeSupported( result.getIgnoreVersions().getIgnoreVersion() );
        }

        // register global blacklist
        if ( result.getIgnoreVersions() != null && result.getIgnoreVersions().getIgnoreVersion() != null ) {
            for ( IgnoreVersion v : result.getIgnoreVersions().getIgnoreVersion() ) 
            {
                blacklist.addIgnoredVersion(v.getValue(),VersionMatcher.fromString( v.getType() ) );
            }
        }
        return blacklist;
    }

    private void assertMatcherTypeSupported(List<IgnoreVersion> list) throws ParseException 
    {
        if ( list != null ) 
        {
            for ( IgnoreVersion v : list ) 
            {
                final String matcher = v.getType();
                if (matcher != null && ! (matcher.equals("regex") | matcher.equals("exact") ) ) {
                    throw new ParseException("Sorry, rules file "+rulesFile.getAbsolutePath()+" "
                            + "contains unsupported value '"+matcher+"'for 'type' attribute of <ignoreVersion/> tag",-1);
                }
            }
        }
    }    

    private void assertSupportedComparisonMethod(String method) throws ParseException 
    {
        if (method != null && ! method.equals("maven") ) {
            throw new ParseException("Sorry, rules file "+rulesFile.getAbsolutePath()+" contains custom comparison method '"+method+"' but custom comparison methods are not supported by this plugin.",-1);
        }
    }
    
    private static LocalAPIClient getLocalAPIClient() 
    {
        synchronized(LOCAL_API_CLIENT_LOCK) 
        {
            if ( LOCAL_API_CLIENT == null ) {
                LOCAL_API_CLIENT = new LocalAPIClient();
                Runtime.getRuntime().addShutdownHook( new Thread( () -> {
                    synchronized(LOCAL_API_CLIENT_LOCK) 
                    {
                        try {
                            LOCAL_API_CLIENT.close();
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            LOCAL_API_CLIENT = null;
                        }
                    }
                }));
            }
            return LOCAL_API_CLIENT;
        }
    }
    
    private static RemoteApiClient getRemoteAPIClient(String endpoint,Protocol protocol,boolean debug) 
    {
        final String key = endpoint+protocol.name()+debug;
        synchronized(CLIENTS) 
        {
            RemoteApiClient existing = CLIENTS.get( key );
            if ( existing == null ) {
                existing = new RemoteApiClient(endpoint,protocol);
                existing.setDebugMode( debug );
                CLIENTS.put(key, existing );
                
                Runtime.getRuntime().addShutdownHook( new Thread( () -> {
                    synchronized(CLIENTS) 
                    {
                        RemoteApiClient client = CLIENTS.get( key );
                        if ( client != null ) {
                            try {
                                client.close();
                            } catch (Exception e) {
                                e.printStackTrace();
                            } finally {
                                CLIENTS.put(key,null);
                            }
                        }
                    }
                }));                
            }
            return existing;
        }
    }
}