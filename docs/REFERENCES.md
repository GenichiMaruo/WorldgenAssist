# References

Use these as secondary references after the generated Minecraft 26.2 source.

## Fabric 26.2

### Fabric for Minecraft 26.2

https://www.fabricmc.net/2026/06/15/262.html

Notes at the time of the 26.2 Fabric release include Loom 1.17 and Gradle 9.5.1 recommendations.

### Fabric Developer Guides

https://docs.fabricmc.net/develop/

### Development environment

https://docs.fabricmc.net/develop/getting-started/setting-up

### Create a project

https://docs.fabricmc.net/develop/getting-started/creating-a-project

### Fabric Template Mod Generator

https://fabricmc.net/develop/template/

### VS Code setup

https://docs.fabricmc.net/develop/getting-started/vscode/setting-up

### Generate Minecraft source in VS Code

https://docs.fabricmc.net/develop/getting-started/vscode/generating-sources

### Launch Minecraft in VS Code

https://docs.fabricmc.net/develop/getting-started/vscode/launching-the-game

### VS Code source navigation tips

https://docs.fabricmc.net/develop/getting-started/vscode/tips-and-tricks

### Build a mod

https://docs.fabricmc.net/develop/getting-started/building-a-mod

### Project structure

https://docs.fabricmc.net/develop/getting-started/project-structure

### Networking

https://docs.fabricmc.net/develop/networking

### Automated testing

https://docs.fabricmc.net/develop/automatic-testing

### Loom

https://docs.fabricmc.net/develop/loom/

### Migrating mappings / modern names

https://docs.fabricmc.net/develop/porting/mappings/

### Mixin bytecode background

https://docs.fabricmc.net/develop/mixins/bytecode

---

## Local primary reference

After project setup run:

```powershell
.\gradlew.bat genSources
```

Then treat the generated Minecraft 26.2 source and Gradle-resolved Fabric API source as the primary implementation references.

Important classes to investigate first:

```text
ChunkStatus
ChunkGenerator
NoiseBasedChunkGenerator
NoiseChunk
RandomState
NoiseGeneratorSettings
Aquifer
Blender
StructureManager
ChunkAccess
ProtoChunk
```

The list is for navigation only. Confirm exact symbols and method signatures locally.

---

## Reference policy

When external documentation and generated source disagree:

1. Confirm project version.
2. Prefer generated source for Minecraft internals.
3. Prefer current Fabric 26.2 API source/docs for Fabric APIs.
4. Record the resolution in repository docs.

Avoid old Yarn names and older-version snippets unless they are explicitly being used for historical comparison.
