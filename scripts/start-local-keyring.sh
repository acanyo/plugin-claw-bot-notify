#!/bin/sh
# Run inside the dedicated test container. Password is derived in memory; never printed or saved.
set -eu
export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/clawbot-dbus
export XDG_RUNTIME_DIR=/tmp/clawbot-runtime
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"
if ! dbus-send --session --dest=org.freedesktop.DBus --type=method_call --print-reply / org.freedesktop.DBus.ListNames >/dev/null 2>&1; then
  rm -f /tmp/clawbot-dbus
  dbus-daemon --session --fork --address="$DBUS_SESSION_BUS_ADDRESS" --print-pid > /tmp/clawbot-dbus.pid
fi
node -e 'if(!process.env.CLAWBOT_MASTER_KEY) process.exit(1); process.stdout.write(require("node:crypto").createHash("sha256").update(process.env.CLAWBOT_MASTER_KEY+":clawbot-cli-keyring").digest("hex")+"\n")' | gnome-keyring-daemon --unlock --components=secrets --daemonize > /tmp/clawbot-keyring.log 2>&1
