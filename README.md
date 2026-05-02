# ChatImage — Send photos in Minecraft chat

> **Send and view images directly in Minecraft chat.**  
> No commands, no special codes — just drag & drop or paste from clipboard.

---

## ✨ Features

- 📷 **Send photos** via drag & drop, file picker or `Ctrl+V` from clipboard
- 🖼 **View images** inline in chat — click to open full screen
- ⚡ **Instant placeholder** appears immediately, photo loads in background
- 🔒 **Server-side moderation** — operators can ban players from sending photos or delete any image
- 🕐 **Cooldown system** — configurable delay between photo sends
- 📐 **Scalable previews** — adjust chat photo size in Chat Settings (50%–200%)
- 🗂 **Supported formats** — PNG, JPEG, WebP, BMP, TIFF

---

## 🚀 How to use

### Sending a photo

Choose any method you prefer:

- 🖱 **Drag & drop** — drag an image file directly into the chat window
- 📁 **File picker** — click the **📷 button** in the bottom-right corner of chat
- 📋 **Clipboard** — press **`Ctrl+V`** to paste a copied image

Then add an optional caption and press **Enter** or click **✔**

### Viewing a photo
- **Left-click** on any photo in chat to open the full-screen viewer
- **Right-click** to copy the image ID (for admin deletion)

---

## 🛠 Installation

### Client (required)
1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.1**
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Install [Kotlin for Fabric](https://modrinth.com/mod/fabric-language-kotlin)
4. Drop `chatimage_Err.jar` into your `.minecraft/mods/` folder

### Server (required for photo sending)
1. Same steps as above — install the mod on the server too
2. Open **TCP port** (default `5050`) in your firewall — this port is used to transfer images
3. Port and other settings are in `config/chatimage-server.json` (auto-created on first launch)

> ⚠️ Without the server-side mod, players can still **see** photos sent by others but **cannot send** new ones.

---

## ⚙️ Server config

`config/chatimage-server.json`:

| Option | Default | Description |
|--------|---------|-------------|
| `resolution` | `720` | Max image resolution: `480`, `720`, `HD`, `2K` |
| `imagePort` | `5050` | TCP port for image transfers (open in firewall!) |
| `autoDownload` | `false` | Auto-download full image when received |
| `photoCooldownSeconds` | `5` | Cooldown between photo sends per player |

---

## 🔧 Operator commands

| Command | Description |
|---------|-------------|
| `/chatimage ban <player>` | Prevent a player from sending photos |
| `/chatimage unban <player>` | Restore photo sending for a player |
| `/chatimage delete <imageId>` | Delete a photo from all clients |

> Right-click on a photo in chat to copy its ID for deletion.

---

## 📋 Requirements

- Minecraft **1.21.1**
- [Fabric Loader](https://fabricmc.net/use/) **0.18.6+**
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Kotlin for Fabric](https://modrinth.com/mod/fabric-language-kotlin)

---

## 🗺 Roadmap

Currently available for **Fabric 1.21.1** only. More platforms are coming:

| Platform | Status |
|----------|--------|
| Fabric 1.21.11 | ✅ Available now |
| Fabric 26.1 | 🔜 Next |
| Forge / NeoForge | 📅 Planned |
| Quilt | 📅 Planned |
| **Paper plugin** | ⚡ Planned  |
| Other plugin platforms (Purpur, Spigot) | 📅 Planned |

> The Paper plugin will be a **server-only** jar — no mod loader required on the server side. Players still use the Fabric client mod.
