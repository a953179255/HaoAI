# -*- coding: utf-8 -*-
# M4.4: 追加 stdio mock MCP 服务器(kind=stdio, 命令走沙箱 /workspace)
import json, io

cfg = json.load(io.open(r'G:\工作台\HaoAI\.test-work\config-m4.json', encoding='utf-8'))
mcp = [s for s in cfg.get('mcp_servers', []) if s.get('id') != 'mock-mcp-1']
mcp.append({
    "name": "stdio-mock",
    "id": "stdio-mock-1",
    "kind": "stdio",
    "command": "sh /workspace/stdio-mock.sh",
    "enabled": True,
    "approvalLevel": "write"
})
cfg['mcp_servers'] = mcp
with io.open(r'G:\工作台\HaoAI\.test-work\config-m44.json', 'w', encoding='utf-8') as f:
    json.dump(cfg, f, ensure_ascii=False, indent=1)
print("config-m44.json:", [s['name'] for s in mcp])
