// Baut Telefonrahmen (<div class="phone" data-shot="img/x.png">) und Klaviaturen (<div class="keys" data-n="15" data-on="3,9">).
const ICONS = `<svg viewBox="0 0 24 18"><path d="M12 17.5 0 4.6C3.3 1.7 7.5 0 12 0s8.7 1.7 12 4.6z"/></svg>
<svg viewBox="0 0 20 18"><path d="M0 18 20 0v18z"/></svg>
<svg viewBox="0 0 12 20"><rect x="3.5" y="0" width="5" height="2" rx=".5"/><rect x="0" y="2" width="12" height="18" rx="2"/></svg>`;
for (const p of document.querySelectorAll('.phone[data-shot]')) {
  const crop = +(p.dataset.crop || 0); // Pixel der Original-Aufnahme (1080 breit), um die nach oben verschoben wird
  p.insertAdjacentHTML('afterbegin', `<div class="cam"></div><div class="screen">
    <div class="sb"><span>${p.dataset.time || '9:41'}</span><span style="display:flex;align-items:center">${ICONS}</span></div>
    <img class="shot" src="${p.dataset.shot}" style="top:calc(var(--w) * ${-crop / 1080})"></div>`);
}
const BLACK = [1, 1, 0, 1, 1, 1, 0]; // hinter C D (E) F G A (H)
for (const k of document.querySelectorAll('.keys')) {
  const n = +k.dataset.n, start = +(k.dataset.start || 0);
  const on = (k.dataset.on || '').split(',').filter(Boolean).map(String);
  const lt = (k.dataset.lt || '').split(',').filter(Boolean).map(String);
  for (let i = 0; i < n; i++) {
    const s = (i + start) % 7;
    const w = document.createElement('div'); w.className = 'w';
    if (on.includes('w' + i)) w.classList.add('on-key');
    if (lt.includes('w' + i)) w.classList.add('on-lt');
    if (BLACK[s] && i < n - 1) {
      const b = document.createElement('div'); b.className = 'b';
      if (on.includes('b' + i)) b.classList.add('on-key');
      if (lt.includes('b' + i)) b.classList.add('on-lt');
      w.appendChild(b);
    }
    k.appendChild(w);
  }
}
