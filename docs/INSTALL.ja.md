# 導入方法 — Minecraft 26.3 / 26.2

[English](INSTALL.md) | [日本語](INSTALL.ja.md) · [MOD の概要](../README.ja.md)

## Minecraft 26.3 alpha.4

Minecraft Java Edition **26.3** と Java **25**（検証版 25.0.4）を用意し、
Fabric Loader **0.19.5** と Fabric API **0.161.0+26.3**、Forge **66.0.3**、
NeoForge **26.3.0.13-beta** のいずれかを選びます。
[alpha.4 リリース](https://github.com/GenichiMaruo/WorldgenAssist/releases/tag/v0.1.0-alpha.4%2Bmc26.3)
からローダーに対応する `0.1.0-alpha.4+mc26.3` の導入用 JAR を取得し、
サーバーと参加クライアントの `mods` に同じ種類の JAR を入れます。Fabric API が
必要なのは Fabric 版だけです。`-sources.jar` や異なるローダー／Minecraft 版の
JAR を混在させないでください。

信頼できる参加者と使い捨てのワールドで試してください。リモート支援は既定で無効で、
有効化にはシードの開示を別途明示する必要があります。26.2 の公開シード専用
transcript 経路は 26.3 に未移植です。対象は vanilla の Overworld・Nether・End の
条件を満たす新規チャンクです。カスタム生成、MOD 追加ディメンション、悪意ある
参加者、速度向上は保証しません。ゲーム内の **設定 → WorldgenAssist** から設定でき、
参加設定は再接続後、保存したサーバー方針は再起動後に反映されます。

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
Get-FileHash -Algorithm SHA256 -LiteralPath '.\worldgen-assist-0.1.0-alpha.3+mc26.2.jar'
```

チェックサムはファイルの相違を検出するもので、独立した発行者署名ではありません。
Minecraft、Java、Fabric Loader、Fabric API は配布物に同梱していません。
他の Minecraft 版や MOD・データパックとの組み合わせは未検証です。

## 不具合を報告するとき

MOD・Minecraft・Java・Fabric の版、使用モード、再現手順、関連するクライアント／
サーバーのエラーを添えてください。ログを共有する前に、アクセストークン、非公開の
アドレス、プレイヤー情報、ワールドのシードを取り除いてください。プライベートな
ワールド自体はアップロードせず、新しいテスト環境でも再現するかを書いてください。
