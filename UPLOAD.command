#!/usr/bin/env bash
# MaterialFiles (Sora-Editor) 一键上传脚本 (macOS / Linux)
# macOS 可直接双击本文件运行；Linux 终端里执行 bash 一键上传.command
set -u
cd "$(cd "$(dirname "$0")" && pwd)"

echo "============================================"
echo "  MaterialFiles (Sora-Editor)  一键上传脚本"
echo "  上传到 GitHub 后会自动用 Actions 编译 APK"
echo "============================================"
echo

# ---------- 1. 检查 git ----------
if ! command -v git >/dev/null 2>&1; then
  echo "[错误] 没检测到 Git，请先安装 Git 后重试。"
  echo "       macOS: 终端执行  xcode-select --install"
  echo "       Linux: 例如  sudo apt install git"
  read -r -p "按回车退出..." _
  exit 1
fi

# ---------- 2. 初始化本地仓库 ----------
echo "[1/4] 准备本地仓库..."
[ -d .git ] || git init >/dev/null
git add -A
git commit -m "MaterialFiles Sora-Editor + APK signing + one-click build" >/dev/null 2>&1 || true
git rev-parse HEAD >/dev/null 2>&1 || git commit --allow-empty -m "init" >/dev/null
git branch -M main >/dev/null 2>&1 || true

# ---------- 3. 优先使用 GitHub CLI ----------
if command -v gh >/dev/null 2>&1; then
  echo "[2/4] 检测到 GitHub CLI，检查登录状态..."
  if ! gh auth status >/dev/null 2>&1; then
    echo "      还没登录 GitHub，现在开始登录（按提示在浏览器里授权）..."
    gh auth login || { echo "[提示] 登录未完成，改用手动方式。"; USE_MANUAL=1; }
  fi
  if [ "${USE_MANUAL:-0}" != "1" ]; then
    echo
    read -r -p "请输入要新建的仓库名（直接回车用默认名 MaterialFiles-Sora-Editor）: " REPO
    [ -z "${REPO}" ] && REPO="MaterialFiles-Sora-Editor"
    VIS="--private"
    read -r -p "仓库设为公开吗？公开输 y，私有直接回车: " CHOICE
    case "${CHOICE:-}" in y|Y) VIS="--public" ;; esac
    echo
    echo "[3/4] 正在创建仓库并推送..."
    if gh repo create "${REPO}" ${VIS} --source=. --remote=origin --push; then
      DONE=1
    else
      echo "[提示] gh 创建/推送失败，改用手动方式。"
      USE_MANUAL=1
    fi
  fi
else
  USE_MANUAL=1
fi

# ---------- 手动方式 ----------
if [ "${DONE:-0}" != "1" ]; then
  echo
  echo "[2/4] 请先到 GitHub 网页手动新建一个【空】仓库"
  echo "      （不要勾选 README / .gitignore / license），然后复制它的地址。"
  echo
  read -r -p "粘贴仓库地址（形如 https://github.com/你的用户名/仓库名.git ）: " URL
  if [ -z "${URL}" ]; then
    echo "[错误] 没有输入地址，已取消。"
    read -r -p "按回车退出..." _
    exit 1
  fi
  git remote remove origin >/dev/null 2>&1 || true
  git remote add origin "${URL}"
  echo
  echo "[3/4] 正在推送（首次可能弹出浏览器让你登录 GitHub 授权）..."
  if ! git push -u origin main; then
    echo "[错误] 推送失败。常见原因：地址写错、仓库不是空的、或没完成登录授权。"
    read -r -p "按回车退出..." _
    exit 1
  fi
fi

echo
echo "============================================"
echo "  [4/4] 完成！代码已上传。"
echo "  接下来：打开你的 GitHub 仓库 → Actions 标签页"
echo "  等「一键编译 Debug APK」跑完（约 5-10 分钟，绿色对勾）"
echo "  进入那次运行 → 最底部 Artifacts 下载 APK。"
echo "============================================"
read -r -p "按回车退出..." _
