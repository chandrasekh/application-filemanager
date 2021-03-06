/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.filemanager.job;

import java.util.Collection;
import java.util.List;

import org.xwiki.component.annotation.Role;
import org.xwiki.filemanager.Path;
import org.xwiki.job.JobException;
import org.xwiki.job.event.status.JobStatus;
import org.xwiki.model.reference.AttachmentReference;
import org.xwiki.stability.Unstable;

/**
 * Exposes APIs to run file system jobs.
 * 
 * @version $Id$
 * @since 2.0M1
 */
@Role
@Unstable
public interface FileManager
{
    /**
     * The string used as a prefix for all file system jobs.
     */
    String JOB_ID_PREFIX = "file-manager";

    /**
     * Schedules a job to move the specified files and folders to the given destination.
     * 
     * @param paths the files and folders to move
     * @param destination where to move the specified files and folders
     * @return the id of the move job that has been scheduled
     * @throws JobException if scheduling the move job fails
     */
    String move(Collection<Path> paths, Path destination) throws JobException;

    /**
     * Schedules a job to copy the specified files and folders to the given destination.
     * 
     * @param paths the files and folders to copy
     * @param destination where to copy the specified files and folders
     * @return the id of the copy job that has been scheduled
     * @throws JobException if scheduling the copy job fails
     */
    String copy(Collection<Path> paths, Path destination) throws JobException;

    /**
     * Schedules a job to delete the specified files and folders.
     * 
     * @param paths the files and folders to delete
     * @return the id of the delete job that has been scheduled
     * @throws JobException if scheduling the delete job fails
     */
    String delete(Collection<Path> paths) throws JobException;

    /**
     * Packs the specified files and folders in a single ZIP archive that is written in the specified output file.
     * <p>
     * The {@link org.xwiki.model.reference.DocumentReference} part of the given {@link AttachmentReference} represents
     * the document that is going to be used to access the output ZIP file. This means that only users with view right
     * on this document can access the output file. The {@code name} property of the given {@link AttachmentReference}
     * will be used as the name of the output ZIP file.
     * <p>
     * The output file is a temporary file (deleted automatically when the server is stopped) that can be accessed
     * through the 'temp' action, e.g.: {@code /xwiki/temp/Space/Page/filemanager/file.zip} .
     * 
     * @param paths the files and folders to be packed
     * @param outputFileReference the reference to the temporary output file
     * @return the id of the pack job that has been scheduled
     * @throws JobException if scheduling the pack job fails
     * @since 2.0M2
     */
    String pack(Collection<Path> paths, AttachmentReference outputFileReference) throws JobException;

    /**
     * @param jobId the job whose status to return
     * @return the status of the specified job
     */
    JobStatus getJobStatus(String jobId);

    /**
     * @return the list of jobs that are running or are pending for execution
     */
    List<String> getActiveJobs();
}
