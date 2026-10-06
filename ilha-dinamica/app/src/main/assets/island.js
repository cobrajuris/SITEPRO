/* Ilha dinâmica — bolha em volta da câmera + cápsula compacta logo abaixo.
   Baseada no protótipo "Bolha flutuante Android" (Bolha da câmera). */
(function () {
  'use strict';
  const { html, render, Component } = window.htmPreact;

  /* ---------- ponte com o Android (tudo protegido: um erro aqui nunca derruba a ilha) ---------- */
  const H = window.IslandHost || null;
  const call = (name, ...a) => { try { if (H && H[name]) return H[name](...a); } catch (e) { /* ignora */ } return undefined; };
  const host = {
    resize: (w, h) => call('resize', w, h),
    report: (j) => call('report', j),
    media: (a) => call('media', a),
    setVolume: (v) => call('setVolume', v),
    setBrightness: (v, m) => call('setBrightness', v, m),
    buzz: (k) => call('buzz', k),
    openApp: (t) => call('openApp', t),
    ready: () => call('ready')
  };

  /* ---------- medidas ---------- */
  const CW = 340;            // largura da cápsula (antes 370)
  const CH = 92;             // altura da cápsula (antes 132)
  const LEFT = CH - 16;      // círculo esquerdo concêntrico à curva da cápsula
  const OUT_MS = 220;

  const TAB_DEFS = [['music', 'Música'], ['timer', 'Timer'], ['bright', 'Brilho'], ['assist', 'Assistente'], ['cal', 'Calendário'], ['ig', 'Instagram'], ['wa', 'WhatsApp'], ['tg', 'Telegram'], ['tt', 'TikTok'], ['call', 'Chamada'], ['yt', 'YouTube'], ['ifood', 'iFood'], ['maps', 'Maps'], ['file', 'Enviar arquivo']];
  const ORDER = TAB_DEFS.map((t) => t[0]);
  const COLOR = { music: '#E8814B', timer: '#FFB547', bright: '#E6E14A', cal: '#EA4335', ig: '#E1306C', wa: '#25D366', tg: '#2AABEE', tt: '#FE2C55', ifood: '#EA1D2C', maps: '#4285F4', call: '#25D366', yt: '#FF0000', assist: '#8A7CFF', file: '#2F8CFF' };
  const rgba = (hex, a) => { const n = parseInt(hex.slice(1), 16); return 'rgba(' + (n >> 16) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a + ')'; };
  const capShadow = (tab) => {
    const c = COLOR[tab];
    if (tab === 'music' || tab === 'timer' || tab === 'bright' || tab === 'cal') return 'inset 0 0 0 1px rgba(255,255,255,.08), 0 10px 28px rgba(0,0,0,.42)';
    return 'inset 0 0 0 1px ' + rgba(c, .7) + ', 0 0 18px ' + rgba(c, .32) + ', 0 10px 28px rgba(0,0,0,.42)';
  };
  const APPS = {
    ig: { app: 'Instagram', color: '#E1306C', avBg: '#5A2A3A' },
    wa: { app: 'WhatsApp', color: '#1FAF5A', avBg: '#2C4A3A' },
    tg: { app: 'Telegram', color: '#2AABEE', avBg: '#27445A' },
    tt: { app: 'TikTok', color: '#FE2C55', avBg: '#3A2730' }
  };
  const TRACKS = ['[Faixa 1]', '[Faixa 2]', '[Faixa 3]', '[Faixa 4]', '[Faixa 5]'];
  const PRESETS = [1, 3, 5, 10, 15];
  const MODES = ['Automático', 'Leitura', 'Noite', 'Vívido'];
  const REMS = ['Tomar o remédio', 'Reunião às 15h', 'Beber água', 'Treinar 18h', 'Ligar pra mãe', 'Pagar o boleto'];
  const DELAYS = [5, 10, 30];
  const DOWS = ['SEG', 'TER', 'QUA', 'QUI', 'SEX', 'SÁB', 'DOM'];

  const clamp = (x, a, b) => Math.max(a, Math.min(b, x));
  const fmt = (sec) => { sec = Math.max(0, Math.floor(sec || 0)); const m = Math.floor(sec / 60), x = sec % 60; return m + ':' + (x < 10 ? '0' : '') + x; };
  const initials = (name) => {
    const p = String(name || '').replace(/[^\p{L}\p{N}\s]/gu, '').trim().split(/\s+/).filter(Boolean);
    if (!p.length) return '?';
    if (p.length === 1) return p[0].slice(0, 2).toUpperCase();
    return (p[0][0] + p[p.length - 1][0]).toUpperCase();
  };
  const ring = (size, stroke, color, track, pct, sw, trans) => {
    const r = (size - sw) / 2, c = 2 * Math.PI * r;
    return html`<svg width=${size} height=${size} viewBox=${'0 0 ' + size + ' ' + size} style="position:absolute;inset:0">
      <circle cx=${size / 2} cy=${size / 2} r=${r} fill="none" stroke=${track} stroke-width=${sw}/>
      <circle cx=${size / 2} cy=${size / 2} r=${r} fill="none" stroke=${color} stroke-width=${stroke} stroke-linecap="round"
        stroke-dasharray=${c.toFixed(2)} stroke-dashoffset=${(c * (1 - clamp(pct, 0, 1))).toFixed(2)}
        transform=${'rotate(-90 ' + size / 2 + ' ' + size / 2 + ')'} style=${'transition:stroke-dashoffset ' + (trans || '1s linear')}/>
    </svg>`;
  };

  /* ---------- ícones ---------- */
  const I = {
    pause: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="currentColor"><path d="M6 4h4v16H6zM14 4h4v16h-4z"/></svg>`,
    play: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="currentColor"><path d="M7 4l13 8-13 8z"/></svg>`,
    prev: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="currentColor"><path d="M6 5h2v14H6zM20 5v14L9 12z"/></svg>`,
    next: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="currentColor"><path d="M16 5h2v14h-2zM4 5v14l11-7z"/></svg>`,
    sun: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>`,
    tiktok: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="currentColor"><path d="M14 3c.3 2.3 1.8 4 4 4.3v2.5c-1.4 0-2.8-.4-4-1.1v5.8a5.3 5.3 0 1 1-5.3-5.3c.3 0 .6 0 .9.1v2.7a2.6 2.6 0 1 0 1.8 2.5V3z"/></svg>`,
    chat: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a8.5 8.5 0 0 1-12.4 7.6L3 21l1.5-5.4A8.5 8.5 0 1 1 21 12z"/></svg>`,
    mic: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10a7 7 0 0 0 14 0M12 17v5"/></svg>`,
    micOff: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10a7 7 0 0 0 14 0M12 17v5"/><path d="M3 3l18 18"/></svg>`,
    x: (s, w) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width=${w || 2.6} stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>`,
    send: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M22 2L11 13"/><path d="M22 2l-7 20-4-9-9-4z"/></svg>`,
    pin: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="currentColor" style="flex:none"><path d="M12 2a7 7 0 0 0-7 7c0 5 7 13 7 13s7-8 7-13a7 7 0 0 0-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z"/></svg>`,
    fork: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 3v7a3 3 0 0 0 3 3v8M7 3v6M10 3v6M18 3c-1.5 0-2.5 2-2.5 5s1 4 2.5 4v8"/></svg>`,
    nav: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="currentColor"><path d="M3 11l18-8-8 18-2-7z"/></svg>`,
    video: (s, slash) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="flex:none"><path d="M23 7l-7 5 7 5z"/><rect x="1" y="5" width="15" height="14" rx="2"/>${slash ? html`<path d="M2 2l20 20"/>` : null}</svg>`,
    phone: (s, sw, style) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width=${sw} stroke-linecap="round" stroke-linejoin="round" style=${style || ''}><path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1.9.4 1.8.7 2.7a2 2 0 0 1-.5 2.1L8 9.8a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.7.7a2 2 0 0 1 1.7 2z"/></svg>`,
    phoneFill: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="currentColor"><path d="M20 15.5a12.5 12.5 0 0 1-4-.6 1 1 0 0 0-1 .2l-1.8 1.8a15 15 0 0 1-6.6-6.6l1.8-1.8a1 1 0 0 0 .2-1 12.5 12.5 0 0 1-.6-4 1 1 0 0 0-1-1H4a1 1 0 0 0-1 1A17 17 0 0 0 20 21a1 1 0 0 0 1-1v-3.5a1 1 0 0 0-1-1z"/></svg>`,
    spark: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="currentColor"><path d="M12 2l1.8 5.6L19.5 9l-4.6 3.3L16.5 18 12 14.6 7.5 18l1.6-5.7L4.5 9l5.7-1.4z"/></svg>`,
    bell: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0"/></svg>`,
    check: (s, w) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width=${w} stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5 9-10"/></svg>`,
    plus: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>`,
    speaker: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="#9A8FFF" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 5L6 9H2v6h4l5 4zM15.5 8.5a5 5 0 0 1 0 7M19 5a9 9 0 0 1 0 14"/></svg>`,
    clock: (s) => html`<svg width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="#9A8FFF" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="flex:none"><path d="M12 8v4l3 2"/><circle cx="12" cy="12" r="9"/></svg>`,
    download: (s) => html`<svg class="bob" width=${s} height=${s} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12"/><path d="M7 10l5 5 5-5"/><path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/></svg>`,
    wave: (n, color) => {
      const d = [0, .18, .36, .12, .3, .48, .06, .24, .42, .14, .33, .5, .09, .27, .45, .15].slice(0, n);
      return html`<span class="wave" style=${'height:18px;color:' + color}>${d.map((x) => html`<span style=${'animation-delay:' + x + 's'}></span>`)}</span>`;
    }
  };

  const T = {
    head: 'display:flex;align-items:center;gap:6px;font-size:11px;color:#8D9298;line-height:14px;height:14px',
    title: 'font-size:16px;font-weight:700;letter-spacing:-.2px;line-height:20px',
    sub: 'font-size:12.5px;color:#9A9FA6;line-height:16px',
    col: 'flex:1;min-width:0;display:flex;flex-direction:column;gap:2px',
    left: 'position:relative;width:' + LEFT + 'px;height:' + LEFT + 'px;flex:none',
    btn: (sz, bg, extra) => 'width:' + sz + 'px;height:' + sz + 'px;flex:none;border-radius:50%;color:#fff;background:' + bg + (extra ? ';' + extra : '')
  };

  class Island extends Component {
    constructor() {
      super();
      const now = new Date();
      this.state = {
        open: false, closing: false, tab: 'music',
        cy: 28, screenW: 394, scale: 1, bscale: 1,
        playing: true, pos: 72, dur: 214, trk: 1, vol: 80,
        real: false, title: '', artist: '', art: '',
        tIdx: 2, timer: 300, total: 300, run: false,
        mIdx: 0, bright: 68,
        recording: false, recSecs: 0,
        msgs: { ig: 'Te mandei as fotos agora.', wa: 'Oi, já enviei o arquivo pra você.', tg: 'Acabei de te enviar o link.', tt: 'Te enviei o vídeo agora.' },
        names: { ig: 'Marina', wa: 'Carlos', tg: 'Lucas', tt: 'Lara' },
        avatars: { ig: '', wa: '', tg: '', tt: '' },
        file: 'idle', prog: 0, day: (now.getDay() + 6) % 7,
        cal: null,
        tk: 0, ifMin: 8, gmMin: 18, gmKm: 74,
        geo: { ifood: null, maps: null },
        callName: 'Marina', callApp: 'WhatsApp', callAvatar: '',
        callDur: 768, callMuted: false, callVideo: true,
        ytPos: 768, ytDur: 1450, ytPlaying: true, ytReal: false, ytTitle: 'Podcast em andamento', ytChannel: 'Canal Tech Brasil', ytArt: '',
        asState: 'compose', asIdx: 0, asDelayIdx: 0, asLeft: 0, asAnsLeft: 0,
        drag: null
      };
      this.lastSize = '';
      this.lastReport = '';
      this.autoT = null;
      this.outT = null;
      this.swipe = null;
    }

    componentDidMount() {
      this.iv = setInterval(() => { try { this.tick(); } catch (e) { /* nunca para o relógio */ } }, 1000);
      window.island = {
        cmd: (c) => {
          try { this.cmd(typeof c === 'string' ? JSON.parse(c) : c); } catch (e) { if (window.console) console.error('cmd', e && e.message); }
        },
        state: () => this.state
      };
      this.sync();
      host.ready();
    }

    componentDidUpdate() { this.sync(); }

    componentWillUnmount() { clearInterval(this.iv); clearInterval(this.fi); clearTimeout(this.autoT); clearTimeout(this.outT); }

    tick() {
      const s = this.state, n = { tk: s.tk + 1 };
      if (s.playing) n.pos = s.real ? Math.min(s.pos + 1, s.dur) : (s.pos + 1) % s.dur;
      if (s.run && s.timer > 0) n.timer = s.timer - 1;
      if (s.run && s.timer <= 1) { n.run = false; host.buzz('timer'); n.tab = 'timer'; this.openNow(n); }
      if (s.recording) n.recSecs = s.recSecs + 1;
      if (s.tab === 'call') n.callDur = s.callDur + 1;
      if (s.ytPlaying && s.ytPos < s.ytDur) n.ytPos = s.ytPos + 1;
      if (s.asState === 'created') {
        if (s.asLeft > 1) { n.asLeft = s.asLeft - 1; }
        else { n.asState = 'ringing'; n.asLeft = 0; n.tab = 'assist'; this.openNow(n); host.buzz('ring'); }
      }
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

    /* ---------- abrir / fechar com animação ---------- */
    openNow(n) {
      clearTimeout(this.outT);
      n.open = true; n.closing = false;
      return n;
    }
    open() { this.touched(); this.setState(this.openNow({})); }
    collapse() {
      clearTimeout(this.autoT);
      const s = this.state;
      if (!s.open || s.closing) return;
      this.setState({ closing: true, recording: false, recSecs: 0, drag: null });
      clearTimeout(this.outT);
      this.outT = setTimeout(() => this.setState({ open: false, closing: false }), OUT_MS);
    }
    toggle() { if (this.state.open && !this.state.closing) this.collapse(); else this.open(); }

    /* ---------- comandos vindos do Android ---------- */
    cmd(c) {
      const s = this.state;
      switch (c.type) {
        case 'cfg': {
          const n = {};
          ['cy', 'screenW', 'scale', 'bscale'].forEach((k) => { if (c[k] != null && isFinite(+c[k])) n[k] = +c[k]; });
          this.setState(n);
          break;
        }
        case 'tab': this.pickTab(c.id, c.open !== false); break;
        case 'open': this.open(); break;
        case 'close': this.collapse(); break;
        case 'toggle': this.toggle(); break;
        case 'media': this.media(c); break;
        case 'volume': if (c.vol != null) this.setState({ vol: clamp(+c.vol, 0, 100) }); break;
        case 'brightness': this.setState({ bright: clamp(+c.value || 0, 0, 100), mIdx: c.auto ? 0 : (s.mIdx === 0 ? 1 : s.mIdx) }); break;
        case 'calendar': this.setState({ cal: c }); break;
        case 'notify': this.notify(c); break;
      }
    }

    media(c) {
      const s = this.state;
      const n = {};
      if (c.vol != null) n.vol = clamp(+c.vol, 0, 100);
      if (c.src === 'yt') {
        Object.assign(n, { ytReal: true, ytTitle: c.title || 'YouTube', ytChannel: c.artist || '', ytPlaying: !!c.playing, ytPos: Math.max(0, c.pos || 0), ytDur: Math.max(1, c.dur || s.ytDur) });
        if (c.art !== undefined) n.ytArt = c.art || '';
      } else if (c.src === 'music') {
        Object.assign(n, { real: true, title: c.title || 'Tocando agora', artist: c.artist || '', playing: !!c.playing, pos: Math.max(0, c.pos || 0), dur: Math.max(1, c.dur || 1) });
        if (c.art !== undefined) n.art = c.art || '';
      } else if (c.src === 'none') {
        Object.assign(n, { real: false, art: '', ytReal: false, ytArt: '' });
      }
      if (c.show && (c.src === 'yt' || c.src === 'music')) {
        n.tab = c.src;
        if (!s.open) { this.openNow(n); this.armAutoCollapse(6000); }
      }
      this.setState(n);
    }

    notify(c) {
      const s = this.state, tab = c.tab;
      if (ORDER.indexOf(tab) < 0) return;
      const n = this.openNow({ tab, recording: false, recSecs: 0 });
      if (APPS[tab]) {
        n.msgs = Object.assign({}, s.msgs, { [tab]: c.text || '' });
        n.names = Object.assign({}, s.names, { [tab]: c.title || APPS[tab].app });
        n.avatars = Object.assign({}, s.avatars, { [tab]: c.avatar || '' });
      } else if (tab === 'ifood' || tab === 'maps') {
        n.geo = Object.assign({}, s.geo, { [tab]: { title: c.title || '', sub: c.text || '', addr: c.sub || '' } });
      } else if (tab === 'call') {
        Object.assign(n, { callName: c.title || 'Chamada', callApp: c.app || 'WhatsApp', callAvatar: c.avatar || '', callDur: 0, callMuted: false });
      }
      this.setState(n);
      this.armAutoCollapse(c.hold || 7000);
    }

    armAutoCollapse(ms) {
      clearTimeout(this.autoT);
      this.autoT = setTimeout(() => {
        const s = this.state;
        if (!s.recording && s.asState !== 'ringing' && s.tab !== 'call') this.collapse();
      }, ms);
    }

    touched() { clearTimeout(this.autoT); }

    pickTab(id, open) {
      const s = this.state;
      if (ORDER.indexOf(id) < 0) return;
      const n = { tab: id, recording: false, recSecs: 0, file: id === 'file' ? 'idle' : s.file, prog: 0, drag: null };
      if (open) this.openNow(n);
      this.touched();
      this.setState(n);
    }

    stepTab(d) {
      const i = ORDER.indexOf(this.state.tab);
      this.pickTab(ORDER[(i + d + ORDER.length) % ORDER.length], true);
    }

    /* ---------- janela flutuante: tamanho e estado ---------- */
    zoom() {
      const s = this.state;
      return clamp(Math.min(s.scale, (s.screenW - 20) / CW), 0.5, 1.2);
    }
    capTop() { return this.state.cy + 17 * this.state.bscale + 9; }

    sync() {
      const s = this.state;
      const z = this.zoom();
      let w, h;
      if (!s.open) {
        w = Math.ceil(40 * s.bscale) + 6;
        h = Math.ceil(s.cy + 20 * s.bscale + 4);
      } else {
        w = Math.min(Math.ceil(s.screenW), Math.ceil(CW * z) + 56);
        h = Math.ceil(this.capTop() + CH * z + 40);
        if (s.tab === 'file' && s.file === 'idle') h += 150;
      }
      const key = w + 'x' + h;
      if (key !== this.lastSize) { this.lastSize = key; host.resize(w, h); }
      const rep = JSON.stringify({ tab: s.tab, open: s.open && !s.closing });
      if (rep !== this.lastReport) { this.lastReport = rep; host.report(rep); }
    }

    /* ---------- arrastar o arquivo até a cápsula ---------- */
    cardTop() { return this.capTop() + CH * this.zoom() + 44; }
    cardDown(e) {
      e.stopPropagation();
      this.touched();
      try { e.currentTarget.setPointerCapture(e.pointerId); } catch (x) { /* ok */ }
      this.setState({ drag: { x0: e.clientX, y0: e.clientY, dx: 0, dy: 0 } });
    }
    cardMove(e) {
      const d = this.state.drag;
      if (d) this.setState({ drag: Object.assign({}, d, { dx: e.clientX - d.x0, dy: e.clientY - d.y0 }) });
    }
    cardUp() {
      const d = this.state.drag;
      if (!d) return;
      const moved = Math.abs(d.dx) + Math.abs(d.dy);
      const capBottom = this.capTop() + CH * this.zoom();
      this.setState({ drag: null });
      if (moved < 10 || this.cardTop() + d.dy < capBottom + 20) this.dropFile();
    }
    dropFile() { if (this.state.tab === 'file' && this.state.file === 'idle') this.setState({ file: 'ready' }); }

    /* ---------- gestos na cápsula: lados troca de aba, para cima recolhe ---------- */
    capDown(e) { this.touched(); this.swipe = { x: e.clientX, y: e.clientY }; }
    capUp(e) {
      const sw = this.swipe; this.swipe = null;
      if (!sw) return;
      const dx = e.clientX - sw.x, dy = e.clientY - sw.y;
      if (Math.abs(dx) > 56 && Math.abs(dx) > Math.abs(dy) * 1.5) this.stepTab(dx < 0 ? 1 : -1);
      else if (dy < -36 && Math.abs(dy) > Math.abs(dx) * 1.5) this.collapse();
    }

    progress() {
      const s = this.state;
      switch (s.tab) {
        case 'music': return s.dur ? s.pos / s.dur : 0;
        case 'timer': return s.total ? s.timer / s.total : 0;
        case 'bright': return s.bright / 100;
        case 'yt': return s.ytPos / s.ytDur;
        case 'file': return s.file === 'sending' ? s.prog / 100 : 1;
        case 'assist': return s.asState === 'created' ? s.asLeft / DELAYS[s.asDelayIdx % DELAYS.length] : 1;
        default: return 1;
      }
    }

    /* ================= abas ================= */

    renderWheel() {
      const s = this.state, tab = s.tab;
      let wheel, num, dot, prevItem, nextItem, numUp, numDown, left;
      const numFor = (title, val, min, max, wrap) => {
        const f = (x) => (wrap ? ((x % (max + 1)) + (max + 1)) % (max + 1) : (x < min || x > max ? '' : x));
        const pad = (x) => (x === '' ? '' : (wrap && x < 10 ? '0' + x : String(x)));
        return { title, a1: pad(f(val + 1)), cur: pad(f(val)), b1: pad(f(val - 1)) };
      };
      if (tab === 'music') {
        if (s.real) {
          wheel = { title: s.artist || 'Tocando agora', prev: 'Anterior', cur: s.title, next: 'Próxima', dotColor: s.playing ? '#FFFFFF' : '#6E737A' };
          dot = () => { host.media(s.playing ? 'pause' : 'play'); this.setState({ playing: !s.playing }); };
          prevItem = () => { host.media('prev'); this.setState({ pos: 0 }); };
          nextItem = () => { host.media('next'); this.setState({ pos: 0 }); };
        } else {
          wheel = { title: 'Álbum', prev: TRACKS[s.trk - 1] || '', cur: TRACKS[s.trk], next: TRACKS[s.trk + 1] || '', dotColor: s.playing ? '#FFFFFF' : '#6E737A' };
          dot = () => this.setState({ playing: !s.playing });
          prevItem = () => this.setState({ trk: clamp(s.trk - 1, 0, TRACKS.length - 1), pos: 0 });
          nextItem = () => this.setState({ trk: clamp(s.trk + 1, 0, TRACKS.length - 1), pos: 0 });
        }
        num = numFor('Vol', s.vol, 0, 100, false);
        numUp = () => { const v = clamp(s.vol + 1, 0, 100); host.setVolume(v); this.setState({ vol: v }); };
        numDown = () => { const v = clamp(s.vol - 1, 0, 100); host.setVolume(v); this.setState({ vol: v }); };
        left = html`<div style=${T.left}>
          <div class=${'spin' + (s.playing ? '' : ' off')} style="position:absolute;inset:0;border-radius:50%;background:#C8452E;overflow:hidden;box-shadow:0 0 0 1px rgba(255,255,255,.1)">
            ${s.real && s.art
              ? html`<img class="cover" src=${s.art}/>`
              : html`<div style="position:absolute;left:-15px;top:34px;width:65px;height:65px;border-radius:50%;background:#F1E6D8"></div>
                <div style="position:absolute;right:8px;top:10px;width:24px;height:24px;border-radius:50%;background:#1A0D0A"></div>
                <div style="position:absolute;right:13px;bottom:18px;width:20px;height:3px;border-radius:3px;background:#1A0D0A"></div>`}
          </div>
          ${ring(LEFT, 2.5, '#E8814B', 'rgba(255,255,255,0)', s.dur ? s.pos / s.dur : 0, 2.5)}
          <button class="ib" onClick=${dot} aria-label="Tocar ou pausar" style="position:absolute;left:50%;top:50%;width:38px;height:38px;margin:-19px 0 0 -19px;border-radius:50%;background:rgba(0,0,0,.45);backdrop-filter:blur(6px);-webkit-backdrop-filter:blur(6px);color:#fff">${s.playing ? I.pause(15) : I.play(15)}</button>
        </div>`;
      } else if (tab === 'timer') {
        const lbl = (i) => (PRESETS[i] ? PRESETS[i] + ' min' : '');
        const pick = (i) => this.setState({ tIdx: i, timer: PRESETS[i] * 60, total: PRESETS[i] * 60, run: false });
        wheel = { title: 'Predefinição', prev: lbl(s.tIdx - 1), cur: lbl(s.tIdx), next: lbl(s.tIdx + 1), dotColor: s.run ? '#FFB547' : '#FFFFFF' };
        num = numFor('Seg', s.timer % 60, 0, 59, true);
        dot = () => this.setState({ run: !s.run, timer: s.timer === 0 ? s.total : s.timer });
        prevItem = () => { if (s.tIdx > 0) pick(s.tIdx - 1); };
        nextItem = () => { if (s.tIdx < PRESETS.length - 1) pick(s.tIdx + 1); };
        numUp = () => this.setState({ timer: s.timer + 1, total: Math.max(s.total, s.timer + 1) });
        numDown = () => this.setState({ timer: Math.max(0, s.timer - 1) });
        left = html`<button class="ib" onClick=${dot} aria-label="Iniciar ou pausar timer" style=${T.left + ';border-radius:50%;background:#0E0F11;color:#FFB547;flex-direction:column'}>
          ${ring(LEFT, 4, '#FFB547', '#2A2010', s.total ? s.timer / s.total : 0, 4)}
          <span class="mono" style="font-size:17px;font-weight:700;line-height:20px">${fmt(s.timer)}</span>
          <span style="font-size:9.5px;color:#A3A8AE">${s.run ? 'rodando' : (s.timer === 0 ? 'fim' : 'pausado')}</span>
        </button>`;
      } else {
        const setMode = (i) => { host.setBrightness(s.bright, i); this.setState({ mIdx: i }); };
        wheel = { title: 'Modo', prev: MODES[s.mIdx - 1] || '', cur: MODES[s.mIdx], next: MODES[s.mIdx + 1] || '', dotColor: '#E6E14A' };
        num = numFor('Brilho', s.bright, 0, 100, false);
        dot = () => setMode((s.mIdx + 1) % MODES.length);
        prevItem = () => setMode(clamp(s.mIdx - 1, 0, MODES.length - 1));
        nextItem = () => setMode(clamp(s.mIdx + 1, 0, MODES.length - 1));
        numUp = () => { const v = clamp(s.bright + 1, 0, 100); host.setBrightness(v, s.mIdx); this.setState({ bright: v }); };
        numDown = () => { const v = clamp(s.bright - 1, 0, 100); host.setBrightness(v, s.mIdx); this.setState({ bright: v }); };
        left = html`<div style=${T.left + ';border-radius:50%;background:#0E0F11;display:flex;align-items:center;justify-content:center;color:#E6E14A'}>
          ${ring(LEFT, 4, '#E6E14A', '#25240E', s.bright / 100, 4, '.4s ease')}
          ${I.sun(28)}
        </div>`;
      }
      const side = 'height:17px;background:transparent;color:#6E737A;justify-content:flex-start;padding:0 12px;font-size:12px;white-space:nowrap;overflow:hidden;width:100%';
      const nb = 'background:transparent;color:#fff;opacity:.4;font-size:17px;font-weight:400;line-height:18px;height:18px';
      return html`${left}
        <div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:1px">
          <div class="ell" style="font-size:9.5px;color:#8D9298;padding-left:12px;height:12px;line-height:12px">${wheel.title}</div>
          <button class="ib" onClick=${prevItem} aria-label="Item anterior" style=${side}>${wheel.prev}</button>
          <div class="roll" key=${'w' + wheel.cur} style="height:28px;border-radius:14px;box-shadow:inset 0 0 0 1px #34363B;display:flex;align-items:center;overflow:hidden">
            <span class="ell" style="flex:1;min-width:0;padding:0 8px 0 12px;font-size:14px;font-weight:600">${wheel.cur}</span>
            <button class="ib" onClick=${dot} aria-label="Confirmar" style="width:32px;height:28px;flex:none;background:#1B1C1F;box-shadow:inset 1px 0 0 #34363B;color:#fff;border-radius:0"><span style=${'width:6px;height:6px;border-radius:50%;display:block;background:' + wheel.dotColor}></span></button>
          </div>
          <button class="ib" onClick=${nextItem} aria-label="Próximo item" style=${side}>${wheel.next}</button>
        </div>
        <div style="width:44px;flex:none;display:flex;flex-direction:column">
          <div style="font-size:9.5px;color:#8D9298;height:12px;line-height:12px">${num.title}</div>
          <div style="display:flex;flex-direction:column;align-items:flex-start">
            <button class="ib" onClick=${numUp} aria-label="Aumentar" style=${nb}>${num.a1}</button>
            <span class="roll" key=${'n' + num.cur} style="display:flex;align-items:center;gap:5px;font-size:26px;font-weight:400;line-height:28px;letter-spacing:-1px">${num.cur}<span style="width:4px;height:4px;border-radius:50%;background:#fff"></span></span>
            <button class="ib" onClick=${numDown} aria-label="Diminuir" style=${nb}>${num.b1}</button>
          </div>
        </div>`;
    }

    avatar(src, label, bg, ringCss) {
      return html`<div style=${'position:absolute;inset:4px;border-radius:50%;overflow:hidden;display:flex;align-items:center;justify-content:center;font-size:23px;font-weight:700;color:#fff;letter-spacing:-1px;background:' + bg + ';box-shadow:' + ringCss}>
        ${src ? html`<img class="cover" src=${src}/>` : label}
      </div>`;
    }

    renderMsg() {
      const s = this.state, tab = s.tab, a = APPS[tab], isTT = tab === 'tt';
      const name = s.names[tab], text = s.msgs[tab];
      const ringCss = isTT ? '0 0 0 2.5px #25F4EE, 0 0 0 5px #FE2C55' : '0 0 0 2.5px ' + a.color;
      const startRec = () => { this.touched(); this.setState({ recording: true, recSecs: 0 }); };
      const cancelRec = () => this.setState({ recording: false, recSecs: 0 });
      const sendAudio = () => { const msgs = Object.assign({}, s.msgs); msgs[tab] = 'Você · áudio ' + fmt(Math.max(1, s.recSecs)); this.setState({ recording: false, recSecs: 0, msgs }); };
      return html`<div style=${T.left}>
          ${this.avatar(s.avatars[tab], initials(name), a.avBg, ringCss)}
          <div style=${'position:absolute;right:-2px;bottom:-1px;width:26px;height:26px;border-radius:50%;display:flex;align-items:center;justify-content:center;box-shadow:0 0 0 3px #000;background:' + (isTT ? '#fff' : a.color) + ';color:' + (isTT ? '#111' : '#fff')}>${isTT ? I.tiktok(13) : I.chat(14)}</div>
        </div>
        <div style=${T.col}>
          <div style=${T.head}><span style="flex:1">${a.app}</span><span>agora</span><span style=${'width:7px;height:7px;border-radius:50%;background:' + a.color}></span></div>
          <div class="ell" style=${T.title}>${name}</div>
          ${!s.recording
            ? html`<div style=${T.sub + ';display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;word-break:break-word'}>${text}</div>`
            : html`<div class="fx" style="display:flex;align-items:center;gap:7px;height:20px">
                <span style="width:7px;height:7px;border-radius:50%;background:#FF3B30;flex:none"></span>
                <span style="flex:1;min-width:0;overflow:hidden;display:flex">${I.wave(14, a.color)}</span>
                <span class="mono" style="font-size:11px;color:#C7CACE;flex:none">${fmt(s.recSecs)}</span>
              </div>`}
        </div>
        ${!s.recording
          ? html`<button class="ib" onClick=${startRec} aria-label="Gravar resposta em áudio" style=${T.btn(42, a.color)}>${I.mic(18)}</button>`
          : html`<div style="display:flex;gap:6px;flex:none;align-items:center">
              <button class="ib" onClick=${cancelRec} aria-label="Cancelar áudio" style=${T.btn(36, '#2A2C30')}>${I.x(15)}</button>
              <button class="ib fx" onClick=${sendAudio} aria-label="Enviar áudio" style=${T.btn(40, a.color)}>${I.send(17)}</button>
            </div>`}`;
    }

    renderMap() {
      const s = this.state, isFood = s.tab === 'ifood';
      const o = s.geo[s.tab] || {};
      const kmTxt = (s.gmKm / 10).toFixed(1).replace('.', ',');
      const g = isFood
        ? { app: 'iFood', title: o.title || 'Motoboy a caminho', sub: o.sub || ('Chega em ' + s.ifMin + ' min · Pedido #1842'), addr: o.addr || 'Saiu para entrega', route: '#EA1D2C', pin: '#EA1D2C', badge: '#EA1D2C' }
        : { app: 'Google Maps', title: o.title || 'Rota para o destino', sub: o.sub || (s.gmMin + ' min · ' + kmTxt + ' km'), addr: o.addr || 'Av. Central, 245', route: '#4285F4', pin: '#EA4335', badge: '#1A73E8' };
      const go = () => { host.openApp(s.tab); if (isFood) this.setState({ ifMin: 8 }); else this.setState({ gmMin: 18, gmKm: 74 }); };
      return html`<div style=${T.left + ';border-radius:50%;overflow:hidden;box-shadow:0 0 0 2.5px ' + g.route}>
          <svg viewBox="0 0 100 100" width=${LEFT} height=${LEFT} style="position:absolute;inset:0;display:block">
            <rect width="100" height="100" fill="#E8EAE5"/>
            <path d="M-10 60 h60 a6 6 0 0 1 6 6 v44 h-66 z" fill="#CFE0BB"/>
            <rect x="62" y="-10" width="60" height="42" fill="#BBD6EC"/>
            <g stroke="#F6F7F4" stroke-width="7" stroke-linecap="round"><path d="M-6 38 L106 28"/><path d="M24 -6 L42 106"/><path d="M-6 74 L82 98"/></g>
            <path d="M22 80 C 38 70, 34 50, 52 46 S 72 30, 80 22" stroke=${g.route} stroke-width="5" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
            <circle cx="22" cy="80" r="5.5" fill="#1FAF5A" stroke="#fff" stroke-width="2.5"/>
            <path transform="translate(66 6) scale(.8)" d="M12 2a7 7 0 0 0-7 7c0 5 7 13 7 13s7-8 7-13a7 7 0 0 0-7-7z" fill=${g.pin}/>
          </svg>
          ${isFood ? html`<span class="bob" style="position:absolute;left:25px;top:36px;width:16px;height:16px;border-radius:6px;background:#E5262B;box-shadow:0 2px 5px rgba(0,0,0,.4);display:flex;align-items:center;justify-content:center"><span style="width:6px;height:6px;border-radius:2px;background:#fff"></span></span>` : null}
        </div>
        <div style=${T.col}>
          <div style=${T.head}>
            <span style=${'width:16px;height:16px;border-radius:5px;display:flex;align-items:center;justify-content:center;color:#fff;flex:none;background:' + g.badge}>${isFood ? I.fork(10) : I.pin(10)}</span>
            <span style="flex:1;color:#C7CACE;font-weight:600">${g.app}</span><span>agora</span>
          </div>
          <div class="ell" style=${T.title}>${g.title}</div>
          <div class="ell" style=${T.sub}>${g.sub}</div>
          <div class="ell" style="display:flex;align-items:center;gap:4px;font-size:11px;color:#8D9298;line-height:14px">${I.pin(11)}<span class="ell">${g.addr}</span></div>
        </div>
        <button class="ib" onClick=${go} aria-label="Abrir navegação" style=${T.btn(42, '#0B0C0E', 'box-shadow:inset 0 0 0 1px #24272B')}>${I.nav(18)}</button>`;
    }

    renderCall() {
      const s = this.state;
      const hang = () => { host.media('hangup'); this.collapse(); };
      return html`<div style=${T.left}>
          <div style="position:absolute;inset:4px;border-radius:50%;overflow:hidden;box-shadow:0 0 0 2.5px #25D366">
            ${s.callAvatar
              ? html`<img class="cover" src=${s.callAvatar}/>`
              : s.callVideo
                ? html`<div style="position:absolute;inset:0;background:radial-gradient(120% 95% at 50% 118%, #7A5341 0%, #3E2C27 55%, #1A1413 100%)"></div>
                  <div style="position:absolute;left:50%;bottom:-8px;transform:translateX(-50%);width:34px;height:40px;border-radius:48% 48% 44% 44%;background:linear-gradient(#D7A585, #B9806180)"></div>
                  <div style="position:absolute;left:50%;bottom:-26px;transform:translateX(-50%);width:56px;height:34px;border-radius:40% 40% 0 0;background:#24201E"></div>`
                : html`<div style="position:absolute;inset:0;background:#1C1D20;display:flex;align-items:center;justify-content:center;color:#6E737A">${I.video(20, true)}</div>`}
          </div>
          <div style="position:absolute;right:-2px;bottom:-1px;width:24px;height:24px;border-radius:50%;background:#25D366;box-shadow:0 0 0 3px #000;color:#fff;display:flex;align-items:center;justify-content:center">${I.phoneFill(12)}</div>
        </div>
        <div style=${T.col}>
          <div style=${T.head}><span style="width:6px;height:6px;border-radius:50%;background:#2FB566;flex:none"></span><span class="ell" style="flex:1">${s.callApp}</span></div>
          <div class="ell" style=${T.title}>${s.callName}</div>
          <div style="display:flex;align-items:center;gap:5px;font-size:11.5px;color:#9A9FA6">${I.video(12, false)}<span class="mono">${fmt(s.callDur)}</span></div>
        </div>
        <div style="display:flex;gap:6px;flex:none;align-items:center">
          <button class="ib" onClick=${() => this.setState({ callMuted: !s.callMuted })} aria-label="Silenciar microfone" style=${T.btn(34, s.callMuted ? '#E5484D' : '#2A2C30', 'transition:background .2s ease')}>${s.callMuted ? I.micOff(16) : I.mic(16)}</button>
          <button class="ib" onClick=${() => this.setState({ callVideo: !s.callVideo })} aria-label="Ligar ou desligar vídeo" style=${T.btn(34, s.callVideo ? '#25D366' : '#2A2C30', 'transition:background .2s ease')}>${I.video(16, !s.callVideo)}</button>
          <button class="ib" onClick=${hang} aria-label="Encerrar chamada" style=${T.btn(34, '#E5484D')}>${I.phone(16, 2.4, 'transform:rotate(135deg)')}</button>
        </div>`;
    }

    renderYt() {
      const s = this.state;
      const pct = clamp(Math.round((s.ytPos / s.ytDur) * 1000) / 10, 0, 100);
      const ytPlay = () => { if (s.ytReal) host.media(s.ytPlaying ? 'pause' : 'play'); this.setState({ ytPlaying: !s.ytPlaying }); };
      const ytPrev = () => { if (s.ytReal) host.media('prev'); this.setState({ ytPos: 0 }); };
      const ytNext = () => { if (s.ytReal) host.media('next'); this.setState({ ytPos: 0, ytPlaying: true }); };
      const ytSeek = (e) => { const p = Math.round((+e.target.value / 100) * s.ytDur); if (s.ytReal) host.media('seek:' + p * 1000); this.setState({ ytPos: p }); };
      return html`<div style=${T.left}>
          <div style="position:absolute;inset:4px;border-radius:50%;overflow:hidden;box-shadow:0 0 0 2.5px #FF0000">
            ${s.ytArt
              ? html`<img class="cover" src=${s.ytArt}/>`
              : html`<div style="position:absolute;inset:0;background:radial-gradient(120% 95% at 50% 118%, #4A3A6E 0%, #2A2440 55%, #141221 100%)"></div>
                <div style="position:absolute;left:50%;bottom:-8px;transform:translateX(-50%);width:36px;height:42px;border-radius:48% 48% 44% 44%;background:linear-gradient(#D7A585, #B9806180)"></div>
                <div style="position:absolute;left:50%;bottom:6px;transform:translateX(-50%);width:44px;height:18px;border-radius:22px 22px 0 0;background:#14121F"></div>
                <div style="position:absolute;left:50%;bottom:-26px;transform:translateX(-50%);width:56px;height:34px;border-radius:40% 40% 0 0;background:#1A1830"></div>`}
          </div>
          <div style="position:absolute;right:-2px;bottom:-1px;width:24px;height:24px;border-radius:50%;background:#FF0000;box-shadow:0 0 0 3px #000;color:#fff;display:flex;align-items:center;justify-content:center"><svg width="11" height="11" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg></div>
        </div>
        <div style=${T.col}>
          <div style=${T.head}><span style="width:6px;height:6px;border-radius:50%;background:#FF0000;flex:none"></span><span class="ell" style="flex:1">${s.ytChannel || 'YouTube'}</span></div>
          <div class="ell" style="font-size:14.5px;font-weight:700;line-height:18px">${s.ytTitle}</div>
          <div class="mono" style="font-size:10.5px;color:#9A9FA6;line-height:13px">${fmt(s.ytPos)} / ${fmt(s.ytDur)}</div>
          <div style="position:relative;height:12px;display:flex;align-items:center">
            <div style="position:absolute;left:0;right:0;height:3px;border-radius:2px;background:#3A2024"></div>
            <div style=${'position:absolute;left:0;height:3px;border-radius:2px;background:#FF0000;width:' + pct + '%'}></div>
            <span style=${'position:absolute;width:9px;height:9px;margin-left:-4.5px;border-radius:50%;background:#FF0000;left:' + pct + '%'}></span>
            <input type="range" min="0" max="100" value=${Math.round(pct)} onChange=${ytSeek} aria-label="Avançar vídeo" style="position:absolute;inset:0;width:100%;height:100%;margin:0;opacity:0"/>
          </div>
        </div>
        <div style="display:flex;gap:4px;flex:none;align-items:center">
          <button class="ib" onClick=${ytPrev} aria-label="Anterior" style=${T.btn(30, '#1C1D20')}>${I.prev(14)}</button>
          <button class="ib" onClick=${ytPlay} aria-label="Tocar ou pausar" style=${T.btn(38, '#FF0000')}>${s.ytPlaying ? I.pause(16) : I.play(16)}</button>
          <button class="ib" onClick=${ytNext} aria-label="Próximo" style=${T.btn(30, '#1C1D20')}>${I.next(14)}</button>
        </div>`;
    }

    renderAssist() {
      const s = this.state, st = s.asState;
      const rem = REMS[s.asIdx % REMS.length], delay = DELAYS[s.asDelayIdx % DELAYS.length];
      const toCompose = () => { host.buzz('stop'); this.setState({ asState: 'compose', asLeft: 0, asAnsLeft: 0 }); };
      const orb = html`<div style=${T.left + ';display:flex;align-items:center;justify-content:center'}>
          <span class="orbspin" style="position:absolute;width:68px;height:68px;border-radius:50%;background:conic-gradient(from 0deg, #8A7CFF, #5BC0FF, #C06CFF, #8A7CFF)"></span>
          <span style="position:absolute;width:58px;height:58px;border-radius:50%;background:radial-gradient(circle at 35% 30%, #2A2740, #15131F);display:flex;align-items:center;justify-content:center;color:#C9C4FF">
            ${st === 'compose' || st === 'created' ? I.spark(22) : null}
            ${st === 'ringing' ? I.bell(22) : null}
            ${st === 'answered' ? I.wave(5, '#C9C4FF') : null}
          </span>
        </div>`;
      let body;
      if (st === 'compose') {
        body = html`<div style=${T.col + ';gap:3px'}>
            <div style=${T.head + ';color:#9A8FFF'}>${I.spark(11)}<span style="color:#8D9298">Assistente</span></div>
            <div style=${T.title}>Novo lembrete</div>
            <div style="display:flex;align-items:center;gap:6px;min-width:0">
              <button class="ib" onClick=${() => this.setState({ asIdx: s.asIdx + 1 })} aria-label="Trocar lembrete" style="flex:1;min-width:0;height:26px;border-radius:13px;background:#1B1C22;box-shadow:inset 0 0 0 1px #34334A;color:#fff;justify-content:flex-start;gap:5px;padding:0 9px;font-size:11.5px;font-weight:600">${I.clock(12)}<span class="ell">${rem}</span></button>
              <button class="ib" onClick=${() => this.setState({ asDelayIdx: s.asDelayIdx + 1 })} aria-label="Trocar quando ligar" style="flex:none;height:26px;border-radius:13px;background:transparent;box-shadow:inset 0 0 0 1px #4A3FA0;color:#C9C4FF;padding:0 9px;font-size:11px;font-weight:700;white-space:nowrap">em ${delay}s</button>
            </div>
          </div>
          <button class="ib fx" onClick=${() => this.setState({ asState: 'created', asLeft: delay })} aria-label="Criar lembrete" style=${T.btn(42, '#8A7CFF')}>${I.plus(19)}</button>`;
      } else if (st === 'created') {
        body = html`<div style=${T.col}>
            <div style=${T.head}>Lembrete programado</div>
            <div class="ell" style=${T.title}>${rem}</div>
            <div style="display:flex;align-items:center;gap:5px;font-size:11.5px;color:#9A8FFF">${I.bell(12)}Vou te ligar em ${s.asLeft}s</div>
          </div>
          <button class="ib" onClick=${toCompose} aria-label="Cancelar lembrete" style=${T.btn(40, '#2A2C30')}>${I.x(16)}</button>`;
      } else if (st === 'ringing') {
        body = html`<div style=${T.col}>
            <div style=${T.head + ';color:#9A8FFF;font-weight:600'}>Assistente chamando…</div>
            <div class="ell" style=${T.title}>${rem}</div>
            <div style=${T.sub}>Lembrete</div>
          </div>
          <div style="display:flex;gap:6px;flex:none;align-items:center">
            <button class="ib" onClick=${toCompose} aria-label="Dispensar" style=${T.btn(38, '#E5484D')}>${I.x(16)}</button>
            <button class="ib fx" onClick=${() => { host.buzz('stop'); this.setState({ asState: 'answered', asAnsLeft: 4 }); }} aria-label="Atender" style=${T.btn(40, '#2FB566')}>${I.phone(17, 2.4)}</button>
          </div>`;
      } else {
        body = html`<div style=${T.col}>
            <div style=${T.head + ';color:#9A8FFF;font-weight:600'}>Lembrete</div>
            <div class="ell" style=${T.title}>${rem}</div>
            <div style="display:flex;align-items:center;gap:5px;font-size:11.5px;color:#8D9298">${I.speaker(12)}Reproduzindo o lembrete…</div>
          </div>
          <button class="ib" onClick=${toCompose} aria-label="Pronto" style=${T.btn(40, '#8A7CFF')}>${I.check(17, 2.8)}</button>`;
      }
      return html`${orb}${body}`;
    }

    week() {
      const s = this.state;
      const now = new Date();
      const todayIdx = (now.getDay() + 6) % 7;
      const evs = (s.cal && s.cal.days) || [];
      return DOWS.map((dow, i) => {
        const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - todayIdx + i);
        const on = i === s.day, today = i === todayIdx;
        return {
          dow, num: d.getDate(), dots: (evs[i] || []).slice(0, 3),
          bg: on ? '#3A1712' : 'transparent',
          ring: on ? 'inset 0 0 0 1px #6A2A1E' : (today ? 'inset 0 0 0 1px #2A2D31' : 'none'),
          pick: () => this.setState({ day: i })
        };
      });
    }

    renderCal() {
      const s = this.state, now = new Date();
      const todayIdx = (now.getDay() + 6) % 7;
      const days = (s.cal && s.cal.days) || [];
      const total = days.reduce((a, d) => a + (d ? d.length : 0), 0);
      const nEv = (days[s.day] || []).length;
      let info;
      if (!s.cal) info = 'Permita o calendário no app Ilha Dinâmica';
      else {
        info = nEv === 0 ? 'Nenhum evento' : nEv + (nEv === 1 ? ' evento' : ' eventos');
        info += s.day === todayIdx ? ' hoje' + (s.cal.next ? ' · Próximo às ' + s.cal.next : '') : ' neste dia';
      }
      return html`<button class="ib" onClick=${() => host.openApp('cal')} aria-label="Abrir calendário" style=${T.left + ';border-radius:50%;background:#140806;box-shadow:inset 0 0 0 1px #2A1410, inset 0 0 24px rgba(234,67,53,.4)'}>
          <div style="width:40px;height:44px;border-radius:10px;background:#F4F4F2;overflow:hidden;display:flex;flex-direction:column;align-items:center;box-shadow:0 5px 12px rgba(0,0,0,.4)">
            <div style="width:100%;height:15px;background:#EA4335;color:#fff;font-size:8.5px;font-weight:800;display:flex;align-items:center;justify-content:center;letter-spacing:.5px">${DOWS[todayIdx]}</div>
            <div style="font-size:19px;font-weight:700;color:#111;line-height:1;margin-top:3px">${now.getDate()}</div>
            <div style="width:4px;height:4px;border-radius:50%;background:#EA4335;margin-top:2px"></div>
          </div>
        </button>
        <div style=${T.col}>
          <div style="display:flex;align-items:baseline;gap:6px"><span style="font-size:14px;font-weight:700;line-height:18px">Sua semana</span><span style="font-size:10.5px;color:#8D9298">${total} ${total === 1 ? 'evento' : 'eventos'}</span></div>
          <div style="display:grid;grid-template-columns:repeat(7, minmax(0, 1fr));gap:2px">
            ${this.week().map((d) => html`<button class="ib" onClick=${d.pick} aria-label=${d.dow + ' ' + d.num} style=${'height:34px;border-radius:8px;color:#fff;flex-direction:column;gap:1px;background:' + d.bg + ';box-shadow:' + d.ring}>
                <span style="font-size:7.5px;color:#8D9298;font-weight:600;line-height:9px">${d.dow}</span>
                <span style="font-size:13px;font-weight:600;line-height:14px">${d.num}</span>
                <span style="display:flex;gap:2px;height:4px">${d.dots.map((c) => html`<span style=${'width:4px;height:4px;border-radius:50%;background:' + c}></span>`)}</span>
              </button>`)}
          </div>
          <div class="ell" style="font-size:10px;color:#8D9298;line-height:13px">${info}</div>
        </div>`;
    }

    renderFile() {
      const s = this.state, fs = s.file;
      const prog = fs === 'sending' ? s.prog : (fs === 'sent' ? 100 : 0);
      const title = fs === 'idle' ? 'Solte para enviar' : fs === 'ready' ? 'Pronto para enviar' : fs === 'sending' ? 'Enviando… ' + s.prog + '%' : 'Enviado';
      const sub = fs === 'idle' ? 'Arraste o arquivo até a cápsula' : fs === 'sent' ? 'Arquivo entregue com sucesso' : 'Toque no botão azul para enviar';
      const btnBg = fs === 'idle' ? '#1B1D21' : (fs === 'sent' ? '#1FAF5A' : '#2F8CFF');
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
      return html`<div style=${T.left + ';border-radius:50%;background:#04101C;box-shadow:inset 0 0 0 1px #12314F, inset 0 0 26px rgba(47,140,255,.32);display:flex;align-items:center;justify-content:center;color:#5BC0FF'}>
          <svg width=${LEFT} height=${LEFT} viewBox="0 0 104 104" fill="none" style="position:absolute;inset:0" class=${fs === 'sending' ? 'spinslow' : ''}><circle cx="52" cy="52" r="44" stroke="#2F8CFF" stroke-width="1.5" stroke-dasharray="4 7" opacity=".7"/></svg>
          ${fs === 'idle' ? I.download(32) : html`<div class="fx" style="width:36px;height:44px;border-radius:7px;background:#E5484D;color:#fff;display:flex;align-items:flex-end;justify-content:center;padding-bottom:6px;font-size:9.5px;font-weight:800">PDF</div>`}
        </div>
        <div style=${T.col + ';gap:3px'}>
          <div class="ell" style="font-size:15px;font-weight:700;line-height:19px">${title}</div>
          <div class="ell" style="font-size:11px;color:#8D9298;line-height:14px">${sub}</div>
          ${fs !== 'idle' ? html`<div class="fx" style="height:26px;border-radius:13px;background:#15171A;box-shadow:inset 0 0 0 1px #2A2D31;display:flex;align-items:center;gap:6px;padding:0 3px 0 8px;position:relative;overflow:hidden">
              <div style=${'position:absolute;left:0;top:0;bottom:0;background:rgba(47,140,255,.22);transition:width .12s linear;width:' + prog + '%'}></div>
              <span style="position:relative;width:11px;height:14px;border-radius:2px;background:#E5484D;flex:none"></span>
              <span class="ell" style="position:relative;flex:1;min-width:0;font-size:11px">Relatorio.pdf <span style="color:#8D9298">· 2,4 MB</span></span>
              <button class="ib" onClick=${removeFile} aria-label="Remover arquivo" style=${'position:relative;' + T.btn(20, '#2A2D31')}>${I.x(10, 3)}</button>
            </div>` : null}
        </div>
        <button class="ib" onClick=${sendFile} aria-label="Enviar arquivo" style=${T.btn(44, btnBg, 'transition:background .3s ease')}>
          ${fs !== 'sent' ? html`<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M3 11l18-8-8 18-2-8z"/></svg>` : I.check(19, 3)}
        </button>`;
    }

    renderBody() {
      switch (this.state.tab) {
        case 'music': case 'timer': case 'bright': return this.renderWheel();
        case 'ig': case 'wa': case 'tg': case 'tt': return this.renderMsg();
        case 'ifood': case 'maps': return this.renderMap();
        case 'call': return this.renderCall();
        case 'yt': return this.renderYt();
        case 'assist': return this.renderAssist();
        case 'cal': return this.renderCal();
        default: return this.renderFile();
      }
    }

    render() {
      const s = this.state;
      const z = this.zoom();
      const color = COLOR[s.tab];
      const capTop = this.capTop();
      const lift = (s.cy - capTop) / z;            // a cápsula nasce exatamente da bolha
      const d = s.drag;
      const showCard = s.open && !s.closing && s.tab === 'file' && s.file === 'idle';
      const r = 15, C = 2 * Math.PI * r;

      return html`<div style="position:fixed;inset:0">
        ${s.open ? html`<div onPointerDown=${() => this.collapse()} style="position:absolute;inset:0;z-index:1"></div>` : null}

        <div style=${'position:absolute;z-index:5;left:50%;width:40px;height:40px;margin-left:-20px;top:' + (s.cy - 20) + 'px;transform:scale(' + s.bscale + ')'}>
          <button class="ib" onClick=${() => this.toggle()} aria-label="Abrir ilha" style="position:absolute;left:3px;top:3px;width:34px;height:34px;border-radius:50%;background:radial-gradient(circle at 50% 38%, #141418, #050506);box-shadow:inset 0 0 0 1px rgba(255,255,255,.06)">
            <svg width="34" height="34" viewBox="0 0 34 34" style="position:absolute;inset:0">
              <circle cx="17" cy="17" r=${r} fill="none" stroke="#1E1F24" stroke-width="2"/>
              <circle cx="17" cy="17" r=${r} fill="none" stroke=${color} stroke-width="2.2" stroke-linecap="round"
                stroke-dasharray=${C.toFixed(2)} stroke-dashoffset=${(C * (1 - clamp(this.progress(), 0, 1))).toFixed(2)}
                transform="rotate(-90 17 17)" style="transition:stroke-dashoffset 1s linear, stroke .4s ease"/>
            </svg>
          </button>
        </div>

        ${s.open ? html`<div style=${'position:absolute;z-index:5;left:50%;transform:translateX(-50%);top:' + capTop + 'px'}
              onPointerDown=${(e) => this.capDown(e)} onPointerUp=${(e) => this.capUp(e)}>
            <div class=${'cap ' + (s.closing ? 'out' : 'in')} style=${'--lift:' + lift + 'px;zoom:' + z + ';width:' + CW + 'px;height:' + CH + 'px;border-radius:' + CH / 2 + 'px;background:#000;color:#fff;overflow:hidden;position:relative;transition:box-shadow .4s ease;box-shadow:' + capShadow(s.tab)}>
              <div class="fx" key=${s.tab} style="height:100%;padding:8px 16px 8px 8px;display:flex;gap:10px;align-items:center">
                ${this.renderBody()}
              </div>
              <button class="ib" onClick=${() => this.stepTab(1)} aria-label="Trocar de aba" style="position:absolute;left:50%;bottom:0;transform:translateX(-50%);width:96px;height:10px;background:transparent">
                <span style="width:48px;height:3px;border-radius:2px;background:rgba(255,255,255,.26);display:block"></span>
              </button>
            </div>
          </div>` : null}

        ${showCard ? html`
          <div style=${'position:absolute;z-index:5;left:50%;margin-left:-10px;pointer-events:none;top:' + (capTop + CH * z + 6) + 'px'}>
            <svg width="20" height="34" viewBox="0 0 20 34" fill="none" stroke="#5BC0FF" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" class="bob"><path d="M10 32V6" stroke-dasharray="4 5"/><path d="M3 12l7-8 7 8"/></svg>
          </div>
          <button class=${'ib card' + (d ? ' dragging' : '')}
            onPointerDown=${(e) => this.cardDown(e)} onPointerMove=${(e) => this.cardMove(e)} onPointerUp=${(e) => this.cardUp(e)} onPointerCancel=${() => this.setState({ drag: null })}
            aria-label="Arraste o Relatorio.pdf até a cápsula"
            style=${'position:absolute;z-index:6;left:50%;margin-left:-48px;width:96px;height:100px;border-radius:18px;background:#F7F8FA;color:#17181A;flex-direction:column;gap:4px;touch-action:none;box-shadow:0 12px 26px rgba(0,0,0,.35), 0 0 0 1px rgba(255,255,255,.6);top:' + this.cardTop() + 'px' + (d ? ';transform:translate(' + d.dx + 'px,' + d.dy + 'px) scale(.92)' : '')}>
            <span style="width:34px;height:40px;border-radius:7px;background:#E5484D;color:#fff;display:flex;align-items:flex-end;justify-content:center;padding-bottom:5px;font-size:9px;font-weight:800">PDF</span>
            <span style="font-size:11.5px;font-weight:700">Relatorio.pdf</span>
            <span style="font-size:10px;color:#5C6066;margin-top:-3px">2,4 MB</span>
          </button>` : null}
      </div>`;
    }
  }

  render(html`<${Island} />`, document.getElementById('root'));
})();
