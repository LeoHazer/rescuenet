<script lang="ts">
  import { onMount } from 'svelte'
  import L from 'leaflet'
  import 'leaflet/dist/leaflet.css'

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
  let markers: L.CircleMarker[] = []

  function formatDate(value: string) {
    if (!value) return 'Não disponível'
    return new Date(value).toLocaleString('pt-BR')
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
      updateMap()
    } catch (error) {
      connectionStatus = 'Desconectado'
      errorMessage =
        error instanceof Error ? error.message : 'Falha ao consultar a API.'
    }
  }

  function updateMap() {
    if (!map) return

    markers.forEach((marker) => marker.remove())
    markers = []

    latestLocations.forEach((location) => {
      const marker = L.circleMarker(
        [location.latitude, location.longitude],
        {
          radius: 9,
          color: '#ffffff',
          weight: 2,
          fillColor: '#19c37d',
          fillOpacity: 1,
        },
      ).addTo(map!)

      marker.bindPopup(`
        <strong>Equipe ${location.team_id}</strong><br>
        Latitude: ${location.latitude}<br>
        Longitude: ${location.longitude}<br>
        Precisão: ${location.accuracy} m<br>
        Bateria: ${location.battery}%<br>
        Registro: ${formatDate(location.recorded_at)}
      `)

      markers.push(marker)
    })

    if (latestLocations.length > 0) {
      const bounds = L.latLngBounds(
        latestLocations.map((location) => [
          location.latitude,
          location.longitude,
        ]),
      )
      map.fitBounds(bounds, { padding: [50, 50], maxZoom: 15 })
    }
  }

  onMount(() => {
    map = L.map(mapElement).setView([-14.235, -51.9253], 4)

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap contributors',
      maxZoom: 19,
    }).addTo(map)

    void refreshData()
    const interval = window.setInterval(() => void refreshData(), 10000)

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
    <div class="nav-item active">
      <span class="nav-icon">▦</span>
      Visão geral
    </div>
    <div class="nav-item">
      <span class="nav-icon">⌖</span>
      Monitoramento
    </div>
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
        <div class="breadcrumb">RESCUENET / <strong>VISÃO GERAL</strong></div>
        <h1>Central de operações</h1>
        <p class="subtitle">Acompanhamento e monitoramento das equipes em campo.</p>
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
          <p>Última posição conhecida de cada equipe.</p>
        </div>
        <div class="map-legend"><span></span> Localização registrada</div>
      </div>
      <div class="map-frame">
        <div class="map-container" bind:this={mapElement}></div>
        {#if latestLocations.length === 0}
          <div class="map-empty">Aguardando a primeira localização das equipes...</div>
        {/if}
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

    <footer>
      <span>RescueNet · Sistema de monitoramento de equipes de resgate</span>
      <span>Dados atualizados automaticamente a cada 10 segundos</span>
    </footer>
  </main>
</div>
