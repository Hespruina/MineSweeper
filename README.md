# MineSweeper

> 一个基于 Minecraft **Paper / Folia** 服务端的小游戏插件：玩家用灰色混凝土搭出一个平台，放置 TNT 即可开启一局「扫雷」。翻开所有非雷方块即获胜，胜利与失败均可触发由脚本定义的奖励。

本项目最大的特色是 **完全可编程的奖励系统**——奖励逻辑不再写死在 Java 代码里，而是由 `scripts/*.lgs`（[LogicStep](#奖励脚本-logicstep) 脚本语言）与 `config.yml` 中的流程共同驱动，无需重新编译即可修改金币、物品箱、命令等一切奖励行为。

---

## 目录

- [特性](#特性)
- [环境要求](#环境要求)
- [安装](#安装)
- [从源码构建](#从源码构建)
- [玩法](#玩法)
- [命令与权限](#命令与权限)
- [配置 config.yml](#配置-configyml)
- [奖励脚本 LogicStep](#奖励脚本-logicstep)
- [项目结构](#项目结构)
- [技术要点](#技术要点)
- [文档索引](#文档索引)
- [许可](#许可)

---

## 特性

- **即玩即建**：用灰色混凝土（GRAY_CONCRETE）铺一个 ≥5×5 的平台，在上面放一个 TNT（不蹲下）即可开局，无需预设地图。
- **标准扫雷玩法**：左键翻开、右键插旗，数字格表示周围 8 格的雷数，`0` 自动连锁展开。
- **难度可调**：教学 GUI 中选择 简单 / 中等 / 困难，对应雷数比例约为平台的 10% / 15% / 20%。
- **自动标雷**（可选，10×10 以上平台可用）：自动标记确定性雷位，并触发连锁翻开，降低繁琐手操。
- **可编程奖励**：胜利 / 失败时的奖励由 LogicStep 脚本 + `config.yml` 流程描述，支持发金币（Vault）、给物品、生成奖励箱、执行命令、广播、延时等动作。
- **持久化变量**：脚本可通过 `store_*` 指令读写跨重启保存的数据（如每日签到）。
- **服务端兼容**：同时兼容 **Paper / Spigot** 与 **Folia**，自动检测并选用正确的线程调度策略。
- **并发安全**：活跃游戏与位置索引使用线程安全集合，世界操作统一通过主线程调度。
- **热重载**：`/sweeper reload` 即可重新加载配置与脚本，无需重启服务器。

---

## 环境要求

| 依赖 | 版本 |
|------|------|
| Java | **17** 或更高 |
| 服务端 | Paper 1.20.1（或兼容的 Spigot / Folia） |
| Vault（可选） | 用于 `give_money` / `eco_*` 经济指令，需搭配任意经济插件 |

> 不安装 Vault 时，依赖经济的指令会被忽略，但物品箱、消息、命令等其它奖励仍可正常工作。

---

## 安装

1. 下载编译好的 `MineSweeper.jar`（见[从源码构建](#从源码构建)）。
2. 将 `MineSweeper.jar` 放入服务端的 `plugins/` 目录。
3. 启动（或重启）服务器。插件会在 `plugins/MineSweeper/` 下自动生成：
   - `config.yml` —— 游戏与奖励配置
   - `scripts/rewards.lgs` —— 内置奖励示例脚本
   - `persistence.yml` —— 脚本持久化数据（运行时产生）
4. （可选）安装 Vault 及一款经济插件，以启用金币奖励。

---

## 从源码构建

本项目使用 **Maven** 构建，使用 `maven-shade-plugin` 把所有内容打包进一个可执行 jar。

```bash
# 在项目根目录执行
mvn clean package
```

构建完成后，产物位于：

```
target/MineSweeper-1.jar
```

将其重命名（或直接）放入 `plugins/` 即可。

> 构建依赖 Paper 的 Maven 仓库（`repo.papermc.io`），已写入 `pom.xml`，无需额外配置。

---

## 玩法

1. **搭建平台**：用灰色混凝土（GRAY_CONCRETE）铺一个完整的矩形平台。**必须包含至少一个完整的 5×5 区域**（防止用一条直线蒙混过关）。
2. **开局**：站在平台上，对平台任意灰色混凝土方块 **放置一个 TNT**（注意：蹲下放置 TNT 不会触发游戏，便于你正常爆破自己的平台）。
3. **设置**：会自动打开一个教学 / 设置 GUI：
   - 前 9 格展示数字对应的颜色含义。
   - 第 13 格：切换「自动标记」开关（仅 10×10 以上平台可用）。
   - 第 18~20 格：选择 简单 / 中等 / 困难 难度。
   - 第 24 格：取消本局。
   - 第 26 格（TNT）：**启动游戏**（仅创建者可启动）。
4. **游戏进行**：
   - **左键** 翻开方块；**右键** 插旗（红石火把）/ 取消插旗。
   - 首次点击才真正布雷（仅保证点击格本身不是雷）。
   - 翻到雷 → 所有雷显示为 TNT，进入 10 秒退出倒计时，本局判负。
   - 翻开所有非雷方块 → 胜利。
   - 视线指向方块时，ActionBar 会实时提示该格示数或插旗提示。
5. **结束**：无论胜负，平台会还原为灰色混凝土，并按 `config.yml` 的 `rewards` 流程发放奖励。胜负结算 10 秒后自动退出（点击任意方块可重置倒计时）。

### 其它规则

- 破坏平台方块、用活塞推拉平台方块 → 立即终止本局。
- 平台区域内禁止放置方块（TNT 除外），禁止破坏奖励箱。
- 创建者开局后超过 15 秒未点「启动游戏」→ 自动解散，避免长期占用平台。
- 游戏无操作超过 60 秒 → 自动判负结束（可在 `config.yml` 调整）。

---

## 命令与权限

| 命令 | 权限 | 说明 |
|------|------|------|
| `/sweeper list` | —（控制台 / 任意玩家） | 列出当前所有进行中的游戏及其坐标 |
| `/sweeper reload` | `minesweeper.admin` | 热重载 `config.yml` 与 `scripts/*.lgs` |
| `/sweeper win [序号]` | `minesweeper.admin` | 强制指定游戏（或当前所在游戏）胜利 |
| `/sweeper exit [序号]` | `minesweeper.admin` | 强制指定游戏结束（判负） |
| `/sweeper see [序号]` | `minesweeper.admin` | 临时显示地雷 3 秒（作弊 / 调试用） |

> `win` / `exit` / `see` 若不提供序号，会尝试定位玩家脚下所在的游戏；当存在多个游戏时，需先用 `/sweeper list` 查看序号。

---

## 配置 config.yml

首次启动会自动生成默认配置（内置版本见 `src/main/resources/config.yml`）。

```yaml
game:
  auto_flag_enabled: false   # 全局默认是否开启自动标雷
  timeout_seconds: 60        # 无操作自动结束超时（秒）

rewards:
  win:                        # 胜利时执行的流程列表
    - trigger: "logic:has_reward_permission"   # 触发判定：调用模块 has_reward_permission
      vars:
        amount: "logic:jiangli"                # 变量预绑定：amount = 模块 jiangli 的返回值
      actions:
        - type: console_command
          command: "eco give {player} {amount}"
        - type: message
          message: "&6金币奖励：{amount}"
    - vars:
        bonus_msg: "&e额外奖励已发放"
      actions:
        - type: give_chest
          use_logic: "reward_chest_items"      # 调用模块填充奖励箱
        - type: message
          message: "&a你获得了奖励箱！{bonus_msg}"
  lose:                       # 失败时执行的流程
    - actions:
        - type: message
          message: "&c很遗憾，你输了！"

logic:
  max_steps: 50000            # 单次脚本执行最大步数
  max_eval_ms: 50             # 表达式求值超时（毫秒）
  constants:                  # 只读常量，脚本中以 global.常量名 访问
    base_eco: 100
```

### 奖励流程与动作

每个 `rewards` 流程包含：

- `trigger`：可选，调用某个 LogicStep 模块作为触发判定（返回真值才执行该流程）。
- `vars`：变量预绑定，`value` 形如 `logic:模块名`，执行前先求值并缓存，避免重复计算。
- `actions`：动作列表，按顺序执行。

可用动作类型（`type`）：

| 类型 | 关键参数 | 说明 |
|------|---------|------|
| `console_command` | `command` | 以控制台身份执行命令 |
| `player_command` | `command` | 以玩家身份执行命令 |
| `message` | `message` | 向玩家发消息（支持 `&` 颜色码） |
| `broadcast` | `message` | 全服广播 |
| `sound` | `sound` | 播放音效（Bukkit Sound 枚举名） |
| `give_item` | `material`, `amount` | 给予物品 |
| `give_chest` | `use_logic` | 调用 LogicStep 模块返回列表 / 映射来填充奖励箱 |
| `delay` | `ticks` | 延迟若干 tick 后执行后续动作 |

动作字符串中的占位符：

| 占位符 | 来源 |
|--------|------|
| `{player}` | 当前玩家名 |
| `{difficulty}` | 难度 |
| `{platform_size}` | 平台方块数 |
| `{mine_count}` | 地雷数 |
| `{global.xxx}` | `logic.constants.xxx` 常量 |
| `{vars 中的键}` | 流程 `vars` 绑定的值 |

---

## 奖励脚本 LogicStep

奖励与游戏逻辑的计算由 **LogicStep** —— 一门积木式脚本语言完成。脚本文件存放在 `plugins/MineSweeper/scripts/`，扩展名 `.lgs`，UTF-8 编码。

- **入口**：`config.yml` 通过 `logic:模块名` 引用脚本中的 `module`，引擎执行并返回结果。
- **数据类型**：数字（double）、字符串、布尔、列表、映射（Map，项目扩展类型）、空值。
- **控制流**：`if / else if / else`、`while`、`repeat / until`、`for / in`、`try / catch`、`exit`。
- **结构**：`step 名` / `module 名`，以 `end` 收尾；`mod.模块名(参数)` 调用模块，`jump.步骤名(参数)` 跳转。
- **安全沙箱**：脚本无文件系统 / 网络访问，仅能调用引擎暴露的指令与函数；有步数、递归深度、循环次数、求值超时等硬性限制。

完整语法、全部内置指令与函数（经济、随机、列表、映射、字符串、日期、持久化等）详见仓库内的 **[`src/main/resources/LogicStep语法说明.md`](src/main/resources/LogicStep语法说明.md)**。

内置示例脚本 **`scripts/rewards.lgs`** 已实现：

- `jiangli` —— 按难度 / 平台大小 / 自动标雷计算金币。
- `reward_chest_items` —— 生成奖励箱物品（混凝土 + 矿物 + TNT）。
- `weighted_item_list` / `calc_minerals` / `random_concrete` / `item_amount` —— 子模块。
- `has_reward_permission` —— 触发判定（始终返回真）。

---

## 项目结构

```
MineSweeper/
├── pom.xml                      # Maven 构建（Paper 依赖 + shade 打包）
├── src/main/
│   ├── java/top/zhrhello/mineSweeper/
│   │   ├── MineSweeperPlugin.java      # 插件入口、命令、定时器、Folia/Paper 线程调度
│   │   ├── MineSweeperListener.java    # 事件监听（放 TNT 开局、点击、破坏、爆炸防护等）
│   │   ├── MineSweeperGame.java        # 单局游戏状态机（布雷、翻开、标旗、胜负、奖励触发）
│   │   ├── config/                     # ConfigManager 配置加载、RewardFlow / RewardAction
│   │   ├── rewards/                    # RewardManager 奖励流程执行
│   │   └── logic/                      # LogicStep 引擎与宿主绑定
│   │       ├── lgs/                    # 词法 / 语法 / 编译 / 解释执行（自研脚本语言）
│   │       ├── GameContext.java        # 传入脚本的游戏上下文
│   │       ├── LogicEngine.java        # 脚本执行引擎
│   │       ├── Persistence.java        # 跨重启持久化（persistence.yml）
│   │       └── VaultHook.java          # Vault 经济桥接
│   └── resources/
│       ├── plugin.yml                  # 插件元数据与命令声明
│       ├── config.yml                  # 默认配置
│       ├── LogicStep语法说明.md         # 脚本语言完整参考
│       └── scripts/rewards.lgs         # 内置奖励脚本
└── target/                            # 构建产物
```

---

## 技术要点

- **Folia / Paper 兼容**：通过反射检测 `RegionizedServer` 判断 Folia，自动切换 `RegionScheduler` / `GlobalRegionScheduler` 或经典 `runTask`，所有世界操作统一经 `executeOnMainThread` 保证线程正确。
- **线程安全**：活跃游戏列表用 `CopyOnWriteArrayList`，位置 → 游戏索引用 `ConcurrentHashMap`；配置对象在热重载时原子替换（`volatile`）。
- **平台检测**：BFS 连通区域识别灰色混凝土平台，并做 5×5 完整矩形校验，防止直线作弊。
- **防护**：拦截平台上的 TNT 爆炸、活塞推拉、方块破坏，避免游戏区域被破坏或利用。
- **奖励解耦**：`MineSweeperGame.endGame()` 只负责状态清理，奖励完全交由 `RewardManager` 按配置执行，逻辑与游戏核心分离。

---

## 文档索引

| 文件 | 内容 |
|------|------|
| `src/main/resources/LogicStep语法说明.md` | LogicStep 脚本语言完整语法、指令、函数、安全限制与示例 |
| `src/main/resources/config.yml` | 默认配置示例 |
| `src/main/resources/scripts/rewards.lgs` | 内置奖励脚本（金币 + 奖励箱） |

---

> 包名：`top.zhrhello.mineSweeper` · 插件名：`MineSweeper` · 适配 Minecraft 1.20.1
