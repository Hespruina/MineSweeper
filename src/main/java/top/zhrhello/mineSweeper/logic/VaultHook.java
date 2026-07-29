package top.zhrhello.mineSweeper.logic;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

/**
 * 通过反射访问 Vault 经济接口，避免将 Vault 作为编译期依赖。
 * 若服务器未安装 Vault 或未注册经济提供者，则所有经济操作静默失败（返回 0 / 不执行）。
 */
public class VaultHook {
    private final JavaPlugin plugin;
    private Object economy;

    public VaultHook(JavaPlugin plugin) {
        this.plugin = plugin;
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(economyClass);
            if (rsp != null) {
                economy = rsp.getProvider();
            }
        } catch (Throwable t) {
            economy = null;
        }
    }

    public boolean available() {
        return economy != null;
    }

    public double getBalance(Player player) {
        if (economy == null || player == null) return 0.0;
        try {
            Method m = economy.getClass().getMethod("getBalance", org.bukkit.OfflinePlayer.class);
            Object r = m.invoke(economy, player);
            return (r instanceof Number) ? ((Number) r).doubleValue() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    public void deposit(Player player, double amount) {
        if (economy == null || player == null) return;
        try {
            Method m = economy.getClass().getMethod("depositPlayer", org.bukkit.OfflinePlayer.class, double.class);
            m.invoke(economy, player, amount);
        } catch (Exception ignored) {
        }
    }

    public void withdraw(Player player, double amount) {
        if (economy == null || player == null) return;
        try {
            Method m = economy.getClass().getMethod("withdrawPlayer", org.bukkit.OfflinePlayer.class, double.class);
            m.invoke(economy, player, amount);
        } catch (Exception ignored) {
        }
    }
}
