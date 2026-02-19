# AuthWithQq Plugin - MC服务器QQ绑定认证插件

AuthWithQq是一款功能强大的Minecraft服务器插件，旨在提供玩家QQ绑定认证、假人自动化支持、实时服务器监控以及便捷的Web管理工具。该插件通过内置的轻量级HTTP服务器，提供了一套完整的Web界面和API接口，帮助服务器管理员更高效地管理玩家和服务器状态。

## ✨ 主要功能

*   **QQ绑定认证**：玩家需绑定QQ才能进入游戏或解除游客限制。
*   **Web认证页面**：提供LP风格的Web认证页面，支持自定义字段，方便玩家通过浏览器完成绑定。
*   **实时服务器看板**：通过Web界面实时监控服务器TPS、内存使用和在线玩家列表。
*   **轻量化管理员管理台**：Web管理界面，支持API Token认证，方便管理员查看玩家绑定信息、强制解绑、管理白名单和假人，并查看插件配置。
*   **智能验证拦截**：灵活的验证逻辑，支持白名单、OP玩家跳过认证，以及假人自动放行。
*   **多账号绑定限制**：配置单个QQ号可绑定的MC账号数量，有效防止一人多开。
*   **假人自动化支持**：完美兼容Citizens等假人插件，自动识别NPC并免除验证。
*   **增强的管理员指令**：提供 `/auth` 指令扩展，支持白名单管理、强制绑定和假人绑定。
*   **开放API接口**：为外部应用或自定义脚本提供绑定、查询、管理等API接口。

## ⚙️ 配置 (`config.yml`)

插件的配置全部集中在 `config.yml` 中，提供了高度的自定义性。

```yaml
binding:
  code-length: 6 # 整数，默认 6。支持生成 4-8 位的数字验证码。
  code-expiration: 300 # 整数，单位秒，默认 300 (5分钟)。验证码的过期时间。
  custom-fields: # 自定义字段，用于前端认证页面动态生成输入框
    - name: "school"
      type: "text"
      label: "学校"
      required: true
    - name: "major"
      type: "text"
      label: "专业"
      required: false
  max-accounts-per-qq: 1 # 整数，默认 1。一个QQ号允许绑定的MC账号数量。
  max-bots-per-player: 0 # 整数，默认 0。每个真实玩家允许绑定的假人数量。

whitelist:
  players: [] # 字符串列表，玩家ID。列表中的玩家无需绑定即可进入游戏。
  bypass-ops: true # 布尔值，默认 true。OP是否自动跳过验证。

server:
  port: 8081
  token: "changeme" # !!! 重要：请务必修改此Token，用于Web API认证

guest-mode:
  allow-move: true
  allow-interact: false
  allow-world-change: false
  gamemode: "SURVIVAL"
  potion-effects:
    - type: BLINDNESS
      level: 0
  allowed-commands:
    - "/login"
    - "/register"
    - "/bind"
  allow-fake-players: false # 布尔值，默认 false。如果为 true，则假人（NPC）将自动跳过验证。

messages: # 插件消息模板
  guest-join: "&c请加入QQ群 123456 发送 /绑定 %code% 进行验证"
  success: "&a验证成功，祝你游戏愉快！"
  already-bound: "&e你已经绑定过了！"
  bind-prompt: "&6请输入 /绑定 <验证码> 以完成绑定。"
  world-change-denied: "&c未验证无法离开当前世界！"
  welcome: "&a欢迎回来, %player%!"
```

## 🌐 Web界面使用

插件内置HTTP服务器，您可以通过浏览器访问以下地址：

*   **玩家认证页面**：`http://[服务器IP]:[端口]/web/auth.html?uuid=xxx&name=xxx`
    *   **示例**：`http://yourserver.com:8081/web/auth.html?uuid=a1b2c3d4-e5f6-7890-1234-567890abcdef&name=Steve`
*   **实时服务器看板 (Dashboard)**：`http://[服务器IP]:[端口]/dashboard` 或 `http://[服务器IP]:[端口]/`
    *   **示例**：`http://yourserver.com:8081/dashboard`
*   **管理员管理台 (Admin Console)**：`http://[服务器IP]:[端口]/admin`
    *   **示例**：`http://yourserver.com:8081/admin`
    *   访问时会提示输入API Token，或在URL中带上 `?token=YOUR_API_TOKEN`。

## 🛠️ 管理员指令 (`/auth`)

所有 `/auth` 指令都需要OP权限才能执行。

*   `/auth reload`：重载插件配置。
*   `/auth csv export`：导出所有玩家绑定数据到 `export.csv`。
*   `/auth csv import`：从 `import.csv` 导入玩家绑定数据。
*   `/auth whitelist add <玩家名>`：将玩家添加到白名单。
*   `/auth whitelist remove <玩家名>`：将玩家从白名单移除。
*   `/auth bind <玩家名> <QQ号>`：强制为指定玩家绑定QQ。
*   `/auth bot add <所有者玩家名> <假人名>`：为指定玩家绑定一个假人名额。

## 🔌 API 参考

插件提供了RESTful API接口，方便与其他系统集成。所有API请求都需要在请求头中包含 `X-API-Token` 进行认证。API Token可在 `config.yml` 中配置。

### 认证头 (`X-API-Token`)

所有管理员和敏感API都需要在HTTP请求头中添加 `X-API-Token`。

*   **Header**: `X-API-Token: YOUR_CONFIGURED_TOKEN`

### API 列表

#### 1. `GET /api/meta` - 获取自定义字段信息

*   **描述**：获取 `config.yml` 中定义的自定义字段列表，用于Web认证页面动态生成表单。
*   **认证**：无需认证。
*   **响应示例 (200 OK)**：
    ```json
    [
      {
        "name": "school",
        "type": "text",
        "label": "学校",
        "required": true
      },
      {
        "name": "major",
        "type": "text",
        "label": "专业",
        "required": false
      }
    ]
    ```

#### 2. `POST /api/bind` - 玩家QQ绑定

*   **描述**：玩家通过验证码进行QQ绑定。
*   **认证**：无需认证（验证码本身起到认证作用）。
*   **请求体 (application/json)**：
    ```json
    {
      "uuid": "a1b2c3d4-e5f6-7890-1234-567890abcdef",
      "qq": 1234567890,
      "code": "123456",
      "meta": {
        "school": "清华大学",
        "major": "计算机科学"
      }
    }
    ```
    *   `uuid`: 玩家的UUID。
    *   `qq`: 玩家的QQ号码。
    *   `code`: 玩家收到的验证码。
    *   `meta`: (可选) 包含自定义字段信息的JSON对象。
*   **响应示例 (200 OK)**：
    ```json
    {
      "success": true
    }
    ```
*   **响应示例 (400 Bad Request)**：
    ```json
    {
      "success": false,
      "error": "验证码无效或已过期"
    }
    ```
    ```json
    {
      "success": false,
      "error": "此QQ号码已达到绑定上限"
    }
    ```

#### 3. `GET /api/status` - 获取服务器实时状态

*   **描述**：获取服务器的TPS、内存使用、在线玩家数量和在线玩家列表等实时信息。
*   **认证**：需要 `X-API-Token`。
*   **响应示例 (200 OK)**：
    ```json
    {
      "online_players": 10,
      "max_players": 20,
      "tps": 19.98,
      "ram_free": 1024,
      "ram_total": 2048,
      "online_player_names": ["Steve", "Alex", "Player3"]
    }
    ```

#### 4. `GET /api/players` - 获取所有已绑定玩家数据

*   **描述**：获取数据库中所有已绑定玩家的详细信息。
*   **认证**：需要 `X-API-Token`。
*   **响应示例 (200 OK)**：
    ```json
    [
      {
        "UUID": "a1b2c3d4-e5f6-7890-1234-567890abcdef",
        "Name": "Steve",
        "QQ": "1234567890",
        "Created": "1700000000000",
        "school": "清华大学",
        "major": "计算机科学"
      },
      {
        "UUID": "f1e2d3c4-b5a6-9876-5432-10fedcba9876",
        "Name": "Alex",
        "QQ": "9876543210",
        "Created": "1700000000000"
      }
    ]
    ```

#### 5. `POST /api/unbind` - 解绑玩家QQ

*   **描述**：根据玩家UUID解绑其QQ号码，并同步游戏内状态。
*   **认证**：需要 `X-API-Token`。
*   **请求体 (application/json)**：
    ```json
    {
      "uuid": "a1b2c3d4-e5f6-7890-1234-567890abcdef"
    }
    ```
    *   `uuid`: 要解绑玩家的UUID。
*   **响应示例 (200 OK)**：
    ```json
    {
      "success": true
    }
    ```

#### 6. `GET /api/config` - 获取插件配置

*   **描述**：获取插件的当前运行配置。敏感信息（如 `server.token`）会被隐藏。
*   **认证**：需要 `X-API-Token`。
*   **响应示例 (200 OK)**：
    ```json
    {
      "binding.code-length": 6,
      "binding.code-expiration": 300,
      "binding.max-accounts-per-qq": 1,
      "binding.max-bots-per-player": 0,
      "whitelist.players": [],
      "whitelist.bypass-ops": true,
      "server.port": 8081,
      "server.token": "********",
      "guest-mode.allow-move": true,
      // ... 其他配置项
    }
    ```

#### 7. `POST /api/bot/bind` - 绑定假人

*   **描述**：为指定玩家绑定一个假人名额。
*   **认证**：需要 `X-API-Token`。
*   **请求体 (application/json)**：
    ```json
    {
      "owner_uuid": "a1b2c3d4-e5f6-7890-1234-567890abcdef",
      "bot_name": "MyBotName"
    }
    ```
    *   `owner_uuid`: 假人所有者（已绑定QQ的真实玩家）的UUID。
    *   `bot_name`: 假人的名称。
*   **响应示例 (200 OK)**：
    ```json
    {
      "success": true
    }
    ```
*   **响应示例 (400 Bad Request)**：
    ```json
    {
      "success": false,
      "error": "Owner is not bound to a QQ"
    }
    ```
    ```json
    {
      "success": false,
      "error": "达到假人绑定上限"
    }
    ```

---

## 🏗️ 构建与部署

请参考项目根目录的 `gradlew` 脚本进行构建。

```bash
./gradlew build
```

构建成功后，JAR文件位于 `build/libs/` 目录下。将生成的 `AuthWithQq-X.Y-SNAPSHOT.jar` 放置到您的Minecraft服务器 `plugins` 文件夹中即可。

## 🤝 贡献

欢迎通过Pull Request或Issue的形式对插件进行改进和建议。

---

**更新这个README！ ;)**