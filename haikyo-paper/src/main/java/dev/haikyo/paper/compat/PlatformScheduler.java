/*
 * Haikyo Plugin - Server integration for the Haikyo Minecraft Client
 * Copyright (C) 2026 Haikyo Project Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package dev.haikyo.paper.compat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Dual-mode scheduler supporting both Folia / Paper 1.19.4-26.3 regionized schedulers
 * ({@code Entity#getScheduler()} and {@code Bukkit#getAsyncScheduler()}) and legacy
 * CraftBukkit / Spigot / Paper 1.8-1.19.3 ({@code Bukkit#getScheduler()}).
 *
 * <p><strong>Folia safety guarantee:</strong> When regionized schedulers are present (Folia
 * and Paper 1.19.4+), this class NEVER touches {@code Bukkit.getScheduler()}. All reflection
 * handles target the public API interfaces returned by {@code Entity#getScheduler()} and
 * {@code Bukkit#getAsyncScheduler()}, avoiding illegal access on package-private implementations.</p>
 */
public final class PlatformScheduler {

    public interface CancellableTask {
        void cancel();
    }

    private static final Method BUKKIT_GET_ASYNC_SCHEDULER = findMethod(Bukkit.class, "getAsyncScheduler");
    private static final Method ENTITY_GET_SCHEDULER = findMethod(Entity.class, "getScheduler");

    private static final Method ASYNC_RUN_NOW = BUKKIT_GET_ASYNC_SCHEDULER == null ? null
            : findMethod(BUKKIT_GET_ASYNC_SCHEDULER.getReturnType(), "runNow", Plugin.class, Consumer.class);
    private static final Method ASYNC_RUN_AT_FIXED_RATE = BUKKIT_GET_ASYNC_SCHEDULER == null ? null
            : findMethod(
                    BUKKIT_GET_ASYNC_SCHEDULER.getReturnType(),
                    "runAtFixedRate",
                    Plugin.class,
                    Consumer.class,
                    long.class,
                    long.class,
                    TimeUnit.class
            );
    private static final Method SCHEDULED_TASK_CANCEL = ASYNC_RUN_AT_FIXED_RATE == null ? null
            : findMethod(ASYNC_RUN_AT_FIXED_RATE.getReturnType(), "cancel");

    private static final Method ENTITY_RUN = ENTITY_GET_SCHEDULER == null ? null
            : findMethod(ENTITY_GET_SCHEDULER.getReturnType(), "run", Plugin.class, Consumer.class, Runnable.class);
    private static final Method ENTITY_RUN_DELAYED = ENTITY_GET_SCHEDULER == null ? null
            : findMethod(
                    ENTITY_GET_SCHEDULER.getReturnType(),
                    "runDelayed",
                    Plugin.class,
                    Consumer.class,
                    Runnable.class,
                    long.class
            );

    private static final boolean REGIONIZED_AVAILABLE =
            BUKKIT_GET_ASYNC_SCHEDULER != null
                    && ENTITY_GET_SCHEDULER != null
                    && ASYNC_RUN_NOW != null
                    && ASYNC_RUN_AT_FIXED_RATE != null
                    && SCHEDULED_TASK_CANCEL != null
                    && ENTITY_RUN != null
                    && ENTITY_RUN_DELAYED != null;

    private PlatformScheduler() {
    }

    public static boolean isRegionizedSchedulerAvailable() {
        return REGIONIZED_AVAILABLE;
    }

    public static String backendName() {
        return REGIONIZED_AVAILABLE ? "Folia/Paper-Regionized" : "Legacy-Bukkit";
    }

    /**
     * Runs a task bound to a specific player (on their Folia entity thread when available,
     * or the main server thread on legacy 1.8-1.19.3 servers).
     */
    public static void runForPlayer(Plugin plugin, Player player, Runnable action) {
        if (player == null || action == null) {
            return;
        }
        if (REGIONIZED_AVAILABLE) {
            try {
                Object scheduler = ENTITY_GET_SCHEDULER.invoke(player);
                Consumer<Object> taskConsumer = ignored -> action.run();
                ENTITY_RUN.invoke(scheduler, plugin, taskConsumer, null);
                return;
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to dispatch task on Folia entity scheduler", t);
            }
        }
        Bukkit.getScheduler().runTask(plugin, action);
    }

    /**
     * Runs a delayed task bound to a specific player ({@code delayTicks >= 1}).
     */
    public static void runForPlayerDelayed(Plugin plugin, Player player, Runnable action, long delayTicks) {
        if (player == null || action == null) {
            return;
        }
        long ticks = Math.max(1L, delayTicks);
        if (REGIONIZED_AVAILABLE) {
            try {
                Object scheduler = ENTITY_GET_SCHEDULER.invoke(player);
                Consumer<Object> taskConsumer = ignored -> action.run();
                ENTITY_RUN_DELAYED.invoke(scheduler, plugin, taskConsumer, null, Long.valueOf(ticks));
                return;
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to dispatch delayed task on Folia entity scheduler", t);
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, action, ticks);
    }

    /**
     * Runs a one-shot asynchronous task.
     */
    public static void runAsync(Plugin plugin, Runnable action) {
        if (action == null) {
            return;
        }
        if (REGIONIZED_AVAILABLE) {
            try {
                Object asyncScheduler = BUKKIT_GET_ASYNC_SCHEDULER.invoke(null);
                Consumer<Object> taskConsumer = ignored -> action.run();
                ASYNC_RUN_NOW.invoke(asyncScheduler, plugin, taskConsumer);
                return;
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to dispatch task on Folia async scheduler", t);
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, action);
    }

    /**
     * Runs a repeating asynchronous task at a fixed interval in seconds.
     */
    public static CancellableTask runAsyncAtFixedRate(
            Plugin plugin,
            Runnable action,
            long initialDelaySeconds,
            long periodSeconds
    ) {
        if (REGIONIZED_AVAILABLE) {
            try {
                Object asyncScheduler = BUKKIT_GET_ASYNC_SCHEDULER.invoke(null);
                Consumer<Object> taskConsumer = ignored -> action.run();
                final Object scheduledTask = ASYNC_RUN_AT_FIXED_RATE.invoke(
                        asyncScheduler,
                        plugin,
                        taskConsumer,
                        Long.valueOf(initialDelaySeconds),
                        Long.valueOf(periodSeconds),
                        TimeUnit.SECONDS
                );
                return new CancellableTask() {
                    @Override
                    public void cancel() {
                        if (scheduledTask != null) {
                            try {
                                SCHEDULED_TASK_CANCEL.invoke(scheduledTask);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                };
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to schedule periodic task on Folia async scheduler", t);
            }
        }
        final BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                action,
                Math.max(0L, initialDelaySeconds) * 20L,
                Math.max(1L, periodSeconds) * 20L
        );
        return new CancellableTask() {
            @Override
            public void cancel() {
                bukkitTask.cancel();
            }
        };
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }
}
