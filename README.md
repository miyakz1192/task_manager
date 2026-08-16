# work-log-app

個人用の作業記録(ログ)アプリ。詳細仕様は [CLAUDE.md](./CLAUDE.md) を参照。

- `server/` — Python(FastAPI)によるサーバー。同期API(push/pull)とタスク統合ウィザード(Web UI)を提供
- `client-android/` — Android(Kotlin/Jetpack Compose)クライアント

## サーバーの起動

**スマホと同じLAN上にあるPCで動かす必要があります**(mDNSでの自動発見・同期はLAN内でしか届きません)。

### 常駐サービスとしてインストール(推奨)

`server/install.sh` が、Python仮想環境の構築からsystemdサービスとしての常駐起動(電源断からの自動復帰・クラッシュ時の自動再起動)まで面倒を見ます。このホスト上の他プロジェクト(arxiv_summarizer, gdelt_analyzer等)と同じ構成方式です。

```bash
# work-log-app/ 一式(このリポジトリ全体)をスマホと同じLAN上のPCにコピーしてから
cd server
sudo bash install.sh
```

- デフォルトポートは `8090`。対象PCで既に使われている場合は、`install.sh` 冒頭の `APP_PORT` を空いている値に変更してから再実行してください(スクリプトが事前にポートの空きを確認し、使用中なら止まります)
- ufwが有効な場合、必要なポート(TCPの同期API/ウィザード用ポートとUDP 5353番のmDNS)を自動で許可します
- アンインストール: `sudo bash install.sh --uninstall`
- 管理コマンド: `sudo systemctl status|restart|stop work-log-app` / ログは `sudo journalctl -u work-log-app -f`
- **スマホがmDNSでサーバーを発見できない場合**、家庭用ルーターの「AP分離(プライバシーセパレーター)」機能が有効になっていないか確認してください。これが有効だと同じWi-Fi上の端末同士が通信できず、mDNS発見もサーバーへの同期アクセスも失敗します

### 手動起動(開発・動作確認用)

```bash
cd server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8090
```

- 起動時に `_worklogapp._tcp.local.` としてmDNS(zeroconf)で自己広告する
- DBファイルは `server/data/worklog.db`(SQLite)。初回起動時に自動作成される
- 統合ウィザードは `http://<サーバーIP>:8090/` から利用

### テスト

```bash
cd server
source .venv/bin/activate
python -m pytest -q
```

12件のテスト(同期の冪等性・change_seqの進み方・統合ロジック)を含み、全て通過することを確認済み。

## Androidクライアント

`client-android/` を Android Studio で開いてください。

- パッケージ: `com.miyakz.worklog` / minSdk 26 / Jetpack Compose + Room + Retrofit
- JDK 17 / Gradle 8.10.2 / Android SDK(platform 35, build-tools 35.0.0)を用いてこの環境上で実際に `./gradlew :app:assembleDebug` を実行し、**ビルド成功(警告0件)を確認済み**です。`gradlew` / `gradle-wrapper.jar` も生成済みなので、そのまま Android Studio で開くかコマンドラインでビルドできます
- `local.properties` はこの環境専用のSDKパスを指しているため`.gitignore`対象です。他の環境で開く場合はAndroid Studioが自動生成するか、`sdk.dir=<自分のSDKパス>` を書いた `local.properties` を作成してください
- 実機/エミュレータでの起動確認は行っていません(コンパイル・リンク・リソース処理・dex化までの確認)
- `./gradlew :app:lintDebug` も実行済み(0エラー・30警告。全てAGP/依存ライブラリの新バージョン案内やmonochromeアイコン未設定などの情報的な指摘で、コード側の問題は0件)

コマンドラインで再ビルドする場合、この環境用に導入したJDK/Gradle/Android SDKが `~/android-tools/` にあります(プロジェクト外・gitには含まれません):

```bash
export JAVA_HOME=~/android-tools/jdk-17.0.13+11
export ANDROID_SDK_ROOT=~/android-tools/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
cd client-android
./gradlew :app:assembleDebug
```

### 未実装・今後の拡張余地

- 発見済みサーバーIP:ポートのキャッシュ(仕様上「後から追加してよい最適化」と明記されている項目。現在は同期の都度mDNS探索します)
- 統合の分割・取り消し機能(仕様で明示的にスコープ外)

## ポート管理

新規に使用したポートは `~/tcp_ports_list.txt` に `work-log-app 8090` として追記済みです。
# task_manager
