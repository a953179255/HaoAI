// 网页壳里 markdown 渲染器的离线检查。用法： node pc/tools/md-check.js
//
// 为什么要单独一个脚本：这段逻辑住在 index.html 里，Gradle 测试跑不了它，
// 而它恰好是"改一行就静默变丑 / 静默变不安全"的那类代码。实测过的两个坑：
// ① 围栏代码块先渲染、行级 "\n → <br>" 后跑，于是 <pre> 里每行代码之间多一个空行；
// ② 把代码块搬出去的时机放在"整段转义"之前，回来时忘了补转义 ——
//    模型（或它引用的文件内容）就能在页面里塞标签。
// 所以这里既测排版行为，也测一次注入。
const fs = require('fs'), path = require('path'), assert = require('assert');
const html = fs.readFileSync(path.join(__dirname, '..', 'src', 'main', 'resources', 'ui', 'index.html'), 'utf8');
const m = html.match(/function md\(t\) \{[\s\S]*?\n\}/);
if (!m) { console.error('x 在 index.html 里找不到 md()'); process.exit(1); }
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

console.log(bad ? '\n' + bad + ' 项不通过' : '\n全部通过');
process.exit(bad ? 1 : 0);
