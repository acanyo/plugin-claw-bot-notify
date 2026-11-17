#!/bin/sh
# Starts the existing dedicated local test instance and its CLI credential service.
set -eu
script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
if ! docker info >/dev/null 2>&1; then
  if command -v orbctl >/dev/null 2>&1; then orbctl start; fi
fi
docker inspect clawbot-m1-smoke >/dev/null
mkdir -p /tmp/clawbot-m1-runtime
config=/tmp/clawbot-m1-runtime/application.properties
if [ ! -f "$config" ]; then
  printf '%s\n' 'server.reactive.session.cookie.name=CLAWBOT_TEST_SESSION' 'halo.security.basic-auth.disabled=false' > "$config"
  chmod 600 "$config"
fi
docker start clawbot-m1-smoke >/dev/null
if docker exec clawbot-m1-smoke test -x /usr/local/bin/halo; then
  docker cp "$script_dir/start-local-keyring.sh" clawbot-m1-smoke:/opt/clawbot/start-keyring.sh >/dev/null
  docker exec clawbot-m1-smoke sh /opt/clawbot/start-keyring.sh
fi
attempt=0
until curl -fsS --max-time 2 http://localhost:18091/actuator/health >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 30 ]; then
    printf '%s\n' '测试 Halo 启动超时，请查看 docker logs clawbot-m1-smoke。' >&2
    exit 1
  fi
  sleep 1
done
printf '%s\n' '测试站点已启动：http://localhost:18091/console/clawbot' 'AI 模型配置：http://localhost:18091/console/ai-foundation/providers'
