/* Ilha dinâmica — bolha em volta da câmera + cápsula fixa logo abaixo.
   Porte fiel do protótipo "Bolha flutuante Android" (Bolha da câmera). */
(function () {
  'use strict';
  const { html, render, Component } = window.htmPreact;
  const noop = function () {};
  const H = window.IslandHost || {};
  const host = {
    resize: (w, h) => (H.resize ? H.resize(w, h) : noop()),
    report: (j) => (H.report ? H.report(j) : noop()),
    media: (a) => (H.media ? H.media(a) : noop()),
    setVolume: (v) => (H.setVolume ? H.setVolume(v) : noop()),
    setBrightness: (v, m) => (H.setBrightness ? H.setBrightness(v, m) : noop()),
    buzz: (k) => (H.buzz ? H.buzz(k) : noop()),
    openApp: (t) => (H.openApp ? H.openApp(t) : noop()),
    ready: () => (H.ready ? H.ready() : noop())
  };

  const TAB_DEFS = [['music', 'Música'], ['timer', 'Timer'], ['bright', 'Brilho'], ['assist', 'Assistente'], ['cal', 'Calendário'], ['ig', 'Instagram'], ['wa', 'WhatsApp'], ['tg', 'Telegram'], ['tt', 'TikTok'], ['call', 'Chamada'], ['yt', 'YouTube'], ['ifood', 'iFood'], ['maps', 'Maps'], ['file', 'Enviar arquivo']];
  const ORDER = TAB_DEFS.map((t) => t[0]);
  const BUB_COLORS = { music: '#E8814B', timer: '#FFB547', bright: '#E6E14A', cal: '#EA4335', ig: '#E1306C', wa: '#25D366', tg: '#2AABEE', tt: '#FE2C55', ifood: '#EA1D2C', maps: '#4285F4', call: '#25D366', yt: '#FF0000', assist: '#8A7CFF', file: '#2F8CFF' };
  const CAP_SHADOW = {
    file: 'inset 0 0 0 1.5px rgba(47,140,255,.9), 0 0 30px rgba(47,140,255,.55), 0 18px 44px rgba(0,0,0,.45)',
    ifood: 'inset 0 0 0 1.5px rgba(234,29,44,.9), 0 0 30px rgba(234,29,44,.55), 0 18px 44px rgba(0,0,0,.45)',
    maps: 'inset 0 0 0 1.5px rgba(66,133,244,.9), 0 0 30px rgba(66,133,244,.5), 0 18px 44px rgba(0,0,0,.45)',
    tt: 'inset 0 0 0 1.5px rgba(254,44,85,.85), 0 0 30px rgba(254,44,85,.5), 0 18px 44px rgba(0,0,0,.45)',
    ig: 'inset 0 0 0 1.5px rgba(225,48,108,.85), 0 0 30px rgba(225,48,108,.5), 0 18px 44px rgba(0,0,0,.45)',
    wa: 'inset 0 0 0 1.5px rgba(37,211,102,.85), 0 0 30px rgba(37,211,102,.5), 0 18px 44px rgba(0,0,0,.45)',
    tg: 'inset 0 0 0 1.5px rgba(42,171,238,.85), 0 0 30px rgba(42,171,238,.5), 0 18px 44px rgba(0,0,0,.45)',
    call: 'inset 0 0 0 1.5px rgba(37,211,102,.9), 0 0 30px rgba(37,211,102,.55), 0 18px 44px rgba(0,0,0,.45)',
    yt: 'inset 0 0 0 1.5px rgba(255,0,0,.85), 0 0 30px rgba(255,0,0,.5), 0 18px 44px rgba(0,0,0,.45)',
    assist: 'inset 0 0 0 1.5px rgba(138,124,255,.85), 0 0 30px rgba(138,124,255,.5), 0 18px 44px rgba(0,0,0,.45)'
  };
  const CAP_SHADOW_DEFAULT = 'inset 0 0 0 1px rgba(255,255,255,.09), 0 18px 44px rgba(0,0,0,.45)';
  const APPS = {
    ig: { app: 'Instagram', color: '#E1306C', glow: 'rgba(225,48,108,.55)', avBg: '#5A2A3A' },
    wa: { app: 'WhatsApp', color: '#1FAF5A', glow: 'rgba(37,211,102,.5)', avBg: '#2C4A3A' },
    tg: { app: 'Telegram', color: '#2AABEE', glow: 'rgba(42,171,238,.55)', avBg: '#27445A' },
    tt: { app: 'TikTok', color: '#FE2C55', glow: 'rgba(254,44,85,.55)', avBg: '#3A2730' }
  };
  const TRACKS = ['[Faixa 1]', '[Faixa 2]', '[Faixa 3]', '[Faixa 4]', '[Faixa 5]'];
  const PRESETS = [1, 3, 5, 10, 15];
  const MODES = ['Automático', 'Leitura', 'Noite', 'Vívido'];
  const REMS = ['Tomar o remédio', 'Reunião às 15h', 'Beber água', 'Treinar 18h', 'Ligar pra mãe', 'Pagar o boleto'];
  const DELAYS = [5, 10, 30];
  const DOWS = ['SEG', 'TER', 'QUA', 'QUI', 'SEX', 'SÁB', 'DOM'];
  const C132 = 377;

  const clamp = (x, a, b) => Math.max(a, Math.min(b, x));
  const fmt = (sec) => { sec = Math.max(0, Math.floor(sec)); const m = Math.floor(sec / 60), x = sec % 60; return m + ':' + (x < 10 ? '0' : '') + x; };
  const initials = (name) => {
    const p = String(name || '').trim().split(/\s+/).filter(Boolean);
    if (!p.length) return '?';
    if (p.length === 1) return p[0].slice(0, 2).toUpperCase();
    return (p[0][0] + p[p.length - 1][0]).toUpperCase();
  };

  /* ---------- ícones ---------- */
  const I = {
    pause: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="currentColor"><path d="M6 4h4v16H6zM14 4h4v16h-4z"/></svg>`,
    play: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="currentColor"><path d="M7 4l13 8-13 8z"/></svg>`,
    sun: () => html`<svg width="36" height="36" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>`,
    tiktok: () => html`<svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M14 3c.3 2.3 1.8 4 4 4.3v2.5c-1.4 0-2.8-.4-4-1.1v5.8a5.3 5.3 0 1 1-5.3-5.3c.3 0 .6 0 .9.1v2.7a2.6 2.6 0 1 0 1.8 2.5V3z"/></svg>`,
    chat: () => html`<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a8.5 8.5 0 0 1-12.4 7.6L3 21l1.5-5.4A8.5 8.5 0 1 1 21 12z"/></svg>`,
    mic: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10a7 7 0 0 0 14 0M12 17v5"/></svg>`,
    x: (s, w) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width=${w || 2.6} stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>`,
    send: () => html`<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M22 2L11 13"/><path d="M22 2l-7 20-4-9-9-4z"/></svg>`,
    pin: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="currentColor" style="flex:none"><path d="M12 2a7 7 0 0 0-7 7c0 5 7 13 7 13s7-8 7-13a7 7 0 0 0-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z"/></svg>`,
    fork: () => html`<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 3v7a3 3 0 0 0 3 3v8M7 3v6M10 3v6M18 3c-1.5 0-2.5 2-2.5 5s1 4 2.5 4v8"/></svg>`,
    nav: () => html`<svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><path d="M3 11l18-8-8 18-2-7z"/></svg>`,
    video: (s, slash) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="flex:none"><path d="M23 7l-7 5 7 5z"/><rect x="1" y="5" width="15" height="14" rx="2"/>${slash ? html`<path d="M2 2l20 20"/>` : null}</svg>`,
    phone: (s, sw, style) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width=${sw} stroke-linecap="round" stroke-linejoin="round" style=${style || ''}><path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1.9.4 1.8.7 2.7a2 2 0 0 1-.5 2.1L8 9.8a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.7.7a2 2 0 0 1 1.7 2z"/></svg>`,
    phoneFill: () => html`<svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><path d="M20 15.5a12.5 12.5 0 0 1-4-.6 1 1 0 0 0-1 .2l-1.8 1.8a15 15 0 0 1-6.6-6.6l1.8-1.8a1 1 0 0 0 .2-1 12.5 12.5 0 0 1-.6-4 1 1 0 0 0-1-1H4a1 1 0 0 0-1 1A17 17 0 0 0 20 21a1 1 0 0 0 1-1v-3.5a1 1 0 0 0-1-1z"/></svg>`,
    spark: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l1.8 5.6L19.5 9l-4.6 3.3L16.5 18 12 14.6 7.5 18l1.6-5.7L4.5 9l5.7-1.4z"/></svg>`,
    bell: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0"/></svg>`,
    check: (s, w) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width=${w} stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5 9-10"/></svg>`,
    plus: () => html`<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>`,
    ext: () => html`<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M14 4h6v6"/><path d="M20 4l-9 9"/><path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/></svg>`,
    download: () => html`<svg class="bob" width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12"/><path d="M7 10l5 5 5-5"/><path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/></svg>`,
    wave: (n, color) => {
      const d = [0, .18, .36, .12, .3, .48, .06, .24, .42, .14, .33, .5, .09, .27, .45, .15, .38, .21].slice(0, n);
      return html`<span class="wave" style=${'height:22px;color:' + color}>${d.map((x) => html`<span style=${'animation-delay:' + x + 's'}></span>`)}</span>`;
    }
  };

  class Island extends Component {
    constructor() {
      super();
      const now = new Date();
      this.state = {
        open: false, tab: 'music',
        cy: 28, screenW: 394, scale: 1, bscale: 1,
        playing: true, pos: 72, dur: 214, trk: 1, vol: 80,
        real: false, title: '', artist: '', art: '',
        tIdx: 2, timer: 300, total: 300, run: false,
        mIdx: 0, bright: 68,
        recording: false, recSecs: 0,
        msgs: { ig: 'Te mandei as fotos agora.', wa: 'Oi, já enviei o arquivo pra você.', tg: 'Acabei de te enviar o link.', tt: 'Te enviei o vídeo agora.' },
        names: { ig: 'Marina', wa: 'Carlos', tg: 'Lucas', tt: 'Lara' },
        file: 'idle', prog: 0, day: (now.getDay() + 6) % 7,
        cal: null,
        tk: 0, ifMin: 8, gmMin: 18, gmKm: 74,
        geo: { ifood: null, maps: null },
        callName: 'Marina', callApp: 'WhatsApp',
        callDur: 768, callMuted: false, callVideo: true,
        ytPos: 768, ytDur: 1450, ytPlaying: true, ytReal: false, ytTitle: 'Podcast em andamento', ytChannel: 'Canal Tech Brasil',
        asState: 'compose', asIdx: 0, asDelayIdx: 0, asLeft: 0, asAnsLeft: 0,
        drag: null
      };
      this.lastSize = '';
      this.lastReport = '';
      this.autoT = null;
      this.swipe = null;
    }

    componentDidMount() {
      this.iv = setInterval(() => this.tick(), 1000);
      window.island = { cmd: (c) => this.cmd(typeof c === 'string' ? JSON.parse(c) : c) };
      this.sync();
      host.ready();
    }

    componentDidUpdate() { this.sync(); }

    componentWillUnmount() { clearInterval(this.iv); clearInterval(this.fi); }

    tick() {
      const s = this.state, n = { tk: s.tk + 1 };
      if (s.playing) n.pos = s.real ? Math.min(s.pos + 1, s.dur) : (s.pos + 1) % s.dur;
      if (s.run && s.timer > 0) n.timer = s.timer - 1;
      if (s.run && s.timer <= 1) { n.run = false; host.buzz('timer'); n.tab = 'timer'; n.open = true; }
      if (s.recording) n.recSecs = s.recSecs + 1;
      if (s.tab === 'call') n.callDur = s.callDur + 1;
      if (s.ytPlaying && s.ytPos < s.ytDur) n.ytPos = s.ytPos + 1;
      if (s.asState === 'created') {
        if (s.asLeft > 1) { n.asLeft = s.asLeft - 1; }
        else { n.asState = 'ringing'; n.asLeft = 0; n.tab = 'assist'; n.open = true; host.buzz('ring'); }
      }
      if (s.asState === 'ringing' && s.tk % 3 === 0) host.buzz('ring');
      if (s.asState === 'answered' && s.asAnsLeft > 0) {
        n.asAnsLeft = s.asAnsLeft - 1;
        if (s.asAnsLeft <= 1) n.asState = 'compose';
      }
      if (s.tk % 4 === 0) {
        if (s.tab === 'ifood' && !s.geo.ifood) n.ifMin = s.ifMin > 1 ? s.ifMin - 1 : 8;
        if (s.tab === 'maps' && !s.geo.maps) { n.gmMin = s.gmMin > 1 ? s.gmMin - 1 : 18; n.gmKm = s.gmMin > 1 ? Math.max(1, s.gmKm - 4) : 74; }
      }
      this.setState(n);
    }

    /* ---------- comandos vindos do Android ---------- */
    cmd(c) {
      const s = this.state;
      switch (c.type) {
        case 'cfg': {
          const n = {};
          ['cy', 'screenW', 'scale', 'bscale'].forEach((k) => { if (c[k] != null) n[k] = +c[k]; });
          this.setState(n);
          break;
        }
        case 'tab': this.pickTab(c.id, c.open !== false); break;
        case 'open': this.setState({ open: true }); break;
        case 'close': this.collapse(); break;
        case 'toggle': this.setState({ open: !s.open }); break;
        case 'media': {
          if (c.src === 'yt') {
            this.setState({ ytReal: true, ytTitle: c.title || 'YouTube', ytChannel: c.artist || '', ytPlaying: !!c.playing, ytPos: c.pos || 0, ytDur: Math.max(1, c.dur || s.ytDur) });
          } else if (c.src === 'music') {
            const n = { real: true, title: c.title || 'Tocando agora', artist: c.artist || '', playing: !!c.playing, pos: c.pos || 0, dur: Math.max(1, c.dur || 1) };
            if (c.art !== undefined) n.art = c.art || '';
            if (c.vol != null) n.vol = c.vol;
            this.setState(n);
          } else if (c.src === 'none') {
            this.setState({ real: false, art: '', ytReal: false });
          }
          if (c.vol != null && c.src !== 'music') this.setState({ vol: c.vol });
          if (c.show) this.pickTab(c.src === 'yt' ? 'yt' : 'music', false);
          break;
        }
        case 'volume': this.setState({ vol: c.vol }); break;
        case 'brightness': this.setState({ bright: c.value, mIdx: c.auto ? 0 : (s.mIdx === 0 ? 1 : s.mIdx) }); break;
        case 'calendar': this.setState({ cal: c }); break;
        case 'notify': this.notify(c); break;
      }
    }

    notify(c) {
      const s = this.state, tab = c.tab, n = { tab, open: true, recording: false, recSecs: 0 };
      if (APPS[tab]) {
        n.msgs = Object.assign({}, s.msgs, { [tab]: c.text || '' });
        n.names = Object.assign({}, s.names, { [tab]: c.title || APPS[tab].app });
      } else if (tab === 'ifood' || tab === 'maps') {
        n.geo = Object.assign({}, s.geo, { [tab]: { title: c.title || '', sub: c.text || '', addr: c.sub || '' } });
      } else if (tab === 'call') {
        n.callName = c.title || 'Chamada'; n.callApp = c.app || 'WhatsApp'; n.callDur = 0; n.callMuted = false;
      }
      this.setState(n);
      this.armAutoCollapse(c.hold || 8000);
    }

    armAutoCollapse(ms) {
      clearTimeout(this.autoT);
      this.autoT = setTimeout(() => { if (!this.state.recording && this.state.asState !== 'ringing') this.collapse(); }, ms);
    }

    touched() { clearTimeout(this.autoT); }

    pickTab(id, open) {
      const s = this.state;
      if (ORDER.indexOf(id) < 0) return;
      this.setState({ tab: id, open: open === undefined ? true : (open || s.open), recording: false, recSecs: 0, file: id === 'file' ? 'idle' : s.file, prog: 0 });
    }

    stepTab(d) {
      const i = ORDER.indexOf(this.state.tab);
      this.pickTab(ORDER[(i + d + ORDER.length) % ORDER.length], true);
    }

    collapse() { clearTimeout(this.autoT); this.setState({ open: false, recording: false, recSecs: 0, drag: null }); }

    /* ---------- janela flutuante: tamanho e estado ---------- */
    zoom() {
      const s = this.state;
      return Math.min(s.scale, (s.screenW - 16) / 370);
    }

    sync() {
      const s = this.state;
      const z = this.zoom();
      let w, h;
      if (!s.open) {
        w = Math.ceil(66 * s.bscale);
        h = Math.ceil(s.cy + 34 * s.bscale);
      } else {
        w = Math.min(s.screenW, Math.ceil(370 * z) + 80);
        h = Math.ceil(s.cy + 26 + 132 * z + 56);
        if (s.tab === 'file' && s.file === 'idle') h = Math.max(h, Math.ceil(s.cy + 26 + 132 * z + 250));
      }
      const key = w + 'x' + h;
      if (key !== this.lastSize) { this.lastSize = key; host.resize(w, h); }
      const rep = JSON.stringify({ tab: s.tab, open: s.open });
      if (rep !== this.lastReport) { this.lastReport = rep; host.report(rep); }
    }

    /* ---------- arrastar o arquivo até a cápsula ---------- */
    cardDown(e) {
      e.stopPropagation();
      this.touched();
      try { e.currentTarget.setPointerCapture(e.pointerId); } catch (x) { /* ok */ }
      this.setState({ drag: { x0: e.clientX, y0: e.clientY, dx: 0, dy: 0 } });
    }
    cardMove(e) {
      const d = this.state.drag;
      if (!d) return;
      this.setState({ drag: Object.assign({}, d, { dx: e.clientX - d.x0, dy: e.clientY - d.y0 }) });
    }
    cardUp(e) {
      const d = this.state.drag;
      if (!d) return;
      const moved = Math.abs(d.dx) + Math.abs(d.dy);
      const capBottom = this.state.cy + 26 + 132 * this.zoom();
      const cardTop = capBottom + 70 + d.dy;
      this.setState({ drag: null });
      if (moved < 10 || cardTop < capBottom + 30) this.dropFile();
    }
    dropFile() { if (this.state.tab === 'file' && this.state.file === 'idle') this.setState({ file: 'ready' }); }

    /* ---------- deslizar na cápsula troca de aba ---------- */
    capDown(e) { this.touched(); this.swipe = { x: e.clientX, y: e.clientY }; }
    capUp(e) {
      const sw = this.swipe; this.swipe = null;
      if (!sw) return;
      const dx = e.clientX - sw.x, dy = e.clientY - sw.y;
      if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5) this.stepTab(dx < 0 ? 1 : -1);
      else if (dy < -40 && Math.abs(dy) > Math.abs(dx) * 1.5) this.collapse();
    }

    /* ---------- valores de renderização (porte do renderVals) ---------- */
    vals() {
      const s = this.state;
      const isMsg = s.tab === 'ig' || s.tab === 'wa' || s.tab === 'tg' || s.tab === 'tt';
      const t = { music: s.tab === 'music', timer: s.tab === 'timer', bright: s.tab === 'bright', cal: s.tab === 'cal', file: s.tab === 'file', msg: isMsg, map: s.tab === 'ifood' || s.tab === 'maps', call: s.tab === 'call', yt: s.tab === 'yt', assist: s.tab === 'assist' };
      t.wheel = t.music || t.timer || t.bright;
      const isTT = s.tab === 'tt';
      const baseM = isMsg ? Object.assign({}, APPS[s.tab], { name: s.names[s.tab], initials: initials(s.names[s.tab]) }) : { color: '#000', glow: 'transparent', avBg: '#000', initials: '', name: '', app: '' };
      const m = Object.assign({}, baseM, {
        text: isMsg ? s.msgs[s.tab] : '', recording: isMsg && s.recording, reading: isMsg && !s.recording,
        recTxt: fmt(s.recSecs), isTT,
        ring: isTT ? '0 0 0 3px #25F4EE, 0 0 0 6px #FE2C55, 0 0 26px rgba(254,44,85,.55)' : '0 0 0 3px ' + baseM.color + ', 0 0 0 7px rgba(255,255,255,.04), 0 0 26px ' + baseM.glow,
        badgeBg: isTT ? '#FFFFFF' : baseM.color, badgeFg: isTT ? '#111111' : '#FFFFFF'
      });

      let wheel, num, dot, prevItem, nextItem, numUp, numDown;
      const numFor = (title, val, min, max, wrap) => {
        const f = (x) => (wrap ? ((x % (max + 1)) + (max + 1)) % (max + 1) : (x < min || x > max ? '' : x));
        const pad = (x) => (x === '' ? '' : (wrap && x < 10 ? '0' + x : String(x)));
        return { title, a2: pad(f(val + 2)), a1: pad(f(val + 1)), cur: pad(f(val)), b1: pad(f(val - 1)), b2: pad(f(val - 2)) };
      };
      if (t.music) {
        if (s.real) {
          wheel = { title: s.artist || 'Tocando agora', prev: 'Anterior', cur: s.title, next: 'Próxima', dotLabel: 'Tocar ou pausar', dotColor: s.playing ? '#FFFFFF' : '#6E737A' };
          dot = () => { host.media(s.playing ? 'pause' : 'play'); this.setState({ playing: !s.playing }); };
          prevItem = () => { host.media('prev'); this.setState({ pos: 0 }); };
          nextItem = () => { host.media('next'); this.setState({ pos: 0 }); };
        } else {
          wheel = { title: 'Álbum', prev: TRACKS[s.trk - 1] || '', cur: TRACKS[s.trk], next: TRACKS[s.trk + 1] || '', dotLabel: 'Tocar ou pausar', dotColor: s.playing ? '#FFFFFF' : '#6E737A' };
          dot = () => this.setState({ playing: !s.playing });
          prevItem = () => this.setState({ trk: clamp(s.trk - 1, 0, TRACKS.length - 1), pos: 0 });
          nextItem = () => this.setState({ trk: clamp(s.trk + 1, 0, TRACKS.length - 1), pos: 0 });
        }
        num = numFor('Vol', s.vol, 0, 100, false);
        numUp = () => { const v = clamp(s.vol + 1, 0, 100); host.setVolume(v); this.setState({ vol: v }); };
        numDown = () => { const v = clamp(s.vol - 1, 0, 100); host.setVolume(v); this.setState({ vol: v }); };
      } else if (t.timer) {
        const lbl = (i) => (PRESETS[i] ? PRESETS[i] + ' min' : '');
        const pick = (i) => this.setState({ tIdx: i, timer: PRESETS[i] * 60, total: PRESETS[i] * 60, run: false });
        wheel = { title: 'Predefinição', prev: lbl(s.tIdx - 1), cur: lbl(s.tIdx), next: lbl(s.tIdx + 1), dotLabel: 'Iniciar ou pausar', dotColor: s.run ? '#FFB547' : '#FFFFFF' };
        num = numFor('Seg', s.timer % 60, 0, 59, true);
        dot = () => this.setState({ run: !s.run, timer: s.timer === 0 ? s.total : s.timer });
        prevItem = () => { if (s.tIdx > 0) pick(s.tIdx - 1); };
        nextItem = () => { if (s.tIdx < PRESETS.length - 1) pick(s.tIdx + 1); };
        numUp = () => this.setState({ timer: s.timer + 1, total: Math.max(s.total, s.timer + 1) });
        numDown = () => this.setState({ timer: Math.max(0, s.timer - 1) });
      } else {
        const setMode = (i) => { host.setBrightness(s.bright, i); this.setState({ mIdx: i }); };
        wheel = { title: 'Modo', prev: MODES[s.mIdx - 1] || '', cur: MODES[s.mIdx], next: MODES[s.mIdx + 1] || '', dotLabel: 'Próximo modo', dotColor: '#E6E14A' };
        num = numFor('Brilho', s.bright, 0, 100, false);
        dot = () => setMode((s.mIdx + 1) % MODES.length);
        prevItem = () => setMode(clamp(s.mIdx - 1, 0, MODES.length - 1));
        nextItem = () => setMode(clamp(s.mIdx + 1, 0, MODES.length - 1));
        numUp = () => { const v = clamp(s.bright + 1, 0, 100); host.setBrightness(v, s.mIdx); this.setState({ bright: v }); };
        numDown = () => { const v = clamp(s.bright - 1, 0, 100); host.setBrightness(v, s.mIdx); this.setState({ bright: v }); };
      }

      const kmTxt = (s.gmKm / 10).toFixed(1).replace('.', ',');
      const g = s.tab === 'ifood'
        ? Object.assign({ app: 'iFood', title: 'Motoboy a caminho', sub: 'Chega em ' + s.ifMin + ' min · Pedido #1842', addr: 'Saiu para entrega', route: '#EA1D2C', pin: '#EA1D2C', glow: 'rgba(234,29,44,.55)', badgeBg: '#EA1D2C', isFood: true, moto: true }, this.geoOverride('ifood'))
        : Object.assign({ app: 'Google Maps', title: 'Rota para o destino', sub: s.gmMin + ' min · ' + kmTxt + ' km', addr: 'Av. Central, 245', route: '#4285F4', pin: '#EA4335', glow: 'rgba(66,133,244,.5)', badgeBg: '#1A73E8', isFood: false, moto: false }, this.geoOverride('maps'));

      const pct = t.music ? (s.dur ? s.pos / s.dur : 0) : t.timer ? (s.total ? s.timer / s.total : 0) : t.bright ? s.bright / 100 : t.yt ? s.ytPos / s.ytDur : t.file ? (s.file === 'sending' ? s.prog / 100 : 1) : 1;
      const bub = { color: BUB_COLORS[s.tab], off34: Math.round(94.2 * (1 - clamp(pct, 0, 1)) * 10) / 10 };

      return { t, m, g, bub, wheel, num, dot, prevItem, nextItem, numUp, numDown };
    }

    geoOverride(tab) {
      const o = this.state.geo[tab];
      if (!o) return {};
      const r = {};
      if (o.title) r.title = o.title;
      if (o.sub) r.sub = o.sub;
      if (o.addr) r.addr = o.addr;
      return r;
    }

    week() {
      const s = this.state;
      const now = new Date();
      const todayIdx = (now.getDay() + 6) % 7;
      const monday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - todayIdx);
      const evs = (s.cal && s.cal.days) || [[], [], [], [], [], [], []];
      return DOWS.map((dow, i) => {
        const d = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + i);
        const on = i === s.day, today = i === todayIdx;
        return {
          dow, num: d.getDate(), dots: (evs[i] || []).slice(0, 3),
          bg: on ? '#3A1712' : 'transparent',
          ring: on ? 'inset 0 0 0 1px #6A2A1E' : (today ? 'inset 0 0 0 1px #2A2D31' : 'none'),
          pick: () => this.setState({ day: i })
        };
      });
    }

    calInfo() {
      const s = this.state;
      if (!s.cal) return 'Permita o calendário no app Ilha Dinâmica';
      const todayIdx = (new Date().getDay() + 6) % 7;
      const nEv = ((s.cal.days || [])[s.day] || []).length;
      const base = nEv === 0 ? 'Nenhum evento' : nEv + (nEv === 1 ? ' evento' : ' eventos');
      if (s.day === todayIdx) return base + ' hoje' + (s.cal.next ? ' · Próximo às ' + s.cal.next : '');
      return base + ' neste dia';
    }

    /* ---------- partes da cápsula ---------- */
    renderWheel(v) {
      const s = this.state, { t, wheel, num } = v;
      let left;
      if (t.music) {
        left = html`<div style="position:relative;width:104px;height:104px;flex:none">
          <div class=${'spin ' + (s.playing ? '' : 'off')} style="position:absolute;inset:0;border-radius:50%;background:#C8452E;overflow:hidden;box-shadow:0 0 0 1px rgba(255,255,255,.1)">
            ${s.real && s.art
              ? html`<img src=${s.art} style="position:absolute;inset:0;width:100%;height:100%;object-fit:cover"/>`
              : html`<div style="position:absolute;left:-20px;top:46px;width:88px;height:88px;border-radius:50%;background:#F1E6D8"></div>
                <div style="position:absolute;right:11px;top:13px;width:32px;height:32px;border-radius:50%;background:#1A0D0A"></div>
                <div style="position:absolute;right:17px;bottom:24px;width:27px;height:4px;border-radius:3px;background:#1A0D0A"></div>`}
          </div>
          <button class="ib" onClick=${v.dot} aria-label="Tocar ou pausar" style="position:absolute;left:50%;top:50%;width:48px;height:48px;margin:-24px 0 0 -24px;border-radius:50%;background:rgba(0,0,0,.42);backdrop-filter:blur(6px);-webkit-backdrop-filter:blur(6px);color:#fff">${s.playing ? I.pause(18) : I.play(18)}</button>
        </div>`;
      } else if (t.timer) {
        const off = Math.round(C132 * (1 - (s.total ? s.timer / s.total : 0)) * 10) / 10;
        left = html`<button class="ib" onClick=${v.dot} aria-label="Iniciar ou pausar timer" style="position:relative;width:104px;height:104px;flex:none;border-radius:50%;background:#0E0F11;color:#FFB547;padding:0;flex-direction:column">
          <svg width="104" height="104" viewBox="0 0 132 132" style="position:absolute;inset:0"><circle cx="66" cy="66" r="60" fill="none" stroke="#2A2010" stroke-width="5"/><circle cx="66" cy="66" r="60" fill="none" stroke="#FFB547" stroke-width="5" stroke-linecap="round" stroke-dasharray="377" stroke-dashoffset=${off} transform="rotate(-90 66 66)" style="transition:stroke-dashoffset 1s linear"/></svg>
          <span class="mono" style="font-size:22px;font-weight:700">${fmt(s.timer)}</span>
          <span style="font-size:11px;color:#A3A8AE;margin-top:2px">${s.run ? 'rodando' : (s.timer === 0 ? 'fim' : 'pausado')}</span>
        </button>`;
      } else {
        const off = Math.round(C132 * (1 - s.bright / 100) * 10) / 10;
        left = html`<div style="position:relative;width:104px;height:104px;flex:none;border-radius:50%;background:#0E0F11;display:flex;align-items:center;justify-content:center;color:#E6E14A">
          <svg width="104" height="104" viewBox="0 0 132 132" style="position:absolute;inset:0"><circle cx="66" cy="66" r="60" fill="none" stroke="#25240E" stroke-width="5"/><circle cx="66" cy="66" r="60" fill="none" stroke="#E6E14A" stroke-width="5" stroke-linecap="round" stroke-dasharray="377" stroke-dashoffset=${off} transform="rotate(-90 66 66)" style="transition:stroke-dashoffset .4s ease"/></svg>
          ${I.sun()}
        </div>`;
      }
      const side = 'height:24px;background:transparent;color:#6E737A;justify-content:flex-start;padding:0 14px;font-size:14px;white-space:nowrap;overflow:hidden';
      return html`${left}
        <div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:2px">
          <div style="font-size:10px;color:#8D9298;padding-left:14px;height:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${wheel.title}</div>
          <button class="ib" onClick=${v.prevItem} aria-label="Item anterior" style=${side}>${wheel.prev}</button>
          <div class="roll" key=${'w' + wheel.cur} style="height:36px;border-radius:18px;box-shadow:inset 0 0 0 1px #34363B;display:flex;align-items:center;overflow:hidden">
            <span style="flex:1;min-width:0;padding:0 12px 0 14px;font-size:16px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${wheel.cur}</span>
            <button class="ib" onClick=${v.dot} aria-label=${wheel.dotLabel} style="width:40px;height:36px;flex:none;background:#1B1C1F;box-shadow:inset 1px 0 0 #34363B;color:#fff"><span style=${'width:6px;height:6px;border-radius:50%;display:block;background:' + wheel.dotColor}></span></button>
          </div>
          <button class="ib" onClick=${v.nextItem} aria-label="Próximo item" style=${side}>${wheel.next}</button>
        </div>
        <div style="width:66px;flex:none;display:flex;flex-direction:column;gap:2px">
          <div style="font-size:10px;color:#8D9298;height:12px">${num.title}</div>
          <div style="height:92px;overflow:hidden;display:flex;flex-direction:column;justify-content:center;align-items:flex-start">
            <span style="font-size:26px;font-weight:400;line-height:28px;color:#fff;opacity:.14">${num.a2}</span>
            <button class="ib" onClick=${v.numUp} aria-label="Aumentar" style="background:transparent;color:#fff;opacity:.42;font-size:28px;font-weight:400;line-height:30px;height:30px;padding:0">${num.a1}</button>
            <span class="roll" key=${'n' + num.cur} style="display:flex;align-items:center;gap:8px;font-size:36px;font-weight:400;line-height:40px;letter-spacing:-1px">${num.cur}<span style="width:5px;height:5px;border-radius:50%;background:#fff"></span></span>
            <button class="ib" onClick=${v.numDown} aria-label="Diminuir" style="background:transparent;color:#fff;opacity:.42;font-size:28px;font-weight:400;line-height:30px;height:30px;padding:0">${num.b1}</button>
            <span style="font-size:26px;font-weight:400;line-height:28px;color:#fff;opacity:.14">${num.b2}</span>
          </div>
        </div>`;
    }

    renderMsg(v) {
      const s = this.state, m = v.m;
      const startRec = () => this.setState({ recording: true, recSecs: 0 });
      const cancelRec = () => this.setState({ recording: false, recSecs: 0 });
      const sendAudio = () => { const msgs = Object.assign({}, s.msgs); msgs[s.tab] = 'Você · áudio ' + fmt(Math.max(1, s.recSecs)); this.setState({ recording: false, recSecs: 0, msgs }); };
      return html`<div style="position:relative;width:92px;height:92px;flex:none">
          <div style=${'position:absolute;inset:6px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:28px;font-weight:700;color:#fff;letter-spacing:-1px;background:' + m.avBg + ';box-shadow:' + m.ring}>${m.initials}</div>
          <div style=${'position:absolute;right:-4px;bottom:-2px;width:32px;height:32px;border-radius:50%;display:flex;align-items:center;justify-content:center;background:' + m.badgeBg + ';box-shadow:0 0 0 4px #000, 0 0 16px ' + m.glow + ';color:' + m.badgeFg}>${m.isTT ? I.tiktok() : I.chat()}</div>
        </div>
        <div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:3px">
          <div style="display:flex;align-items:center;gap:8px;font-size:12px;color:#8D9298">
            <span style="flex:1">${m.app}</span><span>agora</span>
            <span style=${'width:9px;height:9px;border-radius:50%;background:' + m.color + ';box-shadow:0 0 8px ' + m.glow}></span>
          </div>
          <div style="font-size:20px;font-weight:700;letter-spacing:-.3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${m.name}</div>
          ${m.reading
            ? html`<div style="font-size:14px;line-height:1.3;color:#9A9FA6;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden">${m.text}</div>`
            : html`<div class="fx" style="display:flex;align-items:center;gap:9px;height:26px">
                <span class="pulse" style="width:8px;height:8px;border-radius:50%;background:#FF3B30;box-shadow:0 0 8px rgba(255,59,48,.8);flex:none"></span>
                <span style="flex:1;min-width:0;overflow:hidden;display:flex">${I.wave(18, m.color)}</span>
                <span class="mono" style="font-size:12px;color:#C7CACE;flex:none">${m.recTxt}</span>
              </div>`}
        </div>
        ${m.reading
          ? html`<button class="ib" onClick=${startRec} aria-label="Gravar resposta em áudio" style=${'width:50px;height:50px;flex:none;border-radius:50%;color:#fff;background:' + m.color + ';box-shadow:0 0 18px ' + m.glow}>${I.mic(21)}</button>`
          : html`<div style="display:flex;gap:8px;flex:none;align-items:center">
              <button class="ib" onClick=${cancelRec} aria-label="Cancelar áudio" style="width:44px;height:44px;border-radius:50%;background:#2A2C30;color:#fff">${I.x(18)}</button>
              <button class="ib fx" onClick=${sendAudio} aria-label="Enviar áudio" style=${'width:48px;height:48px;border-radius:50%;color:#fff;background:' + m.color + ';box-shadow:0 0 18px ' + m.glow}>${I.send()}</button>
            </div>`}`;
    }

    renderMap(v) {
      const s = this.state, g = v.g;
      const mapTick = () => { host.openApp(s.tab); if (s.tab === 'ifood') this.setState({ ifMin: 8 }); else this.setState({ gmMin: 18, gmKm: 74 }); };
      return html`<div style=${'position:relative;width:100px;height:100px;flex:none;border-radius:50%;overflow:hidden;box-shadow:0 0 0 3px ' + g.route + ', 0 0 0 7px rgba(255,255,255,.04), 0 0 24px ' + g.glow}>
          <svg viewBox="0 0 100 100" width="100" height="100" style="position:absolute;inset:0;display:block">
            <rect width="100" height="100" fill="#E8EAE5"/>
            <path d="M-10 60 h60 a6 6 0 0 1 6 6 v44 h-66 z" fill="#CFE0BB"/>
            <rect x="62" y="-10" width="60" height="42" fill="#BBD6EC"/>
            <g stroke="#F6F7F4" stroke-width="7" stroke-linecap="round"><path d="M-6 38 L106 28"/><path d="M24 -6 L42 106"/><path d="M-6 74 L82 98"/></g>
            <path d="M22 80 C 38 70, 34 50, 52 46 S 72 30, 80 22" stroke=${g.route} stroke-width="5" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
            <circle cx="22" cy="80" r="5.5" fill="#1FAF5A" stroke="#fff" stroke-width="2.5"/>
          </svg>
          ${g.moto ? html`<span class="bob" style="position:absolute;left:32px;top:48px;width:20px;height:20px;border-radius:7px;background:#E5262B;box-shadow:0 2px 6px rgba(0,0,0,.4);display:flex;align-items:center;justify-content:center"><span style="width:8px;height:8px;border-radius:2px;background:#fff"></span></span>` : null}
          <svg width="22" height="22" viewBox="0 0 24 24" fill=${g.pin} style="position:absolute;left:70px;top:12px;filter:drop-shadow(0 2px 3px rgba(0,0,0,.35))"><path d="M12 2a7 7 0 0 0-7 7c0 5 7 13 7 13s7-8 7-13a7 7 0 0 0-7-7z"/><circle cx="12" cy="9" r="2.6" fill="#fff"/></svg>
        </div>
        <div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:3px">
          <div style="display:flex;align-items:center;gap:8px;font-size:12px;color:#8D9298">
            <span style=${'width:20px;height:20px;border-radius:6px;display:flex;align-items:center;justify-content:center;color:#fff;flex:none;background:' + g.badgeBg + ';box-shadow:0 0 10px ' + g.glow}>${g.isFood ? I.fork() : I.pin(12)}</span>
            <span style="flex:1;color:#C7CACE;font-weight:600">${g.app}</span><span>agora</span>
            <span style="width:9px;height:9px;border-radius:50%;background:#2FB566;box-shadow:0 0 8px rgba(47,181,102,.7)"></span>
          </div>
          <div style="font-size:19px;font-weight:700;letter-spacing:-.3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${g.title}</div>
          <div style="font-size:13px;color:#9A9FA6;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${g.sub}</div>
          <div style="display:flex;align-items:center;gap:5px;font-size:12px;color:#9A9FA6;white-space:nowrap;overflow:hidden">${I.pin(13)}<span style="overflow:hidden;text-overflow:ellipsis">${g.addr}</span></div>
        </div>
        <button class="ib" onClick=${mapTick} aria-label="Abrir navegação" style="width:54px;height:54px;flex:none;border-radius:50%;background:#0B0C0E;box-shadow:inset 0 0 0 1px #24272B;color:#fff">${I.nav()}</button>`;
    }

    renderCall() {
      const s = this.state;
      const callMute = () => this.setState({ callMuted: !s.callMuted });
      const callVid = () => this.setState({ callVideo: !s.callVideo });
      const hang = () => { host.media('hangup'); this.collapse(); };
      return html`<div style="position:relative;width:84px;height:84px;flex:none">
          <div style="position:absolute;inset:4px;border-radius:50%;overflow:hidden;box-shadow:0 0 0 3px #25D366, 0 0 0 7px rgba(255,255,255,.04), 0 0 22px rgba(37,211,102,.5)">
            ${s.callVideo
              ? html`<div style="position:absolute;inset:0;background:radial-gradient(120% 95% at 50% 118%, #7A5341 0%, #3E2C27 55%, #1A1413 100%)"></div>
                <div class="vlight" style="position:absolute;left:-10px;top:-6px;width:44px;height:44px;border-radius:50%;background:radial-gradient(circle, rgba(255,214,170,.6), transparent 70%)"></div>
                <div class="vsub" style="position:absolute;left:50%;bottom:-10px;width:42px;height:48px;border-radius:48% 48% 44% 44%;background:linear-gradient(#D7A585, #B9806180)"></div>
                <div class="vsub" style="position:absolute;left:50%;bottom:-30px;width:66px;height:40px;border-radius:40% 40% 0 0;background:#24201E"></div>
                <span class="pulse" style="position:absolute;top:7px;left:8px;width:7px;height:7px;border-radius:50%;background:#FF3B30;box-shadow:0 0 7px rgba(255,59,48,.8)"></span>
                <div style="position:absolute;right:5px;top:5px;width:20px;height:26px;border-radius:5px;background:linear-gradient(140deg, #3C5A6E, #1E2E38);box-shadow:inset 0 0 0 1.5px rgba(255,255,255,.55);overflow:hidden"><div style="position:absolute;left:50%;bottom:-4px;transform:translateX(-50%);width:11px;height:13px;border-radius:45%;background:#C98B6B"></div></div>`
              : html`<div style="position:absolute;inset:0;background:#1C1D20;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;color:#6E737A">${I.video(22, true)}<span style="font-size:9px;font-weight:600">Sem vídeo</span></div>`}
          </div>
          <div style="position:absolute;right:-2px;bottom:-1px;width:28px;height:28px;border-radius:50%;background:#25D366;box-shadow:0 0 0 4px #000, 0 0 12px rgba(37,211,102,.6);color:#fff;display:flex;align-items:center;justify-content:center">${I.phoneFill()}</div>
        </div>
        <div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:5px">
          <div style="display:flex;align-items:center;gap:7px;font-size:12px;color:#8D9298">
            <span style="width:7px;height:7px;border-radius:50%;background:#2FB566;box-shadow:0 0 8px rgba(47,181,102,.7);flex:none"></span>
            <span style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${s.callApp}</span>
          </div>
          <div style="font-size:16px;font-weight:700;letter-spacing:-.3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${s.callName}</div>
          <div style="display:flex;align-items:center;gap:6px;font-size:12px;color:#9A9FA6;min-width:0">${I.video(13, false)}<span class="mono">${fmt(s.callDur)}</span></div>
        </div>
        <div style="display:flex;gap:9px;flex:none;align-items:center">
          <button class="ib" onClick=${callMute} aria-label="Silenciar microfone" style=${'width:42px;height:42px;border-radius:50%;color:#fff;transition:background .2s ease;background:' + (s.callMuted ? '#E5484D' : '#2A2C30')}>
            <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10a7 7 0 0 0 14 0M12 17v5"/>${s.callMuted ? html`<path d="M3 3l18 18"/>` : null}</svg>
          </button>
          <button class="ib" onClick=${callVid} aria-label="Ligar ou desligar vídeo" style=${'width:42px;height:42px;border-radius:50%;color:#fff;transition:background .2s ease;background:' + (s.callVideo ? '#25D366' : '#2A2C30')}>${I.video(19, !s.callVideo)}</button>
          <button class="ib" onClick=${hang} aria-label="Encerrar chamada" style="width:42px;height:42px;border-radius:50%;background:#E5484D;color:#fff">${I.phone(19, 2.4, 'transform:rotate(135deg)')}</button>
        </div>`;
    }

    renderYt() {
      const s = this.state;
      const pct = Math.round((s.ytPos / s.ytDur) * 1000) / 10;
      const ytPlay = () => { if (s.ytReal) host.media(s.ytPlaying ? 'pause' : 'play'); this.setState({ ytPlaying: !s.ytPlaying }); };
      const ytPrev = () => { if (s.ytReal) host.media('prev'); this.setState({ ytPos: 0 }); };
      const ytNext = () => { if (s.ytReal) host.media('next'); this.setState({ ytPos: 0, ytPlaying: true }); };
      const ytSeek = (e) => { const p = Math.round((+e.target.value / 100) * s.ytDur); if (s.ytReal) host.media('seek:' + p * 1000); this.setState({ ytPos: p }); };
      return html`<div style="position:relative;width:84px;height:84px;flex:none">
          <div style="position:absolute;inset:4px;border-radius:50%;overflow:hidden;box-shadow:0 0 0 3px #FF0000, 0 0 0 7px rgba(255,255,255,.04), 0 0 22px rgba(255,0,0,.5)">
            <div style="position:absolute;inset:0;background:radial-gradient(120% 95% at 50% 118%, #4A3A6E 0%, #2A2440 55%, #141221 100%)"></div>
            <div class="vlight" style="position:absolute;right:-8px;top:-6px;width:42px;height:42px;border-radius:50%;background:radial-gradient(circle, rgba(120,170,255,.6), transparent 70%)"></div>
            <div class="vsub" style="position:absolute;left:50%;bottom:-10px;width:44px;height:50px;border-radius:48% 48% 44% 44%;background:linear-gradient(#D7A585, #B9806180)"></div>
            <div class="vsub" style="position:absolute;left:50%;bottom:8px;width:54px;height:22px;border-radius:26px 26px 0 0;background:#14121F"></div>
            <div class="vsub" style="position:absolute;left:50%;bottom:-30px;width:66px;height:40px;border-radius:40% 40% 0 0;background:#1A1830"></div>
            <span class="pulse" style="position:absolute;top:7px;left:8px;width:7px;height:7px;border-radius:50%;background:#FF3B30;box-shadow:0 0 7px rgba(255,59,48,.8)"></span>
          </div>
          <div style="position:absolute;right:-2px;bottom:-1px;width:28px;height:28px;border-radius:50%;background:#FF0000;box-shadow:0 0 0 4px #000, 0 0 12px rgba(255,0,0,.6);color:#fff;display:flex;align-items:center;justify-content:center"><svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg></div>
        </div>
        <div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:4px">
          <div style="display:flex;align-items:center;gap:7px;font-size:12px;color:#8D9298">
            <span style="width:7px;height:7px;border-radius:50%;background:#FF0000;box-shadow:0 0 8px rgba(255,0,0,.7);flex:none"></span>
            <span style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">YouTube</span>
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex:none"><path d="M2 16a5 5 0 0 1 5 5M2 12a9 9 0 0 1 9 9M2 20h.01"/><rect x="2" y="4" width="20" height="14" rx="2"/></svg>
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="flex:none"><path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M16 21h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/></svg>
          </div>
          <div style="font-size:16px;font-weight:700;letter-spacing:-.3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${s.ytTitle}</div>
          <div style="display:flex;align-items:center;gap:7px;font-size:11.5px;color:#9A9FA6;min-width:0">
            <span style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${s.ytChannel}</span>
            <span class="mono" style="flex:none">${fmt(s.ytPos)} / ${fmt(s.ytDur)}</span>
          </div>
          <div style="position:relative;height:14px;display:flex;align-items:center">
            <div style="position:absolute;left:0;right:0;height:4px;border-radius:2px;background:#3A2024"></div>
            <div style=${'position:absolute;left:0;height:4px;border-radius:2px;background:#FF0000;width:' + pct + '%'}></div>
            <span style=${'position:absolute;width:11px;height:11px;margin-left:-5.5px;border-radius:50%;background:#FF0000;box-shadow:0 0 8px rgba(255,0,0,.7);left:' + pct + '%'}></span>
            <input type="range" min="0" max="100" value=${Math.round(pct)} onChange=${ytSeek} aria-label="Avançar vídeo" style="position:absolute;inset:0;width:100%;height:100%;margin:0;opacity:0"/>
          </div>
        </div>
        <div style="display:flex;gap:8px;flex:none;align-items:center">
          <button class="ib" onClick=${ytPrev} aria-label="Anterior" style="width:38px;height:38px;border-radius:50%;background:#1C1D20;color:#fff"><svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M6 5h2v14H6zM20 5v14L9 12z"/></svg></button>
          <button class="ib" onClick=${ytPlay} aria-label="Tocar ou pausar" style="width:46px;height:46px;border-radius:50%;background:#FF0000;box-shadow:0 0 18px rgba(255,0,0,.5);color:#fff">${s.ytPlaying ? I.pause(20) : I.play(20)}</button>
          <button class="ib" onClick=${ytNext} aria-label="Próximo" style="width:38px;height:38px;border-radius:50%;background:#1C1D20;color:#fff"><svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M16 5h2v14h-2zM4 5v14l11-7z"/></svg></button>
        </div>`;
    }

    renderAssist() {
      const s = this.state, st = s.asState;
      const rem = REMS[s.asIdx % REMS.length], delay = DELAYS[s.asDelayIdx % DELAYS.length];
      const orbCls = st === 'ringing' ? 'orbp pulse' : (st === 'created' ? 'orbp' : '');
      const cycleRem = () => this.setState({ asIdx: s.asIdx + 1 });
      const cycleDelay = () => this.setState({ asDelayIdx: s.asDelayIdx + 1 });
      const createRem = () => this.setState({ asState: 'created', asLeft: delay });
      const toCompose = () => this.setState({ asState: 'compose', asLeft: 0, asAnsLeft: 0 });
      const answerRem = () => { host.buzz('stop'); this.setState({ asState: 'answered', asAnsLeft: 4 }); };
      const dismissRem = () => { host.buzz('stop'); toCompose(); };
      const orb = html`<div style="position:relative;width:88px;height:88px;flex:none;display:flex;align-items:center;justify-content:center">
          <span class="orbspin" style="position:absolute;width:78px;height:78px;border-radius:50%;background:conic-gradient(from 0deg, #8A7CFF, #5BC0FF, #C06CFF, #8A7CFF)"></span>
          ${st === 'ringing' ? html`<span class="ring2" style="position:absolute;width:78px;height:78px;border-radius:50%;box-shadow:0 0 0 2px rgba(138,124,255,.6)"></span>` : null}
          <span class=${orbCls} style="position:absolute;width:66px;height:66px;border-radius:50%;background:radial-gradient(circle at 35% 30%, #2A2740, #15131F);display:flex;align-items:center;justify-content:center;color:#C9C4FF">
            ${st === 'compose' || st === 'created' ? I.spark(26) : null}
            ${st === 'ringing' ? I.bell(26) : null}
            ${st === 'answered' ? I.wave(5, '#C9C4FF') : null}
          </span>
        </div>`;
      let body;
      if (st === 'compose') {
        body = html`<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:5px">
            <div style="display:flex;align-items:center;gap:7px;font-size:12px;color:#9A8FFF">${I.spark(13)}<span style="color:#8D9298">Assistente</span></div>
            <div style="font-size:16px;font-weight:700;letter-spacing:-.3px">Novo lembrete</div>
            <div style="display:flex;align-items:center;gap:8px;min-width:0">
              <button class="ib" onClick=${cycleRem} aria-label="Trocar lembrete" style="flex:1;min-width:0;height:32px;border-radius:16px;background:#1B1C22;box-shadow:inset 0 0 0 1px #34334A;color:#fff;justify-content:flex-start;gap:7px;padding:0 12px;font-size:13px;font-weight:600"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#9A8FFF" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="flex:none"><path d="M12 8v4l3 2"/><circle cx="12" cy="12" r="9"/></svg><span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${rem}</span></button>
              <button class="ib" onClick=${cycleDelay} aria-label="Trocar quando ligar" style="flex:none;height:32px;border-radius:16px;background:transparent;box-shadow:inset 0 0 0 1px #4A3FA0;color:#C9C4FF;padding:0 12px;font-size:12px;font-weight:700;white-space:nowrap">em ${delay}s</button>
            </div>
          </div>
          <button class="ib fx" onClick=${createRem} aria-label="Criar lembrete" style="width:52px;height:52px;flex:none;border-radius:50%;background:#8A7CFF;box-shadow:0 0 20px rgba(138,124,255,.55);color:#fff">${I.plus()}</button>`;
      } else if (st === 'created') {
        body = html`<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:4px">
            <div style="font-size:12px;color:#8D9298">Lembrete programado</div>
            <div style="font-size:16px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${rem}</div>
            <div style="display:flex;align-items:center;gap:6px;font-size:12px;color:#9A8FFF">${I.bell(13)}Vou te ligar em ${s.asLeft}s</div>
          </div>
          <button class="ib" onClick=${toCompose} aria-label="Cancelar lembrete" style="width:48px;height:48px;flex:none;border-radius:50%;background:#2A2C30;color:#fff">${I.x(18)}</button>`;
      } else if (st === 'ringing') {
        body = html`<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:3px">
            <div class="pulse" style="font-size:12px;color:#9A8FFF;font-weight:600">Assistente chamando…</div>
            <div style="font-size:12px;color:#8D9298">Lembrete</div>
            <div style="font-size:16px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${rem}</div>
          </div>
          <div style="display:flex;gap:8px;flex:none;align-items:center">
            <button class="ib" onClick=${dismissRem} aria-label="Dispensar" style="width:46px;height:46px;border-radius:50%;background:#E5484D;color:#fff">${I.x(20)}</button>
            <button class="ib fx" onClick=${answerRem} aria-label="Atender" style="width:48px;height:48px;border-radius:50%;background:#2FB566;box-shadow:0 0 18px rgba(47,181,102,.5);color:#fff">${I.phone(21, 2.4)}</button>
          </div>`;
      } else {
        body = html`<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:3px">
            <div style="font-size:12px;color:#9A8FFF;font-weight:600">Lembrete</div>
            <div style="font-size:16px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${rem}</div>
            <div style="display:flex;align-items:center;gap:7px;font-size:12px;color:#8D9298"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#9A8FFF" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5L6 9H2v6h4l5 4zM15.5 8.5a5 5 0 0 1 0 7M19 5a9 9 0 0 1 0 14"/></svg>Reproduzindo o lembrete…</div>
          </div>
          <button class="ib" onClick=${toCompose} aria-label="Pronto" style="width:48px;height:48px;flex:none;border-radius:50%;background:#8A7CFF;box-shadow:0 0 18px rgba(138,124,255,.5);color:#fff">${I.check(20, 2.8)}</button>`;
      }
      return html`${orb}${body}`;
    }

    renderCal() {
      const now = new Date();
      return html`<div style="position:relative;width:80px;height:80px;flex:none;border-radius:50%;background:#140806;box-shadow:inset 0 0 0 1px #2A1410, inset 0 0 28px rgba(234,67,53,.45);display:flex;align-items:center;justify-content:center">
          <div style="width:48px;height:52px;border-radius:12px;background:#F4F4F2;overflow:hidden;display:flex;flex-direction:column;align-items:center;box-shadow:0 6px 16px rgba(0,0,0,.4)">
            <div style="width:100%;height:18px;background:#EA4335;color:#fff;font-size:10px;font-weight:800;display:flex;align-items:center;justify-content:center;letter-spacing:.5px">${DOWS[(now.getDay() + 6) % 7]}</div>
            <div style="font-size:22px;font-weight:700;color:#111;line-height:1;margin-top:3px">${now.getDate()}</div>
            <div style="width:5px;height:5px;border-radius:50%;background:#EA4335;margin-top:3px"></div>
          </div>
        </div>
        <div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:2px">
          <div style="display:flex;align-items:center;gap:6px;font-size:11px;color:#8D9298">
            <span style="flex:1">Calendário</span><span>${this.weekCount()} eventos</span><span style="width:8px;height:8px;border-radius:50%;background:#2F8CFF"></span>
          </div>
          <div style="font-size:15px;font-weight:700">Sua semana</div>
          <div style="display:grid;grid-template-columns:repeat(7, minmax(0, 1fr));gap:2px;padding:3px 0;box-shadow:inset 0 1px 0 #1E2023, inset 0 -1px 0 #1E2023">
            ${this.week().map((d) => html`<button class="ib" onClick=${d.pick} aria-label=${d.dow + ' ' + d.num} style=${'height:38px;border-radius:9px;color:#fff;flex-direction:column;gap:2px;padding:0;background:' + d.bg + ';box-shadow:' + d.ring}>
                <span style="font-size:8px;color:#8D9298;font-weight:600">${d.dow}</span>
                <span style="font-size:14px;font-weight:600;line-height:1">${d.num}</span>
                <span style="display:flex;gap:2px;height:4px">${d.dots.map((c) => html`<span style=${'width:4px;height:4px;border-radius:50%;background:' + c}></span>`)}</span>
              </button>`)}
          </div>
          <div style="font-size:10.5px;color:#8D9298;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${this.calInfo()}</div>
        </div>
        <button class="ib" onClick=${() => host.openApp('cal')} aria-label="Abrir calendário" style="width:46px;height:46px;flex:none;border-radius:50%;background:#141518;box-shadow:inset 0 0 0 1px #2E3034;color:#fff">${I.ext()}</button>`;
    }

    weekCount() {
      const c = this.state.cal;
      if (!c || !c.days) return 0;
      return c.days.reduce((a, d) => a + (d ? d.length : 0), 0);
    }

    renderFile() {
      const s = this.state, fs = s.file;
      const prog = fs === 'sending' ? s.prog : (fs === 'sent' ? 100 : 0);
      const title = fs === 'idle' ? 'Solte para enviar' : fs === 'ready' ? 'Pronto para enviar' : fs === 'sending' ? 'Enviando… ' + s.prog + '%' : 'Enviado';
      const sub = fs === 'idle' ? 'Arraste o arquivo para dentro da cápsula' : fs === 'sent' ? 'Arquivo entregue com sucesso' : 'Toque no botão azul para enviar';
      const btnBg = fs === 'idle' ? '#1B1D21' : (fs === 'sent' ? '#1FAF5A' : '#2F8CFF');
      const btnGlow = fs === 'idle' ? 'none' : (fs === 'sent' ? '0 0 18px rgba(31,175,90,.6)' : '0 0 20px rgba(47,140,255,.65)');
      const removeFile = () => { clearInterval(this.fi); this.setState({ file: 'idle', prog: 0 }); };
      const sendFile = () => {
        if (s.file === 'sent') { this.setState({ file: 'idle', prog: 0 }); return; }
        if (s.file !== 'ready') return;
        this.setState({ file: 'sending', prog: 0 });
        clearInterval(this.fi);
        this.fi = setInterval(() => {
          const p = this.state.prog + 4;
          if (p >= 100) { clearInterval(this.fi); this.setState({ prog: 100, file: 'sent' }); host.buzz('done'); }
          else this.setState({ prog: p });
        }, 90);
      };
      return html`<div style="position:relative;width:88px;height:88px;flex:none;border-radius:50%;background:#04101C;box-shadow:inset 0 0 0 1px #12314F, inset 0 0 30px rgba(47,140,255,.35);display:flex;align-items:center;justify-content:center;color:#5BC0FF">
          <svg width="88" height="88" viewBox="0 0 104 104" fill="none" style="position:absolute;inset:0" class=${fs === 'sending' ? 'spinslow' : ''}><circle cx="52" cy="52" r="44" stroke="#2F8CFF" stroke-width="1.5" stroke-dasharray="4 7" opacity=".7"/></svg>
          ${fs === 'idle' ? I.download() : html`<div class="fx" style="width:44px;height:54px;border-radius:8px;background:#E5484D;color:#fff;display:flex;align-items:flex-end;justify-content:center;padding-bottom:8px;font-size:11px;font-weight:800;box-shadow:0 6px 16px rgba(229,72,77,.4)">PDF</div>`}
        </div>
        <div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:6px">
          <div style="font-size:17px;font-weight:700">${title}</div>
          <div style="font-size:11.5px;color:#8D9298">${sub}</div>
          ${fs !== 'idle' ? html`<div class="fx" style="height:34px;border-radius:17px;background:#15171A;box-shadow:inset 0 0 0 1px #2A2D31;display:flex;align-items:center;gap:8px;padding:0 4px 0 10px;position:relative;overflow:hidden">
              <div style=${'position:absolute;left:0;top:0;bottom:0;background:rgba(47,140,255,.22);transition:width .12s linear;width:' + prog + '%'}></div>
              <span style="position:relative;width:14px;height:17px;border-radius:3px;background:#E5484D;flex:none"></span>
              <span style="position:relative;flex:1;min-width:0;font-size:12.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">Relatorio.pdf <span style="color:#8D9298">· 2,4 MB</span></span>
              <button class="ib" onClick=${removeFile} aria-label="Remover arquivo" style="position:relative;width:26px;height:26px;border-radius:50%;background:#2A2D31;color:#fff;flex:none">${I.x(12, 3)}</button>
            </div>` : null}
        </div>
        <button class="ib" onClick=${sendFile} aria-label="Enviar arquivo" style="width:54px;height:54px;flex:none;border-radius:50%;background:#0B0C0E;box-shadow:inset 0 0 0 1px #24272B;padding:0">
          <span style=${'width:44px;height:44px;border-radius:50%;color:#fff;display:flex;align-items:center;justify-content:center;transition:background .3s ease;background:' + btnBg + ';box-shadow:' + btnGlow}>
            ${fs !== 'sent' ? html`<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M3 11l18-8-8 18-2-8z"/></svg>` : I.check(22, 3)}
          </span>
        </button>`;
    }

    render() {
      const s = this.state, v = this.vals(), t = v.t;
      const z = this.zoom();
      const toggleOpen = () => { this.touched(); this.setState({ open: !s.open }); };
      let body;
      if (t.wheel) body = this.renderWheel(v);
      else if (t.msg) body = this.renderMsg(v);
      else if (t.map) body = this.renderMap(v);
      else if (t.call) body = this.renderCall();
      else if (t.yt) body = this.renderYt();
      else if (t.assist) body = this.renderAssist();
      else if (t.cal) body = this.renderCal();
      else body = this.renderFile();
      const color = v.bub.color;
      const d = s.drag;
      const showCard = s.open && t.file && s.file === 'idle';

      return html`<div style="position:fixed;inset:0">
        ${s.open ? html`<div onPointerDown=${() => this.collapse()} style="position:absolute;inset:0;z-index:1"></div>` : null}

        <div style=${'position:absolute;z-index:5;left:50%;margin-left:-24px;width:48px;height:48px;display:flex;align-items:center;justify-content:center;top:' + (s.cy - 24) + 'px;transform:scale(' + s.bscale + ')'}>
          <span class="cbhalo" style=${'position:absolute;width:46px;height:46px;border-radius:50%;pointer-events:none;background:radial-gradient(circle, ' + color + '66, transparent 68%)'}></span>
          <span class="cbring" style=${'position:absolute;width:34px;height:34px;border-radius:50%;pointer-events:none;box-shadow:0 0 0 1.5px ' + color}></span>
          <span class="cbring d" style=${'position:absolute;width:34px;height:34px;border-radius:50%;pointer-events:none;box-shadow:0 0 0 1.5px ' + color}></span>
          <button class="ib" onClick=${toggleOpen} aria-label="Abrir ilha" style="position:relative;width:34px;height:34px;border-radius:50%;background:radial-gradient(circle at 50% 38%, #141418, #050506);box-shadow:inset 0 0 0 1px rgba(255,255,255,.06);padding:0">
            <svg width="34" height="34" viewBox="0 0 34 34" style="position:absolute;inset:0"><circle cx="17" cy="17" r="15" fill="none" stroke="#1A1B20" stroke-width="2"/><circle cx="17" cy="17" r="15" fill="none" stroke=${color} stroke-width="2.2" stroke-linecap="round" stroke-dasharray="94.2" stroke-dashoffset=${v.bub.off34} transform="rotate(-90 17 17)" style=${'transition:stroke-dashoffset 1s linear;filter:drop-shadow(0 0 3px ' + color + ')'}/></svg>
            <span class="cborbit" style="position:absolute;inset:1px"><span style=${'position:absolute;top:-1px;left:50%;margin-left:-2.5px;width:5px;height:5px;border-radius:50%;background:#fff;box-shadow:0 0 7px ' + color + ', 0 0 3px #fff'}></span></span>
          </button>
        </div>

        ${s.open ? html`<div style=${'position:absolute;z-index:5;left:50%;transform:translateX(-50%);display:flex;top:' + (s.cy + 26) + 'px'}
              onPointerDown=${(e) => this.capDown(e)} onPointerUp=${(e) => this.capUp(e)}>
            <div class="drop" style=${'zoom:' + z + ';width:370px;height:132px;border-radius:66px;background:#000;color:#fff;overflow:hidden;position:relative;transition:box-shadow .4s ease;box-shadow:' + (CAP_SHADOW[s.tab] || CAP_SHADOW_DEFAULT)}>
              <div class="fx" key=${s.tab} style="height:100%;padding:10px 14px 18px 10px;display:flex;gap:12px;align-items:center">
                ${body}
              </div>
              <button class="ib" onClick=${() => this.stepTab(1)} aria-label="Trocar de aba" style="position:absolute;left:50%;bottom:0;transform:translateX(-50%);width:110px;height:16px;background:transparent;padding:0">
                <span style="width:72px;height:4px;border-radius:2px;background:rgba(255,255,255,.28);display:block"></span>
              </button>
            </div>
          </div>` : null}

        ${showCard ? html`
          <div style=${'position:absolute;z-index:5;left:50%;margin-left:-10px;display:flex;flex-direction:column;align-items:center;gap:6px;pointer-events:none;top:' + (s.cy + 26 + 132 * z + 16) + 'px'}>
            <svg width="20" height="44" viewBox="0 0 20 44" fill="none" stroke="#5BC0FF" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" class="bob"><path d="M10 42V6" stroke-dasharray="4 5"/><path d="M3 12l7-8 7 8"/></svg>
          </div>
          <button class=${'ib card' + (d ? ' dragging' : '')}
            onPointerDown=${(e) => this.cardDown(e)} onPointerMove=${(e) => this.cardMove(e)} onPointerUp=${(e) => this.cardUp(e)} onPointerCancel=${() => this.setState({ drag: null })}
            aria-label="Arraste o Relatorio.pdf até a cápsula"
            style=${'position:absolute;z-index:6;left:50%;margin-left:-60px;width:120px;height:124px;border-radius:22px;background:#F7F8FA;color:#17181A;flex-direction:column;gap:6px;touch-action:none;box-shadow:0 16px 34px rgba(0,0,0,.35), 0 0 0 1px rgba(255,255,255,.6);top:' + (s.cy + 26 + 132 * z + 70) + 'px' + (d ? ';transform:translate(' + d.dx + 'px,' + d.dy + 'px) scale(.92)' : '')}>
            <span style="width:42px;height:50px;border-radius:8px;background:#E5484D;color:#fff;display:flex;align-items:flex-end;justify-content:center;padding-bottom:7px;font-size:10px;font-weight:800">PDF</span>
            <span style="font-size:13px;font-weight:700">Relatorio.pdf</span>
            <span style="font-size:11px;color:#5C6066;margin-top:-4px">2,4 MB</span>
          </button>` : null}
      </div>`;
    }
  }

  render(html`<${Island} />`, document.getElementById('root'));
})();
