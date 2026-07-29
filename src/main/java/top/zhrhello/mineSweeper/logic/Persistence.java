package top.zhrhello.mineSweeper.logic;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 持久化键值存储，落地到插件的 data.yml。
 * 采用内存优先策略：YamlConfiguration 本身即内存缓存，读取直接命中内存；
 * 写入时加锁并同步落盘（Logic 解释器运行在主线程，写盘开销可控）。
 * 支持分层键（如 "player.kills" → 嵌套映射）。
 */
public class Persistence {
    private final File file;
    private final YamlConfiguration data = new YamlConfiguration();
    private final ReentrantLock lock = new ReentrantLock();

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

    /** 写入键值（支持列表/映射/数字/字符串）。 */
    public void set(String key, Object value) {
        lock.lock();
        try {
            data.set(key, value);
            try {
                data.save(file);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 删除键。 */
    public void remove(String key) {
        lock.lock();
        try {
            data.set(key, null);
            try {
                data.save(file);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 当前持久化条目数（用于 /sweeper reload 统计）。 */
    public int size() {
        return data.getKeys(true).size();
    }
}
