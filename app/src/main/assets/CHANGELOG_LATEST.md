### MAY Task Manager v1.0.7
- Correcao do monitor de consumo ao vivo: contagem precisa com tela desligada e em sono profundo via captura orientada a eventos.
- Otimizacao energetica estrita com pausa do loop periodico durante tela apagada, garantindo zero consumo extra de bateria.
- Sincronizacao de snapshots via Mutex para eliminar race condition e atribuicao indevida de consumo de sono profundo para tela ligada.
- Restauracao aprimorada de sessao de consumo entre reinicios do app, computando intervalos e mantendo historico intacto.
- Inicializacao automatica da notificacao persistente de consumo apos reinicio do dispositivo ou reinstalacao do pacote.
---
