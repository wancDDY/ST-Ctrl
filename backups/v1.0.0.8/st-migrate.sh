#!/data/data/com.termux/files/usr/bin/bash
#===========================================================================
# ST-Ctrl 数据迁移工具 — Termux → ST-Ctrl
#===========================================================================

set -euo pipefail
trap 'rm -f "$0"' EXIT

# ── Argument parsing (for ST-Ctrl RUN_COMMAND intent) ──
AUTO_YES=false
AUTO_OUTPUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --yes) AUTO_YES=true ;;
    --output) AUTO_OUTPUT="$2"; shift ;;
  esac
  shift
done

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

say()  { echo -e "${GREEN}[*]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
err()  { echo -e "${RED}[x]${NC} $1"; exit 1; }
step() { echo -e "${CYAN}  →${NC} $1"; }

echo ""
echo "  ╔══════════════════════════════════╗"
echo "  ║   ST-Ctrl 数据迁移工具          ║"
echo "  ║   Termux → ST-Ctrl              ║"
echo "  ╚══════════════════════════════════╝"
echo ""

# ── 0. 环境检查 ──
if ! command -v zip &>/dev/null; then
    say "正在安装 zip..."
    pkg install zip -y || err "安装 zip 失败，请手动执行: pkg install zip -y"
fi
say "环境就绪"

# ── 1. 查找酒馆目录 ──
ST_DIR=""
for d in \
    "$HOME/SillyTavern" \
    "$HOME/sillytavern" \
    "$HOME/st"; do
    if [ -f "$d/server.js" ] && [ -d "$d/data" ]; then
        ST_DIR="$d"
        break
    fi
done

if [ -z "$ST_DIR" ]; then
    warn "未自动检测到酒馆目录"
    echo -n "请手动输入酒馆完整路径: "
    read -r ST_DIR
    if [ ! -f "$ST_DIR/server.js" ] || [ ! -d "$ST_DIR/data" ]; then
        err "该路径不是有效的酒馆目录（需要包含 server.js 和 data/）"
    fi
fi
say "酒馆目录: $ST_DIR"

# ── 2. 设置输出路径（与 ST-Ctrl 创建备份同一目录）───
OUT_DIR="$HOME/storage/shared/Documents/TavernBackups"
if ! mkdir -p "$OUT_DIR" 2>/dev/null; then
    OUT_DIR="$HOME/storage/shared/Download"
    mkdir -p "$OUT_DIR" 2>/dev/null || OUT_DIR="$HOME"
fi

echo ""
TS=$(date +"%Y-%m-%d_%H-%M-%S")
if [ -n "$AUTO_OUTPUT" ]; then
  ZIP_NAME="$AUTO_OUTPUT"
  say "输出文件名: $ZIP_NAME"
elif $AUTO_YES; then
  ZIP_NAME="TavernBackup_${TS}_termux.zip"
  say "自动命名: $ZIP_NAME"
else
  echo -n "请输入备份文件名（留空则自动命名）: "
  read -r custom_name
  if [ -z "$custom_name" ]; then
    ZIP_NAME="TavernBackup_${TS}_termux.zip"
  else
    case "$custom_name" in
      *.zip) ZIP_NAME="$custom_name" ;;
      *) ZIP_NAME="$custom_name.zip" ;;
    esac
    say "将使用自定义文件名: $ZIP_NAME"
  fi
fi
OUTPUT="$OUT_DIR/$ZIP_NAME"

# ── 3. 检查源数据 ──
DATA_DIR="$ST_DIR/data"
CONFIG_FILE="$ST_DIR/config.yaml"
EXT_DIR="$ST_DIR/public/scripts/extensions/third-party"
THEMES_DIR="$ST_DIR/public/themes"
AVATARS_DIR="$ST_DIR/public/User Avatars"

if [ ! -d "$DATA_DIR" ]; then
    err "未找到 data/ 目录，无法迁移"
fi

data_count=$(find "$DATA_DIR" -type f 2>/dev/null | wc -l)
[ "$data_count" -gt 0 ] || warn "data/ 目录为空"
say "用户数据: $data_count 个文件"

has_ext=false; has_conf=false; has_themes=false; has_avatars=false
[ -d "$EXT_DIR" ]    && [ "$(find "$EXT_DIR" -type f 2>/dev/null | wc -l)" -gt 0 ]    && { has_ext=true;     step "扩展: 已检测到"; }
[ -f "$CONFIG_FILE" ] && { has_conf=true;    step "config.yaml: 已检测到（API 密钥等）"; }
[ -d "$THEMES_DIR" ]  && [ "$(find "$THEMES_DIR" -type f 2>/dev/null | wc -l)" -gt 0 ]  && { has_themes=true;  step "主题: 已检测到"; }
[ -d "$AVATARS_DIR" ] && [ "$(find "$AVATARS_DIR" -type f 2>/dev/null | wc -l)" -gt 0 ] && { has_avatars=true; step "头像: 已检测到"; }

echo ""
echo "将要打包:"
echo "  - 用户数据 (聊天/角色/世界书/设置)"
$has_ext     && echo "  - 第三方扩展"
$has_conf    && echo "  - config.yaml"
$has_themes  && echo "  - UI 主题"
$has_avatars && echo "  - 用户头像"
echo ""

if $AUTO_YES; then
  say "自动模式，跳过确认"
else
  echo -n "确认继续? [Y/n] "
  read -r confirm
  if [ "$confirm" != "Y" ] && [ "$confirm" != "y" ] && [ -n "$confirm" ]; then
    say "已取消"
    exit 0
  fi
fi

# ── 4. 构建临时目录 ──
say "第1步: 复制文件到临时目录..."
TMP=$(mktemp -d)
step "临时目录: $TMP"

# data/
step "复制 data/ ..."
cp -r "$DATA_DIR" "$TMP/data/"
for skip in _cache _errors _storage _webpack; do
    rm -rf "$TMP/data/"*/"$skip" 2>/dev/null || true
done
find "$TMP/data" -name "content.log" -delete 2>/dev/null || true

# extensions/
if $has_ext; then
    step "复制扩展..."
    cp -r "$EXT_DIR" "$TMP/extensions/"
fi

# root/
mkdir -p "$TMP/root/public"
if $has_conf; then
    step "复制 config.yaml..."
    cp "$CONFIG_FILE" "$TMP/root/config.yaml"
fi
if $has_themes; then
    step "复制主题..."
    cp -r "$THEMES_DIR" "$TMP/root/public/themes/"
fi
if $has_avatars; then
    step "复制头像..."
    cp -r "$AVATARS_DIR" "$TMP/root/public/User Avatars/"
fi

TOTAL_FILES=$(find "$TMP" -type f | wc -l)
say "共 $TOTAL_FILES 个文件待打包"

# ── 5. 打包 ──
# ── 6. 生成元数据文件（ST-Ctrl 恢复时需要） ──
say "第2步: 生成元数据..."
TOTAL_SIZE=$(du -sb "$TMP" | awk '{print $1}')
NOW=$(date +"%Y-%m-%dT%H:%M:%S%z")
cat > "$TMP/backup.json" << METAEOF
{
  "version": 1,
  "timestamp": "$NOW",
  "app_version": "1.0.0",
  "core_version": "1.0.0",
  "file_count": $TOTAL_FILES,
  "total_size_bytes": $TOTAL_SIZE,
  "source": "termux"
}
METAEOF
say "元数据已生成"

# ── 7. 打包 ──
say "第3步: 打包中（文件多时会滚动，这是正常的）..."
rm -f "$OUTPUT"

cd "$TMP"
if zip -r "$OUTPUT" * ; then
    say "打包完成"
else
    err "打包失败！请确认 Termux 已安装 zip:  pkg install zip -y"
fi
cd ~

# ── 8. 验证 ──
say "第4步: 验证..."
if [ ! -f "$OUTPUT" ]; then
    err "打包失败！未生成文件。请检查 Termux 是否安装了 zip（pkg install zip）"
fi

ZIP_SIZE=$(du -h "$OUTPUT" | awk '{print $1}')
ZIP_ENTRIES=$(unzip -l "$OUTPUT" 2>/dev/null | tail -1 | awk '{print $2}')
if [ -z "$ZIP_ENTRIES" ]; then
    # 兼容不同 unzip 输出格式
    ZIP_ENTRIES=$(unzip -l "$OUTPUT" 2>/dev/null | grep -c ":" || echo "?")
fi

# 清理
rm -rf "$TMP"

# 触发 Android 媒体扫描，让 ST-Ctrl 能跨应用看到此文件
am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
  -d "file://$OUTPUT" >/dev/null 2>&1 || true

# ── 7. 输出结果 ──
echo ""
echo "  ╔══════════════════════════════════════════════╗"
echo "  ║                                              ║"
echo "  ║         迁移包已生成                          ║"
echo "  ║                                              ║"
echo "  ║   文件名: $ZIP_NAME                        ║"
echo "  ║                                              ║"
echo "  ║   文件大小: $ZIP_SIZE                            ║"
echo "  ║   文件数:   $ZIP_ENTRIES                              ║"
echo "  ║                                              ║"
echo "  ╚══════════════════════════════════════════════╝"
echo ""
echo "  ┌─────────────────────────────────────────────┐"
echo "  │                                             │"
echo "  │  下一步:                                     │"
echo "  │  1. 打开 ST-Ctrl                            │"
echo "  │  2. 控制台 → 备份与恢复 → 还原备份            │"
echo "  │  3. 文件位于 Documents/TavernBackups/ 目录，用「选择文件」导入                           │"
echo "  │  4. 点击恢复，完成后重启酒馆               │"
echo "  │                                             │"
echo "  └─────────────────────────────────────────────┘"
echo ""
