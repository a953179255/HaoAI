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
// 壳子现在有两段脚本：内联的主逻辑 + 单独一个 md.js（渲染器）。两边都要解析，
// 也要两边一起扫 class —— 只扫 index.html 的话，渲染器挂的 class 就没人对账了。
const uiDir = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'ui');
const mdFile = path.join(uiDir, 'md.js');
const mdSrc = fs.existsSync(mdFile) ? fs.readFileSync(mdFile, 'utf8') : '';
const scriptSrc = (html.match(/<script>([\s\S]*?)<\/script>/) || [, ''])[1];
const allSrc = scriptSrc + '\n' + mdSrc;
check(scriptSrc.length > 200, '取到了 <script> 里的内容', '长度 ' + scriptSrc.length);
check(mdSrc.length > 200, '渲染器 md.js 在（且被 index.html 引用）',
  '没找到 ui/md.js —— 页面会直接白屏');
check(/src="\/md\.js"/.test(html), 'index.html 里确实引了 /md.js');
for (const [label, code] of [['主脚本', scriptSrc], ['md.js', mdSrc]]) {
  try { new Function(code); check(true, label + ' 语法能过 V8 解析'); }
  catch (e) { check(false, label + ' 语法能过 V8 解析', e.message); }
}

// ---- 2) JS 里挂的 class 必须在 CSS 里有规则 ----
const styleSrc = (html.match(/<style>([\s\S]*?)<\/style>/) || [, ''])[1];
const cssClasses = new Set();
for (const m of styleSrc.matchAll(/\.([a-zA-Z][\w-]*)/g)) cssClasses.add(m[1]);

/*
 * 只认真正的 class 名。模板串里有两种插值写法：
 *   class="it${cur?' cur':''}"      —— ${ 开始
 *   class="tick '+(d.ok?'ok':'bad')+' —— '+ 开始（这段是老代码里的字符串拼接）
 * 上一版正则把后一种的 `'+(d.ok?'ok':'bad')+` 整段当成了 class 名，于是报出两条
 * "缺规则"的假故障。量具一报假故障，真故障就没人信了，所以静态段只取到第一个
 * 插值符为止，抠出来的 token 再过一次标识符形状。
 */
const IDENT = /^[a-zA-Z][\w-]*$/;
const used = new Set();
const addStaticClasses = src => {
  for (const m of src.matchAll(/class="([^"]*)"/g)) {
    const stat = m[1].split(/\$\{|\+'/)[0];
    stat.split(/\s+/).forEach(t => { if (IDENT.test(t)) used.add(t) });
  }
};
addStaticClasses(scriptSrc); addStaticClasses(mdSrc);
for (const m of allSrc.matchAll(/classList\.(?:add|remove|toggle)\(\s*'([\w-]+)'/g)) used.add(m[1]);

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

// ---- 5) 前端调用的接口必须在服务端路由表里 ----
/*
 * 这份清单**不能**再手抄：手抄的那份只记得起老接口，新加的一律报"陌生接口"，
 * 而这个检查真正要防的恰恰是"前端打了一个服务端没注册的地址"（那是 404，
 * 页面上表现为按钮点了没反应）。所以直接去 Server.kt 的路由表里抓。
 */
const apiPaths = new Set();
for (const m of allSrc.matchAll(/[/'"`](\/api\/[\w]+)/g)) apiPaths.add(m[1].slice(1));
check(apiPaths.size >= 6, '抓到前端调用的接口清单', [...apiPaths].join(', '));
const routes = new Set();
for (const m of server.matchAll(/"(\/[\w./-]+)"\s*->/g)) routes.add(m[1].slice(1));
const unknown = [...apiPaths].filter(p => !routes.has(p)).sort();
check(unknown.length === 0, '前端没有调到不存在的接口',
  '陌生接口：' + unknown.join(', ') + '\n         （服务端路由：' + [...routes].filter(r => r.startsWith('api/')).sort().join(', ') + '）');

// ---- 6) 像素剧本里的每条 eval 必须能解析 ----
/*
 * 三条真事故换来的：eval 步骤里一个 `a:+(x)` 之类的笔误，要等整轮浏览器起完、
 * 跑到那一步才报 SyntaxError —— 一次浪费两分钟，而"跑到第 5 步就红"看起来像产品坏了。
 * 语法检查是纯本地的，放在这里最便宜。顺带验 JSON 本身能解析。
 */
const stepDir = path.join(__dirname, 'steps');
let stepFiles = [];
try { stepFiles = fs.readdirSync(stepDir).filter(f => f.endsWith('.json')); } catch (e) { stepFiles = []; }
const badSteps = [];
for (const f of stepFiles) {
  let arr;
  try {
    arr = JSON.parse(fs.readFileSync(path.join(stepDir, f), 'utf8'));
  } catch (e) { badSteps.push(f + ' 不是合法 JSON：' + e.message); continue; }
  (Array.isArray(arr) ? arr : []).forEach((st, i) => {
    if (!st || typeof st.eval !== 'string') return;
    // 剧本里两种写法都有：一条表达式，或"点一下再回个话"的语句串。
    // 只按表达式解析会把后者全判成错（实测四份老剧本被冤枉），所以两种都试。
    let parsed = true;
    try { new Function('return (' + st.eval + ')'); }
    catch (e1) { try { new Function(st.eval); } catch (e2) { parsed = false; } }
    if (!parsed) badSteps.push(f + ' 第 ' + (i + 1) + ' 步 eval 两种写法都解析不过');
  });
}
check(stepFiles.length > 0, '抓到像素剧本清单', stepFiles.length + ' 份');
check(badSteps.length === 0, '每份剧本里的 eval 都是合法 JS', badSteps.join('\n         '));

console.log(fails ? '\nUI 自检失败 ' + fails + ' 项' : '\nUI 自检全部通过');
process.exit(fails ? 1 : 0);
