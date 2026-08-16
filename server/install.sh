#!/usr/bin/env bash
# work-log-app サーバー セットアップスクリプト
#
# スマホと同じLAN上にあるPCでこのディレクトリ(server/)を実行してください。
# Python仮想環境の構築、依存ライブラリのインストール、systemdサービスとしての
# 常駐起動(電源断からの再起動にも対応)までを行います。
#
# 使い方:
#   sudo bash install.sh            # インストール / 再インストール
#   sudo bash install.sh --uninstall  # アンインストール

set -euo pipefail

# ── 設定 ──────────────────────────────────────────────
SERVICE_NAME="work-log-app"
APP_HOST="0.0.0.0"
APP_PORT="8090"
# ────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
VENV_DIR="${SCRIPT_DIR}/.venv"
VENV_PYTHON="${VENV_DIR}/bin/python"

# ─── アンインストールモード ────────────────────────────────────────────────
if [[ "${1:-}" == "--uninstall" ]]; then
    if [[ "$EUID" -ne 0 ]]; then
        echo "[エラー] sudo で実行してください: sudo bash $0 --uninstall"
        exit 1
    fi

    echo "サービス ${SERVICE_NAME} をアンインストールします..."

    if systemctl is-active --quiet "${SERVICE_NAME}" 2>/dev/null; then
        systemctl stop "${SERVICE_NAME}"
        echo "  サービスを停止しました。"
    fi

    if systemctl is-enabled --quiet "${SERVICE_NAME}" 2>/dev/null; then
        systemctl disable "${SERVICE_NAME}"
        echo "  サービスを無効化しました。"
    fi

    if [[ -f "${SERVICE_FILE}" ]]; then
        rm -f "${SERVICE_FILE}"
        echo "  ユニットファイルを削除しました: ${SERVICE_FILE}"
    fi

    systemctl daemon-reload
    echo ""
    echo "✓ アンインストール完了。(venv や DBファイルは残しています。削除する場合は"
    echo "  ${SCRIPT_DIR}/.venv と ${SCRIPT_DIR}/data を手動で削除してください)"
    exit 0
fi

# ─── インストールモード ────────────────────────────────────────────────────
echo "========================================"
echo " work-log-app サーバー セットアップ"
echo "========================================"
echo ""

# [1/5] 前提チェック
echo "[1/5] 前提を確認中..."
if ! command -v python3 &>/dev/null; then
    echo "[エラー] python3 が見つかりません。インストールしてください。"
    exit 1
fi

if [[ "$EUID" -ne 0 ]]; then
    echo "[エラー] sudo で実行してください: sudo bash $0"
    exit 1
fi

RUN_USER="${SUDO_USER:-$(logname 2>/dev/null || echo root)}"
echo "  ✓ python3: $(python3 --version)"
echo "  ✓ 実行ユーザー: ${RUN_USER}"
echo ""

# [2/5] Python仮想環境の構築・依存インストール
echo "[2/5] Python仮想環境を準備中..."
if [[ ! -x "${VENV_PYTHON}" ]]; then
    echo "  venv を作成しています..."
    if ! sudo -u "${RUN_USER}" python3 -m venv "${VENV_DIR}"; then
        echo "[エラー] venv の作成に失敗しました。python3-venv パッケージが必要な場合があります:"
        echo "    sudo apt install python3-venv"
        exit 1
    fi
    echo "  ✓ venv を作成しました。"
else
    echo "  venv はすでに存在します。スキップします。"
fi

echo "  依存ライブラリをインストール中..."
sudo -u "${RUN_USER}" "${VENV_PYTHON}" -m pip install --quiet --upgrade pip
sudo -u "${RUN_USER}" "${VENV_PYTHON}" -m pip install --quiet -r "${SCRIPT_DIR}/requirements.txt"
echo "  ✓ 依存ライブラリをインストールしました。"
echo ""

# [3/5] ポートの空き確認
echo "[3/5] ポート ${APP_PORT} の空き状況を確認中..."
if command -v ss &>/dev/null && ss -tln | awk '{print $4}' | grep -q ":${APP_PORT}\$"; then
    echo "[エラー] ポート ${APP_PORT} はすでに他のプロセスが使用しています。"
    echo "  このマシンで使用中のポートを確認し、このスクリプト冒頭の APP_PORT を"
    echo "  空いている値に変更してから再実行してください。"
    echo "    確認コマンド: ss -tlnp"
    exit 1
fi
echo "  ✓ ポート ${APP_PORT} は空いています。"
echo ""

# [4/5] systemdサービスを登録
echo "[4/5] systemdサービスを登録中..."

cat > "${SERVICE_FILE}" <<EOF
[Unit]
Description=work-log-app 作業記録サーバー
After=network.target

[Service]
Type=simple
User=${RUN_USER}
WorkingDirectory=${SCRIPT_DIR}
Environment=WORKLOG_HOST=${APP_HOST}
Environment=WORKLOG_PORT=${APP_PORT}
ExecStart=${VENV_PYTHON} -m uvicorn app.main:app --host ${APP_HOST} --port ${APP_PORT}
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "${SERVICE_NAME}"
systemctl restart "${SERVICE_NAME}"

sleep 1
if ! systemctl is-active --quiet "${SERVICE_NAME}"; then
    echo "[エラー] サービスの起動に失敗しました。ログを確認してください:"
    echo "    sudo journalctl -u ${SERVICE_NAME} -n 50"
    exit 1
fi
echo "  ✓ サービスを起動しました。"
echo ""

# [5/5] ファイアウォールの確認(ufw使用時のみ、ベストエフォート)
echo "[5/5] ファイアウォールを確認中..."
if command -v ufw &>/dev/null && ufw status | grep -q "Status: active"; then
    ufw allow "${APP_PORT}/tcp" comment "work-log-app sync API / wizard" >/dev/null
    ufw allow 5353/udp comment "work-log-app mDNS discovery" >/dev/null
    echo "  ✓ ufw に ${APP_PORT}/tcp と 5353/udp(mDNS) の許可ルールを追加しました。"
else
    echo "  ufw は使用されていません(または無効)。スキップします。"
    echo "  他のファイアウォールを使っている場合は、TCP ${APP_PORT} 番と UDP 5353番"
    echo "  (mDNS)をこのマシンへの着信で許可してください。"
fi
echo ""

LAN_IP="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if ($i=="src") print $(i+1)}')"
LAN_IP="${LAN_IP:-<このPCのLAN IPアドレス>}"

echo "========================================"
echo " インストール完了"
echo "========================================"
echo "  統合ウィザード : http://${LAN_IP}:${APP_PORT}/"
echo ""
echo "  スマホのAndroidアプリは、同じWi-Fi上であれば mDNS で自動的にこのサーバー"
echo "  を発見します(発見できない場合は、家庭用ルーターの「AP分離(プライバシー"
echo "  セパレーター)」機能が有効になっていないか確認してください。有効だと同じ"
echo "  Wi-Fi上の端末同士が通信できず、mDNS発見が失敗します)。"
echo ""
echo "管理コマンド:"
echo "  状態確認 : sudo systemctl status ${SERVICE_NAME}"
echo "  ログ確認 : sudo journalctl -u ${SERVICE_NAME} -f"
echo "  停止     : sudo systemctl stop ${SERVICE_NAME}"
echo "  再起動   : sudo systemctl restart ${SERVICE_NAME}"
echo "  削除     : sudo bash $0 --uninstall"
