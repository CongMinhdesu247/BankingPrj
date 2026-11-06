#!/usr/bin/env bash
# Records run_in_terminal and appmod-* tool calls as JSONL without arguments or secrets.
INPUT=$(cat)
case "$INPUT" in
  *'"tool_name":"'*) ;;
  *) exit 0 ;;
esac
TOOL_NAME="${INPUT#*\"tool_name\":\"}"
TOOL_NAME="${TOOL_NAME%%\"*}"
case "$TOOL_NAME" in
  run_in_terminal|appmod-*) ;;
  *) exit 0 ;;
esac
case "$INPUT" in
  *'"session_id":"'*) ;;
  *) exit 0 ;;
esac
SESSION_ID="${INPUT#*\"session_id\":\"}"
SESSION_ID="${SESSION_ID%%\"*}"
if [ -z "$SESSION_ID" ] || [ "${#SESSION_ID}" -gt 100 ]; then exit 0; fi
case "$SESSION_ID" in
  *[!A-Za-z0-9_-]*) exit 0 ;;
esac
HOOKS_DIR=".github/modernize/java-upgrade/hooks"
mkdir -p "$HOOKS_DIR"
printf '{"tool_name":"%s","session_id":"%s"}\n' "$TOOL_NAME" "$SESSION_ID" >> "$HOOKS_DIR/${SESSION_ID}.json"
