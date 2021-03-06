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
package org.xwiki.filemanager.internal;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.filemanager.File;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;

import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;

/**
 * Default {@link File} implementation, based on XWiki document.
 * 
 * @version $Id$
 * @since 2.0M1
 */
@Component
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class DefaultFile extends AbstractDocument implements File
{
    /**
     * The reference to the Tag class which is used to store the parent folders.
     */
    static final EntityReference TAG_CLASS_REFERENCE = new EntityReference("TagClass", EntityType.DOCUMENT,
        new EntityReference("XWiki", EntityType.SPACE));

    /**
     * The 'tags' property of {@link #TAG_CLASS_REFERENCE}.
     */
    static final String PROPERTY_TAGS = "tags";

    /**
     * The cached collection of references to parent folders.
     */
    private Collection<DocumentReference> parentReferences;

    @Override
    public String getName()
    {
        List<XWikiAttachment> attachments = getDocument().getAttachmentList();
        if (attachments.size() > 0) {
            return attachments.get(0).getFilename();
        } else {
            return super.getName();
        }
    }

    @Override
    public void setName(String name)
    {
        // Avoid cloning the underlying document and modifying the attachment list if it's not really needed.
        if (getName().equals(name)) {
            return;
        }

        super.setName(name);

        XWikiDocument document = getClonedDocument();
        List<XWikiAttachment> attachments = document.getAttachmentList();
        if (attachments.size() > 0) {
            XWikiAttachment oldAttachment = attachments.get(0);
            try {
                document.removeAttachment(oldAttachment, false);
                document.addAttachment(name, oldAttachment.getContentInputStream(getContext()), getContext());
            } catch (Exception e) {
                logger.error("Failed to rename file [{}] to [{}].", oldAttachment.getReference(), name, e);
            }
        }
    }

    @Override
    public Collection<DocumentReference> getParentReferences()
    {
        if (parentReferences == null) {
            parentReferences = retrieveParentReferences();
        }
        return parentReferences;
    }

    /**
     * Updates the list of parent references on the underlying document.
     */
    void updateParentReferences()
    {
        if (parentReferences == null) {
            return;
        }

        // A file can have multiple parent folders, which are declared using tags because the underlying document can
        // have only one real parent.
        XWikiDocument document = getClonedDocument();
        BaseObject tagObject = document.getXObject(TAG_CLASS_REFERENCE);
        if (tagObject == null) {
            tagObject = new BaseObject();
            tagObject.setXClassReference(TAG_CLASS_REFERENCE);
            document.addXObject(tagObject);
        }

        List<String> tags = new ArrayList<String>();
        for (DocumentReference parentReference : parentReferences) {
            tags.add(parentReference.getName());
        }
        tagObject.setStringListValue(PROPERTY_TAGS, tags);

        // We set the first parent folder as the parent of the underlying document to ensure the document hierarchy is
        // still displayed nicely outside of the file manager. This also helps us detect orphan files more easily.
        if (parentReferences.isEmpty()) {
            document.setParentReference((EntityReference) null);
        } else {
            DocumentReference parentReference = parentReferences.iterator().next();
            if (parentReference.getWikiReference().equals(getReference().getWikiReference())) {
                document.setParentReference(parentReference.removeParent(parentReference.getWikiReference()));
            } else {
                document.setParentReference(parentReference.extractReference(EntityType.DOCUMENT));
            }
        }

        parentReferences = null;
    }

    /**
     * @return the saved collection of parent folder references
     */
    private Collection<DocumentReference> retrieveParentReferences()
    {
        Collection<DocumentReference> references = new ArrayList<DocumentReference>();
        BaseObject tagObject = getDocument().getXObject(TAG_CLASS_REFERENCE);
        if (tagObject != null) {
            try {
                List<String> tags = (List<String>) ((BaseProperty) tagObject.get(PROPERTY_TAGS)).getValue();
                if (tags != null) {
                    for (String tag : tags) {
                        references.add(new DocumentReference(tag, getReference().getLastSpaceReference()));
                    }
                }
            } catch (XWikiException e) {
                logger.error("Failed to retrieve the list of tags for file [{}].", getReference(), e);
            }
        }
        return references;
    }

    @Override
    public InputStream getContent()
    {
        List<XWikiAttachment> attachments = getDocument().getAttachmentList();
        if (attachments.size() > 0) {
            try {
                return attachments.get(0).getContentInputStream(getContext());
            } catch (XWikiException e) {
                logger.warn("Failed to get the file content input stream for [{}]. Returning empty content instead.",
                    getReference(), e);
                // Fail-safe.
                return new ByteArrayInputStream(new byte[] {});
            }
        } else {
            // No attachment found. Fail-safe pretending to have an empty attachment.
            return new ByteArrayInputStream(new byte[] {});
        }
    }
}
