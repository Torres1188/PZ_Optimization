# ZombieBuddy review build for 42.22.2

This adapter keeps `pzopt` as the original project's namespace and uses official ZombieBuddy 2.3.4. It does not require Faded Java Loader, ZombieBuddy Extensions, or PZOptPreloader. The FJL edition is maintained separately; enable one optimization edition per process.

The reviewed loader artifact is the official `v2.3.4` release `ZombieBuddy.jar`, SHA-256 `eb79b9876332010733a8e0d7ce4fe0377846059a9e28859029e2a0fb449f6cf2`. Moving GitHub master is a different API and transformer model. The adapter refuses other loader bytes until their ordering and lifecycle have been reviewed.

## Build

Use JDK 25 and Python 3. The payload input must already contain the complete reviewed override/helper classes, generated `pzopt/build-info.properties`, `pzopt/override-methods.properties`, shader/resource generation, licenses, and original Lua/media. Plain `javac` output is not a complete upstream payload.

```powershell
python scripts/build-zombiebuddy.py `
  --payload-root build/qualified-payload `
  --game-jar 'PATH_TO_GAME/projectzomboid.jar' `
  --zombiebuddy-jar 'PATH_TO_REFERENCE/ZombieBuddy.jar' `
  --output build/zombiebuddy-review `
  --version 42.22.2-review.1
```

The output directory must be new and below this checkout's `build/`. The builder does not install anything, modify a game JAR/launcher, download dependencies, run a game, or sign a mod. It emits a mod directory and an exact input/output inventory receipt.

Every existing `pzopt` helper/resource is preserved. Native definitions are stored as data under `META-INF/pzopt-zombiebuddy/payload/`; they are never placed at classpath-root `zombie/`, `fmod/`, `org/` or `se/` paths. Added helpers must be moved into public `pzopt` source types before packaging. Game identity and protected native reporting/startup classes remain native; excluded classes are explicitly named in the receipt. Their required performance hooks must be accounted for in the port's preservation report.

The current native main screen receives three narrow hooks for the existing menu texture-binding budget, cache-directory check and optional loading fade shortcut. Its integrity/version-reporting methods and class initializer remain byte-identical in their code arrays. The old whole-class main-screen source is never installed.

## Activation and removal

Close all game processes and remove an older manual loose-class installation using that installation's documented removal process before enabling this edition. Steam verification does not remove foreign loose class files. Do not run the original manual installer for this package or copy payload classes into the game folder.

Install the mod in the normal PZ mods folder. Use the reviewed offline setup script described in the review guide: the package's `-javaagent:...PZOptimization-ZombieBuddy.jar` must precede the official `-javaagent:...ZombieBuddy.jar`, with the native game JAR first in the classpath. This early entry is needed because official ZombieBuddy warms Lua classes before normal mod preload; their optimization fields and methods must exist at first definition. The bootstrap verifies both exact JAR identities before registering anything. It does not alter or fork ZombieBuddy.

The explicit launcher change authorizes the early Java agent independently of the PZ mod checkbox. Enable `ZombieBuddy` and `PZ_Optimization`, use ZombieBuddy's normal approval policy for its mod entry, save the selection and fully restart. `javaPreload=true` and manifest `ZB-Preload: true` request the ordinary lifecycle entry, which confirms an already-active bootstrap. A preload-only launch refuses the already-loaded targets instead of removing their performance features. The review builder produces an unsigned artifact; it neither forges a signer nor edits ZombieBuddy's saved decisions. Release signing and a real saved-selection startup still require author acceptance.

To remove this edition, close the game, undo its launcher setup with the same offline script, disable `PZ_Optimization`, and fully restart before removing the package. Disabling the checkbox alone cannot remove an agent already selected by the launcher. The legacy in-game updater/loose-file repair/uninstaller refuse that old workflow when the adapter profile is present and explain the managed-package action. Existing player settings and saves are not migrated or removed by the adapter.

The log must show `[pzopt/ZombieBuddy] active:` before gameplay. A refusal means no payload was registered: review the exact reason, including game/loader hash, foreign loose classes or targets loaded too early. A regular `Main.main` invocation reports the state and never installs late. A definition-time provenance mismatch deliberately stops that target definition with a clear preceding error rather than silently running part of the payload.

## Why native signatures and ZombieBuddy hooks survive

The JVM reads a native class from the reviewed, unmodified game JAR, obtains its original protection domain/signers, then invokes the adapter's non-retransformable `ClassFileTransformer`. Only that definition's class bytes change. No game certificate is removed or imitated.

The pinned official ZombieBuddy artifact uses a retransformable ByteBuddy transformer. JVM transformer groups run the adapter's non-retransformable replacement before ZombieBuddy's capable transformer, so ZombieBuddy's advice composes over the optimized definition. This ordering was tested with the actual official artifact; it is not inferred from the registration order. Retransformation reuses the first group's established result, retaining the optimized schema while ZombieBuddy reapplies its advice.

The managed AOT route excludes the package's non-adapter `pzopt` helpers from archiving. The adapter adds an empty private class attribute when defining those helpers; their fields and executable code remain unchanged. Without that barrier, JDK 25 eagerly resolves helper references to native types from the cache before even the first Java agent, preventing initial-definition optimization. Eligible JDK classes still use the AOT cache. Neither transformed native targets nor the non-adapter helpers are claimed archived. Other mods with their own AOT-linked native references still require separate compatibility checks.

The compatibility scanner applies its existing `ZombieBuddy` policy to the exact reviewed loader bytes even when installed outside a recognized mod directory or renamed. Actual source IDs remain in reports, explicit per-source user rules take precedence, and a canonical user `ZombieBuddy` rule carries over to aliases. A changed JAR receives no such alias. The scanner continues to detect unrelated patches; the adapter's own JAR is ignored only by exact package identity.

## Verification

```powershell
python scripts/test-zombiebuddy.py `
  --zombiebuddy-jar 'PATH_TO_REFERENCE/ZombieBuddy.jar' `
  --game-jar 'PATH_TO_GAME/projectzomboid.jar'
```

The isolated fixture creates and signs its own small native test JAR, never the game JAR. It verifies a new optimization field, a mod helper call, original native certificates/provenance, real ZombieBuddy advice installed before the adapter, and a subsequent retransformation. It also checks changed-game, changed-loader, foreign-origin and already-loaded-target refusals, identical package hashes from two builds, and an AOT record/use cycle using the donor's default `aotNoIndy` JVM options. The game JAR is a read-only type dependency for ZombieBuddy; game main, graphics and native methods are not called.

Passing these checks does not prove a complete PZ/ZombieBuddy launch, gameplay, native graphics, FPS or loading gains. Record the actual full-game acceptance and matched-workload benchmark results separately. No voxel, pixel-lighting, God Rays or other visual feature is excluded by the adapter; feature-level compatibility still depends on the port and those runtime checks.

`scripts/test-zombiebuddy-payload.py` additionally launches the early bootstrap followed by the unmodified official `Agent.premain`, then validates every class in an actual prepared package. It checks original native provenance/certificates and patch errors, and compares class initialization against an official-agent-only baseline. ZombieBuddy's own Lua warmup occurs in that baseline. The test uses isolated empty loader configuration and never calls the game's main method; saved-selection approval and graphical acceptance remain separate checks.

Pass `--aot` to that script to also obtain record/use launch arguments from production `ManagedAotCache.plan` and execute both phases with default `aotNoIndy` enabled. It copies the exact native JAR into its private build directory and keeps all cache output there. Static checks cover every packaged helper's schema and method code arrays, the native main-screen hooks, managed lifecycle refusal, real foreign-conflict detection, and refusal of the incorrect agent order.
