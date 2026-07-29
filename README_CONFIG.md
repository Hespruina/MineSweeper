# MineSweeper 配置文件与 Logic 逻辑编程系统 详解

> 本文档详细说明 `config.yml` 的全部配置项、奖励系统的工作方式，以及内嵌的 **Logic 逻辑编程引擎**——一套基于 YAML 步骤序列、高度自由化的脚本语言。
> 所有奖励规则、条件判断、随机抽取、外部命令执行、持久化数据操作都可以通过本文件完成，**无需修改 Java 代码**。

---

## 目录

1. [文件位置与加载](#1-文件位置与加载)
2. [顶层结构总览](#2-顶层结构总览)
3. [`game` 全局设置](#3-game-全局设置)
4. [`rewards` 奖励系统](#4-rewards-奖励系统)
   - 4.1 触发流程（flow）
   - 4.2 条件触发 `trigger`
   - 4.3 变量绑定 `vars`（重要）
   - 4.4 动作 `actions` 与 8 种动作类型
   - 4.5 占位符系统（优先级）
5. [`logic` 逻辑编程系统](#5-logic-逻辑编程系统)
   - 5.1 基础概念：变量、作用域、函数、上下文继承
   - 5.2 函数定义模板
   - 5.3 步骤语法（两种写法）
   - 5.4 步骤类型参考（全表 + 示例）
   - 5.5 表达式语法详解
   - 5.6 `call` 调用与上下文继承
   - 5.7 持久化存储
6. [执行安全与错误处理](#6-执行安全与错误处理)
7. [`/sweeper reload` 热重载](#7-sweeper-reload-热重载)
8. [完整示例合集](#8-完整示例合集)
9. [常见问题排查](#9-常见问题排查)

---

## 1. 文件位置与加载

| 项 | 说明 |
|----|------|
| 文件名 | `config.yml` |
| 位置 | 插件数据目录 `plugins/MineSweeper/config.yml` |
| 首次启动 | 插件自动从 jar 内释放一份默认 `config.yml`（含完整示例 Logic 函数）；若已存在则不覆盖 |
| 持久化数据 | `plugins/MineSweeper/data.yml`（由 `store_*` 步骤写入，跨重启保留） |
| 热重载 | 修改后执行 `/sweeper reload`（权限 `minesweeper.admin`）即时生效，不影响进行中的游戏 |

> **提示**：如果你想恢复出厂配置，删除 `config.yml` 后重载即可重新生成。

---

## 2. 顶层结构总览

```yaml
game:
  auto_flag_enabled: false      # 是否启用自动标记
  timeout_seconds: 60           # 单局超时（秒）

rewards:
  win:                          # 胜利时执行的流程列表
    - trigger: "logic:has_reward_permission"
      vars:
        amount: "logic:jiangli"
      actions:
        - type: console_command
          command: "eco give {player} {amount}"
        - type: message
          message: "&a你获得了 {amount} 金币！"
    - actions:                  # 无条件流程（无 trigger）
        - type: give_chest
          use_logic: "reward_chest_items"
  lose:                         # 失败时执行的流程列表
    - actions:
        - type: message
          message: "&c很遗憾，你输了！"

logic:
  max_steps: 50000              # 单次顶层调用的总步数上限
  max_eval_ms: 50               # 单个表达式最大执行时间（毫秒）
  constants:                    # 只读全局常量，可被 {global.xxx} 引用
    base_eco: 100
  functions:                    # 函数定义，键为函数名
    # ... 见下文
```

三大块职责：

- **`game`**：插件的运行时开关（自动标记、超时）。
- **`rewards`**：声明**在玩家胜利/失败时做什么**（发命令、发物品、发消息、生成箱子……）。
- **`logic`**：声明**可复用的计算函数**（金币公式、箱子内容、随机逻辑……），并被 `rewards` 通过 `logic:` 前缀调用。

---

## 3. `game` 全局设置

| 键 | 类型 | 默认 | 说明 |
|----|------|------|------|
| `auto_flag_enabled` | 布尔 | `false` | 是否自动标记确定的地雷 |
| `timeout_seconds` | 整数 | `60` | 单局无操作超时秒数 |

```yaml
game:
  auto_flag_enabled: true
  timeout_seconds: 120
```

---

## 4. `rewards` 奖励系统

### 4.1 触发流程（flow）

`rewards` 下每个键（如 `win`、`lose`，你也可以自定义更多触发点名）都是一个**流程列表**。每个流程是一个执行单元，包含以下字段：

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `trigger` | 字符串 | 否 | 条件函数，格式 `logic:函数名`。函数返回 `0`/`false`/空字符串则**跳过该流程** |
| `vars` | 映射 | 否 | 在动作执行前预先对 Logic 调用求值，结果绑定为局部变量（见 4.3） |
| `actions` | 列表 | 是 | 要按顺序执行的动作序列 |

> 多个流程是**顺序执行**的；某个流程被 `trigger` 跳过不影响其它流程。

### 4.2 条件触发 `trigger`

```yaml
rewards:
  win:
    - trigger: "logic:has_reward_permission"   # 返回真值才执行本流程
      actions:
        - type: message
          message: "&a权限校验通过，发放奖励"
```

`trigger` 的取值固定为 `logic:函数名`。引擎会调用该函数：
- 返回 `1` / `true` / 非空字符串 → 执行流程
- 返回 `0` / `false` / `""`（空） → 跳过流程

> 若流程**没有** `trigger`，则无条件执行（如上面的 `give_chest` 流程）。

### 4.3 变量绑定 `vars`（重要改进）

在 `actions` 执行前，引擎先解析 `vars` 映射。每个键值对定义一个**动作组内变量**。值可以是：

- `"logic:函数名"`：执行该函数，**捕获其返回值**赋给变量（函数**每个最多执行一次**，结果缓存）
- 任意字面量字符串：直接作为变量的值

```yaml
rewards:
  win:
    - vars:
        amount: "logic:jiangli"              # 调用 jiangli，返回值存入 amount
        bonus_msg: "&e额外奖励已发放"         # 纯字面量
      actions:
        - type: console_command
          command: "eco give {player} {amount}"
        - type: message
          message: "&a你获得了 {amount} 金币！{bonus_msg}"
```

引擎保证：

- `jiangli` 函数**仅执行一次**，返回值赋给 `amount`；即使动作里多次出现 `{amount}`，全部替换为同一值，逻辑绝对一致。
- `bonus_msg` 为字面量，无需函数调用。

> **为什么不用占位符直接写 `{logic:jiangli}`？** 直接占位符每次出现都会独立执行一次函数，既浪费又可能导致多次副作用（如重复发钱）。`vars` 绑定才是推荐做法（见 4.5 优先级④的弃用提醒）。

### 4.4 动作 `actions` 与 8 种动作类型

| 动作类型 | 参数字段 | 说明 |
|----------|----------|------|
| `console_command` | `command: "..."` | 以**控制台**身份执行命令，支持占位符 |
| `player_command` | `command: "..."` | 强制**玩家**执行命令，支持占位符 |
| `message` | `message: "..."` | 向玩家发送一条消息 |
| `broadcast` | `message: "..."` | 全服广播 |
| `sound` | `sound: "..."` | 播放音效（`Sound` 枚举名，如 `ENTITY_PLAYER_LEVELUP`） |
| `give_item` | `material: DIAMOND`, `amount: 1` | 直接给予物品，支持占位符 |
| `give_chest` | `use_logic: "函数名"` | 执行 Logic 函数，读取其**返回值**（列表/映射）填充箱子 |
| `delay` | `ticks: 20` | 延迟指定 tick 数后继续执行后续动作（兼容 Folia/Paper 异步线程） |

**示例：**

```yaml
actions:
  - type: console_command
    command: "eco give {player} 100"
  - type: player_command
    command: "warp vip"
  - type: message
    message: "&a恭喜通关！"
  - type: broadcast
    message: "&6{player} 刚刚清空了扫雷棋盘！"
  - type: sound
    sound: "ENTITY_PLAYER_LEVELUP"
  - type: give_item
    material: DIAMOND
    amount: 3
  - type: give_chest
    use_logic: "reward_chest_items"   # 函数返回值必须是列表/映射/字符串
  - type: delay
    ticks: 40                          # 2 秒（20 tick = 1 秒）后再继续后续动作
```

**`give_chest` 的返回值格式**（由 `use_logic` 指向的函数 `return`）：

- **列表**：每个元素是 `"材质名:数量"` 字符串（数量可省略，默认 1）。例如 `["DIAMOND:3", "IRON_INGOT", "WHITE_CONCRETE:16"]`
- **映射**：键为材质名，值为数量。例如 `{DIAMOND: 3, IRON_INGOT: 5}`
- **字符串**：单条 `"材质名:数量"`

```yaml
# 推荐用列表形式（与内置示例一致）
reward_chest_items:
  steps:
    - list_create: {target: result}
    - list_add: {target: result, value: "DIAMOND:3"}
    - list_add: {target: result, value: "IRON_INGOT:5"}
    - return: "{result}"
```

### 4.5 占位符系统（优先级）

动作字符串中可用 `{变量名}` 占位符，解析优先级如下：

1. **内置上下文变量**：`{player}`、`{difficulty}`、`{platform_size}`、`{mine_count}`
2. **`vars` 中定义的变量**（如 `{amount}`、`{bonus_msg}`）
3. **Logic 全局常量/变量**：`{global.xxx}`（见 5.1 作用域）
4. **已弃用语法**：`{logic:函数名}`（每次出现独立执行，不推荐，控制台会输出警告）

```yaml
actions:
  - type: message
    message: "&a玩家 {player}（难度 {difficulty}）在 {platform_size} 格平台上清雷成功！"
```

| 占位符 | 含义 |
|--------|------|
| `{player}` | 玩家名 |
| `{difficulty}` | 难度等级（1-3） |
| `{platform_size}` | 平台方块总数 |
| `{mine_count}` | 地雷数量 |
| `{amount}` / 自定义 | `vars` 绑定的变量 |
| `{global.base_eco}` | logic 下定义的只读常量（见 5.1） |

---

## 5. `logic` 逻辑编程系统

Logic 引擎是一套基于 YAML 步骤的领域专用语言（DSL），**图灵完备**，专为游戏逻辑设计。所有函数在 `logic.functions` 下定义。

### 5.1 基础概念

**变量（动态类型）**：整数、小数、字符串、布尔、列表、映射。变量名区分大小写。通过 `set`/`eval` 创建，或通过 `call` 捕获返回值、`for`/`for_each` 迭代产生。

**作用域：**

| 作用域 | 说明 | 生命周期 |
|--------|------|----------|
| 局部变量 | 函数内部创建 | 函数返回后销毁 |
| 参数变量 | 由 `parameters` 声明，调用时按位置/名称传入，视为局部 | 函数内有效 |
| 全局变量 | 以 `global.` 前缀（如 `set: {target: global.counter, value: 1}`） | 整个插件生命周期（**重启丢失**）；如需持久化见 5.7 |
| 只读常量 | `logic.constants` 中定义 | 通过 `{global.xxx}` 引用，不可被赋值覆盖（赋值会被忽略并警告） |
| 动作组变量 | 由奖励流程的 `vars` 绑定 | 仅在该组动作的占位符中可见，不进入 Logic 函数作用域 |

**函数**：通过 `parameters` 声明期望参数；通过步骤序列完成计算；**必须显式用 `return` 返回值**；无 `return` 或执行到末尾默认返回空字符串 `""`。

**上下文继承**：由奖励系统触发的顶层函数（如 `trigger`、`vars` 中的调用、`use_logic`）会自动获得**当前玩家和游戏信息**上下文。通过 `call` 调用子函数时**默认继承全部上下文**，子函数可直接使用 `get_player`/`get_difficulty` 等步骤；若需隔离，在 `call` 加 `context: false`。

### 5.2 函数定义模板

```yaml
logic:
  functions:
    函数名:
      parameters: [param1, param2]   # 可选；声明参数名列表
      steps:
        - get_difficulty: -> diff
        - set: {target: localVar, value: "字符串"}
        - eval: {target: result, expression: "{diff} * {param1}"}
        - return: "{result}"
```

- 函数内可直接通过 `{param1}` 引用参数值。
- `parameters` 可省略（无参函数）。

### 5.3 步骤语法（两种写法）

每个步骤是一个 YAML 映射，**只有一个步骤类型键**。输出变量 `out` 有三种等价写法：

**写法 A：内联 `out`/`target` 键（推荐，最清晰）**

```yaml
- get_player: {out: pname}
- random_int: {target: rand, min: 1, max: 10}
```

**写法 B：字符串箭头 `->`**

```yaml
- get_player: -> pname
```

**写法 C：`call` 的兄弟键形式**（用于带参数/输出捕获的 `call`）

```yaml
- call: weighted_item_list      # 函数名
  with: [level]                  # 传入参数
  out: items                     # 捕获返回值到 items
  # context: false              # 可选：不继承上下文
```

> 同一映射中**只能有一个步骤类型键**，其余只能是元信息键（`with`/`out`/`context`/`->`），否则加载时报错。

### 5.4 步骤类型参考（全表 + 示例）

#### 变量赋值 —— `set`

`value` 只能是**字面量**或含 `{变量名}` 的字符串（赋值时替换），**不得编写表达式**。

```yaml
- set: {target: level, value: 0}
- set: {target: greeting, value: "你好，{player}"}
- set: {target: global.counter, value: 1}   # 写入全局变量
```

#### 表达式求值 —— `eval`

`expression` 支持算术、字符串拼接、比较、逻辑与括号（详见 5.5）。

```yaml
- eval: {target: raw_eco, expression: "{diff} * 50 + {size} * 2"}
- eval: {target: entry, expression: '"{mat_name}" + ":" + {amt}'}
- eval: {target: bigger, expression: "{a} > {b}"}
```

#### 内置游戏数据读取

| 步骤 | 说明 | 示例 |
|------|------|------|
| `get_player` | 获取当前玩家名 | `- get_player: {out: pname}` |
| `get_difficulty` | 获取难度等级（1-3） | `- get_difficulty: {out: diff}` |
| `get_platform_size` | 获取平台方块总数 | `- get_platform_size: {out: size}` |
| `get_mine_count` | 获取地雷数量 | `- get_mine_count: {out: mines}` |
| `get_auto_flag` | 获取自动标记状态（0/1） | `- get_auto_flag: {out: autoflag}` |

#### 内置通用功能

| 步骤 | 参数 | 说明 |
|------|------|------|
| `random_int` | `{target, min, max}` | 生成 `[min, max]` 内随机整数 |
| `random_chance` | `{target, percent}` | `percent%` 概率置 `1`，否则 `0` |
| `check_permission` | `{target, permission}` | 玩家有权限则 `1`，否则 `0` |
| `eco_balance` | `{target}` | 获取玩家经济余额 |
| `eco_give` | `{amount, player}` | 给予金币（缺省当前玩家） |
| `eco_take` | `{amount, player}` | 扣除金币 |
| `log` | `{level, message}` | 输出日志（`INFO`/`WARN`/`ERROR`/`DEBUG`） |
| `notify_admin` | `{message}` | 向在线管理员发送消息 |

```yaml
- random_int: {target: rand, min: 1, max: 100}
- random_chance: {target: lucky, percent: 33}
- check_permission: {target: has_vip, permission: "server.vip"}
- eco_balance: {target: bal}
- eco_give: {amount: 100, player: "Hespruina"}
- log: {level: INFO, message: "玩家 {player} 通关，难度 {diff}"}
- notify_admin: {message: "有人触发了奖励"}
```

> `eco_*` 依赖 Vault 经济插件；未安装 Vault 时静默失败，不影响其它逻辑。

#### 条件分支 —— `if`

```yaml
- if:
    condition: "{diff} >= 2"
    then:
      - set: {target: level, value: 2}
    else:
      - set: {target: level, value: 1}
```

`else` 可选。`condition` 是一个表达式（返回 `1`/`true` 为真）。

#### 循环

**`while`**（条件循环，支持 `max_iterations`）

```yaml
- set: {target: i, value: 0}
- while:
    condition: "{i} < 5"
    max_iterations: 10000     # 可选，默认 10000
    do:
      - eval: {target: i, expression: "{i} + 1"}
```

**`for`**（计数循环，自动提供迭代变量）

```yaml
- for:
    range: [0, 10]    # 从 0 到 9（含头不含尾）
    var: i
    do:
      - log: {level: INFO, message: "当前 i = {i}"}
```

**`for_each`**（遍历列表或映射，自动提供迭代变量）

遍历 `target` 指向的列表或映射，逐元素执行 `do`。遍历**列表**时把元素赋给 `var`；遍历**映射**时把键赋给 `key_var`、值赋给 `var`。

```yaml
- for_each:
    target: global.mats        # 列表或映射
    var: item                  # 元素值（列表）或映射的值
    key_var: key               # 可选；遍历映射时保存当前键，列表遍历可省略
    max_iterations: 10000      # 可选，默认 10000
    do:
      - log: {level: INFO, message: "key={key} value={item}"}
```

```yaml
# 遍历映射：统计每种材质的数量
- map_create: {target: counts}
- for_each:
    target: counts
    var: cnt
    key_var: mat
    do:
      - eval: {target: total, expression: "{total} + {cnt}"}
```

> `for_each` 可直接遍历映射的键值对，无需像 `while` + `map_keys` + `map_get` 那样手动取键再查值。配合 `break` 可提前结束。

**`break`**（提前结束当前循环）

```yaml
- while:
    condition: "1"
    do:
      - if:
          condition: "{i} >= 3"
          then:
            - break:
      - eval: {target: i, expression: "{i} + 1"}
```

#### 列表与映射操作

| 步骤 | 参数 | 说明 |
|------|------|------|
| `list_create` | `target` | 创建空列表 |
| `list_add` | `{target, value}` | 尾部添加元素 |
| `list_set` | `{target, index, value}` | 设置索引值 |
| `list_get` | `{target, index} -> outVar` | 获取索引元素（输出到 `outVar`） |
| `list_remove` | `{target, index}` | 移除索引元素 |
| `list_size` | `{target} -> outVar` | 获取长度 |
| `map_create` | `target` | 创建空映射 |
| `map_put` | `{target, key, value}` | 设置键值 |
| `map_get` | `{target, key} -> outVar` | 获取键对应值 |
| `map_remove` | `{target, key}` | 移除键 |
| `map_keys` | `{target} -> outVar` | 获取所有键（列表） |

> `list_get`/`list_size`/`map_get`/`map_keys` 用 `out:` 输出；`get_*` 系列同样用 `out:`。

```yaml
- list_create: {target: mats}
- list_add: {target: mats, value: "DIAMOND"}
- list_add: {target: mats, value: "IRON_INGOT"}
- list_size: {target: mats} -> mat_count
- list_get: {target: mats, index: 0} -> first

- map_create: {target: scores}
- map_put: {target: scores, key: "a", value: 10}
- map_get: {target: scores, key: "a"} -> a_score
- map_keys: {target: scores} -> all_keys
```

#### 函数返回 —— `return`

立即结束当前函数，将值返回调用者。无 `return` 默认返回 `""`。

```yaml
- return: "{eco_value}"
- return: "{result}"          # result 可以是列表/映射
- return: 1                    # 也可返回字面量
```

#### 调用外部函数 —— `call`

```yaml
- call: 函数名                         # 仅执行，不捕获
- call: 函数名 with: [val0, val1]      # 按位置传参
- call: 函数名 with: {p1: v1, p2: v2}  # 按名称传参
- call: 函数名 with: [...] -> 变量名    # 捕获返回值
- call: 函数名 with: [...] context: false   # 隔离上下文
```

详见 5.6。

#### 持久化存储 —— `store_*`

允许在跨重启的 `data.yml` 中保存键值对，用于统计、累计次数等。

| 步骤 | 参数 | 说明 |
|------|------|------|
| `store_set` | `{key, value}` | 写入持久化，key 可分层（如 `player.kills`） |
| `store_get` | `{key} -> var` | 读取键值，不存在返回 `""` |
| `store_remove` | `{key}` | 删除键 |

```yaml
- store_get: {key: "player.{player}.wins"} -> wins
- eval: {target: wins, expression: "{wins} + 1"}
- store_set: {key: "player.{player}.wins", value: "{wins}"}
```

### 5.5 表达式语法详解

`eval` 的表达式由递归下降解析器处理。**占位符 `{变量名}` 在解析前已被替换为值**，因此表达式里最终只剩数字、字符串字面量、运算符、括号、函数调用与关键字。

#### 运算符（按优先级从低到高）

| 类别 | 运算符 | 说明 | 结果 |
|------|--------|------|------|
| 三元 | `? :` | `cond ? a : b`，cond 为真取 a 否则 b（右结合） | a 或 b |
| 逻辑或 | `or` | 逻辑或 | `1`/`0` |
| 逻辑与 | `and` | 逻辑与 | `1`/`0` |
| 逻辑非 | `not` | 一元非 | `1`/`0` |
| 比较 | `> < >= <= == !=` | 数值或字符串比较 | `1`/`0` |
| 字符串关系 | `contains` `starts_with` `ends_with` `matches` `in` | 见下表 | `1`/`0` |
| 加减 | `+ -` | `+` 任一操作数为字符串时为拼接 | 数/字符串 |
| 乘除模 | `* / %` | 数值运算 | 数 |
| 一元负号 | `-` | 取负 | 数 |
| 括号 | `( )` | 提升优先级 | — |

**字符串关系运算符**（左右操作数都按字符串处理）：

| 运算符 | 含义 | 示例（`{mat}` = `"LIME_CONCRETE"`） |
|--------|------|------|
| `contains` | 左串包含右串 | `"{mat}" contains "CONCRETE"` → `1` |
| `starts_with` | 左串以右串开头 | `"{mat}" starts_with "LIME"` → `1` |
| `ends_with` | 左串以右串结尾 | `"{mat}" ends_with "_CONCRETE"` → `1` |
| `matches` | 左串匹配右串正则 | `"{mat}" matches ".*_CONCRETE"` → `1` |
| `in` | 左串出现在右串中 | `"{color}" in "RED,GREEN,BLUE"` → `1`/`0` |

> 关键字运算符必须用**英文小写**（`contains`），不要写成中文 `包含`，否则报 `无法识别的标识符`。

#### 函数调用

语法 `函数名(参数1, 参数2, ...)`，参数个数不符会报错，函数可嵌套。

```yaml
- eval: {target: n, expression: "floor({x} / 2)"}
- eval: {target: t, expression: "is_number({v})"}
- eval: {target: s, expression: "upper({name})"}
- eval: {target: big, expression: "max({a}, {b})"}
- eval: {target: today, expression: 'date("yyyy-MM-dd")'}
```

#### 内置函数一览

**数学**

| 函数 | 说明 |
|------|------|
| `abs(x)` `floor(x)` `ceil(x)` `round(x)` `sqrt(x)` `sign(x)` | 绝对值 / 向下取整 / 向上取整 / 四舍五入 / 平方根 / 符号 |
| `pow(x, y)` | 幂 |
| `max(a, b)` `min(a, b)` | 最大 / 最小 |
| `clamp(x, lo, hi)` | 限制在 `[lo, hi]` 区间 |
| `mod(a, b)` | 取模（与 `%` 一致，b 为 0 报错） |

**类型判断**

| 函数 | 说明 |
|------|------|
| `is_number(x)` `is_string(x)` `is_list(x)` `is_map(x)` `is_bool(x)` | 类型判断，返回 `1`/`0` |
| `type_of(x)` | 返回类型名：`number`/`string`/`list`/`map`/`boolean`/`null` |

**类型转换**

| 函数 | 说明 |
|------|------|
| `to_number(x)` | 安全转数字（失败返回 `0`） |
| `to_int(x)` | 转整数（向下取整） |

**字符串 / 集合**

| 函数 | 说明 |
|------|------|
| `length(x)` | 字符串长度 / 列表长度 / 映射大小 |
| `upper(x)` `lower(x)` `trim(x)` | 大写 / 小写 / 去首尾空白 |
| `index_of(s, sub)` | 子串首次出现位置（从 0，未找到 -1） |
| `contains(s, sub)` `starts_with(s, p)` `ends_with(s, p)` `matches(s, regex)` | 同名运算符的函数形式 |
| `replace(s, a, b)` | 把 s 中所有 a 替换为 b |
| `substr(s, start[, len])` | 子串；省略 len 取到末尾 |
| `split(s, sep)` | 按分隔符拆分为列表 |
| `join(list, sep)` | 用 sep 拼接列表为字符串 |

**日期 / 时间**（毫秒时间戳，配合持久化做限额 / 冷却）

| 函数 | 说明 |
|------|------|
| `now()` | 当前毫秒时间戳 |
| `date([fmt])` | 当前时间按 `fmt`（默认 `yyyy-MM-dd`）格式化为字符串 |
| `date_format(ts, fmt)` | 把时间戳 `ts` 格式化为字符串 |
| `date_parse(s, fmt)` | 把字符串 `s` 按 `fmt` 解析为时间戳，失败报错 |
| `date_diff(t1, t2, unit)` | 返回 `t2 - t1` 在单位下的差值；`unit` 取 `days`/`hours`/`minutes`/`seconds`/`millis`（支持缩写 d/h/m/s/ms）。`days` 按**日历天**计算（截断到当天 00:00），可正确判断跨天 |

**其它**

| 函数 | 说明 |
|------|------|
| `if(cond, a, b)` | 三元函数形式，等价 `cond ? a : b` |
| `pick(list)` | 从列表随机取一个元素 |
| `range(a, b)` | 生成 `[a, b)` 整数列表 |

#### 其它约定

- **变量引用**：`{变量名}` 在解析前替换；数字裸插入，字符串在未被引号包裹时自动加引号。
- **字符串字面量**：用双引号包裹。YAML 中推荐用单引号包住整个 `expression`，内部用双引号包字符串字面量，如 `'"{mat}" == "DIAMOND"'`、`'"{mat}" contains "CONCRETE"'`。
- **类型转换**：算术运算自动转数字，`+` 遇字符串自动拼接。
- **求值出错**（除零、类型错误、超时、未知函数等）会记录错误并终止函数（见第 6 节）。

```yaml
- eval: {target: a, expression: "{x} + {y} * 2"}
- eval: {target: b, expression: '"{name}" + " 通关了"' }            # 字符串拼接
- eval: {target: c, expression: "({x} > 10) and ({y} < 5)"}
- eval: {target: d, expression: "not ({flag} == 1)"}
- eval: {target: e, expression: '"{mat}" == "DIAMOND"'}
- eval: {target: f, expression: '"{mat}" contains "CONCRETE"'}      # 字符串包含
- eval: {target: g, expression: "{size} > 100 ? 1 : 0"}             # 三元
- eval: {target: h, expression: "floor({x} / 3)"}                   # 函数
- eval: {target: k, expression: 'date_diff({last}, now(), "days")'} # 距今天数
```

> **安全上限**：单个 `eval` 最长执行 `logic.max_eval_ms`（默认 50ms），超时按错误处理。

### 5.6 `call` 调用与上下文继承

```yaml
logic:
  functions:
    main:
      steps:
        - get_difficulty: -> diff
        - call: helper with: [diff] -> res     # 传参并捕获返回值
        - return: "{res}"
    helper:
      parameters: [d]
      steps:
        - get_player: -> p                     # 默认继承父上下文，可直接读玩家
        - eval: {target: out, expression: "{d} * 10"}
        - return: "{out}"
```

- `call` 默认 `context: true`：子函数继承父函数的玩家/游戏上下文。
- `context: false`：子函数得到干净上下文（无法读取 `get_player` 等）。

### 5.7 持久化存储（跨重启）

`store_*` 写入 `plugins/MineSweeper/data.yml`，线程安全落盘。适合做累计统计、每日限额、玩家积分等。

```yaml
logic:
  functions:
    record_win:
      steps:
        - get_player: -> p
        - store_get: {key: "wins.{p}"} -> n
        - eval: {target: n, expression: "{n} + 1"}
        - store_set: {key: "wins.{p}", value: "{n}"}
        - return: "{n}"
```

---

## 6. 执行安全与错误处理

为防止脚本失控，引擎内置多层保护：

| 限制 | 默认 | 配置位置 | 说明 |
|------|------|----------|------|
| 总步数上限 | 50000 | `logic.max_steps` | 每个顶层调用（含嵌套）累计步数 |
| 单表达式超时 | 50ms | `logic.max_eval_ms` | 每个 `eval` 最长执行时间，超时按错误 |
| 循环上限 | 10000 | `while`/`for` 的 `max_iterations` | 单次循环最大迭代次数 |
| 递归深度 | 50 | 硬编码 | 函数调用栈超过则判定递归过深，立即终止 |

**错误处理流程**（运行时发生类型错误、除零、变量未定义、超时等）：

1. 记录 `ERROR` 日志（含函数名、步骤序号、错误详情）。
2. 若执行由玩家行为触发，调用 `notify_admin` 通知在线管理员。
3. 向触发玩家发送友好提示（如 `§c[扫雷] Logic 脚本执行出错: ...`）。
4. 终止整个调用链。

> 因真实服务器主线程在反作弊等负载下会偶发停顿，默认 `max_eval_ms` 设为 **50ms**（规范建议 5ms）。若你确需严格 5ms，把 `config.yml` 中 `logic.max_eval_ms: 5` 后重载即可，**无需重新打包**。

---

## 7. `/sweeper reload` 热重载

- **权限**：`minesweeper.admin`
- **执行时**：
  1. 重新加载 `config.yml`。
  2. 解析所有 Logic 函数，进行**静态检查**（步骤语法、必填字段、循环引用检测、未知函数检测等）。
  3. 解析成功则**原子替换**内存中的配置引用；失败则保持旧配置并返回详细错误。
  4. 不影响正在运行的游戏，新配置只对后续触发事件生效。
  5. 输出统计：函数总数、动作总数、持久化条目数、以及所有警告。

```text
[MineSweeper] 配置加载成功：函数 6 个，动作 5 个，持久化条目 0 个（警告 0 条）
```

---

## 8. 完整示例合集

### 示例 1：VIP 双倍金币

```yaml
rewards:
  win:
    - vars:
        amount: "logic:jiangli"
      actions:
        - type: console_command
          command: "eco give {player} {amount}"
        - type: message
          message: "&6金币奖励：{amount}"

logic:
  functions:
    jiangli:
      steps:
        - get_difficulty: -> diff
        - get_platform_size: -> size
        - eval: {target: raw_eco, expression: "{diff} * 50 + {size} * 2"}
        - check_permission: {target: has_vip, permission: "server.vip"}
        - if:
            condition: "{has_vip} == 1"
            then:
              - random_chance: {target: lucky, percent: 33}
              - if:
                  condition: "{lucky} == 1"
                  then:
                    - set: {target: eco_value, value: "{raw_eco}"}
                  else:
                    - set: {target: eco_value, value: 0}
            else:
              - set: {target: eco_value, value: 0}
        - return: "{eco_value}"
```

### 示例 2：按难度生成奖励箱（权重随机）

```yaml
rewards:
  win:
    - actions:
        - type: give_chest
          use_logic: "reward_chest_items"
        - type: message
          message: "&a奖励箱已生成！"

logic:
  functions:
    reward_chest_items:
      parameters: []
      steps:
        - get_difficulty: -> diff
        - get_platform_size: -> size
        - get_auto_flag: -> autoflag
        - set: {target: level, value: 0}
        - if: {condition: "{diff} == 1", then: [ {set: {target: level, value: 1}} ]}
        - if: {condition: "{diff} == 2", then: [ {set: {target: level, value: 2}} ]}
        - if: {condition: "{diff} == 3", then: [ {set: {target: level, value: 3}} ]}
        - if: {condition: "{size} > 100", then: [ {eval: {target: level, expression: "{level} + 1"}} ]}
        - if: {condition: "{autoflag} == 1", then: [ {eval: {target: level, expression: "{level} - 1"}} ]}
        - call: weighted_item_list with: [level] -> items
        - return: "{items}"

    weighted_item_list:
      parameters: [reward_level]
      steps:
        - if:
            condition: "{global.weights_init} != 1"
            then:
              - set: {target: global.weights_init, value: 1}
              - list_create: {target: global.mats}
              - list_add: {target: global.mats, value: "WHITE_CONCRETE"}
              - list_add: {target: global.mats, value: "DIAMOND"}
              - set: {target: global.w_WHITE_CONCRETE, value: [20, 40, 60, 80]}
              - set: {target: global.w_DIAMOND, value: [0, 0, 5, 15]}
        - list_create: {target: result}
        - set: {target: i, value: 0}
        - list_size: {target: global.mats} -> mat_count
        - while:
            condition: "{i} < {mat_count}"
            do:
              - list_get: {target: global.mats, index: "{i}"} -> mat_name
              - eval: {target: weight_key, expression: '"global.w_" + {mat_name}'}
              - set: {target: weight_array, value: "{$weight_key}"}
              - list_get: {target: weight_array, index: "{reward_level}"} -> weight
              - random_int: {target: rand, min: 1, max: 100}
              - if:
                  condition: "{rand} <= {weight}"
                  then:
                    - call: item_amount with: [mat_name, reward_level] -> amt
                    - eval: {target: entry, expression: '"{mat_name}" + ":" + {amt}'}
                    - list_add: {target: result, value: "{entry}"}
              - eval: {target: i, expression: "{i} + 1"}
        - return: "{result}"

    item_amount:
      parameters: [material, level]
      steps:
        - eval: {target: base, expression: "{level} + 1"}
        - if: {condition: '"{material}" == "DIAMOND"', then: [ {random_int: {target: amt, min: 1, max: "{base}"}} ]}
        - if: {condition: '"{material}" == "IRON_INGOT"', then: [ {random_int: {target: amt, min: 1, max: "{base} + 2"}} ]}
        - return: "{amt}"
```

> 要点：`{global.weights_init}` 首次为未定义（等价于空串 `""`），`"" != 1` 为真 → 初始化全局权重表；之后置 `1` 跳过。全局变量在插件运行期内跨函数共享。

### 示例 3：玩家累计通关统计 + 每日限额

```yaml
rewards:
  win:
    - vars:
        total: "logic:add_win"
      actions:
        - type: message
          message: "&a你已累计通关 {total} 次！"

logic:
  functions:
    add_win:
      steps:
        - get_player: -> p
        - store_get: {key: "wins.{p}"} -> n
        - eval: {target: n, expression: "{n} + 1"}
        - store_set: {key: "wins.{p}", value: "{n}"}
        - return: "{n}"
```

### 示例 4：失败才广播，胜利才发箱子

```yaml
rewards:
  win:
    - actions:
        - type: give_chest
          use_logic: "reward_chest_items"
  lose:
    - actions:
        - type: broadcast
          message: "&c{player} 在扫雷中失败了……"
```

### 示例 5：延迟发放 + 音效

```yaml
rewards:
  win:
    - actions:
        - type: sound
          sound: "ENTITY_PLAYER_LEVELUP"
        - type: delay
          ticks: 40
        - type: message
          message: "&a两秒后见，奖励马上到！"
```

### 示例 6：用 `contains` 统计混凝土数量

判断材质名是否包含 `"CONCRETE"`，无需硬编码 16 种颜色名（直接回应 Issue 场景）。

```yaml
logic:
  functions:
    count_concrete:
      parameters: [items]            # items 为材质名列表
      steps:
        - set: {target: count, value: 0}
        - for_each:
            target: items
            var: mat
            do:
              - eval:
                  target: is_conc
                  expression: '"{mat}" contains "CONCRETE"'
              - if:
                  condition: "{is_conc} == 1"
                  then:
                    - eval: {target: count, expression: "{count} + 1"}
        - return: "{count}"
```

也可用 `matches` 做正则模式匹配（如以 `_CONCRETE` 结尾）：

```yaml
- eval: {target: ok, expression: '"{mat}" matches ".*_CONCRETE"'}
```

### 示例 7：三元运算符 `? :` 简化条件赋值

一行表达式替代多行 if-else（右结合，可嵌套）。

```yaml
logic:
  functions:
    grade:
      parameters: [score]
      steps:
        - eval: {target: level, expression: '{score} >= 90 ? 3 : ({score} >= 60 ? 2 : 1)'}
        - eval: {target: msg, expression: '{score} >= 60 ? "及格" : "不及格"'}
        - return: "{level}"
```

等价的函数形式 `if(cond, a, b)`：

```yaml
- eval: {target: level, expression: "if({score} >= 60, 2, 1)"}
```

### 示例 8：`for_each` 遍历映射

直接遍历映射的键值对，无需 `map_keys` + `map_get` 手动取键。

```yaml
logic:
  functions:
    sum_scores:
      steps:
        - map_create: {target: scores}
        - map_put: {target: scores, key: "a", value: 10}
        - map_put: {target: scores, key: "b", value: 25}
        - set: {target: total, value: 0}
        - for_each:
            target: scores
            var: val
            key_var: k
            do:
              - log: {level: INFO, message: "{k} = {val}"}
              - eval: {target: total, expression: "{total} + {val}"}
        - return: "{total}"
```

### 示例 9：每日限额（`date_diff` + 持久化）

用 `now()` 记录领取时间戳，`date_diff(..., "days")` 判断是否已跨天，实现"每天只能领一次"。

```yaml
rewards:
  win:
    - trigger: "logic:can_claim_daily"
      vars:
        msg: "logic:claim_daily"
      actions:
        - type: message
          message: "{msg}"

logic:
  functions:
    can_claim_daily:
      steps:
        - get_player: -> p
        - store_get: {key: "daily.{p}"} -> last   # 上次领取时间戳；未领过为 ""
        - eval: {target: last, expression: "to_number({last})"}      # 空串转 0
        - eval: {target: now_ts, expression: "now()"}
        - eval: {target: diff_days, expression: 'date_diff({last}, {now_ts}, "days")'}
        # 未领过（last=0 → 1970，距今很多天）或已跨天 → 允许领取
        - eval: {target: ok, expression: '{diff_days} >= 1 ? 1 : 0'}
        - return: "{ok}"

    claim_daily:
      steps:
        - get_player: -> p
        - eval: {target: now_ts, expression: "now()"}
        - store_set: {key: "daily.{p}", value: "{now_ts}"}
        - return: "&a今日奖励已发放，明天再来！"
```

> 冷却时间（如 1 小时内不可重复）把单位换成 `date_diff(t1, t2, "minutes")` 再与阈值比较即可。

---

## 9. 常见问题排查

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| 重载后报 `未定义的变量: global.xxx` | 你误把 `global.` 当必填常量，但该全局变量尚未被 `set`。未定义的 `global.` 读取返回空串 `""`（这是设计行为，用于"首次初始化"判断） | 用 `if: "{global.xxx} != 1"` 模式做首次初始化，不要假设它已存在 |
| 报错 `表达式执行超时（>5ms）` | 真实服务器主线程负载高 | 调大 `logic.max_eval_ms`（默认 50），重载即可 |
| 动作不执行 | 该流程有 `trigger`，且函数返回了假值（0/空/false） | 检查 `trigger` 函数的 `return` 值 |
| `{logic:func}` 占位符出现多次导致逻辑重复 | 使用了弃用语法 | 改用 `vars:` 绑定（见 4.3） |
| 箱子为空 | `give_chest` 指向的函数返回值不是列表/映射/字符串 | 确保函数 `return` 的是如 `["DIAMOND:3", ...]` 的列表（见 4.4） |
| 金币未发放 | 未安装 Vault 或玩家经济插件 | `eco_*` 依赖 Vault；未安装时静默跳过 |
| 重载报 `缺少输出变量 (out)` | 静态检查对 `get_*`/`list_get`/`map_*` 要求 `out:`，对 `random_int`/`check_permission` 等要求 `target:` | 按 5.4 各步骤的参数写法补上 `out:` 或 `target:` |
| 报错 `无法识别的标识符: 包含`（或其它中文关键字） | 用了中文关键字 | 改用英文小写关键字：`contains`/`starts_with`/`ends_with`/`matches`/`in`，不要写 `包含`/`开始于` 等 |
| 需要遍历映射的键值对 | 用 `while`+`map_keys`+`map_get` 太繁琐 | 改用 `for_each` 步骤，配 `key_var`/`var`（见 5.4） |
| 想做每日限额 / 冷却时间 | 没有时间函数 | 用 `now()` 记录时间戳、`date_diff(t1,t2,unit)` 判断间隔（见示例 9） |

---

> 本文档对应插件内置 `config.yml` 的语法与行为。修改配置后执行 `/sweeper reload` 即可生效，无需重启服务器或重新编译。
