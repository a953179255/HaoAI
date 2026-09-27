// 网页壳里 markdown 渲染器的离线检查。用法： node pc/tools/md-check.js
//
// 为什么要单独一个脚本：这段逻辑住在 index.html 里，Gradle 测试跑不了它，
// 而它恰好是"改一行就静默变丑 / 静默变不安全"的那类代码。实测过的两个坑：
// ① 围栏代码块先渲染、行级 "\n → <br>" 后跑，于是 <pre> 里每行代码之间多一个空行；
// ② 把代码块搬出去的时机放在"整段转义"之前，回来时忘了补转义 ——
//    模型（或它引用的文件内容）就能在页面里塞标签。
// 所以这里既测排版行为，也测一次注入。
const fs = require('fs'), path = require('path'), assert = require('assert');
// 渲染器现在住在 ui/md.js（以前埋在 index.html 里，改布局时有把它一起改坏的风险）。
// 找不到 md.js 就退回 index.html 里找 —— 两边任一有就能测。
const uiDir = path.join(__dirname, '..', 'src', 'main', 'resources', 'ui');
const html = fs.readFileSync(path.join(uiDir, 'index.html'), 'utf8');
const standalone = fs.existsSync(path.join(uiDir, 'md.js'))
  ? fs.readFileSync(path.join(uiDir, 'md.js'), 'utf8') : '';
const src = standalone || html;
// 用花括号配对取出整个函数体：以前用"找到行首的 }"这种正则，
// 缩进一换（或者函数里嵌套了别的块）就悄悄匹配不到，报"找不到 md()"。
function grabFn(text, head) {
  const at = text.indexOf(head);
  if (at < 0) return null;
  let depth = 0, i = text.indexOf('{', at);
  if (i < 0) return null;
  for (; i < text.length; i++) {
    const c = text[i];
    if (c === '{') depth++;
    else if (c === '}') { depth--; if (!depth) return text.slice(at, i + 1) }
  }
  return null;
}
const m = { 0: grabFn(src, 'function md(t) {') };
if (!m[0]) { console.error('x 在 md.js / index.html 里都找不到 md()'); process.exit(1); }
const md = new Function(m[0] + '; return md;')();
let bad = 0;
const check = (n, f) => { try { f(); console.log('  ok   ' + n); } catch (e) { bad++; console.log('  FAIL ' + n + '\n       ' + (e.message || e)); } };

const code = md('看这段：\n\n```kotlin\nval a = 1\nval b = 2\n```\n\n后面还有一句');
check('代码块内部不再出现 <br>（<pre> 本来就保留换行）', () =>
  assert(!/<pre[\s\S]*?<\/pre>/.exec(code)[0].includes('<br>'), code));
check('围栏前后的正文都还在', () =>
  assert(code.startsWith('<p>看这段') && code.endsWith('<p>后面还有一句</p>'), code));
check('语言标进 data-lang', () => assert(code.includes('data-lang="kotlin"'), code));
check('有序列表渲染成 <ol>', () => assert(md('1. 甲\n2. 乙').includes('<ol><li>甲</li><li>乙</li></ol>')));
check('无序列表渲染成 <ul>', () => assert(md('- 甲\n- 乙').includes('<ul><li>甲</li><li>乙</li></ul>')));
check('引用块渲染成 <blockquote>', () => assert(md('> 引一句').includes('<blockquote>引一句</blockquote>')));
check('行内码/粗体/斜体/裸链接/链接语法', () => {
  const h = md('`code` **粗** *斜* https://a.cn/x [文档](https://a.cn/y)');
  assert(h.includes('<code>code</code>') && h.includes('<b>粗</b>') && h.includes('<i>斜</i>'), h);
  assert(h.includes('<a href="https://a.cn/x"') && h.includes('>文档</a>'), h);
});
check('标题一到四级', () => { assert(md('#### 四级').includes('<h4>四级</h4>')); assert(md('## 二级').includes('<h2>二级</h2>')); });

const evil = md('看这个：\n\n```html\n<img src=x onerror=alert(1)>\n<scr' + 'ipt>alert(2)</scr' + 'ipt>\n```\n');
check('代码块里的标签必须被转义（模型引用的文件内容不可信）', () => {
  assert(!/<img|<scr/.test(evil), evil);
  assert(evil.includes('&lt;img') && evil.includes('&lt;scr'), evil);
});
check('正文里的尖括号同样被转义', () => assert(md('if (a < b) x').includes('&lt; b)'), md('if (a < b) x')));
check('不残留 NUL 占位符', () => { assert(!evil.includes(String.fromCharCode(0)), JSON.stringify(evil)); assert(!code.includes(String.fromCharCode(0))); });
check('空输入不炸', () => { assert(md('') === ''); assert(md(null) === ''); });

check('表格：表头加分隔行才成表', () => {
  const h = md('| 方案 | 成本 |\n|---|---|\n| 甲 | 低 |\n| 乙 | 高 |');
  assert(h.includes('<table><thead><tr><th>方案</th><th>成本</th></tr></thead>'), h);
  assert(h.includes('<tbody><tr><td>甲</td><td>低</td></tr>'), h);
  assert(h.includes('<td>乙</td>'), h);
});
check('表格：没有分隔行的竖线还是普通文字', () => {
  const h = md('选 甲 | 乙 都行');
  assert(!h.includes('<table'), h);
});
check('表格：单元格里也要转义', () => {
  const h = md('| a | b |\n|---|---|\n| <img src=x> | ok |');
  assert(!/<img/.test(h), h);
  assert(h.includes('&lt;img'), h);
});
check('表格后面的正文不会被吞掉', () => {
  const h = md('| a |\n|---|\n| 1 |\n\n收尾的话');
  assert(h.includes('<table') && h.includes('收尾的话'), h);
});

console.log(bad ? '\n' + bad + ' 项不通过' : '\n全部通过');
process.exit(bad ? 1 : 0);
