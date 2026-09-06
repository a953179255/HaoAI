# -*- coding: utf-8 -*-
# M4: glm 切回 active(真实模型驱动 agent) + 追加 mock MCP 服务器(Streamable HTTP, 明文内网)
import json, io

cfg = json.load(io.open(r'G:\工作台\HaoAI\.test-work\config-backup.json', encoding='utf-8'))
for p in cfg['providers']:
    p['active'] = (p['name'] == 'glm-5.3-flash')
cfg['mcp_servers'] = [{
    "name": "mock-mcp",
    "id": "mock-mcp-1",
    "kind": "http",
    "url": "http://10.0.2.2:8802/mcp",
    "enabled": True,
    "approvalLevel": "write",
    "allowPlaintext": True
}]
with io.open(r'G:\工作台\HaoAI\.test-work\config-m4.json', 'w', encoding='utf-8') as f:
    json.dump(cfg, f, ensure_ascii=False, indent=1)
print("config-m4.json written; active:", [p['name'] for p in cfg['providers'] if p.get('active')])
