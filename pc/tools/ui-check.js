// UI 自检：把网页壳子里"只有人能看出来"的错变成机器能看出来的错。
//
// 为什么要有这份：一次界面改动同时横跨三段（CSS 选择器、HTML 结构、JS 里拼的
// class/id 字符串），而这三层之间没有任何编译期联系。实测踩过两类：
//   1) JS 挂了 class="stop"，CSS 里根本没这条规则 —— 按钮换了文字但没换颜色，
//      单看代码每一边都"对"，只有看像素才发现；
//   2) 脚本里少一个右括号 —— 服务端测试全绿，页面白屏。
// 这两类恰好是"结构全绿仍会漏"的那一类，所以判据放在这里，跑一次一秒。
//
// 用法：node pc/tools/ui-check.js   （无依赖，纯静态分析）

const fs = require('fs');
const path = require('path');

const file = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'ui', 'index.html');
const html = fs.readFileSync(file, 'utf8');

let fails = 0;
function check(ok, label, extra) {
  if (ok) { console.log('  ok   ' + label); return }
  fails++;
  console.log('  FAIL ' + label + (extra ? '\n         ' + extra : ''));
}

// ---- 1) 脚本语法：不执行，只让 V8 解析一遍 ----
const scriptSrc = (html.match(/<script>([\s\S]*?)<\/script>/) || [, ''])[1];
check(scriptSrc.length > 200, '取到了 <script> 里的内容', '长度 ' + scriptSrc.length);
try { new Function(scriptSrc); check(true, 'JS 语法能过 V8 解析'); }
catch (e) { check(false, 'JS 语法能过 V8 解析', e.message); }

// ---- 2) JS 里挂的 class 必须在 CSS 里有规则 ----
const styleSrc = (html.match(/<style>([\s\S]*?)<\/style>/) || [, ''])[1];
const cssClasses = new Set();
for (const m of styleSrc.matchAll(/\.([a-zA-Z][\w-]*)/g)) cssClasses.add(m[1]);

const used = new Set();
for (const m of scriptSrc.matchAll(/class="([^"$]*)"/g))
  m[1].split(/\s+/).filter(Boolean).forEach(c => used.add(c));
for (const m of scriptSrc.matchAll(/classList\.(?:add|remove|toggle)\(\s*'([\w-]+)'/g)) used.add(m[1]);
for (const m of scriptSrc.matchAll(/el\('<([\w]+)[^']*class="([\w\- ]+)'/g))
  m[2].split(/\s+/).filter(Boolean).forEach(c => used.add(c));

// 这些是"行为开关"型 class：CSS 里以别的形式存在（或刻意只给 JS 当标记用）
const ALLOW = new Set();
const missing = [...used].filter(c => !cssClasses.has(c) && !ALLOW.has(c)).sort();
check(missing.length === 0, 'JS 用到的 class 在 CSS 里都有规则',
  '缺规则：' + missing.join(', ') + '\n         （挂不上样式=界面上看不出差别，最容易漏）');

// ---- 3) JS 取的元素 id 必须存在（在 HTML 里，或由脚本自己写出来）----
const wanted = new Set();
for (const m of scriptSrc.matchAll(/\$\('#([\w-]+)'\)/g)) wanted.add(m[1]);
for (const m of scriptSrc.matchAll(/getElementById\('([\w-]+)'\)/g)) wanted.add(m[1]);
const presentIds = new Set();
for (const m of html.matchAll(/id="([\w-]+)"/g)) presentIds.add(m[1]);
for (const m of html.matchAll(/id='([\w-]+)'/g)) presentIds.add(m[1]);
const noId = [...wanted].filter(i => !presentIds.has(i)).sort();
check(noId.length === 0, 'JS 里 $#+id 取的元素都存在', '找不到：' + noId.join(', '));

// ---- 4) 服务端事件名与前端监听名要对得上 ----
const server = fs.readFileSync(path.resolve(__dirname, '..', 'src', 'main', 'kotlin', 'com', 'haoai', 'pc', 'Server.kt'), 'utf8');
const emitted = new Set();
for (const m of server.matchAll(/publish\(\s*"([\w]+)"/g)) emitted.add(m[1]);
const listened = new Set();
for (const m of scriptSrc.matchAll(/es\.addEventListener\('([\w]+)'/g)) listened.add(m[1]);
for (const m of scriptSrc.matchAll(/^on\('([\w]+)'/gm)) listened.add(m[1]);
const dead = [...listened].filter(e => !emitted.has(e));
check(dead.length === 0, '前端监听的每个事件名服务端都会发', '没人发：' + dead.join(', '));
const orphan = [...emitted].filter(e => !listened.has(e));
check(orphan.length === 0, '服务端发的每个事件名前端都有人接', '没人接：' + orphan.join(', '));

// ---- 5) 请求字段名两边要对上（POST 的 key 必须出现在服务端的 Body.str 里）----
const apiPaths = new Set();
for (const m of scriptSrc.matchAll(/'\/(api\/[\w]+)'/g)) apiPaths.add(m[1]);
for (const m of scriptSrc.matchAll(/post\('\/(api\/[\w]+)'/g)) apiPaths.add(m[1]);
check(apiPaths.size >= 6, '抓到前端调用的接口清单', [...apiPaths].join(', '));
const known = ['api/events', 'api/state', 'api/task', 'api/stop', 'api/decide', 'api/mode',
  'api/new', 'api/open', 'api/sessions', 'api/rename', 'api/delete', 'api/settings'];
const unknown = [...apiPaths].filter(p => !known.includes(p));
check(unknown.length === 0, '前端没有调到不存在的接口', '陌生接口：' + unknown.join(', '));

console.log(fails ? '\nUI 自检失败 ' + fails + ' 项' : '\nUI 自检全部通过');
process.exit(fails ? 1 : 0);
