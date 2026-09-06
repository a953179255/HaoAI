#!/bin/sh
# 最小 stdio MCP mock:行 JSON,零 fork(纯内置参数展开,规避沙箱 can't fork)
while IFS= read -r line; do
  case "$line" in
    *'"id"'*)
      tail_part=${line#*'"id":'}
      idnum=${tail_part%%,*}
      idnum=${idnum%%\}*}
      case "$line" in
        *initialize*)
          printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"stdio-mock","version":"1.0"}}}\n' "$idnum"
          ;;
        *tools/list*)
          printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"ping","description":"return pong","inputSchema":{"type":"object","properties":{}}}]}}\n' "$idnum"
          ;;
        *tools/call*)
          printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"stdio-pong"}]}}\n' "$idnum"
          ;;
      esac
      ;;
  esac
done
