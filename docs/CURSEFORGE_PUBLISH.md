# CurseForge publishing / 自動公開

GitHubでリリースを公開すると、`Publish to CurseForge` が起動します。
公開済みJARを `SHA256SUMS.txt` と照合し、Fabric・Forge・NeoForgeを別ファイルとして
同じCurseForgeプロジェクトへ送ります。再ビルド・Minecraftの再テストは行わず、
ソースJAR・テストログ・ワールドは送信対象外です。

## 最新の公開確認（2026-10-09 JST）

alpha.9公開で[自動デプロイ37846723769](https://github.com/GenichiMaruo/WorldgenAssist/actions/runs/37846723769)
が起動し、成功しました。公開コミットは`ec0a7868e9eafc1abd7e0abe04b92d264ab9def1`。
ログの`verified=3 uploaded=3`で、Fabric・Forge・NeoForgeの配布JAR照合と投稿受理を確認しました。
GitHubの11添付物は公開前に再取得し、全ハッシュを照合済みです。
公開後の再ビルド・ゲーム再実行・手動再投稿はありません。
CurseForge側の審査完了や公開一覧は独立には確認していません。

### alpha.8の公開確認（2026-10-08 JST）

alpha.8の公開イベントで[自動デプロイ37641768372](https://github.com/GenichiMaruo/WorldgenAssist/actions/runs/37641768372)
が起動し、成功しました。公開コミットは`1ace9f4a1b06c1e288625a64d4b04f1f1e1e9f53`、
ログの`verified=3 uploaded=3`で、3ローダーの配布JAR照合と投稿受理を確認しました。
GitHubの11添付物は公開前に再取得し、すべてSHA256照合済みです。
公開後の再ビルド・ゲーム再検証・手動再投稿は行っていません。
CurseForgeの審査完了や公開一覧は独立には確認していません。

### alpha.7の公開確認（2026-10-05）

alpha.7公開イベントで[自動デプロイ37212747861](https://github.com/GenichiMaruo/WorldgenAssist/actions/runs/37212747861)
が起動し、成功しました。ログの`verified=3 uploaded=3`で3ローダーの照合と投稿受理を
確認しました。再ビルドや手動の再投稿は行っていません。CurseForge側の審査・公開一覧は
独立には確認していません。GitHubの11添付物は、公開前に再取得してハッシュ照合済みです。

## 設定

GitHubの **Settings → Environments → curseforge** を使用します。

| 登録先 | 名前 | 内容 |
| --- | --- | --- |
| Environment secrets | `CURSEFORGE_TOKEN` | 作者用のアップロードAPIトークン |
| Environment variables または secrets | `CURSEFORGE_PROJECT_ID` | 数値のプロジェクトID |

Project IDはvariablesを優先し、なければsecretsを読みます。リポジトリに値を
書き込む必要はありません。完全自動の場合はEnvironmentの必須承認者を設定しません。
公開タグ／ブランチをEnvironmentの制限で許可してください。

## 公開処理

- `release: published` で起動するため、下書きから公開したアルファも対象です。
- タグは `v0.1.0-alpha.5+mc26.3` の形式です。alphaはalpha、beta／rcはbeta、
  正式版はreleaseとして登録し、GitHubのプレリリース設定と照合します。
- 3種類の導入JARのサイズ・ハッシュとCurseForgeのMinecraft版・ローダー分類を
  すべて確認してから投稿します。対応版が未登録なら停止します。
  Minecraft版はversion-typesと照合し、現行の `26.3` と従来の `Minecraft …` の
  分類名に対応します。同名でも分類不明のエントリは採用しません。
  26.2のFabricのみの旧リリースは、この3ローダー用ワークフローの対象外です。
- Fabric APIはFabric版の必須依存として登録します。Java 25の分類が存在すれば
  付与します。両側に導入するMODなので、必須の環境分類 **Client・Server** も
  付与します。環境分類がカタログで解決できなければ、全投稿を開始前に停止します。
  変更履歴はGitHubリリース本文で、性能・シード開示の制限も引き継ぎます。
- APIが投稿を拒否した場合は、トークン・GitHub認証情報・設定IDを伏せ、
  最大1600文字の応答本文をログへ記録します。投稿の自動再試行は行いません。
- 投稿後のCurseForgeファイルIDはActionsのSummaryへ記録します。
  CurseForgeの審査・掲載時刻はGitHubと同時になるとは限りません。

## 既存リリースの確認／転送

**Actions → Publish to CurseForge → Run workflow** で `main` を選びます。

| 入力 | 選択 |
| --- | --- |
| tag | `v0.1.0-alpha.5+mc26.3` など公開済みタグ |
| mode | `check` は事前確認のみ、`publish` は送信 |
| loader | `all`、途中失敗からの再開時は未送信のローダー1つ |

設定の追加だけでは既存のalpha.5は送信されません。まず `check` で確認し、転送する
場合は `publish` を指定します。新しいリリースは公開イベントで自動送信されます。

POSTは自動再試行しません。応答が失われても受理済みの可能性があるためです。
公開ジョブの「Re-run」は拒否します。途中失敗時はSummaryとCurseForgeのファイル一覧で
送信済みを確認し、手動実行で未送信のローダーだけを選んでください。複数ファイルの
公開は一括トランザクションではありません。

## 検証

`scripts/Run-CurseForgeGate.ps1` が5つの対象テストを一括実行します。3ローダーの
正しい投稿、ハッシュ／対応版不一致時の全投稿停止、checkの非投稿、認証情報の
別サービスへの転送拒否、投稿拒否時の理由記録・秘密情報の除去・再試行停止を
確認します。ゲーム・ビルド・外部投稿は対象外です。
GitHubの `check` 実行は実際のEnvironmentと公開添付物を確認しますが、実投稿の権限や
CurseForgeの承認完了までは証明しません。

### alpha.6の投稿拒否の調査（2026-10-03 JST）

- [公開イベントの実行](https://github.com/GenichiMaruo/WorldgenAssist/actions/runs/37093573718)
  は最初のFabric POSTでHTTP 400となりました。旧ログは応答本文を捨てていました。
- [理由を記録した実行](https://github.com/GenichiMaruo/WorldgenAssist/actions/runs/37094441909)
  で `errorCode=1021`、環境分類から少なくとも1つを選択する必要があることを確認しました。
  Minecraft・Java・ローダー分類だけでは不足し、Client／Server分類の追加が必要でした。
- 修正は公開スクリプトとその対象テストのみです。公開済みMOD・タグ・添付物は変更しません。
- 対象5テストを一括で実行し、全成功しました（環境分類の欠落時の全投稿停止も確認）。
  Minecraftテスト・再ビルドは実行していません。
- ソース `036b6c1980ae05798146bc3aa32a25de0fa45cec` の
  [復旧実行](https://github.com/GenichiMaruo/WorldgenAssist/actions/runs/37094546439)
  が成功し、公開済みalpha.6の3 JARをハッシュ照合後に投稿しました。
  Fabric **9043744**、Forge **9043745**、NeoForge **9043746** が受理されました。
  12:51 JSTの作者用一覧ではスキャン・処理待ちでした。受理と審査完了は区別します。
  新しいリリースの公開イベントも、同じ修正済みmainのスクリプトを使用します。

### 設定時の確認結果（2026-09-30 JST）

- ソース `fd9c669dba8f40aeacc581b5f7cc2e1a1db270f0` の対象4テストが一括で成功しました。
- [GitHub Actionsの事前確認](https://github.com/GenichiMaruo/WorldgenAssist/actions/runs/36591014204)
  が成功しました。対象は `v0.1.0-alpha.5+mc26.3`、`verified=3`、`uploaded=0` です。
- 実際のEnvironment secretsを使ってカタログの取得と分類解決、公開済み3 JARの
  ハッシュ一致を確認しました。既存alpha.5のCurseForgeへの転送は実施していません。

## English

The workflow uploads exact GitHub release JARs after verifying all three loader
artifacts against `SHA256SUMS.txt`. It uses the `curseforge` Environment,
`CURSEFORGE_TOKEN` secret and `CURSEFORGE_PROJECT_ID` variable or secret.
No rebuild or Minecraft tests. Sources and test/world artifacts are excluded.

New release publication triggers uploading. Manual dispatch on `main` supports
`check` (default, no upload) and `publish` for an existing tag. To resume a partial
upload, inspect the Summary and CurseForge files, then select a remaining loader.
POSTs are not automatically retried; rerunning publishing jobs is rejected.
Upload permissions and moderation require an actual upload. Existing GitHub
releases, tags and assets are never changed by this workflow.

## References

- [GitHub release events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release)
- [GitHub environments](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/deploy-to-environment)
- [CurseForge Upload API](https://support.curseforge.com/support/solutions/articles/9000197321)
