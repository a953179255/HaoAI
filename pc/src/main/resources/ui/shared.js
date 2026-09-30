/* 两端共用的小件（index.html 与 phone.html 各自引 /shared.js）。
 *
 * S6 从两份 HTML 里抽出来的：真正逐字等价、两边都需要的只有 esc 这一件 ——
 * decide / answer / show 两边**同名不同义**（phone 的 answer 是"把回答发回电脑"，
 * index 的 answer 是"画流式正文"），合到一起就是给未来埋雷，所以不合。
 * 加东西前先问一句：两边真的同一个语义吗？ */
/* esc 必须能吃数字：(t||'').replace 遇到数字直接 TypeError，
   而设置抽屉里只要有一处把数字塞进模板，整个弹窗就画不出来。 */
/* 引号也要转：这些字符串大量被插进 value="…" / data-ws="…" 这类属性里，
   只转 &<> 的话，一个带引号的模型名或目录名就能把自己关到属性外面去。 */
function esc(t){return String(t==null?'':t).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
