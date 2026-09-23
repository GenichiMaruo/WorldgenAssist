# Versions and Minecraft branches / バージョン管理

[English overview](../README.md) · [日本語の概要](../README.ja.md)

## Current release

| Item | Value |
| --- | --- |
| Mod version | `0.1.0-alpha.3+mc26.2` |
| Minecraft | Java Edition `26.2` |
| Git tag | `v0.1.0-alpha.3+mc26.2` |
| Maintenance branch | `mc/26.2` |
| Distribution JAR | `worldgen-assist-0.1.0-alpha.3+mc26.2.jar` |

The mod version and Minecraft target are separate values in `gradle.properties`.
The `+mc26.2` build metadata identifies the target but does not make binaries
compatible with another Minecraft release; `fabric.mod.json` enforces the
dependency. Alpha is a prerelease. Install the distribution JAR, not the
developer `-sources.jar`.

`main` carries ongoing work; `mc/26.2` tracks this Minecraft line. Published
tags, releases, and assets are immutable. For a fix after publication, advance
the version and create a **new** tag/release. Future Minecraft ports need their
own `mc/<version>` branch after source, Mixin, dependency, and behavior checks.

## Release procedure

1. Review version and dependency metadata. Resolve the exact versioned JARs
   with `scripts/Get-WorldgenArtifact.ps1`; never select an old wildcard match.
2. Identify changes since the last verified source/JAR. Run only affected tests
   and necessary build tasks; use targeted vanilla/assisted scenarios when
   worldgen behavior changed. A full matrix is for broad changes or an actual
   evidence gap, not every release. Retain prior passing evidence with its
   original artifact identity and disclose any untested final change.
3. Prepare current English/Japanese install notes, release notes and a
   `SHA256SUMS.txt` for the exact distribution/source JARs and notes. Check
   document links, artifact metadata, hashes, and the staged diff.
4. Commit, advance `mc/26.2` to the commit, create a new annotated
   target-qualified tag, and push without force. Confirm the tag did not
   already exist remotely.
5. Create a draft GitHub **prerelease**, upload the five assets, verify the
   uploaded bytes against `SHA256SUMS.txt`, and publish the draft. Do not
   replace older tags or assets. Keep test worlds/logs outside release assets.

## 日本語

現在の公開版は **`0.1.0-alpha.3+mc26.2`**、Gitタグは
**`v0.1.0-alpha.3+mc26.2`**、26.2向け保守ブランチは **`mc/26.2`** です。
サーバーと参加するクライアントに同じ導入用JARを入れます。`-sources.jar` は
開発者向けです。公開済みのタグや添付物は変更せず、修正時は新しい版にします。

リリース時は変更範囲に必要なテストとビルドの証跡を確認し、正確な版のJARと
日英案内のハッシュを照合します。全検証の再実行を常態化せず、既存の証跡は
元のソース・JARに紐づけて示します。コミット、保守ブランチ、新規タグを
forceなしでpushし、5点の添付物を検証してプレリリースを公開します。
