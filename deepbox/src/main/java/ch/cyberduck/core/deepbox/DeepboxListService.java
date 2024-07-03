package ch.cyberduck.core.deepbox;

/*
 * Copyright (c) 2002-2024 iterate GmbH. All rights reserved.
 * https://cyberduck.io/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

import ch.cyberduck.core.AbstractPath;
import ch.cyberduck.core.Acl;
import ch.cyberduck.core.AttributedList;
import ch.cyberduck.core.ListProgressListener;
import ch.cyberduck.core.ListService;
import ch.cyberduck.core.LocaleFactory;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.PathAttributes;
import ch.cyberduck.core.PathNormalizer;
import ch.cyberduck.core.deepbox.io.swagger.client.ApiException;
import ch.cyberduck.core.deepbox.io.swagger.client.api.BoxRestControllerApi;
import ch.cyberduck.core.deepbox.io.swagger.client.model.Box;
import ch.cyberduck.core.deepbox.io.swagger.client.model.Boxes;
import ch.cyberduck.core.deepbox.io.swagger.client.model.DeepBox;
import ch.cyberduck.core.deepbox.io.swagger.client.model.DeepBoxes;
import ch.cyberduck.core.deepbox.io.swagger.client.model.Node;
import ch.cyberduck.core.deepbox.io.swagger.client.model.NodeContent;
import ch.cyberduck.core.exception.AccessDeniedException;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.preferences.HostPreferences;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.MessageFormat;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static ch.cyberduck.core.deepbox.DeepboxAttributesFinderFeature.*;

public class DeepboxListService implements ListService {

    private static final Logger log = LogManager.getLogger(DeepboxListService.class);
    private final DeepboxSession session;
    private final DeepboxIdProvider fileid;
    private final int chunksize;
    private final DeepboxAttributesFinderFeature attributes;

    public DeepboxListService(final DeepboxSession session, final DeepboxIdProvider fileid) {
        this.session = session;
        this.fileid = fileid;
        this.chunksize = new HostPreferences(session.getHost()).getInteger("deepbox.listing.chunksize");
        this.attributes = new DeepboxAttributesFinderFeature(session, fileid);
    }

    @Override
    public AttributedList<Path> list(final Path directory, final ListProgressListener listener) throws BackgroundException {
        final AttributedList<Path> list = new AttributedList<>();
        final String deepBoxNodeId = fileid.getDeepBoxNodeId(directory);
        final String boxNodeId = fileid.getBoxNodeId(directory);
        final String thirdLevelId = fileid.getThirdLevelId(directory);
        int offset = 0;
        int size = 0;
        final HashSet<String> closed = new HashSet<>();
        try {
            final BoxRestControllerApi api = new BoxRestControllerApi(this.session.getClient());
            if(directory.isRoot()) {
                do {
                    final DeepBoxes deepBoxes = api.listDeepBoxes(offset, this.chunksize, "name asc", null);
                    for(final DeepBox deepBox : deepBoxes.getDeepBoxes()) {
                        list.add(new Path(directory, PathNormalizer.name(deepBox.getName()), EnumSet.of(Path.Type.directory, Path.Type.volume),
                                attributes.toAttributes(deepBox))
                        );
                    }
                    listener.chunk(directory, list);
                    size = deepBoxes.getSize();
                    offset += this.chunksize;
                }
                while(offset < size);
            }
            else if(new DeepboxPathContainerService().isDeepbox(directory)) { // in DeepBox
                do {
                    final Boxes boxes = api.listBoxes(UUID.fromString(directory.attributes().getFileId()), offset, this.chunksize, "name asc", null);
                    for(final Box box : boxes.getBoxes()) {
                        list.add(new Path(directory, PathNormalizer.name(box.getName()), EnumSet.of(Path.Type.directory, Path.Type.volume),
                                attributes.toAttributes(box))
                        );
                    }
                    listener.chunk(directory, list);
                    size = boxes.getSize();
                    offset += this.chunksize;
                }
                while(offset < size);
            }
            else if(new DeepboxPathContainerService().isBox(directory)) { // in Box
                // TODO (7) i18n
                final Box box = api.getBox(UUID.fromString(deepBoxNodeId), UUID.fromString(boxNodeId));
                if(box.getBoxPolicy().isCanListQueue()) {
                    final Path inbox = new Path(directory, PathNormalizer.name(INBOX), EnumSet.of(Path.Type.directory, Path.Type.volume)).withAttributes(
                            new PathAttributes().withFileId(fileid.getFileId(new Path(directory, PathNormalizer.name(INBOX), EnumSet.of(AbstractPath.Type.directory, AbstractPath.Type.volume))))
                    );
                    list.add(inbox.withAttributes(attributes.toAttributesThirdLevel(inbox)));
                }
                if(box.getBoxPolicy().isCanListFilesRoot()) {
                    final Path documents = new Path(directory, PathNormalizer.name(DOCUMENTS), EnumSet.of(Path.Type.directory, Path.Type.volume)).withAttributes(
                            new PathAttributes().withFileId(fileid.getFileId(new Path(directory, PathNormalizer.name(DOCUMENTS), EnumSet.of(AbstractPath.Type.directory, AbstractPath.Type.volume))))
                    );
                    list.add(documents.withAttributes(attributes.toAttributesThirdLevel(documents)));
                }
                if(box.getBoxPolicy().isCanAccessTrash()) {
                    final Path trash = new Path(directory, PathNormalizer.name(TRASH), EnumSet.of(Path.Type.directory, Path.Type.volume)).withAttributes(
                            new PathAttributes().withFileId(fileid.getFileId(new Path(directory, PathNormalizer.name(TRASH), EnumSet.of(AbstractPath.Type.directory, AbstractPath.Type.volume))))
                    );
                    list.add(trash.withAttributes(attributes.toAttributesThirdLevel(trash)));
                }
                listener.chunk(directory, list);
            }
            else if(new DeepboxPathContainerService().isThirdLevel(directory)) { // in Inbox/Documents/Trash
                // N.B. although Documents and Trash have a nodeId, calling the listFiles1/listTrash1 API with parentNode fails!
                if(new DeepboxPathContainerService().isInInbox(directory)) {
                    do {
                        try {
                            final NodeContent inbox = api.listQueue(UUID.fromString(deepBoxNodeId),
                                    UUID.fromString(boxNodeId),
                                    null,
                                    offset, this.chunksize, "displayName asc");
                            listChunk(directory, inbox, list, closed);
                            listener.chunk(directory, list);
                            size = inbox.getSize();
                            offset += this.chunksize;
                        }
                        catch(final ApiException e) {
                            if(e.getCode() != 403) {
                                throw e;
                            }
                            // inbox not visible if 403
                        }
                    }
                    while(offset < size);
                }
                else if(new DeepboxPathContainerService().isInDocuments(directory)) {
                    do {
                        try {
                            final NodeContent files = api.listFiles(
                                    UUID.fromString(deepBoxNodeId),
                                    UUID.fromString(boxNodeId),
                                    offset, this.chunksize, "displayName asc"
                            );
                            listChunk(directory, files, list, closed);
                            listener.chunk(directory, list);
                            size = files.getSize();
                            offset += this.chunksize;
                        }
                        catch(final ApiException e) {
                            if(e.getCode() != 403) {
                                throw e;
                            }
                            // TODO (12) add test
                            // documents not visible if 403
                        }
                    }
                    while(offset < size);
                }
                else if(new DeepboxPathContainerService().isInTrash(directory)) {
                    do {
                        try {
                            final NodeContent trashFiles = api.listTrash(
                                    UUID.fromString(deepBoxNodeId),
                                    UUID.fromString(boxNodeId),
                                    offset, this.chunksize, "displayName asc"
                            );
                            listChunk(directory, trashFiles, list, closed);
                            listener.chunk(directory, list);
                            size = trashFiles.getSize();
                            offset += this.chunksize;
                        }
                        catch(final ApiException e) {
                            if(e.getCode() != 403) {
                                throw e;
                            }
                            // TODO (12) add test
                            // trash not visible if 403
                        }
                    }
                    while(offset < size);
                }
            }
            else { // in subfolder of  Documents/Trash (Inbox has no subfolders)
                final String nodeId = fileid.getFileId(directory);
                if(new DeepboxPathContainerService().isInDocuments(directory)) {
                    do {
                        final NodeContent files = api.listFiles1(
                                UUID.fromString(deepBoxNodeId),
                                UUID.fromString(boxNodeId),
                                UUID.fromString(nodeId),
                                offset, this.chunksize, "displayName asc"
                        );
                        listChunk(directory, files, list, closed);
                        listener.chunk(directory, list);
                        size = files.getSize();
                        offset += this.chunksize;
                    }
                    while(offset < size);
                }
                else if(new DeepboxPathContainerService().isInTrash(directory)) {
                    do {
                        final NodeContent files = api.listTrash1(
                                UUID.fromString(deepBoxNodeId),
                                UUID.fromString(boxNodeId),
                                UUID.fromString(nodeId),
                                offset, this.chunksize, "displayName asc"
                        );
                        listChunk(directory, files, list, closed);
                        listener.chunk(directory, list);
                        size = files.getSize();
                        offset += this.chunksize;
                    }
                    while(offset < size);
                }
            }
        }
        catch(ApiException e) {
            throw new BackgroundException(e);
        }
        return list;
    }

    // list by modifiedTime desc to keep only most recent with the same name
    private void listChunk(final Path directory, final NodeContent inbox, final AttributedList<Path> list, final Set<String> closed) throws ApiException {
        for(final Node node : inbox.getNodes()) {
            final String name = PathNormalizer.name(node.getDisplayName());
            final Path path = new Path(directory, name, EnumSet.of(node.getType() == Node.TypeEnum.FILE ? Path.Type.file : Path.Type.directory))
                    .withAttributes(attributes.toAttributes(node));
            // remove duplicates
            if(!closed.contains(name)) {
                list.add(path);
                // update fileid to latest nodeId for the name
                this.fileid.cache(path, node.getNodeId().toString());
            }
            else {
                // remove from list and cache
                final Path last = list.get(list.size() - 1);
                if(last.getName().equals(name)) {
                    // Usually, the last element in the list should be the duplicate due to listing by file name.
                    list.remove(last);
                    this.fileid.cache(last, null);
                }
                else {
                    // Due to path normalization, the path to remove might not be the last one in the listing.
                    // Should be very rare, so searching the list O(n) should be fine.
                    final Path previous = list.find(p -> p.getName().equals(name));
                    if(previous != null) {
                        list.remove(previous);
                        this.fileid.cache(previous, null);
                    }
                }
            }
            closed.add(name);
        }
    }

    @Override
    public void preflight(final Path directory) throws BackgroundException {
        final Acl acl = directory.attributes().getAcl();
        if(!acl.get(new Acl.CanonicalUser()).contains(CANLISTCHILDREN)) {
            if(log.isWarnEnabled()) {
                log.warn(String.format("ACL %s for %s does not include %s", acl, directory, CANLISTCHILDREN));
            }
            throw new AccessDeniedException(MessageFormat.format(LocaleFactory.localizedString("Cannot download {0}", "Error"), directory.getName())).withFile(directory);
        }
    }
}
