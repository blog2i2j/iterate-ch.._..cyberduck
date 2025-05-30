package ch.cyberduck.core.transfer.upload;

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

import ch.cyberduck.core.Acl;
import ch.cyberduck.core.AlphanumericRandomStringService;
import ch.cyberduck.core.DisabledConnectionCallback;
import ch.cyberduck.core.Filter;
import ch.cyberduck.core.Local;
import ch.cyberduck.core.LocaleFactory;
import ch.cyberduck.core.MappingMimeTypeService;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.PathAttributes;
import ch.cyberduck.core.Permission;
import ch.cyberduck.core.ProgressListener;
import ch.cyberduck.core.Session;
import ch.cyberduck.core.UserDateFormatterFactory;
import ch.cyberduck.core.exception.AccessDeniedException;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.exception.InteroperabilityException;
import ch.cyberduck.core.exception.LocalAccessDeniedException;
import ch.cyberduck.core.exception.LocalNotfoundException;
import ch.cyberduck.core.exception.NotfoundException;
import ch.cyberduck.core.features.AclPermission;
import ch.cyberduck.core.features.AttributesFinder;
import ch.cyberduck.core.features.Delete;
import ch.cyberduck.core.features.Encryption;
import ch.cyberduck.core.features.Find;
import ch.cyberduck.core.features.Headers;
import ch.cyberduck.core.features.Move;
import ch.cyberduck.core.features.Redundancy;
import ch.cyberduck.core.features.Timestamp;
import ch.cyberduck.core.features.UnixPermission;
import ch.cyberduck.core.features.Versioning;
import ch.cyberduck.core.features.Write;
import ch.cyberduck.core.io.ChecksumCompute;
import ch.cyberduck.core.preferences.HostPreferencesFactory;
import ch.cyberduck.core.preferences.PreferencesReader;
import ch.cyberduck.core.transfer.TransferPathFilter;
import ch.cyberduck.core.transfer.TransferStatus;
import ch.cyberduck.core.transfer.symlink.SymlinkResolver;
import ch.cyberduck.ui.browser.SearchFilterFactory;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.text.MessageFormat;
import java.util.EnumSet;
import java.util.Optional;

public abstract class AbstractUploadFilter implements TransferPathFilter {
    private static final Logger log = LogManager.getLogger(AbstractUploadFilter.class);

    private final PreferencesReader preferences;
    private final Session<?> session;
    private final SymlinkResolver<Local> symlinkResolver;
    private final Filter<Path> hidden = SearchFilterFactory.HIDDEN_FILTER;

    protected final Find find;
    protected final AttributesFinder attribute;
    protected final UploadFilterOptions options;

    public AbstractUploadFilter(final SymlinkResolver<Local> symlinkResolver, final Session<?> session, final UploadFilterOptions options) {
        this(symlinkResolver, session, session.getFeature(Find.class), session.getFeature(AttributesFinder.class), options);
    }

    public AbstractUploadFilter(final SymlinkResolver<Local> symlinkResolver, final Session<?> session, final Find find, final AttributesFinder attribute, final UploadFilterOptions options) {
        this.session = session;
        this.symlinkResolver = symlinkResolver;
        this.find = find;
        this.attribute = attribute;
        this.options = options;
        this.preferences = HostPreferencesFactory.get(session.getHost());
    }

    @Override
    public boolean accept(final Path file, final Local local, final TransferStatus parent, final ProgressListener progress) throws BackgroundException {
        if(!local.exists()) {
            // Local file is no more here
            throw new LocalNotfoundException(local.getAbsolute());
        }
        return true;
    }

    @Override
    public TransferStatus prepare(final Path file, final Local local, final TransferStatus parent, final ProgressListener progress) throws BackgroundException {
        log.debug("Prepare {}", file);
        final TransferStatus status = new TransferStatus()
                .setHidden(!hidden.accept(file))
                .setLockId(parent.getLockId());
        // Read remote attributes first
        if(parent.isExists()) {
            if(find.find(file)) {
                status.setExists(true);
                // Read remote attributes
                final PathAttributes attributes = attribute.find(file);
                status.setRemote(attributes);
            }
            else {
                // Look if there is directory or file that clashes with this upload
                if(file.getType().contains(Path.Type.file)) {
                    if(find.find(new Path(file.getParent(), file.getName(), EnumSet.of(Path.Type.directory)))) {
                        throw new AccessDeniedException(String.format("Cannot replace folder %s with file %s", file.getAbsolute(), file.getName()));
                    }
                }
                if(file.getType().contains(Path.Type.directory)) {
                    if(find.find(new Path(file.getParent(), file.getName(), EnumSet.of(Path.Type.file)))) {
                        throw new AccessDeniedException(String.format("Cannot replace file %s with folder %s", file.getAbsolute(), file.getName()));
                    }
                }
            }
        }
        if(file.isFile()) {
            // Set content length from local file
            if(local.isSymbolicLink()) {
                if(!symlinkResolver.resolve(local)) {
                    // Will resolve the symbolic link when the file is requested.
                    final Local target = local.getSymlinkTarget();
                    status.setLength(target.attributes().getSize());
                }
                // No file size increase for symbolic link to be created on the server
            }
            else {
                // Read file size from filesystem
                status.setLength(local.attributes().getSize());
            }
            if(options.temporary) {
                final Move feature = session.getFeature(Move.class);
                final Path renamed = new Path(file.getParent(),
                        MessageFormat.format(preferences.getProperty("queue.upload.file.temporary.format"),
                                file.getName(), new AlphanumericRandomStringService().random()), file.getType());
                if(feature.isSupported(file, Optional.of(renamed))) {
                    log.debug("Set temporary filename {}", renamed);
                    // Set target name after transfer
                    status.setRename(renamed).setDisplayname(file);
                    // Remember status of target file for later rename
                    status.getDisplayname().exists(status.isExists());
                    // Keep exist flag for subclasses to determine additional rename strategy
                }
                else {
                    log.warn("Cannot use temporary filename for upload with missing rename support for {}", file);
                }
            }
            status.setMime(new MappingMimeTypeService().getMime(file.getName()));
        }
        if(file.isDirectory()) {
            status.setLength(0L);
        }
        if(options.permissions) {
            final UnixPermission feature = session.getFeature(UnixPermission.class);
            if(feature != null) {
                if(status.isExists()) {
                    // Already set when reading attributes of file
                    status.setPermission(status.getRemote().getPermission());
                }
                else {
                    if(HostPreferencesFactory.get(session.getHost()).getBoolean("queue.upload.permissions.default")) {
                        status.setPermission(feature.getDefault(file.getType()));
                    }
                    else {
                        // Read permissions from local file
                        status.setPermission(local.attributes().getPermission());
                    }
                }
            }
            else {
                // Setting target UNIX permissions in transfer status
                status.setPermission(Permission.EMPTY);
            }
        }
        if(options.acl) {
            final AclPermission feature = session.getFeature(AclPermission.class);
            if(feature != null) {
                if(status.isExists()) {
                    progress.message(MessageFormat.format(LocaleFactory.localizedString("Getting permission of {0}", "Status"),
                            file.getName()));
                    try {
                        status.setAcl(feature.getPermission(file));
                    }
                    catch(NotfoundException | AccessDeniedException | InteroperabilityException e) {
                        status.setAcl(feature.getDefault(file));
                    }
                }
                else {
                    status.setAcl(feature.getDefault(file));
                }
            }
            else {
                // Setting target ACL in transfer status
                status.setAcl(Acl.EMPTY);
            }
        }
        if(options.timestamp) {
            if(1L != local.attributes().getModificationDate()) {
                status.setModified(local.attributes().getModificationDate());
            }
            if(1L != local.attributes().getCreationDate()) {
                status.setCreated(local.attributes().getCreationDate());
            }
        }
        if(options.metadata) {
            final Headers feature = session.getFeature(Headers.class);
            if(feature != null) {
                if(status.isExists()) {
                    progress.message(MessageFormat.format(LocaleFactory.localizedString("Reading metadata of {0}", "Status"),
                            file.getName()));
                    try {
                        status.setMetadata(feature.getMetadata(file));
                    }
                    catch(NotfoundException | AccessDeniedException | InteroperabilityException e) {
                        status.setMetadata(feature.getDefault());
                    }
                }
                else {
                    status.setMetadata(feature.getDefault());
                }
            }
        }
        if(options.encryption) {
            final Encryption feature = session.getFeature(Encryption.class);
            if(feature != null) {
                if(status.isExists()) {
                    progress.message(MessageFormat.format(LocaleFactory.localizedString("Reading metadata of {0}", "Status"),
                            file.getName()));
                    try {
                        status.setEncryption(feature.getEncryption(file));
                    }
                    catch(NotfoundException | AccessDeniedException | InteroperabilityException e) {
                        status.setEncryption(feature.getDefault(file));
                    }
                }
                else {
                    status.setEncryption(feature.getDefault(file));
                }
            }
        }
        if(options.redundancy) {
            if(file.isFile()) {
                final Redundancy feature = session.getFeature(Redundancy.class);
                if(feature != null) {
                    if(status.isExists()) {
                        progress.message(MessageFormat.format(LocaleFactory.localizedString("Reading metadata of {0}", "Status"),
                                file.getName()));
                        try {
                            status.setStorageClass(feature.getClass(file));
                        }
                        catch(NotfoundException | AccessDeniedException | InteroperabilityException e) {
                            status.setStorageClass(feature.getDefault());
                        }
                    }
                    else {
                        status.setStorageClass(feature.getDefault());
                    }
                }
            }
        }
        if(options.checksum) {
            if(file.isFile()) {
                final ChecksumCompute feature = session.getFeature(Write.class).checksum(file, status);
                if(feature != null) {
                    progress.message(MessageFormat.format(LocaleFactory.localizedString("Calculate checksum for {0}", "Status"),
                            file.getName()));
                    try {
                        status.setChecksum(feature.compute(local.getInputStream(), status));
                    }
                    catch(LocalAccessDeniedException e) {
                        // Ignore failure reading file when in sandbox when we miss a security scoped access bookmark.
                        // Lock for files is obtained only later in Transfer#pre
                        log.warn(e.getMessage());
                    }
                }
            }
        }
        return status;
    }

    @Override
    public void apply(final Path file, final Local local, final TransferStatus status,
                      final ProgressListener listener) throws BackgroundException {
        if(file.isFile()) {
            if(status.isExists()) {
                if(status.isAppend()) {
                    // Append to existing file
                    log.debug("Resume upload for existing file {}", file);
                }
                else {
                    if(options.versioning) {
                        switch(session.getHost().getProtocol().getVersioningMode()) {
                            case custom:
                                final Versioning feature = session.getFeature(Versioning.class);
                                if(feature != null) {
                                    log.debug("Use custom versioning {}", feature);
                                    if(feature.getConfiguration(file).isEnabled()) {
                                        log.debug("Enabled versioning for {}", file);
                                        if(feature.save(file)) {
                                            log.debug("Clear exist flag for file {}", file);
                                            status.setExists(false).getDisplayname().exists(false);
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }
        if(status.getRename().remote != null) {
            log.debug("Clear exist flag for file {}", local);
            // Reset exist flag after subclass hae applied strategy
            status.setExists(false);
        }
    }

    @Override
    public void complete(final Path file, final Local local,
                         final TransferStatus status, final ProgressListener listener) throws BackgroundException {
        log.debug("Complete {} with status {}", file.getAbsolute(), status);
        if(status.isComplete()) {
            if(!Permission.EMPTY.equals(status.getPermission())) {
                final UnixPermission feature = session.getFeature(UnixPermission.class);
                if(feature != null) {
                    try {
                        listener.message(MessageFormat.format(LocaleFactory.localizedString("Changing permission of {0} to {1}", "Status"),
                                file.getName(), status.getPermission()));
                        feature.setUnixPermission(file, status);
                    }
                    catch(BackgroundException e) {
                        // Ignore
                        log.warn(e.getMessage());
                    }
                }
            }
            if(!Acl.EMPTY.equals(status.getAcl())) {
                final AclPermission feature = session.getFeature(AclPermission.class);
                if(feature != null) {
                    try {
                        listener.message(MessageFormat.format(LocaleFactory.localizedString("Changing permission of {0} to {1}", "Status"),
                                file.getName(), StringUtils.isBlank(status.getAcl().getCannedString()) ? LocaleFactory.localizedString("Unknown") : status.getAcl().getCannedString()));
                        feature.setPermission(file, status);
                    }
                    catch(BackgroundException e) {
                        // Ignore
                        log.warn(e.getMessage());
                    }
                }
            }
            if(status.getModified() != null) {
                if(!session.getFeature(Write.class).timestamp(file)) {
                    final Timestamp feature = session.getFeature(Timestamp.class);
                    if(feature != null) {
                        try {
                            listener.message(MessageFormat.format(LocaleFactory.localizedString("Changing timestamp of {0} to {1}", "Status"),
                                    file.getName(), UserDateFormatterFactory.get().getShortFormat(status.getModified())));
                            feature.setTimestamp(file, status);
                        }
                        catch(BackgroundException e) {
                            // Ignore
                            log.warn(e.getMessage());
                        }
                    }
                }
            }
            if(file.isFile()) {
                if(status.getDisplayname().remote != null) {
                    final Move move = session.getFeature(Move.class);
                    log.info("Rename file {} to {}", file, status.getDisplayname().remote);
                    move.move(file, status.getDisplayname().remote, new TransferStatus(status).setExists(status.getDisplayname().exists),
                            new Delete.DisabledCallback(), new DisabledConnectionCallback());
                }
            }
        }
    }
}
