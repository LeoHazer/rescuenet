<script lang="ts">
  import { onMount } from 'svelte'
  import L from 'leaflet'

  // Aba "Monitoramento": rastro e histórico de envios de cada aparelho.
  // Os dados de última posição, liderança e presença vêm do App; o histórico
  // (GET /api/nodes/{node_id}/locations) só é consultado com a aba aberta.

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

  type LeaderRef = { team_id: number; node_id: string; active: boolean }
  type PresenceRef = { team_id: number; node_id: string; status: string }
  type Role = 'leader' | 'offline' | 'online'

  export let nodeLocations: NodeLocation[] = []
  export let leaders: LeaderRef[] = []
  export let nodes: PresenceRef[] = []

  const TRAIL_POINTS = 30
  const TABLE_ROWS = 10
  // Intervalo acima do qual o envio é destacado como lacuna (o esperado é
  // 10 a 15 s).
  const GAP_SECONDS = 30

  const COLORS: Record<Role, string> = {
    leader: '#f5a524',
    offline: '#7b8794',
    online: '#19c37d',
  }

  let mapElement: HTMLDivElement
  let map: L.Map | undefined
  let trails: Record<string, NodeLocation[]> = {}
  let trailError = ''
  let selectedKey = ''
  let mapFitted = false
  const layers = new Map<string, L.LayerGroup>()

  function nodeKey(location: { team_id: number; node_id: string }) {
    return `${location.team_id}-${location.node_id}`
  }

  // Mesmo formato curto do aplicativo e da visão geral: os 6 últimos dígitos.
  function shortNodeId(nodeId: string) {
    return nodeId.length <= 6 ? nodeId : `…${nodeId.slice(-6)}`
  }

  function formatTime(value: string) {
    return new Date(value).toLocaleTimeString('pt-BR')
  }

  function roleOf(location: { team_id: number; node_id: string }): Role {
    const same = (item: { team_id: number; node_id: string }) =>
      item.team_id === location.team_id && item.node_id === location.node_id

    if (leaders.some((leader) => leader.active && same(leader))) return 'leader'
    if (nodes.some((node) => node.status === 'offline' && same(node))) {
      return 'offline'
    }
    return 'online'
  }

  function roleLabel(role: Role) {
    return role === 'leader'
      ? 'Coordenador'
      : role === 'offline'
        ? 'Offline'
        : 'Seguidor'
  }

  // Distância em metros entre dois pontos (fórmula de haversine).
  function distanceMeters(a: NodeLocation, b: NodeLocation) {
    const toRad = Math.PI / 180
    const dLat = (b.latitude - a.latitude) * toRad
    const dLon = (b.longitude - a.longitude) * toRad
    const h =
      Math.sin(dLat / 2) ** 2 +
      Math.cos(a.latitude * toRad) *
        Math.cos(b.latitude * toRad) *
        Math.sin(dLon / 2) ** 2
    return 2 * 6371000 * Math.asin(Math.sqrt(h))
  }

  // Linhas da tabela: da mais recente para a mais antiga, comparando cada
  // envio com o anterior a ele.
  $: selectedTrail = trails[selectedKey] ?? []
  $: rows = selectedTrail.slice(0, TABLE_ROWS).map((point, index) => {
    const previous = selectedTrail[index + 1]
    const interval = previous
      ? Math.round(
          (Date.parse(point.recorded_at) - Date.parse(previous.recorded_at)) /
            1000,
        )
      : null
    return {
      point,
      interval,
      distance: previous ? distanceMeters(previous, point) : null,
      gap: interval !== null && interval > GAP_SECONDS,
    }
  })

  async function fetchTrail(nodeId: string) {
    const response = await fetch(
      `/api/nodes/${nodeId}/locations?limit=${TRAIL_POINTS}`,
    )
    if (!response.ok) {
      throw new Error(`histórico respondeu ${response.status}`)
    }
    return (await response.json()) as NodeLocation[]
  }

  async function refreshTrails() {
    const current = nodeLocations

    try {
      const lists = await Promise.all(
        current.map((location) => fetchTrail(location.node_id)),
      )
      trails = Object.fromEntries(
        current.map((location, index) => [nodeKey(location), lists[index]]),
      )
      trailError = ''

      if (!trails[selectedKey] && current.length > 0) {
        selectedKey = nodeKey(current[0])
      }

      drawTrails()
    } catch (error) {
      trailError =
        error instanceof Error ? error.message : 'Falha ao consultar o histórico.'
    }
  }

  function drawTrails() {
    if (!map) return

    Object.entries(trails).forEach(([key, trail]) => {
      if (trail.length === 0) return

      const latest = trail[0]
      const role = roleOf(latest)
      const color = COLORS[role]
      const selected = key === selectedKey
      const points: L.LatLngExpression[] = trail
        .slice()
        .reverse()
        .map((point) => [point.latitude, point.longitude])

      let group = layers.get(key)
      if (!group) {
        group = L.layerGroup().addTo(map!)
        layers.set(key, group)
      }
      group.clearLayers()

      L.polyline(points, {
        color,
        weight: selected ? 4 : 2,
        opacity: selected ? 0.9 : 0.4,
        interactive: false,
      }).addTo(group)

      // Posições anteriores: pontos pequenos, para ver o espalhamento.
      trail.slice(1).forEach((point) => {
        L.circleMarker([point.latitude, point.longitude], {
          radius: 3,
          color,
          weight: 1,
          fillColor: color,
          fillOpacity: selected ? 0.8 : 0.4,
          interactive: false,
        }).addTo(group!)
      })

      L.circleMarker([latest.latitude, latest.longitude], {
        radius: role === 'leader' ? 11 : 8,
        color: '#ffffff',
        weight: 2,
        fillColor: color,
        fillOpacity: 1,
      })
        .bindTooltip(shortNodeId(latest.node_id), {
          permanent: true,
          direction: 'top',
          offset: [0, -9],
          className: 'node-label',
        })
        .on('click', () => selectNode(key, false))
        .addTo(group)
    })

    layers.forEach((group, key) => {
      if (!trails[key]) {
        group.remove()
        layers.delete(key)
      }
    })

    // Enquadra só na primeira carga; depois, o zoom fica com o usuário.
    if (!mapFitted) {
      const all = Object.values(trails).flat()
      if (all.length > 0) {
        map.fitBounds(
          L.latLngBounds(all.map((point) => [point.latitude, point.longitude])),
          { padding: [40, 40], maxZoom: 18 },
        )
        mapFitted = true
      }
    }
  }

  // Selecionar pela lista centraliza o mapa no rastro do aparelho.
  function selectNode(key: string, focus = true) {
    selectedKey = key
    drawTrails()

    const trail = trails[key] ?? []
    if (focus && map && trail.length > 0) {
      map.fitBounds(
        L.latLngBounds(trail.map((point) => [point.latitude, point.longitude])),
        { padding: [40, 40], maxZoom: 19 },
      )
    }
  }

  onMount(() => {
    map = L.map(mapElement).setView([-14.235, -51.9253], 4)

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap contributors',
      maxZoom: 19,
    }).addTo(map)

    void refreshTrails()
    const interval = window.setInterval(() => void refreshTrails(), 5000)

    return () => {
      window.clearInterval(interval)
      layers.clear()
      map?.remove()
      map = undefined
    }
  })
</script>

<section class="map-section">
  <div class="section-heading">
    <div>
      <h2>Rastro dos aparelhos</h2>
      <p>
        Últimas {TRAIL_POINTS} posições de cada aparelho. Clique em um aparelho
        para ver os envios.
      </p>
    </div>
    <div class="map-legend">
      <span></span> Aparelho
      <span class="leader"></span> Coordenador ativo
      <span class="offline"></span> Offline
      <span class="previous"></span> Posições anteriores
    </div>
  </div>

  {#if trailError}
    <div class="error-banner">Histórico indisponível: {trailError}.</div>
  {/if}

  <div class="map-frame monitoring-map">
    <div class="map-container" bind:this={mapElement}></div>
    {#if nodeLocations.length === 0}
      <div class="map-empty">Aguardando a primeira localização dos aparelhos...</div>
    {/if}
  </div>
</section>

<section class="teams-section">
  <div class="section-heading">
    <div>
      <h2>Envios do aparelho</h2>
      <p>
        Esperado: um envio a cada 10 a 15 s. Intervalos acima de {GAP_SECONDS} s
        ficam destacados.
      </p>
    </div>
  </div>

  {#if nodeLocations.length > 0}
    <div class="device-list">
      {#each nodeLocations as location (nodeKey(location))}
        {@const role = roleOf(location)}
        <button
          type="button"
          class="device-chip"
          class:selected={nodeKey(location) === selectedKey}
          on:click={() => selectNode(nodeKey(location))}
          title={location.node_id}
        >
          <span class="dot" style:background={COLORS[role]}></span>
          {shortNodeId(location.node_id)}
          <small>{roleLabel(role)} · {formatTime(location.recorded_at)}</small>
        </button>
      {/each}
    </div>
  {/if}

  {#if rows.length > 0}
    <div class="table-wrapper">
      <table>
        <thead>
          <tr>
            <th>HORÁRIO</th>
            <th>INTERVALO</th>
            <th>DESLOCAMENTO</th>
            <th>PRECISÃO</th>
            <th>BATERIA</th>
          </tr>
        </thead>
        <tbody>
          {#each rows as row (row.point.id)}
            <tr class:gap={row.gap}>
              <td><strong>{formatTime(row.point.recorded_at)}</strong></td>
              <td>
                {row.interval === null ? '—' : `${row.interval} s`}
                {#if row.gap}<span class="gap-badge">lacuna</span>{/if}
              </td>
              <td>{row.distance === null ? '—' : `${row.distance.toFixed(1)} m`}</td>
              <td>{row.point.accuracy.toFixed(1)} m</td>
              <td>{row.point.battery}%</td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
    <p class="hint">
      Deslocamentos menores que a precisão do GPS costumam ser ruído, não
      movimento real.
    </p>
  {:else}
    <div class="empty-state">Nenhum envio registrado para este aparelho.</div>
  {/if}
</section>

<style>
  .monitoring-map {
    height: 460px;
  }

  .map-legend span.previous {
    width: 5px;
    height: 5px;
    background: #19c37d;
    opacity: 0.6;
    margin-left: 6px;
  }

  .device-list {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-bottom: 16px;
  }

  .device-chip {
    display: flex;
    align-items: center;
    gap: 8px;
    background: #17263a;
    border: 1px solid #293a50;
    border-radius: 8px;
    color: #f0f4fa;
    font: inherit;
    font-size: 12px;
    font-weight: 600;
    padding: 8px 12px;
    cursor: pointer;
  }

  .device-chip.selected {
    border-color: #4c7bd9;
    background: #1d3150;
  }

  .device-chip small {
    color: #8192a9;
    font-weight: 400;
  }

  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
  }

  tr.gap td {
    background: rgba(245, 165, 36, 0.08);
  }

  .gap-badge {
    margin-left: 6px;
    padding: 2px 6px;
    border-radius: 10px;
    background: rgba(245, 165, 36, 0.18);
    color: #f5c46b;
    font-size: 9px;
    font-weight: 700;
    text-transform: uppercase;
  }

  .hint {
    color: #8192a9;
    font-size: 11px;
    margin-top: 12px;
  }
</style>
