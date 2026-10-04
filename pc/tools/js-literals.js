/*
 * js-literals.js —— 给一份 JS 源码算"每个下标是不是字面量里的一段文字"。
 *
 * 为什么单独一个文件：ui-check.js 的"面板私有名不许裸用"这条判据要跳过字面量里的
 * 文字（'/api/kb' 里的 kb、/(html|table|svg)/ 里的 table、跨行模板串里的
 * `<table class="kb-docs">`），而这类"看起来像标识符的文字"恰恰是假故障的主要来源。
 * 假故障一多，真故障就没人看 —— 所以这段扫描值得单独写、单独自测。
 *
 * 自测：node tools/js-literals.js（自带断言，坏了直接非 0 退出）。
 */
'use strict';

const WS = ' \t\r\n';
/** 往前找第一个有意义的字符（用于判断这个 / 是正则还是除法）。 */
function prevMeaningful(src, i) {
  let j = i - 1;
  while (j >= 0 && WS.includes(src[j])) j--;
  return j >= 0 ? src[j] : '';
}
/** `/` 前面是这些字符/单词时，它才是正则的开始（否则是除法）。 */
function regexCanFollow(src, i) {
  let j = i - 1;
  while (j >= 0 && WS.includes(src[j])) j--;
  if (j < 0) return true;
  const c = src[j];
  if ('(,=:[!&|?{};+-*%<>~^'.includes(c)) return true;
  // 前一个 token 是标识符/数字/字符串结尾/右括号 → 除法
  if (/[\w$)"']/.test(c)) {
    // 但 return / typeof / case / in / of 这类关键字后面跟的是正则
    let k = j;
    while (k >= 0 && /[\w$]/.test(src[k])) k--;
    const word = src.slice(k + 1, j + 1);
    return ['return', 'typeof', 'case', 'in', 'of', 'do', 'else', 'delete', 'void'].includes(word);
  }
  return false;
}

/**
 * 一次遍历同时标两类"看起来像标识符的文字"：
 *   tpl —— 跨行模板串的字面文字段（${ … } 里面是代码，标 0）；
 *   com —— 注释（// 与  块注释，含不以 * 开头的续行）。
 * 为什么合在一次遍历里：两类都依赖"字符串/正则要先认出来才不会被带偏"的同一套扫描，
 * 分写两份迟早各自烂掉。ui-check 的"私有名不许裸用"两条都要用。
 */
function scanFlags(src) {
  const flags = new Uint8Array(src.length);
  const com = new Uint8Array(src.length);
  const len = src.length;
  let i = 0;
  while (i < len) {
    const c = src[i], n = src[i + 1];
    if (c === '/' && n === '/') { const s = i; while (i < len && src[i] !== '\n') i++; for (let k = s; k < i; k++) com[k] = 1; continue }
    if (c === '/' && n === '*') {
      const s = i; i += 2;
      while (i < len && !(src[i] === '*' && src[i + 1] === '/')) i++;
      i = Math.min(i + 2, len); for (let k = s; k < i; k++) com[k] = 1; continue;
    }
    if (c === "'" || c === '"') {          // 普通字符串里的反引号不算模板串开始
      const q = c; i++;
      while (i < len && src[i] !== q) { if (src[i] === '\\') i++; if (src[i] === '\n') break; i++ }
      i++; continue;
    }
    if (c === '/' && regexCanFollow(src, i)) {
      // 正则字面量：里面常见 `/['"]/` 这种引号，不认成正则就会把后面整段带偏
      let j = i + 1, cls = false, closed = false;
      for (; j < len; j++) {
        const d = src[j];
        if (d === '\\') { j++; continue }
        if (d === '\n') break;
        if (d === '[') cls = true;
        else if (d === ']') cls = false;
        else if (d === '/' && !cls) { closed = true; break }
      }
      if (closed) { i = j + 1; while (i < len && /[gimsuyd]/.test(src[i])) i++; continue }
    }
    if (c === '`') {
      let j = i + 1;
      while (j < len) {
        const d = src[j];
        if (d === '\\') { flags[j] = 1; j += 2; continue }
        if (d === '`') break;
        if (d === '$' && src[j + 1] === '{') {   // 插值：这段是代码，交给下一层扫
          let bal = 1; j += 2;
          while (j < len && bal > 0) {
            const e = src[j];
            if (e === '{') bal++;
            else if (e === '}') bal--;
            else if (e === '/' && src[j + 1] === '/') { const s = j; while (j < len && src[j] !== '\n') j++; for (let k = s; k < j; k++) com[k] = 1; continue }
            else if (e === '/' && src[j + 1] === '*') {
              const s = j; j += 2;
              while (j < len && !(src[j] === '*' && src[j + 1] === '/')) j++;
              j = Math.min(j + 2, len); for (let k = s; k < j; k++) com[k] = 1; continue
            }
            else if (e === "'" || e === '"' || e === '`') {
              const s = e; j++;
              while (j < len && src[j] !== s && src[j] !== '\n') j++;
            } else if (e === '/' && regexCanFollow(src, j)) {
              let k = j + 1, cls = false, closed = false;
              for (; k < len; k++) {
                const g = src[k];
                if (g === '\\') { k++; continue }
                if (g === '\n') break;
                if (g === '[') cls = true; else if (g === ']') cls = false;
                else if (g === '/' && !cls) { closed = true; break }
              }
              if (closed) { j = k; continue }
            }
            j++;
          }
          continue;
        }
        flags[j] = 1; j++;
      }
      i = j + 1; continue;
    }
    i++;
  }
  return {tpl: flags, com};
}
function templateFlags(src) { return scanFlags(src).tpl }
function commentFlags(src) { return scanFlags(src).com }

/* ---- 自测：这些断言就是"扫描器自己没坏"的证据 ---- */
if (require.main === module) {
  let bad = 0;
  const yes = (label, cond) => { if (!cond) { bad++; console.log('FAIL ' + label) } else console.log('ok   ' + label) };
  const a = 'const x = `<table class="t">\n<tbody>${kb.name}</tbody>`;';
  const fa = templateFlags(a);
  yes('模板串第一行的文字被标出来', fa[a.indexOf('class="t"')] === 1);
  yes('模板串第二行的 <table> 类文字被标出来（跨行）', fa[a.indexOf('table class')] === 1);
  yes('${…} 里的代码不标（那是真引用）', fa[a.indexOf('kb.name')] === 0);
  yes('模板串结束之后的代码不标', fa[a.indexOf(';')] === 0);

  const b = "const s = 'it\\'s a kb'; const kb = 1;";
  yes('单引号串里的撇号不会把后面整行带偏', templateFlags(b)[b.indexOf('const kb')] === 0);

  const c = '// 这里提一下 `kb` 是注释\nlet zz = 1;';
  yes('注释里的反引号不开启模板串', templateFlags(c)[c.indexOf('let zz')] === 0);

  const d = 'const t = `a${ `b${kb}` }c`;';
  yes('嵌套模板串不炸（长度不变）', templateFlags(d).length === d.length);

  // 真事故 2026-10-03：Kbs 的详情模板没被标出来，因为前面有一行 re=/['"]/ 把扫描器带偏了
  const e = 'const re = /[\'"]/g;\nconst html = `<table class="kb-docs"></table>`;';
  const fe = templateFlags(e);
  yes('正则字面量里的引号不开启字符串（模板串照样标出来）', fe[e.indexOf('<table class')] === 1);

  const g = 'const q = 8 / 2;\nconst html = `<div>table</div>`;';
  yes('除号不误判成正则（后面的模板串照样标出来）', templateFlags(g)[g.indexOf('<div>table')] === 1);

  const h = 'if (x) return /a"b/.test(s);\nconst t = `<b>table</b>`;';
  yes('return 之后的正则认出来（模板串照样标）', templateFlags(h)[h.indexOf('<b>table')] === 1);

  // ---- commentFlags：块注释续行不以 * 开头也要标全（真误报：判断里写的"判 types 里有 Files。 */"）----
  const k = 'let a = 1;   /* 选中文字不该弹这层，\n所以判 types 里有 Files。 */\nconst b = types;';
  const fk = commentFlags(k);
  yes('块注释首行标出', fk[k.indexOf('选中文字')] === 1);
  yes('块注释的续行（无 * 开头）也标出', fk[k.indexOf('判 types')] === 1);
  yes('注释结束后的真引用不标', fk[k.indexOf('const b')] === 0);
  const l = '// 行注释提一下 tools\nconst c = tools;';
  yes('行注释标出且不越界', commentFlags(l)[l.indexOf('提一下')] === 1 && commentFlags(l)[l.indexOf('const c')] === 0);
  const m2 = 'const re = /\\/\\*/; /* 真注释 */ const d = 1;';
  yes('正则里的 /* 不当注释开头', commentFlags(m2)[m2.indexOf('\\*')] === 0 && commentFlags(m2)[m2.indexOf('真注释')] === 1);
  const n2 = 'const t = `x${ /* 注释 */ 1 }y`;';
  yes('插值里的注释标 com 且不占模板文字', commentFlags(n2)[n2.indexOf('注释')] === 1 && templateFlags(n2)[n2.indexOf('注释')] === 0);

  console.log(bad ? 'js-literals 自测失败 ' + bad + ' 项' : 'js-literals 自测全部通过');
  process.exit(bad ? 1 : 0);
}

module.exports = { templateFlags, commentFlags, regexCanFollow };
