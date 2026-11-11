### MAY Task Manager v1.0.26
- Correcao do calculo de CPU dos processos em tempo real estabilizando a chave de rastreamento delta de CPU por PID e comando.
- Implementacao de parser dinamico baseado em cabecalho do comando ps para compatibilidade universal com variantes toybox e procps.
- Correcao da execucao do comando top na inspecao de aplicativos removendo a opcao invalida -q e preservando a medicao de CPU retornada por ps.
- Atualizacao do BackgroundAnomalyWatcher para leitura confiavel de processos em segundo plano sem perda de historico.
- Adicao de testes unitarios de regressao para calculo delta de CPU, compatibilidade de colunas e protecao contra reciclagem de PID.
---
