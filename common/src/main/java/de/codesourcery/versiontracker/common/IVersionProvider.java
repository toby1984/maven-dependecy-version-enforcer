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

/**
 * Responsible for retrieving artifact metadata and applying it to a {@link VersionInfo} instance.
 * 
 * Implementations of this interface <b>have</b> to be thread-safe.
 */
public interface IVersionProvider
{
    public static enum UpdateResult 
    {
        /**
         * Artifact was not found.
         */
        ARTIFACT_NOT_FOUND,
        /**
         * Nothing changed on the server.
         */
        NO_CHANGES_ON_SERVER,
        /**
         * Some fields of the {@link VersionInfo} got updated.
         */
        UPDATED,
        /**
         * No artifact metadata was fetched because the artifact was completely blacklisted.
         * @see IBlacklistCheck#isArtifactBlacklisted(Artifact)
         */
        BLACKLISTED,
        /**
         * Retrieving artifact metadata failed.
         */
        ERROR
    }
    
    public static enum Mode 
    {
        UPDATE_ALL,
        FETCH_RELEASE_DATE_ONLY
    }
    
    /**
     * Try to update version information.
     * 
     * This method must be <b>thread-safe</b>.
     * 
     * @param info
     * @param versionNumber version to look up release date for
     * 
     * @return 
     * @throws IOException 
     */
    public UpdateResult update(VersionInfo info,String versionNumber) throws IOException;
}