# MineSweeper.Ver LogicStep 脚本语法完全参考

> 本文档描述 MineSweeper 插件**实际运行的** LogicStep 脚本引擎的全部语法、指令与函数。
> 阅读本文档即可自行编写 `.lgs` 脚本，无需查看源码或参考原始规范文档。
>
> **关于 LogicStep 语言本身**：LogicStep 是一门独立的积木式脚本语言，拥有完整的语法规范、多语言翻译机制和通用标准指令集。本引擎是基于 LogicStep 规范的 MineSweeper 领域实现（添加了游戏专用扩展指令和类型）。如果你希望了解 LogicStep 的核心语言设计、语法标准或将其应用于其他项目，请参阅 LogicStep 官方规范：
>
> [LogicStep 语言规范](https://github.com/Hespruina/LogicStep)
>
> 脚本文件存放于 `plugins/MineSweeper/scripts/`，扩展名 `.lgs`，编码 UTF-8。

---

## 目录

1. [快速上手](#快速上手)
2. [程序结构](#程序结构)
3. [注释](#注释)
4. [数据类型与字面量](#数据类型与字面量)
5. [变量与作用域](#变量与作用域)
6. [表达式](#表达式)
7. [步骤与模块](#步骤与模块)
8. [控制流](#控制流)
9. [指令参考（完整）](#指令参考完整)
10. [内置函数参考](#内置函数参考)
11. [与 config.yml 的配合](#与-configyml-的配合)
12. [安全限制](#安全限制)
13. [完整示例](#完整示例)
14. [常见错误与排查](#常见错误与排查)
15. [附录：关键字速查表](#附录关键字速查表)

---

## 快速上手

LogicStep 是一种积木式脚本语言。一个 `.lgs` 文件由若干个**模块（module）**或**步骤（step）**组成。每条语句占一行，用明确的 `end` 标记结束代码块，不依赖缩进。

```lgs
// 这是一个最简单的模块：返回金币
module jiangli
    get_difficulty -> diff
    set gold = floor(diff * 10 + 5)
    return gold
end module
```

三个核心概念：

- **指令**：`get_difficulty -> diff` — 调用引擎内置功能获取游戏难度，结果存入变量 `diff`
- **赋值**：`set gold = floor(diff * 10 + 5)` — 用 `set` 给变量赋值
- **返回**：`return gold` — 向调用方返回计算结果

配置 `config.yml` 通过 `logic:模块名` 引用模块，引擎自动执行并获取返回值。

---

## 程序结构

一个 `.lgs` 文件由零个或多个 **step** / **module** 定义组成。名称不可重复（step 与 module 也不能同名）。

### 2.1 步骤定义（step）

```lgs
step 步骤名
    语句
    ...
end step
```

步骤可带参数：

```lgs
step 步骤名(参数1, 参数2)
    语句
    ...
end step
```

- 步骤由宿主程序调用，或由脚本内 `jump.步骤名()` 跳转
- 步骤执行到末尾自然结束，也可用 `return` 提前退出
- 若脚本不含任何 step，宿主仍可直接调用 module 作为入口（本项目惯例）

### 2.2 模块定义（module）

```lgs
module 模块名
    语句
    ...
    return 表达式
end module
```

模块可带参数：

```lgs
module 模块名(参数1, 参数2)
    语句
    ...
    return 表达式
end module
```

- 模块通过 `mod.模块名(参数)` 调用，调用完毕后**返回原处继续执行**
- 模块内部必须（或隐式）通过 `return` 返回值；若未写 `return` 则隐式返回 `null`
- 模块内不可使用 `jump`

### 2.3 标识符规则

- 允许字母（含中文）、数字、下划线
- 不能以数字开头
- 不能与关键字冲突
- **注意**：关键字仅支持英文小写（`step` / `module` / `if` / `set` 等），不支持中文替代

---

## 注释

```lgs
// 单行注释：从 // 到行尾

/* 多行注释
   可跨行
   可嵌套？不可以，遇到 */ 即结束 */
```

注释在词法分析阶段即被丢弃，不影响执行。

---

## 数据类型与字面量

### 4.1 数字

整数或小数，引擎内部以双精度浮点数（`double`）存储：

```lgs
set a = 5          // 整数
set b = 3.14       // 小数
set c = -2.5       // 负数
```

### 4.2 字符串

双引号包围，支持转义：

```lgs
set s = "Hello"
set t = "换行：\n 制表：\t 引号：\" 反斜杠：\\"
```

### 4.3 布尔值

```lgs
set done = true
set fail = false
```

### 4.4 列表

方括号，逗号分隔元素：

```lgs
set colors = ["RED", "GREEN", "BLUE"]
set mixed = ["text", 42, true]   // 元素可以是任意类型
set empty = []                   // 空列表
```

### 4.5 映射（Map / 字典）

键值对集合，需通过指令创建和操作：

```lgs
map_create -> mymap                      // 创建空映射
map_put mymap, "key1", "value1"         // 写入键值对
map_put mymap, "key2", 100              // 值可以是任意类型
map_get mymap, "key1" -> val            // 读取：val = "value1"
```

> Map 是项目扩展类型，核心规范中未定义。MineSweeper 脚本大量使用它存储加权表和全局状态。

### 4.6 空值

```lgs
set x = null
```

---

## 变量与作用域

### 5.1 变量赋值

使用 `set` 关键字，无需声明类型，可随时覆盖为不同类型：

```lgs
set 变量名 = 表达式
```

```lgs
set x = 10
set x = "现在是字符串"    // 合法：动态类型
```

### 5.2 局部作用域

每个 step / module 调用时创建**独立**的局部作用域。内部定义的变量仅在该作用域可见，调用结束后销毁。参数也是局部变量。

```lgs
module foo(x, y)
    set z = x + y
    return z
end module

// 此处无法访问 z、x、y——它们属于 foo 的局部作用域
```

### 5.3 全局变量（`global.`）

前缀 `global.` 的变量跨步骤/模块共享，**同一插件运行周期内持久**（重启丢失，见持久化指令）：

```lgs
// 初始化全局标志（只执行一次）
if global.inited != 1 then
    set global.inited = 1
    map_create -> global.cache
end if
```

> 这是项目扩展。规范中"全局只读变量"的概念已被两套机制取代：
> - `global.*` —— 可读写，脚本自行管理
> - `logic.constants` —— 只读，由 `config.yml` 定义

### 5.4 只读常量（来自 config.yml）

在 `config.yml` 的 `logic.constants` 段定义的键值对，脚本中通过 `global.常量名` **只读**访问：

```yaml
# config.yml
logic:
  constants:
    base_eco: 100
```

```lgs
// 脚本中可读
set bonus = global.base_eco * 2

// 试图写入会被忽略并产生告警（不会报错）
set global.base_eco = 999   // ⚠️ 告警：忽略对只读常量的赋值
```

### 5.5 箭头捕获输出（`->`）

许多指令和模块调用会产生返回值，通过 `-> 变量名` 捕获：

```lgs
get_difficulty -> diff               // diff = 游戏难度 (1/2/3)
mod.计算金币(难度, 大小) -> gold      // gold = 模块返回值
random_int 1, 100 -> num             // num = 随机数
```

不写 `-> 变量` 时返回值被丢弃。

---

## 表达式

### 6.1 运算符优先级（从低到高）

| 优先级 | 运算符 | 含义 |
|--------|--------|------|
| 1（最低） | `or` | 逻辑或（短路求值） |
| 2 | `and` | 逻辑与（短路求值） |
| 3 | `==` `!=` | 等于 / 不等于 |
| 4 | `<` `>` `<=` `>=` | 大小比较 |
| 5 | `+` `-` | 加 / 减 |
| 6 | `*` `/` | 乘 / 除 |
| 7 | `!` `-` | 逻辑非 / 负号（一元） |
| 8（最高） | `()` | 括号分组 |

- 字符串拼接用 `+`：`"Hello " + "World"` → `"Hello World"`
- 数字 + 字符串时，数字自动转字符串：`"值：" + 42` → `"值：42"`
- 比较运算符可用于数字（按数值）或字符串（按字典序）
- 布尔值在条件中：`true` 为真，`false` 为假；数字 `0` 等价假，非零等价真；空字符串 `""` 等价假，非空等价真；`null` 等价假
- 条件判断中可省略 `== true`：`if has_perm then` 等效 `if has_perm == true then`

### 6.2 表达式中的函数调用

所有[内置函数](#内置函数参考)都可以在表达式中使用 `函数名(参数, ...)` 形式：

```lgs
set gold = floor(raw * 0.8)               // floor 取整
set val = max(0, x - 10)                  // max 取较大值
set has = contains(material, "CONCRETE")  // contains 字符串包含判断
set parts = split(entry, ":")             // split 拆分字符串
```

函数调用可出现在表达式树的任意位置：

```lgs
set result = floor(max(0, x) * 1.5) + ceil(y / 2)
```

### 6.3 类型自动转换

| 场景 | 行为 |
|------|------|
| `数字 + 字符串` | 数字 → 字符串，���接 |
| `字符串 + 数字` | 数字 → 字符串，拼接 |
| `数字 + 数字` | 正常数值加法 |
| `字符串 + 字符串` | 拼接 |
| 算术 (`-` `*` `/`) 出现非数字 | 尝试转为数字，失败抛运行时错误 |
| 比较中出现数字和字符串 | 先尝试数值比较，失败则字典序 |
| `if` 条件不是布尔值 | 按 §6.1 的真值规则转换 |
| `==` / `!=` 中一边为数字 | 两边均尝试转为数字比较 |

---

## 步骤与模块

### 7.1 调用模块：`mod.模块名(参数)`

```lgs
// 无参数、无输出捕获
mod.random_concrete

// 有参数、捕获输出
mod.weighted_item_list(level) -> concrete_items

// 无参数、捕获输出
mod.random_concrete -> rand_mat
```

- `mod.` 后接模块名，参数放在 `( )` 内，逗号分隔
- 无参数时可省略括号
- 模块执行完毕后返回值赋给 `->` 后变量，继续执行调用处的下一条语句
- 支持递归调用（深度上限 50 层）

### 7.2 跳转步骤：`jump.步骤名(参数)`

```lgs
jump.另一个步骤(参数1, 参数2)
```

- **当前步骤终止**，跳转到目标步骤继续执行
- 跳转**不返回**——原步骤的剩余代码永不执行
- 模块内不可使用 `jump`

### 7.3 返回值：`return`

```lgs
return 表达式      // 返回表达式的值
return             // 返回 null（等效 return null）
```

- 模块通过 `return` 向调用方返回值
- 模块执行到 `end module` 前若未遇到 `return`，隐式返回 `null`
- 步骤也可以使用 `return`（宿主程序调用步骤时获取返回值，步骤自然结束则返回空串 `""`）

---

## 控制流

### 8.1 条件判断：`if / else if / else / end if`

```lgs
if 条件1 then
    语句
    ...
else if 条件2 then
    语句
    ...
else
    语句
    ...
end if
```

- `else if` 可有多条（含零条）
- `else` 可选
- 条件可以是任意表达式，按真值规则判断

示例：

```lgs
if diff == 1 then
    set base = 6
else if diff == 2 then
    set base = 12
else
    set base = 20
end if
```

### 8.2 while 循环：`while / do / end while`

```lgs
while 条件 do
    语句
    ...
end while
```

- 条件为真时重复执行循环体
- 每次迭代前检查条件
- 单循环最大迭代 10000 次，超过抛运行时错误

### 8.3 repeat-until 循环：`repeat / until`

```lgs
repeat
    语句
    ...
until 条件
```

- **循环体至少执行一次**，直到条件为真时退出
- 每次迭代**后**检查条件
- 单循环最大迭代 10000 次

### 8.4 for 遍历循环：`for / in / do / end for`

```lgs
for 元素变量 in 列表表达式 do
    语句
    ...
end for
```

- `元素变量` 依次取列表中每个元素的值
- 在循环体内不能通过修改 `元素变量` 改变原列表
- 若被遍历的不是列表（`null` 或空串），视为空列表
- 若被遍历的是单个值（非列表），视为单元素列表

示例：

```lgs
set colors = ["RED", "GREEN", "BLUE"]
for c in colors do
    send_message "颜色：" + c
end for
```

### 8.5 跳出循环：`exit`

```lgs
exit while     // 跳出当前最内层 while 循环
exit for       // 跳出当前最内层 for 循环
exit repeat    // 跳出当前最内层 repeat-until 循环
```

> ⚠️ 注意：当前引擎实现中，`exit` **不校验循环类型**。`exit for` 写在 while 内也会打断 while。建议始终在对应循环体内使用对应的 exit 类型。

### 8.6 错误捕获：`try / catch / end try`

```lgs
try
    语句
    ...
catch
    语句
    ...
end try
```

- 若 `try` 块内发生运行时错误（LogicException），跳转到 `catch` 块
- 错误信息可通过 `_error` 变量在 catch 块内获取
- `catch` 执行完毕后继续执行 `end try` 之后的内容
- 若 `try` 块正常完成，跳过 `catch` 块

---

## 指令参考（完整）

指令通过**空格分隔指令名与参数，逗号分隔参数**。每条指令标注参数、输出类型与实际行为。

### 9.1 游戏上下文

#### `get_player`
- 参数：无
- 输出：`String` — 当前玩家名（无玩家时返回 `""`）

#### `get_difficulty`
- 参数：无
- 输出：`Number` — 当前游戏难度（1 / 2 / 3），无游戏时返回 `0`

#### `get_platform_size`
- 参数：无
- 输出：`Number` — 平台方块总数

#### `get_mine_count`
- 参数：无
- 输出：`Number` — 地雷数量

#### `get_auto_flag`
- 参数：无
- 输出：`Number` — `1.0`（自动标记开启）或 `0.0`（关闭）

---

### 9.2 经济

#### `give_money`
- 参数：`amount` (Number) — 金额
- 输出：无
- 说明：向当前玩家存款（依赖 Vault）

#### `eco_balance`
- 参数：无
- 输出：`Number` — 当前玩家余额

#### `eco_give`
- 参数：`amount` (Number), `playerName` (String, 可选) — 金额与目标玩家名
- 输出：无
- 说明：向指定玩家存款；省略玩家名则为当前玩家

#### `eco_take`
- 参数：`amount` (Number), `playerName` (String, 可选)
- 输出：无
- 说明：从指定玩家扣款

---

### 9.3 玩家交互

#### `send_message`
- 参数：`message` (String)
- 输出：无
- 说明：向当前玩家发送消息，支持 `&` 颜色码（如 `&6金币奖励` → 金色）

#### `check_permission`
- 参数：`permission` (String) — 权限节点
- 输出：`Boolean` — 玩家是否拥有该权限

#### `console_command`
- 参数：`command` (String)
- 输出：无
- 说明：以控制台身份执行命令

#### `player_command`
- 参数：`command` (String)
- 输出：无
- 说明：以当前玩家身份执行命令

#### `broadcast`
- 参数：`message` (String)
- 输出：无
- 说明：向全服广播消息，支持 `&` 颜色码

#### `play_sound`
- 参数：`soundName` (String) — Bukkit Sound 枚举名（如 `ENTITY_PLAYER_LEVELUP`）
- 输出：无
- 说明：向当前玩家播放音效；音效名无效时仅告警不报错

#### `give_item`
- 参数：`material` (String), `amount` (Number, 可选，默认 1)
- 输出：无
- 说明：向当前玩家背包给予物品材质（如 `DIAMOND`、`IRON_INGOT`）

---

### 9.4 随机

#### `random_int`
- 参数：`min` (Number), `max` (Number)
- 输出：`Number` — `[min, max]` 区间内随机整数（含两端）

#### `random_chance`
- 参数：`percentage` (Number) — 百分比（0-100）
- 输出：`Boolean` — 有指定概率返回 `true`

```lgs
random_chance 30 -> success       // 30% 概率 success=true
```

---

### 9.5 列表操作

#### `list_create`
- 参数：无
- 输出：`List` — 空列表

#### `list_add`
- 参数：`list` (List), `value` (Any)
- 输出：无
- 说明：向列表尾部追加元素

#### `list_get`
- 参数：`list` (List), `index` (Number)
- 输出：`Any` — 索引位置的值（从 0 开始）
- 说明：越界时返回 `""`（空串），不报错

#### `list_set`
- 参数：`list` (List), `index` (Number), `newValue` (Any)
- 输出：无
- 说明：修改指定索引的值；越界抛运行时错误

#### `list_remove`
- 参数：`list` (List), `index` (Number)
- 输出：无
- 说明：移除指定索引的元素

#### `list_size`
- 参数：`list` (List)
- 输出：`Number` — 列表长度；参数不是列表时返回 `0`

#### `list_contains`
- 参数：`list` (List), `value` (Any)
- 输出：`Boolean` — 列表是否包含该值（字符串形式比较）

```lgs
list_create -> items
list_add items, "IRON_INGOT:10"
list_add items, "GOLD_INGOT:5"
list_size items -> count              // count = 2
list_get items, 0 -> first            // first = "IRON_INGOT:10"
list_contains items, "GOLD_INGOT:5" -> has_gold  // has_gold = true
```

---

### 9.6 映射操作（Map）

#### `map_create`
- 参数：无
- 输出：`Map` — 空映射（保持插入顺序）

#### `map_put`
- 参数：`map` (Map), `key` (String), `value` (Any)
- 输出：无

#### `map_get`
- 参数：`map` (Map), `key` (String)
- 输出：`Any` — 键对应的值；键不存在或不是 Map 时返回 `""`

#### `map_remove`
- 参数：`map` (Map), `key` (String)
- 输出：无

#### `map_keys`
- 参数：`map` (Map)
- 输出：`List` — 映射所有键的列表

```lgs
map_create -> weights
map_put weights, "WHITE_CONCRETE", [20, 40, 60, 80]   // 键 → 列表
map_get weights, "WHITE_CONCRETE" -> arr               // arr = [20,40,60,80]
map_keys weights -> names                                // names = ["WHITE_CONCRETE"]
```

---

### 9.7 字符串 / 类型

#### `to_string`
- 参数：`value` (Any)
- 输出：`String`

#### `typeof`
- 参数：`value` (Any)
- 输出：`String` — `"number"` / `"string"` / `"boolean"` / `"list"` / `"map"` / `"null"` / `"unknown"`

---

### 9.8 持久化存储

持久化数据**跨服务器重启保留**，存储在 `plugins/MineSweeper/data.yml`。

#### `store_set`
- 参数：`key` (String), `value` (Any)
- 输出：无

#### `store_get`
- 参数：`key` (String)
- 输出：`Any` — 之前存储的值；键不存在返回 `null`

#### `store_remove`
- 参数：`key` (String)
- 输出：无

```lgs
// 每日签到检查
now -> today
store_get "last_sign_date" -> last_date
if date_diff(last_date, today, "days") >= 1 then
    give_money 100
    store_set "last_sign_date", today
end if
```

---

### 9.9 日志与通知

#### `log`
- 参数：`levelOrMessage` (String), `message` (String, 可选)
- 输出：无
- 说明：
  - 单参数：`log "消息内容"` → 以 INFO 级别输出到控制台
  - 双参数：`log "WARNING", "消息内容"` → 以指定级别输出
  - 支持的级别：`INFO` / `WARNING` / `SEVERE` / `FINE` 等 Java 标准级别

#### `notify_admin`
- 参数：`message` (String)
- 输出：无
- 说明：向所有拥有 `minesweeper.admin` 权限的在线玩家发送消息（同时输出到控制台）

---

## 内置函数参考

以下函数可在**表达式内**直接调用，形式为 `函数名(参数, ...)`。

### 10.1 数学函数

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `abs(x)` | Number | Number | 绝对值 |
| `floor(x)` | Number | Number | 向下取整 |
| `ceil(x)` | Number | Number | 向上取整 |
| `round(x)` | Number | Number | 四舍五入 |
| `sqrt(x)` | Number | Number | 平方根 |
| `sign(x)` | Number | Number | 符号：`-1.0` / `0.0` / `1.0` |
| `pow(x, y)` | Number, Number | Number | x 的 y 次幂 |
| `max(a, b)` | Number, Number | Number | 取较大值 |
| `min(a, b)` | Number, Number | Number | 取较小值 |
| `clamp(x, lo, hi)` | Number, Number, Number | Number | 限制 x 在 [lo, hi] 区间 |
| `mod(a, b)` | Number, Number | Number | 取模（a % b），b=0 抛错 |

### 10.2 字符串函数

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `length(s)` | String | Number | 字符串长度 |
| `upper(s)` | String | String | 全大写 |
| `lower(s)` | String | String | 全小写 |
| `trim(s)` | String | String | 去掉首尾空白 |
| `index_of(s, sub)` | String, String | Number | 子串首次出现位置（从 0 开始，未找到返回 -1） |
| `contains(s, sub)` | String, String | Boolean (1.0/0.0) | 字符串是否包含子串 |
| `starts_with(s, prefix)` | String, String | Boolean (1.0/0.0) | 是否以指定前缀开头 |
| `ends_with(s, suffix)` | String, String | Boolean (1.0/0.0) | 是否以指定后缀结尾 |
| `matches(s, regex)` | String, String | Boolean (1.0/0.0) | 是否匹配正则表达式 |
| `replace(s, old, new)` | String, String, String | String | 替换所有匹配的子串 |
| `substr(s, start)` | String, Number | String | 从 start 位置截取到末尾 |
| `substr(s, start, len)` | String, Number, Number | String | 从 start 截取 len 个字符 |
| `split(s, delim)` | String, String | List | 按分隔符拆分字符串 |
| `join(list, sep)` | List, String | String | 将列表元素用分隔符拼接 |

```lgs
set msg = "Hello World"
length(msg) -> len                    // len = 11
upper(msg) -> up                      // up = "HELLO WORLD"
contains(msg, "World") -> has        // has = true (实际值 1.0)
split("a:b:c", ":") -> parts         // parts = ["a","b","c"]
join(parts, "-") -> joined           // joined = "a-b-c"
```

> ⚠️ `contains(s, sub)` 检查的是**字符串**包含关系，而非列表成员判断。检查列表是否包含某值请用 `list_contains` 指令。

### 10.3 日期函数

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `now()` | 无 | Number | 当前时间戳（毫秒） |
| `date()` | 无 | String | 当前日期，格式 `yyyy-MM-dd` |
| `date(fmt)` | String | String | 当前日期，按指定格式 |
| `date_diff(t1, t2, unit)` | Number, Number, String | Number | t2 − t1 在指定单位下的差值 |
| `date_format(ts, fmt)` | Number, String | String | 将时间戳格式化为字符串 |
| `date_parse(s, fmt)` | String, String | Number | 将日期字符串解析为时间戳 |

`date_diff` 支持的单位（缩写）：
- `days` / `day` / `d` — 天数（按日历天截断，可跨天）
- `hours` / `hour` / `h` — 小时
- `minutes` / `minute` / `min` / `m` — 分钟
- `seconds` / `second` / `sec` / `s` — 秒
- `millis` / `milli` / `ms` — 毫秒

```lgs
now -> ts
date("yyyy-MM-dd HH:mm:ss") -> time_str     // "2026-07-30 21:54:49"

// 判断是否同一天
store_get "last_login" -> last
if date_diff(last, now(), "days") >= 1 then
    send_message "&a每日登录奖励！"
    store_set "last_login", now()
end if
```

### 10.4 类型判断函数

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `is_number(x)` | Any | Boolean (1.0/0.0) | 是否为数字（含可解析为数字的字符串） |
| `is_string(x)` | Any | Boolean (1.0/0.0) | 是否为字符串 |
| `is_list(x)` | Any | Boolean (1.0/0.0) | 是否为列表 |
| `is_map(x)` | Any | Boolean (1.0/0.0) | 是否为映射 |
| `is_bool(x)` | Any | Boolean (1.0/0.0) | 是否为布尔值 |
| `type_of(x)` | Any | String | 类型名称（等效 `typeof` 指令） |

### 10.5 类型转换函数

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `to_number(s)` | Any | Number | 转为数字，失败返回 `0.0` |
| `to_int(s)` | Any | Number | 转为整数（先 to_number 再 floor） |

### 10.6 其他实用函数

| 函数 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `if(cond, a, b)` | Any, Any, Any | Any | 三元：条件为真返回 a，否则 b |
| `pick(list)` | List | Any | 从列表中随机取一个元素 |
| `range(a, b)` | Number, Number | List | 生成 [min(a,b), max(a,b)) 的整数序列 |

```lgs
set msg = if(diff == 3, "困难模式", "简单模式")
set color = pick(["RED", "GREEN", "BLUE"])
set seq = range(0, 10)       // [0,1,2,3,4,5,6,7,8,9]
```

---

## 与 config.yml 的配合

`config.yml` 的 `rewards` 段通过 `logic:模块名` 调用 LogicStep 脚本。完整的调用流程：

```yaml
# config.yml
rewards:
  win:
    # 流程 1：金币奖励
    - trigger: "logic:has_reward_permission"     # ← 触发判定：调用模块 has_reward_permission
      vars:                                       # ← 变量预绑定（避免重复计算）
        amount: "logic:jiangli"                   #    amount = 模块 jiangli 的返回值
      actions:
        - type: console_command
          command: "eco give {player} {amount}"    #    {player} / {amount} 来自上下文和 vars
        - type: message
          message: "&6金币奖励：{amount}"

    # 流程 2：奖励箱
    - vars:
        bonus_msg: "&e额外奖励已发放"
      actions:
        - type: give_chest
          use_logic: "reward_chest_items"          # ← 调用模块填充箱子
        - type: message
          message: "&a你获得了奖励箱！{bonus_msg}"

  lose:
    - actions:
        - type: message
          message: "&c很遗憾，你输了！"

logic:
  max_steps: 50000       # 单次执行最大步数
  max_eval_ms: 50         # 表达式求值超时（毫秒）
  constants:               # 只读常量（脚本中 global.常量名 访问）
    base_eco: 100
```

**可用动作类型**（`actions[].type`）：

| type | 关键参数 | 说明 |
|------|---------|------|
| `console_command` | `command` | 控制台执行命令 |
| `player_command` | `command` | 玩家身份执行命令 |
| `message` | `message` | 向玩家发消息（支持 `&` 颜色码） |
| `broadcast` | `message` | 全服广播 |
| `sound` | `sound` | 播放音效（Bukkit Sound 枚举名） |
| `give_item` | `material`, `amount` | 给予物品 |
| `give_chest` | `use_logic` | 调用模块返回列表/映射填充箱子 |
| `delay` | `ticks` | 延迟指定 tick 后继续执行后续动作 |

**占位符**（在 `command` / `message` 等字符串中使用 `{变量名}`）：

| 占位符 | 来源 |
|--------|------|
| `{player}` | 内置：当前玩家名 |
| `{difficulty}` | 内置：难度 |
| `{platform_size}` | 内置：平台大小 |
| `{mine_count}` | 内置：地雷数 |
| `{global.xxx}` | config.yml 的 `logic.constants.xxx` |
| `{vars 中的键}` | rewards 流程的 `vars` 绑定值 |
| `{logic:模块名}` | 已弃用：直接调用模块（建议用 vars 绑定） |

---

## 安全限制

| 限制项 | 默认值 | 说明 |
|--------|--------|------|
| 单次执行最大步数 | 50000 | 在 `config.yml` 的 `logic.max_steps` 配置 |
| 递归深度上限 | 50 层 | 硬编码，超出抛错 |
| 单循环最大迭代 | 10000 次 | 硬编码，超出抛错 |
| 表达式求值超时 | 50ms | 在 `config.yml` 的 `logic.max_eval_ms` 配置 |
| 模块/步骤名唯一性 | — | 同名定义在编译期即报错 |
| 脚本沙箱 | — | 脚本无文件系统/网络访问，仅能调用引擎暴露的指令 |

---

## 完整示例

### 示例 1：金币计算

```lgs
module jiangli
    get_difficulty -> diff
    get_platform_size -> size
    get_auto_flag -> autoflag

    // 阶梯基数
    if diff == 1 then
        set base = 6
        set inc = 0.4
        set max_limit = 45
    else if diff == 2 then
        set base = 12
        set inc = 0.7
        set max_limit = 80
    else
        set base = 20
        set inc = 1.0
        set max_limit = 150
    end if

    // 额外奖励（超过 100 格的部分）
    set extra = max(0, (size - 100) * inc)

    // 原始金币 = 基数 + 额外，封顶
    set raw_gold = base + extra
    if raw_gold > max_limit then
        set raw_gold = max_limit
    end if

    // 自动插旗折扣
    if autoflag == 1 then
        set factor = diff / (diff + 1)
    else
        set factor = 1
    end if

    return floor(raw_gold * factor)
end module
```

### 示例 2：奖励箱物品生成

```lgs
module reward_chest_items
    get_difficulty -> diff
    get_platform_size -> size
    get_auto_flag -> autoflag

    // 计算混凝土权重等级
    set level = 0
    if diff == 1 then
        set level = 1
    else if diff == 2 then
        set level = 2
    else if diff == 3 then
        set level = 3
    end if

    if size > 100 then
        set level = level + 1
    end if
    if size <= 50 then
        set level = level - 1
    end if
    if autoflag == 1 then
        set level = level - 1
    end if

    // 钳位到 [0, 3]
    set level = clamp(level, 0, 3)

    // 获取混凝土列表和矿物列表
    mod.weighted_item_list(level) -> concrete_items
    mod.calc_minerals(diff, size, autoflag) -> mineral_items

    // 合并列表
    list_create -> all_items
    for item in concrete_items do
        list_add all_items, item
    end for
    for item in mineral_items do
        list_add all_items, item
    end for

    // 统计并补足混凝土
    set concrete_total = 0
    for entry in all_items do
        split entry, ":" -> parts
        list_get parts, 0 -> mat
        list_get parts, 1 -> amt_str
        to_number amt_str -> amt
        if contains(mat, "CONCRETE") then
            set concrete_total = concrete_total + amt
        end if
    end for

    if autoflag == 1 then
        set min_concrete = floor(size * 0.2)
    else
        set min_concrete = floor(size * 0.3)
    end if

    set need = max(0, min_concrete - concrete_total)
    set counter = 0
    while counter < need do
        mod.random_concrete -> mat
        list_add all_items, mat + ":1"
        set counter = counter + 1
    end while

    // 添加 TNT
    set concrete_total = max(concrete_total, min_concrete)
    set tnt_amt = floor(concrete_total / 2)
    if tnt_amt > 0 then
        list_add all_items, "TNT:" + tnt_amt
    end if

    return all_items
end module

// ---- 子模块 ----

module weighted_item_list(reward_level)
    // 首次初始化全局权重表
    if global.weights_init != 1 then
        set global.weights_init = 1
        map_create -> global.weight_map
        map_put global.weight_map, "WHITE_CONCRETE", [20, 40, 60, 80]
    end if

    list_create -> result

    map_keys global.weight_map -> mat_names
    for mat_name in mat_names do
        map_get global.weight_map, mat_name -> weight_array
        list_get weight_array, reward_level -> weight

        random_int 1, 100 -> rand
        if rand <= weight then
            if mat_name == "WHITE_CONCRETE" then
                mod.random_concrete -> mat_name
            end if
            mod.item_amount(mat_name, reward_level) -> amt
            list_add result, mat_name + ":" + amt
        end if
    end for

    return result
end module

module calc_minerals(diff, size, autoflag)
    list_create -> items
    set iron = 0
    set gold = 0
    set diamond = 0
    set netherite = 0

    if diff == 1 then
        set iron = max(0, floor(2 + 0.06 * (size - 100)))
    end if

    if diff == 2 then
        if autoflag == 0 then
            set iron = max(0, floor(5 + 0.15 * (size - 100)))
            set gold = max(0, floor(2 + 0.06 * (size - 100)))
        else
            set iron = max(0, floor(4 + 0.14 * (size - 100)))
            set gold = max(0, floor(1 + 0.02 * (size - 100)))
        end if
    end if

    if diff == 3 then
        if size >= 500 then
            if autoflag == 0 then
                set iron = floor(200 + 0.6 * (size - 500))
                set gold = floor(50 + 0.1 * (size - 500))
                set diamond = floor(15 + 0.03 * (size - 500))
                set netherite = floor(4 + 0.008 * (size - 500))
            else
                set iron = floor(200 + 0.5 * (size - 500))
                set gold = floor(32 + 0.096 * (size - 500))
                set diamond = floor(10 + 0.03 * (size - 500))
                set netherite = floor(2 + 0.008 * (size - 500))
            end if
        else if autoflag == 0 then
            set iron = max(0, floor(10 + 0.2 * (size - 100)))
            set gold = max(0, floor(5 + 0.05 * (size - 100)))
            set diamond = max(0, floor(1 + 0.02 * (size - 100)))
        else
            set iron = max(0, floor(10 + 0.1 * (size - 100)))
            set gold = max(0, floor(5 + 0.01 * (size - 100)))
            set diamond = max(0, floor(0 + 0.01 * (size - 100)))
        end if
    end if

    if iron > 0 then
        list_add items, "IRON_INGOT:" + iron
    end if
    if gold > 0 then
        list_add items, "GOLD_INGOT:" + gold
    end if
    if diamond > 0 then
        list_add items, "DIAMOND:" + diamond
    end if
    if netherite > 0 then
        list_add items, "NETHERITE_INGOT:" + netherite
    end if

    return items
end module

module random_concrete
    set colors = ["WHITE_CONCRETE", "ORANGE_CONCRETE", "MAGENTA_CONCRETE",
                  "LIGHT_BLUE_CONCRETE", "YELLOW_CONCRETE", "LIME_CONCRETE",
                  "PINK_CONCRETE", "GRAY_CONCRETE", "LIGHT_GRAY_CONCRETE",
                  "CYAN_CONCRETE", "PURPLE_CONCRETE", "BLUE_CONCRETE",
                  "BROWN_CONCRETE", "GREEN_CONCRETE", "RED_CONCRETE", "BLACK_CONCRETE"]
    list_size colors -> len
    random_int 0, len - 1 -> idx
    list_get colors, idx -> color
    return color
end module

module item_amount(material, level)
    set base = level + 1
    if contains(material, "CONCRETE") then
        random_int 1, base + 3 -> amt
    else
        set amt = 1
    end if
    return amt
end module

module has_reward_permission
    return 1
end module
```

---

## 常见错误与排查

| 错误信息 | 原因 | 解决 |
|----------|------|------|
| `字符串未闭合（缺少结束引号 "）` | 字符串缺少结尾的 `"` | 检查所有字符串是否成对出现 |
| `无法识别的字符 ';'` | 使用了分号 | **不支持分号分隔语句**，每条语句独占一行 |
| `期望 'end'` | 控制流块未闭合 | 确认 `if` → `end if`、`while` → `end while`、`for` → `end for`、`module` → `end module` 配对 |
| `重复定义: xxx` | step/module 名称冲突 | 每个模块/步骤名全局唯一 |
| `未找到模块: xxx` | `mod.xxx` 调用了不存在的模块 | 检查模块名拼写，确认定义在脚本中 |
| `除零错误` | 除法或 mod 的除数为 0 | 运算前检查分母 |
| `list_add: 第一个参数不是列表` | 把非列表变量当作列表操作 | 确认变量是否已被赋值为列表 |
| `[LogicStep] 引用未定义的局部变量: xxx` | 在赋值前就使用了变量 | 先用 `set` 或 `->` 定义变量 |
| `执行步数超过上限` | 无限循环或计算量过大 | 检查 `while` / `repeat` 的退出条件；调整 `logic.max_steps` |

---

## 附录：关键字速查表

以下单词为保留关键字，**不可用作变量名 / 模块名 / 步骤名**：

| 关键字 | 用途 |
|--------|------|
| `step` | 步骤定义开始 |
| `module` | 模块定义开始 |
| `end` | 代码块结束（配合 `step`/`module`/`if`/`while`/`for`/`try`） |
| `if` | 条件判断开始 |
| `then` | if 条件后接 |
| `else` | 否则分支 |
| `while` | while 循环 |
| `do` | while 条件后接 |
| `repeat` | repeat-until 循环 |
| `until` | repeat 结束条件 |
| `for` | for 遍历循环 |
| `in` | for 变量与列表之间 |
| `return` | 返回值 |
| `exit` | 跳出循环（配 `while`/`for`/`repeat`） |
| `set` | 变量赋值 |
| `try` | 错误捕获开始 |
| `catch` | 错误处理块 |
| `and` | 逻辑与 |
| `or` | 逻辑或 |
| `true` / `false` | 布尔字面量 |
| `null` | 空值 |
| `mod` | 模块调用前缀（`mod.模块名`） |
| `jump` | 步骤跳转前缀（`jump.步骤名`） |

> 指令名和函数名默认不属于关键字，但建议避免用作标识符。
