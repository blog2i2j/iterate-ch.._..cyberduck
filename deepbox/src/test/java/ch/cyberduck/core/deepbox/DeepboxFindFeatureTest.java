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

import ch.cyberduck.core.AlphanumericRandomStringService;
import ch.cyberduck.core.DisabledLoginCallback;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.features.Delete;
import ch.cyberduck.core.features.Home;
import ch.cyberduck.core.shared.DefaultHomeFinderService;
import ch.cyberduck.core.transfer.TransferStatus;
import ch.cyberduck.test.IntegrationTest;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.Collections;
import java.util.EnumSet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@Category(IntegrationTest.class)
public class DeepboxFindFeatureTest extends AbstractDeepboxTest {

    @Test
    public void testFindNotFound() throws Exception {
        final Path box = new Path("/ORG 4 - DeepBox Desktop App/ORG 4 - DeepBox Desktop App/ORG3:Box1/Documents", EnumSet.of(Path.Type.directory, Path.Type.volume));
        assertFalse(new DeepboxFindFeature(session, new DeepboxIdProvider(session)).find(new Path(box, new AlphanumericRandomStringService().random(), EnumSet.of(Path.Type.file))));
    }

    @Test
    public void testFindHome() throws Exception {
        assertTrue(new DeepboxFindFeature(session, new DeepboxIdProvider(session)).find(new DefaultHomeFinderService(session).find()));
    }

    @Test
    public void testFindRoot() throws Exception {
        assertTrue(new DeepboxFindFeature(session, new DeepboxIdProvider(session)).find(Home.root()));
    }

    @Test
    public void testFindDirectory() throws Exception {
        final DeepboxIdProvider nodeid = new DeepboxIdProvider(session);
        final Path box = new Path("/ORG 4 - DeepBox Desktop App/ORG 4 - DeepBox Desktop App/ORG3:Box1/Documents", EnumSet.of(Path.Type.directory, Path.Type.volume));
        final Path folder = new DeepboxDirectoryFeature(session, nodeid).mkdir(
                new Path(box, new AlphanumericRandomStringService().random(), EnumSet.of(Path.Type.directory)), new TransferStatus());
        assertTrue(new DeepboxFindFeature(session, nodeid).find(folder));
        assertFalse(new DeepboxFindFeature(session, nodeid).find(new Path(folder.getAbsolute(), EnumSet.of(Path.Type.file))));
        new DeepboxDeleteFeature(session, nodeid).delete(Collections.singletonList(folder), new DisabledLoginCallback(), new Delete.DisabledCallback());
    }

    @Test
    public void testFindFile() throws Exception {
        final Path box = new Path("/ORG 4 - DeepBox Desktop App/ORG 4 - DeepBox Desktop App/ORG3:Box1/Documents", EnumSet.of(Path.Type.directory, Path.Type.volume));
        final Path file = new Path(box, StringUtils.lowerCase(new AlphanumericRandomStringService().random()), EnumSet.of(Path.Type.file));
        final DeepboxIdProvider nodeid = new DeepboxIdProvider(session);
        new DeepboxTouchFeature(session, nodeid).touch(file, new TransferStatus());
        assertTrue(new DeepboxFindFeature(session, nodeid).find(file));
        assertFalse(new DeepboxFindFeature(session, nodeid).find(new Path(box, StringUtils.upperCase(file.getName()), EnumSet.of(Path.Type.file))));
        assertFalse(new DeepboxFindFeature(session, nodeid).find(new Path(file.getAbsolute(), EnumSet.of(Path.Type.directory))));
        new DeepboxDeleteFeature(session, nodeid).delete(Collections.singletonList(file), new DisabledLoginCallback(), new Delete.DisabledCallback());
    }
}