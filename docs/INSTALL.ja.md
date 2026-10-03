# 導入方法 — Minecraft 26.3 / 26.2

[English](INSTALL.md) | [日本語](INSTALL.ja.md) · [MOD の概要](../README.ja.md)

## Minecraft 26.3 alpha.6

Minecraft Java Edition **26.3** と Java **25**（検証版 25.0.4）を用意し、
Fabric Loader **0.19.5** と Fabric API **0.161.0+26.3**、Forge **66.0.3**、
NeoForge **26.3.0.13-beta** のいずれかを選びます。
[alpha.6 リリース](https://github.com/GenichiMaruo/WorldgenAssist/releases/tag/v0.1.0-alpha.6%2Bmc26.3)
からローダーに対応する `0.1.0-alpha.6+mc26.3` の導入用 JAR を取得し、
サーバーと参加クライアントの `mods` に同じ種類の JAR を入れます。Fabric API が
必要なのは Fabric 版だけです。`-sources.jar` や異なるローダー／Minecraft 版の
JAR を混在させないでください。

以前の Worldgen Assist JAR は各 `mods` フォルダーから移してください。alpha.6 は
プロトコル6を使い、プロトコル4の alpha.5 と支援通信できません。サーバーと参加する
全クライアントを同時に更新します。導入するのは次のいずれか1つです。

| ローダー | 導入用 JAR |
| --- | --- |
| Fabric | `worldgen-assist-0.1.0-alpha.6+mc26.3.jar` |
| Forge | `worldgen-assist-forge-0.1.0-alpha.6+mc26.3.jar` |
| NeoForge | `worldgen-assist-neoforge-0.1.0-alpha.6+mc26.3.jar` |

最新の導入済み実行・一致検証と性能測定は Fabric の Overworld・クライアント2台が
対象です。Forge／NeoForge の最新候補はビルド確認済みですが、変更後の実行経路は
再検証していません。以前の vanilla ディメンションと互換追加ディメンションの結果は、
元の候補 JAR に紐づく証跡として保持しています。添付の検証記録を参照してください。

信頼できる参加者と使い捨てのワールドで試してください。リモート支援は既定で無効で、
有効化にはシードの開示を別途明示する必要があります。26.2 の公開シード専用
transcript 経路は 26.3 に未移植です。対象は vanilla の Overworld・Nether・End の
条件を満たす新規チャンクです。互換追加ディメンションと vanilla ノイズ生成器を
明示的に使うラッパーは実験的対応です。任意の独自生成やサーバーにしか存在しない
ノイズ定義は対象外で、悪意ある参加者への対応や速度向上は保証しません。
ゲーム内の **設定 → WorldgenAssist** から設定でき、
参加設定は再接続後、保存したサーバー方針は再起動後に反映されます。

### 測定した実験設定

alpha.6の導入だけでは、支援・並列生成・非同期待機の実験設定は有効になりません。
Fabric・距離32の中央値は完成1.88%・受信1.44%・CPU6.62%改善ですが、完成は3回中2回改善、
tick p95は3.64%悪化しました。サーバーとクライアントは別PCで、2人のクライアントは
同じPC上です。支援なし・ありとも同じサーバー並列生成方式で比較しています。

信頼できる参加者と使い捨てのワールドで、**サーバーを起動するPowerShell**に次を設定して
起動し直します。シードを参加者へ開示する設定を含みます。秘密の8点による検証は、
悪意ある結果の全体を認証するものではありません。Java起動オプションの同名設定が優先されます。

```powershell
$env:WORLDGEN_ASSIST_REMOTE='true'
$env:WORLDGEN_ASSIST_REMOTE_SEED_DISCLOSURE='trusted_raw'
$env:WORLDGEN_ASSIST_NOISE_BACKEND='cooperative'
$env:WORLDGEN_ASSIST_REMOTE_MAX_IN_FLIGHT='32'
$env:WORLDGEN_ASSIST_REMOTE_OWNER_WINDOW='16'
$env:WORLDGEN_ASSIST_REMOTE_CACHE_ENTRIES='128'
$env:WORLDGEN_ASSIST_REMOTE_PREDICTION='true'
$env:WORLDGEN_ASSIST_REMOTE_VALIDATION_SAMPLE_CELLS='8'
$env:WORLDGEN_ASSIST_REMOTE_TIMEOUT_MS='30000'
$env:WORLDGEN_ASSIST_REMOTE_PREFETCH='true'
$env:WORLDGEN_ASSIST_REMOTE_PREPARE_VALIDATION='true'
$env:WORLDGEN_ASSIST_REMOTE_ADAPTIVE_DEMAND_WAIT='true'
$env:WORLDGEN_ASSIST_REMOTE_DEMAND_WAIT_MS='100'
$env:WORLDGEN_ASSIST_REMOTE_READY_SURFACE_ONLY='false'
$env:WORLDGEN_ASSIST_REMOTE_PREFETCH_LOOKAHEAD='0'
```

依頼全体の有効期限は30秒ですが、必要になったチャンクの非同期待機は最大200msで通常生成へ戻ります。
サーバー・通信スレッドをブロックしません。**各クライアント**の設定 → WorldgenAssistで参加を有効にし、
ランチャーのJava起動オプションへ `-Dworldgen_assist.client.job_window=16` と
`-Dworldgen_assist.client.reuse_context=true` を加えて起動・再接続します。計算スレッドは既定の2本です。
測定した大きさに揃える場合はサーバーの `view-distance=32`、クライアントの描画距離も32にします。
測定時は `simulation-distance=3`、サーバーヒープ6GB・各クライアント4GBでした。
CPUを2論理CPU・Java認識数2へ制限したのは試験条件で、導入時に制限する必要はありません。
通常の既定値は変更せず、別環境での高速化は保証しません。
[alpha.6検証記録](releases/v0.1.0-alpha.6+mc26.3-verification.md)を参照してください。

## 旧 Minecraft 26.2 Fabric alpha.3

このアルファ版は、信頼できる参加者と使い捨てのワールドで試すためのものです。
一般の公開サーバーで常用できる段階ではありません。導入するだけではリモート
生成は有効になりません。また、速度向上はまだ実証していません。

1. 既存環境をバックアップし、別のテスト用プロファイルとワールドを用意します。
2. Minecraft Java Edition **26.2**、Java **25**（検証版: 25.0.4）、
   Fabric Loader **0.19.3**、Fabric API **0.156.0+26.2** を用意します。
3. [GitHub Releases](https://github.com/GenichiMaruo/WorldgenAssist/releases)から
   `worldgen-assist-0.1.0-alpha.3+mc26.2.jar` をダウンロードします。
   `-sources.jar` は開発者向けソースで、導入用ではありません。
4. クライアントとサーバーの `mods` フォルダーに、Fabric API と同じバージョンの
   Worldgen Assist の JAR を入れます。古い Worldgen Assist の JAR は別の場所へ
   保管し、2つの版を同時に読み込ませないでください。Minecraft 本体の JAR を
   置き換えるものではありません。
5. [詳細ガイド（英語）](ADVANCED_GUIDE.md)のモード制限を確認するまでは、
   リモート支援を無効のままにしてください。起動に成功しただけでは、クライアント
   による計算支援が動作しているとは限りません。

trusted raw-seed モードでは、ワールドのシードをクライアントに開示します。
別経路の公開 transcript フィクスチャは、公開シード `8675309` 専用です。
どちらも、通常のサバイバルサーバーでシードを秘匿したまま使えるモードでは
ありません。両経路を同時に有効にしないでください。条件を限定した検証は
[フィクスチャの仕様と手順（英語）](SEEDED_LEAF_FIXTURE_PROTOCOL.md)に従います。

alpha.3 では、参加する各クライアントが vanilla の Overworld・Nether・End の
対象となる新規地形を同時に支援できます。サーバーと参加する全クライアントを
alpha.3 に揃え、他の版と混在させないでください。
既定では全体で8ジョブ、1人あたり1ジョブが上限です。担当プレイヤーが処理中・切断時は
通常生成に戻ります。**設定 → WorldgenAssist** からクライアントの参加設定と、
管理者に限りサーバー方針を変更できます。前者は再接続、後者はサーバー再起動後に
反映されます。より大きな集団、MOD追加ディメンション、カスタムジェネレーター、
悪意ある参加者への対応は保証しません。

## ダウンロードの確認

次のコマンドの SHA-256 と、リリース添付の `SHA256SUMS.txt` 内の同名ファイルの
値を照合してください。

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath '.\worldgen-assist-0.1.0-alpha.6+mc26.3.jar'
```

チェックサムはファイルの相違を検出するもので、独立した発行者署名ではありません。
Minecraft、Java、Fabric Loader、Fabric API は配布物に同梱していません。
他の Minecraft 版や MOD・データパックとの組み合わせは未検証です。

## 不具合を報告するとき

MOD・Minecraft・Java・Fabric の版、使用モード、再現手順、関連するクライアント／
サーバーのエラーを添えてください。ログを共有する前に、アクセストークン、非公開の
アドレス、プレイヤー情報、ワールドのシードを取り除いてください。プライベートな
ワールド自体はアップロードせず、新しいテスト環境でも再現するかを書いてください。
