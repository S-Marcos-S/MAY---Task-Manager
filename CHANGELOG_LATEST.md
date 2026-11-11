### MAY Task Manager v1.0.24
- Sanitizacao do nome de pacote base ao inspecionar processos e subprocessos (ex: sub-rotinas com separador de dois-pontos).
- Prevencao de falhas por NameNotFoundException no PackageManager durante inspecao detalhada com fallback resiliente.
- Otimizacao do script unificado de superusuario com eliminacao de chamadas lentas redundantes e fechamento seguro de descritores.
- Aumento do tempo limite de execucao de root na inspecao de processos para 12 segundos evitando timeouts indevidos.
- Preservacao do estado de inspecao na interface durante ciclos de atualizacao periodica em caso de oscilacao temporaria.
- Adicao de testes unitarios de regressao para reconhecimento de processos subordinados, tratamento de permissoes e telemetria.
---
