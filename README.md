# AuthWithQq Plugin - MC服务器QQ绑定认证插件

AuthWithQq是一款多功能的Minecraft服务器插件，旨在提供玩家QQ绑定认证。该插件通过内置的轻量级HTTP服务器，提供了一套完整的Web界面和API接口，帮助服务器管理员更高效地管理玩家和服务器状态。

### 🚀 快速开始

1. **下载插件**：前往 [Releases](https://github.com/Cc-Cece/AuthwithQQ_plugin/releases/tag/dev) 页面下载最新版本的 `AuthWithQq-xx.xx-SNAPSHOT.jar`。
2. **安装插件**：将下载的 JAR 文件放入 Minecraft 服务器根目录下的 `plugins` 文件夹中。
3. **启动服务器**：启动（或重启）服务器以生成默认配置文件。
4. **配置插件**：编辑 `plugins/AuthWithQq/config.yml`，设置您的 HTTP 端口、API Token 以及 QQ 绑定逻辑。
5. **应用配置**：在游戏内执行 `/auth reload` 指令重载配置，无需重启服务器。
6. **开始绑定**：玩家进入游戏后输入 即可看到提示，使用QQ机器人或访问 Web 页面完成认证。

### ⚙️ 兼容性

- **服务端版本**：原生支持 **Minecraft 1.20.x - 1.21.x**。(其它版本自行测试)
- **服务端核心**：完美支持 **Paper** ；兼容 **Spigot**, **Bukkit** 环境。
- **运行环境**：需要 **Java 21** 或更高版本。
- **向下兼容**：若需在 1.20 以下的旧版本（如 1.12.2 或 1.16.5）运行，可能需要降低源码中的 API 版本并重新构建（Build）。

## ✨ 主要功能

*   **QQ绑定认证**：玩家需绑定QQ才能进入游戏或解除游客限制。
*   **Web认证页面**：提供Web认证页面（可选），支持自定义字段，方便玩家通过浏览器完成绑定。
*   **实时服务器看板**：简单的Web界面实时监控服务器TPS、内存使用和在线玩家列表。
*   **轻量化管理员管理台**：Web管理界面，方便管理员查看玩家绑定信息、强制解绑、管理白名单和假人。
*   **智能验证拦截**：灵活的验证逻辑，支持白名单、OP玩家跳过认证，以及假人自动放行。
*   **多账号绑定限制**：配置单个QQ号可绑定的MC账号数量，有效防止一人多开。
*   **假人自动化支持**：兼容Fakeplayer等假人插件，自动识别NPC并免除验证。
*   **增强的指令系统**：提供 `/auth` (管理员) 和 `/bind` (玩家) 两套指令系统，覆盖绑定、管理、假人操作等全流程。
*   **开放API接口**：为外部应用或自定义脚本提供绑定、查询、管理等API接口。

## ⚙️ 配置 (`config.yml`)

插件的配置、消息提示等全部集中在 `config.yml` 中，高度自定义并可更方便地翻译。

## 🌐 Web界面使用

插件内置HTTP服务器，您可以通过浏览器访问以下地址：

*   **玩家认证页面**(可配置绑定时自动发送)：`http://[服务器IP]:[端口]/web/auth.html?uuid=xxx&name=xxx`
    *   **示例**：`http://yourserver.com:8081/web/auth.html?uuid=a1b2c3d4-e5f6-7890-1234-567890abcdef&name=Steve`
*   **简单的实时服务器看板 (Dashboard)**：`http://[服务器IP]:[端口]/dashboard` 
    *   **示例**：`http://yourserver.com:8081/dashboard`
*   **管理员管理台 (Admin Console)**：`http://[服务器IP]:[端口]/admin`
    *   **示例**：`http://yourserver.com:8081/admin`
    *   访问时会提示输入API Token，或在URL中带上 `?token=YOUR_API_TOKEN`。

## 💻 玩家指令 (`/bind`)

普通玩家可使用以下指令进行操作：

*   `/bind getcode`：获取当前的绑定验证码。
*   `/bind profile`：获取个人资料页面的链接，可用于查看绑定信息。
*   `/bind bot add <假人名>`：将一个假人绑定到自己的名下（需配置 `max-bots-per-player` > 0）。
*   `/bind bot remove <假人名>`：解绑自己名下的假人。

## 🛠️ 管理员指令 (`/auth`)

所有 `/auth` 指令都需要OP权限才能执行。

*   `/auth reload`：重载插件配置。
*   `/auth csv export`：导出所有玩家绑定数据到 `export.csv`。
*   `/auth csv import`：从 `import.csv` 导入玩家绑定数据。
*   `/auth whitelist add <玩家名>`：将玩家添加到白名单。
*   `/auth whitelist remove <玩家名>`：将玩家从白名单移除。
*   `/auth bind <玩家名> <QQ号>`：强制为指定玩家绑定QQ。
*   `/auth unbind <玩家名>`：强制解绑指定玩家。
*   `/auth bot add <所有者玩家名> <假人名>`：强制为指定玩家绑定一个假人名额。

## 🔌 API 参考

插件提供了RESTful API接口，方便与其他代码集成。

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

#### 8. `POST /api/bot/unbind` - 解绑假人

*   **描述**：解绑指定玩家名下的假人。
*   **认证**：需要 `X-API-Token`。
*   **请求体 (application/json)**：
    ```json
    {
      "owner_uuid": "a1b2c3d4-e5f6-7890-1234-567890abcdef",
      "bot_name": "MyBotName"
    }
    ```
    *   `owner_uuid`: 假人所有者的UUID（用于验证权限）。
    *   `bot_name`: 假人的名称。
*   **响应示例 (200 OK)**：
    ```json
    {
      "success": true,
      "message": "Bot MyBotName unbound successfully"
    }
    ```
*   **响应示例 (400 Bad Request)**：
    ```json
    {
      "success": false,
      "error": "Bot not found or not owned by you"
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
