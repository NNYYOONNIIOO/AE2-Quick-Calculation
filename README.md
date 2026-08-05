# AE2 Quick Calculation for Minecraft 1.12.2

This project provides a direct crafting calculation accelerator for Minecraft
1.12.2 Forge and AE2 Unofficial Extended Life. Compatible patterns are handled
directly as pattern objects. The calculator keeps intermediate results in a
private stock ledger and preserves ordinary `a + b -> c` patterns where `b`
returns as its item container and can be reused by later crafts.

Deep dependency chains are processed with explicit pattern frames instead of
Java recursion. Pattern metadata and per-calculation pattern lookups are
cached, and intermediate stock is tracked locally to avoid repeated inventory
round trips. Root requests that contain a small, provably productive cycle use
an integer closed-form calculation; cycles without a seed, neutral cycles,
dissipative cycles, and ambiguous multi-input cycles stay on the native path.

Crafting substitutions are resolved in the same calculation pass when their
candidate inputs are ordinary non-container items. A damageable non-consumed
input is supported when its container transition is a same-item,
one-durability-step transition; the calculator accounts for whole durability
lifetimes in batches and leaves the final damaged container for the CPU to
return normally. Equal same-item input/output pairs are treated as reusable
catalysts. Other recursive structures are only optimized when their integer
execution ratios, seed requirement, and positive net gain can be proven
without exceeding the 1.12.2 quantity limit; otherwise the native path is
used with a reason-specific status message.

The 1.12.2 AE2 API represents requested and planned stack sizes as `long`.
Consequently, a literal request larger than `Long.MAX_VALUE` cannot enter or
be submitted through this API. The direct calculator optimizes quantities that
the API can represent and stops immediately when an intermediate quantity
would overflow, rather than handing that request to AE2's slower recursive
fallback.

The mod does not include hostile behavior. It does not inspect authors, delete
files or mods, or copy its own JAR outside the game directory.

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
build/libs/ae2-quick-calculation-1.12.2-1.0.0.jar
```

## Runtime dependencies

Install the final JAR together with:

- AE2 Unofficial Extended Life for Minecraft 1.12.2
- MixinBooter 11.x for Minecraft 1.12.2

The build uses the requested AE2 dependency:

```groovy
implementation fg.deobf("curse.maven:ae2-extended-life-570458:6302098")
```

MixinBooter is resolved from the CleanroomMC Maven repository and is not
bundled into the final mod JAR.

When a job uses the direct calculator, the requesting player sees a one-time
localized status overlay drawn above the active GUI, including the crafting
CPU management screen. English and Simplified Chinese are included. Unsupported
pattern semantics and runtime failures show a one-time native AE2 fallback
message instead. Quantity overflow is reported separately, is not sent into
native recursive calculation, and leaves a non-requestable missing entry in the
plan so the failed calculation is never presented as an empty plan.
