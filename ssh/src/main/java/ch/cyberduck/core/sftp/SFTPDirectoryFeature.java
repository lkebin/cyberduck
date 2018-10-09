package ch.cyberduck.core.sftp;

/*
 * Copyright (c) 2002-2013 David Kocher. All rights reserved.
 * http://cyberduck.ch/
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
 *
 * Bug fixes, suggestions and comments should be sent to feedback@cyberduck.ch
 */

import ch.cyberduck.core.DisabledListProgressListener;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.Permission;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.features.Directory;
import ch.cyberduck.core.features.Write;
import ch.cyberduck.core.preferences.Preferences;
import ch.cyberduck.core.preferences.PreferencesFactory;
import ch.cyberduck.core.transfer.TransferStatus;

import java.io.IOException;

import net.schmizz.sshj.sftp.FileAttributes;

public class SFTPDirectoryFeature implements Directory<Void> {

    private final Preferences preferences = PreferencesFactory.get();
    private final SFTPSession session;

    public SFTPDirectoryFeature(final SFTPSession session) {
        this.session = session;
    }

    @Override
    public Path mkdir(final Path folder, final String region, final TransferStatus status) throws BackgroundException {
        try {
            final FileAttributes attrs;
            if(Permission.EMPTY != status.getPermission()) {
                attrs = new FileAttributes.Builder().withPermissions(Integer.parseInt(status.getPermission().getMode(), 8)).build();
            }
            else {
                attrs = FileAttributes.EMPTY;
            }
            session.sftp().makeDir(folder.getAbsolute(), attrs);
        }
        catch(IOException e) {
            throw new SFTPExceptionMappingService().map("Cannot create folder {0}", e, folder);
        }
        return new Path(folder.getParent(), folder.getName(), folder.getType(), new SFTPAttributesFinderFeature(session).find(folder, new DisabledListProgressListener()));
    }

    @Override
    public boolean isSupported(final Path workdir, final String name) {
        return true;
    }

    @Override
    public SFTPDirectoryFeature withWriter(final Write<Void> writer) {
        return this;
    }
}
