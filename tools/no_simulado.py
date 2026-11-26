"""No RescueNet simulado, para testar a eleicao com um unico celular.

Publica HELLO a cada 5 s no topico de eleicao, mostra todo o trafego de
eleicao recebido e, opcionalmente, atua como coordenador ou responde OK.

Exemplos:
  python no_simulado.py --nivel 20                         # no fraco, passivo
  python no_simulado.py --nivel 100 --responder-ok         # no forte que aceita liderar
  python no_simulado.py --nivel 80 --coordenador --epoca 1 # coordenador "teimoso"
  python no_simulado.py --nivel 40 --coordenador --epoca 3 --histerese
                                                           # lider fraco que cede a um no melhor

Requer: pip install paho-mqtt
"""

import argparse
import json
import threading
import time

import paho.mqtt.client as mqtt

PESOS = {
    "connection_stability": 0.30,
    "battery": 0.25,
    "availability": 0.20,
    "connectivity": 0.15,
    "computational_resources": 0.05,
    "data_efficiency": 0.05,
}


def curto(node_id):
    texto = str(node_id)
    return texto if len(texto) <= 6 else "..." + texto[-6:]


def main():
    p = argparse.ArgumentParser(description="No RescueNet simulado")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--porta", type=int, default=1883)
    p.add_argument("--equipe", type=int, default=1)
    p.add_argument("--node-id", type=int, default=1)
    p.add_argument("--nivel", type=float, default=50.0,
                   help="valor de todas as metricas (0-100); a pontuacao fica igual a ele")
    p.add_argument("--coordenador", action="store_true",
                   help="anuncia COORDINATOR ao iniciar e envia HEARTBEAT a cada 5 s")
    p.add_argument("--epoca", type=int, default=1,
                   help="contador de epoca inicial (>= 1)")
    p.add_argument("--responder-ok", action="store_true",
                   help="responde OK a ELECTION e assume a coordenacao em epoca nova")
    p.add_argument("--histerese", action="store_true",
                   help="como coordenador, aplica a regra do app: se outro no tiver "
                        "10 pontos a mais em dois HELLOs seguidos, abre eleicao e cede")
    a = p.parse_args()

    if not 0 <= a.nivel <= 100:
        p.error("--nivel deve estar entre 0 e 100")
    if a.epoca < 1 or a.node_id < 1:
        p.error("--epoca e --node-id devem ser positivos")
    if a.nivel < 15:
        print("[aviso] bateria < 15: o no simulado sera inelegivel")

    topico = f"rescuenet/teams/{a.equipe}/election"
    score = round(sum(a.nivel * peso for peso in PESOS.values()), 2)
    estado = {"epoca": a.epoca, "coordenador": a.coordenador}
    conhecidos = set()   # nos que ja enviaram HELLO
    vantagens = {}       # no -> HELLOs seguidos com vantagem sobre este no
    trava = threading.Lock()

    cliente = mqtt.Client(
        mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"rescuenet-simulado-{a.node_id}",
    )

    def base(tipo, epoca=None):
        contador, iniciador = epoca or (estado["epoca"], a.node_id)
        return {
            "type": tipo,
            "team_id": a.equipe,
            "node_id": a.node_id,
            "epoch_counter": contador,
            "epoch_initiator_node_id": iniciador,
            "timestamp": int(time.time()),
        }

    def enviar(msg, mostrar=True):
        cliente.publish(topico, json.dumps(msg), qos=1)
        if mostrar:
            print(f"  >> {msg['type']:<11} epoca=({msg['epoch_counter']},{curto(msg['epoch_initiator_node_id'])})")

    def hello():
        msg = base("HELLO")
        msg.update({k: a.nivel for k in PESOS})
        msg["score"] = score
        enviar(msg, mostrar=False)

    def anunciar_coordenador():
        msg = base("COORDINATOR")
        msg["coordinator_node_id"] = a.node_id
        enviar(msg)

    def heartbeat():
        hb = base("HEARTBEAT")
        hb["coordinator_node_id"] = a.node_id
        enviar(hb, mostrar=False)

    def avaliar_desafiante(m):
        """Mesma regra do app (AdaptiveBullyPolicy): margem de 10 pontos,
        confirmada em 2 HELLOs seguidos, antes de abrir uma eleicao."""
        no = m["node_id"]
        desafiante = m.get("score") or 0
        if desafiante >= score + 10:
            vantagens[no] = vantagens.get(no, 0) + 1
            print(f"  -- {curto(no)} tem {desafiante - score:.2f} pontos a mais ({vantagens[no]}/2)")
            if vantagens[no] >= 2:
                vantagens.clear()
                estado["coordenador"] = False
                estado["epoca"] += 1
                print("  -- vantagem persistente: abrindo eleicao e deixando a coordenacao")
                enviar(base("ELECTION"))
        else:
            vantagens.pop(no, None)

    def ao_conectar(client, userdata, flags, reason_code, properties):
        print(f"[conectado] no={a.node_id} pontuacao={score} topico={topico}")
        client.subscribe(topico, qos=1)

    def ao_receber(client, userdata, message):
        try:
            m = json.loads(message.payload)
        except ValueError:
            print(f"[invalido] {message.payload!r}")
            return
        if m.get("node_id") == a.node_id:
            return

        tipo = m.get("type")
        epoca = (m.get("epoch_counter"), m.get("epoch_initiator_node_id"))
        extra = ""
        if tipo == "HELLO":
            extra = f"pontuacao={m.get('score')}"
        elif tipo == "OK":
            extra = f"para={curto(m.get('target_node_id'))}"
        print(f"{time.strftime('%H:%M:%S')} << {tipo:<11} de={curto(m.get('node_id'))} "
              f"epoca=({epoca[0]},{curto(epoca[1])}) {extra}")

        with trava:
            # Como o app: o coordenador se anuncia na hora para quem chega,
            # para o recem-chegado nao iniciar uma eleicao propria.
            if tipo == "HELLO" and m["node_id"] not in conhecidos:
                conhecidos.add(m["node_id"])
                if estado["coordenador"]:
                    hello()
                    heartbeat()
                    print(f"  -- no novo {curto(m['node_id'])}: HELLO e HEARTBEAT reenviados")

            if tipo == "HELLO" and a.histerese and estado["coordenador"]:
                avaliar_desafiante(m)

            # Sem --responder-ok/--histerese, o no e "teimoso" (nunca cede),
            # usado para reproduzir o split-brain.
            if not (a.responder_ok or a.histerese):
                return

            if tipo == "ELECTION" and a.responder_ok:
                ok = base("OK", epoca=epoca)
                ok["target_node_id"] = m["node_id"]
                enviar(ok)
                estado["epoca"] = max(estado["epoca"], epoca[0]) + 1
                estado["coordenador"] = True
                anunciar_coordenador()
            elif tipo == "COORDINATOR" and epoca > (estado["epoca"], a.node_id):
                if estado["coordenador"]:
                    print("  -- outro coordenador com epoca maior: deixando a coordenacao")
                else:
                    print(f"  -- novo coordenador: {curto(m['node_id'])}")
                estado["coordenador"] = False
                estado["epoca"] = epoca[0]

    cliente.on_connect = ao_conectar
    cliente.on_message = ao_receber
    cliente.connect(a.host, a.porta, keepalive=30)
    cliente.loop_start()

    try:
        time.sleep(1)
        hello()
        if estado["coordenador"]:
            time.sleep(1)
            anunciar_coordenador()
        while True:
            time.sleep(5)
            with trava:
                hello()
                if estado["coordenador"]:
                    heartbeat()
    except KeyboardInterrupt:
        print("\n[encerrado] o no simulado parou (simula queda)")
    finally:
        cliente.loop_stop()
        cliente.disconnect()


if __name__ == "__main__":
    main()
