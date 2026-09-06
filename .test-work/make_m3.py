# -*- coding: utf-8 -*-
# M3: 在配置里追加 TEST-GATE provider(指向 PC mock 10.0.2.2:8802)并切 active;不动原有 3 个
import json, io

PATH_SRC = r'G:\工作台\HaoAI\.test-work\config-backup.json'
# run-as 推送后的文件在 /data/local/tmp,由 shell cp 进 app;这里只生成推送文件
cfg = json.load(io.open(PATH_SRC, encoding='utf-8'))
for p in cfg['providers']:
    p['active'] = False
cfg['providers'].append({
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
})
with io.open(r'G:\工作台\HaoAI\.test-work\config-m3.json', 'w', encoding='utf-8') as f:
    json.dump(cfg, f, ensure_ascii=False, indent=1)
print("config-m3.json written, providers:", [p['name'] for p in cfg['providers']])
