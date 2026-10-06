(function () {
  'use strict';
  const { html, render, Component } = window.htmPreact;
  const A = window.Android || {
    getState: () => JSON.stringify({ overlay: false, notif: false, calendar: false, write: false, post: true, running: false, enabled: false, tab: 'music', open: false, scale: 1, bscale: 1, dx: 0, dy: 0, real: true }),
    requestOverlay() {}, requestNotifAccess() {}, requestCalendar() {}, requestWrite() {},
    setEnabled() {}, setTab() {}, setOpen() {}, setScale() {}, setBubbleScale() {}, setOffset() {}, setReal() {}
  };
  const TABS = [['music', 'Música'], ['timer', 'Timer'], ['bright', 'Brilho'], ['assist', 'Assistente'], ['cal', 'Calendário'], ['ig', 'Instagram'], ['wa', 'WhatsApp'], ['tg', 'Telegram'], ['tt', 'TikTok'], ['call', 'Chamada'], ['yt', 'YouTube'], ['ifood', 'iFood'], ['maps', 'Maps'], ['file', 'Enviar arquivo']];

  class Panel extends Component {
    constructor() {
      super();
      this.state = JSON.parse(A.getState());
    }
    componentDidMount() {
      window.panel = { refresh: () => this.refresh() };
      this.iv = setInterval(() => this.refresh(), 1000);
    }
    refresh() {
      const s = JSON.parse(A.getState());
      // Mantém os controles deslizantes onde o usuário deixou enquanto arrasta.
      if (this.sliding) { delete s.scale; delete s.bscale; delete s.dx; delete s.dy; }
      this.setState(s);
    }
    slide(key, v) {
      this.sliding = true;
      clearTimeout(this.slT);
      this.slT = setTimeout(() => { this.sliding = false; }, 800);
      const n = { [key]: v };
      this.setState(n);
      const s = Object.assign({}, this.state, n);
      if (key === 'scale') A.setScale(v);
      else if (key === 'bscale') A.setBubbleScale(v);
      else A.setOffset(s.dx, s.dy);
    }

    perm(ok, title, sub, action, optional) {
      return html`<div class="card">
        <span class="dot" style=${'background:' + (ok ? '#1FAF5A' : (optional ? '#C9CAC4' : '#E8814B'))}></span>
        <div style="flex:1;min-width:0">
          <div style="font-size:15px;font-weight:700">${title}</div>
          <div style="font-size:12.5px;color:#5C6066;line-height:1.35;margin-top:2px">${sub}</div>
        </div>
        ${ok
          ? html`<span style="font-size:13px;font-weight:700;color:#1FAF5A">Ativo</span>`
          : html`<button class="cb pill" onClick=${action} style="background:#17181A;color:#fff">Permitir</button>`}
      </div>`;
    }

    render() {
      const s = this.state;
      const on = s.enabled && s.running;
      const tabCols = window.innerWidth >= 420 ? 4 : 3;
      return html`<div style="display:flex;flex-direction:column;gap:30px;max-width:560px;margin:0 auto">
        <div>
          <div style="font-size:13px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;color:#5C6066">Ilha dinâmica Android</div>
          <h1 style="margin:8px 0 0;font-size:40px;line-height:1.05;font-weight:800;letter-spacing:-1px">Bolha da câmera</h1>
          <p style="margin:12px 0 0;font-size:16px;line-height:1.5;color:#3F4247">A ilha é uma bolinha fixa em volta da câmera. Toque nela para abrir a cápsula logo abaixo; os tracinhos trocam de aba. Escolha uma aba aqui para simular a chegada de uma mensagem, um evento ou o envio de um arquivo.</p>
        </div>

        <div class="sec">
          <div class="lbl">Ilha</div>
          <button class="cb" onClick=${() => A.setEnabled(!on)} style=${'height:60px;border-radius:16px;font-size:17px;font-weight:800;display:flex;align-items:center;justify-content:center;gap:12px;' + (on ? 'background:#17181A;color:#fff' : 'background:#F7F7F4;color:#17181A;box-shadow:inset 0 0 0 1.5px #D2D3CD')}>
            <span style=${'width:22px;height:22px;border-radius:50%;background:#050506;box-shadow:0 0 0 2.5px ' + (on ? '#E8814B' : '#9A9FA6') + ', 0 0 12px ' + (on ? '#E8814B' : 'transparent')}></span>
            ${on ? 'Ilha ligada — tocar para desligar' : 'Ligar a ilha'}
          </button>
        </div>

        <div class="sec">
          <div class="lbl">Permissões</div>
          ${this.perm(s.overlay, 'Aparecer sobre outros apps', 'Necessária para desenhar a bolha em volta da câmera.', () => A.requestOverlay())}
          ${this.perm(s.notif, 'Acesso às notificações', 'Mostra mensagens reais (WhatsApp, Instagram, Telegram, TikTok, iFood, Maps) e a música que está tocando.', () => A.requestNotifAccess())}
          ${this.perm(s.calendar, 'Calendário', 'Mostra os eventos da sua semana na aba Calendário.', () => A.requestCalendar(), true)}
          ${this.perm(s.write, 'Alterar brilho', 'Deixa a aba Brilho mudar o brilho de verdade.', () => A.requestWrite(), true)}
        </div>

        <div class="sec">
          <div class="lbl">Aba</div>
          <div style=${'display:grid;grid-template-columns:repeat(' + tabCols + ', minmax(0, 1fr));gap:10px'}>
            ${TABS.map(([id, label]) => {
              const sel = id === s.tab;
              return html`<button class="cb" onClick=${() => { A.setTab(id); this.setState({ tab: id, open: true }); }} style=${'height:52px;border-radius:14px;font-size:15px;font-weight:700;padding:0 4px;background:' + (sel ? '#17181A' : '#F7F7F4') + ';color:' + (sel ? '#FFFFFF' : '#17181A') + ';box-shadow:inset 0 0 0 1.5px ' + (sel ? '#17181A' : '#D2D3CD')}>${label}</button>`;
            })}
          </div>
        </div>

        <div class="sec">
          <div class="lbl">Estado</div>
          <div style="display:flex;gap:6px;padding:5px;background:#DCDDD7;border-radius:16px;width:fit-content">
            <button class="cb" onClick=${() => { A.setOpen(false); this.setState({ open: false }); }} style=${'height:44px;padding:0 22px;border-radius:12px;font-size:15px;font-weight:700;background:' + (s.open ? 'transparent' : '#17181A') + ';color:' + (s.open ? '#17181A' : '#FFFFFF')}>Bolha</button>
            <button class="cb" onClick=${() => { A.setOpen(true); this.setState({ open: true }); }} style=${'height:44px;padding:0 22px;border-radius:12px;font-size:15px;font-weight:700;background:' + (s.open ? '#17181A' : 'transparent') + ';color:' + (s.open ? '#FFFFFF' : '#17181A')}>Cápsula aberta</button>
          </div>
          <label class="card" style="cursor:pointer">
            <div style="flex:1">
              <div style="font-size:15px;font-weight:700">Abrir sozinha com eventos reais</div>
              <div style="font-size:12.5px;color:#5C6066;margin-top:2px">Quando chega uma mensagem ou começa uma música, a cápsula abre por 8 segundos.</div>
            </div>
            <input type="checkbox" checked=${s.real} onChange=${(e) => { A.setReal(e.target.checked); this.setState({ real: e.target.checked }); }} style="width:22px;height:22px;accent-color:#17181A"/>
          </label>
        </div>

        <div class="sec">
          <div class="lbl">Ajuste em volta da câmera</div>
          <div class="card" style="flex-direction:column;align-items:stretch;gap:6px">
            ${this.slider('Tamanho da cápsula', 'scale', s.scale, 0.7, 1.1, 0.02, (v) => Math.round(v * 100) + '%')}
            ${this.slider('Tamanho da bolha', 'bscale', s.bscale, 0.6, 1.8, 0.02, (v) => Math.round(v * 100) + '%')}
            ${this.slider('Posição horizontal', 'dx', s.dx, -80, 80, 1, (v) => (v > 0 ? '+' : '') + Math.round(v) + ' dp')}
            ${this.slider('Posição vertical', 'dy', s.dy, -40, 60, 1, (v) => (v > 0 ? '+' : '') + Math.round(v) + ' dp')}
          </div>
        </div>

        <div style="border-top:1.5px solid #D2D3CD;padding-top:20px;display:flex;flex-direction:column;gap:10px">
          <div class="row"><b>Bolha</b>Círculo de 32 px em volta da câmera, com um anel fino de progresso da aba atual.</div>
          <div class="row"><b>Cápsula</b>Fixa logo abaixo da câmera: círculo à esquerda, roda de itens no meio e roda numérica à direita.</div>
          <div class="row"><b>Rodas</b>Toque no item de cima ou de baixo para girar; o ponto confirma ou toca/pausa.</div>
          <div class="row"><b>Gestos</b>Deslize a cápsula para os lados para trocar de aba; para cima ou toque fora para recolher.</div>
        </div>
      </div>`;
    }

    slider(label, key, val, min, max, step, show) {
      return html`<div>
        <div style="display:flex;justify-content:space-between;font-size:14px;font-weight:700"><span>${label}</span><span style="color:#5C6066">${show(val)}</span></div>
        <input type="range" min=${min} max=${max} step=${step} value=${val} onInput=${(e) => this.slide(key, +e.target.value)}/>
      </div>`;
    }
  }

  render(html`<${Panel} />`, document.getElementById('root'));
})();
