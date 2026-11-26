<script lang="ts">
  import { onMount } from 'svelte'
  import {
    MIN_BATTERY,
    createSimNode,
    crashSimNode,
    reconnectSimNode,
    refreshSimNodes,
    removeAllSimNodes,
    removeSimNode,
    simAvailable,
    simNodes,
    simRoleLabel,
    simScore,
    updateSimNode,
    type SimNode,
  } from './simulator'

  type Draft = { level: number; battery: number }

  const presets = [
    { label: 'Fraco', level: 30, battery: 60 },
    { label: 'Médio', level: 60, battery: 80 },
    { label: 'Forte', level: 95, battery: 100 },
  ]

  let level = 60
  let battery = 80
  let busy = false
  let actionError = ''

  // Valores editáveis por nó; não são sobrescritos pela atualização periódica.
  let drafts: Record<string, Draft> = {}

  $: validInput = isValid(level) && isValid(battery)
  $: previewScore = validInput ? simScore(level, battery) : null
  $: syncDrafts($simNodes)

  function isValid(value: number | null | undefined): value is number {
    return typeof value === 'number' && value >= 0 && value <= 100
  }

  function syncDrafts(nodes: SimNode[]) {
    let changed = false
    for (const node of nodes) {
      if (!(node.node_id in drafts)) {
        drafts[node.node_id] = { level: node.level, battery: node.battery }
        changed = true
      }
    }
    if (changed) drafts = drafts
  }

  function draftChanged(node: SimNode) {
    const draft = drafts[node.node_id]
    return (
      draft !== undefined &&
      isValid(draft.level) &&
      isValid(draft.battery) &&
      (draft.level !== node.level || draft.battery !== node.battery)
    )
  }

  function roleClass(node: SimNode) {
    if (node.status !== 'online') return 'down'
    return (node.role ?? 'IDLE').toLowerCase()
  }

  async function run(action: () => Promise<void>) {
    busy = true
    try {
      await action()
      actionError = ''
    } catch (error) {
      actionError =
        error instanceof Error ? error.message : 'Falha ao falar com o simulador.'
    } finally {
      busy = false
    }
  }

  function applyDraft(node: SimNode) {
    const draft = drafts[node.node_id]
    void run(() => updateSimNode(node.node_id, draft.level, draft.battery))
  }

  function forget(nodeId: string) {
    delete drafts[nodeId]
    drafts = drafts
  }

  onMount(() => {
    void refreshSimNodes()
    // Mais frequente que o resto do painel, para acompanhar as transições
    // de papel (descoberta, eleição, troca de líder).
    const interval = window.setInterval(() => void refreshSimNodes(), 2000)
    return () => window.clearInterval(interval)
  })
</script>

<div class="sim-panel">
  <div class="sim-heading">
    <div>
      <h3>Nós simulados</h3>
      <p>
        Executam o mesmo algoritmo de eleição do aplicativo, cada um com a
        própria conexão MQTT, LWT e presença.
      </p>
    </div>
    {#if $simAvailable && $simNodes.length > 0}
      <button
        class="sim-button ghost"
        disabled={busy}
        on:click={() =>
          run(async () => {
            await removeAllSimNodes()
            drafts = {}
          })}
      >
        Remover todos
      </button>
    {/if}
  </div>

  {#if $simAvailable === false}
    <div class="empty-state">
      Simulador desligado. Na raiz do repositório, inicie com
      <code>.\gradlew.bat -p simulator run</code>.
    </div>
  {:else if $simAvailable}
    <div class="sim-form">
      <div class="sim-presets">
        {#each presets as preset}
          <button
            class="sim-chip"
            class:selected={preset.level === level && preset.battery === battery}
            on:click={() => {
              level = preset.level
              battery = preset.battery
            }}
          >
            {preset.label}
            <span>{simScore(preset.level, preset.battery).toFixed(1)}</span>
          </button>
        {/each}
      </div>

      <label>
        Nível geral
        <input type="number" min="0" max="100" bind:value={level} />
      </label>

      <label>
        Bateria (%)
        <input type="number" min="0" max="100" bind:value={battery} />
      </label>

      <div class="sim-preview">
        Pontuação
        <strong>{previewScore === null ? '—' : previewScore.toFixed(2)}</strong>
        {#if validInput && battery < MIN_BATTERY}
          <span class="sim-warning">
            bateria abaixo de {MIN_BATTERY}%: só lidera se nenhum nó tiver bateria suficiente
          </span>
        {/if}
      </div>

      <button
        class="sim-button primary"
        disabled={busy || !validInput}
        on:click={() => run(() => createSimNode(level, battery))}
      >
        Adicionar nó
      </button>
    </div>

    {#if actionError}
      <div class="error-banner">{actionError}</div>
    {/if}

    {#if $simNodes.length > 0}
      <div class="table-wrapper">
        <table>
          <thead>
            <tr>
              <th>NÓ</th>
              <th>PAPEL</th>
              <th>PONTUAÇÃO</th>
              <th>NÍVEL</th>
              <th>BATERIA</th>
              <th>ÉPOCA</th>
              <th>AÇÕES</th>
            </tr>
          </thead>
          <tbody>
            {#each $simNodes as node (node.node_id)}
              <tr class:down={node.status !== 'online'}>
                <td>
                  <strong>Sim {node.node_id}</strong>
                  <small>Equipe {String(node.team_id).padStart(3, '0')} · conhece {node.known_candidates} nó(s)</small>
                  {#if node.error}
                    <small class="sim-warning">{node.error}</small>
                  {/if}
                </td>
                <td>
                  <span class="role-badge role-{roleClass(node)}">{simRoleLabel(node)}</span>
                </td>
                <td>
                  {node.score.toFixed(2)}
                  {#if !node.eligible}
                    <small class="sim-warning" title="Só lidera se nenhum nó tiver bateria suficiente">
                      bateria baixa
                    </small>
                  {/if}
                </td>
                <td>
                  {#if drafts[node.node_id]}
                    <input
                      class="sim-inline"
                      type="number"
                      min="0"
                      max="100"
                      bind:value={drafts[node.node_id].level}
                    />
                  {/if}
                </td>
                <td>
                  {#if drafts[node.node_id]}
                    <input
                      class="sim-inline"
                      type="number"
                      min="0"
                      max="100"
                      bind:value={drafts[node.node_id].battery}
                    />
                  {/if}
                </td>
                <td>{node.epoch_counter ?? '—'}</td>
                <td class="sim-actions">
                  <button
                    class="sim-button"
                    disabled={busy || !draftChanged(node)}
                    on:click={() => applyDraft(node)}
                  >
                    Aplicar
                  </button>
                  {#if node.status === 'online' || node.status === 'conectando'}
                    <button
                      class="sim-button warn"
                      disabled={busy}
                      title="Queda abrupta: o broker publica o LWT e os outros nós deixam de receber heartbeat"
                      on:click={() => run(() => crashSimNode(node.node_id))}
                    >
                      Derrubar
                    </button>
                  {:else}
                    <button
                      class="sim-button"
                      disabled={busy}
                      on:click={() => run(() => reconnectSimNode(node.node_id))}
                    >
                      Reconectar
                    </button>
                  {/if}
                  <button
                    class="sim-button ghost"
                    disabled={busy}
                    on:click={() =>
                      run(async () => {
                        await removeSimNode(node.node_id)
                        forget(node.node_id)
                      })}
                  >
                    Remover
                  </button>
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    {:else}
      <div class="empty-state">
        Nenhum nó simulado. Escolha as características e clique em "Adicionar nó".
      </div>
    {/if}
  {/if}
</div>

<style>
  .sim-panel {
    border: 1px solid #23334a;
    border-radius: 10px;
    padding: 16px;
    margin-bottom: 22px;
    background: #0f1a2a;
  }

  .sim-heading {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 12px;
    margin-bottom: 14px;
  }

  .sim-heading p {
    color: #8192a9;
    font-size: 11px;
    margin: 0;
  }

  .sim-form {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-end;
    gap: 12px;
    margin-bottom: 14px;
  }

  .sim-presets {
    display: flex;
    gap: 6px;
  }

  .sim-form label {
    display: flex;
    flex-direction: column;
    gap: 4px;
    color: #8da0b8;
    font-size: 10px;
    font-weight: 700;
    letter-spacing: 0.4px;
  }

  input {
    background: #121e30;
    border: 1px solid #344963;
    border-radius: 6px;
    color: #f0f4fa;
    font: inherit;
    font-size: 12px;
    padding: 7px 9px;
    width: 96px;
  }

  input.sim-inline {
    width: 64px;
    padding: 4px 6px;
  }

  .sim-preview {
    color: #8da0b8;
    font-size: 11px;
    display: flex;
    align-items: baseline;
    gap: 6px;
    padding-bottom: 8px;
  }

  .sim-preview strong {
    color: #f0f4fa;
    font-size: 16px;
  }

  .sim-warning {
    display: block;
    color: #ffb36b;
    font-size: 10px;
  }

  .sim-preview .sim-warning {
    display: inline;
  }

  .sim-chip,
  .sim-button {
    font: inherit;
    font-size: 11px;
    font-weight: 700;
    border-radius: 6px;
    padding: 7px 11px;
    cursor: pointer;
    border: 1px solid #344963;
    background: #17263a;
    color: #c9d4e3;
  }

  .sim-chip span {
    color: #8192a9;
    margin-left: 4px;
  }

  .sim-chip.selected {
    border-color: #2563eb;
    color: #ffffff;
  }

  .sim-button.primary {
    background: #2563eb;
    border-color: #2563eb;
    color: #ffffff;
  }

  .sim-button.warn {
    border-color: #714040;
    color: #ffaaaa;
  }

  .sim-button.ghost {
    background: transparent;
  }

  .sim-button:disabled,
  .sim-chip:disabled {
    opacity: 0.45;
    cursor: default;
  }

  .sim-actions {
    display: flex;
    gap: 6px;
  }

  td small {
    display: block;
    color: #7f90a7;
    font-size: 10px;
    margin-top: 2px;
  }

  tr.down td {
    opacity: 0.6;
  }

  .role-badge {
    display: inline-block;
    padding: 3px 9px;
    border-radius: 20px;
    font-size: 10px;
    font-weight: 700;
    border: 1px solid #344963;
    color: #c9d4e3;
    background: #17263a;
  }

  .role-coordinator {
    color: #5ee0a7;
    background: #153426;
    border-color: #235440;
  }

  .role-follower {
    color: #9cc0ff;
    background: #16264a;
    border-color: #2a4479;
  }

  .role-electing,
  .role-idle {
    color: #ffd27a;
    background: #3a2f17;
    border-color: #6b5524;
  }

  .role-down {
    color: #ffaaaa;
    background: #402525;
    border-color: #714040;
  }

  code {
    background: #17263a;
    border-radius: 4px;
    padding: 1px 5px;
    font-size: 11px;
  }

  @media (max-width: 700px) {
    .sim-actions {
      flex-wrap: wrap;
    }
  }

  /* Etiqueta usada na tabela de dispositivos do App.svelte. */
  :global(.sim-tag) {
    display: inline-block;
    margin-left: 6px;
    padding: 1px 7px;
    border-radius: 20px;
    font-size: 9px;
    font-weight: 700;
    letter-spacing: 0.4px;
    color: #ffd27a;
    background: #3a2f17;
    border: 1px solid #6b5524;
  }
</style>
