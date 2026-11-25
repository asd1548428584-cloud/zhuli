# zhuli（基于 LastChat / RikkaHub 的本地优先个人助理 fork）

> 本仓库的现状**不以本 README 为准**。项目状态、数据库版本、已验证/未验证边界，
> 一律看仓库根目录的 `CURRENT_STATE.md`（唯一权威状态）；技术决策看 `DECISIONS.md`，
> 下一步看 `NEXT_STEP.md` / `docs/规划/01_详细执行规划.md`。
>
> 2026-11-24 起，本 fork 在 LastChat 宿主之上新增了独立的记忆核心（结构化事实 + 来源追溯
> + 纠错）与 Agent 核心（统一模型网关 + 工具/策略分离 + 多步骤任务 + 审批恢复）。
> 控制台里的「记忆」「任务」两个页面可直接查看和纠正这些后台数据。
> 以下 LastChat 上游说明保留作宿主层文档。

# LastChat

<div align="center">
  <img src="docs/LastChat_icon.png" alt="LastChat Icon" width="128" height="128" />
  <br><br>
  <a href="https://www.buymeacoffee.com/cocolalilal"><img src="https://img.buymeacoffee.com/button-api/?text=Support%20the%20development!&slug=cocolalilal&button_colour=005143&font_colour=ffffff&font_family=Bree&outline_colour=ffffff&coffee_colour=FFDD00" alt="Buy Me A Coffee" width="220" /></a> 
  <br><br>
</div>

**LastChat** is a feature-rich AI assistant application for Android. It is a fork of [RikkaHub](https://github.com/re-ovo/RikkaHub), modified using AI agents

This project aims to provide a privacy-focused and highly personalized AI chat experience on Android

## Gallery

<table align="center" style="border: none;">
  <tr>
    <td align="center" style="border: none; width: 50%;">
      <img src="docs/1.4.7_chat.jpg" alt="Chat Interface" width="100%" />
    </td>
    <td align="center" style="border: none; width: 50%;">
      <img src="docs/1.4.3_stats.jpg" alt="Home Screen" width="100%" />
    </td>
  </tr>
  <tr>
    <td align="center" style="border: none; width: 50%;">
      <img src="docs/1.3.4_providers_use.gif" alt="providers page" width="100%" />
    </td>
    <td align="center" style="border: none; width: 50%;">
      <img src="docs/1.4.3_memory.jpg" alt="Memory Settings" width="100%" />
    </td>
  </tr>
</table>

## ✨ Key Features

### Advanced AI Capabilities
* **Multi-Provider Support**: Provider presets make it easier to get up and running. There's support for custom providers too!
* **RAG Memory**: Features a RAG-based memory system. Assistants can "remember" details from past conversations using embeddings.
* **Multi-Modal Inputs**: Interact using Text, Images, Video, and Audio.

### Tools & Integrations
* **Python**: Built-in Python Engine, powered by Workspaces/PRoot-based Linux environments.
* **JavaScript**: Built-in **JavaScript Engine** (QuickJS)
* **Web Search**: Integrated web search capabilities to fetch real-time information.
* **MCP**: Support for MCP servers.

### Assistant Management
* **Multiple Personas**: Create, manage, and switch between unlimited custom assistants.
* **Tagging System**: Organize assistants with custom tags.
* **Import/Export**: Easily share or backup your assistant configurations.

### Modern & Fluid UI
* **Material You**: The app was designed with Material You 3 Expressive in mind.
* **Rich Rendering**: Markdown support with LaTeX for math, code highlighting, and tables.

### Additional Modules
* **Image Generation**: Dedicated interface for generating images using supported models.
* **Text-to-Speech (TTS)**: Supports system TTS or other providers.

### Privacy & Data
* **Local-First**: Chat history and vector memory are stored locally on your device.
* **WebDAV Backup**: Securely sync and backup your data to any WebDAV-compatible server.

## Built With
* **Kotlin** & **Jetpack Compose**
* **Koin** for Dependency Injection
* **Room** & **DataStore** for persistence
* **WorkManager** & **AlarmManager** for reliable background tasks
* **QuickJS** for JavaScript integration

## Credits
* Original Project: [RikkaHub](https://github.com/re-ovo/RikkaHub)
* Image cropper is an edited version of the image editor found in [LavenderPhotos](https://github.com/kaii-lb/LavenderPhotos)
* Made with various **AI Agents**

##
*Note: This project is a fork and may contain modifications or features not present in the original RikkaHub repository.*
