# Versions and Minecraft branches / バージョン管理

[English overview](../README.md) · [日本語の概要](../README.ja.md)

## Current release

| Item | Value |
| --- | --- |
| Mod version | `0.1.0-alpha.4+mc26.3` |
| Minecraft | Java Edition `26.3` |
| Git tag | `v0.1.0-alpha.4+mc26.3` |
| Maintenance branch | `mc/26.3` |
| Distribution JARs | Fabric `worldgen-assist-0.1.0-alpha.4+mc26.3.jar`; Forge and NeoForge have loader-named JARs |

The mod version and Minecraft target are separate values in `gradle.properties`.
The `+mc26.3` build metadata identifies the target but does not make binaries
compatible with another Minecraft release or another loader. Alpha is a
prerelease. Install the matching distribution JAR, not the developer
`-sources.jar`.

The earlier Minecraft 26.2 Fabric release remains at
`v0.1.0-alpha.3+mc26.2` on `mc/26.2`. The 26.3 port and validation limits
are tracked in `PORT_26_3.md` and the alpha.4 verification note.

`main` carries ongoing work; `mc/26.2` and `mc/26.3` track their Minecraft lines. Published
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
   `SHA256SUMS.txt` for the exact loader-specific distribution/source JARs and notes. Check
   document links, artifact metadata, hashes, and the staged diff.
4. Commit, advance the corresponding `mc/<version>` branch to the commit, create a new annotated
   target-qualified tag, and push without force. Confirm the tag did not
   already exist remotely.
5. Create a draft GitHub **prerelease**, upload the artifacts and both install
   guides, verify the
   uploaded bytes against `SHA256SUMS.txt`, and publish the draft. Do not
   replace older tags or assets. Keep test worlds/logs outside release assets.

## 日本語

現在の公開版は **`0.1.0-alpha.4+mc26.3`**、Gitタグは
**`v0.1.0-alpha.4+mc26.3`**、26.3向け保守ブランチは **`mc/26.3`** です。
サーバーと参加クライアントに同じローダー向けの導入用JARを入れます。`-sources.jar` は
開発者向けです。公開済みのタグや添付物は変更せず、修正時は新しい版にします。

リリース時は変更範囲に必要なテストとビルドの証跡を確認し、正確な版のJARと
日英案内のハッシュを照合します。全検証の再実行を常態化せず、既存の証跡は
元のソース・JARに紐づけて示します。コミット、保守ブランチ、新規タグを
forceなしでpushし、添付物を検証してプレリリースを公開します。
