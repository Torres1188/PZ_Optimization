# B42.22.2 source validation

Use Python 3.9+ and JDK 25 or newer. The compiler always emits ordinary Java 25
bytecode with `--release 25`. The game JAR must have SHA-256
`3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287`.
Run from the repository root; paths containing spaces are supported on Windows
and Linux. Each output must be a new directory below this checkout's `build/`.

```text
python scripts/port-validation/compile-payload.py --game-jar "<game>/projectzomboid.jar" --output build/b4222-classes
```

The result includes `classes/`, the shipped-target stock comparison under
`stock/`, `javac.args`, compiler logs, and `compile-inventory.json` with source
and class hashes. Compilation neither installs files nor starts the game.
Protected native reporting classes remain in the game JAR at runtime; their
explicit stock exclusions are recorded. The legacy MainScreenState source is
not a replacement for B42.22.2's native integrity/version methods.

For the complete source checks, supply the original donor's compiled classes
as a baseline and the five ASM 9.8 modules (`asm`, `asm-tree`, `asm-analysis`,
`asm-util`, `asm-commons`) in a directory searched recursively:

```text
python scripts/port-validation/compile-payload.py --game-jar "<game>/projectzomboid.jar" --output build/b4222-validated --validate --asm-dir "<ASM 9.8 directory>" --baseline-payload "<original donor classes>"
```

The original review baseline is commit
`2f75296ea7e64f2fa0c236eb80d87f4060173ffa`, compiled against this same game JAR.
Validation compiles the strict tools, checks all member/access/type references,
compares Config declarations/defaults and the guarded NPC method to that
baseline, tests adversarial null-guard cases, runs the upstream native-method
fingerprint audit with its unchanged allow file, verifies every class definition
and native signer with initialization disabled, and runs the changed cache,
scratch-isolation and managed-AOT CPU fixtures. Exact commands and dependency
hashes are retained in the receipt.

The existing checks can also be run separately:

```text
python scripts/bytecode-audit.py --classes build/b4222-classes/classes --stock build/b4222-classes/stock
python scripts/port-validation/verify-jvm.py --payload build/b4222-classes/classes --game-jar "<game>/projectzomboid.jar" --output build/b4222-jvm
```

These are source and JVM checks. The definition verifier uses its own validation
agent and explicitly does not establish real ZombieBuddy transformer
composition, graphical/native gameplay, multiplayer correctness or measured
performance. Run the separate adapter test and qualify the assembled package
before release; a compile receipt always records `releaseQualified: false`.
