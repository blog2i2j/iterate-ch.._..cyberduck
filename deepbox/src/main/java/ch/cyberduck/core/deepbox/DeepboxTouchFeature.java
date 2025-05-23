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

import ch.cyberduck.core.Acl;
import ch.cyberduck.core.LocaleFactory;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.deepbox.io.swagger.client.model.Node;
import ch.cyberduck.core.exception.AccessDeniedException;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.shared.DefaultTouchFeature;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static ch.cyberduck.core.deepbox.DeepboxAttributesFinderFeature.CANADDCHILDREN;

public class DeepboxTouchFeature extends DefaultTouchFeature<Node> {
    private static final Logger log = LogManager.getLogger(DeepboxTouchFeature.class);

    private final DeepboxPathContainerService containerService;

    public DeepboxTouchFeature(final DeepboxSession session, final DeepboxIdProvider fileid) {
        super(new DeepboxWriteFeature(session, fileid));
        this.containerService = new DeepboxPathContainerService(session, fileid);
    }

    @Override
    public void preflight(final Path workdir, final String filename) throws BackgroundException {
        if(workdir.isRoot()) {
            throw new AccessDeniedException(LocaleFactory.localizedString("Adding files is not permitted at the organisation level", "Deepbox")).withFile(workdir);
        }
        if(containerService.isCompany(workdir)) {
            throw new AccessDeniedException(LocaleFactory.localizedString("Adding files is not permitted in this area", "Deepbox")).withFile(workdir);
        }
        if(containerService.isDeepbox(workdir)) {
            throw new AccessDeniedException(LocaleFactory.localizedString("Adding files is not permitted in the boxes area", "Deepbox")).withFile(workdir);
        }
        if(containerService.isTrash(workdir)) {
            throw new AccessDeniedException(LocaleFactory.localizedString("Adding files is not permitted in this area", "Deepbox")).withFile(workdir);
        }
        if(containerService.isSharedWithMe(workdir)) {
            throw new AccessDeniedException(LocaleFactory.localizedString("Adding files is not permitted in the boxes area", "Deepbox")).withFile(workdir);
        }
        if(containerService.isBox(workdir)) {
            throw new AccessDeniedException(LocaleFactory.localizedString("Adding files is not permitted in this area", "Deepbox")).withFile(workdir);
        }
        final Acl acl = workdir.attributes().getAcl();
        if(Acl.EMPTY == acl) {
            // Missing initialization
            log.warn("Unknown ACLs on {}", workdir);
            return;
        }
        if(!acl.get(new Acl.CanonicalUser()).contains(CANADDCHILDREN)) {
            log.warn("ACL {} for {} does not include {}", acl, workdir, CANADDCHILDREN);
            throw new AccessDeniedException(LocaleFactory.localizedString("Adding files is not permitted in this area", "Deepbox")).withFile(workdir);
        }
    }
}
