# AE2 VM for Minecraft 1.12.2

This project ports the AE2-VM crafting calculation engine to Minecraft 1.12.2
Forge and AE2 Unofficial Extended Life. It replaces the calculation tree with
an iterative bytecode VM where the pattern semantics are compatible. Patterns
that depend on substitution or damage-sensitive slot behavior stay on the
native AE2 path.

The port does not include the original hostile behavior. It does not inspect
authors, delete files or mods, or copy its own JAR outside the game directory.

## Development

Requirements:

- Java 8 (the tested runtime is JDK 1.8.0_202)
- A network connection for the ForgeGradle and Maven dependencies

From the project directory:

```text
gradlew.bat clean build --no-daemon
gradlew.bat prepareRunServer --no-daemon
gradlew.bat runServer --no-daemon
```

`prepareRunServer` copies MixinBooter 11.2 to `run/mods` and packages the late
Mixin configuration with its refmap. The AE2 UEL dependency is supplied by
Gradle on the development classpath. Do not place a second AE2 UEL JAR in
`run/mods`, or Forge will report a duplicate `appliedenergistics2` mod.

The final reobfuscated mod is written to:

```text
build/libs/ae2vm-1.12.2-1.0.0.jar
```

## Runtime dependencies

Install the final JAR together with:

- AE2 Unofficial Extended Life for Minecraft 1.12.2
- MixinBooter 11.x for Minecraft 1.12.2

The build uses the requested dependency:

```groovy
implementation fg.deobf("curse.maven:ae2-extended-life-570458:6302098")
```

MixinBooter is resolved from the CleanroomMC Maven repository and is not
bundled into the final mod JAR.

## Integration API

Third-party machines that need to opt into VM calculations can register a
class-name marker through `AE2VMCraftingRegistry.register(String)`. The
registry exists for the 1.12.2 API, which does not expose the newer requester
contract directly.
