### MAY Task Manager v1.0.5
- Resolucao de race condition na inicializacao dos Ciclos de Bateria: carga sincronica de dados antes de escutar eventos de hardware.
- Gravacao atomica em disco para arquivos de ciclos (active_cycle.json e cycles.json), evitando corrupcao ou perda em encerramentos abruptos.
- Forcada persistencia sincronizada do ciclo ativo imediatamente antes de instalar atualizacoes via root ou pelo instalador do sistema.
---
