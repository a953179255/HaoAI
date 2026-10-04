// ESLint 门：审"只有真解析器能看出来"的错。
//
// ui-check 的正则近似（6.7 私有名裸用 / 6.8 同名声明撞车）已经挡住两大家族，但它们
// 有个共同的盲区：**歧义名**（avatar / tools / paint 这类声明在好几处的名字）一律跳过，
// 而"引用了别的面板的私有函数"恰恰常发在这类名字上（真事故：Hub 引用 Experts 的
// 私有 avatar()，运行时 ReferenceError 炸在 setBody 前，症状是"导航亮了正文没换"，
// 看起来像"页面没反应"）。这份用真作用域分析补盲区：no-undef 与 no-dupe-keys 等。
//
// 为什么用进程内 Linter 而不是 eslint CLI：审的"文件"是从 index.html <script> 抽出来的
// 一段（拼上 md.js / shared.js，三段之间的引用要能互相解析），落临时文件还得对行号；
// Linter.verify 直接吃字符串，报错行号在输出里换算回所属文件。
//
// 规则刻意只收"可能错误"类、不收风格：风格门会让这条红几千行，**红几千行的门等于没有门**。
// eslint 固定 8.x（eslintrc 风格的 Linter API 稳定；9 的 flat config 对本用例没有增益）。
//
// 用法：node pc/tools/lint.js   （依赖 pc/tools/node_modules，见 package.json）

const fs = require('fs');
const path = require('path');
const { Linter } = require('eslint');

const uiDir = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'ui');
const html = fs.readFileSync(path.join(uiDir, 'index.html'), 'utf8');

// 与 ui-check 同源的三段拼接：内联主逻辑 + 渲染器 + 两端共用件。
// esc（shared.js）/ 渲染函数（md.js）被主脚本大量调用，分开审会满屏 no-undef 假故障。
const parts = [];
parts.push({ name: 'index.html <script>', code: (html.match(/<script>([\s\S]*?)<\/script>/) || [, ''])[1] });
for (const f of ['md.js', 'shared.js']) {
  const p = path.join(uiDir, f);
  if (fs.existsSync(p)) parts.push({ name: f, code: fs.readFileSync(p, 'utf8') });
}
if (!parts[0].code.trim()) { console.log('x 从 index.html 抽不到主脚本'); process.exit(1); }

const RULES = {
  'no-undef': 'error',                       // 引用了谁都没声明的名字 = 运行时 ReferenceError
  'no-dupe-keys': 'error',                   // 对象字面量重复键：后一份静默覆盖前一份
  'no-dupe-args': 'error',
  'no-unreachable': 'error',                 // return 之后还写着代码 = 永远执行不到
  'no-fallthrough': ['error', { commentPattern: ' falls?through|继续往下' }],
  'no-cond-assign': ['error', 'except-parens'],
  'no-constant-condition': ['error', { checkLoops: false }],
  'no-async-promise-executor': 'error',
  'no-empty': ['error', { allowEmptyCatch: true }],
  'no-func-assign': 'error',
  'no-loss-of-precision': 'error',
  'no-template-curly-in-string': 'off',      // 噪音：拼接风格的老代码里太多
  'no-unsafe-finally': 'error',
  'no-unsafe-negation': 'error',
  'no-useless-backreference': 'error',
  'use-isnan': 'error',
  'valid-typeof': 'error',
};

const messages = [];
{
  // 拼接成一份 Program：跨文件引用（esc / 渲染函数）要能互相解析。
  // 段与段之间垫一个换行，行号映射按"这段从第几行开始"往回换算。
  let offset = 0;
  const spans = [];
  let combined = '';
  for (const p of parts) {
    spans.push({ name: p.name, start: offset + 1 });   // 1 起算行号
    combined += p.code + '\n';
    offset += p.code.split('\n').length;
  }
  const linter = new Linter();
  const results = linter.verify(combined, {
    env: { browser: true, es2022: true },
    parserOptions: { ecmaVersion: 2022, sourceType: 'script' },
    rules: RULES,
  });
  const where = line => {
    let hit = spans[0];
    for (const s of spans) if (line >= s.start) hit = s;
    return hit.name + ':' + (line - hit.start + 1);
  };
  for (const m of results) messages.push(where(m.line) + ':' + m.column + '  ' + m.ruleId + '  ' + m.message);
}

if (messages.length) {
  console.log('x ESLint 门没过 ' + messages.length + ' 处：');
  for (const m of messages) console.log('  ' + m);
  process.exit(1);
}
console.log('ESLint 门通过（' + Object.keys(RULES).length + ' 条规则 / ' +
  parts.map(p => p.name).join(' + ') + '）');
