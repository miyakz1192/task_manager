# 作業記録アプリ(work-log-app)プロジェクト仕様

このファイルは、実装開始前の設計議論をまとめたものです。Claude Codeはこの内容を前提として実装を進めてください。未確定の項目は文書末尾に明記しています。

## 概要

個人利用の作業記録(ログ)アプリ。一般的なタスク管理アプリとは異なり、**実行した作業を記録するだけの「ログ」**であり、記録済みタスクの状態を更新する概念(進行中/完了などのステータス管理)は持たない。1レコード = 1回の作業実行、を表す。

- ユーザーは1人のみ(マルチユーザー非対応)
- クライアント: Androidアプリ。日々の作業記録の入力・一覧・削除を行う
- サーバー: 自宅の常時稼働PC(頻繁に電源が落ちることがある)。データの集約と、タスク名の統合(表記ゆれの解消)を担う
- サーバーとクライアントはベストエフォートで同期する。**サーバーが見つからない/停止していても、Androidアプリは単体で問題なく動作し続ける必要がある**

## 実行環境

- サーバーはUbuntu Linuxサーバー上で稼働する
- 同じサーバー上には、Claude Codeによって作成された他のPythonプロジェクトが複数、ディレクトリを分けてすでに稼働中である
- 本プロジェクトも同様に、専用ディレクトリを新設して構築する
- サーバー側言語はPython。既存の他プロジェクトと同様に、**Python仮想環境(venv)を使用**する
- クライアント(Android)のプログラムも、同じプロジェクトディレクトリ内で開発する(サーバーとクライアントのコードが同一プロジェクト配下に混在する)

## ディレクトリ構成(提案)

サーバー(Python)とクライアント(Android/Kotlin)は言語・ビルド体系が全く異なるため、プロジェクト直下でサブディレクトリを分ける。

```
work-log-app/
├── CLAUDE.md              # 本ファイル
├── server/                # Pythonサーバー
│   ├── .venv/              # 仮想環境(gitignore対象)
│   ├── app/                 # アプリケーション本体
│   ├── requirements.txt
│   └── ...
├── client-android/        # Androidクライアント
│   ├── app/
│   ├── build.gradle.kts
│   └── ...
└── docs/                  # 設計メモ等(必要に応じて)
```

必要に応じて内部構成は調整してよいが、「サーバーとクライアントで別ディレクトリ」という分離は維持すること。

## データモデル

タスクの定義(マスタ)と実行履歴という2階層は存在せず、**1レコード=1回の作業実行**というフラットな単一テーブル構造とする。同じ文言の作業を複数回行えば、その都度別レコード(別タスクID)として記録される。

### タスクID

データベースの内部主キー(自動採番)とは別に、公開用の一意なIDを持つ。

- 形式: `作成日時 + ランダムサフィックス`(例: `20260729-143522-x7k9`)
- UUIDより短く人間が読める。ソート可能。専用ライブラリ不要で生成できる

### クライアント側テーブル(Android / Room・SQLite)

```sql
CREATE TABLE records (
  db_id       INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id     TEXT UNIQUE NOT NULL,
  text        TEXT NOT NULL,
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL,
  is_deleted  INTEGER NOT NULL DEFAULT 0,
  dirty       INTEGER NOT NULL DEFAULT 1   -- サーバー未Pushの目印(クライアントのみが持つ)
);

CREATE TABLE sync_state (
  key   TEXT PRIMARY KEY,
  value TEXT
);
-- 例: ('since_change_seq', '137')
```

### サーバー側テーブル(SQLite)

```sql
CREATE TABLE records (
  db_id       INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id     TEXT UNIQUE NOT NULL,
  text        TEXT NOT NULL,
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL,
  is_deleted  INTEGER NOT NULL DEFAULT 0,
  change_seq  INTEGER NOT NULL           -- 状態変更イベントごとの連番(サーバーのみが持つ)
);

CREATE TABLE merge_history (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  merged_at         TEXT NOT NULL,
  from_text         TEXT NOT NULL,
  to_text           TEXT NOT NULL,
  affected_task_ids TEXT NOT NULL   -- 対象task_idのJSON配列
);
```

サーバーDBは、単一ユーザー・低頻度書き込みという前提から、SQLiteで十分と判断している(PostgreSQL等の本格的なDBサーバーは不要)。

### ソフトデリート

削除は物理削除ではなく `is_deleted=1` へのフラグ更新として扱う。クライアント・サーバーとも、レコードの物理削除は行わない。同期の整合性を保つため(墓標=tombstoneとして残し続ける必要がある。特にスマホ故障時の新端末への全件復元で、削除済みタスクが復活する事故を防ぐため)。

- 通常の一覧表示クエリには必ず `WHERE is_deleted = 0` を付ける

## change_seq の意味

`change_seq` は「サーバー上でレコードに対して発生した状態変更イベント(INSERTまたはUPDATE)の連番」である。**レコード数とは異なる。** 読み取りでは進まず、書き込み(新規作成・削除・統合によるリネーム)のたびに新しい値が発行される。

- 1つの統合(マージ)操作で複数レコードを同時に書き換える場合、それらに同じ `change_seq` を割り振ってよい(Pull側は `>` 比較のため問題なく拾える)
- 採番方法: `SELECT COALESCE(MAX(change_seq), 0) + 1 FROM records` をトランザクション内で実行する程度で、この利用規模では十分(専用カウンタテーブルは過剰)

## 同期プロトコル

### 前提

- サーバー発見: 同一LAN内でサーバーを検出できた場合のみ同期を試みる(mDNS/NSD等での自動検出を想定。具体的な実装方式は実装フェーズで検討する)
- 認証: 同一LAN内であれば無条件で信頼してよい(個人利用のため)
- サーバー不在・停止中でも、Androidアプリは通常通り動作し続けること(同期の失敗は無視してよい。エラー表示不要)
- 同期タイミング: アプリ起動時の自動同期 + 手動同期ボタンの両方を用意する
- 実行順序: 1回の同期では必ず **Push → Pull** の順で実行する

### レコードの識別・競合解決の考え方

- レコードの同一性は `task_id` で判定する
- このアプリには「編集」機能がなく、各フィールドの書き込み主体があらかじめ決まっている
  - `text`: 作成時はクライアントが書き込み、以降はサーバー(統合ウィザード)のみが書き換える
  - `is_deleted`: クライアントのみが書き込む(false→trueの一方向)
- そのため、**クライアントとサーバーの時刻同期には依存しない**、役割ベースの判定で競合を解決できる

### Push(クライアント → サーバー)

クライアント側:
1. `dirty=1` のレコードをすべて取得
2. `POST /sync/push` で送信: `{ "records": [...] }`
3. レスポンスで受理された `task_id` の `dirty` を `0` にクリア
4. 失敗時は何もしない(`dirty=1` のまま残り、次回同期時に再送される。冪等なので安全)

サーバー側の処理(task_idごと):
```
if 該当task_idが存在しない:
    無条件でINSERT(新しいchange_seqを採番)
else if 送られてきたis_deleted=true かつ 既存レコードのis_deleted=false:
    is_deletedをtrueに更新(新しいchange_seqを採番)
else:
    何もしない(既に同期済み、または無効な要求として無視)
```

サーバーは既存レコードの `text` を、クライアントからのPushでは絶対に書き換えない(textの書き換えは統合ウィザードのみが行う)。

レスポンス: `{ "accepted": ["task_id1", "task_id2", ...] }`

### Pull(サーバー → クライアント)

リクエスト: `GET /sync/pull?since_change_seq={クライアントが保持する値}`

サーバー側の処理:
```
records = SELECT * FROM records WHERE change_seq > since_change_seq ORDER BY change_seq
max_change_seq = records が空なら since_change_seq、そうでなければ records の change_seq の最大値
return { "records": records, "max_change_seq": max_change_seq }
```

クライアント側の処理:
1. 受信した各レコードを `task_id` でUpsert(新規ならINSERT、既存ならUPDATE。`dirty=0` で保存)
2. `sync_state` の `since_change_seq` を `max_change_seq` に更新

### 新端末への復元(スマホ故障時)

新しいAndroid端末でアプリを初期化する際は、`since_change_seq=0` を指定して通常のPullを呼ぶだけで、`is_deleted` を含む全レコードを取得できる。専用の復元APIは不要。

複数端末は「故障時の入れ替え」目的のみを想定しており、複数端末による同時利用は想定しない。

## サーバー側:タスク統合(マージウィザード)

表記ゆれのあるタスク名を、ユーザーが確認しながら正式名称に統一する管理機能。Web UIを想定。

想定する操作:
1. 表記ゆれの自動検出→一括統合(全角半角統一・トリムなど正規化した結果が完全一致するものは、確認を軽くしてよい)
2. 類似候補の提示→手動確認での統合(編集距離などで類似文字列を検出し、ユーザーに統合の可否を確認する)
3. 一覧からの手動選択統合(タスク名一覧を頻度付きで表示し、複数選択→統合先の名称を指定)
4. 正式名称の指定(既存候補から選ぶか、新規に入力するかを選べる)
5. 低頻度タスクのレビュー一覧(出現回数が少ない順に並べ、統合対象の発見を補助する)
6. 統合履歴の記録(`merge_history` テーブルに、いつ・何を・何に統合したか、対象task_idを記録する)

統合実行時の処理:
```sql
BEGIN TRANSACTION;

-- next_change_seq を採番

UPDATE records
SET text = '正式名称', updated_at = now(), change_seq = next_change_seq
WHERE task_id IN (対象task_idのリスト);

INSERT INTO merge_history (merged_at, from_text, to_text, affected_task_ids)
VALUES (now(), '元の表記', '正式名称', 対象task_idのJSON配列);

COMMIT;
```

統合結果は、既存のPull処理(`change_seq > since_change_seq`)にそのまま乗る形でクライアントに配信される。専用の配信経路は不要。

スコープ外(v1では実装しない): 統合の分割・取り消し機能(専用UI)。`merge_history` を見ながら手動で戻すことは可能だが、自動化はしない。

## クライアント(Android)側の要件

- 起動すると「本日の画面」が表示される
- 作業内容をテキストで入力し、記録として追加できる。追加はすぐに反映される(同期を待たない)
- インクリメンタル予測(入力補完)機能を持つ。サーバー同期後の統合済み正式名称も含めた候補を出す
- 似た文言でも1文字でも異なれば別タスクとして扱ってよい(クライアント側で類似判定・統合は行わない。統合はサーバーの役割)
- タスクの編集機能は不要。追加と削除のみ
- 削除はシンプルなレコード削除(ユーザー視点)。内部的にはソフトデリート

## 命名規約(重要)

「サーバーで発生した状態変更イベントの連番」という意味を反映し、以下の名称で統一する。

| 役割 | 名称 |
|---|---|
| サーバーDBのカラム | `change_seq` |
| クライアントの保存値(`sync_state`のkey) | `since_change_seq` |
| Pull APIのクエリパラメータ | `since_change_seq` |
| Pull APIレスポンスのフィールド | `max_change_seq` |
| サーバー側の採番関数 | `nextChangeSeq()`(実装言語に応じて命名は調整可) |

## 技術スタック・実装方針(確定)

### サーバー発見(mDNS/NSD)

| 役割 | 採用technology |
|---|---|
| サーバー側(Python) | `zeroconf` パッケージ(`pip install zeroconf`) |
| クライアント側(Android) | 標準API `NsdManager`(追加ライブラリ不要) |
| サービスタイプ | `_worklogapp._tcp.local.`(独自定義) |

- サーバー起動時に `zeroconf` でサービスタイプを登録し、待受ポートを広告する
- Android側は同期実行のたびに `NsdManager.discoverServices()` で探索。タイムアウトは2〜3秒程度に短く設定し、見つからなければ即座に諦めて通常動作を続ける(UIをブロックしない)
- 発見できたIP:ポートは端末側でキャッシュし、次回はまずそのアドレスに直接接続を試み、失敗したら改めてmDNS探索する(後から追加してよい最適化。初期実装では毎回探索でも可)

### タスク統合の類似度判定

| 段階 | 判定方法 | 扱い |
|---|---|---|
| 表記ゆれ(完全一致相当) | `unicodedata.normalize('NFKC', text)` + トリム + 空白圧縮 → 完全一致 | 「同じ表記です」とワンクリック確認できる形で提示(自動統合はしない) |
| 類似候補 | `rapidfuzz.fuzz.ratio()`(0〜100のスコア)を正規化後の文字列に適用 | スコア80以上のペアを統合候補としてスコア降順に一覧表示し、人間が個別に確認 |

- 類似度判定ライブラリは `rapidfuzz` を使用する(`python-Levenshtein`より高速、メンテナンス活発)
- 閾値(初期値80)は設定ファイル等で後から調整可能にする。実際の語彙を見ながら調整する前提
- スコア80未満のペアは候補として表示しない

### サーバー管理画面(統合ウィザード)

- **FastAPI + Jinja2テンプレート**(サーバーサイドレンダリング)を採用する
- 同期API(push/pull)はPydanticモデルによる型付きリクエスト/レスポンスとする
- 統合ウィザードUIはSPA(React/Vue等)ではなく、Jinja2による素朴なHTMLフォームとし、必要箇所のみ`htmx`等の軽量ライブラリで動的更新する
- ビルドパイプライン(npm/webpack)は導入せず、Pythonプロセス1つで完結させる
- 同一サーバー上の既存プロジェクトが別フレームワーク(Flask等)に統一されている場合は、一貫性を優先してそちらに合わせてもよい

### Android側の技術スタック

| 領域 | 採用technology |
|---|---|
| UI | Jetpack Compose |
| ローカルDB | Room |
| 通信 | Retrofit + kotlinx.serialization |
| サーバー発見 | NsdManager(標準API) |
| 同期のトリガー | 画面表示時にコルーチンで実行(バックグラウンド常駐は不要のためWorkManagerは使用しない) |

### 同期APIの通信仕様

- パス: `/api/v1/sync/push`、`/api/v1/sync/pull`(将来の変更に備えてバージョンプレフィックスを付与)
- `Content-Type: application/json`、認証ヘッダーなし(LAN内無条件信頼の方針)
- ポート番号: 同一サーバー上の既存プロジェクトと衝突しない番号を選定する(実装開始時に既存プロジェクトの使用ポートを確認すること)
- クライアント側の接続タイムアウトは短め(3秒程度)に設定し、サーバー不調時でもUIが固まらないようにする
