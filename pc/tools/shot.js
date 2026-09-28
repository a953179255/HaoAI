// 真像素验收：把页面跑在一个无头 Edge 里，按步骤点/打字，每一步存一张图。
//
// 为什么要有这份：应用内的浏览器面板没打开时，截图工具拿不到可见表而拒绝工作，
// 于是"界面到底长什么样"退化成"我推理它应该长什么样" —— 而界面缺陷恰恰是
// 结构检查全绿也漏掉的那一类（重叠、纯黑、假玻璃、挂不上样式）。
// 这份脚本不依赖任何第三方包：Node 24 自带 WebSocket，直连 CDP。
//
// 用法：
//   node pc/tools/shot.js --out <目录> --url <页面> --steps <steps.json>
// steps.json 是一个数组，元素：
//   {"url":"..."}                 导航（通常只有第一步有）
//   {"goto":"#stream .it"}        等某个选择器出现（默认再等 300ms 让动画落定）
//                                 加 "visible":true 才要求它真的有尺寸（hidden 区块里的元素 querySelector 也命中）
//   {"sleep":1200}                等时间
//   {"eval":"JS 表达式"}           在页面里跑一段（结果会打印；用于断言 DOM 事实）
//                                 加 "must":["a","b.c"]：这些字段不为真就把整轮判成失败
//   {"type":{"sel":"#box","text":"…","enter":true}}  往输入框打字并按发送
//   {"click":"选择器"}             点一下（用真实的鼠标事件序列，不用 el.click()；0 尺寸直接报错）
//   {"shot":"文件名"}              存一张 PNG
//   {"viewport":"phone"|"desktop"|[{"width":..,"height":..,"mobile":true}]}
//                                 中途换设备度量（手机剧本要回电脑页勾开关时必须撤掉覆盖）

const fs = require('fs');
const os = require('os');
const path = require('path');
const {spawn} = require('child_process');

function arg(name, dflt) {
  const i = process.argv.indexOf('--' + name);
  return i > 0 && process.argv[i + 1] ? process.argv[i + 1] : dflt;
}

const OUT = path.resolve(arg('out', '.'));
const PORT = parseInt(arg('port', '9333'), 10);
/** 实际用上的调试端口（端口被占时会往旁边挪）；连浏览器、读页签都得用它。 */
let PORT_ACTUAL = PORT;
const W = parseInt(arg('width', '1500'), 10);
const H = parseInt(arg('height', '930'), 10);
/** --mobile：按手机视口量（手机网页端用得上，桌面窗口宽度会被最小值顶回去）。 */
const MOBILE = process.argv.includes('--mobile');
/*
 * SHOT_DPR=2 把截图按 2 倍采样。为什么要有这个开关：12px 的加粗中文在 dpr=1 的 PNG 里
 * 会被 ClearType 子像素边缘 + 缩放采样糊成"重影"，我在验收记忆里看到过两次，
 * 每次都怀疑是排版坏了 —— 而 `getClientRects()` 量出来加粗那段与后面那句之间 gap=0，
 * 布局根本没重叠。判"看不清"之前先按高倍率重拍一张，别把量具的锯齿算成产品的缺陷。
 */
const DPR = Math.max(1, Number(process.env.SHOT_DPR || (MOBILE ? 2 : 1)) || 1);
const steps = JSON.parse(fs.readFileSync(path.resolve(arg('steps', '')), 'utf8'));
const EDGE = arg('edge', 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe');

fs.mkdirSync(OUT, {recursive: true});
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'haoai-shot-'));

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

/**
 * 端口被上一轮没退干净的 Edge 占着时，探 `/json/version` 会**成功**——
 * 于是脚本以为自己的浏览器起来了，其实连的是别人的：断言在新标签里跑，
 * 截图却可能落到它攒下的旧标签上（实测截到过上一轮的会话，版本号都不对）。
 * 所以开火之前先探，占了就往旁边挪，挪不动才报错。
 */
async function pickPort(want) {
  for (let p = want; p < want + 6; p++) {
    try {
      await fetch('http://127.0.0.1:' + p + '/json/version', {signal: AbortSignal.timeout(600)});
    } catch (e) { return p;   // 没人听 —— 这个端口是我们的
    }
  }
  throw new Error('调试端口 ' + want + '~' + (want + 5) + ' 都被占着（多半是上一轮的无头 Edge 没退）');
}

async function startBrowser() {
  PORT_ACTUAL = await pickPort(PORT);
  const proc = spawn(EDGE, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    // 无头页默认 visibilityState=hidden，Chrome 会**推迟**音视频的加载：
    // 实测同一个 <video> 在首屏（页面还"可见"那会儿）解码得出 160x120，
    // 刷新之后就不动了 —— 那是量具的条件，不是产品的缺陷。下面这两样把页面钉成"在前台"。
    '--disable-backgrounding-occluded-windows', '--disable-renderer-backgrounding',
    '--disable-background-timer-throttling',
    '--window-size=' + W + ',' + H,
    '--user-data-dir=' + profile,
    '--remote-debugging-port=' + PORT_ACTUAL,
    'about:blank',
  ], {stdio: ['ignore', 'ignore', 'pipe']});
  proc.stderr.on('data', d => { if (process.env.SHOT_VERBOSE) process.stderr.write(d); });
  for (let i = 0; i < 60; i++) {
    try {
      const r = await fetch('http://127.0.0.1:' + PORT_ACTUAL + '/json/version');
      const j = await r.json();
      // /json/version 的键是大写开头的（"Browser"），写成 j.browser 会一直判不到 —— 
      // 症状是"Edge 明明起了却报没起来"，而 DevTools listening 那行就在 stderr 上。
      if (j.browser || j.Browser) return proc;
    } catch (e) { /* 还没起来 */ }
    await sleep(250);
    if (!proc.connected && proc.exitCode) break;
  }
  try { proc.kill(); } catch (e) { /* 已经退了 */ }
  throw new Error('无头 Edge 没起起来（检查端口 ' + PORT + ' 是否被占用）');
}

let ws, seq = 0;
const waiting = new Map();
let sessionId = '';

function send(method, params, ms) {
  return new Promise((resolve, reject) => {
    const id = ++seq;
    // 每条命令都要能超时：量具挂死和界面挂死长得一模一样，
    // 上一版就是没有任何日志与超时，白等了五分钟才发现是脚本自己卡在握手上了。
    //
    // 但断言步的预算必须**大于**这一步自己声明的等待时间：ui-iab 那条"等预览起来"
    // 循环 60 秒，而命令 20 秒就被砍，于是永远只能看到"CDP 命令超时"，
    // 拿不到步里准备的那句失败原因（`ok:false, note:…`）—— 一个把诊断吃掉的量具。
    const timer = setTimeout(() => {
      if (waiting.has(id)) { waiting.delete(id); reject(new Error('CDP 命令超时：' + method)) }
    }, ms || 20_000);
    waiting.set(id, {
      resolve: v => { clearTimeout(timer); resolve(v) },
      reject: e => { clearTimeout(timer); reject(e) }
    });
    const msg = {id, method, params: params || {}};
    // 只有走 browser endpoint + Target.attachToTarget(flatten) 时才带 sessionId；
    // 直连 page endpoint 时多塞一个空 sessionId 会让所有命令静默没反应。
    if (sessionId) msg.sessionId = sessionId;
    if (process.env.SHOT_VERBOSE) console.log('  > ' + method);
    ws.send(JSON.stringify(msg));
  });
}

async function attach(url) {
  const info = await (await fetch('http://127.0.0.1:' + PORT_ACTUAL + '/json/version')).json();
  ws = new WebSocket(info.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('连不上 CDP')); });
  ws.onmessage = ev => {
    const m = JSON.parse(ev.data);
    const w = waiting.get(m.id);
    if (w) { waiting.delete(m.id); m.error ? w.reject(new Error(JSON.stringify(m.error))) : w.resolve(m.result); }
  };
  /*
   * 用 browser endpoint 自己开 target，而不是去 /json/list 里捡现成的 page：
   * 无头 Edge 起来以后 /json/list 里未必有 type:'page' 的项（表现是"没有可用的 page target"，
   * 而浏览器明明活着），捡谁也不如自己开一个确定。
   */
  const {targetId} = await send('Target.createTarget', {url});
  const attached = await send('Target.attachToTarget', {targetId, flatten: true});
  sessionId = attached.sessionId || '';
  console.log('  target=' + targetId + ' session=' + (sessionId || '(空!)'));
  if (!sessionId) throw new Error('attachToTarget 没给 sessionId：' + JSON.stringify(attached));
  // 不发 Page.enable / Runtime.enable：这两个域的命令不是必需的
  // （Runtime.evaluate 与 Page.captureScreenshot 不依赖 enable），
  // 而在无头 Edge 上 enable 会撞上渲染器还没就绪的窗口期，表现是命令永久不回。
  await sleep(900);
  // 把页面钉成"有焦点/可见"，否则音视频元素永远不动（见上面那串启动参数）
  await send('Emulation.setFocusEmulationEnabled', {enabled: true})
    .catch(e => console.log('  焦点模拟没开成：' + e.message));
  /*
   * --window-size=390 在桌面 Chrome/Edge 上会被最小窗口宽度顶回去（实测 innerWidth 500+），
   * 于是"按手机宽度排版"这条判据量的其实还是桌面宽度 —— 一个安静的假阴性。
   * 手机网页端要用 Emulation.setDeviceMetricsOverride 才量得准（顺带把 deviceScaleFactor/mobile 摆对）。
   */
  if (MOBILE || DPR > 1) {
    await send('Emulation.setDeviceMetricsOverride', {width: W, height: H, deviceScaleFactor: DPR, mobile: MOBILE})
      .then(() => console.log('  ' + (MOBILE ? '手机' : '桌面') + '视口 ' + W + 'x' + H + ' @dpr' + DPR))
      .catch(e => console.log('  视口覆盖没开成（判据会失真）：' + e.message));
  }
}

async function evalJs(expr, ms) {
  const r = await send('Runtime.evaluate', {expression: expr, returnByValue: true, awaitPromise: true}, ms);
  if (r.exceptionDetails) throw new Error('页面里的 JS 抛错：' + JSON.stringify(r.exceptionDetails.exception || r.exceptionDetails));
  return r.result.value;
}

/**
 * 断言步的命令超时：按这一步自己声明的等待预算推算（脚本里写 `Date.now()-t0>60000`
 * 就是打算等 60 秒），再加 15 秒余量。不这样算的话，凡是"没等到"的场合
 * 都只会得到一句 CDP 超时，而那一步本来准备好的失败原因永远打不出来。
 */
function evalMs(expr) {
  let max = 0;
  for (const m of String(expr).matchAll(/Date\.now\(\)\s*-\s*\w+\s*[<>]\s*(\d{4,})/g))
    max = Math.max(max, parseInt(m[1], 10));
  return max ? max + 15_000 : 20_000;
}

async function shot(name) {
  // 抓屏偶发不回（页面刚被大改之后尤其容易），重试一次再认输：
  // 不然一次抖动就把整轮验收变成"失败了"，而失败原因和界面缺陷长得一样。
  let r = null;
  for (let i = 0; i < 3 && !r; i++) {
    try { r = await send('Page.captureScreenshot', {format: 'png'}) }
    catch (e) { if (i === 2) throw e; await sleep(700) }
  }
  const f = path.join(OUT, name.endsWith('.png') ? name : name + '.png');
  fs.writeFileSync(f, Buffer.from(r.data, 'base64'));
  console.log('  图 -> ' + f);
}

/** 真实鼠标事件序列：el.click() 会跳过 hover/:active 那一套，样式问题就看不出来了。 */
async function click(sel) {
  /*
   * 先滚到中间再量坐标：消息流是滚动容器，目标在视口外时 getBoundingClientRect
   * 给的是负数 y，而 CDP 的鼠标事件按**视口**坐标算 —— 于是"点到了"其实点在空中，
   * 而 hover 才显形的东西（消息操作行）就永远验不到。上一轮就是这么误判成
   * "hover 不生效"的。
   */
  const found = await evalJs(`!!document.querySelector(${JSON.stringify(sel)})`);
  if (!found) throw new Error('找不到元素：' + sel);
  await evalJs(`document.querySelector(${JSON.stringify(sel)}).scrollIntoView({block:'center',behavior:'instant'})`);
  await sleep(120);
  const box = await evalJs(`(()=>{const r=document.querySelector(${JSON.stringify(sel)}).getBoundingClientRect();
    return JSON.stringify({x:r.x+r.width/2,y:r.y+r.height/2,w:r.width,h:r.height})})()`);
  const {x, y, w, h} = JSON.parse(box);
  /*
   * 零尺寸的元素点不到：鼠标按在它"中心"其实是按在别的东西上，而前面的步骤一切正常，
   * 于是最后一屏截图和真实行为对不上。宁可在这里报错，也不要往下走一个假的成功。
   */
  if (!(w > 0 && h > 0)) throw new Error('要点但看不见（0 尺寸）：' + sel);
  for (const type of ['mouseMoved', 'mousePressed', 'mouseReleased'])
    await send('Input.dispatchMouseEvent', {type, x, y, button: 'left',
      clickCount: type === 'mouseMoved' ? 0 : 1, buttons: type === 'mouseReleased' ? 0 : 1});
}

async function type(step) {
  await evalJs(`(()=>{const e=document.querySelector(${JSON.stringify(step.sel)});
    e.focus();e.value=${JSON.stringify(step.text)};
    // 程序化改 value 之后光标位置各家浏览器不一致，而 @ 补全这类要看"光标前那段"，
    // 所以显式把光标放到末尾，模拟真的打完字的状态
    const L=e.value.length;try{e.setSelectionRange(L,L)}catch(_){}
    e.dispatchEvent(new Event('input',{bubbles:true}))})()`);
  if (step.enter !== false)
    await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13})
        .catch(() => evalJs(`document.querySelector(${JSON.stringify(step.sel)})
            .dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))`));
}

const KEYCODE = {Tab: 9, Enter: 13, Escape: 27, ArrowDown: 40, ArrowUp: 38, ArrowLeft: 37, ArrowRight: 39};

/** 真按键：Tab / 方向键这类要走"默认行为"的路径，用 eval 派发合成事件是测不出来的。 */
const MOD_BIT = {alt: 1, ctrl: 2, meta: 4, shift: 8};
/** mods 是给快捷键本身用的（Ctrl+Shift+P 这种）：不带修饰位的话 Chrome 收到的就是裸的 P。 */
function modMask(mods) {
  let m = 0;
  for (const x of (mods || [])) m |= (MOD_BIT[String(x).toLowerCase()] || 0);
  return m;
}
async function key(name, mods) {
  const vk = KEYCODE[name] || (name.length === 1 ? name.toUpperCase().charCodeAt(0) : 0);
  const mask = modMask(mods);
  // 单个字符要带 text：不带的话 Chrome 只发 keydown 不产生"打字"，
  // 于是"在输入框里按 y 该打出 y 还是该决定审批"这种判据根本测不出来。
  const ch = name.length === 1;
  for (const t of ['keyDown', 'keyUp'])
    await send('Input.dispatchKeyEvent', {
      type: t, key: name, code: ch ? 'Key' + name.toUpperCase() : name,
      modifiers: mask,
      windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk,
      ...(ch && t === 'keyDown' ? {text: name, unmodifiedText: name} : {}),
    });
}

/**
 * 等到元素出现。visible=true 时要的是"看得见的出现"：
 * hidden 的 section 里的按钮用 querySelector 一样找得到，于是 waitFor 报"出现"、
 * click 把鼠标点在空中（getBoundingClientRect 全 0），现象和"界面坏了"一模一样。
 * 上一轮手机网页端就是这么把两轮排查送掉的。
 */
async function waitFor(sel, timeoutMs, visible) {
  const test = visible
    ? `(()=>{const e=document.querySelector(${JSON.stringify(sel)});if(!e)return false;
        const r=e.getBoundingClientRect();return r.width>0&&r.height>0})()`
    : `!!document.querySelector(${JSON.stringify(sel)})`;
  const until = Date.now() + (timeoutMs || 12_000);
  while (Date.now() < until) {
    if (await evalJs(test)) return true;
    await sleep(120);
  }
  return false;
}

/** 判据里哪些声明字段没为真。返回值是给人看的字符串列表。 */
function mustMiss(val, must) {
  if (!must || !must.length) return [];
  let v = val;
  if (typeof v === 'string') {
    const t = v.trim();
    if (t.startsWith('{') || t.startsWith('[')) { try { v = JSON.parse(t) } catch (_) {} }
  }
  const out = [];
  for (const p of must) {
    const got = p.split('.').reduce((o, k) => (o == null ? undefined : o[k]), v);
    if (!got) out.push(p + ' = ' + JSON.stringify(got));
  }
  return out;
}

(async () => {
  const proc = await startBrowser();
  let code = 0;
  const misses = [];
  try {
    await attach(steps[0] && steps[0].url ? steps[0].url : arg('url', 'about:blank'));
    console.log('  已连上无头 Edge，共 ' + steps.length + ' 步');
    for (const [i, s] of steps.entries()) {
      if (s.url && s === steps[0]) continue;
      const label = s.goto ? '等 ' + s.goto : s.shot ? '图 ' + s.shot : s.eval ? '断言' :
        s.click ? '点 ' + s.click : s.key ? '按键 ' + s.key : s.type ? '打字' : s.viewport ? '视口 ' + s.viewport
          : s.sleep ? '睡 ' + s.sleep + 'ms' : '?';
      console.log('  [' + (i + 1) + '/' + steps.length + '] ' + label);
      if (s.goto) {
        const ok = await waitFor(s.goto, s.timeout || 12_000, s.visible === true);
        console.log('  等 ' + s.goto + (s.visible ? '（要看得见）' : '') + ' -> ' + (ok ? '出现' : '没出现'));
        if (!ok) { code = 1; misses.push('第 ' + (i + 1) + ' 步没等到 ' + s.goto); }
        await sleep(250);
      } else if (s.sleep) await sleep(s.sleep);
      else if (s.eval) {
        const got = await evalJs(s.eval, evalMs(s.eval));
        console.log('  断言 ' + (JSON.stringify(got) || ''));
        /*
         * "断言打印出来是 false，整轮还是 PASS" —— 打印不是判据。
         * 所以按用例显式声明哪些字段必须为真（must 支持点号路径），
         * 没声明的步一律只打印，兼容那些"期望值本来就是 false"的老步。
         */
        for (const miss of mustMiss(got, s.must)) {
          console.log('  !! 第 ' + (i + 1) + ' 步判据没成立：' + miss);
          misses.push('第 ' + (i + 1) + ' 步 ' + miss);
          code = 1;
        }
      }
      else if (s.viewport) {
        /*
         * 剧本中途换尺寸：手机那条链路要"先在手机上、再回电脑上勾个开关、再回手机"，
         * 而设备度量覆盖是跟着标签走的 —— 不撤掉的话，回到桌面页量的还是 390px，
         * 窄栏布局把右侧页签整个藏起来，于是"点不到「工具」页签"看着像产品坏了。
         */
        const v = s.viewport === 'phone' ? {width: W, height: H, mobile: true}
          : s.viewport === 'desktop' ? {width: 1500, height: 930, mobile: false}
          : {width: s.viewport.width || W, height: s.viewport.height || H, mobile: !!s.viewport.mobile};
        await send('Emulation.setDeviceMetricsOverride',
          {width: v.width, height: v.height, deviceScaleFactor: v.mobile ? Math.max(2, DPR) : DPR, mobile: v.mobile});
        console.log('  视口 -> ' + v.width + 'x' + v.height + (v.mobile ? '（手机）' : ''));
        await sleep(300);
      }
      else if (s.click) { await click(s.click); await sleep(s.after || 350); }
      else if (s.key) { await key(s.key, s.mods); await sleep(s.after || 250); }
      else if (s.type) { await type(s.type); await sleep(s.after || 350); }
      else if (s.shot) await shot(s.shot);
    }
    /*
     * 收尾再喊一次。中间那行 `!!` 太容易被忽略：v0.67.0 有一份剧本第 14 步的判据
     * 写成在 Rect 上取 `scrollWidth`（永远是 undefined → 永远 false），整轮其实是红的，
     * 而我把它 `| tail` 之后只看了打印出来的数值就当通过了。
     * 退出码是对的，**管道会把退出码换成 tail 的** —— 所以最后一行必须自己把结论说出来。
     */
    if (misses.length) console.log('x 判据没过 ' + misses.length + ' 处：' + misses.join(' | '));
    else console.log('全部判据通过（' + steps.length + ' 步）');
  } catch (e) {
    console.log('  失败：' + e.message);
    try { await shot('failure'); } catch (_) {}
    code = 1;
  } finally {
    try { ws && ws.close(); } catch (_) {}
    proc.kill();
    /*
     * 每次跑都要给无头 Edge 一个全新的 user-data-dir，一天二十次就是二十个目录躺在 %TEMP%。
     * Edge 退出后 Windows 还会占着它一会儿，所以重试几轮；删不掉也不改退出码
     * （收尾删不动 ≠ 验收失败，上一版就因为这个把全绿的一轮报成非零）。
     */
    if (process.env.KEEP_PROFILE) {
      await sleep(300);
    } else {
      for (let i = 0; i < 12; i++) {
        await sleep(250);
        try { fs.rmSync(profile, {recursive: true, force: true}); break } catch (_) {}
      }
    }
  }
  process.exit(code);
})();
