# -*- coding: utf-8 -*-
# M3.1/M3.2: TEST-GATE baseUrl/apiKey 变体生成器  用法: python make_gate.py <public_empty|local_empty|local_key>
import json, io, sys

variant = sys.argv[1]
cfg = json.load(io.open(r'G:\工作台\HaoAI\.test-work\config-backup.json', encoding='utf-8'))
for p in cfg['providers']:
    p['active'] = False
gate = {
    "id": "test-gate-0001",
    "name": "TEST-GATE",
    "baseUrl": "http://10.0.2.2:8802/v1",
    "model": "mock-model",
    "protocol": "openai_compat",
    "apiKey": "sk-test-1234",
    "contextLength": 128000,
    "maxTokens": 4096,
    "active": True,
    "models": [{"id": "mock-model", "vision": False, "tools": True, "reasoning": False, "contextLength": 128000, "maxTokens": 4096}]
}
if variant == "public_empty":
    gate["baseUrl"] = "https://api.example.com/v1/"
    gate["apiKey"] = ""
elif variant == "local_empty":
    gate["apiKey"] = ""
out = r'G:\工作台\HaoAI\.test-work\config-gate.json'
with io.open(out, 'w', encoding='utf-8') as f:
    json.dump(cfg, f, ensure_ascii=False, indent=1)
print("variant:", variant, "baseUrl:", gate["baseUrl"], "key:", repr(gate["apiKey"]))
