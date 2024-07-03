package ch.cyberduck.core.deepbox;/*
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
import ch.cyberduck.core.AttributedList;
import ch.cyberduck.core.DisabledListProgressListener;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.PathAttributes;
import ch.cyberduck.core.SimplePathPredicate;
import ch.cyberduck.core.deepbox.io.swagger.client.ApiException;
import ch.cyberduck.core.deepbox.io.swagger.client.api.BoxRestControllerApi;
import ch.cyberduck.core.deepbox.io.swagger.client.api.CoreRestControllerApi;
import ch.cyberduck.core.exception.AccessDeniedException;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.features.FileIdProvider;
import ch.cyberduck.test.IntegrationTest;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.EnumSet;
import java.util.UUID;

import static ch.cyberduck.core.deepbox.DeepboxAttributesFinderFeature.CANLISTCHILDREN;
import static org.junit.Assert.*;

@Category(IntegrationTest.class)
public class DeepboxCanListChildrenTest extends AbstractDeepboxTest {

    @Test
    // Subfolders of documents may be visible, despite 403 on listing files from that node/getting NodeInfo for that node!
    public void testNoListChildrenTrashInbox() throws Exception {
        final DeepboxIdProvider nodeid = (DeepboxIdProvider) session.getFeature(FileIdProvider.class);
        final Path box = new Path("/ORG 1 - DeepBox Desktop App/Box2", EnumSet.of(Path.Type.directory, Path.Type.volume));
        final AttributedList<Path> list = new DeepboxListService(session, nodeid).list(box, new DisabledListProgressListener());
        final UUID deepBoxNodeId = UUID.fromString(nodeid.getDeepBoxNodeId(box));
        final UUID boxNodeId = UUID.fromString(nodeid.getBoxNodeId(box));
        final Path documents = new Path("/ORG 1 - DeepBox Desktop App/Box2/Documents", EnumSet.of(Path.Type.directory, Path.Type.volume));
        final UUID documentsNodeId = UUID.fromString(nodeid.getFileId(documents));

        final ApiException apiExceptionGetNodeInfo = assertThrows(ApiException.class, () -> new CoreRestControllerApi(session.getClient()).getNodeInfo(documentsNodeId, null, null, null));
        assertEquals(403, apiExceptionGetNodeInfo.getCode());

        final ApiException apiExceptionListFilestWithDocumentsNodeId = assertThrows(ApiException.class, () -> new BoxRestControllerApi(session.getClient()).listFiles1(deepBoxNodeId, boxNodeId, documentsNodeId, null, null, null));
        assertEquals(403, apiExceptionListFilestWithDocumentsNodeId.getCode());

        assertFalse(new BoxRestControllerApi(session.getClient()).getBox(deepBoxNodeId, boxNodeId).getBoxPolicy().isCanAccessTrash());
        assertFalse(new BoxRestControllerApi(session.getClient()).getBox(deepBoxNodeId, boxNodeId).getBoxPolicy().isCanListQueue());
        assertNotNull(list.find(new SimplePathPredicate(documents)));
        assertNull(list.find(new SimplePathPredicate(new Path("/ORG 1 - DeepBox Desktop App/Box2/Inbox", EnumSet.of(Path.Type.directory, Path.Type.volume)))));
        assertNull(list.find(new SimplePathPredicate(new Path("/ORG 1 - DeepBox Desktop App/Box2/Trash", EnumSet.of(Path.Type.directory, Path.Type.volume)))));
    }

    @Test
    public void testListChildrenInbox() throws BackgroundException, ApiException {
        final DeepboxIdProvider nodeid = (DeepboxIdProvider) session.getFeature(FileIdProvider.class);
        final Path folder = new Path("/ORG 4 - DeepBox Desktop App/Box1/Inbox/", EnumSet.of(Path.Type.directory, Path.Type.volume));
        final PathAttributes attributes = new DeepboxAttributesFinderFeature(session, nodeid).find(folder);
        assertTrue(new BoxRestControllerApi(session.getClient()).getBox(ORG4, ORG4_BOX1).getBoxPolicy().isCanAddQueue());
        assertTrue(attributes.getAcl().get(new Acl.CanonicalUser()).contains(CANLISTCHILDREN));
        // assert no fail
        new DeepboxListService(session, nodeid).preflight(folder.withAttributes(attributes));
    }

    @Test
    public void testListChildrenDocuments() throws BackgroundException, ApiException {
        final DeepboxIdProvider nodeid = (DeepboxIdProvider) session.getFeature(FileIdProvider.class);
        final Path folder = new Path("/ORG 4 - DeepBox Desktop App/Box1/Documents/", EnumSet.of(Path.Type.directory, Path.Type.volume));
        final PathAttributes attributes = new DeepboxAttributesFinderFeature(session, nodeid).find(folder);
        assertTrue(new BoxRestControllerApi(session.getClient()).getBox(ORG4, ORG4_BOX1).getBoxPolicy().isCanAddFilesRoot());
        assertTrue(attributes.getAcl().get(new Acl.CanonicalUser()).contains(CANLISTCHILDREN));
        // assert no fail
        new DeepboxListService(session, nodeid).preflight(folder.withAttributes(attributes));
    }

    @Test
    public void testListChildrenTrash() throws BackgroundException, ApiException {
        final DeepboxIdProvider nodeid = (DeepboxIdProvider) session.getFeature(FileIdProvider.class);
        final Path folder = new Path("/ORG 4 - DeepBox Desktop App/Box1/Trash/", EnumSet.of(Path.Type.directory, Path.Type.volume));
        final PathAttributes attributes = new DeepboxAttributesFinderFeature(session, nodeid).find(folder);
        assertTrue(new BoxRestControllerApi(session.getClient()).getBox(ORG4, ORG4_BOX1).getBoxPolicy().isCanAddFilesRoot());
        assertTrue(attributes.getAcl().get(new Acl.CanonicalUser()).contains(CANLISTCHILDREN));
        // assert no fail
        new DeepboxListService(session, nodeid).preflight(folder.withAttributes(attributes));
    }

    @Test
    // N.B. all folders always seem to have canListChildren
    public void testListChildrenFolder() throws BackgroundException, ApiException {
        final DeepboxIdProvider nodeid = (DeepboxIdProvider) session.getFeature(FileIdProvider.class);
        final Path folder = new Path("/ORG 4 - DeepBox Desktop App/Box1/Documents/Auditing", EnumSet.of(Path.Type.directory, Path.Type.volume));
        final PathAttributes attributes = new DeepboxAttributesFinderFeature(session, nodeid).find(folder);
        assertTrue(new CoreRestControllerApi(session.getClient()).getNodeInfo(UUID.fromString(attributes.getFileId()), null, null, null).getNode().getPolicy().isCanListChildren());
        assertTrue(attributes.getAcl().get(new Acl.CanonicalUser()).contains(CANLISTCHILDREN));
        // assert no fail
        new DeepboxListService(session, nodeid).preflight(folder.withAttributes(attributes));
    }


    @Test
    public void testNoListChildrenFile() throws BackgroundException, ApiException {
        final DeepboxIdProvider nodeid = (DeepboxIdProvider) session.getFeature(FileIdProvider.class);
        final Path folder = new Path("/ORG 4 - DeepBox Desktop App/Box1/Documents/RE-IN - Copy1.pdf", EnumSet.of(Path.Type.directory, Path.Type.volume));
        final PathAttributes attributes = new DeepboxAttributesFinderFeature(session, nodeid).find(folder);
        assertFalse(new CoreRestControllerApi(session.getClient()).getNodeInfo(UUID.fromString(attributes.getFileId()), null, null, null).getNode().getPolicy().isCanListChildren());
        assertFalse(attributes.getAcl().get(new Acl.CanonicalUser()).contains(CANLISTCHILDREN));
        assertThrows(AccessDeniedException.class, () -> new DeepboxListService(session, nodeid).preflight(folder.withAttributes(attributes)));
    }
}