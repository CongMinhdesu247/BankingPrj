#!/usr/bin/env bash
set -euo pipefail
action=${1:?action}; directory=${2:?directory}; port=${3:?port}; controller=${4:?controller port}
[[ "$directory" =~ ^/tmp/banking-kafka-[a-f0-9]{32}$ && "$port" =~ ^[0-9]{4,5}$ && "$controller" =~ ^[0-9]{4,5}$ ]] || exit 2
((port>=1024 && port<=65535 && controller>=1024 && controller<=65535 && port!=controller)) || exit 2
kafka=/opt/kafka_2.13-4.1.2
config="$directory/server.properties"
owns_pid() {
  [[ -f "$directory/kafka.pid" ]] || return 1
  local process_id; process_id=$(cat "$directory/kafka.pid")
  [[ "$process_id" =~ ^[0-9]+$ && -r "/proc/$process_id/cmdline" ]] || return 1
  tr '\0' ' ' < "/proc/$process_id/cmdline" | grep -Fq -- "$config"
}
case "$action" in
  start)
    if owns_pid; then exit 0; fi
    if ss -Hltn "sport = :$port" | grep -q .; then echo 'Kafka port occupied' >&2; exit 3; fi
    mkdir -p "$directory"
    if [[ ! -f "$config" ]]; then
      cat > "$config" <<EOF
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@127.0.0.1:$controller
listeners=PLAINTEXT://127.0.0.1:$port,CONTROLLER://127.0.0.1:$controller
advertised.listeners=PLAINTEXT://127.0.0.1:$port
listener.security.protocol.map=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
controller.listener.names=CONTROLLER
inter.broker.listener.name=PLAINTEXT
log.dirs=$directory/data
num.partitions=3
auto.create.topics.enable=false
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
group.initial.rebalance.delay.ms=0
log.retention.hours=24
EOF
    fi
    grep -Fxq "advertised.listeners=PLAINTEXT://127.0.0.1:$port" "$config" || exit 4
    if [[ ! -f "$directory/data/meta.properties" ]]; then
      cluster=$("$kafka/bin/kafka-storage.sh" random-uuid)
      "$kafka/bin/kafka-storage.sh" format -t "$cluster" -c "$config" > "$directory/format.log" 2>&1
    fi
    mkdir -p "$directory/logs"
    LOG_DIR="$directory/logs" KAFKA_OPTS='-Djava.net.preferIPv4Stack=true' KAFKA_HEAP_OPTS='-Xms256m -Xmx512m' nohup "$kafka/bin/kafka-server-start.sh" "$config" > "$directory/console.log" 2>&1 < /dev/null &
    echo $! > "$directory/kafka.pid"
    # Let the launcher exec Java before the short-lived WSL command returns.
    sleep 1
    kill -0 "$(cat "$directory/kafka.pid")" || { tail -20 "$directory/console.log" >&2; exit 5; }
    ;;
  stop)
    if owns_pid; then
      process_id=$(cat "$directory/kafka.pid"); kill -TERM "$process_id"
      for _ in $(seq 1 100); do if ! owns_pid; then exit 0; fi; sleep 0.1; done
      if owns_pid; then kill -KILL "$process_id"; fi
    fi
    ;;
  ready) "$kafka/bin/kafka-topics.sh" --bootstrap-server "127.0.0.1:$port" --list ;;
  *) echo 'Unsupported Kafka runtime action' >&2; exit 2 ;;
esac
