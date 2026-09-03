# 📦 Re-MaterialFiles

[![Sora-Editor版 状态](https://img.shields.io/github/actions/workflow/status/Sulee7z/Re-MaterialFiles/Sora-Editor.yml?branch=Sora-Editor_1.7&style=for-the-badge&label=Sora-Editor%E7%89%88%20%E7%8A%B6%E6%80%81)](https://github.com/Sulee7z/Re-MaterialFiles/actions/workflows/Sora-Editor.yml)
[![GitHub 发行版](https://img.shields.io/github/v/release/Sulee7z/Re-MaterialFiles?include_prereleases&display_name=release&style=for-the-badge)](https://github.com/Sulee7z/Re-MaterialFiles/releases)
[![许可证](https://img.shields.io/github/license/Sulee7z/Re-MaterialFiles?color=blue&style=for-the-badge)](LICENSE)
[![支持 Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-green?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/studio)

> ### 🚀 文件管理器 × APK 逆向分析工具箱
>
> 在 [Material Files](https://github.com/zhanghai/MaterialFiles)（Hai Zhang）与
> [Sora-Editor 分支](https://github.com/Citrinae-Lime/MaterialFiles.Sora-Editor)（Citrinae-Lime）的基础上继续演进：
> 融合 **MT 管理器风格的逆向工具、内置终端、Everything 搜索**，并加入 **双栏浏览、全局拖拽、回收站、文档提供器** 等大量重构与增强。

---

## ✨ 本分支亮点（相对上游）

| 领域 | 能力 |
| --- | --- |
| 🗂️ **双栏浏览** | 设置 → 行为 → "双栏浏览"：并排两个独立文件列表，各自导航；可拖分隔条调宽（25%~75%）；右缘共享快速滚动条（点按即跳、跟随活动栏）；F5/F6 一键复制/移动到对面栏；跨栏拖拽移动 |
| 🖱️ **全局拖拽** | 拖动文件 → 文件夹行 / 面包屑路径段 / 对面栏 / 底部删除栏；列表边缘自动滚动；拖动中目标高亮 |
| 🗑️ **回收站** | 删除进回收站 + Snackbar 撤销；移动后同样可撤销（远程文件系统为永久删除） |
| 📤 **文档提供器** | 本应用作为根出现在系统文件选择器侧边栏；root/Shizuku 模式下其他应用可借它访问 `/data` 等受限目录 |
| 🕵️ **APK 逆向** | DEX 解析 / Smali 导出 / 引用跳转 / 字符串搜索 / ELF 分析 / Manifest 解码 / Hex 编辑 / APK 对比 |
| ✍️ **APK 签名** | 一键签名（v1+v2+v3）· 自定义密钥库 · 带版本号重命名 · 直接安装 |
| 🖥️ **终端** | Termux 内核 · PTY 支持 · Root 会话 · 附加按键行 |
| 🔍 **超级搜索** | SQLite 文件名索引 + Everything HTTP/ETP 语法搜索 · 内容正则搜索 · Root/Shizuku 索引 |
| 🗜️ **压缩增强** | 解压目标编辑 / 解压到此处 / tar.gz 创建 / 嵌套归档原位打开与编辑保存 |
| 🧭 **文件打开** | 无后缀/未知扩展名按文件头嗅探自动路由（ELF/DEX→分析器、图片→看图器、文本→编辑器） |

---

## 🗂️ 双栏模式细节

- **独立双列表**：横竖屏均可双栏并排，各自独立面包屑、排序、滚动。
- **活动栏跟随**：触摸哪一栏哪一栏为活动栏（高亮标识）；抽屉、FAB、搜索、菜单均作用于活动栏。
- **分隔条**：拖动调宽 25%~75%；打开导航抽屉时自动禁用防误触。
- **文件夹大小**：后台统计并缓存文件夹内容总大小，权限授予后自动补算。
- **滚动位置记忆**：刷新、切换设置后保持原滚动位置；长文件名跑马灯滚动。
- **键盘**：F5 复制到对面 / F6 移动到对面；返回键仅作用于活动栏。

---

## 🛠️ 逆向工具箱（长按文件 → 更多操作）

### 🕵️ APK 分析

- **DEX 分析器**：类 / 方法 / 字段 / 指令级解析，**导出 Smali**、正则搜索、**查找引用跳转**。
- **APK 字符串搜索**：在 DEX 与 .so 中批量搜索。
- **ELF 分析器**：.so 的 ELF 头、程序头、节区与字符串表。
- **十六进制查看器 / 编辑器**：任意文件 Hex 查看与编辑。
- **AndroidManifest 解码**：AXML → 可读文本（版本、minSdk/targetSdk、组件等）。
- **APK 对比**：比较两个 APK 的签名信息。

### ✍️ 签名与安装

- **一键签名**：内置密钥，v1+v2+v3，无需配置。
- **自定义签名**：选择密钥库与密码；结果可通过 `apksigner verify`。
- **带版本号重命名**：读取 APK 版本名，重命名为 `名称_版本号.apk`。
- **安装 APK**：直接调用系统安装器。

### 🏖️ 终端与系统

- **内置终端**：Termux PTY，当前目录直达，支持 Root 会话与 ESC/TAB/CTRL/ALT/方向键附加按键行。
- **Logcat 查看器** · **Activity 启动器** · **显示/隐藏文件管理**。

---

## 🔍 搜索与索引

- **SQLite 文件名索引**：启动后台自动构建，海量目录毫秒检索；`⋮` 菜单可手动重建。
- **Everything 语法**：空格=且、`|`=或、`!`=排除、`"..."`=精确、`file:`/`folder:`/`doc:`/`pic:`/`video:`/`zip:` 类型筛选、`size:>10mb`/`size:1mb..50mb` 大小、`dm:thisyear`/`dm:2026-08-01..2026-08-09` 日期、`/路径 关键词` 限定目录。
- **Everything 服务器**：浏览/搜索/下载全走 HTTP API，无需 ETP/FTP。
- **Root/Shizuku 索引**：自动包含 `/data/app`、`/data/user/0` 与根目录，`/ 关键词` 搜索受限文件。
- **内容搜索**：当前目录递归搜索文本内容，支持取消。

---

## 🏆 原版 Material Files 特性

- 🌿 **开源**：轻量、简洁并且安全。
- 🎨 **Material Design**：遵循规范，注重细节。
- 🧭 **面包屑导航**：点击路径段快速跳转（本分支还可拖放文件到路径段移动）。
- 👑 **Root 支持** · 🗄️ **FTP/SFTP/SMB/WebDAV**（含 SMB 加密协商、SFTP 主机密钥验证）。
- 📦 **压缩文件**：查看、提取和创建常见格式。
- 🌗 **主题**：可定制配色，纯黑夜间模式。
- 🐧 **Linux 友好**：符号链接、权限、SELinux 上下文；基于系统调用而非 `ls` 解析器。

---

## 📸 预览

<p>
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="32%" />
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="32%" />
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="32%" /><br/>
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="32%" />
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="32%" />
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.png" width="32%" />
</p>

---

## ⬇️ 下载安装

**点击下图前往 Release 下载最新版 APK**（GitHub Actions 自动构建，已签名）：

[<img alt="下载应用，请到 GitHub" src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png" width="240">](https://github.com/Sulee7z/Re-MaterialFiles/releases/latest/)

---

## 💖 鸣谢

- [Hai Zhang](https://github.com/zhanghai) —— 原版 **Material Files** 作者。
- [Citrinae-Lime](https://github.com/Citrinae-Lime) —— **Sora-Editor 分支**作者。
- [Termux](https://github.com/termux) —— 终端模拟器内核与 Terminal View 组件。

---

## 🔧 在定制 ROM 中集成

- 请勿用本应用替换 AOSP [DocumentsUI](https://android.googlesource.com/platform/packages/apps/DocumentsUI/)——它依赖 DocumentsUI授予外置 SD 卡访问权限。
- 请确保应用可被卸载或禁用。
- 修改重签请复刻本项目并更改软件包名，避免与原版冲突。

---

## 📄 许可证

    Copyright (C) 2024 Hai Zhang

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
