import { writable } from 'svelte/store'

// API do simulador de nós (simulator/), acessada pelo proxy /sim do Vite.

export type SimRole = 'IDLE' | 'ELECTING' | 'COORDINATOR' | 'FOLLOWER'
export type SimStatus = 'conectando' | 'online' | 'caido' | 'falha'

export type SimNode = {
  node_id: string
  team_id: number
  level: number
  battery: number
  score: number
  eligible: boolean
  status: SimStatus
  role: SimRole | null
  coordinator_node_id: string | null
  epoch_counter: number | null
  epoch_initiator_node_id: string | null
  known_candidates: number
  error: string | null
}

/** Nós simulados ativos, compartilhados entre o painel e a tabela de dispositivos. */
export const simNodes = writable<SimNode[]>([])

/** null enquanto não foi verificado; false quando o simulador está desligado. */
export const simAvailable = writable<boolean | null>(null)

/** Mesma regra do app (AdaptiveBullyPolicy). */
export const MIN_BATTERY = 15

/**
 * Pontuação de um nó simulado: o nível geral vale para todos os critérios,
 * exceto a bateria (pesos 0,30 + 0,20 + 0,15 + 0,05 + 0,05 = 0,75).
 */
export function simScore(level: number, battery: number): number {
  return Math.round((0.75 * level + 0.25 * battery) * 100) / 100
}

export function simRoleLabel(node: SimNode): string {
  if (node.status === 'caido') return 'Caído'
  if (node.status === 'conectando') return 'Conectando'
  if (node.status === 'falha') return 'Falha de conexão'

  switch (node.role) {
    case 'COORDINATOR':
      return 'Coordenador'
    case 'FOLLOWER':
      return 'Seguidor'
    case 'ELECTING':
      return 'Em eleição'
    case 'IDLE':
      return 'Descobrindo nós'
    default:
      return '—'
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(`/sim${path}`, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  if (response.status === 204) {
    return undefined as T
  }

  const contentType = response.headers.get('content-type') ?? ''
  if (!contentType.includes('application/json')) {
    throw new Error('Simulador indisponível.')
  }

  const data = await response.json()
  if (!response.ok) {
    throw new Error(data?.error ?? `O simulador respondeu ${response.status}.`)
  }

  return data as T
}

export async function refreshSimNodes(): Promise<void> {
  try {
    simNodes.set(await request<SimNode[]>('GET', '/nodes'))
    simAvailable.set(true)
  } catch {
    simNodes.set([])
    simAvailable.set(false)
  }
}

async function thenRefresh(action: Promise<unknown>): Promise<void> {
  await action
  await refreshSimNodes()
}

export const createSimNode = (level: number, battery: number) =>
  thenRefresh(request('POST', '/nodes', { level, battery }))

export const updateSimNode = (nodeId: string, level: number, battery: number) =>
  thenRefresh(request('PATCH', `/nodes/${nodeId}`, { level, battery }))

export const crashSimNode = (nodeId: string) =>
  thenRefresh(request('POST', `/nodes/${nodeId}/crash`))

export const reconnectSimNode = (nodeId: string) =>
  thenRefresh(request('POST', `/nodes/${nodeId}/reconnect`))

export const removeSimNode = (nodeId: string) =>
  thenRefresh(request('DELETE', `/nodes/${nodeId}`))

export const removeAllSimNodes = () => thenRefresh(request('DELETE', '/nodes'))
