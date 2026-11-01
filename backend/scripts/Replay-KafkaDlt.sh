#!/usr/bin/env bash
set -euo pipefail
# Operator-only localhost tool. Preserve the original event ID/key/value; never manufacture a new event ID.
port=${1:?port}; topic=${2:?original topic}; consumer=${3:?fraud or notification}; partition=${4:?partition}; offset=${5:?DLT offset}
[[ "$port" =~ ^[0-9]{4,5}$ && "$partition" =~ ^[0-9]+$ && "$offset" =~ ^[0-9]+$ ]] || exit 2
[[ "$topic" =~ ^banking\.(dev|test\.[a-f0-9]{32}|demo\.[a-f0-9]{32})\.(transfer\.completed|transfer\.failed|fraud\.detected)\.v1$ ]] || exit 2
[[ "$consumer" == notification || ( "$consumer" == fraud && "$topic" == *.transfer.completed.v1 ) ]] || exit 2
kafka=/opt/kafka_2.13-4.1.2
record=$("$kafka/bin/kafka-console-consumer.sh" --bootstrap-server "127.0.0.1:$port" --topic "$topic.$consumer.dlt" \
  --partition "$partition" --offset "$offset" --max-messages 1 --timeout-ms 5000 --property print.partition=true --property print.offset=true --property print.key=true --property key.separator=$'\t')
[[ "$record" != *$'\n'* ]] || { echo 'Multiline/unvalidated poison record requires manual inspection; not replayed.' >&2; exit 3; }
IFS=$'\t' read -r recorded_partition recorded_offset key value <<< "$record"
[[ "$recorded_partition" == "Partition:$partition" && "$recorded_offset" == "Offset:$offset" ]] || { echo 'Requested DLT coordinate was not retained; no replay.' >&2; exit 3; }
[[ "$key" =~ ^[a-f0-9-]{36}$ && "$value" == \{*\} ]] || { echo 'Only single-line JSON event envelopes are eligible.' >&2; exit 3; }
printf '%s\t%s\n' "$key" "$value" | "$kafka/bin/kafka-console-producer.sh" --bootstrap-server "127.0.0.1:$port" --topic "$topic" \
  --property parse.key=true --property key.separator=$'\t' --producer-property enable.idempotence=true --producer-property acks=all
echo 'Replayed one envelope. Verify receipt/effect and DLT lag; an unfixed poison event will return to DLT.' >&2
