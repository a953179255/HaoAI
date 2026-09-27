/* md(t)：回答正文的 markdown 渲染。
 *
 * 单独一个文件而不是埋在 index.html 里，是因为它有一整套离线检查（tools/md-check.js），
 * 而界面排版这一层接下来还会大改 —— 分开之后两边互不影响，改布局不可能碰坏渲染器。
 * 也正因为如此，这里刻意不引任何外部库：整个壳子必须能在断网时用。
 */
function md(t) {
  if (t == null || String(t).trim() === '') return '';   // 空回答不该产出任何标记（否则多一个空隔块）
  const esc2 = s => String(s).replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
  const inline = s => s
    .replace(/`([^`\n]+)`/g, (_, c) => `<code>${c}</code>`)
    .replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>')
    .replace(/(^|[\s(])\*([^*\n]+)\*(?=[\s).,!;?]|$)/g, '$1<i>$2</i>')
    .replace(/\[([^\]\n]+)\]\((https?:[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>')
    .replace(/(^|[^"'>])(https?:\/\/[^\s<)]+)/g, '$1<a href="$2" target="_blank" rel="noopener">$2</a>');
  
  // 1) 先把围栏代码块整块搬出去：行级规则一律不许碰它
  const blocks = [];
  let src = String(t == null ? '' : t).replace(/```([^\n`]*)\r?\n([\s\S]*?)```/g,
    (m, lang, code) => {
      blocks.push({ lang: lang.trim(), code: code.replace(/\n$/, '') });
      return '\u0000' + (blocks.length - 1) + '\u0000';
    });
  // 2) 整段转义：后面拼进去的标签才是我们自己造的
  src = esc2(src);
  
  const out = [];
  let list = null;
  const closeList = () => { if (list) { out.push(list === 'o' ? '</ol>' : '</ul>'); list = null } };
  const putBlock = i => {
    const b = blocks[i];
    if (!b) return '';
    // 代码内容必须在这里补转义：块是在"整段转义"之前搬出去的，
    // 不转义就等于模型（或它引用的文件内容）能在页面里塞标签。
    return `<pre${b.lang ? ` data-lang="${esc2(b.lang)}"` : ''}><code>${esc2(b.code)}</code></pre>`;
  };
  
  const rows = src.split(/\r?\n/);
  // 表格：`|a|b|` + 分隔行 `|---|---|` 才算表头，否则就是普通的一行文字
  // （模型很爱用竖线写"选项一 | 选项二"，没有分隔行就不能变成表）。
  const isRow = l => /^\s*\|.*\|\s*$/.test(l);
  const isSep = l => /^\s*\|[\s:|-]+\|[\s:|-]*$/.test(l) && l.includes('-');
  const cells = l => l.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(c => c.trim());
  for (let i = 0; i < rows.length; i++) {
    const raw = rows[i];
    const line = raw.replace(/\s+$/, '');
    let m = line.match(/^\u0000(\d+)\u0000$/);
    if (m) { closeList(); out.push(putBlock(+m[1])); continue }
    if (isRow(line) && i + 1 < rows.length && isSep(rows[i + 1])) {
      closeList();
      const head = cells(line);
      i++;                                   // 吃掉分隔行
      const body = [];
      while (i + 1 < rows.length && isRow(rows[i + 1])) { i++; body.push(cells(rows[i])) }
      out.push('<table><thead><tr>' + head.map(c => `<th>${inline(c)}</th>`).join('') + '</tr></thead><tbody>' +
        body.map(r => '<tr>' + r.map(c => `<td>${inline(c)}</td>`).join('') + '</tr>').join('') + '</tbody></table>');
      continue;
    }
    m = line.match(/^(#{1,4}) +(.*)$/);
    if (m) { closeList(); const lv = m[1].length; out.push(`<h${lv}>${inline(m[2])}</h${lv}>`); continue }
    m = line.match(/^&gt; +(.*)$/);
    if (m) { closeList(); out.push(`<blockquote>${inline(m[1])}</blockquote>`); continue }
    m = line.match(/^[-*+] +(.*)$/);
    if (m) { if (list !== 'u') { closeList(); out.push('<ul>'); list = 'u' } out.push(`<li>${inline(m[1])}</li>`); continue }
    m = line.match(/^\d+[.)] +(.*)$/);
    if (m) { if (list !== 'o') { closeList(); out.push('<ol>'); list = 'o' } out.push(`<li>${inline(m[1])}</li>`); continue }
    if (!line.trim()) { closeList(); out.push('<div class="gap"></div>'); continue }
    closeList();
    // 段落里的代码块占位符（模型把围栏和正文写在同一行时）也一并换回来
    out.push(`<p>${inline(line).replace(/\u0000(\d+)\u0000/g, (_, i) => putBlock(+i))}</p>`);
  }
  closeList();
  // 兜底：任何没被行级规则吃掉的占位符都要还原，不能把 \x00 数字漏在界面上
  return out.join('').replace(/\u0000(\d+)\u0000/g, (_, i) => putBlock(+i));
  }
