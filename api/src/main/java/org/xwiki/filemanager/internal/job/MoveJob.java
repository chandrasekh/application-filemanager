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
package org.xwiki.filemanager.internal.job;

import java.util.Collection;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang3.ObjectUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.filemanager.File;
import org.xwiki.filemanager.FileSystem;
import org.xwiki.filemanager.Folder;
import org.xwiki.filemanager.Path;
import org.xwiki.filemanager.internal.reference.DocumentNameSequence;
import org.xwiki.filemanager.job.MoveRequest;
import org.xwiki.filemanager.job.OverwriteQuestion;
import org.xwiki.filemanager.reference.UniqueDocumentReferenceGenerator;
import org.xwiki.job.internal.AbstractJob;
import org.xwiki.job.internal.DefaultJobStatus;
import org.xwiki.model.reference.DocumentReference;

/**
 * Move files and folders to a different path, possibly renaming the target file or folder in case there is only one
 * item to move.
 * 
 * @version $Id$
 * @since 2.0M1
 */
@Component
@Named(MoveJob.JOB_TYPE)
public class MoveJob extends AbstractJob<MoveRequest, DefaultJobStatus<MoveRequest>>
{
    /**
     * The id of the job.
     */
    public static final String JOB_TYPE = "fileManager/move";

    /**
     * The error message logged when the folder destination of a move operation doesn't exist.
     */
    private static final String ERROR_DESTINATION_NOT_FOUND = "The destination folder [{}] doesn't exist.";

    /**
     * The pseudo file system.
     */
    @Inject
    protected FileSystem fileSystem;

    /**
     * Used to generate unique document references.
     */
    @Inject
    private UniqueDocumentReferenceGenerator uniqueDocRefGenerator;

    /**
     * Specifies whether all files with the same name are to be overwritten on not. When {@code true} all files with the
     * same name are overwritten. When {@code false} all files with the same name are skipped. If {@code null} then a
     * question is asked for each file.
     */
    private Boolean overwriteAll;

    @Override
    public String getType()
    {
        return JOB_TYPE;
    }

    @Override
    protected void runInternal() throws Exception
    {
        Collection<Path> paths = getRequest().getPaths();
        Path destination = getRequest().getDestination();
        if (paths != null && destination != null) {
            if (destination.getFolderReference() != null && fileSystem.exists(destination.getFolderReference())
                && destination.getFileReference() == null) {
                move(paths, destination.getFolderReference());
            } else if (paths.size() == 1 && destination.getFileReference() != null) {
                rename(paths.iterator().next(), destination);
            }
        }
    }

    /**
     * Moves a collection of files and folders to the destination folder.
     * 
     * @param paths the paths to the files and folders to move
     * @param destination the destination folder where to move the files and folders
     */
    private void move(Collection<Path> paths, DocumentReference destination)
    {
        notifyPushLevelProgress(paths.size());

        try {
            for (Path path : paths) {
                move(path, destination);
                notifyStepPropress();
            }
        } finally {
            notifyPopLevelProgress();
        }
    }

    /**
     * Moves the specified file or folder to the destination folder.
     * 
     * @param path the path to the file or folder to move
     * @param destination the destination folder
     */
    private void move(Path path, DocumentReference destination)
    {
        if (path.getFileReference() != null) {
            moveFile(path.getFileReference(), path.getFolderReference(), destination);
        } else if (path.getFolderReference() != null) {
            moveFolder(path.getFolderReference(), destination);
        }
    }

    /**
     * Moves a folder to another folder.
     * 
     * @param folderReference the folder to move
     * @param newParentReference the destination folder
     */
    private void moveFolder(DocumentReference folderReference, DocumentReference newParentReference)
    {
        if (isDescendantOrSelf(newParentReference, folderReference)) {
            this.logger.error("Cannot move [{}] to a sub-folder of itself.", folderReference);
            return;
        }

        Folder folder = fileSystem.getFolder(folderReference);
        if (folder != null && !ObjectUtils.equals(folder.getParentReference(), newParentReference)) {
            if (fileSystem.canEdit(folderReference)) {
                Folder newParent = fileSystem.getFolder(newParentReference);
                if (newParent != null) {
                    moveFolder(folder, newParent);
                } else {
                    this.logger.error(ERROR_DESTINATION_NOT_FOUND, newParentReference);
                }
            } else {
                this.logger.error("You are not allowed to move the folder [{}].", folderReference);
            }
        }
    }

    /**
     * @param aliceReference a folder reference
     * @param bobReference a folder reference
     * @return {@code true} if the first folder is a descendant of the second, {@code false} otherwise
     */
    protected boolean isDescendantOrSelf(DocumentReference aliceReference, DocumentReference bobReference)
    {
        DocumentReference parentReference = aliceReference;
        while (parentReference != null && !parentReference.equals(bobReference)) {
            Folder parent = fileSystem.getFolder(parentReference);
            if (parent == null) {
                return false;
            } else {
                parentReference = parent.getParentReference();
            }
        }
        return parentReference != null;
    }

    /**
     * Move a folder to a different folder.
     * 
     * @param folder the folder to move
     * @param newParent the destination folder
     */
    private void moveFolder(Folder folder, Folder newParent)
    {
        // Check if the new parent has a child folder with the same name.
        Folder child = getChildFolderByName(newParent, folder.getName());
        if (child != null) {
            mergeFolders(folder, child.getReference());
        } else {
            folder.setParentReference(newParent.getReference());
            fileSystem.save(folder);
        }
    }

    /**
     * Looks for a folder with the given name under the specified parent.
     * 
     * @param parent the parent folder
     * @param name the name to look for
     * @return a child folder with the given name, {@code null} if the parent doesn't have a child folder with the
     *         specified name
     */
    protected Folder getChildFolderByName(Folder parent, String name)
    {
        for (DocumentReference childReference : parent.getChildFolderReferences()) {
            Folder child = fileSystem.getFolder(childReference);
            if (child.getName().equals(name)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Moves the content of the source folder to the destination folder and then deletes the remaining empty source
     * folder.
     * 
     * @param source the folder whose content is moved
     * @param destination a reference to the destination folder
     */
    private void mergeFolders(Folder source, DocumentReference destination)
    {
        List<DocumentReference> childFolderReferences = source.getChildFolderReferences();
        List<DocumentReference> childFileReferences = source.getChildFileReferences();
        notifyPushLevelProgress(childFolderReferences.size() + childFileReferences.size() + 1);

        try {
            for (DocumentReference childReference : childFolderReferences) {
                moveFolder(childReference, destination);
                notifyStepPropress();
            }

            for (DocumentReference childReference : childFileReferences) {
                moveFile(childReference, source.getReference(), destination);
                notifyStepPropress();
            }

            // Delete the source folder if it's empty.
            if (source.getChildFolderReferences().isEmpty() && source.getChildFileReferences().isEmpty()) {
                if (fileSystem.canDelete(source.getReference())) {
                    fileSystem.delete(source.getReference());
                } else {
                    this.logger.error("You are not allowed to delete the folder [{}].", source.getReference());
                }
            }
            notifyStepPropress();
        } finally {
            notifyPopLevelProgress();
        }
    }

    /**
     * Moves a file to a different folder. Since a file can have multiple parent folders, the specified parent is
     * replaced with the new folder.
     * 
     * @param fileReference the file to move
     * @param oldParentReference the parent folder to replace
     * @param newParentReference the new parent folder
     */
    private void moveFile(DocumentReference fileReference, DocumentReference oldParentReference,
        DocumentReference newParentReference)
    {
        File file = fileSystem.getFile(fileReference);
        if (file != null && !ObjectUtils.equals(oldParentReference, newParentReference)) {
            if (fileSystem.canEdit(fileReference)) {
                Folder newParent = fileSystem.getFolder(newParentReference);
                if (newParent != null) {
                    moveFile(file, oldParentReference, newParent);
                } else {
                    this.logger.error(ERROR_DESTINATION_NOT_FOUND, newParentReference);
                }
            } else {
                this.logger.error("You are not allowed to move the file [{}].", fileReference);
            }
        }
    }

    /**
     * Move a file to a different parent folder.
     * 
     * @param file the file to be moved
     * @param oldParentReference the parent folder to replace
     * @param newParent the new parent folder
     */
    private void moveFile(File file, DocumentReference oldParentReference, Folder newParent)
    {
        // Check if a file with the same name already exits under the new parent folder.
        if (!prepareOverwrite(file.getName(), newParent, file.getReference())) {
            return;
        }

        Collection<DocumentReference> parentReferences = file.getParentReferences();
        boolean save = parentReferences.remove(oldParentReference);
        save |= parentReferences.add(newParent.getReference());
        if (save) {
            fileSystem.save(file);
        }
    }

    protected boolean prepareOverwrite(String fileName, Folder parentFolder, DocumentReference newFileReference)
    {
        File child = getChildFileByName(parentFolder, fileName);
        if (child != null) {
            boolean hasMoreParents = child.getParentReferences().size() > 1;
            if ((hasMoreParents && fileSystem.canEdit(child.getReference()))
                || (!hasMoreParents && fileSystem.canDelete(child.getReference()))) {
                if (shouldOverwrite(newFileReference, child.getReference())) {
                    deleteFile(child, parentFolder.getReference());
                } else {
                    return false;
                }
            } else {
                this.logger.error("You are not allowed to overwrite the file [{}].", child.getReference());
                return false;
            }
        }
        return true;
    }

    /**
     * Looks for a file with the given name under the specified parent.
     * 
     * @param parent the parent folder
     * @param name the name to look for
     * @return a child file with the given name, {@code null} if the parent doesn't have a child file with the specified
     *         name
     */
    protected File getChildFileByName(Folder parent, String name)
    {
        for (DocumentReference childReference : parent.getChildFileReferences()) {
            File child = fileSystem.getFile(childReference);
            if (child.getName().equals(name)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Ask whether to overwrite or not the destination file with the source file.
     * 
     * @param source the file being moved or copied
     * @param destination a file with the same name that exists in the destination folder
     * @return {@code true} to overwrite the file, {@code false} otherwise
     */
    protected boolean shouldOverwrite(DocumentReference source, DocumentReference destination)
    {
        if (getRequest().isInteractive() && getStatus() != null) {
            if (overwriteAll == null) {
                OverwriteQuestion question = new OverwriteQuestion(source, destination);
                try {
                    getStatus().ask(question);
                    if (!question.isAskAgain()) {
                        overwriteAll = question.isOverwrite();
                    }
                    return question.isOverwrite();
                } catch (InterruptedException e) {
                    this.logger.warn("Overwrite question has been interrupted.");
                }
            } else {
                return overwriteAll;
            }
        }

        return false;
    }

    /**
     * Deletes the given file from the specified parent folder. If the file has no other parent folders then it is moved
     * to the recycle bin. Otherwise it is only removed from the specified parent folder.
     * 
     * @param file the file to be deleted
     * @param parentReference the folder from where to delete the file
     */
    private void deleteFile(File file, DocumentReference parentReference)
    {
        Collection<DocumentReference> parentReferences = file.getParentReferences();
        parentReferences.remove(parentReference);
        if (parentReferences.isEmpty()) {
            fileSystem.delete(file.getReference());
        } else {
            fileSystem.save(file);
        }
    }

    /**
     * Rename a file or a folder.
     * 
     * @param oldPath the path to rename
     * @param newPath the new path
     */
    private void rename(Path oldPath, Path newPath)
    {
        if (oldPath != null) {
            if (oldPath.getFileReference() != null) {
                renameFile(oldPath, newPath);
            } else if (oldPath.getFolderReference() != null) {
                renameFolder(oldPath.getFolderReference(), newPath);
            }
        }
    }

    /**
     * Renames a folder.
     * 
     * @param oldReference the old folder reference
     * @param newPath the new folder path
     */
    private void renameFolder(DocumentReference oldReference, Path newPath)
    {
        Folder folder = fileSystem.getFolder(oldReference);
        if (folder != null) {
            if (fileSystem.canDelete(oldReference)) {
                DocumentReference newParentReference = newPath.getFolderReference();
                if (newParentReference == null) {
                    // If the new parent is not specified we assume the parent doesn't change.
                    newParentReference = folder.getParentReference();
                }
                if (ObjectUtils.equals(newParentReference, folder.getParentReference())
                    && newPath.getFileReference().equals(oldReference)) {
                    // No move (same parent) and no rename (same reference).
                    return;
                }
                if (newParentReference != null) {
                    moveAndRenameFolder(folder, new Path(newParentReference, newPath.getFileReference()));
                } else {
                    // Rename an orphan folder.
                    // The file reference from the new path is actually used as the new folder reference.
                    renameFolder(folder, newPath.getFileReference());
                }
            } else {
                this.logger.error("You are not allowed to rename the folder [{}].", oldReference);
            }
        }
    }

    /**
     * Moves and renames a folder.
     * 
     * @param folder the folder to move and rename
     * @param newPath the new path
     */
    private void moveAndRenameFolder(Folder folder, Path newPath)
    {
        Folder newParent = fileSystem.getFolder(newPath.getFolderReference());
        Folder child = getChildFolderByName(newParent, newPath.getFileReference().getName());
        if (child == null) {
            // Move the folder first, if needed, because the rename must be performed inside the right parent.
            if (!newParent.getReference().equals(folder.getParentReference())) {
                folder.setParentReference(newParent.getReference());
                fileSystem.save(folder);
            }

            // Rename the folder.
            if (!newPath.getFileReference().equals(folder.getReference())) {
                // The file reference from the new path is actually used as the new folder reference.
                renameFolder(folder, newPath.getFileReference());
            }
        } else {
            this.logger.error("A folder with the same name [{}] already exists under [{}]", newPath.getFileReference()
                .getName(), newParent.getReference());
        }
    }

    /**
     * Rename the given folder. The new folder reference may not be exactly the given one, in case a document with the
     * same reference already exists on the same drive (XWiki space), in which case a counter is added to the folder id.
     * 
     * @param folder the folder to rename
     * @param newReference the desired new folder reference
     */
    private void renameFolder(Folder folder, DocumentReference newReference)
    {
        DocumentReference actualNewReference = getUniqueReference(newReference);
        if (fileSystem.canEdit(actualNewReference)) {
            // Update the folder reference.
            fileSystem.rename(folder.getReference(), actualNewReference);

            // Update the folder pretty name.
            Folder newFolder = fileSystem.getFolder(actualNewReference);
            newFolder.setName(newReference.getName());
            fileSystem.save(newFolder);

            // Update the child folders.
            for (DocumentReference childFolderReference : folder.getChildFolderReferences()) {
                Folder childFolder = fileSystem.getFolder(childFolderReference);
                childFolder.setParentReference(actualNewReference);
                fileSystem.save(childFolder);
            }

            // Update the child files.
            for (DocumentReference childFileReference : folder.getChildFileReferences()) {
                File childFile = fileSystem.getFile(childFileReference);
                childFile.getParentReferences().remove(folder.getReference());
                childFile.getParentReferences().add(actualNewReference);
                fileSystem.save(childFile);
            }
        } else {
            this.logger.error("You are not allowed to create the folder [{}].", actualNewReference);
        }
    }

    /**
     * Renames a file.
     * 
     * @param oldPath the old file path
     * @param newPath the new file path
     */
    private void renameFile(Path oldPath, Path newPath)
    {
        File file = fileSystem.getFile(oldPath.getFileReference());
        if (file != null) {
            if (fileSystem.canDelete(file.getReference())) {
                moveAndRenameFile(file, oldPath.getFolderReference(), newPath);
            } else {
                this.logger.error("You are not allowed to rename the file [{}].", file.getReference());
            }
        }
    }

    /**
     * Moves and renames the given file.
     * 
     * @param file the file to move and rename
     * @param parentReference the parent folder the file may be moved from (a file can have multiple parent folders so
     *            we need to specify the source parent folder)
     * @param newPath the new file path
     */
    private void moveAndRenameFile(File file, DocumentReference parentReference, Path newPath)
    {
        // Move the file first, if needed, because the rename must be performed inside the right parents.
        if (newPath.getFolderReference() != null && parentReference != null
            && !newPath.getFolderReference().equals(parentReference)) {
            Collection<DocumentReference> parentReferences = file.getParentReferences();
            boolean save = parentReferences.remove(parentReference);
            save |= parentReferences.add(newPath.getFolderReference());
            if (save) {
                fileSystem.save(file);
            }
        }

        // Rename the file.
        if (!file.getReference().equals(newPath.getFileReference())) {
            renameFile(file, newPath.getFileReference());
        }
    }

    /**
     * Renames the given file.
     * 
     * @param file the file to rename
     * @param newReference the new file reference
     */
    private void renameFile(File file, DocumentReference newReference)
    {
        if (!file.getName().equals(newReference.getName())) {
            for (DocumentReference parentReference : file.getParentReferences()) {
                Folder folder = fileSystem.getFolder(parentReference);
                if (folder != null && getChildFileByName(folder, newReference.getName()) != null) {
                    this.logger.error("A file with the same name [{}] already exists under [{}]",
                        newReference.getName(), parentReference);
                    return;
                }
            }
        }

        DocumentReference actualNewReference = getUniqueReference(newReference);
        if (fileSystem.canEdit(actualNewReference)) {
            // Update the file reference.
            fileSystem.rename(file.getReference(), actualNewReference);

            // Update the file pretty name.
            File newFile = fileSystem.getFile(actualNewReference);
            newFile.setName(newReference.getName());
            fileSystem.save(newFile);
        } else {
            this.logger.error("You are not allowed to create the file [{}].", actualNewReference);
        }
    }

    /**
     * @param documentReference a document reference
     * @return a unique document references based on the given reference (adds a counter if needed)
     */
    protected DocumentReference getUniqueReference(DocumentReference documentReference)
    {
        return this.uniqueDocRefGenerator.generate(documentReference.getLastSpaceReference(), new DocumentNameSequence(
            documentReference.getName()));
    }
}
