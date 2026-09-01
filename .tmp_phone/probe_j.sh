#!/system/bin/sh
ND="/data/app/~~zIX8tdCUZ8yStiaqMHTUhg==/com.haoai.agent-wElJvb0pVvolV9XCgK5wFw==/lib/arm64"
export LD_LIBRARY_PATH="$ND:/vendor/lib64"
export ADSP_LIBRARY_PATH="$ND"
exec "$ND/libllamaserver.so" -m /storage/emulated/0/MT2/MiniCPM5-1B-Q4_K_M.gguf --host 127.0.0.1 --port 8085 -c 4096 -t 6 -np 1 --no-webui --jinja -ngl 0
