// 一个最小的真 MCP 服务器（stdio / 换行分隔 JSON-RPC 2.0），用来验收 PC 端的 MCP 客户端。
//
// 为什么要有这份：MCP 那套协议没法靠"读代码"确认接对了 —— 握手字段、通知与回包混在
// 同一条流里、tools/list 的 schema 形状，任何一处错了都是"工具表是空的"这种没有堆栈的故障。
// 拿真协议跑一遍才算验收。它同时把 tools/call 的入参写进 --mark 指定的文件，
// 于是测试能证明"这一次调用真的到了外部进程"，而不是客户端自己编了个返回值。
//
//   node pc/tools/mock-mcp.js --mark /tmp/mcp-hit.txt
const fs = require('fs');

const mark = (() => {
  const i = process.argv.indexOf('--mark');
  return i > 0 ? process.argv[i + 1] : null;
})();

const TOOLS = [{
  name: 'echo',
  description: '把传进去的一句话原样带回来，并记一笔到磁盘上。',
  inputSchema: {
    type: 'object',
    properties: { text: { type: 'string', description: '要带回的话' } },
    required: ['text'],
  },
}];

function send(obj) { process.stdout.write(JSON.stringify(obj) + '\n'); }
// 主动推一条通知：客户端必须按 id 挑回包、把通知跳过，否则工具结果会变成一团乱码
function notify(method, params) { send({ jsonrpc: '2.0', method, params }); }

const rl = require('readline').createInterface({ input: process.stdin });
rl.on('line', (line) => {
  line = line.trim();
  if (!line) return;
  let m;
  try { m = JSON.parse(line); } catch (e) { return; }
  if (m.id === undefined) return;                       // 通知：不需要回
  if (m.method === 'initialize') {
    return send({
      jsonrpc: '2.0', id: m.id,
      result: {
        protocolVersion: '2024-11-05',
        capabilities: { tools: {} },
        serverInfo: { name: 'mock-mcp', version: '0.1' },
      },
    });
  }
  if (m.method === 'tools/list') {
    notify('notifications/message', { level: 'info', data: '列出工具' });
    return send({ jsonrpc: '2.0', id: m.id, result: { tools: TOOLS } });
  }
  if (m.method === 'tools/call') {
    const text = (m.params.arguments || {}).text || '';
    if (mark) fs.appendFileSync(mark, text + '\n');
    return send({
      jsonrpc: '2.0', id: m.id,
      result: { content: [{ type: 'text', text: '外部工具收到：' + text }], isError: false },
    });
  }
  send({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: '没这个办法：' + m.method } });
});
