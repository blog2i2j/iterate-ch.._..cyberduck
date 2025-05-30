package ch.cyberduck.core.vault.registry;

/*
 * Copyright (c) 2002-2017 iterate GmbH. All rights reserved.
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

import ch.cyberduck.core.ConnectionCallback;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.Session;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.features.Copy;
import ch.cyberduck.core.features.Vault;
import ch.cyberduck.core.io.StreamListener;
import ch.cyberduck.core.transfer.TransferStatus;
import ch.cyberduck.core.vault.VaultRegistry;
import ch.cyberduck.core.vault.VaultUnlockCancelException;

import java.util.EnumSet;
import java.util.Optional;

public class VaultRegistryCopyFeature implements Copy {

    private final Session<?> session;
    private Session<?> destination;
    private final Copy proxy;
    private final VaultRegistry registry;


    public VaultRegistryCopyFeature(final Session<?> session, final Copy proxy, final VaultRegistry registry) {
        this.session = session;
        this.destination = session;
        this.proxy = proxy;
        this.registry = registry;
    }

    @Override
    public Path copy(final Path source, final Path copy, final TransferStatus status, final ConnectionCallback callback, final StreamListener listener) throws BackgroundException {
        if(registry.find(session, source).equals(Vault.DISABLED)) {
            return registry.find(session, copy).getFeature(session, Copy.class, proxy).withTarget(destination).copy(source, copy, status, callback, listener);
        }
        else if(registry.find(session, copy).equals(Vault.DISABLED)) {
            return registry.find(session, source).getFeature(session, Copy.class, proxy).withTarget(destination).copy(source, copy, status, callback, listener);
        }
        else {
            // Move files inside vault. May use server side copy.
            return registry.find(session, copy).getFeature(session, Copy.class, proxy).withTarget(destination).copy(source, copy, status, callback, listener);
        }
    }

    @Override
    public EnumSet<Flags> features(final Path source, final Path copy) {
        try {
            if(registry.find(session, source, false).equals(Vault.DISABLED)) {
                return registry.find(session, copy, false).getFeature(session, Copy.class, proxy).withTarget(destination).features(source, copy);
            }
            else if(registry.find(session, copy, false).equals(Vault.DISABLED)) {
                return registry.find(session, source, false).getFeature(session, Copy.class, proxy).withTarget(destination).features(source, copy);
            }
            return registry.find(session, copy, false).getFeature(session, Copy.class, proxy).withTarget(destination).features(source, copy);
        }
        catch(VaultUnlockCancelException e) {
            return proxy.features(source, copy);
        }
    }

    @Override
    public void preflight(final Path source, final Optional<Path> optional) throws BackgroundException {
        if(optional.isPresent()) {
            final Path target = optional.get();
            try {
                if(registry.find(session, source, false).equals(Vault.DISABLED)) {
                    registry.find(session, target, false).getFeature(session, Copy.class, proxy).withTarget(destination).preflight(source, optional);
                }
                else if(registry.find(session, target, false).equals(Vault.DISABLED)) {
                    registry.find(session, source, false).getFeature(session, Copy.class, proxy).withTarget(destination).preflight(source, optional);
                }
                else {
                    registry.find(session, target, false).getFeature(session, Copy.class, proxy).withTarget(destination).preflight(source, optional);
                }
            }
            catch(VaultUnlockCancelException e) {
                proxy.preflight(source, optional);
            }
        }
        else {
            proxy.preflight(source, optional);
        }
    }

    @Override
    public Copy withTarget(final Session<?> session) {
        this.destination = session.withRegistry(registry);
        return this;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("VaultRegistryCopyFeature{");
        sb.append("proxy=").append(proxy);
        sb.append('}');
        return sb.toString();
    }
}
