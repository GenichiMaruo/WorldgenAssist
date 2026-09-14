# Versions and Minecraft branches / バージョン管理

[English overview](../README.md) · [日本語の概要](../README.ja.md)

## Naming

Current release version is `0.1.0-alpha.2+mc26.2`. Alpha.2 publication and the
fast-forward of `mc/26.2` were authorized by the user. Alpha.1's published tag,
release and assets remain immutable.

The mod version and Minecraft target are separate settings in `gradle.properties`.
Include the target as SemVer build metadata in every published mod version:

- Mod version: `0.1.0-alpha.2+mc26.2`
- Minecraft version: `26.2`
- Annotated Git tag: `v0.1.0-alpha.2+mc26.2`
- Maintenance branch: `mc/26.2`
- Distribution JAR: `worldgen-assist-0.1.0-alpha.2+mc26.2.jar`

`alpha` is a prerelease, not stable support. The `+mc26.2` suffix identifies the
target but does not make binaries compatible across Minecraft versions. Fabric's
Minecraft dependency in `fabric.mod.json` is a separate compatibility gate.
The source JAR is for developers, not installation in `mods`.

Git tags identify exact published snapshots and must not be moved or overwritten.
`main` is current development; `mc/26.2` is the 26.2 line. Future ports may create
their own `mc/<version>` branch and target-qualified release tag after generated
sources, Mixins, dependencies and tests have been reverified. No other Minecraft
version is currently supported, and no speculative port branch is created now.

## Release procedure

1. Set the mod version/target and review metadata dependencies; do not silently
   upgrade Minecraft, Java, Loader or API.
2. Run `scripts/Run-SeededLeafVerification.ps1` with the pinned JDK; inspect the
   final XML and unchanged source/config manifests. The artifact resolver uses
   exact Gradle properties, never the newest file or a wildcard-selected old JAR.
3. Run installed-JAR smoke/comparison before publication, retaining failed runs.
4. Commit notes/docs and version, create an annotated target-qualified tag and
   push the commit, maintenance branch and tag without force.
5. Create a draft GitHub prerelease, upload the distribution and source JARs,
   installation notes and `SHA256SUMS.txt`, verify the uploaded assets, then
   publish the draft as a prerelease. Never silently replace published assets.

## 日本語

MODのバージョンと対応Minecraft版を区別し、公開版の識別子に両方を含めます。
今回の版は **`0.1.0-alpha.2+mc26.2`**、Gitタグは
**`v0.1.0-alpha.2+mc26.2`**、26.2向けの保守ブランチは **`mc/26.2`** です。
`alpha` は安定版ではありません。別のMinecraft版でも同じJARが動くという意味
ではなく、対応条件は `fabric.mod.json` でも指定します。

将来別バージョン向けを作るときは、対応する `mc/<Minecraft版>` ブランチを
分け、生成ソース・Mixin・依存関係・テストを確認してから別タグで公開します。
公開済みタグの移動や成果物の上書きはせず、修正は新しいバージョンにします。
現時点で対応しているのはMinecraft Java Edition 26.2のみです。

リリース時は、版の設定、全テスト・ビルド、配布JARでの検証、コミットとタグの
push、ドラフトへのJAR・導入案内・SHA-256の添付、添付物の照合、プレリリース
公開の順に進めます。詳細ログやテストワールドは配布物に含めません。
