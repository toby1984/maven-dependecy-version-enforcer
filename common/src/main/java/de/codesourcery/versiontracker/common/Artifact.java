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
package de.codesourcery.versiontracker.common;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

/**
 * Maven artifact coordinates.
 *
 * @author tobias.gierke@code-sourcery.de
 */
public class Artifact
{
    private static final String[] EMPTY_ARRAY=new String[0];
    
    /**
     * Version comparator.
     * 
     * Note that this one just roughly mimics what Maven is actually doing, I didn't spend
     * time looking into the actual implementation there. For my needs it's just "Good Enough(tm)".
     */
    public static final Comparator<String> VERSION_COMPARATOR = (a,b) -> 
    {
    	final String[] partsA = Artifact.splitVersionNumber( a );
    	final String[] partsB = Artifact.splitVersionNumber( b );
    	final int min = Math.min(partsA.length,partsB.length);
    	int result = 0;
    	for ( int i = 0 ; i < min ; i++ ) 
    	{
    		int tmp;
    		try {
    			tmp = Integer.compare( Integer.parseInt( partsA[i] ) , Integer.parseInt( partsB[i] ));
    		} catch(Exception e) {
    			tmp = partsA[i].compareTo(partsB[i]);
    		}
    		if ( tmp != 0 ) {
    			return tmp;
    		}
    	}
    	if ( partsA.length == partsB.length ) {
    		return result;
    	}
    	if ( partsA.length > partsB.length ) {
    		return 1;
    	}
    	return -1; 
    };

    public String groupId;
    public String version;
    public String artifactId;
    private String classifier;
    public String type;
    
    public void serialize(BinarySerializer serializer) throws IOException {
        serializer.writeString(groupId);
        serializer.writeString(version);
        serializer.writeString(artifactId);
        serializer.writeString(classifier);
        serializer.writeString(type);
    }
    
    public static Artifact deserialize(BinarySerializer serializer) throws IOException 
    {
        final Artifact a = new Artifact();
        a.groupId = serializer.readString();
        a.version = serializer.readString();
        a.artifactId = serializer.readString();
        a.classifier = serializer.readString();
        a.type = serializer.readString();
        return a;
    }
    
    public Artifact() {
    }
    
    public Artifact(Artifact artifact)
    {
        this.groupId = artifact.groupId;
        this.version = artifact.version;
        this.artifactId = artifact.artifactId;
        this.classifier = artifact.classifier;
        this.type = artifact.type;
    }
    
    public boolean matchesGroupIdAndArtifactId(Artifact other) 
    {
        return this.groupId.equals( other.groupId ) && this.artifactId.equals( other.artifactId );
    }

    @Override
    public int hashCode()
    {
        int result = 31 + ((artifactId == null) ? 0 : artifactId.hashCode());
        result = 31 * result + ((classifier == null) ? 0 : classifier.hashCode());
        result = 31 * result + ((groupId == null) ? 0 : groupId.hashCode());
        result = 31 * result + ((type == null) ? 0 : type.hashCode());
        return 31 * result + ((version == null) ? 0 : version.hashCode());
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if ( obj instanceof Artifact) 
        {
            final Artifact a = (Artifact) obj;
            return Objects.equals( this.groupId, a.groupId ) &&
                    Objects.equals( this.artifactId, a.artifactId ) &&
                    Objects.equals( this.classifier, a.classifier ) &&
                    Objects.equals( this.type, a.type ) &&
                    Objects.equals( this.version, a.version );
        }
        return false;
    }

    public boolean hasVersionNumber() {
        return StringUtils.isNotBlank(this.version);
    }
    
    public boolean hasSnapshotVersion() {
        return isSnapshotVersion(this.version);
    }
    
    public boolean hasReleaseVersion() {
        return isReleaseVersion(this.version);
    }    
    
    public static boolean isReleaseVersion(String version) {
        return ! isSnapshotVersion(version);
    }
    
    public static boolean isSnapshotVersion(String version) 
    {
    	final String[] parts = splitVersionNumber( version );
        return parts.length > 0 && parts[parts.length-1].startsWith("-");
    }
    
    public static String[] splitVersionNumber(String number) {
    	if ( number == null || number.trim().length() == 0 ) {
    		return EMPTY_ARRAY;
    	}    	
    	final List<String> parts = new ArrayList<>();
    	final StringBuilder  buffer = new StringBuilder();
    	for ( int len = number.length() ,i=0 ; i < len ; i++ ) {
    		final char c = number.charAt(i);
    		if ( Character.isDigit( c ) ) {
    			buffer.append( c );
    		} else {
    	        if ( buffer.length() > 0 ) {
    	        	parts.add( buffer.toString() );
    	        	buffer.setLength(0);
    	        }
    	        if ( c != '.' ) {
    	        	parts.add( number.substring(i, number.length()));
    	        	break;
    	        }
    		}
    	}
    	if ( buffer.length() > 0 ) {
    		parts.add(buffer.toString());
    	}
    	return parts.toArray(new String[0]);
    }
    
    public Artifact copy() { 
        return new Artifact(this);
    }
    
    public boolean matchesExcludingVersion(Artifact other) 
    {
        return 
                Objects.equals( this.groupId ,other.groupId ) &&
                Objects.equals( this.artifactId ,other.artifactId ) &&
                Objects.equals( this.classifier ,other.classifier ) &&
                Objects.equals( this.type ,other.type );
    }

    public static int hashCode(Artifact a)
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((a.artifactId == null) ? 0 : a.artifactId.hashCode());
        result = prime * result + ((a.classifier == null) ? 0 : a.classifier.hashCode());
        result = prime * result + ((a.groupId == null) ? 0 : a.groupId.hashCode());
        result = prime * result + ((a.type == null) ? 0 : a.type.hashCode());
        return result;
    }

    @Override
    public String toString()
    {
        if ( classifier == null ) {
            return "Artifact " + groupId + ":" + artifactId + ":"+version+":"+type;
        }
        return "Artifact " + groupId + ":" + artifactId + ":"+version+":"+type+":"+classifier;
    }

    public String getClassifier()
    {
        return classifier;
    }

    public void setClassifier(String classifier)
    {
        if ("null".equals( classifier ) ) {
            throw new IllegalArgumentException("GOT YOU ,offender: "+this);
        }
        this.classifier = classifier;
    }         
}