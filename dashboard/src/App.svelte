<script lang="ts">
  import { onMount } from 'svelte'
  import SimulatorPanel from './lib/SimulatorPanel.svelte'
  import { simNodes } from './lib/simulator'
  import L from 'leaflet'
  import 'leaflet/dist/leaflet.css'
  import MonitoringPanel from './lib/MonitoringPanel.svelte'

  type Team = {
    id: number
    name: string
    created_at: string
  }

  type Location = {
    id: number
    team_id: number
    latitude: number
    longitude: number
    accuracy: number
    battery: number
    recorded_at: string
  }

  let mapElement: HTMLDivElement
  let teams: Team[] = []
  let latestLocations: Location[] = []
  let connectionStatus = 'Conectando'
  let lastUpdate = 'Aguardando dados'
  let errorMessage = ''
  let map: L.Map | undefined

  // Última posição de cada aparelho (GET /api/nodes/locations/latest).
  type NodeLocation = {
    id: number
    team_id: number
    node_id: string
    latitude: number
    longitude: number
    accuracy: number
    battery: number
    recorded_at: string
  }

  let nodeLocations: NodeLocation[] = []
  let nodeLocationsError = ''

  // Um marcador por aparelho, reaproveitado entre atualizações (chave
  // "equipe-nó"), para não fechar popups nem piscar o mapa.
  const nodeMarkers = new Map<string, L.CircleMarker>()

  // Aba ativa. A "Visão geral" fica sempre montada (apenas oculta), para o
  // mapa manter o zoom; o rastro e o histórico ficam na aba "Monitoramento".
  type View = 'overview' | 'monitoring'
  let activeView: View = 'overview'

  // O enquadramento automático só acontece na primeira carga ou quando
  // surge um aparelho novo, para não brigar com o zoom do usuário.
  let mapFitted = false

  // Identificadores de nó chegam como texto: são inteiros de 64 bits,
  // acima do limite exato de number em JavaScript.
  type Leader = {
    team_id: number
    node_id: string
    epoch_counter: number
    epoch_initiator_node_id: string
    elected_at: string
    last_seen_at: string
    active: boolean
  }

  type NodePresence = {
    team_id: number
    node_id: string
    status: 'online' | 'offline'
    event_timestamp: string
    received_at: string
  }

  type LeaderEvent = {
    id: number
    team_id: number
    node_id: string
    epoch_counter: number
    epoch_initiator_node_id: string
    source_type: string
    received_at: string
  }

  let leaders: Leader[] = []
  let nodes: NodePresence[] = []
  let leaderHistory: LeaderEvent[] = []
  let coordinationError = ''

  // Nós criados pelo simulador, para marcá-los na tabela de dispositivos.
  $: simulatedIds = new Set($simNodes.map((sim) => `${sim.team_id}-${sim.node_id}`))

  $: onlineCount = nodes.filter((node) => node.status === 'online').length

  function formatDate(value: string) {
    if (!value) return 'Não disponível'
    return new Date(value).toLocaleString('pt-BR')
  }

  function formatTime(value: string) {
    if (!value) return '—'
    return new Date(value).toLocaleTimeString('pt-BR')
  }

  function formatTeam(teamId: number) {
    return `Equipe ${String(teamId).padStart(3, '0')}`
  }

  // Mesmo formato curto exibido no aplicativo: os 6 últimos dígitos.
  function shortNodeId(nodeId: string) {
    return nodeId.length <= 6 ? nodeId : `…${nodeId.slice(-6)}`
  }

  function isActiveLeader(node: NodePresence) {
    return isLeaderNode(node.team_id, node.node_id)
  }

  async function fetchJson<T>(url: string): Promise<T> {
    const response = await fetch(url)
    if (!response.ok) {
      throw new Error(`${url} respondeu ${response.status}`)
    }
    return (await response.json()) as T
  }

  async function refreshCoordination() {
    try {
      const [leaderList, presenceList, history] = await Promise.all([
        fetchJson<Leader[]>('/api/leaders'),
        fetchJson<NodePresence[]>('/api/presence'),
        fetchJson<LeaderEvent[]>('/api/leaders/history?limit=10'),
      ])

      leaders = leaderList
      nodes = presenceList
      leaderHistory = history
      coordinationError = ''
      // A cor do coordenador e dos aparelhos offline depende destes dados.
      updateMap()
    } catch (error) {
      coordinationError =
        error instanceof Error
          ? error.message
          : 'Falha ao consultar liderança e presença.'
    }
  }

  async function refreshNodeLocations() {
    try {
      nodeLocations = await fetchJson<NodeLocation[]>(
        '/api/nodes/locations/latest',
      )
      nodeLocationsError = ''
      updateMap()
    } catch (error) {
      nodeLocationsError =
        error instanceof Error
          ? error.message
          : 'Falha ao consultar a posição dos aparelhos.'
    }
  }

  function refreshAll() {
    void refreshData()
    void refreshCoordination()
    void refreshNodeLocations()
  }

  async function refreshData() {
    try {
      const [healthResponse, teamsResponse, locationsResponse] =
        await Promise.all([
          fetch('/health'),
          fetch('/api/teams'),
          fetch('/api/locations/latest'),
        ])

      if (!healthResponse.ok || !teamsResponse.ok || !locationsResponse.ok) {
        throw new Error('A API retornou uma resposta inválida.')
      }

      const health = await healthResponse.json()
      teams = await teamsResponse.json()
      latestLocations = await locationsResponse.json()

      connectionStatus =
        health.status === 'ok' && health.database === 'connected'
          ? 'Operacional'
          : 'Atenção'

      lastUpdate = new Date().toLocaleTimeString('pt-BR')
      errorMessage = ''
    } catch (error) {
      connectionStatus = 'Desconectado'
      errorMessage =
        error instanceof Error ? error.message : 'Falha ao consultar a API.'
    }
  }

  function isLeaderNode(teamId: number, nodeId: string) {
    return leaders.some(
      (leader) =>
        leader.active &&
        leader.team_id === teamId &&
        leader.node_id === nodeId,
    )
  }

  function isOfflineNode(teamId: number, nodeId: string) {
    return nodes.some(
      (node) =>
        node.team_id === teamId &&
        node.node_id === nodeId &&
        node.status === 'offline',
    )
  }

  function nodeMarkerStyle(location: NodeLocation): L.CircleMarkerOptions {
    const base = { color: '#ffffff', weight: 2, fillOpacity: 1 }

    if (isLeaderNode(location.team_id, location.node_id)) {
      return { ...base, radius: 12, fillColor: '#f5a524' }
    }
    if (isOfflineNode(location.team_id, location.node_id)) {
      return { ...base, radius: 8, fillColor: '#7b8794', fillOpacity: 0.8 }
    }
    return { ...base, radius: 9, fillColor: '#19c37d' }
  }

  function nodeKey(location: NodeLocation) {
    return `${location.team_id}-${location.node_id}`
  }

  function nodePopup(location: NodeLocation) {
    const leader = isLeaderNode(location.team_id, location.node_id)
    const offline = isOfflineNode(location.team_id, location.node_id)
    const role = leader
      ? 'Coordenador ativo'
      : offline
        ? 'Offline'
        : 'Seguidor'

    return `
      <strong>Aparelho ${shortNodeId(location.node_id)}</strong><br>
      ${formatTeam(location.team_id)} · ${role}<br>
      Latitude: ${location.latitude.toFixed(6)}<br>
      Longitude: ${location.longitude.toFixed(6)}<br>
      Precisão: ${location.accuracy.toFixed(1)} m<br>
      Bateria: ${location.battery}%<br>
      Registro: ${formatDate(location.recorded_at)}
    `
  }

  function showView(view: View) {
    activeView = view

    // O mapa da visão geral ficou oculto: o Leaflet precisa recalcular o
    // tamanho do contêiner quando ele volta a aparecer.
    if (view === 'overview') {
      window.setTimeout(() => map?.invalidateSize(), 0)
    }
  }

  function updateMap() {
    if (!map) return

    const seen = new Set<string>()
    let newDevice = false

    nodeLocations.forEach((location) => {
      const key = nodeKey(location)
      const position: L.LatLngExpression = [
        location.latitude,
        location.longitude,
      ]
      const style = nodeMarkerStyle(location)
      seen.add(key)

      const existing = nodeMarkers.get(key)

      if (existing) {
        existing.setLatLng(position)
        existing.setStyle(style)
        existing.setPopupContent(nodePopup(location))
        if (isLeaderNode(location.team_id, location.node_id)) {
          existing.bringToFront()
        }
        return
      }

      const marker = L.circleMarker(position, style).addTo(map!)
      marker.bindPopup(nodePopup(location))
      marker.bindTooltip(shortNodeId(location.node_id), {
        permanent: true,
        direction: 'top',
        offset: [0, -10],
        className: 'node-label',
      })
      nodeMarkers.set(key, marker)
      newDevice = true
    })

    nodeMarkers.forEach((marker, key) => {
      if (!seen.has(key)) {
        marker.remove()
        nodeMarkers.delete(key)
      }
    })


    if (nodeLocations.length > 0 && (!mapFitted || newDevice)) {
      const bounds = L.latLngBounds(
        nodeLocations.map((location) => [
          location.latitude,
          location.longitude,
        ]),
      )
      map.fitBounds(bounds, { padding: [50, 50], maxZoom: 16 })
      mapFitted = true
    }
  }

  onMount(() => {
    map = L.map(mapElement).setView([-14.235, -51.9253], 4)

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap contributors',
      maxZoom: 19,
    }).addTo(map)

    // Intervalo curto o bastante para acompanhar um failover (cerca de 15 s).
    refreshAll()
    const interval = window.setInterval(refreshAll, 5000)

    return () => {
      window.clearInterval(interval)
      map?.remove()
      map = undefined
    }
  })
</script>

<svelte:head>
  <title>RescueNet | Central de Operações</title>
  <meta
    name="description"
    content="Painel de monitoramento de equipes RescueNet"
  />
</svelte:head>

<div class="dashboard">
  <aside class="sidebar">
    <div class="brand">
      <div class="brand-symbol">R</div>
      <div>
        <strong>RescueNet</strong>
        <span>GESTÃO DE OPERAÇÕES</span>
      </div>
    </div>

    <div class="nav-label">MENU PRINCIPAL</div>
    <button
      type="button"
      class="nav-item"
      class:active={activeView === 'overview'}
      on:click={() => showView('overview')}
    >
      <span class="nav-icon">▦</span>
      Visão geral
    </button>
    <button
      type="button"
      class="nav-item"
      class:active={activeView === 'monitoring'}
      on:click={() => showView('monitoring')}
    >
      <span class="nav-icon">⌖</span>
      Monitoramento
    </button>
    <div class="nav-item">
      <span class="nav-icon">◷</span>
      Histórico
    </div>

    <div class="sidebar-bottom">
      <div class="system-indicator">
        <span class:online={connectionStatus === 'Operacional'}></span>
        <div>
          <strong>Sistema RescueNet</strong>
          <small>{connectionStatus}</small>
        </div>
      </div>
      <small class="version">PROJETO ACADÊMICO · SPRINT 3</small>
    </div>
  </aside>

  <main class="main-content">
    <header class="topbar">
      <div>
        <div class="breadcrumb">
          RESCUENET / <strong>{activeView === 'overview' ? 'VISÃO GERAL' : 'MONITORAMENTO'}</strong>
        </div>
        <h1>{activeView === 'overview' ? 'Central de operações' : 'Monitoramento dos aparelhos'}</h1>
        <p class="subtitle">
          {activeView === 'overview'
            ? 'Acompanhamento e monitoramento das equipes em campo.'
            : 'Rastro de posições e regularidade dos envios de cada aparelho.'}
        </p>
      </div>

      <div class="topbar-right">
        <div class="connection-pill" class:disconnected={connectionStatus !== 'Operacional'}>
          <span></span>
          {connectionStatus}
        </div>
        <div class="update-info">Atualizado às {lastUpdate}</div>
      </div>
    </header>

    {#if errorMessage}
      <div class="error-banner">
        Não foi possível atualizar os dados: {errorMessage}
      </div>
    {/if}

    {#if activeView === 'monitoring'}
      <MonitoringPanel {nodeLocations} {leaders} {nodes} />
    {/if}

    <div class="view" hidden={activeView !== 'overview'}>
    <section class="stats-grid">
      <article class="stat-card">
        <div class="stat-heading">EQUIPES CADASTRADAS <span>▦</span></div>
        <div class="stat-value">{teams.length}</div>
        <div class="stat-description">Equipes registradas no sistema</div>
      </article>

      <article class="stat-card">
        <div class="stat-heading">EQUIPES COM LOCALIZAÇÃO <span>⌖</span></div>
        <div class="stat-value">{latestLocations.length}</div>
        <div class="stat-description">Com posição registrada no banco</div>
      </article>

      <article class="stat-card">
        <div class="stat-heading">BATERIA DA ÚLTIMA POSIÇÃO <span>▰</span></div>
        <div class="stat-value">
          {latestLocations.length > 0 ? `${latestLocations[0].battery}%` : '—'}
        </div>
        <div class="stat-description">Dado recebido da equipe</div>
      </article>

      <article class="stat-card">
        <div class="stat-heading">PRECISÃO DO GPS <span>◎</span></div>
        <div class="stat-value">
          {latestLocations.length > 0
            ? `${latestLocations[0].accuracy.toFixed(1)} m`
            : '—'}
        </div>
        <div class="stat-description">Precisão da última localização</div>
      </article>
    </section>

    <section class="map-section">
      <div class="section-heading">
        <div>
          <h2>Mapa de monitoramento</h2>
          <p>Última posição conhecida de cada aparelho.</p>
        </div>
        <div class="map-legend">
          <span></span> Aparelho
          <span class="leader"></span> Coordenador ativo
          <span class="offline"></span> Offline
        </div>
      </div>
      {#if nodeLocationsError}
        <div class="error-banner">
          Posição dos aparelhos indisponível: {nodeLocationsError}. Confira se o
          script backend/migrations/localizacao_por_no.sql foi aplicado no banco.
        </div>
      {/if}
      <div class="map-frame">
        <div class="map-container" bind:this={mapElement}></div>
        {#if nodeLocations.length === 0}
          <div class="map-empty">Aguardando a primeira localização dos aparelhos...</div>
        {/if}
      </div>
    </section>

    <section class="coordination-section">
      <div class="section-heading">
        <div>
          <h2>Coordenação da operação</h2>
          <p>Coordenador eleito por equipe e presença dos dispositivos (LWT).</p>
        </div>
        <span class="team-count">{onlineCount} DE {nodes.length} DISPOSITIVO(S) ONLINE</span>
      </div>

      {#if coordinationError}
        <div class="error-banner">
          Liderança e presença indisponíveis: {coordinationError}. Confira se o
          script backend/migrations/lideranca.sql foi aplicado no banco.
        </div>
      {/if}

      <div class="leader-grid">
        {#each leaders as leader (leader.team_id)}
          <article class="stat-card leader-card" class:inactive={!leader.active}>
            <div class="stat-heading">
              {formatTeam(leader.team_id).toUpperCase()}
              <span class="leader-status">
                {leader.active ? 'COORDENADOR ATIVO' : 'SEM LÍDER ATIVO'}
              </span>
            </div>
            <div class="stat-value" title={leader.node_id}>{shortNodeId(leader.node_id)}</div>
            <div class="stat-description">
              {leader.active
                ? `Época ${leader.epoch_counter}, eleito às ${formatTime(leader.elected_at)}`
                : `Último coordenador (época ${leader.epoch_counter}); aguardando nova eleição`}
            </div>
            <div class="stat-description">Último sinal às {formatTime(leader.last_seen_at)}</div>
          </article>
        {:else}
          <div class="empty-state">Nenhum coordenador anunciado até o momento.</div>
        {/each}
      </div>

      <SimulatorPanel />

      <div class="coordination-columns">
        <div>
          <h3>Dispositivos</h3>
          {#if nodes.length > 0}
            <div class="table-wrapper">
              <table>
                <thead>
                  <tr>
                    <th>EQUIPE</th>
                    <th>DISPOSITIVO</th>
                    <th>STATUS</th>
                    <th>PAPEL</th>
                    <th>DESDE</th>
                  </tr>
                </thead>
                <tbody>
                  {#each nodes as node (`${node.team_id}-${node.node_id}`)}
                    <tr>
                      <td>{formatTeam(node.team_id)}</td>
                      <td title={node.node_id}>
                        <strong>{shortNodeId(node.node_id)}</strong>
                        {#if simulatedIds.has(`${node.team_id}-${node.node_id}`)}
                          <span class="sim-tag">simulado</span>
                        {/if}
                      </td>
                      <td>
                        <span class="presence-badge" class:offline={node.status === 'offline'}>
                          {node.status === 'online' ? 'Online' : 'Offline'}
                        </span>
                      </td>
                      <td>
                        {isActiveLeader(node)
                          ? 'Coordenador'
                          : node.status === 'online'
                            ? 'Seguidor'
                            : '—'}
                      </td>
                      <td>{formatDate(node.received_at)}</td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            </div>
          {:else}
            <div class="empty-state">Nenhum dispositivo registrou presença.</div>
          {/if}
        </div>

        <div>
          <h3>Histórico de liderança</h3>
          {#if leaderHistory.length > 0}
            <div class="table-wrapper">
              <table>
                <thead>
                  <tr>
                    <th>HORÁRIO</th>
                    <th>EQUIPE</th>
                    <th>COORDENADOR</th>
                    <th>ÉPOCA</th>
                  </tr>
                </thead>
                <tbody>
                  {#each leaderHistory as event (event.id)}
                    <tr>
                      <td>{formatTime(event.received_at)}</td>
                      <td>{formatTeam(event.team_id)}</td>
                      <td title={event.node_id}><strong>{shortNodeId(event.node_id)}</strong></td>
                      <td>{event.epoch_counter}</td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            </div>
          {:else}
            <div class="empty-state">Nenhuma troca de coordenador registrada.</div>
          {/if}
        </div>
      </div>
    </section>

    <section class="teams-section">
      <div class="section-heading">
        <div>
          <h2>Equipes monitoradas</h2>
          <p>Informações da última posição recebida.</p>
        </div>
        <span class="team-count">{latestLocations.length} LOCALIZAÇÃO(ÕES)</span>
      </div>

      {#if latestLocations.length > 0}
        <div class="table-wrapper">
          <table>
            <thead>
              <tr>
                <th>EQUIPE</th>
                <th>LATITUDE</th>
                <th>LONGITUDE</th>
                <th>BATERIA</th>
                <th>PRECISÃO</th>
                <th>REGISTRO</th>
              </tr>
            </thead>
            <tbody>
              {#each latestLocations as location (location.team_id)}
                <tr>
                  <td><strong>Equipe {String(location.team_id).padStart(3, '0')}</strong></td>
                  <td>{location.latitude.toFixed(6)}</td>
                  <td>{location.longitude.toFixed(6)}</td>
                  <td>{location.battery}%</td>
                  <td>{location.accuracy.toFixed(1)} m</td>
                  <td>{formatDate(location.recorded_at)}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {:else}
        <div class="empty-state">Nenhuma localização registrada até o momento.</div>
      {/if}
    </section>
    </div>

    <footer>
      <span>RescueNet · Sistema de monitoramento de equipes de resgate</span>
      <span>Dados atualizados automaticamente a cada 5 segundos</span>
    </footer>
  </main>
</div>
