package ch.cyberduck.core.googledrive;

/*
 * Copyright (c) 2002-2016 iterate GmbH. All rights reserved.
 * https://cyberduck.io/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

import ch.cyberduck.core.AlphanumericRandomStringService;
import ch.cyberduck.core.AttributedList;
import ch.cyberduck.core.DisabledListProgressListener;
import ch.cyberduck.core.DisabledLoginCallback;
import ch.cyberduck.core.DisabledPasswordCallback;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.features.Delete;
import ch.cyberduck.core.shared.DefaultFindFeature;
import ch.cyberduck.core.transfer.TransferStatus;
import ch.cyberduck.test.IntegrationTest;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.Arrays;
import java.util.EnumSet;

import com.google.api.services.drive.model.File;

import static org.junit.Assert.*;

@Category(IntegrationTest.class)
public class DriveDefaultListServiceTest extends AbstractDriveTest {

    @Test
    public void testList() throws Exception {
        final AttributedList<Path> list = new DriveDefaultListService(session, new DriveFileidProvider(session).withCache(cache)).list(new Path("/", EnumSet.of(Path.Type.directory)), new DisabledListProgressListener());
        assertFalse(list.isEmpty());
        for(Path f : list) {
            assertEquals(new Path("/", EnumSet.of(Path.Type.directory)), f.getParent());
            if(!f.isVolume()) {
                assertNotNull(f.attributes().getVersionId());
            }
        }
    }

    @Test
    public void testListLexicographically() throws Exception {
        final DriveFileidProvider fileid = new DriveFileidProvider(session).withCache(cache);
        final Path directory = new DriveDirectoryFeature(session, fileid).mkdir(new Path(new AlphanumericRandomStringService().random(), EnumSet.of(Path.Type.directory)), null, new TransferStatus());
        final Path f2 = new DriveTouchFeature(session, fileid).touch(new Path(directory, "aa", EnumSet.of(Path.Type.file)), new TransferStatus());
        final Path f1 = new DriveTouchFeature(session, fileid).touch(new Path(directory, "a", EnumSet.of(Path.Type.file)), new TransferStatus());
        final AttributedList<Path> list = new DriveDefaultListService(session, fileid).list(directory, new DisabledListProgressListener());
        assertEquals(2, list.size());
        assertEquals(f1, list.get(0));
        assertEquals(f2, list.get(1));
        new DriveDeleteFeature(session, fileid).delete(Arrays.asList(f1, f2, directory), new DisabledPasswordCallback(), new Delete.DisabledCallback());
    }

    @Test
    public void testFilenameColon() throws Exception {
        final Path file = new Path(DriveHomeFinderService.MYDRIVE_FOLDER, String.format("%s:name", new AlphanumericRandomStringService().random()), EnumSet.of(Path.Type.file));
        final Path folder = new Path(DriveHomeFinderService.MYDRIVE_FOLDER, String.format("%s:name", new AlphanumericRandomStringService().random()), EnumSet.of(Path.Type.directory));
        final DriveFileidProvider fileid = new DriveFileidProvider(session).withCache(cache);
        new DriveTouchFeature(session, fileid).touch(file, new TransferStatus());
        new DriveDirectoryFeature(session, fileid).mkdir(folder, null, new TransferStatus());
        assertTrue(new DefaultFindFeature(session).find(file));
        assertTrue(new DefaultFindFeature(session).find(folder));
        new DriveDeleteFeature(session, fileid).delete(Arrays.asList(file, folder), new DisabledLoginCallback(), new Delete.DisabledCallback());
    }

    @Test
    public void testSameFoldername() throws Exception {
        final String f1 = new AlphanumericRandomStringService().random();
        final String f2 = new AlphanumericRandomStringService().random();
        final Path parent = new Path(DriveHomeFinderService.MYDRIVE_FOLDER, f1, EnumSet.of(Path.Type.directory));
        final Path folder = new Path(parent, f2, EnumSet.of(Path.Type.directory));
        final DriveFileidProvider provider = new DriveFileidProvider(session).withCache(cache);
        new DriveDirectoryFeature(session, provider).mkdir(parent, null, new TransferStatus());
        new DriveDirectoryFeature(session, provider).mkdir(folder, null, new TransferStatus());
        assertTrue(new DefaultFindFeature(session).find(folder));
        assertEquals(1, new DriveDefaultListService(session, provider).list(parent, new DisabledListProgressListener()).size());
        final String fileid = provider.getFileid(folder, new DisabledListProgressListener());
        final File body = new File();
        body.set("trashed", true);
        session.getClient().files().update(fileid, body).execute();
        new DriveDirectoryFeature(session, provider).mkdir(folder, null, new TransferStatus());
        assertEquals(2, new DriveDefaultListService(session, provider).list(parent, new DisabledListProgressListener()).size());
        new DriveDeleteFeature(session, provider).delete(Arrays.asList(parent), new DisabledLoginCallback(), new Delete.DisabledCallback());
    }
}
