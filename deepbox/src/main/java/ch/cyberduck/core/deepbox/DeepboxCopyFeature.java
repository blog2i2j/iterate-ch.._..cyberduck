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

import ch.cyberduck.core.ConnectionCallback;
import ch.cyberduck.core.LocaleFactory;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.PathAttributes;
import ch.cyberduck.core.deepbox.io.swagger.client.ApiException;
import ch.cyberduck.core.deepbox.io.swagger.client.api.CoreRestControllerApi;
import ch.cyberduck.core.deepbox.io.swagger.client.model.Node;
import ch.cyberduck.core.deepbox.io.swagger.client.model.NodeCopy;
import ch.cyberduck.core.deepbox.io.swagger.client.model.NodeUpdate;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.exception.NotfoundException;
import ch.cyberduck.core.exception.UnsupportedException;
import ch.cyberduck.core.features.Copy;
import ch.cyberduck.core.features.Delete;
import ch.cyberduck.core.io.StreamListener;
import ch.cyberduck.core.transfer.TransferStatus;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.MessageFormat;
import java.util.Collections;
import java.util.UUID;

public class DeepboxCopyFeature implements Copy {
    private static final Logger log = LogManager.getLogger(DeepboxCopyFeature.class);

    private final DeepboxSession session;
    private final DeepboxIdProvider fileid;

    public DeepboxCopyFeature(final DeepboxSession session, final DeepboxIdProvider fileid) {
        this.session = session;
        this.fileid = fileid;
    }

    @Override
    public Path copy(final Path file, final Path target, final TransferStatus status, final ConnectionCallback callback, final StreamListener listener) throws BackgroundException {
        try {
            if(status.isExists()) {
                if(log.isWarnEnabled()) {
                    log.warn(String.format("Delete file %s to be replaced with %s", target, file));
                }
                new DeepboxTrashFeature(session, fileid).delete(Collections.singletonList(target), callback, new Delete.DisabledCallback());
            }
            if(fileid.getFileId(target.withAttributes(new PathAttributes())) != null) {
                new DeepboxTrashFeature(session, fileid).delete(Collections.singletonList(target), callback, new Delete.DisabledCallback());
            }
            final CoreRestControllerApi core = new CoreRestControllerApi(session.getClient());
            final NodeCopy nodeCopy = new NodeCopy();
            nodeCopy.setTargetParentNodeId(UUID.fromString(fileid.getFileId(target.getParent())));
            final String nodeId = fileid.getFileId(file);
            if(nodeId == null) {
                throw new NotfoundException(String.format("Cannot find node id for %s", file.getName()));
            }
            // manually patched deepbox-api.json, return code 200 missing in theirs
            final Node copied = core.copyNode(nodeCopy, UUID.fromString(nodeId));
            final NodeUpdate nodeUpdate = new NodeUpdate();
            nodeUpdate.setName(target.getName());
            core.updateNode(nodeUpdate, copied.getNodeId());
            return target;
        }
        catch(ApiException e) {
            throw new DeepboxExceptionMappingService(fileid).map("Cannot copy {0}", e, file);
        }
    }

    @Override
    public void preflight(final Path source, final Path target) throws BackgroundException {
        if(source.isDirectory()) {
            throw new UnsupportedException(MessageFormat.format(LocaleFactory.localizedString("Cannot copy {0}", "Error"), source.getName())).withFile(source);
        }
    }
}
