### MAY Task Manager v1.0.22
- Separacao estrita de RX (download) e TX (upload) na amostragem de consumo de rede por aplicativo.
- Normalizacao proporcional por conservacao fisica para compatibilizar lotes atrasados do NetworkStatsManager com o trafego total do dispositivo.
- Descarte imediato de velocidades acumuladas quando o dispositivo estiver ocioso, eliminando picos fantasmas.
- Indicacao direcional de trafego (download e upload) na lista de aplicativos ativos da notificacao.
- Correcao da opcao de resetar estatisticas de bateria ao conectar o carregador com sincronizacao atomica e invalidacao de snapshots obsoletos.
- Supressao de falsos positivos de CPU e cancelamento de notificacao de anomalia quando o app esta visivel em primeiro plano.
- Protecao de tempo limite (timeout) com destruicao forcada de processos em comandos de root para evitar travamento de corrotinas.
- Prevencao de colisoes em reciclagem de PID via chave composta de identidade com tempo de execucao.
---
