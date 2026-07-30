package top.zhrhello.mineSweeper.folia;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Paper / Folia 统一调度兼容层。
 *
 * <p>设计目标：
 * <ul>
 *   <li>编译期<b>仅依赖 paper-api</b>，不引用任何 Folia 类（{@code io.papermc.paper.threadedregions.*}），
 *       因此用纯 paper-api 即可编译，可在 Paper 与 Folia 两种服务端运行。</li>
 *   <li>运行时通过 {@link #isFolia()} 检测：Folia 下反射调用其区域/全局/异步/实体调度器；
 *       Paper 下回落到 {@link org.bukkit.scheduler.BukkitScheduler}（主线程）。</li>
 * </ul>
 *
 * <p>线程规则（务必遵守，否则 Folia 上会抛 IllegalStateException）：
 * <ul>
 *   <li>{@link #runOnRegion} / {@link #runOnRegionDelayed}：在指定 Location 所属 region 线程执行，
 *       适合<b>世界方块读写、getNearbyEntities</b>等。</li>
 *   <li>{@link #runOnEntity} / {@link #runOnEntityDelayed}：在实体所属 region 线程执行，
 *       适合<b>玩家 playSound / getInventory / dispatchCommand(player) / getTargetBlockExact</b>等。</li>
 *   <li>{@link #runOnGlobal} 系列：全局区域线程，<b>不可访问任何具体世界方块/实体</b>，
 *       仅用于纯逻辑/计时/转发（如超时检测读状态字段后再转发）。</li>
 *   <li>{@link #runAsyncDelayed}：异步线程，仅用于计时后再转发，<b>不可直接访问世界/实体</b>。</li>
 *   <li>{@link #isOwnedByCurrentRegion(Location)}：判断当前线程是否拥有该位置，
 *       用于遍历平台时"同 region 直接执行、跨 region 转发"的优化与正确性。</li>
 * </ul>
 */
public final class SchedulerCompat {

    private static volatile Boolean folia;
    private static boolean initialized;

    // 反射缓存（仅在 isFolia()==true 时初始化）
    private static Method mServerGetRegionScheduler;
    private static Method mServerGetGlobalRegionScheduler;
    private static Method mServerGetAsyncScheduler;
    private static Method mEntityGetScheduler;

    private static Method mRegionRun;
    private static Method mRegionRunDelayed;
    private static Method mRegionRunAtFixedRate;

    private static Method mGlobalExecute;
    private static Method mGlobalRunDelayed;
    private static Method mGlobalRunAtFixedRate;

    private static Method mAsyncRunDelayed;

    private static Method mEntityRun;
    private static Method mEntityRunDelayed;

    private static Method mWorldIsOwnedLoc;

    private SchedulerCompat() {
    }

    /** 检测当前是否运行在 Folia 服务端（存在 RegionizedServer 类）。 */
    public static boolean isFolia() {
        Boolean f = folia;
        if (f != null) {
            return f;
        }
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
        return folia;
    }

    private static synchronized void initFolia() {
        if (initialized) {
            return;
        }
        try {
            Class<?> regionSchCls = Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            Class<?> globalSchCls = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Class<?> asyncSchCls = Class.forName("io.papermc.paper.threadedregions.scheduler.AsyncScheduler");
            Class<?> entitySchCls = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");

            Class<?> serverCls = Bukkit.getServer().getClass();
            mServerGetRegionScheduler = serverCls.getMethod("getRegionScheduler");
            mServerGetGlobalRegionScheduler = serverCls.getMethod("getGlobalRegionScheduler");
            mServerGetAsyncScheduler = serverCls.getMethod("getAsyncScheduler");
            mEntityGetScheduler = Entity.class.getMethod("getScheduler");

            mRegionRun = regionSchCls.getMethod("run", Plugin.class, Location.class, Consumer.class);
            mRegionRunDelayed = regionSchCls.getMethod("runDelayed", Plugin.class, Location.class, Consumer.class, long.class);
            mRegionRunAtFixedRate = regionSchCls.getMethod("runAtFixedRate", Plugin.class, Location.class, Consumer.class, long.class, long.class);

            mGlobalExecute = globalSchCls.getMethod("execute", Plugin.class, Runnable.class);
            mGlobalRunDelayed = globalSchCls.getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
            mGlobalRunAtFixedRate = globalSchCls.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);

            mAsyncRunDelayed = asyncSchCls.getMethod("runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class);

            mEntityRun = entitySchCls.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
            mEntityRunDelayed = entitySchCls.getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);

            // 兼容 Luminol 等 Folia 分支：isOwnedByCurrentRegion 方法可能不存在
            try {
                mWorldIsOwnedLoc = World.class.getMethod("isOwnedByCurrentRegion", Location.class);
            } catch (NoSuchMethodException e) {
                mWorldIsOwnedLoc = null;
                try {
                    Bukkit.getLogger().warning("[SchedulerCompat] World.isOwnedByCurrentRegion(Location) 方法不存在，" +
                            "当前服务端可能为 Luminol 等 Folia 分支，isOwnedByCurrentRegion 将始终返回 false");
                } catch (Throwable ignored) {
                }
            }

            initialized = true;
        } catch (Throwable t) {
            throw new IllegalStateException("[SchedulerCompat] Folia 调度器反射初始化失败", t);
        }
    }

    private static void ensureFolia() {
        if (!initialized) {
            initFolia();
        }
    }

    /** 把 Runnable 包装成忽略 ScheduledTask 参数的 Consumer。 */
    private static Consumer<Object> wrap(Runnable task) {
        return o -> {
            try {
                task.run();
            } catch (Throwable t) {
                Bukkit.getServer().getLogger().log(Level.WARNING, "[SchedulerCompat] 调度任务执行异常", t);
            }
        };
    }

    // ===================== Region（按 Location） =====================

    /** 在 loc 所属 region 线程执行 task（Folia）；主线程执行（Paper）。loc 为空时回退全局。 */
    public static void runOnRegion(Plugin plugin, Location loc, Runnable task) {
        if (!isFolia()) {
            runOnMain(plugin, task);
            return;
        }
        ensureFolia();
        if (loc == null || loc.getWorld() == null) {
            runOnGlobal(plugin, task);
            return;
        }
        try {
            Object rs = mServerGetRegionScheduler.invoke(Bukkit.getServer());
            mRegionRun.invoke(rs, plugin, loc, wrap(task));
        } catch (Throwable t) {
            fallback(plugin, task, t);
        }
    }

    /**
     * 若当前线程已拥有 loc（Folia）或已为主线程（Paper），则同步执行 task；
     * 否则转发到 loc 所属 region 线程执行。适合遍历一批位置时"同 region 直接执行、跨 region 转发"。
     */
    public static void runOnRegionOwned(Plugin plugin, Location loc, Runnable task) {
        if (!isFolia()) {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
            return;
        }
        ensureFolia();
        if (loc == null || loc.getWorld() == null) {
            runOnGlobal(plugin, task);
            return;
        }
        if (isOwnedByCurrentRegion(loc)) {
            task.run();
        } else {
            runOnRegion(plugin, loc, task);
        }
    }

    /** 在 loc 所属 region 线程延迟 ticks 执行 task。 */
    public static void runOnRegionDelayed(Plugin plugin, Location loc, Runnable task, long ticks) {
        if (!isFolia()) {
            Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
            return;
        }
        ensureFolia();
        if (loc == null || loc.getWorld() == null) {
            runOnGlobalDelayed(plugin, task, ticks);
            return;
        }
        try {
            Object rs = mServerGetRegionScheduler.invoke(Bukkit.getServer());
            mRegionRunDelayed.invoke(rs, plugin, loc, wrap(task), ticks);
        } catch (Throwable t) {
            fallback(plugin, task, t);
        }
    }

    /** 在 loc 所属 region 线程周期执行 task（延迟 delayTicks，周期 periodTicks）。 */
    public static void runOnRegionAtFixedRate(Plugin plugin, Location loc, Runnable task, long delayTicks, long periodTicks) {
        if (!isFolia()) {
            Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
            return;
        }
        ensureFolia();
        if (loc == null || loc.getWorld() == null) {
            runOnGlobalAtFixedRate(plugin, task, delayTicks, periodTicks);
            return;
        }
        try {
            Object rs = mServerGetRegionScheduler.invoke(Bukkit.getServer());
            mRegionRunAtFixedRate.invoke(rs, plugin, loc, wrap(task), delayTicks, periodTicks);
        } catch (Throwable t) {
            fallback(plugin, task, t);
        }
    }

    // ===================== Entity（按实体） =====================

    /** 在 entity 所属 region 线程执行 task（Folia）；主线程执行（Paper）。entity 为空时回退全局。 */
    public static void runOnEntity(Plugin plugin, Entity entity, Runnable task) {
        if (!isFolia()) {
            runOnMain(plugin, task);
            return;
        }
        ensureFolia();
        if (entity == null) {
            runOnGlobal(plugin, task);
            return;
        }
        try {
            Object es = mEntityGetScheduler.invoke(entity);
            // retired 回调传 null：实体失效时丢弃任务（避免在错误线程执行世界访问）
            mEntityRun.invoke(es, plugin, wrap(task), null);
        } catch (Throwable t) {
            fallback(plugin, task, t);
        }
    }

    /** 在 entity 所属 region 线程延迟 ticks 执行 task。 */
    public static void runOnEntityDelayed(Plugin plugin, Entity entity, Runnable task, long ticks) {
        if (!isFolia()) {
            Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
            return;
        }
        ensureFolia();
        if (entity == null) {
            runOnGlobalDelayed(plugin, task, ticks);
            return;
        }
        try {
            Object es = mEntityGetScheduler.invoke(entity);
            mEntityRunDelayed.invoke(es, plugin, wrap(task), null, ticks);
        } catch (Throwable t) {
            fallback(plugin, task, t);
        }
    }

    // ===================== Global（全局区域，禁止访问世界/实体） =====================

    /** 在全局区域线程执行 task（Folia，不可访问具体世界/实体）；主线程执行（Paper）。 */
    public static void runOnGlobal(Plugin plugin, Runnable task) {
        if (!isFolia()) {
            runOnMain(plugin, task);
            return;
        }
        ensureFolia();
        try {
            Object gs = mServerGetGlobalRegionScheduler.invoke(Bukkit.getServer());
            mGlobalExecute.invoke(gs, plugin, task);
        } catch (Throwable t) {
            fallback(plugin, task, t);
        }
    }

    /** 在全局区域线程延迟 ticks 执行 task。 */
    public static void runOnGlobalDelayed(Plugin plugin, Runnable task, long ticks) {
        if (!isFolia()) {
            Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
            return;
        }
        ensureFolia();
        try {
            Object gs = mServerGetGlobalRegionScheduler.invoke(Bukkit.getServer());
            mGlobalRunDelayed.invoke(gs, plugin, wrap(task), ticks);
        } catch (Throwable t) {
            fallback(plugin, task, t);
        }
    }

    /** 在全局区域线程周期执行 task（用于不访问世界的纯计时/转发，如超时检测）。 */
    public static void runOnGlobalAtFixedRate(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (!isFolia()) {
            Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
            return;
        }
        ensureFolia();
        try {
            Object gs = mServerGetGlobalRegionScheduler.invoke(Bukkit.getServer());
            mGlobalRunAtFixedRate.invoke(gs, plugin, wrap(task), delayTicks, periodTicks);
        } catch (Throwable t) {
            fallback(plugin, task, t);
        }
    }

    // ===================== Async（异步计时/转发，禁止访问世界/实体） =====================

    /** 异步延迟 ticks 后执行 task（Folia）；主线程延迟执行（Paper）。仅用于计时后再转发，不可直接访问世界/实体。 */
    public static void runAsyncDelayed(Plugin plugin, Runnable task, long ticks) {
        if (!isFolia()) {
            Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
            return;
        }
        ensureFolia();
        try {
            Object as = mServerGetAsyncScheduler.invoke(Bukkit.getServer());
            long ms = Math.max(0L, ticks) * 50L; // 1 tick = 50ms
            mAsyncRunDelayed.invoke(as, plugin, wrap(task), ms, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            fallback(plugin, task, t);
        }
    }

    // ===================== 归属判断 =====================

    /** 
     * 当前线程是否拥有 loc（Folia）；是否为主线程（Paper）。
     * <p>注意：在 Luminol 等部分 Folia 分支上，由于缺少 isOwnedByCurrentRegion 方法，
     * 此方法将始终返回 false，此时建议使用 {@link #runOnRegion} 代替 {@link #runOnRegionOwned}。
     */
    public static boolean isOwnedByCurrentRegion(Location loc) {
        if (!isFolia()) {
            return Bukkit.isPrimaryThread();
        }
        ensureFolia();
        // 兼容 Luminol 等缺失 isOwnedByCurrentRegion 方法的 Folia 分支
        if (mWorldIsOwnedLoc == null) {
            return false;
        }
        try {
            if (loc == null || loc.getWorld() == null) {
                return false;
            }
            return (boolean) mWorldIsOwnedLoc.invoke(loc.getWorld(), loc);
        } catch (Throwable t) {
            return false;
        }
    }

    // ===================== 内部工具 =====================

    private static void runOnMain(Plugin plugin, Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** 反射调度失败时的兜底：尽量在主线程执行，避免任务丢失。 */
    private static void fallback(Plugin plugin, Runnable task, Throwable t) {
        try {
            plugin.getLogger().warning("[SchedulerCompat] Folia 调度失败，回退主线程: " + t);
        } catch (Throwable ignored) {
        }
        try {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        } catch (Throwable ignored) {
        }
    }
}