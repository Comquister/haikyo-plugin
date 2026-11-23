/*
 * Haikyo Plugin - Server integration for the Haikyo Minecraft Client
 * Copyright (C) 2026 Haikyo Project Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package dev.haikyo.api;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Static provider for obtaining the active {@link HaikyoApi} instance on any supported platform.
 */
public final class HaikyoApiProvider {
    private static final AtomicReference<HaikyoApi> INSTANCE = new AtomicReference<>();

    private HaikyoApiProvider() {
    }

    public static Optional<HaikyoApi> get() {
        return Optional.ofNullable(INSTANCE.get());
    }

    public static HaikyoApi require() {
        HaikyoApi api = INSTANCE.get();
        if (api == null) {
            throw new IllegalStateException("HaikyoApi is not registered (is the Haikyo plugin enabled?)");
        }
        return api;
    }

    public static void register(HaikyoApi api) {
        INSTANCE.set(Objects.requireNonNull(api, "api"));
    }

    public static void unregister(HaikyoApi api) {
        INSTANCE.compareAndSet(api, null);
    }
}
