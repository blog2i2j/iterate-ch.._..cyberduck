package ch.cyberduck.core.exception;

/*
 * Copyright (c) 2002-2022 iterate GmbH. All rights reserved.
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

/**
 * Skip of file when preparing transfer status
 */
public class TransferStatusCanceledException extends TransferCanceledException {
    public TransferStatusCanceledException() {
    }

    public TransferStatusCanceledException(final String detail) {
        super(detail);
    }

    public TransferStatusCanceledException(final Throwable cause) {
        super(cause);
    }

    public TransferStatusCanceledException(final String detail, final Throwable cause) {
        super(detail, cause);
    }
}
