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
// 壳子现在有三段脚本：内联的主逻辑 + 渲染器 md.js + 两端共用件 shared.js。
// 两边都要解析，也要一起扫 class —— 只扫 index.html 的话，别的文件挂的 class 就没人对账了。
const uiDir = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'ui');
const mdFile = path.join(uiDir, 'md.js');
const mdSrc = fs.existsSync(mdFile) ? fs.readFileSync(mdFile, 'utf8') : '';
const sharedFile = path.join(uiDir, 'shared.js');
const sharedSrc = fs.existsSync(sharedFile) ? fs.readFileSync(sharedFile, 'utf8') : '';
const scriptSrc = (html.match(/<script>([\s\S]*?)<\/script>/) || [, ''])[1];
const allSrc = scriptSrc + '\n' + mdSrc + '\n' + sharedSrc;
check(scriptSrc.length > 200, '取到了 <script> 里的内容', '长度 ' + scriptSrc.length);
check(mdSrc.length > 200, '渲染器 md.js 在（且被 index.html 引用）',
  '没找到 ui/md.js —— 页面会直接白屏');
check(/src="\/md\.js"/.test(html), 'index.html 里确实引了 /md.js');
/*
 * shared.js：S6 起两端共用件（esc）只存在这一份。两份 HTML 各自少了它就是白屏
 * （esc 满页都在用），所以引用与存在性都要挡。
 */
check(sharedSrc.length > 50, '共用件 shared.js 在', '没找到 ui/shared.js —— 两端 esc 会变未定义');
check(/src="\/shared\.js"/.test(html), 'index.html 里确实引了 /shared.js');
for (const [label, code] of [['主脚本', scriptSrc], ['md.js', mdSrc], ['shared.js', sharedSrc]]) {
  if (!code) continue;
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

// ---- 3b) 同一个 id 不许出现两次（HTML 里写死的 + JS 模板里注入的算同一份文档）----
/*
 * 为什么值得静态挡：`#pvClose` 曾在"浏览器预览页签"和"文件预览弹窗"里各有一个。
 * 页面自己的代码侥幸没事（弹窗那个用 `$('#dialog').querySelector(...)` 限了范围），
 * 但 `getElementById` / 任何通用 `querySelector('#pvClose')` 只会命中**文档里第一个** ——
 * 于是像素剧本点"关闭"实际点在另一个页签里那个 0 尺寸的按钮上，报"点不到"，
 * 而这类错在页面上看起来像"剧本写错了"，不像"产品有两个同名 id"。
 */
const idHits = new Map();
for (const m of html.matchAll(/id="([\w-]+)"/g)) idHits.set(m[1], (idHits.get(m[1]) || 0) + 1);
for (const m of html.matchAll(/id='([\w-]+)'/g)) idHits.set(m[1], (idHits.get(m[1]) || 0) + 1);
const dup = [...idHits].filter(([, n]) => n > 1).map(([i, n]) => i + '×' + n).sort();
check(dup.length === 0, '整份文档里每个 id 只出现一次',
  '重名：' + dup.join(', ') + '（getElementById 只认第一个，另一个永远点不到）');

// ---- 3c) 命令面板的每条入口都要真的存在 ----
/*
 * 上面那条 $#+id 只认 `$('#x')` 这种写法，而面板的目标是**数组里的字符串**
 * （'#duoBtn'、'#tabs button[data-t=role]'），扫不到。而"入口漂了"是面板独有的坏法：
 * 条目还在、搜得到、回车也按了，只是 toast 一句"找不到那个入口"——
 * 面板的价值就是"不用记入口在哪"，这一条坏等于那半个功能没了。
 * 后面几批（分屏 / 整理记忆 / 手动压缩 / 自动化页签）就是这么漏进面板的。
 */
const palSrc = (scriptSrc.match(/const PALSET=\[([\s\S]*?)\n\];/) || [, ''])[1];
const tabNames = new Set();
const tabBlock = (html.match(/<div class="tabs" id="tabs">([\s\S]*?)<\/div>/) || [, ''])[1];
for (const m of tabBlock.matchAll(/data-t="([\w-]+)"/g)) tabNames.add(m[1]);
const palBad = [];
let palRows = 0, palActs = 0;
for (const m of palSrc.matchAll(/\[\s*'([^']+)'(?:\s*,\s*'([^']*)')?/g)) {
  palRows++;
  const target = m[2] || '';
  if (!target || target[0] !== '#') continue;   // 函数=动作类；不以 # 开头=设置抽屉里的某一节（要 fetch 回来才知道）
  const id = (target.match(/^#([\w-]+)/) || [, ''])[1];
  if (!id || !presentIds.has(id)) palBad.push(m[1] + ' → ' + target);
}
for (const m of palSrc.matchAll(/palTabBtn\('(\w+)'\s*,\s*'#([\w-]+)'\)/g)) {
  palActs++;
  if (!tabNames.has(m[1])) palBad.push('动作要的页签 data-t=' + m[1] + ' 不存在');
  if (!presentIds.has(m[2])) palBad.push('动作要的 #' + m[2] + ' 不存在');
}
check(palRows >= 20, '命令面板抓到条目',
  '只抓到 ' + palRows + ' 条 —— 数组写法改了的话这条检查就形同虚设了');
check(palActs >= 2, '面板里"两步动作"的页签与按钮都能静态对上', '只对上 ' + palActs + ' 条');
check(palBad.length === 0, '命令面板每条入口都真的存在', '漂了：' + palBad.join('、'));

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
// 允许多段（/api/shell/open 这种）：只吃一段会把嵌套路由报成"陌生接口"
for (const m of allSrc.matchAll(/[/'"`](\/api\/[\w/]+)/g)) apiPaths.add(m[1].slice(1).replace(/\/+$/, ''));
check(apiPaths.size >= 6, '抓到前端调用的接口清单', [...apiPaths].join(', '));
const routes = new Set();
for (const m of server.matchAll(/"(\/[\w./-]+)"\s*->/g)) routes.add(m[1].slice(1));
const unknown = [...apiPaths].filter(p => !routes.has(p)).sort();
check(unknown.length === 0, '前端没有调到不存在的接口',
  '陌生接口：' + unknown.join(', ') + '\n         （服务端路由：' + [...routes].filter(r => r.startsWith('api/')).sort().join(', ') + '）');

// ---- 5.5) 整段前端 JS 必须能解析 ----
/*
 * 浏览器只在真的加载到那段脚本时才报语法错，而剧本可能压根没跑到那一步 ——
 * 于是"界面坏了"要等到有人手动打开页面才看得见。这里直接拿 V8 解析一遍整段 script。
 */
const jsBlocks = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]);
let jsBad = '';
for (const src of jsBlocks) {
  if (!src.trim()) continue;
  try { new Function(src); } catch (e) { jsBad = e.message; break; }
}
check(jsBlocks.length > 0, '抓到前端整段脚本', jsBlocks.length + ' 段');
check(jsBad === '', '整段前端 JS 能解析', jsBad);

/*
 * 手机网页端（phone.html）也是前端：它的脚本坏了，手机上只是"白屏 + 按钮点不动"，
 * 桌面这边一切正常。上一版就是被一次写入截断（localStorage 变成 localS…tem）坑掉的，
 * 现象是"配对按钮点了没反应"，查了两轮才想到去看 rect=0x0。
 */
let phoneBad = '', phonePages = 0, lanMissing = [], phoneShared = false;
const lanSrc = fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'kotlin', 'com', 'haoai', 'pc', 'Lan.kt'), 'utf8');
const lanRoutes = new Set([...lanSrc.matchAll(/"(\/lan\/[a-z]+)"/g)].map(m => m[1].slice(1)));
for (const f of fs.readdirSync(uiDir).filter(x => x.endsWith('.html') && x !== 'index.html')) {
  phonePages++;
  const pageSrc = fs.readFileSync(path.join(uiDir, f), 'utf8');
  if (/src="\/shared\.js"/.test(pageSrc)) phoneShared = true;
  for (const m of pageSrc.matchAll(/<script>([\s\S]*?)<\/script>/g)) {
    if (!m[1].trim()) continue;
    try { new Function(m[1]); } catch (e) { phoneBad = f + '：' + e.message; break; }
  }
  for (const q of new Set([...pageSrc.matchAll(/'(\/lan\/[a-z]+)'/g)].map(m => m[1]))) {
    if (!lanRoutes.has(q.slice(1))) lanMissing.push(f + ' 调了 ' + q);
  }
}
check(phonePages > 0, '抓到手机网页端', phonePages + ' 个非主页面');
check(phoneBad === '', '手机网页端 JS 能解析', phoneBad);
check(lanMissing.length === 0, '手机网页端没有调到不存在的 /lan 接口', lanMissing.join(', '));
check(phoneShared, '手机网页端也引了 /shared.js', 'phone 的 esc 会变未定义（原来两份各抄一份，S6 起只有一份）');
// phone 是从 **Lan 端点**出的（不是主服务）：那边不发 /shared.js 的话，
// 手机页的 esc 是未定义 —— 白屏发生在"还没配对"那一步，看起来像配对页坏了。
check(!phoneShared || /path == "\/shared\.js"/.test(lanSrc),
  'Lan 端点也发 /shared.js', 'phone 引了但 Lan.kt 没这条路由（手机页会白屏）');

/*
 * ---- 6.6) S6：每个面板一个对象，静态接线收进 init ----
 * 面板对象化是"动 UI 之前先分区"的那一步：状态、接线(init)、刷新(render) 要在一个地方看得见。
 * 这条判据防的是回退 —— 哪天有人图省事又在顶层加一个 `function loadXxx()`，
 * 或者把 `Xxx.init();` 删了（接线全哑，按钮点了没反应但语法照样绿）。
 */
const PANELS = ['Palette', 'Term', 'Ck', 'Lan', 'Pv', 'Git', 'Files', 'Mem', 'Skills', 'Cron', 'Presets', 'Hooks', 'Cfg'];
const panelBad = [];
for (const p of PANELS) {
  if (!new RegExp('const ' + p + ' = \\(\\(\\) => \\{').test(scriptSrc)) panelBad.push(p + ' 没收成对象');
  if (!new RegExp('^' + p + '\\.init\\(\\);', 'm').test(scriptSrc)) panelBad.push(p + '.init() 没被调（静态接线全哑）');
}
check(panelBad.length === 0, '13 个面板都是对象且 init 被调', panelBad.join('、'));
// 页签分派必须走面板对象（改回裸函数名 = 又一份顶层散函数）
const tabBlockSrc = (scriptSrc.match(/querySelectorAll\('#tabs button'\)\.forEach\(b=>b\.onclick=\(\)=>\{([\s\S]*?)\n\}\);/) || [, ''])[1];
const tabCalls = [...tabBlockSrc.matchAll(/([A-Z]\w+)\.render\(\)/g)].map(m => m[1]);
check(tabCalls.length >= 8, '页签分派走面板对象的 render', '只抓到 ' + tabCalls.length + ' 处：' + tabCalls.join(','));
check(!/\b(loadGit|loadFiles|loadLan|loadCron|loadPresets|loadHooks|loadMems|loadDigest)\(\)/.test(tabBlockSrc),
  '页签分派里没有残留的裸面板函数调用', '还指着顶层旧名：' + tabBlockSrc.match(/\b(load\w+)\(\)/g));

/*
 * ---- 6.7) 面板私有成员不许在对象外裸用 ----
 * S6 把状态与函数关进了对象之后，核心代码里再写 `palOpen` 这种裸名字 = 运行时
 * ReferenceError，而且炸的位置常常在没人想到的地方：实测残留一个 `palOpen` 在
 * Escape 处理里，整个 hideSlash 被跳过，@ 列表关不掉 —— 是 ui-at 的判据红了才看见的。
 * 静态挡：每个面板对象区间（`const X = (() => {` … `X.init();`）之外，
 * 它的私有名字只许以 `Panel.成员` 的形式出现，裸用一律报出来。
 */
const panelLeaks = [];
for (const p of PANELS) {
  const start = scriptSrc.indexOf('const ' + p + ' = (() => {');
  const end = scriptSrc.indexOf('\n' + p + '.init();', start);
  if (start < 0 || end < 0) continue;   // 上一条判据已经报过"没收成对象"
  const span = scriptSrc.slice(start, end);
  // 对象内的列 0 声明 = 私有成员（init 里的缩进声明是 init 局部，核心看不见）
  const priv = new Set();
  for (const m of span.matchAll(/^(?:function|let|const|var)\s+([A-Za-z_$][\w$]*)/gm)) priv.add(m[1]);
  priv.delete(p);   // IIFE 头那行 `const X = (() => {` 不是成员
  for (const name of priv) {
    const re = new RegExp('\\b' + name + '\\b', 'g');
    for (const m of scriptSrc.matchAll(re)) {
      if (m.index >= start && m.index < end) continue;
      const before = scriptSrc[m.index - 1] || '';
      if (before === '#' || before === "'" || before === '"' || before === '.') continue;   // $('#palList') 这类 id/属性位
      const lineStart = scriptSrc.lastIndexOf('\n', m.index) + 1;
      const line = scriptSrc.slice(lineStart, scriptSrc.indexOf('\n', m.index));
      if (/^\s*(\/\/|\*|\/\*)/.test(line)) continue;   // 注释里提名字不算用
      panelLeaks.push(p + '.' + name + ' 在对象外裸用：' + line.trim().slice(0, 90));
    }
  }
}
check(panelLeaks.length === 0, '面板私有成员没有在对象外裸用（裸用 = 运行时 ReferenceError）',
  panelLeaks.join('\n         '));

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
  } catch (e) {
    // 剧本里最常见的两种"JSON 自己坏了"：note 里用了 ASCII 双引号（整行被截断）、
    // 正则写了 \s \d（JSON 里必须 \\s \\d）。两种都是本轮自己踩的，指个方向省一轮排查。
    badSteps.push(f + ' 不是合法 JSON：' + e.message +
      '（note 里的引号请用「」，正则里的 \\s \\d 要写成 \\\\s \\\\d）');
    continue;
  }
  (Array.isArray(arr) ? arr : []).forEach((st, i) => {
    /*
     * 一步同时写两个动词 = 后面那个被静默丢掉。shot.js 的执行链是 if/else 按
     * goto→sleep→eval→click… 取第一个，而它打印的标签走另一条顺序，
     * 所以"eval + sleep"会只睡不判、日志还印成「断言」。
     * v0.79.0 那份订阅源剧本就这么空转了四步，34 条判据全绿而截图里没有被测的东西。
     */
    const used = ['goto', 'sleep', 'eval', 'click', 'key', 'type', 'shot', 'viewport'].filter(v => st && st[v] !== undefined);
    if (used.length > 1) badSteps.push(f + ' 第 ' + (i + 1) + ' 步同时写了 ' + used.join(' + ') + '，只会执行第一个');
    if (!st || typeof st.eval !== 'string') return;
    // 剧本里两种写法都有：一条表达式，或"点一下再回个话"的语句串。
    // 只按表达式解析会把后者全判成错（实测四份老剧本被冤枉），所以两种都试。
    let parsed = true;
    try { new Function('return (' + st.eval + ')'); }
    catch (e1) { try { new Function(st.eval); } catch (e2) { parsed = false; } }
    if (!parsed) badSteps.push(f + ' 第 ' + (i + 1) + ' 步 eval 两种写法都解析不过');
    // 正则里的 `\s` `\d` 在 JSON 字符串里是非法转义（要写成 `\\s`），
    // 而 note 里用 ASCII 双引号会把整行 JSON 截断 —— 这两处都是本轮自己踩的，
    // 症状是"剧本跑到那一步突然什么都读不出来"。JSON.parse 在上面已经拦住了，
    // 这里只是让**报错落在写剧本的那一刻**。
    if (typeof st.note === 'string' && /(^|[^\\])"/.test(st.note)) {
      try { JSON.stringify({ n: st.note }); } catch (e) { badSteps.push(f + ' 第 ' + (i + 1) + ' 步 note 里有裸引号'); }
    }
  });
}
check(stepFiles.length > 0, '抓到像素剧本清单', stepFiles.length + ' 份');
check(badSteps.length === 0, '每份剧本里的 eval 都是合法 JS', badSteps.join('\n         '));

/*
 * ---- 6.4) 只打印不断言的剧本 = 没有判据（只数得清"退出了 0"）----
 * 2026-09-29 给 audit-steps.sh 加上"判据数为 0 单独标红"之后全量跑了一遍：
 * 43 份里有 31 份一条 `must` 都没有 —— 它们把 JSON 打印给人读，
 * 于是界面坏了、按钮没了、卡不重画了，它都照样"绿"。这是整个验收体系最大的一笔债。
 * 一次补完不现实，所以做成**棘轮**：只许变好。每补完一份就把下面的上限改小，
 * 补不完不许加新的零判据剧本。
 */
const ZERO_GATE_CEILING = 0;    // #99 清零：30 份零判据剧本已全部补上（本批只补判据，不许再出现新的零判据剧本）
const zeroGate = [];
for (const f of stepFiles) {
  let arr;
  try { arr = JSON.parse(fs.readFileSync(path.join(stepDir, f), 'utf8')); } catch (e) { continue; }
  const gates = (Array.isArray(arr) ? arr : []).reduce((s, st) => s + ((st && st.must) || []).length, 0);
  if (gates === 0) zeroGate.push(f.replace(/\.json$/, ''));
}
check(zeroGate.length <= ZERO_GATE_CEILING,
  '一条判据都没有的剧本数（棘轮，只许降）',
  '现在 ' + zeroGate.length + ' 份，上限 ' + ZERO_GATE_CEILING +
  '。补判据的顺序建议按最近改过的面：' + zeroGate.slice(0, 8).join(' ') + ' …');

/*
 * ---- 6.5) 分屏之后"看得见的会话"有两种：左格（.on）与右格（.duo）----
 * 凡是写死 `.view.on` 的选择器，分屏时就把右边那一格整个漏掉 —— 这类漏法在单屏下
 * 测不出来（那时 on 就是唯一可见的），只有并排看的时候才显形。所以逐条对账：
 * 要么同一条选择器也管 .duo，要么进这份"刻意只作用于输入对象"的白名单。
 */
const DUO_TARGET_ONLY = [
  '.view.on .card.ask'   // 键盘决定审批卡：只该打在"这句话发到哪一条"上
];
const duoBlind = [];
for (const m of scriptSrc.matchAll(/['"`][^'"`\n]*\.view\.on[^'"`\n]*['"`]/g)) {
  const sel = m[0].slice(1, -1);
  if (sel.includes('.duo')) continue;
  if (DUO_TARGET_ONLY.some(t => sel.includes(t))) continue;
  duoBlind.push(sel);
}
check(duoBlind.length === 0, '按 .view.on 取元素的地方要么也管 .duo，要么在白名单里',
  '分屏时会漏掉右栏：' + duoBlind.join(', '));

console.log(fails ? '\nUI 自检失败 ' + fails + ' 项' : '\nUI 自检全部通过');
process.exit(fails ? 1 : 0);
