# Guided Halo CLI setup

The plugin packages only installation metadata, expected file hashes, authentication
adapters and licenses. Node.js and the official Halo CLI are downloaded on the Halo
server when an administrator clicks **Download and install** in the environment wizard.
Startup, status reads and CLI commands never start a download implicitly.

The detector inspects the server/container OS, CPU architecture, writable data directory,
compatible existing Node.js (22+) and verified managed CLI files. A configured
`CLAWBOT_NODE_EXECUTABLE` takes priority, followed by a verified managed runtime and a
compatible Node.js on PATH. The downloaded Node.js 22.23.5 executable supports Linux
(glibc), macOS and Windows on x64/arm64; musl needs an existing compatible runtime.
`runtimes.json` pins official artifact and executable SHA-256 values. Only the
current platform's executable is extracted, without npm, headers or install scripts.

`build.mjs` generates `distribution.json` from `package-lock.json` after `npm ci`.
It lists production non-optional packages from the official npm registry, their
SHA-512 integrity values and expected SHA-256 file hashes. The Java installer fetches
those tarballs directly, checks integrity before bounded extraction into a private
staging directory, verifies every installed file, then activates the distribution.
Neither npm nor package lifecycle scripts execute on the server. Verified tarballs
are cached for retries and repairs. Completed installs persist across restarts.

The downloaded upstream CLI is adapted at its single RuntimeContext constructor to
use `ManagedConfigStore`. The installer fails if the pinned upstream contract changes.
The keyring import uses a stub that fails closed. Tokens arrive through the first stdin
line, remain in process memory, and never enter argv, environment variables or files.
The remaining stdin retains the original command payload. Profile diagnostics work;
profile mutations return `CLI_AUTH_MANAGED` and use the plugin's binding screen.
An explicit `CLAWBOT_HALO_CLI_EXECUTABLE` retains the legacy external profile/keyring mode.

`npm run build` also creates a single-file CLI bundle for adapter tests; that bundle
and Node.js binaries are excluded from the plugin JAR. `./gradlew build` builds the
plugin, installer metadata and UI, and runs the Java, UI and adapter tests. To run the
real network installation and native execution test, set `CLAWBOT_SETUP_INTEGRATION=true`.
Licenses for Halo CLI, Node.js and dependencies are included and copied to the managed
installation. Initial installation needs access to nodejs.org and registry.npmjs.org.
