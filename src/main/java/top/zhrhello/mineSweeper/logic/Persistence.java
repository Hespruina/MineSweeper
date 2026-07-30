package top.zhrhello.mineSweeper.logic;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 持久化键值存储，落地到插件的 data.yml。
 * 采用内存优先策略：YamlConfiguration 本身即内存缓存，读取直接命中内存；
 * 写入时先在内存中同步更新（保证后续读取立即可见），再把"落盘"交给后台单线程
 * 串行执行（coalesce：同一时刻最多一个待执行的落盘任务，它执行时会保存提交时刻的
 * 最新内存快照）。这样游戏/region 线程不会被磁盘 IO 阻塞。
 * 支持分层键（如 "player.kills" → 嵌套映射）。
 */
public class Persistence {
    private final File file;
    private final YamlConfiguration data = new YamlConfiguration();
    private final ReentrantLock lock = new ReentrantLock();

    /** 后台落盘线程（单线程，保证文件写入串行，避免并发 save 互相覆盖/损坏）。 */
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MineSweeper-Persistence");
        t.setDaemon(true);
        return t;
    });

    /** 是否已有落盘任务在队列中（用于 coalesce，避免每次写都排队一次全量落盘）。 */
    private final AtomicBoolean saveQueued = new AtomicBoolean(false);

    public Persistence(File dataFolder) {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.file = new File(dataFolder, "data.yml");
        load();
    }

    private void load() {
        if (file.exists()) {
            try {
                data.load(file);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /** 读取键值；不存在返回空字符串。 */
    public Object get(String key) {
        Object v = data.get(key);
        return v == null ? "" : v;
    }

    /** 写入键值（支持列表/映射/数字/字符串）。内存同步更新，落盘异步执行。 */
    public void set(String key, Object value) {
        lock.lock();
        try {
            data.set(key, value);
        } finally {
            lock.unlock();
        }
        scheduleSave();
    }

    /** 删除键。内存同步更新，落盘异步执行。 */
    public void remove(String key) {
        lock.lock();
        try {
            data.set(key, null);
        } finally {
            lock.unlock();
        }
        scheduleSave();
    }

    /**
     * 提交一次落盘（coalesce）：若已有待执行任务则跳过——该任务执行时会读取最新内存快照，
     * 因此不会丢失任何已写入内存的值；若没有则入队一个后台保存任务。
     */
    private void scheduleSave() {
        if (saveQueued.compareAndSet(false, true)) {
            try {
                saveExecutor.submit(() -> {
                    try {
                        lock.lock();
                        try {
                            data.save(file);
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            lock.unlock();
                        }
                    } finally {
                        saveQueued.set(false);
                    }
                });
            } catch (RejectedExecutionException e) {
                // 已关闭（插件停用中）：不再入队，改为同步落盘，避免丢失最后写入或抛异常
                lock.lock();
                try {
                    data.save(file);
                } catch (Exception ex) {
                    ex.printStackTrace();
                } finally {
                    lock.unlock();
                }
                saveQueued.set(false);
            }
        }
    }

    /**
     * 等待所有待落盘任务完成并关闭后台线程。应在插件 onDisable 时调用，
     * 确保关停前最后一次写入不丢失。
     */
    public void flush() {
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                saveExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            saveExecutor.shutdownNow();
        }
    }

    /** 当前持久化条目数（用于 /sweeper reload 统计）。 */
    public int size() {
        lock.lock();
        try {
            return data.getKeys(true).size();
        } finally {
            lock.unlock();
        }
    }
}
