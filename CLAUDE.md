# MeTM

Man-in-the-middle demo using plain Java sockets (Alice -> Trudy -> Bob). Standard Maven layout:

```
pom.xml
src/main/java/com/herrcult69/metm/{Alice,Bob,Trudy}.java   (package com.herrcult69.metm)
```

Ports: Alice -> Trudy on 9000, Trudy -> Bob on 9002. Java release 11 (see `pom.xml`'s
`maven.compiler.source`/`target`), no dependencies.

## Building (do this automatically after any code/pom change)

1. Windows has no `mvn`/`java` on PATH. Don't waste time retrying `mvn` natively; if `mvn -v` fails, use WSL.
2. Build in WSL (distro `Ubuntu-Restored`, has Maven 3.6.3 + JDK 21). From Git Bash on Windows:

```sh
MSYS_NO_PATHCONV=1 wsl -d Ubuntu-Restored -- bash -lc 'cd /mnt/p/AntiGravity/FinalYear/Project_CS2/MeTM && mvn -B -q clean package'
```

   (`wsl --cd` with a Windows path fails; `cd /mnt/p/...` inside `bash -lc` works. Output of `wsl -l` is UTF-16; pipe through `tr -d '\0'`.)
3. Success = `target/metm-1.0-SNAPSHOT.jar` and `target/classes/com/herrcult69/metm/*.class` exist.
4. Run (each in its own terminal, in this order):
   `java -cp target/classes com.herrcult69.metm.Bob`, then `...Trudy`, then `...Alice`.

## Conventions

- New classes go under `src/main/java/com/herrcult69/metm/`; tests under `src/test/java/...`.
- Never commit `target/` (already in .gitignore).
