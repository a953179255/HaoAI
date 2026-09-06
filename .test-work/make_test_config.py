# -*- coding: utf-8 -*-
"""从 phone-backup/haoai.config.json 生成测试配置:
   1) 追加 mock provider (指向 PC LAN 8802) 并置 active
   2) 其余 provider 全部 active=false
   3) dream_provider -> mock, dream_idle_minutes -> 1 (R4.2 提速, 测后还原)
   输出: test-config.json
"""
import json, io, sys

SRC = "phone-backup/haoai.config.json"
DST = "test-config.json"

with io.open(SRC, "r", encoding="utf-8") as f:
    cfg = json.load(f)

MOCK_ID = "mock-test-0001"
MOCK = {
    "id": MOCK_ID,
    "name": "Mock测试(PC)",
    "baseUrl": "http://192.168.1.37:8802/v1",
    "model": "mock-model",
    "protocol": "openai_compat",
    "apiKey": "sk-test-mock",
    "contextLength": 128000,
    "maxTokens": 4096,
    "active": True,
    "models": [],
    "send_temperature": False, "temperature": 1.0,
    "send_top_p": False, "top_p": 1.0,
    "send_presence_penalty": False, "presence_penalty": 0.0,
    "send_frequency_penalty": False, "frequency_penalty": 0.0,
    "key_rotation": "ROUND_ROBIN", "apiKeyPool": [],
    "balance_enabled": False, "balance_api_path": "/credits",
    "balance_json_path": "data.total_usage",
}

providers = [p for p in cfg["providers"] if p["id"] != MOCK_ID]
for p in providers:
    p["active"] = False
providers.append(MOCK)
cfg["providers"] = providers

st = cfg.get("settings", {})
st["dream_provider"] = MOCK_ID
st["dream_idle_minutes"] = 1

with io.open(DST, "w", encoding="utf-8") as f:
    json.dump(cfg, f, ensure_ascii=False, separators=(",", ":"))

print("providers:", [(p["name"], p["active"]) for p in cfg["providers"]])
print("dream_provider:", st["dream_provider"], "idle_min:", st["dream_idle_minutes"])
